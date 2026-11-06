// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sesión de voz con Gemini Live hablando DIRECTO desde el cel (sin la Mac), sobre ClienteWebSocket.
 *
 * Arma el mensaje `setup`, manda el micrófono como `realtimeInput.audio` (PCM16 16 kHz base64) y
 * recibe la voz de Isabella en `serverContent.modelTurn.parts[].inlineData` (PCM 24 kHz). Atiende
 * `interrupted`, `turnComplete`, transcripciones, `goAway`, y la herramienta `colgar` (function
 * calling) para que Isabella pueda terminar la llamada.
 *
 * Todo lo que sale hacia Google pasa por una cola y un hilo emisor propio («ws-emisor», 2.4, hallazgo 1 del
 * arquitecto): ni la captura, ni el hilo de acción, ni el lector se traban si la red se atora. En la cola
 * caben hasta 1 s de micrófono; si se llena, se tira la trama MÁS VIEJA (lo reciente es lo que sirve en una
 * conversación), se avisa una vez y se cuenta. El setup, los avisos y el toolResponse nunca se tiran.
 * `msEscribiendo()` dice cuánto lleva trabada la escritura en curso.
 *
 * Mide además los huecos del servidor a media respuesta (HuecoServidor, 2.4) y anota el desglose de tokens por
 * modalidad de cada turno.
 *
 * ⚠️ Los nombres EXACTOS de los campos, el id del modelo, la voz y el prompt de Isabella se
 * reconcilian con `puente/ESPEC_GEMINI_LIVE_PARA_ANDROID.md` (lo escribe `puente`). Lo que aquí
 * se deja son constantes en un solo lugar y la estructura documentada del protocolo BidiGenerateContent.
 */
final class SesionGemini {

    interface Oyente {
        void onLista();                       // setupComplete
        void onVozIsabella(byte[] pcm24k);    // trozo de audio de Isabella
        void onIsabellaHabla(boolean on, String motivo); // true al llegar audio; false en fin/interrumpida
        void onTexto(String quien, String texto);        // transcripción de entrada/salida
        void onColgar(long topeMs);           // colgar tras la despedida (tool colgar o goAway); topeMs = tope del drenaje
        void onAviso(String aviso);           // goAway u otros informativos
        /** Google reportó actividad del interlocutor: «inicio»/«fin» (voiceActivity) o «texto» (transcripción). */
        void onActividadInterlocutor(String tipo);
        void onError(String motivo);
        void onCerrada(String motivo);
    }

    // --- Parámetros reconciliables con la especificación de `puente` ---
    static final String HOST = "generativelanguage.googleapis.com";
    static final int PUERTO = 443;
    static final String API_DEFECTO = "v1beta"; // la guía dice v1beta, el SDK v1alpha: configurable por --es api
    static final String MODELO = "models/gemini-3.8-live";
    static final String VOZ = "Zephyr";
    static final String MIME_ENTRADA = "audio/pcm;rate=16000";
    /** Prompt mínimo de arranque; el canónico de Isabella lo trae la espec de `puente`. */
    static final String INSTRUCCION_SISTEMA =
            "Eres Isabella, una asistente de voz con inteligencia artificial, hablando por teléfono. "
            + "En tu primera frase di que eres una inteligencia artificial y que es una llamada de prueba. "
            + "Habla en español de México, con frases cortas y naturales. Espera a oír a la otra persona "
            + "antes de hablar. Si la persona se despide o pide terminar, usa la herramienta colgar.";

    private final String token;
    private final String api;
    private final boolean constrained; // token efímero (auth_tokens/…) → BidiGenerateContentConstrained
    private final String wsBase;       // modo prueba: base ws://… del mock; vacío = TLS a Google
    private final String modelo;       // «models/…»: el que dijo el Worker, o MODELO
    private final Oyente oyente;
    private volatile ClienteWebSocket ws;
    private volatile boolean lista;
    private volatile boolean cerrando; // la app pidió cerrar: lo que llegue después ya no se avisa
    private volatile boolean isabellaHablando;
    private long acumRespuesta;        // tokens de salida acumulados (costo de la llamada)
    private int ultimoTotal;

