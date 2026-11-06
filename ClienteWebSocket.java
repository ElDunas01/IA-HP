// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Cliente WebSocket mínimo (RFC 6455) sobre TLS, sin librerías externas.
 *
 * Lo usa la app para hablar directo con Gemini Live desde el cel (sin la Mac). Hace:
 *  - handshake HTTP de subida con `Sec-WebSocket-Key` y verifica `Sec-WebSocket-Accept`;
 *  - verifica el certificado y el nombre del servidor (identificación HTTPS), para que nadie se
 *    meta en medio (el token de Gemini viaja por aquí);
 *  - tramas del cliente SIEMPRE enmascaradas; longitudes de 7/16/64 bits;
 *  - reensambla tramas de texto fragmentadas (el JSON grande con audio base64 puede venir partido);
 *  - responde a PING con PONG y atiende el cierre;
 *  - `cerrar()` nunca espera a la red (2.4, hallazgo 1 del arquitecto): la trama 1000 sale como mejor
 *    esfuerzo con 300 ms de plazo y después se suelta el TCP de abajo SIN el candado de escritura. En
 *    Android, cerrar un Socket despierta a los hilos trabados en su descriptor (libcore,
 *    AsynchronousCloseMonitor), así que una escritura atorada sale con excepción y nadie se queda esperando.
 *    El TLS va encima de un TCP propio para poder hacer eso también durante el connect y el saludo.
 *
 * No sabe nada de Gemini: solo mueve texto y binario. El protocolo de Gemini vive en SesionGemini.
 */
final class ClienteWebSocket {

    interface Oyente {
        void onAbierto();
        void onTexto(String mensaje);
        void onBinario(byte[] datos);
        void onCerrado(int codigo, String motivo);
        void onError(String motivo);
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"; // RFC 6455
    private static final int OP_CONT = 0x0, OP_TEXTO = 0x1, OP_BINARIO = 0x2,
            OP_CIERRE = 0x8, OP_PING = 0x9, OP_PONG = 0xA;

    private final String host;
    private final int puerto;
    private final String rutaConConsulta; // p. ej. "/ws/...BidiGenerateContent?access_token=..."
    private final boolean tls;             // true = wss (TLS a Google); false = ws (SOLO mock en LAN)
    private final Oyente oyente;

    static final int PLAZO_CONEXION_MS = 8000;      // connect del TCP
    static final int PLAZO_SALUDO_MS = 10_000;      // TLS y subida HTTP: lecturas con plazo; ya en sesión, sin plazo
    static final long PLAZO_TRAMA_CIERRE_MS = 300;  // la trama 1000 es de mejor esfuerzo: después se suelta el socket

    private final AtomicBoolean abierto = new AtomicBoolean(false);
    private volatile boolean cerradoPorApp; // cerrar() pedido por la app: el hilo lector termina sin avisar error
    private final Object candadoEscritura = new Object();
    private final SecureRandom azar = new SecureRandom();
    private volatile Socket crudo;            // el TCP de abajo: cerrarlo desatora connect, TLS, escritura y lectura
    private volatile Socket socket;           // el de trabajo: TLS encima de `crudo`, o el mismo `crudo` en ws://
    private InputStream entrada;
    private volatile OutputStream salida;
    private volatile long escribiendoDesdeMs; // 0 = no hay escritura en curso; si no, cuándo empezó la que está en curso
    private Thread lector;

    ClienteWebSocket(String host, int puerto, String rutaConConsulta, boolean tls, Oyente oyente) {
        this.host = host;
        this.puerto = puerto;
        this.rutaConConsulta = rutaConConsulta;
        this.tls = tls;
        this.oyente = oyente;
    }

    /** Conecta en su propio hilo; no bloquea. */
    void conectar() {
        lector = new Thread(this::correr, "ws-gemini");
        lector.start();
    }

