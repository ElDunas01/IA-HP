// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cliente TCP del puente en la Mac. Implementa el lado del cel del protocolo «PCM16-TCP v1».
 *
 * - Se conecta (hasta 3 intentos) y manda HELLO.
 * - Un hilo lee tramas: AUDIO_OUT y EVENT se entregan al oyente; a un PING responde PONG.
 * - Manda AUDIO_IN (micrófono) y EVENT (estado de la llamada) desde otros hilos; las escrituras
 *   se serializan con un candado sobre el flujo de salida.
 * - Un PING cada 5 s mantiene viva la conexión y detecta cortes.
 *
 * Si no logra conectarse, avisa al oyente y NO revienta la llamada: la llamada y el diagnóstico
 * siguen funcionando aunque no haya puente (así el agente `dispositivo` prueba sin la Mac).
 */
final class PuenteCliente {

    interface Oyente {
        void onConectado();
        void onAudioVoz(byte[] pcm24k);       // AUDIO_OUT: voz de Isabella
        void onEvento(JSONObject evento);     // EVENT desde la Mac
        void onDesconectado(String motivo);   // se cerró o nunca conectó
    }

    private final String ip;
    private final int puerto;
    private final String numero;
    private final Oyente oyente;

    private final AtomicBoolean vivo = new AtomicBoolean(false);
    private final Object candadoSalida = new Object();
    private Socket socket;
    private OutputStream salida;
    private Thread hiloPing;

    PuenteCliente(String ip, int puerto, String numero, Oyente oyente) {
        this.ip = ip;
        this.puerto = puerto;
        this.numero = numero;
        this.oyente = oyente;
    }

    /** Arranca la conexión en su propio hilo; no bloquea a quien llama. */
    void conectar() {
        new Thread(this::correr, "puente-conectar").start();
    }

    private void correr() {
        IOException ultima = null;
        for (int intento = 1; intento <= 3 && !Thread.currentThread().isInterrupted(); intento++) {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(ip, puerto), 4000);
                s.setTcpNoDelay(true); // el audio en tiempo real no espera a llenar paquetes
                socket = s;
                salida = new BufferedOutputStream(s.getOutputStream());
                vivo.set(true);
                Bitacora.log("Puente: conectado a " + ip + ":" + puerto + " (intento " + intento + ")");
                enviarHello();
                oyente.onConectado();
                lanzarPing();
                leerSiempre(new BufferedInputStream(s.getInputStream()));
                return;
            } catch (IOException e) {
                ultima = e;
                Bitacora.log("Puente: intento " + intento + " falló (" + e.getMessage() + ")");
                dormir(1000);
            }
        }
        vivo.set(false);
        oyente.onDesconectado(ultima != null ? ultima.getMessage() : "sin conexión");
    }

    private void enviarHello() throws IOException {
        JSONObject j = new JSONObject();
        try {
            j.put("app", "isabellallamadas");
            j.put("ver", 1);
            j.put("rate", Protocolo.RATE_MIC);
            j.put("numero", numero);
        } catch (org.json.JSONException ignorado) {
            // claves fijas: no puede fallar
        }
        escribir(Protocolo.HELLO, j.toString().getBytes("UTF-8"));
    }

    private void leerSiempre(InputStream entrada) {
        try {
            Protocolo.Trama t;
            while (vivo.get() && (t = Protocolo.leer(entrada)) != null) {
                switch (t.tipo) {
                    case Protocolo.AUDIO_OUT:
                        oyente.onAudioVoz(t.carga);
                        break;
                    case Protocolo.EVENT:
                        oyente.onEvento(new JSONObject(new String(t.carga, "UTF-8")));
                        break;
                    case Protocolo.PING:
                        escribir(Protocolo.PONG, null);
                        break;
                    case Protocolo.PONG:
                        break; // respuesta a nuestro PING: la conexión sigue viva
                    default:
                        Bitacora.log("Puente: trama de tipo desconocido 0x" + Integer.toHexString(t.tipo));
                }
            }
            cerrarYAvisar("el puente cerró la conexión");
        } catch (Exception e) {
            cerrarYAvisar(e.getMessage());
        }
    }

    private void lanzarPing() {
        hiloPing = new Thread(() -> {
            while (vivo.get()) {
                dormir(5000);
                if (!vivo.get()) break;
                try {
                    escribir(Protocolo.PING, null);
                } catch (IOException e) {
                    cerrarYAvisar("se perdió el puente (ping)");
                    break;
                }
            }
        }, "puente-ping");
        hiloPing.setDaemon(true);
        hiloPing.start();
    }

    /** Manda una trama de micrófono (640 bytes PCM16 16 kHz). Silencioso si ya no hay conexión. */
    void enviarAudioMic(byte[] pcm16k, int largo) {
        if (!vivo.get()) return;
        try {
            if (largo == pcm16k.length) {
                escribir(Protocolo.AUDIO_IN, pcm16k);
            } else {
                byte[] exacto = new byte[largo];
                System.arraycopy(pcm16k, 0, exacto, 0, largo);
                escribir(Protocolo.AUDIO_IN, exacto);
            }
        } catch (IOException e) {
            cerrarYAvisar("se perdió el puente (audio)");
        }
    }

    /** Manda un EVENT (p. ej. el estado de la llamada). */
    void enviarEvento(JSONObject evento) {
        if (!vivo.get()) return;
        try {
            escribir(Protocolo.EVENT, evento.toString().getBytes("UTF-8"));
        } catch (IOException e) {
            cerrarYAvisar("se perdió el puente (evento)");
        }
    }

    boolean conectado() {
        return vivo.get();
    }

    void cerrar() {
        vivo.set(false);
        if (hiloPing != null) hiloPing.interrupt();
        cerrarSocket();
    }

    private void escribir(int tipo, byte[] carga) throws IOException {
        synchronized (candadoSalida) {
            if (salida == null) throw new IOException("sin flujo de salida");
            Protocolo.escribir(salida, tipo, carga);
        }
    }

    private void cerrarYAvisar(String motivo) {
        if (vivo.compareAndSet(true, false)) {
            cerrarSocket();
            oyente.onDesconectado(motivo);
        }
    }

    private void cerrarSocket() {
        synchronized (candadoSalida) {
            try {
                if (socket != null) socket.close();
            } catch (IOException ignorado) {
                // ya está cerrándose
            }
            socket = null;
            salida = null;
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