    // Emisor: byte[] = trama de micrófono (se arma aquí), String = mensaje listo (setup, avisos, toolResponse).
    static final int MAX_AUDIO_EN_COLA = 50; // 1 s de tramas de 20 ms
    private final LinkedBlockingQueue<Object> colaEnvio = new LinkedBlockingQueue<>();
    private final AtomicInteger audioEnCola = new AtomicInteger();
    private final AtomicInteger tramasTiradas = new AtomicInteger();
    private Thread emisor;
    private final HuecoServidor hueco = new HuecoServidor(); // 2.4: más de 1 s sin mensajes a media respuesta

    SesionGemini(String token, String api, String wsBase, String modelo, Oyente oyente) {
        this.token = token;
        this.api = (api == null || api.isEmpty()) ? API_DEFECTO : api;
        this.constrained = token != null && token.startsWith("auth_tokens/");
        this.wsBase = wsBase == null ? "" : wsBase;
        String m = modelo == null ? "" : modelo.trim();
        this.modelo = m.isEmpty() ? MODELO : (m.startsWith("models/") ? m : "models/" + m);
        this.oyente = oyente;
    }

    void abrir() {
        // Con token efímero (auth_tokens/…) el método es BidiGenerateContentConstrained y el token va en
        // ?access_token=; con llave directa sería BidiGenerateContent (no se usa en el cel).
        String metodo = constrained ? "BidiGenerateContentConstrained" : "BidiGenerateContent";
        String host; int puerto; boolean tls; String ruta;
        if (!wsBase.isEmpty()) {
            java.net.URI u = java.net.URI.create(wsBase);
            tls = "wss".equalsIgnoreCase(u.getScheme());
            host = u.getHost();
            puerto = u.getPort() > 0 ? u.getPort() : (tls ? 443 : 80);
            String base = u.getRawPath() != null ? u.getRawPath() : "";
            // Sin ruta en wsbase se usa la misma de Google (el mock acepta cualquiera que diga BidiGenerateContent).
            if (base.isEmpty() || "/".equals(base)) base = "/ws/google.ai.generativelanguage." + api + ".GenerativeService";
            ruta = base + "." + metodo + "?access_token=" + Uri_encode(token);
            Bitacora.log("MODO PRUEBA: Gemini falso en " + host + ":" + puerto + " (" + (tls ? "wss" : "ws") + ", " + metodo + ").");
        } else {
            host = HOST; puerto = PUERTO; tls = true;
            ruta = "/ws/google.ai.generativelanguage." + api + ".GenerativeService." + metodo
                    + "?access_token=" + Uri_encode(token);
            Bitacora.log("Gemini: conectando (" + api + ", " + metodo + ").");
        }
        ws = new ClienteWebSocket(host, puerto, ruta, tls, new ClienteWebSocket.Oyente() {
            @Override public void onAbierto() { if (!cerrando) enviarSetup(); }
            @Override public void onTexto(String mensaje) { if (!cerrando) procesar(mensaje); }
            @Override public void onBinario(byte[] datos) {
                // Gemini Live manda TODO en marcos BINARIOS cuyo contenido es JSON UTF-8.
                if (!cerrando) procesar(new String(datos, java.nio.charset.StandardCharsets.UTF_8));
            }
            @Override public void onCerrado(int codigo, String motivo) {
                lista = false;
                if (!cerrando) oyente.onCerrada("WS cerrado (" + codigo + "): " + motivo);
            }
            @Override public void onError(String motivo) {
                lista = false;
                if (!cerrando) oyente.onError(motivo);
            }
        });
        emisor = new Thread(this::bucleEmisor, "ws-emisor");
        emisor.setDaemon(true);
        emisor.start();
        ws.conectar();
    }

    /** Saca la cola en orden. Si la red se atora, el único que se traba es este hilo (y lo suelta cerrar()). */
    private void bucleEmisor() {
        try {
            while (!cerrando) {
                Object m = colaEnvio.poll(200, TimeUnit.MILLISECONDS);
                if (m == null) continue;
                ClienteWebSocket w = ws;
                if (w == null || cerrando) break;
                if (m instanceof byte[]) {
                    audioEnCola.decrementAndGet();
                    w.enviarTexto(jsonMicrofono((byte[]) m));
                } else {
                    w.enviarTexto((String) m);
                }
            }
        } catch (InterruptedException e) {
            // cerrar(): se acabó
        }
    }

