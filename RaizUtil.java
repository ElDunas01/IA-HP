// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas.audio;

import com.viajatech.lab.isabellallamadas.Bitacora;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Util compartido por FuenteRaiz y SumideroRaiz: ejecutar un comando por `su -c`, poner un mixer,
 * y sondear que el modo raíz es viable (binarios + mixers presentes).
 */
public final class RaizUtil {
    public static final String MIXER_UP = "Incall_Music Audio Mixer MultiMedia2";
    public static final String MIXER_DN = "MultiMedia9 Mixer VOC_REC_DL";

    private RaizUtil() {}

    /** Ejecuta `su -c <cmd>` sincrónico; devuelve exit code (-1 si no se pudo lanzar). */
    public static int suSync(String cmd) {
        try {
            Process p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            StringBuilder salida = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String ln;
                while ((ln = br.readLine()) != null) salida.append(ln).append('\n');
            }
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Bitacora.log("RaizUtil: timeout en `" + cmd + "`");
                return -1;
            }
            int rc = p.exitValue();
            if (rc != 0 && salida.length() > 0) {
                Bitacora.log("RaizUtil: `" + cmd + "` rc=" + rc + " · " + salida.toString().trim());
            }
            return rc;
        } catch (Exception e) {
            Bitacora.log("RaizUtil: no pude ejecutar `" + cmd + "`: " + e.getMessage());
            return -1;
        }
    }

    /** Pone un mixer booleano a 0/1 (control del tipo BOOL con 2 canales). */
    public static boolean setMixer(String nombre, int valor) {
        int rc = suSync("tinymix '" + nombre.replace("'", "'\\''") + "' " + valor);
        return rc == 0;
    }

    /** Cierra ambos mixers; defensa de profundidad frente a crashes previos. */
    public static void cerrarAmbosMixers() {
        setMixer(MIXER_UP, 0);
        setMixer(MIXER_DN, 0);
    }

    /**
     * Sonda que valida que el modo raíz es viable AQUÍ Y AHORA:
     *  - su funciona (uid 0)
     *  - tinymix, tinyplay y tinycap existen
     *  - los dos mixers de la ruta I-3 están presentes en la lista de controles
     */
    public static boolean sondearRaiz() {
        if (suSync("test -x /system/bin/tinymix -a -x /system/bin/tinyplay -a -x /system/bin/tinycap") != 0) {
            Bitacora.log("RaizUtil: faltan binarios tinymix/tinyplay/tinycap");
            return false;
        }
        try {
            Process p = new ProcessBuilder("su", "-c",
                    "tinymix | grep -c -E \"'" + MIXER_UP + "'|'" + MIXER_DN + "'|"
                    + MIXER_UP + "|" + MIXER_DN + "\"").start();
            StringBuilder salida = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String ln;
                while ((ln = br.readLine()) != null) salida.append(ln.trim());
            }
            p.waitFor(5, TimeUnit.SECONDS);
            int n = 0;
            try { n = Integer.parseInt(salida.toString().trim()); } catch (NumberFormatException ignorado) {}
            if (n < 2) {
                Bitacora.log("RaizUtil: no veo los dos mixers I-3 (" + MIXER_UP + ", " + MIXER_DN + ") · hits=" + n);
                return false;
            }
            return true;
        } catch (Exception e) {
            Bitacora.log("RaizUtil: sondearRaiz falló: " + e.getMessage());
            return false;
        }
    }

    /** Lanza un hilo daemon que drena un InputStream (típicamente stderr) a Bitácora con un prefijo. */
    public static Thread drenarAEntropia(final java.io.InputStream in, final String prefijo) {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in))) {
                String ln;
                while ((ln = br.readLine()) != null) {
                    Bitacora.log(prefijo + ln);
                }
            } catch (Exception ignorado) {}
        }, "raiz-" + prefijo);
        t.setDaemon(true);
        t.start();
        return t;
    }
}
