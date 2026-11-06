// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Grabación manual de una llamada (2.5, orden del director). Solo existe si la orden la pide (`--es grabar 1` o el
 * interruptor de la pantalla, que arranca apagado y no se guarda); si no, la app no escribe nada. En
 * `<files>/llamadas/<fecha_hora>/` deja tres archivos nuevos:
 *   transcripcion.txt   las transcripciones de Gemini por turno, con el desfase desde el inicio y la hora del cel;
 *   microfono_16k.wav   lo que capta el micrófono del cel (16 kHz mono PCM16) desde que empieza la conversación;
 *   isabella_24k.wav    la voz de Isabella al entrar a la bocina (24 kHz mono PCM16).
 * Las dos pistas empiezan en +0.0 s y se rellenan con silencio según el reloj, para oírlas juntas. Escribe un hilo
 * propio («graba-llamada»): la captura, la bocina y el lector del socket solo encolan y nunca esperan al disco. Si el
 * disco no da abasto, lo que no cabe en la cola (4 MB) se tira y se avisa una vez. Nunca borra ni pisa nada: si la
 * carpeta ya existe, usa otra con sufijo. Las cabeceras WAV se reescriben cada 2 s, así que las pistas se pueden oír
 * aunque el proceso muera a media llamada. El número marcado no se escribe. Java puro: se prueba en la Mac.
 */
final class Grabacion {
    /** Turno de contexto para Isabella (clientContent sin turnComplete): lo dice al presentarse, sin hablar antes. */
    static final String TEXTO_AVISO = "Sistema: esta llamada se está grabando; avísalo al presentarte.";
    /** Respaldo (clientContent CON turnComplete): terminó un turno sin decirlo; se pide una sola vez. */
    static final String SISTEMA_RECORDATORIO = "Sistema: no has dicho que esta llamada se está grabando. Dilo ahora en una frase corta y sigue con la conversación.";
    /** La grabación no abrió después de avisarle a Isabella: que no lo diga. */
    static final String TEXTO_SIN_GRABAR = "Sistema: corrección: al final esta llamada NO se está grabando; no menciones ninguna grabación.";
    static final String CARPETA = "llamadas";
    static final String ARCHIVO_MIC = "microfono_16k.wav", ARCHIVO_VOZ = "isabella_24k.wav", ARCHIVO_TEXTO = "transcripcion.txt";
    static final int RATE_MIC = 16_000, RATE_VOZ = 24_000;
    static final long RELLENO_MIN_MS = 100;          // menos que esto es el vaivén normal de las tramas: no se rellena
    static final long MAX_RELLENO_S = 600;           // silencio máximo por trozo (la llamada dura ≤ 6 min: no se alcanza)
    static final long MAX_EN_COLA = 4L * 1024 * 1024; // bytes de audio esperando al disco (≈ 50 s de las dos pistas)
    static final long CABECERA_CADA_MS = 2000;
    static final long ESPACIO_MINIMO = 100L * 1024 * 1024; // 5 min de llamada son ≈ 25 MB

    /** Reloj monótono en ms: en el cel, System.nanoTime(); en las pruebas, uno falso. */
    interface Reloj {
        long ms();
    }

    static final Reloj RELOJ_SISTEMA = () -> System.nanoTime() / 1_000_000L;

    private static final int MIC = 1, VOZ = 2, TEXTO = 3, FIN_TURNO = 4, SISTEMA = 5, CERRAR = 6;

    private static final class Paso {
        final int tipo;
        final long ms;
        final byte[] pcm;
        final String quien, texto;
        final boolean interrumpida;

        Paso(int tipo, long ms, byte[] pcm, String quien, String texto, boolean interrumpida) {
            this.tipo = tipo;
            this.ms = ms;
            this.pcm = pcm;
            this.quien = quien;
            this.texto = texto;
            this.interrumpida = interrumpida;
        }
    }

    final File carpeta;
    private final Reloj reloj;
    private final long t0, t0Pared, maxEnCola;
    private final LinkedBlockingQueue<Paso> cola = new LinkedBlockingQueue<>();
    private final AtomicLong bytesEnCola = new AtomicLong(), bytesTirados = new AtomicLong();
    private final AtomicBoolean cerrada = new AtomicBoolean();
    private final CountDownLatch terminado = new CountDownLatch(1);
    private volatile boolean rota; // falló el disco: ya no se escribe (lo escrito se queda)

    // Del hilo escritor (o de quien llame atenderPendiente en las pruebas):
    private final Wav mic, voz;
    private final Writer texto;
    private long muestrasMic, muestrasVoz, rellenoMic, rellenoVoz, ultimaCabecera;
    private String quienActual;
    private final StringBuilder linea = new StringBuilder();
    private long lineaDesde;
    private int lineas;
    private boolean finalizada, turnoIsabellaAbierto;

