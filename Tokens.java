// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

/**
 * Token efímero para hablar con Gemini. La LLAVE de Gemini nunca vive en el cel.
 *
 * Dos fuentes:
 *  - Banco de pruebas: un token acuñado en la Mac (`puente/token.mjs`) y pasado por adb. Caduca en
 *    minutos y sirve una vez (`uses: 1`), así que puede viajar por adb; la llave no.
 *  - El Worker propio (`token-worker/`), con el contrato de `puente/ESPEC_GEMINI_LIVE_PARA_ANDROID.md` §2.1:
 *    `POST <worker>/token?idioma=es&max_min=N&modo=M` con la cabecera `X-Device-Secret` y los mismos tres en el
 *    cuerpo `{"idioma":"es","max_min":N,"modo":"M"}` (2.4: así sirve con el Worker viejo, que solo lee `idioma` del
 *    cuerpo, y con el nuevo, que lee la URL primero). `max_min` es el techo de ESA llamada: el token vive
 *    60 + max_min·60 + 30 s. Responde `{"ok":true,"token":"auth_tokens/…","modelo":"…","idioma":"es","maxMin":N,
 *    "expira":"…","abrirAntes":"…","quedanHoy":N}`; los errores traen `{"ok":false,"error":"…"}` y la tabla de §2.1
 *    dice cuáles se reintentan, siempre UNA sola vez.
 *
 * El token y el secreto NUNCA se escriben en la bitácora ni en logcat.
 */
final class Tokens {
    static final String PREFS = "isabella_llamadas"; // mismo archivo que Ajustes
    private static final String CLAVE_SECRETO = "device_secret";

    /** Lo que devolvió el Worker. Sin token (`ok()` falso), `error` dice por qué. */
    static final class Resultado {
        final String token;
        final String modelo;        // sin «models/»; vacío si no vino
        final String error;         // código del Worker («no_existe», «tope_minuto»…), «red» o «sin_token»
        final int http;             // 0 si no hubo respuesta
        final int reintentarEnSeg;  // -1 = no se reintenta; si no, espera antes del único reintento
        final int maxMin;           // minutos para los que salió el token; -1 si el Worker no lo dijo (Worker viejo)
        final long venceMs;         // cuándo vence el token, en el reloj del cel; 0 si no se supo

        Resultado(String token, String modelo, String error, int http, int reintentarEnSeg) {
            this(token, modelo, error, http, reintentarEnSeg, -1, 0);
        }

        Resultado(String token, String modelo, String error, int http, int reintentarEnSeg, int maxMin, long venceMs) {
            this.token = token == null ? "" : token;
            this.modelo = modelo == null ? "" : modelo;
            this.error = error == null ? "" : error;
            this.http = http;
            this.reintentarEnSeg = reintentarEnSeg;
            this.maxMin = maxMin;
            this.venceMs = venceMs;
        }

        boolean ok() {
            return !token.isEmpty();
        }
    }

    private Tokens() {}

    static String secreto(Context ctx) {
        return prefs(ctx).getString(CLAVE_SECRETO, "");
    }

    static void guardarSecreto(Context ctx, String secreto) {
        prefs(ctx).edit().putString(CLAVE_SECRETO, secreto).apply();
    }

    /** Igual, pero escrito en disco antes de volver (importar_secreto limpia el archivo después). */
    static boolean guardarSecretoYa(Context ctx, String secreto) {
        return prefs(ctx).edit().putString(CLAVE_SECRETO, secreto).commit();
    }

