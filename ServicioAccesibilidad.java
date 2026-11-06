// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * Servicio de accesibilidad MÍNIMO y OPCIONAL, apagado por defecto.
 *
 * Único motivo de existir: en Android 10+ una app normal recibe SILENCIO del micrófono mientras hay
 * una llamada celular activa; la excepción documentada por Google es que la app sea un servicio de
 * accesibilidad habilitado (developer.android.com/media/platform/sharing-audio-input). Con él activo,
 * la captura con la fuente VOICE_RECOGNITION sí recibe el audio del altavoz, que es lo que Isabella
 * necesita oír del interlocutor.
 *
 * NO lee el contenido de la pantalla (sin canRetrieveWindowContent), no actúa sobre eventos y no hace
 * nada con ellos: el método está vacío a propósito. Lo habilita y lo apaga David por adb en SU cel de
 * pruebas (ver README). David lo autorizó por escrito el 27-oct. No se enciende solo.
 */
public class ServicioAccesibilidad extends AccessibilityService {

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Bitacora.log("Servicio de accesibilidad conectado: la app ya puede oír el altavoz en llamada (fuente de reconocimiento).");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent evento) {
        // A propósito vacío: este servicio no observa ni actúa sobre la pantalla.
    }

    @Override
    public void onInterrupt() {
        // No hay nada que interrumpir.
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        Bitacora.log("Servicio de accesibilidad desconectado.");
        return super.onUnbind(intent);
    }
}
