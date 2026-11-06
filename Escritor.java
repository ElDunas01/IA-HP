// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package org.json;
import java.util.Map;
/** Serializa como JSONStringer de Android (que también escapa la diagonal: «\/»). */
final class Escritor {
    static void valor(StringBuilder sb, Object v) {
        if (v == null || v == JSONObject.NULL) { sb.append("null"); return; }
        if (v instanceof JSONObject) {
            sb.append('{'); boolean primero = true;
            for (Map.Entry<String, Object> e : ((JSONObject) v).m.entrySet()) {
                if (!primero) sb.append(','); primero = false;
                cadena(sb, e.getKey()); sb.append(':'); valor(sb, e.getValue());
            }
            sb.append('}'); return;
        }
        if (v instanceof JSONArray) {
            sb.append('['); boolean primero = true;
            for (Object o : ((JSONArray) v).l) { if (!primero) sb.append(','); primero = false; valor(sb, o); }
            sb.append(']'); return;
        }
        if (v instanceof Boolean || v instanceof Integer || v instanceof Long) { sb.append(v); return; }
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d); else sb.append(d);
            return;
        }
        cadena(sb, String.valueOf(v));
    }
    static void cadena(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '/': sb.append("\\/"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }
}
