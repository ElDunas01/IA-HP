// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package android.content;
public interface SharedPreferences {
    String getString(String key, String defValue);
    int getInt(String key, int defValue);
    Editor edit();
    interface Editor { Editor putString(String key, String value); Editor putInt(String key, int value); void apply(); boolean commit(); }
}
