// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

/**
 * Ruta ESTRICTA (2.4, decisión del director): `llamar` solo marca si el InCallService puede ligarse (appop
 * MANAGE_ONGOING_CALLS concedido), porque solo él sabe cuándo contestan (STATE_ACTIVE). En la ruta clásica la
 * conversación arranca 1 s después de OFFHOOK y los silencios del timbre van a Gemini: eso rompe la regla de David de
 * no gastar sin que contesten. El escape `--es ruta_clasica 1` es solo para laboratorio.
 * Java puro: se prueba en la Mac.
 */
final class Ruta {
    static final String ESTRICTA = "estricta";   // con InCallService: la conversación empieza en STATE_ACTIVE real
    static final String CLASICA = "clasica";     // sin InCallService y con el escape: activa aproximada (OFFHOOK + 1 s)
    static final String NO_MARCAR = "no_marcar"; // sin InCallService y sin el escape: no se marca

    private Ruta() {}

    /** Con el appop siempre va la estricta (aunque pidan la clásica); sin él, la clásica solo con el escape. */
    static String decidir(boolean appopConcedido, boolean clasicaPedida) {
        if (appopConcedido) return ESTRICTA;
        return clasicaPedida ? CLASICA : NO_MARCAR;
    }
}
