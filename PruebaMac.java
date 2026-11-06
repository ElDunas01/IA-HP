// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lado de la app, en la Mac: Tokens contra un Worker falso y SesionGemini + ClienteWebSocket contra mock-live.mjs. */
public class PruebaMac {
    static int fallas = 0, pasan = 0;

    static void comprueba(boolean ok, String que) {
        System.out.println((ok ? "PASA  " : "FALLA ") + que);
        if (ok) pasan++; else fallas++;
    }

    public static void main(String[] a) throws Exception {
        int puertoMock = Integer.parseInt(a[0]);
        int puertoWorker = Integer.parseInt(a[1]);
        System.out.println("AVISO25=" + SesionGemini.sistemaAviso(25));
        pruebaUnidades();
        pruebaArchivoSecreto(a.length > 2 ? a[2] : System.getProperty("java.io.tmpdir"));
        pruebaAjustes();
        pruebaRuta();
        pruebaConfirmacion();
        pruebaMarcas();
        pruebaHueco();
        pruebaGrabacion(a.length > 2 ? a[2] : System.getProperty("java.io.tmpdir"));
        pruebaTopeTimbre();
        pruebaTokens(puertoWorker);
        pruebaSesion(puertoMock);
        pruebaSinServidor();
        pruebaRedAtorada();
        int malas = 0;
        for (String l : Bitacora.copia()) if (l.contains("falso-") || l.contains("secreto-falso")) { malas++; System.out.println("  línea con secreto: " + l); }
        comprueba(malas == 0, "la bitácora no trae token ni secreto (" + Bitacora.copia().size() + " líneas revisadas)");
        System.out.println("RESUMEN JAVA: " + pasan + " pasan, " + fallas + " fallan");
        System.exit(fallas == 0 ? 0 : 1);
    }

    static void pruebaUnidades() {
        comprueba(SesionGemini.Uri_encode("auth_tokens/abc_-9").equals("auth_tokens/abc_-9"), "token en la URL con la diagonal tal cual (espec §3)");
        comprueba(SesionGemini.Uri_encode("auth_tokens/a+b=c").equals("auth_tokens/a%2Bb%3Dc"), "lo raro del token sí se codifica");
        comprueba(Tokens.urlDeToken("https://h.example").equals("https://h.example/token"), "Worker sin ruta → /token");
        comprueba(Tokens.urlDeToken("https://h.example/").equals("https://h.example/token"), "Worker con «/» → /token");
        comprueba(Tokens.urlDeToken("https://h.example/token").equals("https://h.example/token"), "Worker con /token se respeta");
        comprueba(Tokens.esperaSegunTabla(429, "tope_minuto", "17") == 17, "tabla: tope_minuto espera retry-after");
        comprueba(Tokens.esperaSegunTabla(429, "tope_minuto", "600") == 60, "tabla: tope_minuto, 60 s como máximo");
        comprueba(Tokens.esperaSegunTabla(429, "tope_dia", "3600") == -1, "tabla: tope_dia no se reintenta");
        comprueba(Tokens.esperaSegunTabla(502, "cuota_gemini", null) == -1, "tabla: cuota_gemini no se reintenta");
        comprueba(Tokens.esperaSegunTabla(502, "gemini_sin_respuesta", null) == 2, "tabla: gemini_sin_respuesta, 2 s");
        comprueba(Tokens.esperaSegunTabla(503, "topes_no_disponibles", null) == 2, "tabla: topes_no_disponibles, 2 s");
        comprueba(Tokens.esperaSegunTabla(503, "sin_llave", null) == -1, "tabla: sin_llave no se reintenta");
        comprueba(Tokens.esperaSegunTabla(404, "no_existe", null) == -1, "tabla: no_existe no se reintenta");
        // 2.4: el pedido lleva el techo y el modo en la URL (manda) y en el cuerpo (para el Worker viejo).
        comprueba(Tokens.urlConParametros("https://h.example", "es", 3, "servicios").equals("https://h.example/token?idioma=es&max_min=3&modo=servicios"),
                "pedido: idioma, max_min y modo en la URL");
        comprueba(Tokens.urlConParametros("https://h.example/token?x=1", "en", 9, "Flexible").equals("https://h.example/token?x=1&idioma=en&max_min=5&modo=flexible"),
                "pedido: con consulta previa usa «&», el techo se topa en 5 y el modo se normaliza");
        comprueba(Tokens.urlConParametros("https://h.example", "fr", 0, "otro").endsWith("?idioma=es&max_min=1&modo=servicios"),
                "pedido: idioma raro → es, techo 0 → 1, modo raro → servicios");
        comprueba(Tokens.cuerpoDelPedido("es", 4, "isabella").equals("{\"idioma\":\"es\",\"max_min\":4,\"modo\":\"isabella\"}"),
                "pedido: el mismo pedido en el cuerpo JSON");
        long ahora = System.currentTimeMillis();
        long fechaSrv = ahora - 7_000; // el reloj del Worker va 7 s atrás del cel
        String expira = java.time.Instant.ofEpochMilli(fechaSrv + 270_000).toString();
        long vence = Tokens.venceEnRelojDelCel(expira, fechaSrv, ahora);
        comprueba(vence == ahora + 269_000, "vida del token: se mide con el reloj del Worker (cabecera Date) y 1 s de colchón (" + (vence - ahora) + " ms)");
        comprueba(Tokens.venceEnRelojDelCel(expira, 0, ahora) == fechaSrv + 270_000, "vida del token: sin cabecera Date, el expira tal cual");
        comprueba(Tokens.venceEnRelojDelCel("", 0, ahora) == 0 && Tokens.venceEnRelojDelCel("mañana", fechaSrv, ahora) == 0, "vida del token: expira vacío o raro → 0 (no se sabe)");
        comprueba(Tokens.esperaSegunTabla(400, "max_min_invalido", null) == -1 && Tokens.esperaSegunTabla(500, "setup_faltante", null) == -1
                && Tokens.esperaSegunTabla(400, "modo_invalido", null) == -1 && Tokens.esperaSegunTabla(400, "idioma_invalido", null) == -1,
                "tabla: los errores nuevos del Worker no se reintentan");
        comprueba(Tokens.porQueNoReintento("max_min_invalido").startsWith("parámetro fuera de rango") && Tokens.porQueNoReintento("setup_faltante").contains("redesplegarlo"),
                "los errores nuevos traen su explicación en la bitácora");
        try {
            org.json.JSONArray d = new org.json.JSONArray()
                    .put(new org.json.JSONObject().put("modality", "TEXT").put("tokenCount", 4238))
                    .put(new org.json.JSONObject().put("modality", "AUDIO").put("tokenCount", 248));
            comprueba(SesionGemini.desglose(d).equals(" (texto 4238, audio 248)") && SesionGemini.desglose(null).isEmpty(),
                    "tokens por turno: desglose por modalidad:" + SesionGemini.desglose(d));
        } catch (org.json.JSONException e) {
            comprueba(false, "desglose: " + e);
        }
    }

    /** Ruta estricta (2.4): sin el appop no se marca, salvo el escape de laboratorio. */
    static void pruebaRuta() {
        comprueba(Ruta.ESTRICTA.equals(Ruta.decidir(true, false)), "ruta: con el appop, la estricta");
        comprueba(Ruta.ESTRICTA.equals(Ruta.decidir(true, true)), "ruta: con el appop, la estricta aunque pidan la clásica");
        comprueba(Ruta.NO_MARCAR.equals(Ruta.decidir(false, false)), "ruta: sin el appop y sin el escape, no se marca");
        comprueba(Ruta.CLASICA.equals(Ruta.decidir(false, true)), "ruta: sin el appop y con --es ruta_clasica 1, la clásica (aprox)");
    }