    private void encolar(String json) {
        if (!cerrando) colaEnvio.offer(json);
    }

    /** Cuánto lleva trabada la escritura en curso hacia Google (0 si no hay). */
    long msEscribiendo() {
        ClienteWebSocket w = ws;
        return w == null ? 0 : w.msEscribiendo();
    }

    /** Tramas de micrófono tiradas porque la cola estaba llena (red lenta o atorada). */
    int tramasTiradas() {
        return tramasTiradas.get();
    }

    boolean estaLista() {
        ClienteWebSocket w = ws;
        return lista && w != null && w.estaAbierto();
    }

    boolean isabellaHablando() {
        return isabellaHablando;
    }

    /**
     * Encola una trama de micrófono (PCM16 16 kHz) y vuelve al instante: nunca espera a la red. Si ya hay 1 s
     * de micrófono en cola, se tira la trama MÁS VIEJA de la cola (nunca un mensaje de control), se avisa una
     * sola vez y se cuenta; la nueva entra. El medio dúplex lo decide quien llama. Devuelve true si la trama quedó
     * en la cola (false si la sesión no está lista).
     */
    boolean enviarMicrofono(byte[] pcm16k, int largo) {
        if (!estaLista()) return false;
        if (audioEnCola.get() >= MAX_AUDIO_EN_COLA && tirarAudioMasViejo()) {
            if (tramasTiradas.incrementAndGet() == 1) {
                Bitacora.log("Micrófono: la red no da abasto; tiro lo más viejo de la cola (aviso único; el total sale al cerrar).");
            }
        }
        audioEnCola.incrementAndGet();
        colaEnvio.offer(Arrays.copyOf(pcm16k, largo)); // copia: quien llama puede reusar su búfer
        return true;
    }

    /** Quita la trama de micrófono más vieja de la cola. False si el emisor se la llevó justo antes (ya hay lugar). */
    private boolean tirarAudioMasViejo() {
        for (Object m : colaEnvio) {
            if (m instanceof byte[]) {
                // remove(Object) compara por identidad en los arreglos: solo cuenta si ESTA llamada la quitó.
                if (colaEnvio.remove(m)) {
                    audioEnCola.decrementAndGet();
                    return true;
                }
                return false;
            }
        }
        return false;
    }

    /** Solo para las pruebas en la Mac: la trama de micrófono más vieja que sigue en la cola (null si no hay). */
    byte[] audioMasViejoEnCola() {
        for (Object m : colaEnvio) if (m instanceof byte[]) return (byte[]) m;
        return null;
    }

    /** El vigilante (cada segundo): anota un hueco del servidor cuya ventana de 300 ms ya cerró. */
    void revisarHueco() {
        String h = hueco.cerrarSiVencio(System.currentTimeMillis());
        if (h != null) Bitacora.log(h);
    }

    private static String jsonMicrofono(byte[] pcm) {
        String b64 = Base64.encodeToString(pcm, Base64.NO_WRAP);
        try {
            JSONObject audio = new JSONObject().put("data", b64).put("mimeType", MIME_ENTRADA);
            JSONObject entrada = new JSONObject().put("audio", audio);
            return new JSONObject().put("realtimeInput", entrada).toString();
        } catch (JSONException e) {
            return ""; // claves fijas: no puede fallar
        }
    }

    /** Cierra sin esperar a la red: ≤ 300 ms para la trama 1000 y luego suelta el TCP (ClienteWebSocket.cerrar). */
    void cerrar() {
        if (cerrando) return;
        cerrando = true;
        lista = false;
        colaEnvio.clear();
        Thread e = emisor;
        if (e != null) e.interrupt();
        ClienteWebSocket w = ws;
        if (w != null) w.cerrar();
        String h = hueco.vaciar();
        if (h != null) Bitacora.log(h);
        int tiradas = tramasTiradas.get();
        Bitacora.log("Tokens de la llamada: salida acumulada " + acumRespuesta + ", último total " + ultimoTotal + "."
                + (tiradas > 0 ? " Micrófono: " + tiradas + " tramas tiradas por red lenta." : ""));
    }

