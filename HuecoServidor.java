// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.util.Locale;

/**
 * «Hueco del servidor» (2.4, veredicto del arquitecto sobre el trabón de la prueba 0, que vino de antes del cel): a
 * media respuesta de Isabella, del primer audio del turno hasta generationComplete, turnComplete o interrupted, si pasa
 * más de 1 s sin NINGÚN mensaje de Google se anota cuánto duró el hueco, la hora del mensaje que lo cerró y cuántos
 * mensajes más llegaron en los 300 ms siguientes. Solo mide: no agrega colchón a la reproducción.
 * Java puro: se prueba en la Mac. Lo llaman el lector del socket (cada mensaje) y el vigilante (cada segundo).
 */
final class HuecoServidor {
    static final long HUECO_MS = 1000, VENTANA_MS = 300;

    private boolean enRespuesta;
    private long ultimoMs;    // último mensaje de Google (0 = ninguno todavía)
    private long huecoMs;     // hueco abierto: cuánto duró
    private long siguienteMs; // hueco abierto: hora del mensaje que lo cerró (0 = no hay hueco abierto)
    private int masEnVentana; // mensajes que llegaron en los 300 ms después de ese

    /** Primer audio de un turno de Isabella. */
    synchronized void empiezaRespuesta() {
        enRespuesta = true;
    }

    /** generationComplete, turnComplete o interrupted: ya no se espera más audio de esa respuesta. */
    synchronized void terminaRespuesta() {
        enRespuesta = false;
    }

    /**
     * Cada mensaje de Google, también los `{}`, ANTES de interpretarlo. Devuelve la línea de un hueco anterior cuya
     * ventana de 300 ms ya cerró (o null).
     */
    synchronized String alMensaje(long ahoraMs) {
        String linea = cerrarSiVencio(ahoraMs);
        if (siguienteMs != 0) {
            masEnVentana++;
        } else if (enRespuesta && ultimoMs != 0 && ahoraMs - ultimoMs > HUECO_MS) {
            huecoMs = ahoraMs - ultimoMs;
            siguienteMs = ahoraMs;
            masEnVentana = 0;
        }
        ultimoMs = ahoraMs;
        return linea;
    }

    /** La línea del hueco abierto si ya pasaron sus 300 ms; null si no hay o todavía no. */
    synchronized String cerrarSiVencio(long ahoraMs) {
        if (siguienteMs == 0 || ahoraMs - siguienteMs <= VENTANA_MS) return null;
        return linea();
    }

    /** Al cerrar la sesión: la línea pendiente aunque su ventana no haya terminado (o null). */
    synchronized String vaciar() {
        return siguienteMs == 0 ? null : linea();
    }

    private String linea() {
        String l = String.format(Locale.US,
                "Hueco del servidor: %.1f s sin mensajes de Google a media respuesta; siguiente mensaje a las %s; %d más en los 300 ms siguientes.",
                huecoMs / 1000.0, MarcasLatencia.hora(siguienteMs), masEnVentana);
        siguienteMs = 0;
        huecoMs = 0;
        masEnVentana = 0;
        return l;
    }
}