    /** Confirmación contra toques fantasma (2.4): dos toques en lugares distintos dentro de 3 s. */
    static void pruebaConfirmacion() {
        Confirmacion c = new Confirmacion();
        comprueba(c.confirmar(1000) == null, "confirmación: «Sí» sin nada armado no marca");
        comprueba(c.tocarBoton("llamar", 1000) && "llamar".equals(c.armado(1500)), "confirmación: el botón arma la orden");
        comprueba(c.restaMs(2000) == 2000, "confirmación: quedan 2 s a 1 s de armar (" + c.restaMs(2000) + ")");
        comprueba("llamar".equals(c.confirmar(3900)) && c.armado(3900) == null, "confirmación: «Sí» dentro de 3 s marca y desarma");
        c.tocarBoton("solo_marcar", 10_000);
        comprueba(c.confirmar(13_001) == null, "confirmación: «Sí» a los 3.001 s ya no marca");
        c.tocarBoton("llamar", 20_000);
        comprueba(!c.tocarBoton("llamar", 20_150) && c.armado(20_200) == null && c.confirmar(20_300) == null,
                "confirmación: un segundo toque en el MISMO botón cancela (ráfaga de fantasmas en un punto)");
        c.tocarBoton("llamar", 30_000);
        comprueba(c.tocarBoton("solo_marcar", 30_500) && "solo_marcar".equals(c.confirmar(31_000)), "confirmación: tocar el otro botón cambia la orden armada");
        c.tocarBoton("llamar", 40_000);
        comprueba(c.tocarBoton("llamar", 43_500), "confirmación: tocar el mismo botón después de vencer vuelve a armar");
        c.cancelar();
        comprueba(c.armado(43_600) == null && c.confirmar(43_700) == null, "confirmación: cancelar desarma");
        comprueba(Confirmacion.numeroLegible("5500000000").equals("55 0000 0000") && Confirmacion.numeroLegible("123").equals("123"),
                "confirmación: el número se muestra como 55 0000 0000");
    }

    /** Marcas de latencia (2.4): fin de la voz local → primer audio de Isabella → bocina, por turno. */
    static void pruebaMarcas() {
        MarcasLatencia m = new MarcasLatencia(300);
        long t = 1_000_000;
        m.alEnviarTrama(t, 800);
        m.alEnviarTrama(t + 20, 1500);
        m.alEnviarTrama(t + 40, 300);  // justo en el umbral: todavía es voz
        m.alEnviarTrama(t + 60, 299);  // debajo del umbral: no mueve el fin de la voz
        m.alEnviarTrama(t + 80, 120);  // silencio
        java.util.List<String> l = m.alPrimerAudio(t + 40 + 1384);
        comprueba(l.size() == 2 && l.get(0).equals("Voz local: fin a las " + MarcasLatencia.hora(t + 40) + " (turno 1; RMS 300, umbral 300)."),
                "marcas: «Voz local: fin» con la última trama en o sobre el umbral (" + l + ")");
        comprueba(l.get(1).equals("Isabella: primer audio del turno (+1384 ms desde el fin de la voz local)."),
                "marcas: «Isabella: primer audio del turno (+1384 ms desde el fin de la voz local)»");
        byte[] primero = new byte[100], otro = new byte[100];
        m.primerTrozo(primero);
        m.primerTrozo(otro); // solo cuenta el primero del turno
        comprueba(m.alEscribirBocina(otro, t + 1500) == null, "marcas: la bocina no marca un trozo que no es el primero");
        String b = m.alEscribirBocina(primero, t + 40 + 1384 + 35);
        comprueba("Bocina: primer trozo del turno escrito (+35 ms desde que llegó).".equals(b), "marcas: la bocina marca el primer trozo (" + b + ")");
        comprueba(m.alEscribirBocina(primero, t + 2000) == null, "marcas: la bocina marca una sola vez por turno");
        java.util.List<String> l2 = m.alPrimerAudio(t + 9000);
        comprueba(l2.size() == 1 && l2.get(0).contains("sin voz local antes"), "marcas: un turno sin voz local nueva lo dice (" + l2 + ")");
        m.alEnviarTrama(t + 10_000, 5000);
        m.alFinDeVozGemini(t + 10_600);
        java.util.List<String> l3 = m.alPrimerAudio(t + 11_000);
        comprueba(l3.size() == 2 && l3.get(1).startsWith("Isabella: primer audio del turno (+1000 ms desde el fin de la voz local) · +400 ms desde el fin de voz que marcó Gemini"),
                "marcas: también la distancia al ACTIVITY_END de Gemini (" + l3.get(1) + ")");
        comprueba(MarcasLatencia.limitar(10) == 50 && MarcasLatencia.limitar(99_999) == 20_000 && new MarcasLatencia(0).umbral() == 50,
                "marcas: el umbral se topa entre 50 y 20000");
    }

    /** Hueco del servidor (2.4): más de 1 s sin mensajes a media respuesta, con la cuenta de los 300 ms siguientes. */
    static void pruebaHueco() {
        HuecoServidor h = new HuecoServidor();
        long t = 5_000_000;
        comprueba(h.alMensaje(t) == null && h.alMensaje(t + 3000) == null && h.cerrarSiVencio(t + 4000) == null,
                "hueco: fuera de una respuesta, el silencio de Google no cuenta");
        h.empiezaRespuesta();
        h.alMensaje(t + 3100);
        h.alMensaje(t + 3300);
        comprueba(h.alMensaje(t + 5600) == null, "hueco: 2.3 s sin mensajes a media respuesta abre un hueco");
        h.alMensaje(t + 5650);
        h.alMensaje(t + 5700);
        h.alMensaje(t + 5790);
        h.alMensaje(t + 5840);
        comprueba(h.cerrarSiVencio(t + 5850) == null, "hueco: la línea espera a que pasen los 300 ms");
        String linea = h.alMensaje(t + 6000); // ya fuera de la ventana
        comprueba(linea != null && linea.startsWith("Hueco del servidor: 2.3 s sin mensajes de Google a media respuesta; siguiente mensaje a las ")
                && linea.endsWith("; 4 más en los 300 ms siguientes."), "hueco: duración, hora del siguiente y 4 más en 300 ms (" + linea + ")");
        h.terminaRespuesta(); // generationComplete
        comprueba(h.alMensaje(t + 9000) == null && h.cerrarSiVencio(t + 9500) == null, "hueco: después de generationComplete el silencio es normal");
        h.empiezaRespuesta();
        h.alMensaje(t + 9600);
        h.alMensaje(t + 11_000);
        String porVigilante = h.cerrarSiVencio(t + 12_000);
        comprueba(porVigilante != null && porVigilante.contains("1.4 s") && porVigilante.endsWith("; 0 más en los 300 ms siguientes."),
                "hueco: el vigilante anota el hueco aunque no lleguen más mensajes (" + porVigilante + ")");
        h.alMensaje(t + 13_000);
        comprueba(h.vaciar() != null && h.vaciar() == null, "hueco: al cerrar la sesión sale lo pendiente, una vez");
        comprueba(h.alMensaje(t + 13_500) == null && h.alMensaje(t + 14_400) == null && h.vaciar() == null, "hueco: 0.9 s no es hueco");
    }

    static int le32(byte[] b, int i) {
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24;
    }

    /** ¿Cabecera WAV PCM16 mono a `rate` con `bytesPcm` de datos, y el archivo mide 44 + eso? */
    static boolean cabeceraWav(java.io.File f, int rate, int bytesPcm) throws Exception {
        byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
        return b.length == 44 + bytesPcm && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && le32(b, 4) == 36 + bytesPcm && b[8] == 'W' && b[12] == 'f' && le32(b, 24) == rate && le32(b, 28) == rate * 2
                && b[36] == 'd' && b[37] == 'a' && b[38] == 't' && b[39] == 'a' && le32(b, 40) == bytesPcm;
    }

