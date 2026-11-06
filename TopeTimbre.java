// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tope de timbre de la ruta InCallService (`--es max_timbre_s`, 15 s por defecto): si la llamada no pasa a
 * STATE_ACTIVE en ese tiempo desde DIALING, la app cuelga y cierra Gemini antes de que conteste el buzón.
 * En Telecom no hay estado «alerting»: el timbre de la red es parte de DIALING.
 *
 * Se arma UNA vez por llamada (gana el primer aviso: DIALING del InCallService u OFFHOOK con el InCallService
 * ligado) y STATE_ACTIVE lo apaga hasta la siguiente llamada. Un vencimiento viejo (de otra llamada o ya
 * cancelado) no dispara nada. Java puro: se prueba en la Mac (pruebas_mac).
 *
 * La ruta clásica (sin InCallService) no lo usa: OFFHOOK llega al empezar a marcar y no distingue el timbre
 * de una llamada contestada, así que un tope ahí colgaría llamadas ya contestadas.
 */
final class TopeTimbre {
    private final ScheduledExecutorService reloj;
    private ScheduledFuture<?> pendiente;
    private boolean armado, contestada;
    private int generacion;

    TopeTimbre(ScheduledExecutorService reloj) {
        this.reloj = reloj;
    }

    /**
     * Arma el tope si no estaba armado y no han contestado. Cuenta desde `desdeMs`; si ya pasó el tiempo,
     * vence de inmediato. Devuelve los ms que faltan, o -1 si no se armó.
     */
    synchronized long armar(long topeMs, long desdeMs, long ahoraMs, Runnable alVencer) {
        if (armado || contestada) return -1;
        armado = true;
        final int g = ++generacion;
        long resta = Math.max(0, desdeMs + topeMs - ahoraMs);
        try {
            pendiente = reloj.schedule(() -> {
                if (vigente(g)) alVencer.run();
            }, resta, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            armado = false; // el servicio se está cerrando: no hay nada que vigilar
            return -1;
        }
        return resta;
    }

    /** STATE_ACTIVE real: en esta llamada el tope ya no aplica. */
    synchronized void contestaron() {
        contestada = true;
        cancelarPendiente();
    }

    synchronized boolean contestada() {
        return contestada;
    }

    synchronized boolean armado() {
        return armado;
    }

    /** Llamada nueva o fin de la actual: cancela lo pendiente y lo deja listo para armarse otra vez. */
    synchronized void reiniciar() {
        cancelarPendiente();
        generacion++;
        armado = false;
        contestada = false;
    }

    private synchronized boolean vigente(int g) {
        return g == generacion && armado && !contestada;
    }

    private void cancelarPendiente() {
        if (pendiente != null) pendiente.cancel(false);
        pendiente = null;
    }
}