    /** Abre la grabación con su hilo escritor. `base` es la carpeta de archivos de la app. */
    static Grabacion abrir(File base, String encabezado, long ahoraPared) throws IOException {
        Grabacion g = abrir(base, encabezado, ahoraPared, RELOJ_SISTEMA, MAX_EN_COLA);
        Thread h = new Thread(g::bucle, "graba-llamada");
        h.setDaemon(true); // en la Mac no detiene la salida; en el cel da igual (cerrar() lo termina)
        h.start();
        return g;
    }

    /**
     * ¿Se puede grabar en `base`? null si sí; si no, el motivo. La app lo pregunta ANTES de avisarle a Isabella, para
     * no anunciar una grabación que no va a haber. Crea `llamadas/` si falta (nunca borra nada).
     */
    static String revisar(File base) {
        if (base == null) return "la app no tiene carpeta de archivos";
        File raiz = new File(base, CARPETA);
        if (!raiz.isDirectory() && !raiz.mkdirs()) return "no pude crear " + raiz.getPath();
        long libre = raiz.getUsableSpace();
        if (libre > 0 && libre < ESPACIO_MINIMO) return "quedan " + (libre >> 20) + " MB libres y pido " + (ESPACIO_MINIMO >> 20);
        return null;
    }

    /** ¿Isabella dijo algo de la grabación? («grabando», «grabación», «se graba»…) Sobre el texto del turno entero. */
    static boolean mencionaGrabacion(CharSequence texto) {
        return texto != null && texto.toString().toLowerCase(Locale.ROOT).contains("grab");
    }

