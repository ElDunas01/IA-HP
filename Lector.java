// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package org.json;
/** Analizador JSON recursivo (objetos, arreglos, cadenas con escapes, números, true/false/null). */
final class Lector {
    private final String s; private int i;
    Lector(String s) { this.s = s == null ? "" : s; }
    Object raiz() throws JSONException { Object v = valor(); blancos(); if (i != s.length()) throw new JSONException("basura al final"); return v; }
    private void blancos() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private Object valor() throws JSONException {
        blancos();
        if (i >= s.length()) throw new JSONException("fin inesperado");
        char c = s.charAt(i);
        if (c == '{') return objeto();
        if (c == '[') return arreglo();
        if (c == '"') return cadena();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return JSONObject.NULL; }
        return numero();
    }
    private JSONObject objeto() throws JSONException {
        JSONObject o = new JSONObject(); i++; blancos();
        if (i < s.length() && s.charAt(i) == '}') { i++; return o; }
        while (true) {
            blancos(); if (i >= s.length() || s.charAt(i) != '"') throw new JSONException("se esperaba clave en " + i);
            String k = cadena(); blancos();
            if (i >= s.length() || s.charAt(i) != ':') throw new JSONException("se esperaba ':' en " + i);
            i++; o.m.put(k, valor()); blancos();
            if (i >= s.length()) throw new JSONException("objeto sin cerrar");
            char c = s.charAt(i++);
            if (c == '}') return o;
            if (c != ',') throw new JSONException("se esperaba ',' o '}' en " + (i - 1));
        }
    }
    private JSONArray arreglo() throws JSONException {
        JSONArray a = new JSONArray(); i++; blancos();
        if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
        while (true) {
            a.put(valor()); blancos();
            if (i >= s.length()) throw new JSONException("arreglo sin cerrar");
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw new JSONException("se esperaba ',' o ']' en " + (i - 1));
        }
    }
    private String cadena() throws JSONException {
        StringBuilder sb = new StringBuilder(); i++;
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break; case '\\': sb.append('\\'); break; case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break; case 'f': sb.append('\f'); break; case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break; case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw new JSONException("\\u corto");
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: throw new JSONException("escape raro \\" + e);
            }
        }
        throw new JSONException("cadena sin cerrar");
    }
    private Object numero() throws JSONException {
        int ini = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        String t = s.substring(ini, i);
        if (t.isEmpty()) throw new JSONException("valor raro en " + ini);
        try {
            if (t.contains(".") || t.contains("e") || t.contains("E")) return Double.parseDouble(t);
            long l = Long.parseLong(t);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        } catch (NumberFormatException e) { throw new JSONException("número raro: " + t); }
    }
}