    private void correr() {
        try {
            Socket tcp = new Socket();
            crudo = tcp;
            if (cerradoPorApp) { // cerrar() llegó antes que el socket
                cerrarSocket();
                return;
            }
            tcp.connect(new InetSocketAddress(host, puerto), PLAZO_CONEXION_MS);
            tcp.setTcpNoDelay(true);
            Socket s;
            if (tls) {
                // TLS encima del TCP propio (createSocket con autoClose): así cerrar() puede soltar el TCP de abajo.
                SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
                SSLSocket ss = (SSLSocket) f.createSocket(tcp, host, puerto, true);
                // Verificar el nombre del servidor contra el certificado (evita intermediarios) y mandar el SNI.
                SSLParameters p = ss.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                try {
                    p.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(host)));
                } catch (IllegalArgumentException ignorado) {
                    // una IP literal no lleva SNI
                }
                ss.setSSLParameters(p);
                socket = ss;
                ss.setSoTimeout(PLAZO_SALUDO_MS);
                ss.startHandshake();
                s = ss;
            } else {
                // Texto plano: SOLO modo de prueba contra el mock en la LAN (lo controla LlamadaService).
                s = tcp;
                socket = tcp;
                tcp.setSoTimeout(PLAZO_SALUDO_MS);
            }
            entrada = new BufferedInputStream(s.getInputStream());
            salida = s.getOutputStream();

