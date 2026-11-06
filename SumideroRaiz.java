// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas.audio;

import android.media.AudioDeviceInfo;
import android.os.Process;

import com.viajatech.lab.isabellallamadas.Bitacora;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * SumideroRaiz v2: tinyplay via su + stdin (NO FIFO). Un hilo "pacer" bombea PCM
 * al pipe a tasa constante inyectando silencio cuando el productor TTS pausa,
 * para evitar que el ring-buffer de Qualcomm Incall_Music se vacíe y pcm_writei
 * devuelva -EPIPE (xrun).
 *
 * Cambios vs v1:
 *  - stdin del proceso en vez de FIFO (sin mkfifo, sin SELinux file-context).
 *  - Prefill de 500 ms de silencio tras el header.
 *  - Hilo "pacer" @ THREAD_PRIORITY_URGENT_AUDIO que bombea cada 20 ms al pipe.
 *  - write() público no bloquea al productor (ArrayBlockingQueue + offer con
 *    descarte del más viejo).
 *  - Constructor acepta "dirFifoIgnorado" solo para compat de firma con v1
 *    (LlamadaService lo pasa como getFilesDir().getAbsolutePath()).
 */
public final class SumideroRaiz implements SumideroAudio {
    private static final int BUFFER_BYTES   = 16 * 1024;
    private static final int MS_SILENCIO    = 20;                 // granularidad del pacer
    private static final int PREFILL_MS_DEF = 500;                // colchón inicial default v6
    private static final int CAPACIDAD_COLA = 128;                // ~2.5 s a 20 ms/chunk
    private static final byte[] CENTINELA   = new byte[0];        // comparado por identidad
    private static final int DITHER_RESERVA_MS = 2000;            // v7: 2 s de dither pre-generado (sin GC por tick)

    private final int rateEntrada;
    private final int rateSalida;             // = rateEntrada: PCM 0,1 soporta 8-384 kHz (tinypcminfo),
    private final int bytesPorMs;             // dejamos que el ADSP de Qualcomm haga su propio SRC con
                                              // filtros polyfase FIR, en vez de un lineal 24→16 nuestro.
    private final int prefillMs;              // v7 FIX C: colchón inicial configurable (default 500)
    private final int ditherAmp;              // v7 FIX B: amplitud del dither en silencio (0=cero puro v6)
    private byte[] bufferDither;              // v7 FIX B: ruido pre-generado, se rota en el pacer
    private int ditherOffset;                 // v7 FIX B: puntero rotatorio en bufferDither
    private byte[] silencio;                  // v7 (supervisor): buffer reutilizable de ceros (evita 48 KB/s de GC cuando ditherAmp=0). Escrito en play() antes de arrancar el pacer; después solo lectura.

    private java.lang.Process proceso;
    private BufferedOutputStream out;
    private Thread stderrDrain;
    private Thread pacer;
    private ArrayBlockingQueue<byte[]> cola;
    private volatile int estado;
    private volatile boolean pacerVivo;

    /** dirFifoIgnorado se mantiene por compat con el call-site viejo (si alguien quedara). */
    public SumideroRaiz(int rateEntrada, String dirFifoIgnorado) {
        this(rateEntrada, dirFifoIgnorado, PREFILL_MS_DEF, 0);
    }

    /** v7: constructor con prefill y amplitud de dither configurables. */
    public SumideroRaiz(int rateEntrada, String dirFifoIgnorado, int prefillMs, int ditherAmp) {
        this.rateEntrada = rateEntrada;
        this.rateSalida = rateEntrada;        // 24 kHz nativo: eliminamos el resample lineal.
        this.bytesPorMs = rateSalida * 2 / 1000; // mono 16-bit → 48 B/ms a 24 kHz
        this.prefillMs = Math.max(100, Math.min(2000, prefillMs));
        this.ditherAmp = Math.max(0, Math.min(8, ditherAmp));
        estado = ESTADO_LISTO;
    }

    @Override public int getState() {
        if (estado != ESTADO_LISTO) return 0;
        if (proceso != null && !proceso.isAlive()) { estado = 0; return 0; }
        return ESTADO_LISTO;
    }

