// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package org.json;
import java.util.ArrayList;
public class JSONArray {
    final ArrayList<Object> l = new ArrayList<>();
    public JSONArray() {}
    public JSONArray put(Object v) { l.add(v); return this; }
    public int length() { return l.size(); }
    public Object opt(int i) { return i >= 0 && i < l.size() ? l.get(i) : null; }
    public JSONObject optJSONObject(int i) { Object v = opt(i); return v instanceof JSONObject ? (JSONObject) v : null; }
    public String optString(int i) { Object v = opt(i); return v == null ? "" : String.valueOf(v); }
    @Override public String toString() { StringBuilder sb = new StringBuilder(); Escritor.valor(sb, this); return sb.toString(); }
}