    /** Texto de sistema por realtimeInput (respaldo del saludo, espec §8). No corta el turno. */
    void enviarSistemaRealtime(String texto) {
        ClienteWebSocket w = ws;
        if (w == null || !w.estaAbierto() || cerrando) return;
        try {
            encolar(new JSONObject().put("realtimeInput", new JSONObject().put("text", texto)).toString());
        } catch (JSONException ignorado) {
        }
    }

    /** Texto de sistema a Isabella (clientContent, corta el turno actual). Ver espec §8. */
    void enviarSistema(String texto) {
        enviarTurnoTexto(texto, true);
    }

    /**
     * Contexto para Isabella SIN pedirle respuesta (2.5, aviso de grabación): clientContent con turnComplete:false.
     * Según la referencia de la Live API, lo de clientContent se agrega siempre a la conversación y, con turnComplete
     * en falso, el servidor espera más mensajes antes de generar: Isabella no habla por esto, lo toma en cuenta en su
     * siguiente turno. Falta verlo con Google en el cel. Devuelve false si no se pudo encolar (sesión cerrada).
     */
    boolean enviarContexto(String texto) {
        return enviarTurnoTexto(texto, false);
    }

    private boolean enviarTurnoTexto(String texto, boolean turnComplete) {
        ClienteWebSocket w = ws;
        if (w == null || !w.estaAbierto() || cerrando) return false;
        String json = jsonTurnoTexto(texto, turnComplete);
        if (json.isEmpty()) return false;
        encolar(json);
        return true;
    }

    /** {"clientContent":{"turns":[{"role":"user","parts":[{"text":…}]}],"turnComplete":…}}; vacío si no se pudo armar. */
    static String jsonTurnoTexto(String texto, boolean turnComplete) {
        try {
            JSONObject parte = new JSONObject().put("text", texto);
            JSONObject turno = new JSONObject().put("role", "user").put("parts", new JSONArray().put(parte));
            JSONObject cc = new JSONObject().put("turns", new JSONArray().put(turno)).put("turnComplete", turnComplete);
            return new JSONObject().put("clientContent", cc).toString();
        } catch (JSONException e) {
            return ""; // claves fijas: no puede fallar
        }
    }

    private void registrarUso(JSONObject uso) {
        int entrada = uso.optInt("promptTokenCount", 0);
        int salida = uso.optInt("responseTokenCount", 0);
        int total = uso.optInt("totalTokenCount", 0);
        int razonamiento = uso.optInt("thoughtsTokenCount", 0);
        acumRespuesta += salida;
        if (total > ultimoTotal) ultimoTotal = total;
        // Desglose por modalidad (espec §5: promptTokensDetails / responseTokensDetails), para el costo por turno.
        Bitacora.log("Tokens turno: entrada " + entrada + desglose(uso.optJSONArray("promptTokensDetails"))
                + ", salida " + salida + desglose(uso.optJSONArray("responseTokensDetails"))
                + (razonamiento > 0 ? ", razonamiento " + razonamiento : "") + ", total " + total + ".");
    }

    /** « (texto 4238, audio 248)» a partir de [{"modality":"TEXT","tokenCount":4238},…]; vacío si no vino. */
    static String desglose(JSONArray detalles) {
        if (detalles == null || detalles.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < detalles.length(); i++) {
            JSONObject d = detalles.optJSONObject(i);
            if (d == null) continue;
            String modalidad = d.optString("modality", "?");
            String nombre;
            switch (modalidad) {
                case "TEXT": nombre = "texto"; break;
                case "AUDIO": nombre = "audio"; break;
                case "IMAGE": nombre = "imagen"; break;
                case "VIDEO": nombre = "video"; break;
                default: nombre = modalidad.toLowerCase(java.util.Locale.ROOT);
            }
            if (sb.length() > 0) sb.append(", ");
            sb.append(nombre).append(' ').append(d.optInt("tokenCount", 0));
        }
        return sb.length() == 0 ? "" : " (" + sb + ")";
    }

    /** Aviso de tiempo de `puente/lib/isabella.mjs` (SISTEMA.aviso, español, con la función colgar). */
    static String sistemaAviso(long segundos) {
        return "Sistema: quedan unos " + segundos + " segundos de llamada. Despídete en una o dos frases y llama a la función \"colgar\".";
    }

