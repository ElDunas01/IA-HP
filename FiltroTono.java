// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.util.ArrayList;
import java.util.List;

/**
 * Filtro del tono de llamada (ringback) en el micrófono. En México el tono es 425 Hz puro (1 s sí,
 * 4 s no); se revisa también 440+480 Hz (EE. UU.). Las tramas con tono NO se mandan a Gemini ni
 * cuentan como voz. Portado de `puente/lib/audio.mjs` (FiltroTono/razonTono), con sus parámetros
 * medidos el 27-oct.
 *
 * Cada trama de 20 ms se mide junto con la anterior (ventana de 40 ms: con 20 ms una voz femenina
 * de ~212 Hz pone su 2º armónico en 425 Hz y engaña al detector). Una trama es candidata si la razón
 * de Goertzel pasa de `umbral` y no es casi silencio; el tono se confirma con `confirma` candidatas
 * seguidas (las dudosas se retienen y se sueltan si no era tono) y se da por terminado tras `suelta`
 * tramas sin tono. Las tramas que sí son audio salen en orden.
 */
final class FiltroTono {
    private final int tasa;
    private final double umbral;
    private final int confirma, suelta, minimo;
    private final double[][] grupos;

    private byte[] anterior;
    private boolean enTono;
    private int sinTono;
    private final List<byte[]> retenidas = new ArrayList<>();

    FiltroTono(int tasa) {
        this.tasa = tasa;
        this.grupos = new double[][]{ {425}, {440, 480} };
        this.umbral = 0.5;
        this.confirma = 6;
        this.suelta = 3;
        this.minimo = 60;
    }

    boolean enTono() {
        return enTono;
    }

    /** Recibe una trama PCM16 (bytes); devuelve las tramas que SÍ son audio y hay que mandar (0, 1 o varias). */
    List<byte[]> filtra(byte[] trama) {
        double r = razon(trama, anterior);
        anterior = trama;
        boolean candidata = r > umbral;
        List<byte[]> pasan = new ArrayList<>();
        if (!enTono) {
            if (!candidata) {
                pasan.addAll(retenidas);
                retenidas.clear();
                pasan.add(trama);
                return pasan;
            }
            retenidas.add(trama);
            if (retenidas.size() < confirma) return pasan; // retener mientras se confirma
            enTono = true;
            retenidas.clear();                              // eran el arranque del tono: se descartan
            sinTono = 0;
            return pasan;
        }
        if (candidata) {
            retenidas.clear();
            sinTono = 0;
            return pasan;
        }
        retenidas.add(trama);
        if (++sinTono < suelta) return pasan;               // aún dentro del tono (su cola)
        enTono = false;
        pasan.addAll(retenidas);
        retenidas.clear();
        sinTono = 0;
        return pasan;
    }

    /** Razón de la trama medida con la anterior (la mayor entre los grupos); 0 si es casi silencio. */
    private double razon(byte[] pcm, byte[] previa) {
        if (rms(pcm) < minimo) return 0;
        short[] s;
        if (previa != null && previa.length == pcm.length) {
            byte[] junto = new byte[previa.length + pcm.length];
            System.arraycopy(previa, 0, junto, 0, previa.length);
            System.arraycopy(pcm, 0, junto, previa.length, pcm.length);
            s = aInt16(junto);
        } else {
            s = aInt16(pcm);
        }
        double r = 0;
        for (double[] g : grupos) r = Math.max(r, razonTono(s, g));
        return r;
    }

    /** Potencia normalizada (Goertzel): razón = Σ 2·|X(f)|² / (N · Σx²). Un seno puro da ≈ 1. */
    private double razonTono(short[] x, double[] frecuencias) {
        int n = x.length;
        if (n == 0) return 0;
        double energia = 0;
        for (int i = 0; i < n; i++) energia += (double) x[i] * x[i];
        if (energia <= 0) return 0;
        double suma = 0;
        for (double f : frecuencias) {
            double c = 2 * Math.cos(2 * Math.PI * f / tasa);
            double s1 = 0, s2 = 0;
            for (int i = 0; i < n; i++) {
                double s0 = x[i] + c * s1 - s2;
                s2 = s1;
                s1 = s0;
            }
            suma += (2 * (s1 * s1 + s2 * s2 - c * s1 * s2)) / (n * energia);
        }
        return suma;
    }

    private static double rms(byte[] pcm) {
        short[] s = aInt16(pcm);
        if (s.length == 0) return 0;
        double acc = 0;
        for (short v : s) acc += (double) v * v;
        return Math.sqrt(acc / s.length);
    }

    private static short[] aInt16(byte[] b) {
        int n = b.length / 2;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
        return s;
    }
}