    /** Sin hilo: para las pruebas, que la atienden con atenderPendiente(). */
    static Grabacion abrir(File base, String encabezado, long ahoraPared, Reloj reloj, long maxEnCola) throws IOException {
        String motivo = revisar(base);
        if (motivo != null) throw new IOException(motivo);
        File raiz = new File(base, CARPETA);
        String nombre = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date(ahoraPared));
        File carpeta = null;
        for (int k = 1; k <= 99 && carpeta == null; k++) {
            File c = new File(raiz, k == 1 ? nombre : nombre + "_" + k);
            if (c.mkdir()) carpeta = c; // mkdir falla si ya existe: nunca se pisa otra grabación
        }
        if (carpeta == null) throw new IOException("ya hay 99 carpetas " + nombre + " en " + raiz.getPath());
        return new Grabacion(carpeta, encabezado, ahoraPared, reloj, maxEnCola);
    }

    private Grabacion(File carpeta, String encabezado, long ahoraPared, Reloj reloj, long maxEnCola) throws IOException {
        this.carpeta = carpeta;
        this.reloj = reloj;
        this.t0 = reloj.ms();
        this.t0Pared = ahoraPared;
        this.maxEnCola = maxEnCola;
        this.ultimaCabecera = t0;
        Wav m = null, v = null;
        Writer w = null;
        try {
            m = new Wav(nuevo(ARCHIVO_MIC), RATE_MIC);
            v = new Wav(nuevo(ARCHIVO_VOZ), RATE_VOZ);
            w = new OutputStreamWriter(new FileOutputStream(nuevo(ARCHIVO_TEXTO)), StandardCharsets.UTF_8);
            m.cabecera(); // pistas válidas (vacías) desde el principio
            v.cabecera();
            escribirLinea(w, "Grabación de Isabella Llamadas · empezó el " + fechaHora(ahoraPared) + " (hora del cel).");
            if (encabezado != null && !encabezado.isEmpty()) escribirLinea(w, encabezado);
            escribirLinea(w, "Pistas: " + ARCHIVO_MIC + " (lo que capta el micrófono del cel) e " + ARCHIVO_VOZ
                    + " (la voz de Isabella al entrar a la bocina). Las dos empiezan en +0.0 s.");
            escribirLinea(w, "");
        } catch (IOException e) {
            cerrarCalladito(m, v, w); // lo que alcanzó a crear se queda (nunca se borra)
            throw e;
        }
        mic = m;
        voz = v;
        texto = w;
    }

    /** Ruta de un archivo que todavía no existe dentro de la carpeta nueva. */
    private String nuevo(String nombre) throws IOException {
        File f = new File(carpeta, nombre);
        if (f.exists()) throw new IOException(f.getPath() + " ya existe: no lo piso");
        return f.getPath();
    }

    // --- Lo que llaman los hilos de la llamada (nunca esperan al disco) ---

    /** Captura: trama PCM16 de 16 kHz recién leída. Se copia porque el búfer se reusa. */
    void microfono(byte[] pcm, int largo) {
        if (pcm == null || largo <= 0) return;
        encolarAudio(new Paso(MIC, reloj.ms(), Arrays.copyOf(pcm, largo), null, null, false));
    }

    /** Bocina: trozo de 24 kHz que está por entrar al AudioTrack. Nadie lo reusa: no se copia. */
    void isabella(byte[] pcm) {
        if (pcm == null || pcm.length == 0) return;
        encolarAudio(new Paso(VOZ, reloj.ms(), pcm, null, null, false));
    }

    /** Fragmento de transcripción de Gemini: `quien` es «isabella» o «interlocutor». */
    void texto(String quien, String fragmento) {
        if (quien == null || fragmento == null || fragmento.isEmpty()) return;
        encolar(new Paso(TEXTO, reloj.ms(), null, quien, fragmento, false));
    }

    /** Isabella terminó su turno (turnComplete) o la interrumpieron. */
    void finTurno(boolean interrumpida) {
        encolar(new Paso(FIN_TURNO, reloj.ms(), null, null, null, interrumpida));
    }

    /** Aviso «Sistema:» que la app le mandó a Isabella. */
    void sistema(String aviso) {
        if (aviso == null || aviso.isEmpty()) return;
        encolar(new Paso(SISTEMA, reloj.ms(), null, null, aviso, false));
    }

    /** Fin de la llamada: ya no acepta nada; el escritor termina lo pendiente, cierra las pistas y lo anota. No espera. */
    void cerrar() {
        if (cerrada.compareAndSet(false, true)) cola.offer(new Paso(CERRAR, reloj.ms(), null, null, null, false));
    }

    /** Espera a que el escritor cierre los archivos (pruebas y onDestroy). */
    boolean esperarCierre(long ms) throws InterruptedException {
        return terminado.await(ms, TimeUnit.MILLISECONDS);
    }

    long bytesTirados() {
        return bytesTirados.get();
    }

    private void encolarAudio(Paso p) {
        if (cerrada.get() || rota) return;
        long n = p.pcm.length;
        if (bytesEnCola.get() + n > maxEnCola) {
            if (bytesTirados.getAndAdd(n) == 0) {
                Bitacora.log("Grabación: el disco no da abasto; tiro audio de la grabación (aviso único; el total sale al cerrar).");
            }
            return;
        }
        bytesEnCola.addAndGet(n);
        cola.offer(p);
    }

    private void encolar(Paso p) {
        if (!cerrada.get() && !rota) cola.offer(p);
    }

    // --- Hilo escritor ---

    private void bucle() {
        try {
            while (true) {
                Paso p = cola.poll(500, TimeUnit.MILLISECONDS);
                if (p != null && p.tipo == CERRAR) break;
                if (p != null) atender(p);
                cabeceraSiToca();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            finalizar();
        }
    }

    /** Sin hilo (pruebas): atiende lo que haya en la cola. True si llegó el cierre y ya cerró los archivos. */
    boolean atenderPendiente() {
        Paso p;
        while ((p = cola.poll()) != null) {
            if (p.tipo == CERRAR) {
                finalizar();
                return true;
            }
            atender(p);
            cabeceraSiToca();
        }
        return false;
    }

    private void atender(Paso p) {
        if (p.pcm != null) bytesEnCola.addAndGet(-p.pcm.length);
        if (rota) return;
        try {
            switch (p.tipo) {
                case MIC: {
                    // La trama terminó de captarse en p.ms: empezó una trama antes.
                    long inicio = p.ms - (p.pcm.length / 2) * 1000L / RATE_MIC;
                    long r = rellenar(mic, RATE_MIC, inicio, muestrasMic);
                    muestrasMic += r;
                    rellenoMic += r;
                    mic.escribir(p.pcm, p.pcm.length);
                    muestrasMic += p.pcm.length / 2;
                    break;
                }
                case VOZ: {
                    long r = rellenar(voz, RATE_VOZ, p.ms, muestrasVoz);
                    muestrasVoz += r;
                    rellenoVoz += r;
                    voz.escribir(p.pcm, p.pcm.length);
                    muestrasVoz += p.pcm.length / 2;
                    break;
                }
                case TEXTO:
                    if (!p.quien.equals(quienActual)) {
                        volcar();
                        quienActual = p.quien;
                        lineaDesde = p.ms;
                    }
                    if ("isabella".equals(p.quien)) turnoIsabellaAbierto = true;
                    linea.append(p.texto);
                    break;
                case FIN_TURNO:
                    if ("isabella".equals(quienActual)) {
                        if (p.interrumpida) linea.append(" [interrumpida]");
                        volcar();
                    } else if (p.interrumpida && turnoIsabellaAbierto) {
                        // La voz del interlocutor llegó antes que el aviso de Google: la marca va en su propia línea.
                        volcar();
                        escribirMarca(p.ms, null, "[Isabella interrumpida]");
                    }
                    turnoIsabellaAbierto = false;
                    break;
                case SISTEMA:
                    volcar();
                    escribirMarca(p.ms, null, p.texto);
                    break;
                default:
                    break;
            }
        } catch (IOException e) {
            rota = true;
            Bitacora.log("Grabación: falló la escritura (" + e.getMessage() + "); dejo de grabar. Lo escrito hasta aquí se queda.");
        }
    }

    /** Silencio hasta donde debe empezar el trozo según el reloj; devuelve las muestras agregadas. */
    private long rellenar(Wav w, int rate, long inicioMs, long escritas) throws IOException {
        long objetivo = Math.max(0, (inicioMs - t0) * rate / 1000);
        long falta = objetivo - escritas;
        if (falta * 1000 < RELLENO_MIN_MS * rate) return 0;
        falta = Math.min(falta, MAX_RELLENO_S * rate);
        byte[] ceros = new byte[8192];
        long bytes = falta * 2;
        while (bytes > 0) {
            int n = (int) Math.min(ceros.length, bytes);
            w.escribir(ceros, n);
            bytes -= n;
        }
        return falta;
    }

    private void cabeceraSiToca() {
        long t = reloj.ms();
        if (rota || t - ultimaCabecera < CABECERA_CADA_MS) return;
        ultimaCabecera = t;
        try {
            mic.cabecera();
            voz.cabecera();
        } catch (IOException e) {
            rota = true;
            Bitacora.log("Grabación: falló la cabecera WAV (" + e.getMessage() + "); dejo de grabar. Lo escrito hasta aquí se queda.");
        }
    }

    /** Escribe la línea pendiente de transcripción. */
    private void volcar() throws IOException {
        String l = linea.toString().trim();
        String quien = quienActual;
        linea.setLength(0);
        quienActual = null;
        if (l.isEmpty() || quien == null) return;
        escribirMarca(lineaDesde, "isabella".equals(quien) ? "Isabella" : "Interlocutor", l);
    }

    private void escribirMarca(long ms, String quien, String texto) throws IOException {
        escribirLinea(String.format(Locale.US, "[+%.1f s · %s] ", (ms - t0) / 1000.0, MarcasLatencia.hora(t0Pared + (ms - t0)))
                + (quien == null ? "" : quien + ": ") + texto);
        lineas++;
    }

    private void escribirLinea(String l) throws IOException {
        escribirLinea(texto, l);
    }

    private static void escribirLinea(Writer w, String l) throws IOException {
        w.write(l);
        w.write('\n');
        w.flush();
    }

    private void finalizar() {
        if (finalizada) return;
        finalizada = true;
        cerrada.set(true); // si el escritor terminó por un error, los productores dejan de encolar
        double segMic = muestrasMic / (double) RATE_MIC, segVoz = muestrasVoz / (double) RATE_VOZ;
        if (!rota) {
            try {
                volcar();
                escribirLinea("");
                escribirLinea(String.format(Locale.US, "Fin de la grabación: +%.1f s. Micrófono %.1f s, Isabella %.1f s.",
                        (reloj.ms() - t0) / 1000.0, segMic, segVoz));
            } catch (IOException e) {
                Bitacora.log("Grabación: no pude escribir el final de la transcripción (" + e.getMessage() + ").");
            }
        }
        cerrarCalladito(mic, voz, texto);
        long tirados = bytesTirados.get();
        Bitacora.log(String.format(Locale.US,
                "Grabación cerrada en %s: micrófono %.1f s (%.1f s de silencio de relleno), Isabella %.1f s (%.1f s de relleno), %d %s de transcripción%s.",
                carpeta.getPath(), segMic, rellenoMic / (double) RATE_MIC, segVoz, rellenoVoz / (double) RATE_VOZ, lineas,
                lineas == 1 ? "línea" : "líneas",
                tirados > 0 ? "; se tiraron " + tirados + " bytes de audio porque el disco no daba abasto" : ""));
        terminado.countDown();
    }

    private static void cerrarCalladito(Wav m, Wav v, Writer w) {
        try {
            if (m != null) m.cerrar();
        } catch (IOException ignorado) {
            // se intenta cerrar lo demás
        }
        try {
            if (v != null) v.cerrar();
        } catch (IOException ignorado) {
            // ídem
        }
        try {
            if (w != null) w.close();
        } catch (IOException ignorado) {
            // ídem
        }
    }

    static String fechaHora(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }
}
