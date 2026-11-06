// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;
import java.util.ArrayList;
import java.util.List;
/** Bitácora de la Mac: misma firma que la del cel; guarda las líneas para revisar que no salgan secretos. */
final class Bitacora {
    private static final long T0 = System.currentTimeMillis();
    static final List<String> LINEAS = new ArrayList<>();
    static synchronized void log(String m) {
        LINEAS.add(m);
        System.out.println(String.format(java.util.Locale.US, "[%6.2f] %s", (System.currentTimeMillis() - T0) / 1000.0, m));
    }
    static synchronized List<String> copia() { return new ArrayList<>(LINEAS); }
}
