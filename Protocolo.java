// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Protocolo «PCM16-TCP v1» (lo comparten el cel y el puente en la Mac; no se cambia sin avisar al director).
 *
 * Cada mensaje es: 1 byte tipo + 4 bytes longitud (big-endian, sin signo) + la carga.
 *
 *   0x01 HELLO     cel→Mac   JSON {"app","ver","rate","numero"}
 *   0x02 AUDIO_IN  cel→Mac   PCM16 mono little-endian 16 kHz, tramas de 20 ms (640 bytes): micrófono del cel
 *   0x03 AUDIO_OUT Mac→cel   PCM16 mono little-endian 24 kHz (voz de Isabella), trozos <= 4800 bytes
 *   0x04 EVENT     ambos     JSON (call_state, isabella_speaking, text, hangup)
 *   0x05 PING      ambos     vacío
 *   0x06 PONG      ambos     vacío
 */
final class Protocolo {
    static final int HELLO = 0x01;
    static final int AUDIO_IN = 0x02;
    static final int AUDIO_OUT = 0x03;
    static final int EVENT = 0x04;
    static final int PING = 0x05;
    static final int PONG = 0x06;

    static final int RATE_MIC = 16000;   // lo que manda el cel
    static final int RATE_VOZ = 24000;   // lo que manda la Mac (voz de Isabella)
    static final int TRAMA_MIC_BYTES = 640; // 20 ms a 16 kHz mono PCM16

    /** Tope defensivo: una carga más grande que esto es una trama corrupta; se corta la conexión. */
    static final int MAX_CARGA = 1 << 20; // 1 MB

    private Protocolo() {}

    /** Una trama recibida. */
    static final class Trama {
        final int tipo;
        final byte[] carga;

        Trama(int tipo, byte[] carga) {
            this.tipo = tipo;
            this.carga = carga;
        }
    }

    /** Escribe una trama. Sincroniza fuera si varios hilos comparten el mismo flujo. */
    static void escribir(OutputStream salida, int tipo, byte[] carga) throws IOException {
        int n = carga != null ? carga.length : 0;
        byte[] cabecera = new byte[5];
        cabecera[0] = (byte) tipo;
        cabecera[1] = (byte) (n >>> 24);
        cabecera[2] = (byte) (n >>> 16);
        cabecera[3] = (byte) (n >>> 8);
        cabecera[4] = (byte) n;
        salida.write(cabecera);
        if (n > 0) salida.write(carga);
        salida.flush();
    }

    /** Lee una trama completa. Devuelve null al cerrarse limpio el flujo (EOF en la cabecera). */
    static Trama leer(InputStream entrada) throws IOException {
        DataInputStream in = entrada instanceof DataInputStream ? (DataInputStream) entrada : new DataInputStream(entrada);
        int tipo = in.read();
        if (tipo < 0) return null; // el otro lado cerró
        int n;
        try {
            n = in.readInt();
        } catch (EOFException fin) {
            return null;
        }
        if (n < 0 || n > MAX_CARGA) {
            throw new IOException("Trama con longitud inválida: " + n);
        }
        byte[] carga = new byte[n];
        in.readFully(carga);
        return new Trama(tipo, carga);
    }
}