    /** Segundos a partir de un timeLeft como "10s" o "10.5s"; `porDefecto` si no se puede. */
    private static long parseSegundos(String s, long porDefecto) {
        if (s == null) return porDefecto;
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.') num.append(c); else break;
        }
        try {
            return num.length() > 0 ? (long) Double.parseDouble(num.toString()) : porDefecto;
        } catch (NumberFormatException e) {
            return porDefecto;
        }
    }

    // --- Armado del setup ----------------------------------------------------

    private void enviarSetup() {
        try {
            // Con token efímero (constrained) el setup va MÍNIMO: la voz, el prompt y las herramientas
            // vienen amarradas en el token y Google ignora lo demás. Con llave directa (solo el puente)
            // se manda todo. La app siempre usa token, así que manda el mínimo.
            JSONObject genConfig = new JSONObject().put("responseModalities", new JSONArray().put("AUDIO"));
            JSONObject setup = new JSONObject().put("model", modelo).put("generationConfig", genConfig);
            if (!constrained) {
                genConfig.put("speechConfig", new JSONObject().put("voiceConfig",
                        new JSONObject().put("prebuiltVoiceConfig", new JSONObject().put("voiceName", VOZ))));
                JSONObject sistema = new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", INSTRUCCION_SISTEMA)));
                JSONObject colgar = new JSONObject()
                        .put("name", "colgar")
                        .put("description", "Termina la llamada telefónica cuando la persona se despide o pide colgar.");
                setup.put("systemInstruction", sistema)
                        .put("inputAudioTranscription", new JSONObject())
                        .put("outputAudioTranscription", new JSONObject())
                        .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations",
                                new JSONArray().put(colgar))));
            }
            encolar(new JSONObject().put("setup", setup).toString());
            Bitacora.log("Gemini: setup enviado (" + (constrained ? "mínimo; config del token" : "completo; llave directa") + ").");
        } catch (JSONException e) {
            oyente.onError("no se pudo armar el setup: " + e.getMessage());
        }
    }

    // --- Lectura de los mensajes del servidor --------------------------------

    private void procesar(String json) {
        String h = hueco.alMensaje(System.currentTimeMillis()); // cuenta todo, también los `{}`
        if (h != null) Bitacora.log(h);
        JSONObject m;
        try {
            m = new JSONObject(json);
        } catch (JSONException e) {
            return; // fragmento no-JSON o ruido
        }
        if (m.has("setupComplete")) {
            lista = true;
            oyente.onLista();
            Bitacora.log("Gemini: sesión lista.");
            return;
        }
        if (m.has("sessionResumptionUpdate")) return; // handle de reanudación: se ignora y no se registra
        if (m.has("goAway")) {
            JSONObject ga = m.optJSONObject("goAway");
            String tl = ga != null ? ga.optString("timeLeft", "") : "";
            long seg = parseSegundos(tl, 8);
            // goAway es un AVISO con timeLeft, no un corte. Si todavía queda tiempo
            // de sobra (> 20 s) lo tratamos como informativo: la despedida real la
            // dispara el techo (maxMin), el token, otro goAway por debajo del umbral
            // o la herramienta `colgar`. Umbral 20 s porque despedida típica (TTS +
            // 1-2 frases + drenaje de 12 s) cabe en ~15 s; con 20 s hay 2x margen.
            final long UMBRAL_DESPEDIDA_S = 20L;
            if (seg > UMBRAL_DESPEDIDA_S) {
                String aviso = "Gemini: goAway informativo (timeLeft=" + (tl.isEmpty() ? "?" : tl)
                        + "); sigo normal, la despedida la disparará el techo o un goAway < " + UMBRAL_DESPEDIDA_S + " s.";
                Bitacora.log(aviso);
                oyente.onAviso(aviso);
                return;
            }
            Bitacora.log("Gemini: goAway (timeLeft=" + (tl.isEmpty() ? "?" : tl) + "); despido a Isabella y programo el cierre.");
            enviarSistema(sistemaAviso(Math.max(5, seg - 2)));
            long tope = Math.max(2000L, Math.min(12000L, seg * 1000L - 1500L));
            oyente.onColgar(tope);
            return;
        }
        JSONObject va = m.optJSONObject("voiceActivity");
        if (va != null) {
            // El VAD de Google oyó empezar o terminar a la otra persona (espec §5); solo sirve a los temporizadores.
            String tipo = va.optString("type", "");
            if (tipo.endsWith("START")) oyente.onActividadInterlocutor("inicio");
            else if (tipo.endsWith("END")) oyente.onActividadInterlocutor("fin");
            return;
        }
        if (m.has("toolCall")) {
            atenderHerramienta(m.optJSONObject("toolCall"));
            return;
        }
        JSONObject uso = m.optJSONObject("usageMetadata");
        if (uso != null) registrarUso(uso);
        JSONObject sc = m.optJSONObject("serverContent");
        if (sc != null) procesarServerContent(sc);
    }

    private void procesarServerContent(JSONObject sc) {
        if (sc.optBoolean("interrupted", false)) {
            isabellaHablando = false;
            hueco.terminaRespuesta();
            oyente.onIsabellaHabla(false, "interrumpida");
            return;
        }
        JSONObject modelTurn = sc.optJSONObject("modelTurn");
        if (modelTurn != null) {
            JSONArray parts = modelTurn.optJSONArray("parts");
            if (parts != null) {
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject parte = parts.optJSONObject(i);
                    if (parte == null) continue;
                    JSONObject inline = parte.optJSONObject("inlineData");
                    if (inline != null) {
                        String data = inline.optString("data", "");
                        if (!data.isEmpty()) {
                            if (!isabellaHablando) {
                                isabellaHablando = true;
                                hueco.empiezaRespuesta();
                                oyente.onIsabellaHabla(true, "");
                            }
                            try {
                                oyente.onVozIsabella(Base64.decode(data, Base64.DEFAULT));
                            } catch (IllegalArgumentException ignorado) {
                                // base64 raro: se ignora el trozo
                            }
                        }
                    }
                }
            }
        }
        JSONObject entradaTx = sc.optJSONObject("inputTranscription");
        if (entradaTx != null && !entradaTx.optString("text").isEmpty()) {
            oyente.onActividadInterlocutor("texto");
            oyente.onTexto("interlocutor", entradaTx.optString("text"));
        }
        JSONObject salidaTx = sc.optJSONObject("outputTranscription");
        if (salidaTx != null && !salidaTx.optString("text").isEmpty()) {
            oyente.onTexto("isabella", salidaTx.optString("text"));
        }
        // generationComplete: ya llegó todo el audio del turno (aunque falte por sonar); desde aquí el silencio de
        // Google es normal hasta el turnComplete, que llega cuando ese audio terminaría de sonar (espec §5).
        if (sc.optBoolean("generationComplete", false)) hueco.terminaRespuesta();
        if (sc.optBoolean("turnComplete", false)) {
            isabellaHablando = false;
            hueco.terminaRespuesta();
            oyente.onIsabellaHabla(false, "fin");
        }
    }

    private void atenderHerramienta(JSONObject toolCall) {
        if (toolCall == null) return;
        JSONArray llamadas = toolCall.optJSONArray("functionCalls");
        if (llamadas == null) return;
        for (int i = 0; i < llamadas.length(); i++) {
            JSONObject fc = llamadas.optJSONObject(i);
            if (fc == null) continue;
            String nombre = fc.optString("name");
            if ("colgar".equals(nombre)) {
                responderHerramienta(fc.optString("id"), nombre);
                Bitacora.log("Gemini: Isabella usó la herramienta colgar.");
                oyente.onColgar(12000L);
            }
        }
    }

    private void responderHerramienta(String id, String nombre) {
        try {
            JSONObject respuesta = new JSONObject()
                    .put("id", id)
                    .put("name", nombre)
                    .put("response", new JSONObject().put("resultado", "ok"))
                    .put("scheduling", "SILENT");
            JSONObject tr = new JSONObject().put("functionResponses",
                    new JSONArray().put(respuesta));
            encolar(new JSONObject().put("toolResponse", tr).toString());
        } catch (JSONException ignorado) {
            // claves fijas
        }
    }

    /**
     * Token para la query. La librería de Google lo manda tal cual (espec §3: `auth_tokens/…` con la diagonal
     * sin codificar) y así lo aceptó Google; se deja la diagonal y se codifica cualquier otro carácter raro.
     */
    static String Uri_encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("%2F", "/");
        } catch (Exception e) {
            return s;
        }
    }
}
