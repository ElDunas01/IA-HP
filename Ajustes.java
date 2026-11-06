// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Ajustes que sobreviven entre llamadas (el servicio muere al colgar): la URL del Worker de tokens, el
 * techo por llamada, el tope de timbre y el modo del preámbulo de Isabella. Solo `--es cmd config` los
 * guarda; en los demás comandos valen para esa vez.
 * El número NO se guarda a propósito: cada orden dice a quién se marca (pruebas solo a números de David).
 */
final class Ajustes {
    private static final String CLAVE_WORKER = "worker_url";
    private static final String CLAVE_MAXMIN = "max_min";
    private static final String CLAVE_MAXTIMBRE = "max_timbre_s";
    private static final String CLAVE_MODO = "modo";
    static final int MAXMIN_DEFECTO = 3, MAXMIN_TOPE = 5;
    // Tope de timbre (ruta InCallService): de 5 a 60 s, contado desde DIALING. En la llamada 1, ALERTING llegó 4.7 s
    // después de marcar: con 20 s desde DIALING suenan unos 15 s (lo que `llamar.sh --timbre 15` quería). El buzón de
    // la red suele contestar a los ~20 s de timbre.
    static final int MAXTIMBRE_DEFECTO = 20, MAXTIMBRE_MINIMO = 5, MAXTIMBRE_TOPE = 60;
    // Modo del preámbulo de Isabella que acuña el Worker (`?modo=`, decisión de David 21:58): servicios = David y sus
    // servicios (el amarrado de la prueba 0b), isabella = la persona pública del sitio, flexible = contesta lo razonable.
    static final String MODO_DEFECTO = "servicios";
    static final String[] MODOS = {"servicios", "isabella", "flexible"};

    private Ajustes() {}

    static String worker(Context ctx) {
        return prefs(ctx).getString(CLAVE_WORKER, "");
    }

    static boolean guardarWorker(Context ctx, String url) {
        return prefs(ctx).edit().putString(CLAVE_WORKER, url).commit();
    }

    static int maxMin(Context ctx) {
        return limitar(prefs(ctx).getInt(CLAVE_MAXMIN, MAXMIN_DEFECTO));
    }

    static boolean guardarMaxMin(Context ctx, int minutos) {
        return prefs(ctx).edit().putInt(CLAVE_MAXMIN, limitar(minutos)).commit();
    }

    static int limitar(int minutos) {
        return Math.max(1, Math.min(MAXMIN_TOPE, minutos));
    }

    static int maxTimbre(Context ctx) {
        return limitarTimbre(prefs(ctx).getInt(CLAVE_MAXTIMBRE, MAXTIMBRE_DEFECTO));
    }

    static boolean guardarMaxTimbre(Context ctx, int segundos) {
        return prefs(ctx).edit().putInt(CLAVE_MAXTIMBRE, limitarTimbre(segundos)).commit();
    }

    static int limitarTimbre(int segundos) {
        return Math.max(MAXTIMBRE_MINIMO, Math.min(MAXTIMBRE_TOPE, segundos));
    }

    static String modo(Context ctx) {
        return modoOPorDefecto(prefs(ctx).getString(CLAVE_MODO, MODO_DEFECTO));
    }

    static boolean guardarModo(Context ctx, String modo) {
        String m = normalizarModo(modo);
        return m != null && prefs(ctx).edit().putString(CLAVE_MODO, m).commit();
    }

    /** «Flexible » → «flexible»; null si no es uno de los tres. */
    static String normalizarModo(String modo) {
        if (modo == null) return null;
        String m = modo.trim().toLowerCase(java.util.Locale.ROOT);
        for (String valido : MODOS) if (valido.equals(m)) return m;
        return null;
    }

    static String modoOPorDefecto(String modo) {
        String m = normalizarModo(modo);
        return m != null ? m : MODO_DEFECTO;
    }

    /** El Worker de producción: https:// con host. */
    static boolean esWorkerValido(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(Tokens.PREFS, Context.MODE_PRIVATE);
    }
}