    /** El tamaño de datos que dice la cabecera WAV (bytes 40–43). */
    static int tamDatos(java.io.File f) throws Exception {
        return le32(java.nio.file.Files.readAllBytes(f.toPath()), 40);
    }

    static byte[] lleno(int largo, int valor) {
        byte[] b = new byte[largo];
        java.util.Arrays.fill(b, (byte) valor);
        return b;
    }

    static java.util.List<String> lineas(java.io.File f) throws Exception {
        return java.nio.file.Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
    }

    static boolean hayLinea(java.util.List<String> ls, String exacta) {
        return ls.contains(exacta);
    }

    /**
     * Grabación de la llamada (2.5), sin hilo y con un reloj falso: relleno con silencio según el reloj, cabeceras WAV
     * (también a media llamada), transcripción unida por hablante, carpetas que nunca se pisan, desborde de la cola y el
     * turno de contexto con turnComplete:false. Luego una vuelta con el hilo escritor real.
     */
    static void pruebaGrabacion(String carpetaBase) throws Exception {
        java.io.File base = new java.io.File(carpetaBase, "grabaciones");
        final long[] reloj = {7_000_000};
        final long t0 = reloj[0];
        Grabacion.Reloj r = () -> reloj[0];
        long pared = 1_761_600_000_000L;
        String nombre = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(new java.util.Date(pared));
        comprueba(Grabacion.revisar(null) != null && Grabacion.revisar(base) == null && new java.io.File(base, "llamadas").isDirectory(),
                "grabación: revisar() sin carpeta da el motivo; con carpeta crea llamadas/ y dice que sí");
        Grabacion g = Grabacion.abrir(base, "Orden llamar · prueba en la Mac", pared, r, Grabacion.MAX_EN_COLA);
        java.io.File dir = new java.io.File(new java.io.File(base, "llamadas"), nombre);
        java.io.File fMic = new java.io.File(dir, Grabacion.ARCHIVO_MIC), fVoz = new java.io.File(dir, Grabacion.ARCHIVO_VOZ),
                fTexto = new java.io.File(dir, Grabacion.ARCHIVO_TEXTO);
        comprueba(g.carpeta.equals(dir) && cabeceraWav(fMic, 16_000, 0) && cabeceraWav(fVoz, 24_000, 0) && fTexto.isFile(),
                "grabación: carpeta " + nombre + " con las dos pistas vacías pero válidas y la transcripción");
        // Micrófono: 50 tramas de 20 ms seguidas (1.0 s), un hueco de 500 ms (se rellena) y luego 5 ms de vaivén (no).
        byte[] trama = lleno(640, 0x11);
        for (int k = 1; k <= 50; k++) {
            reloj[0] = t0 + 20L * k;
            g.microfono(trama, trama.length);
        }
        reloj[0] = t0 + 1520;
        g.microfono(trama, trama.length);
        reloj[0] = t0 + 1545;
        g.microfono(trama, 640);
        // Isabella: un trozo de 0.5 s a los 200 ms (relleno de 0.2 s), otro pegado y uno adelantado (sin relleno).
        byte[] trozo = lleno(24_000, 0x22);
        reloj[0] = t0 + 200;
        g.isabella(trozo);
        reloj[0] = t0 + 700;
        g.isabella(trozo);
        reloj[0] = t0 + 730;
        g.isabella(trozo);
        // Transcripción: fragmentos que se unen por hablante, un aviso «Sistema:» y dos interrupciones.
        reloj[0] = t0 + 100; g.texto("interlocutor", "¿Bue");
        reloj[0] = t0 + 150; g.texto("interlocutor", "no?");
        reloj[0] = t0 + 200; g.texto("isabella", " Hola, soy");
        reloj[0] = t0 + 300; g.texto("isabella", " Isabella; esta llamada se está grab");
        reloj[0] = t0 + 350; g.texto("isabella", "ando.");
        reloj[0] = t0 + 1200; g.finTurno(false);
        reloj[0] = t0 + 1300; g.sistema("Sistema: prueba de aviso.");
        reloj[0] = t0 + 1400; g.texto("isabella", " Te decía");
        reloj[0] = t0 + 1450; g.finTurno(true);
        reloj[0] = t0 + 1500; g.finTurno(false); // el turnComplete que sigue a la interrupción: no escribe nada
        reloj[0] = t0 + 1600; g.texto("isabella", " Otra");
        reloj[0] = t0 + 1640; g.texto("interlocutor", " Perdón");
        reloj[0] = t0 + 1660; g.finTurno(true);
        reloj[0] = t0 + 1900;
        comprueba(!g.atenderPendiente(), "grabación: sin cerrar, atenderPendiente() no cierra");
        comprueba(tamDatos(fMic) == 0 && fMic.length() > 44 && tamDatos(fVoz) == 0 && fVoz.length() > 44,
                "grabación: antes de 2 s las cabeceras siguen en 0 aunque ya hay audio (se reescriben cada 2 s)");
        reloj[0] = t0 + 2100;
        g.texto("interlocutor", " Sigo.");
        g.atenderPendiente();
        int micBytes = (16_000 + 8_000 + 320 + 320) * 2; // 50 tramas + 500 ms de relleno + 2 tramas
        int vozBytes = (4_800 + 3 * 12_000) * 2;         // 200 ms de relleno + 3 trozos de 0.5 s
        comprueba(cabeceraWav(fMic, 16_000, micBytes) && cabeceraWav(fVoz, 24_000, vozBytes),
                "grabación: a media llamada (pasados 2 s) las cabeceras ya dicen lo escrito: el WAV se oye aunque el proceso muera");
        byte[] mic = java.nio.file.Files.readAllBytes(fMic.toPath());
        boolean rellenoEnCeros = true, tramasIntactas = true;
        for (int i = 44 + 32_000; i < 44 + 48_000; i++) if (mic[i] != 0) rellenoEnCeros = false;
        for (int i = 44; i < 44 + 32_000; i++) if (mic[i] != 0x11) tramasIntactas = false;
        for (int i = 44 + 48_000; i < mic.length; i++) if (mic[i] != 0x11) tramasIntactas = false;
        comprueba(rellenoEnCeros && tramasIntactas, "grabación: el hueco de 500 ms del micrófono es silencio y las tramas quedan tal cual (5 ms de vaivén no se rellenan)");
        byte[] voz = java.nio.file.Files.readAllBytes(fVoz.toPath());
        boolean vozBien = true;
        for (int i = 44; i < 44 + 9_600; i++) if (voz[i] != 0) vozBien = false;
        for (int i = 44 + 9_600; i < voz.length; i++) if (voz[i] != 0x22) vozBien = false;
        comprueba(vozBien, "grabación: la voz de Isabella empieza con 0.2 s de silencio y luego sus tres trozos, sin huecos inventados");
        reloj[0] = t0 + 2500;
        g.cerrar();
        g.microfono(trama, 640); // después de cerrar: se ignora
        g.texto("isabella", "tarde");
        comprueba(g.atenderPendiente() && g.esperarCierre(10), "grabación: cerrar() deja cerrar al escritor y esperarCierre() lo confirma");
        comprueba(cabeceraWav(fMic, 16_000, micBytes) && cabeceraWav(fVoz, 24_000, vozBytes), "grabación: al cerrar, cabeceras finales y nada de lo que llegó tarde");
        java.util.List<String> ls = lineas(fTexto);
        String h = MarcasLatencia.hora(pared + 100);
        comprueba(ls.get(0).startsWith("Grabación de Isabella Llamadas · empezó el ") && hayLinea(ls, "Orden llamar · prueba en la Mac"),
                "transcripción: encabezado con la fecha y la orden");
        comprueba(hayLinea(ls, "[+0.1 s · " + h + "] Interlocutor: ¿Bueno?"), "transcripción: fragmentos unidos, desfase y hora del cel (" + h + ")");
        comprueba(hayLinea(ls, "[+0.2 s · " + MarcasLatencia.hora(pared + 200) + "] Isabella: Hola, soy Isabella; esta llamada se está grabando."),
                "transcripción: el turno de Isabella en una línea al terminar");
        comprueba(hayLinea(ls, "[+1.3 s · " + MarcasLatencia.hora(pared + 1300) + "] Sistema: prueba de aviso."), "transcripción: los avisos Sistema van sin nombre de hablante");
        comprueba(hayLinea(ls, "[+1.4 s · " + MarcasLatencia.hora(pared + 1400) + "] Isabella: Te decía [interrumpida]"), "transcripción: turno interrumpido marcado");
        int iOtra = ls.indexOf("[+1.6 s · " + MarcasLatencia.hora(pared + 1600) + "] Isabella: Otra");
        int iPerdon = ls.indexOf("[+1.6 s · " + MarcasLatencia.hora(pared + 1640) + "] Interlocutor: Perdón");
        int iMarca = ls.indexOf("[+1.7 s · " + MarcasLatencia.hora(pared + 1660) + "] [Isabella interrumpida]");
        comprueba(iOtra > 0 && iPerdon == iOtra + 1 && iMarca == iPerdon + 1,
                "transcripción: si la voz del interlocutor llega antes que el aviso de Google, la interrupción va en su propia línea (" + iOtra + "," + iPerdon + "," + iMarca + ")");
        comprueba(hayLinea(ls, "[+2.1 s · " + MarcasLatencia.hora(pared + 2100) + "] Interlocutor: Sigo.") && !String.join("\n", ls).contains("tarde"),
                "transcripción: lo pendiente se escribe al cerrar; lo que llega después no");
        comprueba(ls.get(ls.size() - 1).equals("Fin de la grabación: +2.5 s. Micrófono 1.5 s, Isabella 1.7 s."), "transcripción: cierre con la duración de las pistas (" + ls.get(ls.size() - 1) + ")");
        String cierre = null;
        for (String l : Bitacora.copia()) if (l.startsWith("Grabación cerrada en " + dir.getPath())) cierre = l;
        comprueba(cierre != null && cierre.contains("micrófono 1.5 s (0.5 s de silencio de relleno), Isabella 1.7 s (0.2 s de relleno), 8 líneas de transcripción.")
                && !cierre.contains("tiraron"), "bitácora: resumen al cerrar (" + cierre + ")");
        // Carpetas: misma hora → sufijo _2; una carpeta que ya existía (con algo adentro) queda intacta.
        long pared2 = pared + 60_000;
        String nombre2 = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(new java.util.Date(pared2));
        java.io.File previa = new java.io.File(new java.io.File(base, "llamadas"), nombre2);
        comprueba(previa.mkdir(), "grabación: preparo una carpeta que ya existe");
        java.nio.file.Files.write(new java.io.File(previa, "de-antes.txt").toPath(), "no me toques".getBytes("UTF-8"));
        long antes = fMic.length();
        Grabacion g2 = null, g3 = null;
        try {
            g2 = Grabacion.abrir(base, "", pared, r, Grabacion.MAX_EN_COLA);
            g3 = Grabacion.abrir(base, "", pared2, r, Grabacion.MAX_EN_COLA);
        } catch (java.io.IOException e) {
            System.out.println("  abrir() lanzó: " + e.getMessage());
        }
        comprueba(g2 != null && g3 != null && g2.carpeta.getName().equals(nombre + "_2") && g3.carpeta.getName().equals(nombre2 + "_2"),
                "grabación: nunca pisa una carpeta: misma hora → _2 y carpeta ajena → _2 ("
                + (g2 == null ? "-" : g2.carpeta.getName()) + ", " + (g3 == null ? "-" : g3.carpeta.getName()) + ")");
        comprueba(fMic.length() == antes && cabeceraWav(fMic, 16_000, micBytes) && previa.list().length == 1
                && "no me toques".equals(new String(java.nio.file.Files.readAllBytes(new java.io.File(previa, "de-antes.txt").toPath()), "UTF-8")),
                "grabación: la grabación anterior y la carpeta ajena siguen intactas");
        if (g2 != null) { g2.cerrar(); g2.atenderPendiente(); }
        if (g3 != null) { g3.cerrar(); g3.atenderPendiente(); }
        java.io.File noCarpeta = new java.io.File(carpetaBase, "no-es-carpeta.txt");
        java.nio.file.Files.write(noCarpeta.toPath(), new byte[]{1});
        boolean lanzo = false;
        try {
            Grabacion.abrir(noCarpeta, "", pared, r, Grabacion.MAX_EN_COLA);
        } catch (java.io.IOException e) {
            lanzo = true;
        }
        comprueba(lanzo && Grabacion.revisar(noCarpeta) != null, "grabación: si no se puede crear la carpeta, revisar() lo dice y abrir() lanza IOException");
        // Desborde: con una cola de 1000 bytes, la segunda trama ya no cabe; se avisa una vez y el total sale al cerrar.
        Grabacion g4 = Grabacion.abrir(base, "", pared + 120_000, r, 1000);
        g4.microfono(trama, 640);
        g4.microfono(trama, 640);
        g4.microfono(trama, 640);
        g4.atenderPendiente();
        g4.microfono(trama, 640); // ya hay lugar otra vez
        g4.cerrar();
        g4.atenderPendiente();
        int avisos = 0;
        String cierre4 = null;
        for (String l : Bitacora.copia()) {
            if (l.startsWith("Grabación: el disco no da abasto")) avisos++;
            if (l.startsWith("Grabación cerrada en " + g4.carpeta.getPath())) cierre4 = l;
        }
        comprueba(avisos == 1 && g4.bytesTirados() == 1280 && cabeceraWav(new java.io.File(g4.carpeta, Grabacion.ARCHIVO_MIC), 16_000, 1280)
                && cierre4 != null && cierre4.contains("se tiraron 1280 bytes"), "grabación: la cola llena tira audio, avisa una vez y lo suma al cerrar (" + avisos + ", " + cierre4 + ")");
        // El aviso para Isabella: clientContent sin turnComplete; los avisos de siempre siguen con turnComplete:true.
        String ctx = SesionGemini.jsonTurnoTexto(Grabacion.TEXTO_AVISO, false);
        comprueba(ctx.equals("{\"clientContent\":{\"turns\":[{\"role\":\"user\",\"parts\":[{\"text\":\"Sistema: esta llamada se está grabando; avísalo al presentarte.\"}]}],\"turnComplete\":false}}"),
                "contexto de grabación: clientContent con turnComplete:false (" + ctx + ")");
        org.json.JSONObject cc = new org.json.JSONObject(SesionGemini.jsonTurnoTexto("Sistema: x", true)).optJSONObject("clientContent");
        comprueba(cc != null && cc.optBoolean("turnComplete", false), "los avisos Sistema de siempre siguen con turnComplete:true");
        comprueba(Grabacion.mencionaGrabacion(" esta llamada se está grabando") && Grabacion.mencionaGrabacion("GRABACIÓN")
                && !Grabacion.mencionaGrabacion("hola, soy Isabella") && !Grabacion.mencionaGrabacion(null),
                "grabación: detecta cuándo Isabella menciona la grabación");
        // Con el hilo escritor de verdad (reloj del sistema): lo encolado llega entero y el hilo termina.
        Grabacion g5 = Grabacion.abrir(base, "", pared + 180_000);
        for (int k = 0; k < 10; k++) g5.microfono(trama, 640);
        g5.isabella(trozo);
        g5.texto("isabella", " Hola.");
        g5.finTurno(false);
        g5.cerrar();
        boolean cerro = g5.esperarCierre(3000);
        Thread.sleep(50);
        boolean vivo = false;
        for (Thread t : Thread.getAllStackTraces().keySet()) if (t.isAlive() && t.getName().equals("graba-llamada")) vivo = true;
        java.util.List<String> ls5 = lineas(new java.io.File(g5.carpeta, Grabacion.ARCHIVO_TEXTO));
        boolean hola = false;
        for (String l : ls5) if (l.endsWith("] Isabella: Hola.")) hola = true;
        java.io.File m5 = new java.io.File(g5.carpeta, Grabacion.ARCHIVO_MIC), v5 = new java.io.File(g5.carpeta, Grabacion.ARCHIVO_VOZ);
        // Con el reloj de verdad puede haber un poco de relleno si la Mac se tarda: se pide al menos lo encolado.
        comprueba(cerro && !vivo && tamDatos(m5) == m5.length() - 44 && tamDatos(m5) >= 6400
                && tamDatos(v5) == v5.length() - 44 && tamDatos(v5) >= 24_000 && hola,
                "grabación con su hilo: todo lo encolado queda en disco, cabeceras finales y el hilo «graba-llamada» termina");
    }

