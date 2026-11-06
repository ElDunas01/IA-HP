// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * El archivo que deja la Mac para `importar_secreto` (Java puro, se prueba en la Mac con pruebas_mac/).
 * Orden que usa el servicio: leer → validar → guardar en SharedPreferences → sobrescribir con ceros → vaciar.
 * Nunca se borra el archivo (regla de la casa) y nada de aquí escribe en la bitácora.
 */
final class ArchivoSecreto {
    static final int MAXIMO_BYTES = 4096;

    private ArchivoSecreto() {}

    /** Contenido recortado; null si no existe, está vacío, mide más de MAXIMO_BYTES o no se pudo leer. */
    static String leer(File f) {
        if (f == null || !f.isFile()) return null;
        long largo = f.length();
        if (largo <= 0 || largo > MAXIMO_BYTES) return null;
        byte[] datos = new byte[(int) largo];
        try (FileInputStream in = new FileInputStream(f)) {
            int leidos = 0;
            while (leidos < datos.length) {
                int r = in.read(datos, leidos, datos.length - leidos);
                if (r < 0) break;
                leidos += r;
            }
            return new String(datos, 0, leidos, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return null;
        } finally {
            Arrays.fill(datos, (byte) 0);
        }
    }

    /** Lo que cabe en la cabecera X-Device-Secret: ASCII visible, sin espacios, de 16 a 512 caracteres. */
    static boolean valido(String s) {
        if (s == null || s.length() < 16 || s.length() > 512) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) return false;
        }
        return true;
    }

    /** Escribe ceros sobre los `largo` bytes del archivo y sincroniza. */
    static boolean sobrescribirConCeros(File f, long largo) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            byte[] ceros = new byte[(int) Math.max(1, Math.min(largo, MAXIMO_BYTES))];
            raf.seek(0);
            long restante = largo;
            while (restante > 0) {
                int n = (int) Math.min(restante, ceros.length);
                raf.write(ceros, 0, n);
                restante -= n;
            }
            raf.getFD().sync();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Deja el archivo en 0 bytes, sin borrarlo. */
    static boolean vaciar(File f) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
            raf.setLength(0);
            raf.getFD().sync();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
