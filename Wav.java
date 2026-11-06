// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Escritor de archivos WAV (PCM16 mono) para las grabaciones del modo Diagnóstico y, desde la 2.5, de las llamadas.
 *
 * Se abre, se le van echando tramas de bytes PCM16 little-endian y, al cerrar, rellena los dos
 * tamaños de la cabecera RIFF/WAVE (que no se conocen hasta el final). Así el agente `dispositivo`
 * baja el .wav con `adb pull` y lo oye en la Mac.
 */
final class Wav {
    private final RandomAccessFile archivo;
    private final int rate;
    private int bytesPcm;

    Wav(String ruta, int rate) throws IOException {
        this.rate = rate;
        this.archivo = new RandomAccessFile(ruta, "rw");
        this.archivo.setLength(0);
        this.archivo.write(new byte[44]); // hueco para la cabecera; se rellena al cerrar
    }

    void escribir(byte[] datos, int largo) throws IOException {
        archivo.write(datos, 0, largo);
        bytesPcm += largo;
    }

    void cerrar() throws IOException {
        try {
            escribirCabecera();
        } finally {
            archivo.close();
        }
    }

    /**
     * Reescribe la cabecera con los tamaños de ahora y vuelve al final (2.5, grabación de llamadas): así el archivo
     * se puede oír hasta ese punto aunque el proceso muera antes de cerrar.
     */
    void cabecera() throws IOException {
        long fin = archivo.getFilePointer();
        escribirCabecera();
        archivo.seek(fin);
    }

    private void escribirCabecera() throws IOException {
        int canales = 1, bitsPorMuestra = 16;
        int bytesPorSegundo = rate * canales * bitsPorMuestra / 8;
        int alineacion = canales * bitsPorMuestra / 8;
        archivo.seek(0);
        archivo.write(new byte[]{'R', 'I', 'F', 'F'});
        escribirLE32(36 + bytesPcm);
        archivo.write(new byte[]{'W', 'A', 'V', 'E'});
        archivo.write(new byte[]{'f', 'm', 't', ' '});
        escribirLE32(16);                 // tamaño del bloque fmt
        escribirLE16(1);                  // PCM
        escribirLE16(canales);
        escribirLE32(rate);
        escribirLE32(bytesPorSegundo);
        escribirLE16(alineacion);
        escribirLE16(bitsPorMuestra);
        archivo.write(new byte[]{'d', 'a', 't', 'a'});
        escribirLE32(bytesPcm);
    }

    int bytesPcm() {
        return bytesPcm;
    }

    private void escribirLE32(int v) throws IOException {
        archivo.write(new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)});
    }

    private void escribirLE16(int v) throws IOException {
        archivo.write(new byte[]{(byte) v, (byte) (v >>> 8)});
    }

    /** Valor eficaz (RMS) de un bloque PCM16 little-endian; sirve para saber si de verdad se grabó algo. */
    static double rms(byte[] pcm, int largo) {
        long suma = 0;
        int muestras = largo / 2;
        if (muestras == 0) return 0;
        for (int i = 0; i + 1 < largo; i += 2) {
            int m = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            suma += (long) m * m;
        }
        return Math.sqrt((double) suma / muestras);
    }
}
