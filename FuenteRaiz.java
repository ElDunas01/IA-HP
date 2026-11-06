// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas.audio;

import android.media.AudioDeviceInfo;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;

import com.viajatech.lab.isabellallamadas.Bitacora;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * FuenteRaiz: tinycap vía su, lee del PCM device 23 (VOC_REC_DL) a través de un FIFO en /data/local/tmp.
 * tinycap escribe primero una cabecera WAV de 44 bytes; la clase la consume y descarta.
 * El pipeline queda: tinycap → FIFO → BufferedInputStream → read().
 */
public final class FuenteRaiz implements FuenteAudio {
    private static final int BUFFER_BYTES = 16 * 1024;

    private final int rate;
    private final String fifo;

    private java.lang.Process proceso;
    private BufferedInputStream in;
    private Thread stderrDrain;
    private volatile int estado;

    /** dirFifo debe ser un directorio que la app pueda abrir (típicamente getFilesDir()). */
    public FuenteRaiz(int rate, String dirFifo) {
        this.rate = rate;
        this.fifo = dirFifo + "/isabella_dn_" + Process.myPid() + ".wav";
        try {
            new File(fifo).delete();
            Os.mkfifo(fifo, 0666);
        } catch (ErrnoException | SecurityException e) {
            Bitacora.log("FuenteRaiz: no pude crear el FIFO " + fifo + ": " + e.getMessage());
            estado = 0;
            return;
        }
        RaizUtil.setMixer(RaizUtil.MIXER_DN, 0);
        estado = ESTADO_LISTO;
    }

    @Override public int getState() {
        if (estado != ESTADO_LISTO) return 0;
        if (proceso != null && !proceso.isAlive()) { estado = 0; return 0; }
        return ESTADO_LISTO;
    }

    @Override public void startRecording() {
        if (estado != ESTADO_LISTO) return;
        try {
            // timeout 180: fusible defensivo; si tinycap sigue vivo tras 3 min (p.ej. porque -T 7200 no se respetó)
            // el timeout lo mata y libera el PCM 0,23 para la próxima captura.
            String cmd =
                "tinymix '" + RaizUtil.MIXER_DN.replace("'", "'\\''") + "' 1"
                + " && exec timeout 180 tinycap '" + fifo + "' -D 0 -d 23 -c 1 -r " + rate + " -b 16 -T 7200";
            proceso = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            stderrDrain = RaizUtil.drenarAEntropia(proceso.getInputStream(), "tinycap: ");

            FileInputStream raw = abrirConTimeout(fifo, 1500);
            if (raw == null) throw new IOException("open FIFO bajada agotó 1500 ms (tinycap no abrió el lado escritor)");
            in = new BufferedInputStream(raw, BUFFER_BYTES);
            // El tinycap de AOSP escribe 44 B de header RIFF; el stock del A72, con salida no-seekable (FIFO),
            // los omite y empieza a volcar PCM crudo desde el byte 0. En ambos casos descartamos los primeros
            // 44 B: si fue header, lo saltamos; si fue PCM, perdemos 1.4 ms (22 muestras @ 16 kHz, inaudibles).
            byte[] cab = new byte[44];
            int leidos = 0;
            while (leidos < 44) {
                int n = in.read(cab, leidos, 44 - leidos);
                if (n < 0) throw new IOException("EOF antes de 44 B iniciales de tinycap");
                leidos += n;
            }
            boolean esRiff = cab[0] == 'R' && cab[1] == 'I' && cab[2] == 'F' && cab[3] == 'F'
                    && cab[8] == 'W' && cab[9] == 'A' && cab[10] == 'V' && cab[11] == 'E';
            Bitacora.log("FuenteRaiz: tinycap activo · VOC_REC_DL · hw:0,23 · rate " + rate
                    + " · descarté 44 B iniciales (" + (esRiff ? "header RIFF/WAVE" : "raw PCM, 1.4 ms") + ")");
        } catch (Exception e) {
            Bitacora.log("FuenteRaiz.startRecording: " + e.getMessage());
            estado = 0;
            release();
        }
    }

    private FileInputStream abrirConTimeout(final String ruta, long ms) {
        final FileInputStream[] holder = new FileInputStream[1];
        final Exception[] err = new Exception[1];
        Thread t = new Thread(() -> {
            try { holder[0] = new FileInputStream(ruta); } catch (Exception e) { err[0] = e; }
        }, "fifo-open-dn");
        t.setDaemon(true);
        t.start();
        try { t.join(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        if (t.isAlive()) {
            t.interrupt();
            if (proceso != null && !proceso.isAlive()) Bitacora.log("FuenteRaiz: tinycap murió antes de abrir el FIFO");
            return null;
        }
        if (err[0] != null) { Bitacora.log("FuenteRaiz: abrir FIFO falló: " + err[0].getMessage()); return null; }
        return holder[0];
    }

    @Override public int read(byte[] b, int off, int len) {
        if (estado != ESTADO_LISTO || in == null) return -1;
        try {
            int total = 0;
            while (total < len) {
                int n = in.read(b, off + total, len - total);
                if (n < 0) return total > 0 ? total : -1;
                total += n;
            }
            return total;
        } catch (IOException e) {
            Bitacora.log("FuenteRaiz.read: " + e.getMessage());
            estado = 0;
            return -1;
        }
    }

    @Override public int getAudioSessionId() { return 0; }

    @Override public boolean isClientSilenced() { return false; }

    @Override public AudioDeviceInfo getDispositivoRuta() { return null; }

    @Override public void stop() { cerrar(); }

    @Override public void release() { cerrar(); }

    private synchronized void cerrar() {
        if (in != null) { try { in.close(); } catch (Exception ignorado) {} in = null; }
        if (proceso != null) {
            proceso.destroy();
            try { proceso.waitFor(500, TimeUnit.MILLISECONDS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            if (proceso.isAlive()) proceso.destroyForcibly();
            proceso = null;
        }
        RaizUtil.setMixer(RaizUtil.MIXER_DN, 0);
        try { new File(fifo).delete(); } catch (Exception ignorado) {}
        estado = 0;
    }
}
