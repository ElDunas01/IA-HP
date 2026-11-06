// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas.audio;

import android.media.AudioDeviceInfo;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;

/** Wrapper trivial sobre AudioRecord — equivalente bit a bit a la 2.6. */
public final class FuenteNativa implements FuenteAudio {
    private final AudioRecord rec;

    public FuenteNativa(AudioRecord rec) {
        this.rec = rec;
    }

    @Override public int getState() {
        return rec.getState() == AudioRecord.STATE_INITIALIZED ? ESTADO_LISTO : 0;
    }

    @Override public void startRecording() { rec.startRecording(); }

    @Override public int read(byte[] b, int off, int len) {
        return rec.read(b, off, len, AudioRecord.READ_BLOCKING);
    }

    @Override public int getAudioSessionId() {
        try { return rec.getAudioSessionId(); } catch (Exception ignorado) { return 0; }
    }

    @Override public boolean isClientSilenced() {
        try {
            AudioRecordingConfiguration cfg = rec.getActiveRecordingConfiguration();
            return cfg != null && cfg.isClientSilenced();
        } catch (Exception ignorado) {
            return false;
        }
    }

    @Override public AudioDeviceInfo getDispositivoRuta() {
        try { return rec.getRoutedDevice(); } catch (Exception ignorado) { return null; }
    }

    @Override public void stop() {
        try { rec.stop(); } catch (Exception ignorado) {}
    }

    @Override public void release() {
        try { rec.release(); } catch (Exception ignorado) {}
    }
}
