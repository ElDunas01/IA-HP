// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package android.util;
/** Sustituto para la Mac: mismas constantes que Android; NO_WRAP es lo único que usa la app al codificar. */
public final class Base64 {
    public static final int DEFAULT = 0, NO_PADDING = 1, NO_WRAP = 2, CRLF = 4, URL_SAFE = 8, NO_CLOSE = 16;
    public static String encodeToString(byte[] in, int flags) { return java.util.Base64.getEncoder().encodeToString(in); }
    public static byte[] decode(String s, int flags) { return java.util.Base64.getMimeDecoder().decode(s); }
}