    /** importar_secreto: leer recortado, validar, sobrescribir con ceros del mismo tamaño y dejar vacío (sin borrar). */
    static void pruebaArchivoSecreto(String carpeta) throws Exception {
        java.io.File f = new java.io.File(carpeta, "secreto.txt");
        String contenido = "  abcdefghijklmnop-1234567890_XYZ\n";
        java.nio.file.Files.write(f.toPath(), contenido.getBytes("UTF-8"));
        long largo = f.length();
        String leido = ArchivoSecreto.leer(f);
        comprueba("abcdefghijklmnop-1234567890_XYZ".equals(leido), "secreto.txt: se lee recortado");
        comprueba(ArchivoSecreto.valido(leido), "secreto.txt: el contenido cabe en X-Device-Secret");
        comprueba(ArchivoSecreto.sobrescribirConCeros(f, largo), "secreto.txt: sobrescrito con ceros");
        byte[] despues = java.nio.file.Files.readAllBytes(f.toPath());
        boolean ceros = despues.length == largo;
        for (byte b : despues) if (b != 0) ceros = false;
        comprueba(ceros, "secreto.txt: mismo tamaño (" + despues.length + " bytes) y todo en ceros");
        comprueba(ArchivoSecreto.vaciar(f) && f.isFile() && f.length() == 0, "secreto.txt: queda vacío y sigue existiendo (no se borra)");
        comprueba(ArchivoSecreto.leer(f) == null, "secreto.txt vacío: no hay nada que importar");
        comprueba(!ArchivoSecreto.valido("con espacio adentro 12345") && !ArchivoSecreto.valido("corto") && !ArchivoSecreto.valido(null),
                "contenido con espacios, corto o nulo: no se acepta");
        java.nio.file.Files.write(f.toPath(), new byte[ArchivoSecreto.MAXIMO_BYTES + 1]);
        comprueba(ArchivoSecreto.leer(f) == null, "archivo de más de 4096 bytes: no se lee");
        ArchivoSecreto.vaciar(f);
        comprueba(ArchivoSecreto.leer(new java.io.File(carpeta, "no-existe.txt")) == null, "archivo que no existe: null");
    }