    @Override public void play() {
        if (estado != ESTADO_LISTO) return;
        if (proceso != null && proceso.isAlive()) {
            // Idempotente: ya hay un tinyplay vivo con el PCM 0,1 abierto. Un segundo
            // ProcessBuilder.start() aquí abriría otro tinyplay que compite por hw:0,1
            // (Qualcomm ALSA no permite 2 writers) y falla con "cannot open device 1".
            // La segunda llamada típica viene de LlamadaService.vaciarReproduccion()
            // haciendo pause()+flush()+play() (idioma de AudioTrack): aquí pause y
            // flush son no-op, así que play también debe serlo mientras el pacer vive.
            return;
        }
        try {
            // timeout 600: fusible defensivo. Si la app crashea sin cerrar(), tinyplay
            // muere solo a los 10 min (techo del Live API) y no queda zombie bloqueando
            // el PCM 0,1 para la próxima llamada. En cierre ordenado `cerrar()` manda
            // destroy()/destroyForcibly() mucho antes; este fusible es sólo para crashes.
            String cmd =
                "tinymix '" + RaizUtil.MIXER_UP.replace("'", "'\\''") + "' 1"
                + " && exec timeout 600 tinyplay /dev/stdin -D 0 -d 1";
            proceso = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            stderrDrain = RaizUtil.drenarAEntropia(proceso.getInputStream(), "tinyplay: ");

            out = new BufferedOutputStream(proceso.getOutputStream(), BUFFER_BYTES);
            out.write(cabeceraWavStreaming(rateSalida));
            // v7 FIX C: prefill configurable (default 500 ms = v6 exacto). El contenido es
            // dither sub-dBFS si está activo, cero puro si no (mantiene la semántica v6
            // con ditherAmp=0).
            byte[] prefill = new byte[bytesPorMs * prefillMs];
            if (ditherAmp > 0) rellenarConDither(prefill, 0, prefill.length);
            out.write(prefill);
            out.flush();

            // v7 FIX B: pre-generar 2 s de dither una sola vez para no hacer GC por tick.
            if (ditherAmp > 0) {
                bufferDither = new byte[bytesPorMs * DITHER_RESERVA_MS];
                rellenarConDither(bufferDither, 0, bufferDither.length);
                ditherOffset = 0;
            }
            // v7 (supervisor): buffer de ceros reutilizable (20 ms = 960 B @ 24 k). Lo
            // escribimos AQUÍ antes de arrancar el pacer; el thread.start() del pacer
            // publica el escrito por happens-before, así no hace falta sync adicional.
            silencio = new byte[bytesPorMs * MS_SILENCIO];

            cola = new ArrayBlockingQueue<>(CAPACIDAD_COLA);
            pacerVivo = true;
            pacer = new Thread(this::bombearPacer, "sumidero-pacer");
            pacer.setDaemon(true);
            pacer.start();

            Bitacora.log("SumideroRaiz v2: tinyplay stdin · Incall_Music · hw:0,1 · rate=" + rateSalida
                    + " (nativo, sin resample app; ADSP hace SRC a lo del modem)"
                    + " · prefill=" + prefillMs + "ms · pacer=" + MS_SILENCIO + "ms"
                    + " · cola_cap=" + CAPACIDAD_COLA
                    + (ditherAmp > 0 ? " · dither=±" + ditherAmp + " (anti-DTX)" : " · dither=off (cero puro)"));
        } catch (Exception e) {
            Bitacora.log("SumideroRaiz.play: " + e.getMessage());
            estado = 0;
            release();
        }
    }

    @Override public int write(byte[] b, int off, int len) {
        if (estado != ESTADO_LISTO || cola == null) return 0;
        // 24 kHz nativo: el chunk va al pipe tal cual (sin resample 24→16 lineal).
        byte[] chunk = Arrays.copyOfRange(b, off, off + len);
        try {
            cola.put(chunk);                              // backpressure al productor
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return 0;
        }
        return len;
    }

