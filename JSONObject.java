// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package org.json;
import java.util.Iterator;
import java.util.LinkedHashMap;
/** Sustituto mínimo de org.json de Android para correr la app en la Mac (mismos métodos y semántica opt*). */
public class JSONObject {
    public static final Object NULL = new Object() { @Override public String toString() { return "null"; } };
    final LinkedHashMap<String, Object> m = new LinkedHashMap<>();
    public JSONObject() {}
    public JSONObject(String json) throws JSONException {
        Object v = new Lector(json).raiz();
        if (!(v instanceof JSONObject)) throw new JSONException("no es un objeto JSON");
        m.putAll(((JSONObject) v).m);
    }
    public JSONObject put(String k, Object v) throws JSONException {
        if (k == null) throw new JSONException("clave nula");
        if (v == null) m.remove(k); else m.put(k, v);
        return this;
    }
    public JSONObject put(String k, boolean v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, int v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, long v) throws JSONException { return put(k, (Object) v); }
    public JSONObject put(String k, double v) throws JSONException { return put(k, (Object) v); }
    public boolean has(String k) { return m.containsKey(k); }
    public Object opt(String k) { return k == null ? null : m.get(k); }
    public JSONObject optJSONObject(String k) { Object v = opt(k); return v instanceof JSONObject ? (JSONObject) v : null; }
    public JSONArray optJSONArray(String k) { Object v = opt(k); return v instanceof JSONArray ? (JSONArray) v : null; }
    public String optString(String k) { return optString(k, ""); }
    public String optString(String k, String def) { Object v = opt(k); return v == null ? def : String.valueOf(v); }
    public boolean optBoolean(String k, boolean def) {
        Object v = opt(k);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) { if ("true".equalsIgnoreCase((String) v)) return true; if ("false".equalsIgnoreCase((String) v)) return false; }
        return def;
    }
    public int optInt(String k, int def) {
        Object v = opt(k);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) { try { return (int) Double.parseDouble((String) v); } catch (NumberFormatException e) { return def; } }
        return def;
    }
    public Iterator<String> keys() { return m.keySet().iterator(); }
    @Override public String toString() { StringBuilder sb = new StringBuilder(); Escritor.valor(sb, this); return sb.toString(); }
}