    /**
     * Pide un token para una llamada de `maxMin` minutos con el preámbulo `modo` (servicios | isabella | flexible) y
     * aplica la tabla de §2.1: a lo más UN reintento, con la espera que diga la tabla.
     */
    static Resultado pedirAlWorker(String urlWorker, String secretoDispositivo, String idioma, int maxMin, String modo) {
        Resultado r = pedirUnaVez(urlWorker, secretoDispositivo, idioma, maxMin, modo);
        if (r.ok() || r.reintentarEnSeg < 0) return r;
        Bitacora.log("Token: reintento único en " + r.reintentarEnSeg + " s.");
        try {
            Thread.sleep(r.reintentarEnSeg * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resultado("", "", r.error, r.http, -1);
        }
        Resultado r2 = pedirUnaVez(urlWorker, secretoDispositivo, idioma, maxMin, modo);
        if (r2.ok()) return r2;
        Bitacora.log("Token: el reintento también falló (" + r2.error + "); no hay otro.");
        return new Resultado("", "", r2.error, r2.http, -1);
    }

    /** Acepta «https://host» o «https://host/token»; sin ruta, agrega /token. */
    static String urlDeToken(String urlWorker) {
        String u = urlWorker == null ? "" : urlWorker.trim();
        try {
            String ruta = java.net.URI.create(u).getRawPath();
            if (ruta == null || ruta.isEmpty() || "/".equals(ruta)) {
                while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                return u + "/token";
            }
        } catch (Exception ignorado) {
            // la URL mala falla abajo, con su propio aviso
        }
        return u;
    }

    /** «…/token» + «?idioma=es&max_min=3&modo=servicios» (o «&…» si la URL ya traía consulta). */
    static String urlConParametros(String urlWorker, String idioma, int maxMin, String modo) {
        String u = urlDeToken(urlWorker);
        return u + (u.contains("?") ? "&" : "?") + "idioma=" + lengua(idioma) + "&max_min=" + Ajustes.limitar(maxMin)
                + "&modo=" + Ajustes.modoOPorDefecto(modo);
    }

    /** El mismo pedido en el cuerpo, para el Worker viejo (solo lee `idioma` de aquí). */
    static String cuerpoDelPedido(String idioma, int maxMin, String modo) {
        try {
            return new JSONObject().put("idioma", lengua(idioma)).put("max_min", Ajustes.limitar(maxMin))
                    .put("modo", Ajustes.modoOPorDefecto(modo)).toString();
        } catch (Exception e) {
            return "{\"idioma\":\"es\"}"; // claves fijas: no pasa
        }
    }

    private static String lengua(String idioma) {
        return "en".equals(idioma) ? "en" : "es";
    }

    /**
     * Cuándo vence el token en el reloj del cel: con la hora del Worker (cabecera Date) se resta su propio reloj, así
     * no importa si el del cel está movido; la cabecera viene en segundos enteros, por eso se resta 1 s de colchón.
     * 0 si `expira` no vino o no se entiende.
     */
    static long venceEnRelojDelCel(String expira, long fechaServidorMs, long ahoraCelMs) {
        if (expira == null || expira.isEmpty()) return 0;
        long expiraMs;
        try {
            expiraMs = java.time.Instant.parse(expira).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
        return fechaServidorMs > 0 ? ahoraCelMs + (expiraMs - fechaServidorMs) - 1000 : expiraMs;
    }

    private static Resultado pedirUnaVez(String urlWorker, String secreto, String idioma, int maxMin, String modo) {
        HttpURLConnection con = null;
        try {
            URL url = new URL(urlConParametros(urlWorker, idioma, maxMin, modo));
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(6000);
            con.setReadTimeout(8000);
            con.setUseCaches(false);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("X-Device-Secret", secreto);
            byte[] cuerpo = cuerpoDelPedido(idioma, maxMin, modo).getBytes("UTF-8");
            con.setFixedLengthStreamingMode(cuerpo.length);
            try (OutputStream os = con.getOutputStream()) {
                os.write(cuerpo);
            }
            int http = con.getResponseCode();
            String texto = leer(http >= 400 ? con.getErrorStream() : con.getInputStream());
            JSONObject j;
            try {
                j = new JSONObject(texto);
            } catch (Exception e) {
                j = new JSONObject();
            }
            if (http == 200 && j.optBoolean("ok", false)) {
                String token = j.optString("token", "");
                if (token.startsWith("auth_tokens/")) {
                    String modelo = j.optString("modelo", "");
                    int minutosToken = j.has("maxMin") ? j.optInt("maxMin", -1) : -1;
                    String expira = j.optString("expira", "");
                    long ahora = System.currentTimeMillis();
                    long vence = venceEnRelojDelCel(expira, con.getHeaderFieldDate("Date", 0), ahora);
                    String modoPedido = Ajustes.modoOPorDefecto(modo);
                    String modoDicho = j.optString("modo", "");
                    Bitacora.log("Token: obtenido del Worker (no se muestra) · modelo " + (modelo.isEmpty() ? "?" : modelo)
                            + " · " + (minutosToken > 0 ? "maxMin " + minutosToken + (minutosToken == Ajustes.limitar(maxMin) ? "" : " (OJO: pedí " + Ajustes.limitar(maxMin) + ")")
                                    : "el Worker no dijo maxMin (Worker viejo: token de 6 min)")
                            + " · expira " + (expira.isEmpty() ? "?" : expira)
                            + (vence > 0 ? String.format(java.util.Locale.US, " (vida %d s)", Math.round((vence - ahora) / 1000.0)) : "")
                            + " · modo " + modoPedido + (modoDicho.isEmpty() ? " (el Worker no lo confirmó)"
                                    : (modoDicho.equals(modoPedido) ? "" : " (OJO: el Worker dijo " + modoDicho + ")"))
                            + " · quedan hoy " + j.optInt("quedanHoy", -1) + ".");
                    return new Resultado(token, modelo, "", http, -1, minutosToken, vence);
                }
                Bitacora.log("Token: el Worker respondió 200 sin un token válido; no reintento.");
                return new Resultado("", "", "sin_token", http, -1);
            }
            String error = j.optString("error", "");
            if (error.isEmpty()) error = "http_" + http;
            int espera = esperaSegunTabla(http, error, con.getHeaderField("retry-after"));
            Bitacora.log("Token: el Worker respondió " + http + " (" + error + ")" + (espera >= 0 ? "." : "; " + porQueNoReintento(error)));
            return new Resultado("", "", error, http, espera);
        } catch (Exception e) {
            Bitacora.log("Token: falló la petición al Worker (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ").");
            return new Resultado("", "", "red", 0, -1);
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Tabla de §2.1: segundos de espera del único reintento, o -1 si ese error no se reintenta. */
    static int esperaSegunTabla(int http, String error, String retryAfter) {
        if (http == 429 && "tope_minuto".equals(error)) {
            int s = entero(retryAfter, 60);
            return Math.max(1, Math.min(60, s));
        }
        if (http == 502 && ("gemini".equals(error) || "gemini_sin_respuesta".equals(error) || "gemini_sin_token".equals(error))) {
            return 2;
        }
        if (http == 503 && "topes_no_disponibles".equals(error)) return 2;
        return -1; // no_existe, json_invalido, cuerpo_grande, tope_dia, cuota_gemini, sin_llave y lo desconocido
    }

    static String porQueNoReintento(String error) {
        switch (error) {
            case "no_existe": return "secreto o URL mal (el Worker no distingue cuál). No reintento.";
            case "idioma_invalido":
            case "max_min_invalido":
            case "modo_invalido": return "parámetro fuera de rango: error de la app. No reintento.";
            case "setup_faltante": return "al Worker le falta esa variante del setup (falta redesplegarlo). No reintento.";
            case "tope_dia": return "ya no hay tokens hoy (tope del día de la CDMX). No reintento.";
            case "cuota_gemini": return "se acabó la cuota de Google. No reintento.";
            case "sin_llave": return "al Worker le falta la llave de Gemini. No reintento.";
            case "json_invalido":
            case "cuerpo_grande": return "error de la app. No reintento.";
            default: return "no reintento.";
        }
    }

    private static int entero(String s, int porDefecto) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return porDefecto;
        }
    }

    private static String leer(InputStream in) {
        if (in == null) return "";
        try (Scanner sc = new Scanner(in, "UTF-8")) {
            return sc.useDelimiter("\\A").hasNext() ? sc.next() : "";
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