    /** Preferencias en memoria: el mismo contrato que SharedPreferences para lo que usan Ajustes y Tokens. */
    static final class ContextoFalso extends android.content.Context {
        final java.util.Map<String, java.util.Map<String, Object>> archivos = new java.util.HashMap<>();
        String ultimoArchivo;
        @Override public android.content.SharedPreferences getSharedPreferences(String nombre, int modo) {
            ultimoArchivo = nombre;
            final java.util.Map<String, Object> m = archivos.computeIfAbsent(nombre, k -> new java.util.HashMap<>());
            return new android.content.SharedPreferences() {
                public String getString(String k, String d) { Object v = m.get(k); return v instanceof String ? (String) v : d; }
                public int getInt(String k, int d) { Object v = m.get(k); return v instanceof Integer ? (Integer) v : d; }
                public Editor edit() {
                    final java.util.Map<String, Object> cambios = new java.util.HashMap<>();
                    return new Editor() {
                        public Editor putString(String k, String v) { cambios.put(k, v); return this; }
                        public Editor putInt(String k, int v) { cambios.put(k, v); return this; }
                        public void apply() { m.putAll(cambios); }
                        public boolean commit() { m.putAll(cambios); return true; }
                    };
                }
            };
        }
    }

    /** Lo que `config` deja guardado para que `llamar` funcione sin la Mac. */
    static void pruebaAjustes() {
        ContextoFalso c = new ContextoFalso();
        comprueba(Ajustes.worker(c).isEmpty() && Ajustes.maxMin(c) == 3, "sin config: sin Worker y techo de 3 min");
        comprueba(Ajustes.maxTimbre(c) == 20, "sin config: tope de timbre de 20 s (desde DIALING ≈ 15 s de timbre)");
        comprueba("servicios".equals(Ajustes.modo(c)), "sin config: modo servicios");
        comprueba(Ajustes.guardarWorker(c, "https://agente-isabella-token.tu-subdominio.workers.dev/token")
                && "https://agente-isabella-token.tu-subdominio.workers.dev/token".equals(Ajustes.worker(c)), "la URL del Worker queda guardada");
        comprueba(Ajustes.guardarMaxMin(c, 3) && Ajustes.maxMin(c) == 3, "el techo queda guardado");
        Ajustes.guardarMaxMin(c, 10);
        comprueba(Ajustes.maxMin(c) == 5, "un techo de 10 se guarda como 5 (tope)");
        Ajustes.guardarMaxMin(c, 0);
        comprueba(Ajustes.maxMin(c) == 1, "un techo de 0 se guarda como 1");
        comprueba(Ajustes.esWorkerValido("https://agente-isabella-token.tu-subdominio.workers.dev/token") && !Ajustes.esWorkerValido("http://agente-isabella-token.tu-subdominio.workers.dev/token")
                && !Ajustes.esWorkerValido("https://") && !Ajustes.esWorkerValido("ftp://x/token"), "Worker válido solo con https:// y host");
        comprueba(Tokens.guardarSecretoYa(c, "abcdefghijklmnop-1234567890_XYZ") && "abcdefghijklmnop-1234567890_XYZ".equals(Tokens.secreto(c)),
                "el secreto importado queda en las preferencias");
        comprueba(Ajustes.guardarMaxTimbre(c, 20) && Ajustes.maxTimbre(c) == 20, "el tope de timbre queda guardado");
        Ajustes.guardarMaxTimbre(c, 100);
        comprueba(Ajustes.maxTimbre(c) == 60, "un tope de timbre de 100 s se guarda como 60");
        Ajustes.guardarMaxTimbre(c, 1);
        comprueba(Ajustes.maxTimbre(c) == 5, "un tope de timbre de 1 s se guarda como 5");
        comprueba(Ajustes.guardarModo(c, " Flexible ") && "flexible".equals(Ajustes.modo(c)), "el modo queda guardado (normalizado)");
        comprueba(!Ajustes.guardarModo(c, "libre") && "flexible".equals(Ajustes.modo(c)), "un modo que no existe no se guarda ni borra el anterior");
        comprueba(Ajustes.normalizarModo("ISABELLA").equals("isabella") && Ajustes.normalizarModo("") == null && Ajustes.modoOPorDefecto(null).equals("servicios"),
                "modos: isabella, servicios y flexible; lo demás → servicios");
        comprueba(c.archivos.size() == 1 && c.archivos.containsKey("isabella_llamadas"), "Ajustes y Tokens usan el mismo archivo de preferencias");
    }

