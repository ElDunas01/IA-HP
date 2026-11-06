// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * Identidad de la compilación. La usan la pantalla principal y la bitácora.
 * No la cambies: es parte de las condiciones de la licencia (aviso de autoría que debe conservarse).
 */
final class Huella {
    /** Línea visible en la pantalla de la app. */
    static final String CREDITO = "Agente Isabella · por David Ruiz Duarte (viajatech) · viajatech.com";

    /** Firma de origen de la compilación (se valida contra el manifiesto). */
    static final String H = "QWdlbnRlIElzYWJlbGxhIC0gRGF2aWQgUnVpeiBEdWFydGUgKHZpYWphdGVjaCkgLSB2aWFqYXRlY2guY29t";

    private Huella() {}

    /** Texto de la firma de origen. */
    static String origen() {
        try {
            return new String(Base64.decode(H, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