            if (!handshake()) {
                cerrarYAvisar(1002, "handshake WebSocket rechazado");
                return;
            }
            s.setSoTimeout(0); // en sesión el servidor calla mientras nadie habla: eso lo vigila LlamadaService
            abierto.set(true);
            if (cerradoPorApp) { // cerrar() llegó durante el saludo
                cerrarYAvisar(1000, "cerrado por la app");
                return;
            }
            oyente.onAbierto();
            bucleLectura();
        } catch (Exception e) {
            cerrarYAvisar(1006, "no se pudo conectar: " + e.getMessage());
        }
    }

    private boolean handshake() throws IOException {
        byte[] nonce = new byte[16];
        azar.nextBytes(nonce);
        String clave = Base64.encodeToString(nonce, Base64.NO_WRAP);
        String peticion = "GET " + rutaConConsulta + " HTTP/1.1\r\n"
                + "Host: " + host + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + clave + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        salida.write(peticion.getBytes("UTF-8"));
        salida.flush();

        String cabeceras = leerCabecerasHttp();
        if (!cabeceras.startsWith("HTTP/1.1 101") && !cabeceras.startsWith("HTTP/1.0 101")) {
            Bitacora.log("WS: el servidor no aceptó la subida (" + primeraLinea(cabeceras) + ").");
            return false;
        }
        String esperado = aceptacionEsperada(clave);
        return cabeceras.toLowerCase().contains("sec-websocket-accept: " + esperado.toLowerCase());
    }

    private String leerCabecerasHttp() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c, estado = 0; // cuenta \r\n\r\n
        while ((c = entrada.read()) != -1) {
            buf.write(c);
            if (c == '\r') { /* espera */ }
            else if (c == '\n') { estado++; if (estado == 2) break; }
            else estado = 0;
            if (buf.size() > 16384) break; // defensa
        }
        return buf.toString("UTF-8");
    }

    private String aceptacionEsperada(String clave) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] h = sha1.digest((clave + GUID).getBytes("UTF-8"));
            return Base64.encodeToString(h, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    // --- Envío ---------------------------------------------------------------

    void enviarTexto(String mensaje) {
        try {
            enviarTrama(OP_TEXTO, mensaje.getBytes("UTF-8"));
        } catch (IOException e) {
            cerrarYAvisar(1006, "error al enviar texto: " + e.getMessage());
        }
    }

    void enviarBinario(byte[] datos) {
        try {
            enviarTrama(OP_BINARIO, datos);
        } catch (IOException e) {
            cerrarYAvisar(1006, "error al enviar binario: " + e.getMessage());
        }
    }

    private void enviarTrama(int opcode, byte[] carga) throws IOException {
        if (!abierto.get()) return;
        escribirTrama(opcode, carga);
    }

    /** Escribe una trama completa. La arma fuera del candado; dentro solo escribe, y anota desde cuándo. */
    private void escribirTrama(int opcode, byte[] carga) throws IOException {
        byte[] trama = armarTrama(opcode, carga);
        synchronized (candadoEscritura) {
            OutputStream o = salida;
            if (o == null) return;
            escribiendoDesdeMs = System.currentTimeMillis();
            try {
                o.write(trama);
                o.flush();
            } finally {
                escribiendoDesdeMs = 0;
            }
        }
    }

    /** Cuánto lleva trabada la escritura en curso (0 si no hay ninguna). Con la red atorada crece sin parar. */
    long msEscribiendo() {
        long t = escribiendoDesdeMs;
        return t == 0 ? 0 : System.currentTimeMillis() - t;
    }

    private byte[] armarTrama(int opcode, byte[] carga) {
        ByteArrayOutputStream t = new ByteArrayOutputStream(carga.length + 14);
        t.write(0x80 | opcode); // FIN + opcode
        int n = carga.length;
        if (n <= 125) {
            t.write(0x80 | n); // MASK + longitud
        } else if (n <= 0xFFFF) {
            t.write(0x80 | 126);
            t.write((n >>> 8) & 0xFF);
            t.write(n & 0xFF);
        } else {
            t.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) t.write((int) (((long) n >>> (8 * i)) & 0xFF));
        }
        byte[] mascara = new byte[4];
        azar.nextBytes(mascara);
        t.write(mascara, 0, mascara.length);
        for (int i = 0; i < carga.length; i++) t.write(carga[i] ^ mascara[i & 3]);
        return t.toByteArray();
    }

    // --- Lectura -------------------------------------------------------------

    private void bucleLectura() {
        ByteArrayOutputStream mensaje = new ByteArrayOutputStream();
        int opcodeMensaje = -1;
        try {
            while (abierto.get()) {
                int b0 = entrada.read();
                if (b0 == -1) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;

                int b1 = entrada.read();
                if (b1 == -1) break;
                boolean enmascarada = (b1 & 0x80) != 0;
                long largo = b1 & 0x7F;
                if (largo == 126) {
                    largo = (leer(entrada) << 8) | leer(entrada);
                } else if (largo == 127) {
                    largo = 0;
                    for (int i = 0; i < 8; i++) largo = (largo << 8) | leer(entrada);
                }
                byte[] mascara = null;
                if (enmascarada) {
                    mascara = new byte[4];
                    leerN(entrada, mascara, 4);
                }
                if (largo < 0 || largo > (8 << 20)) { // 8 MB de tope
                    cerrarYAvisar(1009, "trama demasiado grande");
                    return;
                }
                byte[] carga = new byte[(int) largo];
                leerN(entrada, carga, (int) largo);
                if (mascara != null) {
                    for (int i = 0; i < carga.length; i++) carga[i] ^= mascara[i & 3];
                }

                switch (opcode) {
                    case OP_PING:
                        enviarTrama(OP_PONG, carga);
                        break;
                    case OP_PONG:
                        break;
                    case OP_CIERRE:
                        int codigo = carga.length >= 2 ? ((carga[0] & 0xFF) << 8) | (carga[1] & 0xFF) : 1000;
                        String motivo = carga.length > 2 ? new String(carga, 2, carga.length - 2, "UTF-8") : "";
                        // RFC 6455 §5.5.1: se contesta el cierre con el mismo código antes de soltar el socket.
                        try {
                            enviarTrama(OP_CIERRE, carga.length >= 2 ? new byte[]{carga[0], carga[1]} : new byte[0]);
                        } catch (IOException ignorado) {
                        }
                        cerrarYAvisar(codigo, motivo.isEmpty() ? "el servidor cerró" : motivo);
                        return;
                    case OP_TEXTO:
                    case OP_BINARIO:
                        mensaje.reset();
                        mensaje.write(carga);
                        opcodeMensaje = opcode;
                        if (fin) entregar(opcodeMensaje, mensaje);
                        break;
                    case OP_CONT:
                        mensaje.write(carga);
                        if (fin) entregar(opcodeMensaje, mensaje);
                        break;
                    default:
                        // opcode reservado: se ignora
                }
            }
            cerrarYAvisar(1006, "la conexión se cerró");
        } catch (Exception e) {
            cerrarYAvisar(1006, e.getMessage());
        }
    }

    private void entregar(int opcode, ByteArrayOutputStream mensaje) throws IOException {
        byte[] datos = mensaje.toByteArray();
        mensaje.reset();
        if (opcode == OP_TEXTO) oyente.onTexto(new String(datos, "UTF-8"));
        else if (opcode == OP_BINARIO) oyente.onBinario(datos);
    }

    boolean estaAbierto() {
        return abierto.get();
    }

    /**
     * Cierre pedido por la app. No espera a la red: la trama 1000 sale en su propio hilo y, a los 300 ms como
     * mucho, se suelta el TCP de abajo. Si otro hilo estaba trabado escribiendo con el candado tomado, sale con
     * excepción; si la trama 1000 no alcanzó a salir, el servidor ve el TCP cerrado, que también corta la sesión.
     */
    void cerrar() {
        cerradoPorApp = true;
        boolean eraAbierto = abierto.getAndSet(false);
        try {
            if (eraAbierto && salida != null) {
                Thread despedida = new Thread(() -> {
                    try {
                        escribirTrama(OP_CIERRE, new byte[]{0x03, (byte) 0xE8}); // 1000 normal
                    } catch (Exception ignorado) {
                        // el socket se suelta igual
                    }
                }, "ws-cierre");
                despedida.setDaemon(true);
                despedida.start();
                try {
                    despedida.join(PLAZO_TRAMA_CIERRE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            cerrarSocket(); // pase lo que pase con la trama 1000, el TCP se suelta
        }
    }

    private void cerrarYAvisar(int codigo, String motivo) {
        if (cerradoPorApp) { // la app ya cerró: el error del lector es el socket que ella soltó
            abierto.set(false);
            cerrarSocket();
            return;
        }
        boolean eraAbierto = abierto.getAndSet(false);
        cerrarSocket();
        if (eraAbierto) {
            oyente.onCerrado(codigo, motivo != null ? motivo : "");
        } else {
            oyente.onError(motivo != null ? motivo : "error de conexión");
        }
    }

    // SIN el candado de escritura (hallazgo 1): primero el TCP de abajo, que despierta a quien esté trabado en
    // él; luego el TLS, que así ya no puede quedarse escribiendo su close_notify. Cerrar dos veces no hace daño.
    // En Android 14 (revisado en la fuente de AOSP): el pre-cierre del TCP (dup2 + AsynchronousCloseMonitor) saca
    // con SocketException al hilo trabado en el poll de Conscrypt; el close() del TLS, además, llama a SSL_interrupt
    // antes de esperar su candado de escritura. Cualquier excepción aquí se traga: lo que importa es soltar los dos.
    private void cerrarSocket() {
        salida = null;
        Socket c = crudo, s = socket;
        try { if (c != null) c.close(); } catch (Exception ignorado) {}
        try { if (s != null && s != c) s.close(); } catch (Exception ignorado) {}
    }

    private static int leer(InputStream in) throws IOException {
        int c = in.read();
        if (c == -1) throw new IOException("fin inesperado");
        return c & 0xFF;
    }

    private static void leerN(InputStream in, byte[] destino, int n) throws IOException {
        int leidos = 0;
        while (leidos < n) {
            int r = in.read(destino, leidos, n - leidos);
            if (r == -1) throw new IOException("fin inesperado");
            leidos += r;
        }
    }

    private static String primeraLinea(String s) {
        int i = s.indexOf('\r');
        if (i < 0) i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }
}