    private void bombearPacer() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        // v7 FIX B: con ditherAmp=0 el silencio sale desde el campo `silencio` (cero puro, v6 exacto);
        // con ditherAmp>0 se escribe desde bufferDither rotando offset, sin GC por tick.
        final int TAM_PEDACITO = bytesPorMs * MS_SILENCIO;
        final long NS_POR_TICK = TimeUnit.MILLISECONDS.toNanos(MS_SILENCIO);
        byte[] chunkActual = null;
        int offActual = 0;
        long siguienteTick = System.nanoTime();
        try {
            while (pacerVivo) {
                // 1) Elegir qué sale en este tick de 20 ms. Nunca bloqueamos en la cola:
                //    el pacing lo da el reloj, no la disponibilidad del productor.
                if (chunkActual == null || offActual >= chunkActual.length) {
                    byte[] siguiente = cola.poll();
                    if (siguiente == CENTINELA) break;
                    if (siguiente != null) { chunkActual = siguiente; offActual = 0; }
                }
                if (chunkActual != null && offActual < chunkActual.length) {
                    int tam = Math.min(TAM_PEDACITO, chunkActual.length - offActual);
                    out.write(chunkActual, offActual, tam);
                    offActual += tam;
                    int faltante = TAM_PEDACITO - tam;
                    while (faltante > 0) {
                        // Antes de meter silencio en la frontera del chunk (que produce
                        // un step audible), intentar continuar con el siguiente chunk del
                        // productor en el MISMO tick. Solo si la cola está vacía usamos
                        // silencio: ese es el único caso real de productor atorado.
                        byte[] siguiente = cola.poll();
                        if (siguiente == CENTINELA) { pacerVivo = false; break; }
                        if (siguiente == null) {
                            escribirSilencio(faltante);
                            faltante = 0;
                            break;
                        }
                        chunkActual = siguiente;
                        offActual = 0;
                        int tomar = Math.min(faltante, chunkActual.length);
                        out.write(chunkActual, 0, tomar);
                        offActual = tomar;
                        faltante -= tomar;
                    }
                } else {
                    escribirSilencio(TAM_PEDACITO);
                }
                out.flush();
                if (proceso != null && !proceso.isAlive()) {
                    Bitacora.log("SumideroRaiz: tinyplay murió durante el pacer (exit="
                            + proceso.exitValue() + ")");
                    break;
                }
                // 2) Esperar hasta la siguiente marca wall-clock de 20 ms.
                siguienteTick += NS_POR_TICK;
                long esperaNs = siguienteTick - System.nanoTime();
                if (esperaNs > 0) {
                    java.util.concurrent.locks.LockSupport.parkNanos(esperaNs);
                } else if (esperaNs < -NS_POR_TICK * 10L) {
                    // Caímos > 200 ms detrás (GC largo o preempción) → resetear el
                    // deadline para no acumular deuda y no soltar una ráfaga al pipe.
                    siguienteTick = System.nanoTime();
                }
            }
        } catch (IOException e) {
            Bitacora.log("SumideroRaiz.pacer: IO · " + e.getMessage());
        } finally {
            estado = 0;
            try { if (out != null) out.close(); } catch (Exception ignorado) {}
        }
    }

    @Override public AudioDeviceInfo getDispositivoRuta() { return null; }

    @Override public void pause() {
        /* El pacer sigue vivo e inyecta silencio; no cerramos el pipe para que
         * la ruta Incall_Music no se tire. El llamador deja de hacer write(). */
    }

    @Override public void flush() {
        /* Tirar los chunks pendientes para que, al interrumpir a Isabella
         * (LlamadaService.vaciarReproduccion → pause/flush/play), lo que ya
         * está en el pipe del tinyplay siga sonando (no hay forma de recortarlo)
         * pero NO siga encolándose detrás. El pacer sigue vivo inyectando silencio.
         * Guard de null porque flush() puede llamarse antes de play() (idioma de
         * AudioTrack: pause/flush al constructor). */
        if (cola != null) cola.clear();
    }

    @Override public void stop() {
        pacerVivo = false;
        if (cola != null) cola.offer(CENTINELA);
        if (pacer != null) {
            try { pacer.join(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        if (out != null) { try { out.close(); } catch (Exception ignorado) {} out = null; }
        if (proceso != null) {
            try { proceso.waitFor(800, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    @Override public void release() { cerrar(); }

    private synchronized void cerrar() {
        pacerVivo = false;
        if (cola != null) cola.offer(CENTINELA);
        if (pacer != null) {
            try { pacer.join(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            pacer = null;
        }
        if (out != null) { try { out.close(); } catch (Exception ignorado) {} out = null; }
        if (proceso != null) {
            try { proceso.waitFor(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (proceso.isAlive()) proceso.destroy();
            try { proceso.waitFor(500, TimeUnit.MILLISECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (proceso.isAlive()) proceso.destroyForcibly();
            proceso = null;
        }
        RaizUtil.setMixer(RaizUtil.MIXER_UP, 0);
        estado = 0;
    }

    // --- Helpers (idénticos a v1) --------------------------------------------

    static byte[] resample(byte[] src, int off, int len, int rateIn, int rateOut) {
        int samplesIn = len / 2;
        if (samplesIn <= 1) return new byte[0];
        int samplesOut = (int) ((long) samplesIn * rateOut / rateIn);
        byte[] out = new byte[samplesOut * 2];
        for (int i = 0; i < samplesOut; i++) {
            double idx = (double) i * rateIn / rateOut;
            int i0 = (int) idx;
            int i1 = Math.min(i0 + 1, samplesIn - 1);
            double frac = idx - i0;
            int s0 = (short) ((src[off + i0 * 2] & 0xFF) | (src[off + i0 * 2 + 1] << 8));
            int s1 = (short) ((src[off + i1 * 2] & 0xFF) | (src[off + i1 * 2 + 1] << 8));
            int interp = (int) (s0 + frac * (s1 - s0));
            if (interp >  32767) interp =  32767;
            else if (interp < -32768) interp = -32768;
            out[i * 2] = (byte) interp;
            out[i * 2 + 1] = (byte) (interp >> 8);
        }
        return out;
    }

    /**
     * v7 FIX B + supervisor: escribe `bytes` de silencio al pipe.
     * Si ditherAmp<=0, usa el campo reutilizable `silencio` (cero puro, SIN GC por tick → v6 exacto).
     * Si ditherAmp>0, rota sobre `bufferDither` pre-generado (anti-DTX).
     */
    private void escribirSilencio(int bytes) throws IOException {
        if (ditherAmp <= 0 || bufferDither == null) {
            // Cero puro (comportamiento v6). El campo `silencio` vive en cero y es reutilizable.
            byte[] s = silencio;
            if (s == null || s.length < bytes) {
                // Defensa: si el pacer corriera antes de play() (no debería), fallback sin crash.
                out.write(new byte[bytes]);
                return;
            }
            out.write(s, 0, bytes);
            return;
        }
        // Rotamos sobre bufferDither pre-generado. Puede abarcar más de una vuelta.
        int faltan = bytes;
        while (faltan > 0) {
            int disponible = bufferDither.length - ditherOffset;
            if (disponible <= 0) { ditherOffset = 0; disponible = bufferDither.length; }
            int tomar = Math.min(faltan, disponible);
            out.write(bufferDither, ditherOffset, tomar);
            ditherOffset += tomar;
            if (ditherOffset >= bufferDither.length) ditherOffset = 0;
            faltan -= tomar;
        }
    }

    /** v7 FIX B: rellena `buf[off..off+len)` con PCM16 LE de amplitud uniforme ±ditherAmp. */
    private void rellenarConDither(byte[] buf, int off, int len) {
        java.util.Random r = new java.util.Random(0xCAFEBABEL); // determinista: no hace falta aleatorio verdadero a ±2
        int n = len / 2;
        for (int i = 0; i < n; i++) {
            int v = ditherAmp == 0 ? 0 : (r.nextInt(2 * ditherAmp + 1) - ditherAmp);
            buf[off + i * 2]     = (byte) (v & 0xFF);
            buf[off + i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }
    }

    static byte[] cabeceraWavStreaming(int rate) {
        int bits = 16, ch = 1;
        int byteRate = rate * ch * bits / 8;
        int blockAlign = ch * bits / 8;
        int dataSize = rate * ch * bits / 8 * 600; // 10 min
        int chunkSize = dataSize + 36;
        ByteBuffer bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bb.put((byte)'R').put((byte)'I').put((byte)'F').put((byte)'F');
        bb.putInt(chunkSize);
        bb.put((byte)'W').put((byte)'A').put((byte)'V').put((byte)'E');
        bb.put((byte)'f').put((byte)'m').put((byte)'t').put((byte)' ');
        bb.putInt(16);
        bb.putShort((short)1);
        bb.putShort((short)ch);
        bb.putInt(rate);
        bb.putInt(byteRate);
        bb.putShort((short)blockAlign);
        bb.putShort((short)bits);
        bb.put((byte)'d').put((byte)'a').put((byte)'t').put((byte)'a');
        bb.putInt(dataSize);
        return bb.array();
    }
}
