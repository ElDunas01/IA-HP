// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Bitácora en memoria que ven a la vez la pantalla y logcat.
 *
 * La app y el servicio corren en el mismo proceso, así que la pantalla escucha con un oyente simple
 * (sin broadcasts). Todo lo que se anota también va a logcat con la etiqueta "IsabellaLlamadas",
 * para que el agente `dispositivo` lo lea desde la Mac con `adb logcat -s IsabellaLlamadas`.
 */
public final class Bitacora {
    static final String TAG = "IsabellaLlamadas";
    private static final int MAX_LINEAS = 400;
    private static final SimpleDateFormat HORA = new SimpleDateFormat("HH:mm:ss.SSS", new Locale("es", "MX"));

    private static final List<String> LINEAS = new ArrayList<>();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static Runnable oyente;

    private Bitacora() {}

    /** Anota una línea con hora, la manda a logcat y avisa a la pantalla si está escuchando. */
    public static synchronized void log(String mensaje) {
        String linea = HORA.format(new Date()) + "  " + mensaje;
        LINEAS.add(linea);
        while (LINEAS.size() > MAX_LINEAS) LINEAS.remove(0);
        Log.i(TAG, mensaje);
        if (oyente != null) UI.post(oyente);
    }

    /** Todo el texto acumulado, para pintarlo en la pantalla. */
    static synchronized String texto() {
        StringBuilder s = new StringBuilder();
        for (String l : LINEAS) s.append(l).append('\n');
        return s.toString();
    }

    /** La pantalla registra aquí un callback que se dispara (en el hilo principal) con cada línea nueva. */
    static synchronized void escuchar(Runnable r) {
        oyente = r;
    }

    static synchronized void dejarDeEscuchar() {
        oyente = null;
    }
}
