// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Marcas para medir la latencia de verdad (2.4, propuesta de `dispositivo` aprobada por el director). Las
 * transcripciones de Gemini llegan junto con el principio de la respuesta, así que restarlas da casi cero. Por turno:
 *   «Voz local: fin a las HH:mm:ss.SSS …»: la última trama del micrófono con RMS sobre el umbral que SÍ se mandó a
 *       Gemini antes del primer audio de la respuesta. Con el medio dúplex, lo que suena de Isabella no se manda, así
 *       que su eco no cuenta.
 *   «Isabella: primer audio del turno (+N ms desde el fin de la voz local)»: cuando llega el primer trozo de audio de
 *       la respuesta (isabellaHablando pasa de false a true). Si llegó el ACTIVITY_END del VAD de Google, también va
 *       cuánto pasó desde él.
 *   «Bocina: primer trozo del turno escrito (+N ms desde que llegó)»: cuando ese mismo trozo entra al AudioTrack.
 * Java puro (sin Android): se prueba en la Mac. Hilos: la captura llama alEnviarTrama; el lector del socket,
 * alFinDeVozGemini, alPrimerAudio y primerTrozo; la bocina, alEscribirBocina. Todo va sincronizado (≈ 50 llamadas/s).
 */
final class MarcasLatencia {
    /** RMS de una trama de 20 ms en PCM16. Medido en el A72 (prueba 0b): cuarto en silencio 20-110, voz de David 600+. */
    static final int UMBRAL_DEFECTO = 300;
    static final int UMBRAL_MINIMO = 50, UMBRAL_TOPE = 20_000;

    private final int umbral;
    private long finVozLocalMs;  // última trama con voz mandada desde el turno anterior de Isabella (0 = ninguna)
    private double rmsFinVoz;
    private long finVozGeminiMs; // último ACTIVITY_END desde el turno anterior (0 = ninguno)
    private long primerAudioMs;  // primer audio del turno en curso (0 = todavía no hay turno)
    private byte[] primerTrozo;  // ese trozo, para reconocerlo en la bocina (por identidad)
    private boolean primerTrozoReportado; // la línea "Bocina: primer trozo…" ya salió para este turno
    private int turno;

    MarcasLatencia(int umbral) {
        this.umbral = limitar(umbral);
    }

    static int limitar(int umbral) {
        return Math.max(UMBRAL_MINIMO, Math.min(UMBRAL_TOPE, umbral));
    }

    int umbral() {
        return umbral;
    }

    /** Cada trama que SÍ se mandó a Gemini (la captura no la llama para las del medio dúplex ni las del tono). */
    synchronized void alEnviarTrama(long ahoraMs, double rms) {
        if (rms >= umbral) {
            finVozLocalMs = ahoraMs;
            rmsFinVoz = rms;
        }
    }

    /** voiceActivity ACTIVITY_END: el VAD de Google oyó terminar a la persona. */
    synchronized void alFinDeVozGemini(long ahoraMs) {
        finVozGeminiMs = ahoraMs;
    }

    /** Primer audio del turno (isabellaHablando de false a true). Devuelve las líneas para la bitácora. */
    synchronized List<String> alPrimerAudio(long ahoraMs) {
        List<String> lineas = new ArrayList<>();
        turno++;
        primerAudioMs = ahoraMs;
        primerTrozo = null;
        primerTrozoReportado = false;
        String deGemini = finVozGeminiMs > 0
                ? " · +" + (ahoraMs - finVozGeminiMs) + " ms desde el fin de voz que marcó Gemini (ACTIVITY_END)" : "";
        if (finVozLocalMs > 0) {
            lineas.add("Voz local: fin a las " + hora(finVozLocalMs) + " (turno " + turno + "; RMS " + Math.round(rmsFinVoz)
                    + ", umbral " + umbral + ").");
            lineas.add("Isabella: primer audio del turno (+" + (ahoraMs - finVozLocalMs) + " ms desde el fin de la voz local)"
                    + deGemini + ".");
        } else {
            lineas.add("Isabella: primer audio del turno " + turno + " (sin voz local antes: contesta un aviso del sistema)"
                    + deGemini + ".");
        }
        finVozLocalMs = 0;
        rmsFinVoz = 0;
        finVozGeminiMs = 0;
        return lineas;
    }

    /** El primer trozo de audio que llegó en el turno (lo encola el oyente justo después de alPrimerAudio). */
    synchronized void primerTrozo(byte[] trozo) {
        if (primerAudioMs != 0 && primerTrozo == null && !primerTrozoReportado) primerTrozo = trozo;
    }

    /** La bocina va a escribir `trozo` al AudioTrack: si es el primero del turno, la línea; si no, null. */
    synchronized String alEscribirBocina(byte[] trozo, long ahoraMs) {
        if (trozo == null || trozo != primerTrozo) return null;
        primerTrozo = null;
        primerTrozoReportado = true;
        return "Bocina: primer trozo del turno escrito (+" + (ahoraMs - primerAudioMs) + " ms desde que llegó).";
    }

    static String hora(long ms) {
        return new SimpleDateFormat("HH:mm:ss.SSS", new Locale("es", "MX")).format(new Date(ms));
    }
}
