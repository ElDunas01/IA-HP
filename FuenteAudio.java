// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas.audio;

import android.media.AudioDeviceInfo;

/**
 * Fuente de audio: contrato que abstrae AudioRecord (nativo) y el pipeline por su + tinycap (raíz).
 * Las dos implementaciones viven en este paquete; LlamadaService consume la interfaz y nunca ve
 * AudioRecord directamente desde las fábricas construirGrabadora/construirReproductor.
 */
public interface FuenteAudio {
    int ESTADO_LISTO = 1;

    int getState();
    void startRecording();
    int read(byte[] b, int off, int len);
    int getAudioSessionId();
    boolean isClientSilenced();
    AudioDeviceInfo getDispositivoRuta();
    void stop();
    void release();
}