    /** Fusible l: el tope de timbre se arma una vez, STATE_ACTIVE lo apaga y lo de otra llamada no dispara. */
    static void pruebaTopeTimbre() throws Exception {
        java.util.concurrent.ScheduledExecutorService reloj = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            TopeTimbre t = new TopeTimbre(reloj);
            AtomicInteger disparos = new AtomicInteger();
            AtomicLong cuando = new AtomicLong();
            long t0 = System.currentTimeMillis();
            long resta = t.armar(300, t0, t0, () -> { disparos.incrementAndGet(); cuando.set(System.currentTimeMillis()); });
            comprueba(resta == 300, "tope de timbre: armado desde ahora, faltan 300 ms (" + resta + ")");
            comprueba(t.armar(300, t0, t0, disparos::incrementAndGet) == -1 && t.armado(), "tope de timbre: se arma UNA vez por llamada");
            Thread.sleep(150);
            comprueba(disparos.get() == 0, "tope de timbre: no vence antes de tiempo");
            espera(() -> disparos.get() == 1, 1500);
            long tardo = cuando.get() - t0;
            comprueba(disparos.get() == 1 && tardo >= 290 && tardo < 900, "tope de timbre: vence una vez y a su tiempo (" + tardo + " ms)");
            Thread.sleep(300);
            comprueba(disparos.get() == 1, "tope de timbre: no vuelve a vencer");

            TopeTimbre t2 = new TopeTimbre(reloj);
            AtomicInteger d2 = new AtomicInteger();
            long a = System.currentTimeMillis();
            t2.armar(250, a, a, d2::incrementAndGet);
            Thread.sleep(80);
            t2.contestaron();
            comprueba(t2.armar(250, a, System.currentTimeMillis(), d2::incrementAndGet) == -1, "tope de timbre: después de STATE_ACTIVE ya no se arma en esa llamada");
            Thread.sleep(450);
            comprueba(d2.get() == 0 && t2.contestada(), "tope de timbre: STATE_ACTIVE antes del tope → no cuelga");

            TopeTimbre t3 = new TopeTimbre(reloj);
            long b = System.currentTimeMillis();
            comprueba(t3.armar(1000, b - 800, b, () -> { }) == 200, "tope de timbre: cuenta desde el DIALING/OFFHOOK que lo armó (800 ms tarde → faltan 200)");
            t3.reiniciar();
            TopeTimbre t4 = new TopeTimbre(reloj);
            AtomicInteger d4 = new AtomicInteger();
            long c = System.currentTimeMillis();
            comprueba(t4.armar(1000, c - 5000, c, d4::incrementAndGet) == 0, "tope de timbre: si ya pasó el tiempo, faltan 0 ms");
            comprueba(espera(() -> d4.get() == 1, 1000), "tope de timbre: el ya vencido dispara de inmediato");

            TopeTimbre t5 = new TopeTimbre(reloj);
            AtomicInteger viejo = new AtomicInteger(), nuevo = new AtomicInteger();
            long e = System.currentTimeMillis();
            t5.armar(200, e, e, viejo::incrementAndGet);
            t5.reiniciar();
            comprueba(!t5.armado() && !t5.contestada(), "tope de timbre: reiniciar (fin de llamada) lo deja sin armar");
            long f = System.currentTimeMillis();
            t5.armar(300, f, f, nuevo::incrementAndGet);
            Thread.sleep(700);
            comprueba(viejo.get() == 0 && nuevo.get() == 1, "tope de timbre: lo de la llamada anterior no dispara y lo nuevo sí (" + viejo.get() + "/" + nuevo.get() + ")");
        } finally {
            reloj.shutdownNow();
        }
        TopeTimbre t6 = new TopeTimbre(reloj);
        long g = System.currentTimeMillis();
        comprueba(t6.armar(100, g, g, () -> { }) == -1 && !t6.armado(), "tope de timbre: con el reloj cerrado (servicio cerrándose) no arma ni lanza excepción");
    }

    static Tokens.Resultado pide(int p, String ruta, String secreto) {
        return Tokens.pedirAlWorker("http://127.0.0.1:" + p + ruta, secreto, "es", 3, "servicios");
    }

    static int cuenta(int p, String caso) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("http://127.0.0.1:" + p + "/cuenta/" + caso).openConnection();
        try (java.util.Scanner sc = new java.util.Scanner(c.getInputStream(), "UTF-8")) {
            return Integer.parseInt(sc.next().trim());
        }
    }

    static void pruebaTokens(int p) throws Exception {
        final String S = "secreto-falso-123";
        long antes = System.currentTimeMillis();
        Tokens.Resultado r = pide(p, "", S);
        comprueba(r.ok() && r.token.startsWith("auth_tokens/") && "gemini-3.8-live".equals(r.modelo), "Worker 200: token y modelo (X-Device-Secret, idioma, max_min y modo, ruta /token)");
        long vida = r.venceMs - antes;
        comprueba(r.maxMin == 3 && vida > 265_000 && vida <= 270_500, "Worker 200: maxMin 3 y vida ≈ 60 + 3·60 + 30 s menos 1 s (" + vida + " ms)");
        r = Tokens.pedirAlWorker("http://127.0.0.1:" + p, S, "es", 5, "flexible");
        vida = r.venceMs - System.currentTimeMillis();
        comprueba(r.ok() && r.maxMin == 5 && vida > 385_000 && vida <= 390_500, "techo 5 → token de 5 min, vida ≈ 390 s (" + vida + " ms)");
        r = pide(p, "/caso/viejo/token", S);
        vida = r.venceMs - System.currentTimeMillis();
        comprueba(r.ok() && r.maxMin == -1 && vida > 355_000 && vida <= 360_500, "Worker viejo: sin maxMin (−1) y la vida sale de su expira (" + vida + " ms)");
        r = pide(p, "/caso/corto/token", S);
        comprueba(r.ok() && r.maxMin == 2, "Worker que da menos minutos de los pedidos: la app lo ve (maxMin " + r.maxMin + ")");
        r = pide(p, "/caso/maxmin/token", S);
        comprueba(!r.ok() && "max_min_invalido".equals(r.error) && cuenta(p, "maxmin") == 1, "400 max_min_invalido → sin reintento");
        r = pide(p, "/caso/setup/token", S);
        comprueba(!r.ok() && "setup_faltante".equals(r.error) && cuenta(p, "setup") == 1, "500 setup_faltante → sin reintento");
        r = pide(p, "", "otro-secreto");
        comprueba(!r.ok() && "no_existe".equals(r.error) && r.http == 404, "secreto malo → 404 no_existe, sin reintento");
        long t0 = System.currentTimeMillis();
        r = pide(p, "/caso/503/token", S);
        long dt = System.currentTimeMillis() - t0;
        comprueba(r.ok() && dt >= 1900 && cuenta(p, "503") == 2, "503 topes_no_disponibles → reintento único a los 2 s, con token (" + dt + " ms)");
        t0 = System.currentTimeMillis();
        r = pide(p, "/caso/429/token", S);
        dt = System.currentTimeMillis() - t0;
        comprueba(r.ok() && dt >= 900 && dt < 1900 && cuenta(p, "429") == 2, "429 tope_minuto → espera retry-after (1 s) y reintenta una vez (" + dt + " ms)");
        r = pide(p, "/caso/dia/token", S);
        comprueba(!r.ok() && "tope_dia".equals(r.error) && cuenta(p, "dia") == 1, "429 tope_dia → sin reintento");
        r = pide(p, "/caso/cuota/token", S);
        comprueba(!r.ok() && "cuota_gemini".equals(r.error) && cuenta(p, "cuota") == 1, "502 cuota_gemini → sin reintento");
        r = pide(p, "/caso/502doble/token", S);
        comprueba(!r.ok() && "gemini".equals(r.error) && cuenta(p, "502doble") == 2, "502 gemini dos veces → un solo reintento y se rinde");
        r = pide(p, "/caso/sintoken/token", S);
        comprueba(!r.ok() && "sin_token".equals(r.error) && cuenta(p, "sintoken") == 1, "200 sin token → sin_token, sin reintento");
        r = pide(p, "/caso/sinllave/token", S);
        comprueba(!r.ok() && "sin_llave".equals(r.error) && cuenta(p, "sinllave") == 1, "503 sin_llave → sin reintento");
        r = Tokens.pedirAlWorker("http://127.0.0.1:1/token", S, "es", 3, "servicios");
        comprueba(!r.ok() && "red".equals(r.error), "Worker caído → error de red, sin reintento");
    }

    interface Cond { boolean ok(); }

    static boolean espera(Cond c, long ms) throws InterruptedException {
        long fin = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < fin) {
            if (c.ok()) return true;
            Thread.sleep(50);
        }
        return c.ok();
    }

    static long fase = 0;

    static void tramas(SesionGemini s, int ms, int amp) throws InterruptedException {
        for (int k = 0; k < ms / 20; k++) {
            byte[] b = new byte[640]; // 20 ms de PCM16 a 16 kHz, como la app
            for (int i = 0; i < 320; i++) {
                short v = (short) Math.round(amp * Math.sin(2 * Math.PI * 200 * (fase++) / 16000.0));
                b[2 * i] = (byte) v;
                b[2 * i + 1] = (byte) (v >> 8);
            }
            s.enviarMicrofono(b, b.length);
            Thread.sleep(20);
        }
    }

    static void sena(SesionGemini s, int ms) throws InterruptedException { tramas(s, ms, 3000); }
    static void silencio(SesionGemini s, int ms) throws InterruptedException { tramas(s, ms, 0); }

    static void pruebaSesion(int puerto) throws Exception {
        final CountDownLatch lista = new CountDownLatch(1), colgar = new CountDownLatch(1);
        final AtomicInteger turnosFin = new AtomicInteger(), interrumpida = new AtomicInteger(), errores = new AtomicInteger(), actividad = new AtomicInteger();
        final AtomicLong bytesVoz = new AtomicLong(), ultimoAudio = new AtomicLong(), primerAudioTurno = new AtomicLong();
        final AtomicBoolean habla = new AtomicBoolean();
        SesionGemini.Oyente o = new SesionGemini.Oyente() {
            public void onLista() { lista.countDown(); }
            public void onVozIsabella(byte[] pcm) {
                bytesVoz.addAndGet(pcm.length);
                ultimoAudio.set(System.currentTimeMillis());
                primerAudioTurno.compareAndSet(0, System.currentTimeMillis());
            }
            public void onIsabellaHabla(boolean on, String motivo) {
                habla.set(on);
                if (!on) { if ("interrumpida".equals(motivo)) interrumpida.incrementAndGet(); else turnosFin.incrementAndGet(); }
            }
            public void onTexto(String quien, String texto) { }
            public void onColgar(long topeMs) { colgar.countDown(); }
            public void onAviso(String aviso) { }
            public void onActividadInterlocutor(String tipo) { actividad.incrementAndGet(); }
            public void onError(String motivo) { errores.incrementAndGet(); System.out.println("  onError: " + motivo); }
            public void onCerrada(String motivo) { errores.incrementAndGet(); System.out.println("  onCerrada: " + motivo); }
        };
        // wsbase SIN ruta: la app usa la de Google por defecto.
        SesionGemini s = new SesionGemini("auth_tokens/falso-1", "v1beta", "ws://127.0.0.1:" + puerto, "gemini-3.8-live", o);
        s.abrir();
        comprueba(lista.await(5, TimeUnit.SECONDS), "setupComplete en marco binario → sesión lista");
        sena(s, 800); silencio(s, 1000);
        comprueba(espera(() -> turnosFin.get() >= 1, 8000), "turno 1: voz de Isabella y turnComplete");
        comprueba(bytesVoz.get() >= 24000 * 2, "turno 1: ~1.2 s de voz a 24 kHz (" + bytesVoz.get() + " bytes)");
        primerAudioTurno.set(0);
        sena(s, 800); silencio(s, 1000);
        comprueba(espera(() -> primerAudioTurno.get() != 0, 8000), "turno 2: empieza la respuesta larga");
        Thread.sleep(600);
        sena(s, 400);
        comprueba(espera(() -> interrumpida.get() >= 1, 4000), "señal encima de la respuesta → interrupted → «interrumpida»");
        final int antes = turnosFin.get();
        silencio(s, 1000);
        comprueba(espera(() -> turnosFin.get() > antes, 8000), "turno 3: contesta después de la interrupción");
        sena(s, 800); silencio(s, 1000);
        comprueba(colgar.await(10, TimeUnit.SECONDS), "turno 4: toolCall colgar → onColgar y toolResponse");
        comprueba(espera(() -> System.currentTimeMillis() - ultimoAudio.get() > 1200 && !habla.get(), 12000), "la despedida termina (1.2 s sin audio)");
        final int t0 = turnosFin.get();
        s.enviarSistema(SesionGemini.sistemaAviso(25));
        comprueba(espera(() -> turnosFin.get() > t0, 8000), "aviso por clientContent: el mock lo recibe y contesta");
        Thread.sleep(2500);
        s.enviarSistemaRealtime("Sistema: prueba por realtimeInput.");
        Thread.sleep(1500);
        // 2.5: el aviso de grabación sale como contexto (turnComplete:false); el arnés lo revisa en lo que vio el mock.
        comprueba(s.enviarContexto(Grabacion.TEXTO_AVISO), "contexto de grabación: con la sesión abierta, enviarContexto() lo encola");
        Thread.sleep(300);
        comprueba(actividad.get() >= 4, "transcripciones de entrada → onActividadInterlocutor (" + actividad.get() + ")");
        comprueba(s.tramasTiradas() == 0 && s.msEscribiendo() < 1000, "con la red sana no se tira micrófono ni se traba la escritura (" + s.tramasTiradas() + " tiradas)");
        int huecos = 0, desgloses = 0;
        for (String l : Bitacora.copia()) {
            if (l.startsWith("Hueco del servidor")) huecos++;
            if (l.startsWith("Tokens turno: entrada ") && l.contains("(texto ")) desgloses++;
        }
        comprueba(huecos == 0, "con el mock sano no hay «Hueco del servidor» (" + huecos + ")");
        comprueba(desgloses >= 3, "cada turnComplete anota los tokens con su desglose por modalidad (" + desgloses + " turnos)");
        long c0 = System.currentTimeMillis();
        s.cerrar();
        long cerro = System.currentTimeMillis() - c0;
        comprueba(cerro < 150, "con la red sana cerrar() vuelve al instante (" + cerro + " ms)");
        Thread.sleep(800);
        comprueba(errores.get() == 0, "cerrar() de la app no produce onError ni onCerrada");
        comprueba(!s.estaLista(), "tras cerrar(), la sesión ya no está lista");
    }

    static void pruebaSinServidor() throws Exception {
        final CountDownLatch err = new CountDownLatch(1);
        final AtomicInteger cerradas = new AtomicInteger(), listas = new AtomicInteger();
        SesionGemini s = new SesionGemini("auth_tokens/falso-2", "v1beta", "ws://127.0.0.1:1", "", new SesionGemini.Oyente() {
            public void onLista() { listas.incrementAndGet(); }
            public void onVozIsabella(byte[] pcm) { }
            public void onIsabellaHabla(boolean on, String motivo) { }
            public void onTexto(String quien, String texto) { }
            public void onColgar(long topeMs) { }
            public void onAviso(String aviso) { }
            public void onActividadInterlocutor(String tipo) { }
            public void onError(String motivo) { err.countDown(); }
            public void onCerrada(String motivo) { cerradas.incrementAndGet(); }
        });
        s.abrir();
        comprueba(err.await(10, TimeUnit.SECONDS) && cerradas.get() == 0 && listas.get() == 0, "sin servidor: onError antes del setup (con esto la app no marca)");
        s.cerrar();
    }

    /**
     * Hallazgo 1 del arquitecto (2.4): un servidor que confirma el setup y deja de leer. Antes, la captura se trababa
     * escribiendo con el candado tomado y cerrar() esperaba detrás; ahora la captura nunca espera, el único trabado es
     * el emisor, msEscribiendo() lo delata (fusible m) y cerrar() vuelve en ≤ 300 ms soltando el TCP.
     */
    static void pruebaRedAtorada() throws Exception {
        final java.net.ServerSocket srv = new java.net.ServerSocket();
        srv.setReceiveBufferSize(4096);
        srv.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
        final CountDownLatch soltar = new CountDownLatch(1);
        Thread servidor = new Thread(() -> {
            try (java.net.Socket c = srv.accept()) {
                java.io.InputStream in = c.getInputStream();
                java.io.OutputStream out = c.getOutputStream();
                java.io.ByteArrayOutputStream cab = new java.io.ByteArrayOutputStream();
                int b, fin = 0;
                while ((b = in.read()) != -1) { cab.write(b); fin = (b == '\n' || b == '\r') ? fin + 1 : 0; if (fin == 4) break; }
                String clave = "";
                for (String l : cab.toString("UTF-8").split("\r\n")) if (l.toLowerCase().startsWith("sec-websocket-key:")) clave = l.substring(18).trim();
                String acepta = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1")
                        .digest((clave + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acepta + "\r\n\r\n").getBytes("UTF-8"));
                byte[] m = "{\"setupComplete\":{}}".getBytes("UTF-8");
                out.write(new byte[]{(byte) 0x81, (byte) m.length});
                out.write(m);
                out.flush();
                soltar.await(); // desde aquí no lee: la red «se atora»
            } catch (Exception e) {
                // fin de la prueba
            }
        }, "servidor-atascado");
        servidor.setDaemon(true);
        servidor.start();
        final CountDownLatch lista = new CountDownLatch(1);
        final AtomicInteger avisos = new AtomicInteger();
        final SesionGemini s = new SesionGemini("auth_tokens/atasco", "v1beta", "ws://127.0.0.1:" + srv.getLocalPort(), "", new SesionGemini.Oyente() {
            public void onLista() { lista.countDown(); }
            public void onVozIsabella(byte[] pcm) { }
            public void onIsabellaHabla(boolean on, String motivo) { }
            public void onTexto(String quien, String texto) { }
            public void onColgar(long topeMs) { }
            public void onAviso(String aviso) { }
            public void onActividadInterlocutor(String tipo) { }
            public void onError(String motivo) { avisos.incrementAndGet(); System.out.println("  onError: " + motivo); }
            public void onCerrada(String motivo) { avisos.incrementAndGet(); System.out.println("  onCerrada: " + motivo); }
        });
        s.abrir();
        comprueba(lista.await(5, TimeUnit.SECONDS), "red atorada: el servidor confirma el setup y deja de leer");
        final AtomicLong peor = new AtomicLong(), llamadaDesde = new AtomicLong();
        final AtomicBoolean parar = new AtomicBoolean();
        final AtomicInteger numeroTrama = new AtomicInteger();
        Thread captura = new Thread(() -> { // como la captura, pero más rápido para llenar los búferes de TCP
            byte[] trama = new byte[640];
            while (!parar.get()) {
                long t0 = System.currentTimeMillis();
                llamadaDesde.set(t0);
                int k = numeroTrama.incrementAndGet(); // cada trama lleva su número en los primeros 4 bytes
                trama[0] = (byte) (k >>> 24); trama[1] = (byte) (k >>> 16); trama[2] = (byte) (k >>> 8); trama[3] = (byte) k;
                s.enviarMicrofono(trama, trama.length);
                llamadaDesde.set(0);
                peor.accumulateAndGet(System.currentTimeMillis() - t0, Math::max);
                try { Thread.sleep(1); } catch (InterruptedException e) { return; }
            }
        }, "captura-falsa");
        captura.setDaemon(true);
        captura.start();
        boolean delatada = espera(() -> s.msEscribiendo() > 1500, 12000);
        comprueba(delatada, "red atorada: msEscribiendo() la delata (" + s.msEscribiendo() + " ms trabada; el fusible m corta a los 5 s)");
        long enCurso = llamadaDesde.get() == 0 ? 0 : System.currentTimeMillis() - llamadaDesde.get(); // una llamada trabada no vuelve
        long peorMs = Math.max(peor.get(), enCurso);
        comprueba(peorMs < 50, "red atorada: la captura nunca espera a la red (peor llamada " + peorMs + " ms)");
        comprueba(s.tramasTiradas() > 0, "red atorada: la cola se topa en 1 s y tira lo que no cabe (" + s.tramasTiradas() + " tramas)");
        byte[] vieja = s.audioMasViejoEnCola();
        int numVieja = vieja == null ? -1 : ((vieja[0] & 0xFF) << 24) | ((vieja[1] & 0xFF) << 16) | ((vieja[2] & 0xFF) << 8) | (vieja[3] & 0xFF);
        int ultima = numeroTrama.get();
        comprueba(numVieja > ultima - SesionGemini.MAX_AUDIO_EN_COLA - 5, "red atorada: se tira lo MÁS VIEJO; en la cola queda lo reciente (la más vieja es la "
                + numVieja + " de " + ultima + ")");
        int avisosCola = 0;
        for (String l : Bitacora.copia()) if (l.startsWith("Micrófono: la red no da abasto")) avisosCola++;
        comprueba(avisosCola == 1, "red atorada: el aviso de la cola llena sale UNA vez (" + avisosCola + ")");
        final AtomicLong duro = new AtomicLong(-1);
        Thread cierre = new Thread(() -> { long a0 = System.currentTimeMillis(); s.cerrar(); duro.set(System.currentTimeMillis() - a0); }, "cierre-prueba");
        cierre.setDaemon(true);
        cierre.start();
        cierre.join(3000);
        comprueba(!cierre.isAlive() && duro.get() >= 0 && duro.get() < 1000, "red atorada: cerrar() vuelve sin esperar a la red (" + (cierre.isAlive() ? "sigue trabado" : duro.get() + " ms") + ")");
        parar.set(true);
        Thread.sleep(1000);
        int vivos = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String n = t.getName();
            if (t.isAlive() && (n.equals("ws-emisor") || n.equals("ws-gemini") || n.equals("ws-cierre"))) { vivos++; System.out.println("  hilo vivo: " + n); }
        }
        comprueba(vivos == 0, "red atorada: tras cerrar() no queda vivo ningún hilo del socket (emisor, lector, cierre)");
        comprueba(avisos.get() == 0, "red atorada: cerrar() de la app no produce onError ni onCerrada");
        soltar.countDown();
        srv.close();
    }
}
