// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

/**
 * Confirmación contra toques fantasma en los botones que marcan (2.4). En el A72 los toques fantasma duran de 0.1 a
 * 0.7 s y caen siempre en el MISMO punto, en ráfagas de doble toque (memoria del 18-oct): le ganan a una pulsación larga
 * y a un segundo toque en el mismo botón. Por eso marcar pide dos toques en lugares distintos:
 *   1. el botón («Llamar con Isabella» o «Solo marcar») arma la orden por 3 s y muestra el número;
 *   2. el botón «Sí, …» que aparece a otra altura de la pantalla la confirma dentro de esos 3 s.
 * Un segundo toque en el mismo botón de marcar CANCELA (así una ráfaga en un solo punto nunca marca). Los comandos por
 * adb no pasan por aquí. Java puro: se prueba en la Mac.
 */
final class Confirmacion {
    static final long VENTANA_MS = 3000;

    private String armado; // orden armada («llamar» | «solo_marcar»); null = nada
    private long armadoMs;

    /**
     * Toque en un botón de marcar. Devuelve true si la orden quedó armada; false si el toque canceló la que ya estaba
     * armada en ese mismo botón.
     */
    synchronized boolean tocarBoton(String orden, long ahoraMs) {
        if (vigente(ahoraMs) && orden.equals(armado)) {
            armado = null;
            return false;
        }
        armado = orden;
        armadoMs = ahoraMs;
        return true;
    }

    /** Toque en «Sí, …»: la orden a mandar si sigue vigente; null si venció o no había nada armado. Siempre desarma. */
    synchronized String confirmar(long ahoraMs) {
        String orden = vigente(ahoraMs) ? armado : null;
        armado = null;
        return orden;
    }

    /** La orden armada si sigue en su ventana (null si no). */
    synchronized String armado(long ahoraMs) {
        return vigente(ahoraMs) ? armado : null;
    }

    /** Milisegundos que le quedan a la orden armada (0 si no hay). */
    synchronized long restaMs(long ahoraMs) {
        return vigente(ahoraMs) ? Math.max(0, VENTANA_MS - (ahoraMs - armadoMs)) : 0;
    }

    synchronized void cancelar() {
        armado = null;
    }

    private boolean vigente(long ahoraMs) {
        return armado != null && ahoraMs - armadoMs <= VENTANA_MS;
    }

    /** «5500000000» → «55 0000 0000»; otro largo se deja tal cual. */
    static String numeroLegible(String n) {
        String s = n == null ? "" : n.trim();
        if (s.length() == 10 && s.chars().allMatch(Character::isDigit)) {
            return s.substring(0, 2) + " " + s.substring(2, 6) + " " + s.substring(6);
        }
        return s;
    }
}
