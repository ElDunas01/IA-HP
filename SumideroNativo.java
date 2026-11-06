// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas.audio;

import android.media.AudioDeviceInfo;
import android.media.AudioTrack;

/** Wrapper trivial sobre AudioTrack — equivalente bit a bit a la 2.6. */
public final class SumideroNativo implements SumideroAudio {
    private final AudioTrack track;

    public SumideroNativo(AudioTrack track) {
        this.track = track;
    }

    @Override public int getState() {
        return track.getState() == AudioTrack.STATE_INITIALIZED ? ESTADO_LISTO : 0;
    }

    @Override public void play() { track.play(); }

    @Override public int write(byte[] b, int off, int len) {
        return track.write(b, off, len, AudioTrack.WRITE_BLOCKING);
    }

    @Override public AudioDeviceInfo getDispositivoRuta() {
        try { return track.getRoutedDevice(); } catch (Exception ignorado) { return null; }
    }

    @Override public void pause() { try { track.pause(); } catch (Exception ignorado) {} }
    @Override public void flush() { try { track.flush(); } catch (Exception ignorado) {} }
    @Override public void stop()  { try { track.stop();  } catch (Exception ignorado) {} }
    @Override public void release() { try { track.release(); } catch (Exception ignorado) {} }
}
