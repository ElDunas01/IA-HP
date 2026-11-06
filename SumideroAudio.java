// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas.audio;

import android.media.AudioDeviceInfo;

/**
 * Sumidero de audio: contrato que abstrae AudioTrack (nativo) y el pipeline por su + tinyplay (raíz).
 * LlamadaService consume la interfaz y nunca ve AudioTrack directamente desde construirReproductor.
 */
public interface SumideroAudio {
    int ESTADO_LISTO = 1;

    int getState();
    void play();
    int write(byte[] b, int off, int len);
    AudioDeviceInfo getDispositivoRuta();
    void pause();
    void flush();
    void stop();
    void release();
}
