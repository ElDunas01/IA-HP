// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.speech.tts.TextToSpeech;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import com.viajatech.lab.isabellallamadas.audio.FuenteAudio;
import com.viajatech.lab.isabellallamadas.audio.FuenteNativa;
import com.viajatech.lab.isabellallamadas.audio.FuenteRaiz;
import com.viajatech.lab.isabellallamadas.audio.RaizUtil;
import com.viajatech.lab.isabellallamadas.audio.SumideroAudio;
import com.viajatech.lab.isabellallamadas.audio.SumideroNativo;
import com.viajatech.lab.isabellallamadas.audio.SumideroRaiz;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Servicio en primer plano (tipo micrófono) de «Isabella Llamadas», arquitectura v2:
 * la app habla DIRECTO con Gemini Live desde el cel (sin la Mac).
 *
 *   abre la sesión de Gemini → marca cuando Gemini confirma el setup (espec §11; si no abre, no marca)
 *   → al contestar pone altavoz y empieza la conversación → reproduce por el altavoz la voz de Isabella
 *   → cuelga. El camino del audio es acústico (altavoz ↔ micrófono del propio cel).
 *
 * Avisos «Sistema:» (textos de `puente/lib/isabella.mjs`, espec §8 y §11): saludo de respaldo si nadie
 * habla en 12 s (20 s con la activa aproximada), aviso de silencio (12 s, máx. 2) y aviso de tiempo
 * antes del techo.
 *
 * Fusibles de gasto (el gasto de Isabella para en ≤ 1 s cuando cuelga cualquiera de los dos):
 *   a) IDLE de la línea → cierra la sesión, para audio y se detiene.
 *   b) techo por llamada (max_min) → se despide, cierra Gemini y cuelga.
 *   c) vigilante: 30 s sin mensaje del servidor o 10 s sin micrófono → cierra.
 *   d) herramienta `colgar` (function calling) → como (a).
 *   h) cualquier error del socket → limpia todo.
 *   l) tope de timbre (max_timbre_s, solo con el InCallService): DIALING sin STATE_ACTIVE → cuelga y cierra
 *      Gemini antes del buzón; el micrófono no se abre antes de STATE_ACTIVE.
 *   m) red atorada (2.4): una escritura hacia Google trabada 5 s → cierra y cuelga. Con la red atorada nada
 *      espera al socket: la sesión sale por una cola con su propio hilo y cerrarla tarda ≤ 300 ms.
 *   n) vida del token (2.4): si el token vence antes del techo, el techo se adelanta para que Isabella alcance a
 *      despedirse antes de que Google corte con 1011.
 *
 * Ruta ESTRICTA (2.4): `llamar` solo marca con el InCallService (appop MANAGE_ONGOING_CALLS), que es el único que
 * sabe cuándo contestan; sin él no marca. Escape solo de laboratorio: `--es ruta_clasica 1` (activa aproximada).
 * El token se pide con el techo de la llamada (`max_min`) y el modo del preámbulo (`modo`). La bitácora trae marcas
 * de latencia por turno (MarcasLatencia), los huecos del servidor (HuecoServidor) y lo que tiran las colas.
 *
 * Grabación manual (2.5): solo con `--es grabar 1` (o el interruptor de la pantalla) en `llamar` o `isabella`. Al
 * abrir la sesión, Isabella recibe el aviso como contexto para decirlo al presentarse; si termina un turno sin
 * decirlo, se le pide una vez. Desde que empieza la conversación se graban el micrófono, su voz y la transcripción
 * en `files/llamadas/<fecha_hora>/` (Grabacion). Sin aviso no hay grabación; nada se borra; el número no se anota.
 *
 * Bocina (2.6): por omisión la app pide la bocina al marcar y al contestar. Con `--es bocina 0` (o el interruptor
 * «Sin bocina» de la pantalla) no pide ninguna ruta: la llamada se queda donde la ponga Telecom (auricular, audífonos
 * con cable o Bluetooth), y la bitácora anota la ruta en efecto y cada cambio de ruta. Vale solo para esa orden.
 *
 * Modos: `llamar` (normal), `solo_marcar` (plan B: marca y pone altavoz, sin captura ni Gemini),
 * `isabella` (prueba sin llamada: abre Gemini con micrófono y altavoz, sin marcar).
 */
public class LlamadaService extends Service {
    private static final String CANAL = "llamada";
    private static final int ID_NOTIFICACION = 7;

    // Claves de los extras de los intents de control.
    static final String EXTRA_CMD = "cmd";       // llamar | solo_marcar | isabella | colgar | tono | tono_sostenido | parar_tono | tts | grabar | config | importar_secreto | estado
    static final String EXTRA_NUMERO = "numero";
    static final String EXTRA_TOKEN = "token";    // token efímero de Gemini (NUNCA se registra)
    static final String EXTRA_WORKER = "worker";  // URL del Worker que acuña el token
    static final String EXTRA_SECRETO = "secreto"; // secreto del dispositivo (se guarda; no se registra)
    static final String EXTRA_MAXMIN = "maxmin";
    static final String EXTRA_MAX_TIMBRE = "max_timbre_s"; // ruta InCallService: cuelga si no contestan en N s desde DIALING (5–60; 20)
    static final String EXTRA_MODO = "modo";      // servicios | isabella | flexible: preámbulo que acuña el Worker (config lo guarda)
    static final String EXTRA_RUTA_CLASICA = "ruta_clasica"; // 1 = escape de laboratorio: marcar sin InCallService (por llamada)
    static final String EXTRA_UMBRAL_VOZ = "umbral_voz"; // RMS de trama para las marcas de latencia (50–20000; 300; por llamada)
    static final String EXTRA_GRABAR = "grabar"; // 1 = grabar ESTA llamada (micrófono, voz de Isabella y transcripción); no se guarda (2.5)
    static final String EXTRA_BOCINA = "bocina"; // 0 = NO pedir la bocina en esta orden: la ruta la deja Telecom (2.6); 1 = como siempre. No se guarda
    static final String EXTRA_FUENTE = "fuente";  // comunicacion | mic | reconocimiento
    static final String EXTRA_USO = "uso";        // voz | media | asistente
    static final String EXTRA_AEC = "aec";        // on | off
    static final String EXTRA_API = "api";        // v1beta | v1alpha (versión de la API de Gemini)
    static final String EXTRA_MODO_PRUEBA = "modo_prueba"; // 1 = contra el mock de puente (ws:// a LAN)
    static final String EXTRA_WSBASE = "wsbase";   // modo prueba: base ws://ip:puerto/...GenerativeService
    static final String EXTRA_TOKENURL = "tokenurl"; // https:// = Worker de producción (como worker); en modo prueba, el local http://127.0.0.1:8797/token
    static final String EXTRA_SECRETO_PRUEBA = "secreto_prueba"; // modo prueba: secreto FALSO, solo en memoria
    static final String EXTRA_RAIZ = "raiz";                      // 2.7: 1 = usar tinymix+tinyplay+tinycap vía su (ruta I-3 real)
    static final String EXTRA_SALUDAR = "saludar";                // 2.7: 1 = Isabella saluda apenas conecte (sin esperar los 12 s de respaldo)
    static final String EXTRA_WAV_CONTESTAR = "wav_contestar";    // 2.7: ruta a WAV PCM 16-bit mono 16 kHz que se reproduce al pasar la línea a ACTIVE (requiere raiz=1)
    static final String EXTRA_FRASE = "frase";                    // 2.7: texto del saludo inmediato (sólo con --es saludar 1); si no viene, se usa SISTEMA_SALUDO canónico
    static final String EXTRA_DEMORA_SALUDO = "demora_saludo_s";  // 2.7: estrategia del saludo inmediato en ruta aprox (-1 = 500 ms inmediato (compat); 0 = auto fin de ringback (default); N>0 = fijo N s). Vale solo esta orden.
    static final String EXTRA_GANANCIA_TTS = "ganancia_tts";      // 2.7: ganancia digital de la voz de Isabella en raíz (décimas: 10=1.0x…50=5.0x; default 30=3.0x ≈ +9.5 dB). Clamp int16. Vale solo esta orden.
    static final String EXTRA_PREENFASIS = "preenfasis";          // v7 FIX A: pre-énfasis IIR 1er orden antes de ganancia (centésimas de α: 0=off default (v6-exacto); 12=α=0.12 opt-in; max 30). Compensa rolloff AMR-WB. Vale solo esta orden.
    static final String EXTRA_SOFTCLIP_KNEE = "softclip_knee";    // v7 FIX D: headroom del tanh (décimas: 10=×1.0 default v6; 11..15 más headroom). Vale solo esta orden.
    static final String EXTRA_DITHER_TTS = "dither_tts";          // v7 FIX B: amplitud del dither en silencio del pacer (int16: 0=off default (v6-exacto); 2=±2 opt-in ≈-84 dBFS; max 8). Vale solo esta orden.
    static final String EXTRA_PREFILL_MS = "prefill_ms";          // v7 FIX C: prefill del pacer en ms (default 500; min 100; max 2000). Vale solo esta orden.
    static final String EXTRA_HPF_HZ = "hpf_hz";                  // v7.4: HPF 1er orden pre-pipe (0=off default v7.3-exacto; 1..200 Hz; típico 80..120). Elimina rumble sub-fc para liberar headroom al soft-clip. Vale solo esta orden.
    static final String EXTRA_MUTE_MIC_FIX = "mute_mic_fix";      // v7.1 FIX A opt-in: 0=OFF default (v6-exacto, uplink OK, eco acústico presente); 1=ON (mutea el mic físico; rompe uplink en SM-A72, solo A/B lab). Vale solo esta orden.

    static final String MODO_LLAMAR = "llamar", MODO_SOLO_MARCAR = "solo_marcar", MODO_ISABELLA = "isabella";

    private static LlamadaService activo;

    // Configuración.
    private String numero = ""; // lo pone el usuario (UI o --es numero); vacío = no marca
    private volatile String token = "";        // de adb; sirve una vez (uses: 1) y se olvida al usarlo
    private String workerUrl = "";
    private int maxMin = 3;                 // techo por defecto; tope 5 (fusible b); el token vive 60 + max_min·60 + 30 s
    private int maxTimbreS = Ajustes.MAXTIMBRE_DEFECTO; // fusible l (solo con el InCallService)
    private int fuenteMic = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    private int usoAudio = AudioAttributes.USAGE_VOICE_COMMUNICATION;
    private String aec = "default";
    private boolean appopIncall;                // ¿concedido MANAGE_ONGOING_CALLS? (InCallService opcional)
    // Mismo valor que AppOpsManager.OPSTR_MANAGE_ONGOING_CALLS, que es @hide en el SDK.
    private static final String OP_MANAGE_ONGOING_CALLS = "android:manage_ongoing_calls";
    private String apiGemini = SesionGemini.API_DEFECTO; // v1beta | v1alpha (--es api)
    private boolean modoPrueba;                 // solo con --es modo_prueba 1 y hosts de LAN privada
    private String wsBase = "";
    private String tokenUrl = "";
    private volatile boolean vioOffhook;        // ¿la línea ya pasó por OFFHOOK? (ignora el IDLE inicial del callback)
    private volatile long tOffhookMs;           // primer OFFHOOK de la saliente (≈ DIALING): respaldo del tope de timbre
    private String secretoPrueba = "";          // modo prueba: el del Worker local (falso); nunca se guarda
    static final String ARCHIVO_SECRETO = "secreto.txt"; // importar_secreto: lo deja la Mac por tubería adb
    private boolean altavozPedido;              // ponerAltavoz pidió el altavoz al sistema: se suelta al terminar
    private String modoPreambulo = Ajustes.MODO_DEFECTO; // --es modo (lo guardado por config, o el de esta orden)
    private boolean rutaClasica;                // --es ruta_clasica 1 (solo esta orden): marcar sin InCallService
    private boolean rutaRaiz;                   // 2.7 --es raiz 1 (solo esta orden): sumidero/fuente por su+tinymix+tinyplay/tinycap
    private boolean saludarAlContestar;         // 2.7 --es saludar 1 (solo esta orden): Isabella saluda apenas conecte
    private String wavContestar;                // 2.7 --es wav_contestar <ruta> (solo esta orden): WAV que se dispara al ACTIVE con raiz=1
    private volatile boolean wavContestarDisparado; // ya se arrancó el hilo del WAV en esta orden (idempotente entre OFFHOOK/aprox/activaReal)
    private String fraseSaludo;                 // 2.7 --es frase <texto> (solo esta orden): texto del saludo inmediato cuando --es saludar 1; vacío/null = SISTEMA_SALUDO
    private int demoraSaludoS;                  // 2.7 --es demora_saludo_s N (solo esta orden): -1 inmediato (compat 500 ms) · 0 auto fin de ringback (default) · N>0 fijo N s
    private int gananciaTtsDecimas = 30;        // 2.7 --es ganancia_tts N (solo esta orden; décimas): 10=1.0x…50=5.0x; default 30=3.0x (+9.5 dB) solo en ruta raíz. La grabación conserva el nivel original.
    private int preenfasisCentesimas = 0;       // v7 FIX A --es preenfasis N (solo esta orden; centésimas de α): 0=off default (v6-exacto); 12=α=0.12 opt-in compensa rolloff AMR-WB. La grabación conserva el nivel original.
    private int softclipKneeDecimas = 10;       // v7 FIX D --es softclip_knee N (solo esta orden; décimas de headroom): 10=×1.0 (default, tanh v6 exacto); 11..15 más headroom. Solo en ruta raíz.
    private int ditherTts = 0;                  // v7 FIX B --es dither_tts N (solo esta orden; int16 amp): 0=off default (cero puro v6-exacto); 2=±2 opt-in (≈-84 dBFS). Pasa a SumideroRaiz.
    private int prefillTtsMs = 500;             // v7 FIX C --es prefill_ms N (solo esta orden; ms): default 500 (v6); min 100; max 2000. Pasa a SumideroRaiz.
    private int hpfHz = 0;                      // v7.4 --es hpf_hz N (solo esta orden; Hz): 0=off default (v7.3-exacto); 80..120 típico; max 200. HPF 1er orden pre-pipe en aplicarGananciaPcm16 (antes del pre-énfasis).
    private int muteMicFix = 0;                 // v7.1 --es mute_mic_fix N (solo esta orden): 0=OFF default v6-exacto; 1=ON mutea mic físico con setMicrophoneMute (rompe UPLINK en SM-A72 Qualcomm: el mute es post-mixer del VoiceStream TX y silencia también Incall_Music_MM2). Preservado solo para A/B futuro cuando sepamos cómo evitar que mute también el UP.
    private volatile int preenfasisEstado;      // v7 FIX A estado del IIR 1er orden entre chunks del MISMO reproductor. Reset en reiniciarEstado().
    private volatile float hpfXPrev;            // v7.4 HPF 1er orden: x[n-1] entre chunks del MISMO reproductor. Reset en reiniciarEstado().
    private volatile float hpfYPrev;            // v7.4 HPF 1er orden: y[n-1] entre chunks del MISMO reproductor. Reset en reiniciarEstado().
    private Thread hiloWavContestar;            // referencia al hilo del WAV, para interrumpirlo en terminar()/onDestroy()
    private volatile SumideroAudio sumideroWavContestar; // sumidero abierto por el hilo del WAV, para cerrarlo si lo matan
    private int umbralVoz = MarcasLatencia.UMBRAL_DEFECTO; // --es umbral_voz (solo esta orden)
    private volatile boolean grabar;            // --es grabar 1 (solo esta orden): grabar la llamada (2.5); la lee el lector del socket
    private boolean bocina = true;              // --es bocina 0 (solo esta orden): no pedir la bocina; la ruta la deja Telecom (2.6)
    private volatile Grabacion grabacion;       // abierta desde que empieza la conversación hasta terminar()
    private volatile boolean grabacionMencionada; // Isabella ya dijo que se graba (su transcripción)
    private volatile boolean recordatorioGrabacion; // ya se le pidió UNA vez que lo diga
    private volatile boolean turnoConVoz;       // el turno de Isabella en curso ya trajo audio
    private final StringBuilder turnoIsabella = new StringBuilder(); // transcripción del turno en curso
    private volatile long venceTokenMs;         // cuándo vence el token del Worker (reloj del cel); 0 = no se sabe
    private volatile MarcasLatencia marcas = new MarcasLatencia(MarcasLatencia.UMBRAL_DEFECTO);
    private volatile int trozosVozTirados;      // la cola de voz se llenó y tiró lo más viejo (lo escribe el lector)

    // Sesión abierta ANTES de marcar (espec §11.1) y conversación desde la activa.
    private volatile boolean enCurso;           // hay un modo en marcha: otro «llamar» se ignora
    private boolean marcarAlEstarLista;         // MODO_LLAMAR: se marca al llegar setupComplete
    private boolean sesionFueLista;             // ¿llegó setupComplete en esta llamada?
    private boolean tokenDelWorker;             // el token salió del Worker (se puede pedir otro UNA vez)
    private boolean reintentoTokenUsado;        // espec §9: otro token, una sola vez, si cerró antes del setup
    private volatile String modeloToken = "";   // el modelo que dijo el Worker
    private int generacionSesion;               // invalida una preparación vieja
    private volatile boolean enConversacion;
    private boolean conversacionAprox;          // la activa fue aproximada (OFFHOOK): puede seguir sonando
    private volatile long tMarcadoMs, tConversacionMs, tPrimerAudioMs, tPrimeraVozMs;
    private volatile long ultimaVozMs, ultimaActividadMs, ultimoTonoMs;
    private volatile boolean interlocutorHablando, enTonoAhora;
    private boolean saludoPedido;
    private int empujones;                      // avisos de silencio mandados (máx. 2)
    private ScheduledFuture<?> aperturaWatchdog, avisoTiempo, saludoReintento;

    // Textos «Sistema:» de puente/lib/isabella.mjs (SISTEMA, español). El prompt del token los explica.
    static final String SISTEMA_SALUDO = "Sistema: la llamada ya conectó, pero la otra persona no ha dicho nada o no se le oyó. Saluda ahora con tu primera frase de la llamada.";
    static final String SISTEMA_SILENCIO = "Sistema: la otra persona lleva un rato sin hablar o no se le oye. Pregúntale en una frase corta si te escucha bien.";
    static String sistemaAviso(long segundos) {
        return SesionGemini.sistemaAviso(segundos);
    }

    private String modo = MODO_LLAMAR;
    private volatile String estadoLlamada = "idle"; // idle | dialing | active | ended
    private boolean terminada;

    private AudioManager am;
    private TelecomManager telecom;
    private TelephonyManager telefonia;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;

    private volatile SesionGemini sesion;

    private volatile boolean capturando;
    private Thread hiloCaptura;
    private AcousticEchoCanceler cancelaEco;

    private volatile boolean reproduciendo;
    private Thread hiloReproduccion;
    private final LinkedBlockingQueue<byte[]> colaVoz = new LinkedBlockingQueue<>();
    private SumideroAudio reproductor; // referencia para vaciarlo si Isabella es interrumpida
    private static final int MAX_CHUNKS_VOZ = 60;

    // Medio dúplex: no mandar micrófono mientras Isabella habla y 500 ms después.
    private volatile boolean isabellaHablando;
    private volatile long isabellaHablaHastaMs;

    // Vigilante de inactividad (fusible c) y de red atorada (fusible m).
    static final long RED_ATORADA_MS = 5000;
    private volatile long ultimoDelServidor;
    private volatile long ultimoMicrofono;
    private final ScheduledExecutorService temporizadores = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> vigilante;
    private ScheduledFuture<?> techoLlamada;
    private ScheduledFuture<?> marcadoWatchdog; // corta si nunca llega OFFHOOK tras marcar
    private final TopeTimbre topeTimbre = new TopeTimbre(temporizadores); // fusible l
    private ScheduledFuture<?> drenajeColgar;   // cuelga cuando la despedida termina de sonar
    private volatile boolean colgarPendiente;
    private volatile long ultimoAudioMs;

    // Diagnostico: tono sostenido (mide la ruta MEDIA antes y durante la llamada).
    private volatile boolean tonoSostenido;
    private Thread hiloTono;

    private TextToSpeech tts;

    private final ExecutorService accion = Executors.newSingleThreadExecutor();
    private final Executor hiloTelefonia = Executors.newSingleThreadExecutor();
    private TelephonyCallback callback;
    private boolean enPrimerPlano;

    // --- Ciclo de vida -------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        activo = this;
        am = getSystemService(AudioManager.class);
        telecom = getSystemService(TelecomManager.class);
        telefonia = getSystemService(TelephonyManager.class);
        PowerManager pm = getSystemService(PowerManager.class);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "isabella:llamada");
        WifiManager wm = getSystemService(WifiManager.class);
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "isabella:llamada");

        NotificationChannel canal = new NotificationChannel(CANAL, "Llamada de Isabella", NotificationManager.IMPORTANCE_LOW);
        canal.setDescription("Aviso mientras Isabella tiene una sesión de voz o una llamada en curso");
        getSystemService(NotificationManager.class).createNotificationChannel(canal);

        // Lo que guardó `config` (el servicio muere al colgar; sin esto, `llamar` olvidaría el Worker y el techo).
        workerUrl = Ajustes.worker(this);
        maxMin = Ajustes.maxMin(this);
        maxTimbreS = Ajustes.maxTimbre(this);
        modoPreambulo = Ajustes.modo(this);
        getExternalFilesDir(null); // crea …/files/ para que la Mac pueda dejar secreto.txt
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!enPrimerPlano) {
            startForeground(ID_NOTIFICACION, notificacion("Isabella Llamadas", "Listo"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            enPrimerPlano = true;
        }
        String cmd = intent != null ? intent.getStringExtra(EXTRA_CMD) : null;
        if (cmd == null) cmd = "estado";
        final Intent in = intent;
        final String comando = cmd;
        enAccion(() -> ejecutar(comando, in));
        return START_NOT_STICKY;
    }

    private void ejecutar(String cmd, Intent intent) {
        switch (cmd) {
            case "llamar":
            case "solo_marcar":
            case "isabella":
                if (enCurso) { // antes de aplicar nada: una orden repetida no debe tocar los ajustes de la que va
                    Bitacora.log("Ya hay una llamada o sesión en curso (" + modo + "); ignoro «" + cmd + "» y sus ajustes. Cuelga primero.");
                    break;
                }
                prepararOrden();
                aplicarConfig(intent);
                // Versión pública: no hay número por omisión. Sin número no se marca ni se pide token (cero gasto).
                if (!"isabella".equals(cmd) && (numero == null || numero.replaceAll("[^0-9+]", "").isEmpty())) {
                    Bitacora.log("No hay número: escríbelo en la app o manda --es numero <TU_NUMERO>. "
                            + "Usa solo números tuyos o de personas que aceptaron recibir la llamada. No marqué.");
                    break;
                }
                iniciar("llamar".equals(cmd) ? MODO_LLAMAR : "solo_marcar".equals(cmd) ? MODO_SOLO_MARCAR : MODO_ISABELLA);
                break;
            case "colgar":
                terminar("orden de colgar");
                break;
            case "tono":
                if (intent != null && intent.hasExtra(EXTRA_USO)) usoAudio = usoDe(intent.getStringExtra(EXTRA_USO));
                diagTono();
                break;
            case "tono_sostenido":
                if (intent != null && intent.hasExtra(EXTRA_USO)) usoAudio = usoDe(intent.getStringExtra(EXTRA_USO));
                diagTonoSostenido();
                break;
            case "parar_tono":
                pararTonoSostenido();
                break;
            case "tts":
                if (intent != null && intent.hasExtra(EXTRA_USO)) usoAudio = usoDe(intent.getStringExtra(EXTRA_USO));
                diagTts();
                break;
            case "grabar":
                if (intent != null && intent.hasExtra(EXTRA_FUENTE)) fuenteMic = fuenteDe(intent.getStringExtra(EXTRA_FUENTE));
                diagGrabar();
                break;
            case "importar_secreto":
                importarSecreto();
                break;
            case "config":
                aplicarConfig(intent, true);
                Bitacora.log("Guardado para las siguientes llamadas: worker=" + (Ajustes.worker(this).isEmpty() ? "ninguno" : Ajustes.worker(this))
                        + " · techo " + Ajustes.maxMin(this) + " min · timbre " + Ajustes.maxTimbre(this) + " s"
                        + " · modo " + Ajustes.modo(this)
                        + " · secreto " + (Tokens.secreto(this).isEmpty() ? "no" : "sí") + ".");
                Bitacora.log("Config: numero=" + numero + " maxmin=" + maxMin + " max_timbre_s=" + maxTimbreS
                        + " fuente=" + nombreFuente(fuenteMic) + " uso=" + nombreUso(usoAudio)
                        + " aec=" + aec + " token=" + (token.isEmpty() ? "no" : "sí") + " worker=" + (workerUrl.isEmpty() ? "no" : "sí")
                        + " modo_prueba=" + (modoPrueba ? "sí" : "no"));
                break;
            default:
                Bitacora.log("Estado: modo=" + modo + " llamada=" + estadoLlamada
                        + " · sesión " + (sesion != null && sesion.estaLista() ? "lista" : "no")
                        + " · captura " + (capturando ? "sí" : "no") + " · reproducción " + (reproduciendo ? "sí" : "no"));
        }
    }

    private void aplicarConfig(Intent intent) {
        aplicarConfig(intent, false);
    }

    /**
     * Lo que vale solo para UNA orden vuelve a su valor antes de aplicar los extras de la nueva: lo guardado por
     * `config` (lo mismo que lee onCreate) y lo de laboratorio que cuesta o desvía (ruta clásica, modo prueba, grabar, bocina 0). Si el
     * servicio sigue vivo entre dos órdenes, la segunda no hereda el techo que bajó el token ni el Worker de la primera.
     * Las perillas de audio (fuente, uso, aec) sí se quedan: fisica.sh y matriz.sh las fijan con `config` sin guardarlas.
     */
    private void prepararOrden() {
        rutaClasica = false;
        rutaRaiz = false;
        saludarAlContestar = false;
        wavContestar = null;
        fraseSaludo = null;
        demoraSaludoS = 0;
        gananciaTtsDecimas = 30;
        preenfasisCentesimas = 0;
        softclipKneeDecimas = 10;
        ditherTts = 0;
        prefillTtsMs = 500;
        hpfHz = 0;
        muteMicFix = 0;
        grabar = false;
        bocina = true;
        umbralVoz = MarcasLatencia.UMBRAL_DEFECTO;
        workerUrl = Ajustes.worker(this);
        maxMin = Ajustes.maxMin(this);
        maxTimbreS = Ajustes.maxTimbre(this);
        modoPreambulo = Ajustes.modo(this);
        modoPrueba = false;
        wsBase = "";
        tokenUrl = "";
        secretoPrueba = "";
    }

    /** `persistir` (solo `config`): la URL del Worker, el techo, el tope de timbre y el modo quedan guardados para las siguientes llamadas. */
    private void aplicarConfig(Intent intent, boolean persistir) {
        if (intent == null) return;
        if (intent.hasExtra(EXTRA_MODO_PRUEBA)) modoPrueba = "1".equals(valor(intent.getStringExtra(EXTRA_MODO_PRUEBA)));
        if (intent.hasExtra(EXTRA_NUMERO)) numero = intent.getStringExtra(EXTRA_NUMERO);
        if (intent.hasExtra(EXTRA_TOKEN)) token = valor(intent.getStringExtra(EXTRA_TOKEN)); // no se registra
        if (intent.hasExtra(EXTRA_WORKER)) fijarWorker(valor(intent.getStringExtra(EXTRA_WORKER)), persistir);
        if (intent.hasExtra(EXTRA_SECRETO)) {
            Tokens.guardarSecreto(this, valor(intent.getStringExtra(EXTRA_SECRETO)));
            Bitacora.log("Secreto del dispositivo guardado (no se muestra). Ojo: llegó por la línea de comandos; lo correcto es importar_secreto.");
        }
        if (intent.hasExtra(EXTRA_MAXMIN)) {
            maxMin = Ajustes.limitar(parseInt(intent.getStringExtra(EXTRA_MAXMIN), maxMin));
            if (persistir && !Ajustes.guardarMaxMin(this, maxMin)) Bitacora.log("No pude guardar el techo; vale solo para esta vez.");
        }
        if (intent.hasExtra(EXTRA_MAX_TIMBRE)) {
            maxTimbreS = Ajustes.limitarTimbre(parseInt(intent.getStringExtra(EXTRA_MAX_TIMBRE), maxTimbreS));
            if (persistir && !Ajustes.guardarMaxTimbre(this, maxTimbreS)) Bitacora.log("No pude guardar el tope de timbre; vale solo para esta vez.");
        }
        if (intent.hasExtra(EXTRA_MODO)) {
            String m = Ajustes.normalizarModo(intent.getStringExtra(EXTRA_MODO));
            if (m == null) {
                Bitacora.log("Modo «" + valor(intent.getStringExtra(EXTRA_MODO)) + "» no existe (servicios, isabella o flexible); sigo con «" + modoPreambulo + "».");
            } else {
                modoPreambulo = m;
                if (persistir && !Ajustes.guardarModo(this, m)) Bitacora.log("No pude guardar el modo; vale solo para esta vez.");
            }
        }
        if (intent.hasExtra(EXTRA_RUTA_CLASICA)) rutaClasica = "1".equals(valor(intent.getStringExtra(EXTRA_RUTA_CLASICA)));
        if (intent.hasExtra(EXTRA_RAIZ)) {
            boolean pedido = "1".equals(valor(intent.getStringExtra(EXTRA_RAIZ)));
            if (pedido) {
                if (RaizUtil.sondearRaiz()) {
                    rutaRaiz = true;
                    Bitacora.log("Modo RAÍZ activo: la captura y la reproducción usan su+tinymix+tinyplay/tinycap (ruta I-3 real).");
                } else {
                    rutaRaiz = false;
                    Bitacora.log("Modo raíz pedido PERO el sondeo falló (su / binarios / mixers I-3). Sigo con la ruta nativa.");
                }
            } else {
                rutaRaiz = false;
            }
        }
        if (intent.hasExtra(EXTRA_SALUDAR)) saludarAlContestar = "1".equals(valor(intent.getStringExtra(EXTRA_SALUDAR)));
        if (intent.hasExtra(EXTRA_WAV_CONTESTAR)) {
            if (persistir) Bitacora.log("«wav_contestar» no se guarda: se pide en cada llamada (--es wav_contestar /ruta).");
            else wavContestar = valor(intent.getStringExtra(EXTRA_WAV_CONTESTAR));
        }
        if (intent.hasExtra(EXTRA_FRASE)) {
            if (persistir) Bitacora.log("«frase» no se guarda: se pide en cada llamada (--es frase \"…\").");
            else fraseSaludo = valor(intent.getStringExtra(EXTRA_FRASE));
        }
        if (intent.hasExtra(EXTRA_DEMORA_SALUDO)) {
            if (persistir) Bitacora.log("«demora_saludo_s» no se guarda: se pide en cada llamada (--es demora_saludo_s N).");
            else {
                int v = parseInt(intent.getStringExtra(EXTRA_DEMORA_SALUDO), demoraSaludoS);
                if (v < -1) v = -1;
                if (v > 120) v = 120;
                demoraSaludoS = v;
            }
        }
        if (intent.hasExtra(EXTRA_GANANCIA_TTS)) {
            if (persistir) Bitacora.log("«ganancia_tts» no se guarda: se pide en cada llamada (--es ganancia_tts N; décimas).");
            else {
                int v = parseInt(intent.getStringExtra(EXTRA_GANANCIA_TTS), gananciaTtsDecimas);
                if (v < 10) v = 10;
                if (v > 50) v = 50;
                gananciaTtsDecimas = v;
            }
        }
        if (intent.hasExtra(EXTRA_PREENFASIS)) {
            if (persistir) Bitacora.log("«preenfasis» no se guarda: se pide en cada llamada (--es preenfasis N; centésimas de α).");
            else {
                int v = parseInt(intent.getStringExtra(EXTRA_PREENFASIS), preenfasisCentesimas);
                if (v < 0) v = 0;
                if (v > 30) v = 30;
                preenfasisCentesimas = v;
            }
        }
        if (intent.hasExtra(EXTRA_SOFTCLIP_KNEE)) {
            if (persistir) Bitacora.log("«softclip_knee» no se guarda: se pide en cada llamada (--es softclip_knee N; décimas).");
            else {
                int v = parseInt(intent.getStringExtra(EXTRA_SOFTCLIP_KNEE), softclipKneeDecimas);
                if (v < 10) v = 10;
                if (v > 20) v = 20;
                softclipKneeDecimas = v;
            }
        }
        if (intent.hasExtra(EXTRA_DITHER_TTS)) {
            if (persistir) Bitacora.log("«dither_tts» no se guarda: se pide en cada llamada (--es dither_tts N; int16 amp).");
            else {
                int v = parseInt(intent.getStringExtra(EXTRA_DITHER_TTS), ditherTts);
                if (v < 0) v = 0;
                if (v > 8) v = 8;
                ditherTts = v;
            }
        }
        if (intent.hasExtra(EXTRA_PREFILL_MS)) {
            if (persistir) Bitacora.log("«prefill_ms» no se guarda: se pide en cada llamada (--es prefill_ms N; ms).");
            else {
                int v = parseInt(intent.getStringExtra(EXTRA_PREFILL_MS), prefillTtsMs);
                if (v < 100) v = 100;
                if (v > 2000) v = 2000;
                prefillTtsMs = v;
            }
        }
        if (intent.hasExtra(EXTRA_HPF_HZ)) {
            if (persistir) Bitacora.log("«hpf_hz» no se guarda: se pide en cada llamada (--es hpf_hz N; Hz).");
            else {
                int v = parseInt(intent.getStringExtra(EXTRA_HPF_HZ), hpfHz);
                if (v < 0) v = 0;
                if (v > 200) v = 200;
                hpfHz = v;
            }
        }
        if (intent.hasExtra(EXTRA_MUTE_MIC_FIX)) {
            if (persistir) Bitacora.log("«mute_mic_fix» no se guarda: se pide en cada llamada (--es mute_mic_fix N; 0=OFF default; 1=ON).");
            else {
                int v = parseInt(intent.getStringExtra(EXTRA_MUTE_MIC_FIX), muteMicFix);
                if (v < 0) v = 0;
                if (v > 1) v = 1;
                muteMicFix = v;
                if (v == 1) Bitacora.log("v7.1 FIX A opt-in: --es mute_mic_fix 1 → silenciarMicFisicoRaiz activo. ATENCIÓN: en SM-A72 Qualcomm esto rompe el UPLINK VoLTE (el mute es post-mixer y silencia también Incall_Music_MM2). Solo para A/B de laboratorio.");
            }
        }
        if (intent.hasExtra(EXTRA_UMBRAL_VOZ)) {
            umbralVoz = MarcasLatencia.limitar(parseInt(intent.getStringExtra(EXTRA_UMBRAL_VOZ), umbralVoz));
        }
        if (intent.hasExtra(EXTRA_GRABAR)) {
            if (persistir) Bitacora.log("«grabar» no se guarda: se pide en cada llamada (--es grabar 1).");
            else grabar = "1".equals(valor(intent.getStringExtra(EXTRA_GRABAR)));
        }
        if (intent.hasExtra(EXTRA_BOCINA)) {
            if (persistir) Bitacora.log("«bocina» no se guarda: se pide en cada llamada (--es bocina 0).");
            else bocina = !"0".equals(valor(intent.getStringExtra(EXTRA_BOCINA)));
        }
        if (intent.hasExtra(EXTRA_FUENTE)) fuenteMic = fuenteDe(intent.getStringExtra(EXTRA_FUENTE));
        if (intent.hasExtra(EXTRA_USO)) usoAudio = usoDe(intent.getStringExtra(EXTRA_USO));
        if (intent.hasExtra(EXTRA_AEC)) aec = intent.getStringExtra(EXTRA_AEC);
        if (intent.hasExtra(EXTRA_API)) apiGemini = valor(intent.getStringExtra(EXTRA_API));
        if (intent.hasExtra(EXTRA_WSBASE)) wsBase = valor(intent.getStringExtra(EXTRA_WSBASE));
        if (intent.hasExtra(EXTRA_TOKENURL)) {
            String u = valor(intent.getStringExtra(EXTRA_TOKENURL));
            // https:// fuera del modo prueba es el Worker de producción (mismo efecto que --es worker).
            if (!modoPrueba && u.startsWith("https://")) fijarWorker(u, persistir);
            else tokenUrl = u;
        }
        if (intent.hasExtra(EXTRA_SECRETO_PRUEBA)) secretoPrueba = valor(intent.getStringExtra(EXTRA_SECRETO_PRUEBA)); // no se registra
        if (modoPrueba && !sonLanPrivadas()) {
            Bitacora.log("MODO PRUEBA rechazado: wsbase/tokenurl deben apuntar a una IP privada de LAN. Sigo con TLS a Google.");
            modoPrueba = false;
            wsBase = "";
            tokenUrl = "";
        }
    }

    /** URL del Worker de producción (https con host). Vacía = ninguna. */
    private void fijarWorker(String url, boolean persistir) {
        if (!url.isEmpty() && !Ajustes.esWorkerValido(url)) {
            Bitacora.log("URL del Worker rechazada: debe ser https:// con host. Sigue la anterior.");
            return;
        }
        workerUrl = url;
        if (persistir && !Ajustes.guardarWorker(this, url)) Bitacora.log("No pude guardar la URL del Worker; vale solo para esta vez.");
    }

    /**
     * Secreto del aparato desde un archivo que la Mac deja por tubería adb (nunca por la línea de comandos):
     * lee …/files/secreto.txt, guarda el contenido recortado, sobrescribe el archivo con ceros del mismo
     * tamaño y lo deja vacío. No lo borra (regla de la casa). En la bitácora solo queda el tamaño.
     */
    private void importarSecreto() {
        File dir = getExternalFilesDir(null);
        File f = dir != null ? new File(dir, ARCHIVO_SECRETO) : null;
        if (f == null || !f.isFile()) {
            Bitacora.log("No hay " + ARCHIVO_SECRETO + " en la carpeta de la app; escríbelo desde la Mac por tubería adb.");
            return;
        }
        long largo = f.length();
        if (largo == 0) {
            Bitacora.log(ARCHIVO_SECRETO + " está vacío: no hay nada que importar.");
            return;
        }
        if (largo > ArchivoSecreto.MAXIMO_BYTES) {
            Bitacora.log(ARCHIVO_SECRETO + " mide " + largo + " bytes y un secreto no pasa de " + ArchivoSecreto.MAXIMO_BYTES + ": no lo toco.");
            return;
        }
        String secreto = ArchivoSecreto.leer(f);
        boolean valido = ArchivoSecreto.valido(secreto);
        boolean guardado = valido && Tokens.guardarSecretoYa(this, secreto);
        boolean limpio = ArchivoSecreto.sobrescribirConCeros(f, largo) & ArchivoSecreto.vaciar(f);
        if (secreto == null) {
            Bitacora.log("No pude leer " + ARCHIVO_SECRETO + ".");
        } else if (!valido) {
            Bitacora.log("El contenido de " + ARCHIVO_SECRETO + " no parece un secreto (corto, con espacios o caracteres raros): no lo guardé.");
        } else if (!guardado) {
            Bitacora.log("No pude guardar el secreto; repite la importación.");
        } else {
            Bitacora.log("secreto importado (" + secreto.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + " bytes)");
        }
        if (!limpio) Bitacora.log("No pude limpiar " + ARCHIVO_SECRETO + "; límpialo desde la Mac (README, «Secreto del aparato»).");
    }

    @Override
    public void onDestroy() {
        activo = null;
        cancelarTemporizadores();
        if (sesion != null) { sesion.cerrar(); sesion = null; }
        detenerCaptura();
        detenerReproduccion();
        detenerWavContestar();
        cerrarGrabacion();
        soltarAltavoz();
        // Defensa dura: si el service muere sin pasar por terminar() (OOM-killer,
        // usuario tira la notificación de foreground), el mic podría quedar muteado a
        // nivel AudioManager y afectar a otras apps hasta reiniciar. Sin guarda rutaRaiz
        // por si el flag se perdió entre starts; el método es idempotente (checa isMicrophoneMute()).
        desmutearMicFisicoRaiz();
        pararTonoSostenido();
        if (tts != null) { tts.shutdown(); tts = null; }
        soltarCandados();
        quitarCallback();
        if (rutaRaiz) { try { RaizUtil.cerrarAmbosMixers(); } catch (Exception ignorado) {} }
        temporizadores.shutdownNow();
        accion.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- Arranque de cada modo ----------------------------------------------

    private void iniciar(String nuevoModo) {
        if (enCurso) {
            Bitacora.log("Ya hay una llamada o sesión en curso (" + modo + "); ignoro «" + nuevoModo + "». Cuelga primero.");
            return;
        }
        enCurso = true;
        modo = nuevoModo;
        terminada = false;
        reiniciarEstado();
        adquirirCandados();
        if (grabar && MODO_SOLO_MARCAR.equals(modo)) {
            grabar = false;
            Bitacora.log("Solo marcar no graba (no hay sesión de Isabella ni captura).");
        } else if (grabar) {
            Bitacora.log("Grabación pedida: al abrir la sesión le aviso a Isabella para que lo diga al presentarse; se graba desde que empieza la conversación.");
        }
        if (!bocina) {
            Bitacora.log("Bocina 0: esta orden no pide la bocina ni al marcar ni al contestar; la ruta de la llamada la deja Telecom (auricular, audífonos o Bluetooth).");
        }

        if (MODO_ISABELLA.equals(modo)) {
            Bitacora.log("Isabella sin llamada: abriendo la sesión de voz para probar antes de marcar.");
            ponerAltavoz();
            prepararSesion();
            return;
        }

        if (!tienePermiso(Manifest.permission.CALL_PHONE)) {
            Bitacora.log("Falta CALL_PHONE. Dalo desde la pantalla o con ./permisos.sh.");
            terminar("sin permiso CALL_PHONE", true);
            return;
        }
        if (MODO_SOLO_MARCAR.equals(modo)) {
            marcarAhora();
            return;
        }
        // MODO_LLAMAR, ruta ESTRICTA (2.4): sin InCallService no se sabe cuándo contestan y el timbre iría a Gemini.
        // Se decide ANTES de pedir el token: así una orden sin el appop no gasta nada.
        appopIncall = incallConcedido();
        String ruta = Ruta.decidir(appopIncall, rutaClasica);
        if (Ruta.NO_MARCAR.equals(ruta)) {
            Bitacora.log("Sin InCallService no marco: concede MANAGE_ONGOING_CALLS (adb shell appops set " + getPackageName()
                    + " MANAGE_ONGOING_CALLS allow). Escape solo de laboratorio: --es ruta_clasica 1.");
            terminar("sin InCallService: no marqué", true);
            return;
        }
        if (Ruta.CLASICA.equals(ruta)) {
            Bitacora.log("RUTA CLÁSICA (APROXIMADA) POR --es ruta_clasica 1: SIN InCallService LA CONVERSACIÓN EMPIEZA 1 S DESPUÉS DE OFFHOOK, "
                    + "EL TIMBRE VA A GEMINI Y NO HAY TOPE DE TIMBRE. SOLO LABORATORIO.");
        } else if (rutaClasica) {
            Bitacora.log("Pediste ruta_clasica, pero el appop está concedido: uso la ruta estricta (InCallService).");
        }
        // Primero la sesión (espec §11.1), para que esté lista cuando contesten.
        Bitacora.log("Abro la sesión de Isabella ANTES de marcar (modo " + modoPreambulo + "): marco cuando Gemini confirme el setup; si no abre, no marco.");
        actualizarNotificacion("Preparando", "Abriendo la sesión de Isabella");
        marcarAlEstarLista = true;
        prepararSesion();
    }

    private void reiniciarEstado() {
        marcarAlEstarLista = false;
        sesionFueLista = false;
        tokenDelWorker = false;
        reintentoTokenUsado = false;
        modeloToken = "";
        enConversacion = false;
        conversacionAprox = false;
        tMarcadoMs = tConversacionMs = tPrimerAudioMs = tPrimeraVozMs = 0;
        ultimaVozMs = ultimaActividadMs = ultimoTonoMs = 0;
        interlocutorHablando = false;
        enTonoAhora = false;
        saludoPedido = false;
        empujones = 0;
        colgarPendiente = false;
        isabellaHablando = false;
        isabellaHablaHastaMs = 0;
        tOffhookMs = 0;
        topeTimbre.reiniciar();
        venceTokenMs = 0;
        trozosVozTirados = 0;
        wavContestarDisparado = false;
        preenfasisEstado = 0;
        hpfXPrev = 0f;
        hpfYPrev = 0f;
        marcas = new MarcasLatencia(umbralVoz);
        grabacionMencionada = false;
        recordatorioGrabacion = false;
        turnoConVoz = false;
        synchronized (turnoIsabella) {
            turnoIsabella.setLength(0);
        }
    }

    // La marcación en sí: en MODO_LLAMAR, después del setupComplete; en MODO_SOLO_MARCAR, de inmediato.
    private void marcarAhora() {
        vioOffhook = false;
        appopIncall = incallConcedido();
        if (MODO_LLAMAR.equals(modo) && Ruta.NO_MARCAR.equals(Ruta.decidir(appopIncall, rutaClasica))) {
            Bitacora.log("El appop MANAGE_ONGOING_CALLS dejó de estar concedido: no marco (ruta estricta).");
            terminar("sin InCallService: no marqué", true);
            return;
        }
        registrarCallback();
        Bitacora.log(appopIncall
                ? "Appop MANAGE_ONGOING_CALLS concedido: si el InCallService liga, la conversación empieza en STATE_ACTIVE real (al contestar)."
                : "Sin appop MANAGE_ONGOING_CALLS: ruta clásica (OFFHOOK + altavoz al marcar); la activa es aprox.");
        if (MODO_LLAMAR.equals(modo)) {
            Bitacora.log(appopIncall
                    ? "Tope de timbre: " + maxTimbreS + " s desde DIALING; si no contestan antes, cuelgo y cierro Gemini (no le hablo al buzón)."
                    : "Sin tope de timbre: en la ruta clásica OFFHOOK no distingue el timbre de una llamada contestada (solo queda el de 90 s).");
        }
        estadoLlamada = "dialing";
        tMarcadoMs = System.currentTimeMillis();
        armarMarcadoWatchdog();
        if (MODO_LLAMAR.equals(modo)) armarVigilante();
        actualizarNotificacion("Marcando", numero);
        Bitacora.log("Marcando a " + numero + " · modo " + modo
                + (MODO_LLAMAR.equals(modo) ? " · preámbulo " + modoPreambulo + " · ruta " + (appopIncall ? "estricta" : "CLÁSICA (APROX)") : "")
                + " · fuente " + nombreFuente(fuenteMic) + " · uso " + nombreUso(usoAudio) + " · techo " + maxMin + " min"
                + " · bocina " + (bocina ? "sí" : "no (ruta de Telecom)"));
        marcar();
    }

    private void marcar() {
        Uri tel = Uri.fromParts("tel", numero, null);
        Bundle extras = new Bundle();
        // 2.6: con bocina 0 no se pide ruta. 2.7 raíz: tampoco se pide, porque Telecom aplica el extra
        // en el HAL al momento de ACTIVE y rompe el path MM2→Incall_Music (la voz al modem va por tinyplay).
        if (bocina && !rutaRaiz) extras.putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true);
        try {
            PhoneAccountHandle cuenta = telecom.getDefaultOutgoingPhoneAccount("tel");
            if (cuenta != null) extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, cuenta);
        } catch (SecurityException e) {
            Bitacora.log("No se pudo leer la cuenta de voz por defecto: " + e.getMessage());
        }
        try {
            telecom.placeCall(tel, extras);
            Bitacora.log(bocina ? "placeCall enviado (altavoz solicitado al marcar)." : "placeCall enviado (bocina 0: sin pedir ruta; la deja Telecom).");
        } catch (SecurityException e) {
            Bitacora.log("placeCall rechazado (" + e.getMessage() + "). Probando ACTION_CALL…");
            try {
                Intent llamada = new Intent(Intent.ACTION_CALL, tel);
                if (bocina && !rutaRaiz) llamada.putExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true);
                llamada.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(llamada);
                Bitacora.log("ACTION_CALL enviado.");
            } catch (Exception e2) {
                Bitacora.log("No se pudo marcar: " + e2.getMessage());
                terminar("sin marcar");
            }
        }
    }

    /** OFFHOOK: la línea quedó en curso (en salientes llega al EMPEZAR a marcar, no al contestar). */
    private void marcarActiva() {
        if ("active".equals(estadoLlamada) || "ended".equals(estadoLlamada)) return;
        if (MODO_SOLO_MARCAR.equals(modo)) {
            estadoLlamada = "active";
            actualizarNotificacion("En llamada", numero);
            Bitacora.log("Línea activa (OFFHOOK). Modo Solo marcar: no se capta ni se reproduce; el audio lo pone quien esté junto al cel.");
            if (rutaRaiz) {
                Bitacora.log("Ruta RAÍZ: skip ponerAltavoz (setCommunicationDevice resetea el path del HAL y rompe el mixer Incall_Music).");
            } else {
                ponerAltavoz();
            }
            dispararWavContestar("OFFHOOK (solo_marcar)");
            return;
        }
        // MODO_LLAMAR. OFFHOOK llega durante el timbre (en salientes, al empezar a marcar). La sesión ya está
        // abierta desde antes de marcar; aquí solo se decide cuándo empieza la conversación: con el
        // InCallService, en STATE_ACTIVE real; sin él, aproximada en 1 s y con el tono de llamada filtrado.
        if (appopIncall) {
            Bitacora.log("OFFHOOK (marcando). Appop concedido: 1.5 s para que el sistema ligue el InCallService.");
            temporizadores.schedule(() -> enAccion(this::decidirTrasOffhook), 1500, TimeUnit.MILLISECONDS);
        } else {
            Bitacora.log("OFFHOOK (marcando). RUTA CLÁSICA (APROX): la conversación empieza en 1 s (con el tono filtrado).");
            temporizadores.schedule(() -> enAccion(this::aperturaClasicaAprox), 1000, TimeUnit.MILLISECONDS);
        }
    }

    // Tras el margen: si el InCallService quedó ligado, esperamos su STATE_ACTIVE. Si no: con la ruta estricta se
    // espera hasta 5 s desde OFFHOOK (en el A72 nunca se midió cuánto tarda en ligarse) y luego se cuelga; con el
    // escape de laboratorio, ruta clásica.
    static final long ESPERA_LIGADO_MS = 5000;

    private void decidirTrasOffhook() {
        if (terminada || "active".equals(estadoLlamada) || enConversacion) return;
        if (ServicioEnLlamada.enLlamada()) {
            Bitacora.log("InCallService ligado: la conversación empieza en STATE_ACTIVE real (al contestar).");
            armarTopeTimbre("OFFHOOK", tOffhookMs != 0 ? tOffhookMs : System.currentTimeMillis()); // si DIALING no lo armó ya
        } else if (rutaClasica) {
            Bitacora.log("EL InCallService NO SE LIGÓ PESE AL APPOP: RUTA CLÁSICA (APROXIMADA) POR --es ruta_clasica 1 (activa aprox, sin tope de timbre).");
            aperturaClasicaAprox();
        } else {
            long desde = tOffhookMs != 0 ? tOffhookMs : System.currentTimeMillis();
            long falta = ESPERA_LIGADO_MS - (System.currentTimeMillis() - desde);
            if (falta > 0) {
                Bitacora.log("El InCallService todavía no se liga; espero hasta 5 s desde OFFHOOK (ruta estricta).");
                temporizadores.schedule(() -> enAccion(this::ultimaOportunidadLigado), falta, TimeUnit.MILLISECONDS);
            } else {
                ultimaOportunidadLigado();
            }
        }
    }

    private void ultimaOportunidadLigado() {
        if (terminada || "active".equals(estadoLlamada) || enConversacion) return;
        if (ServicioEnLlamada.enLlamada()) {
            Bitacora.log("InCallService ligado (tarde): la conversación empieza en STATE_ACTIVE real (al contestar).");
            armarTopeTimbre("OFFHOOK", tOffhookMs != 0 ? tOffhookMs : System.currentTimeMillis());
            return;
        }
        Bitacora.log("El InCallService no se ligó en 5 s pese al appop: cuelgo y cierro Gemini (ruta estricta: sin él no sé cuándo contestan).");
        terminar("InCallService sin ligar (ruta estricta)");
    }

    // Ruta clásica (sin InCallService): la línea se da por activa de forma aproximada. Solo con el escape de laboratorio.
    private void aperturaClasicaAprox() {
        if (terminada || "active".equals(estadoLlamada) || enConversacion) return;
        if (!rutaClasica) { // candado: la ruta estricta nunca llega aquí
            Bitacora.log("Ruta clásica sin --es ruta_clasica 1: no empiezo la conversación; cuelgo (ruta estricta).");
            terminar("ruta clásica no autorizada");
            return;
        }
        estadoLlamada = "active";
        actualizarNotificacion("En llamada", numero);
        Bitacora.log("Línea activa (OFFHOOK). Aprox (sin InCallService); empieza la conversación con el tono filtrado.");
        if (rutaRaiz) {
            Bitacora.log("Ruta RAÍZ: skip ponerAltavoz (setCommunicationDevice resetea el path del HAL y rompe el mixer Incall_Music).");
        } else {
            ponerAltavoz();
        }
        dispararWavContestar("activa aproximada (ruta clásica)");
        empezarConversacion(true, "activa aproximada (puede seguir sonando)");
    }

    private boolean incallConcedido() {
        try {
            AppOpsManager aom = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            return aom.unsafeCheckOpNoThrow(OP_MANAGE_ONGOING_CALLS, Process.myUid(), getPackageName())
                    == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            Bitacora.log("No se pudo consultar el appop MANAGE_ONGOING_CALLS: " + e.getMessage());
            return false;
        }
    }

    // Candado del modo prueba: wsbase/tokenurl deben apuntar a una IP privada de LAN, nunca a internet.
    private boolean sonLanPrivadas() {
        return hostPrivado(wsBase) && (tokenUrl.isEmpty() || hostPrivado(tokenUrl));
    }

    private static boolean hostPrivado(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            String h = java.net.URI.create(url).getHost();
            if (h == null) return false;
            if (h.equals("localhost") || h.startsWith("127.")) return true;
            if (h.startsWith("10.") || h.startsWith("192.168.")) return true;
            if (h.startsWith("172.")) {
                int seg = Integer.parseInt(h.split("\\.")[1]);
                return seg >= 16 && seg <= 31;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    // Corta si la llamada nunca llega a OFFHOOK tras marcar (p. ej. no se pudo establecer).
    private void armarMarcadoWatchdog() {
        cancelar(marcadoWatchdog);
        marcadoWatchdog = temporizadores.schedule(() -> enAccion(() -> {
            if (!terminada && !vioOffhook) {
                Bitacora.log("No llegó OFFHOOK en 20 s: la llamada no se estableció. Cierro sin colgar.");
                terminar("no se estableció la llamada (sin OFFHOOK)", true);
            }
        }), 20, TimeUnit.SECONDS);
    }

    // Colgar tras la despedida: la herramienta se contesta al toque, pero se cuelga cuando la cola de
    // voz se vació y ya no llega más audio (≈1.2 s), con tope de 12 s. Así no se corta la despedida.
    private void programarColgarTrasDespedida(long topeMs) {
        if (colgarPendiente) return;
        colgarPendiente = true;
        ultimoAudioMs = System.currentTimeMillis();
        final long tope = System.currentTimeMillis() + topeMs;
        cancelar(drenajeColgar);
        drenajeColgar = temporizadores.scheduleWithFixedDelay(() -> {
            long ahora = System.currentTimeMillis();
            boolean drenado = colaVoz.isEmpty() && !isabellaHablando && (ahora - ultimoAudioMs) > 1200;
            if (drenado || ahora >= tope) {
                cancelar(drenajeColgar);
                drenajeColgar = null;
                Bitacora.log(drenado ? "La despedida terminó de sonar; cuelgo." : "Tope de despedida alcanzado; cuelgo.");
                enAccion(() -> terminar("Isabella colgó"));
            }
        }, 300, 300, TimeUnit.MILLISECONDS);
    }

    // En llamada, la política de captura de Android solo exceptúa la fuente VOICE_RECOGNITION cuando el
    // servicio de accesibilidad de ESTA app está habilitado y enlazado; con VOICE_COMMUNICATION la
    // captura llega en ceros. Fuera de ese caso se respeta el selector (para medir las tres fuentes).
    private int fuenteParaLlamada() {
        if (accesibilidadActiva()) {
            if (fuenteMic != MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                Bitacora.log("Accesibilidad activa: la llamada se capta con fuente reconocimiento (VOICE_RECOGNITION).");
            }
            return MediaRecorder.AudioSource.VOICE_RECOGNITION;
        }
        return fuenteMic;
    }

    private boolean accesibilidadActiva() {
        try {
            AccessibilityManager am2 = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
            if (am2 == null || !am2.isEnabled()) return false;
            for (AccessibilityServiceInfo si : am2.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                String id = si != null ? si.getId() : null;
                if (id != null && id.contains(getPackageName()) && id.contains("ServicioAccesibilidad")) return true;
            }
        } catch (Exception e) {
            Bitacora.log("No se pudo comprobar la accesibilidad: " + e.getMessage());
        }
        return false;
    }

    // Puentes estáticos que llama el InCallService (ServicioEnLlamada); el trabajo corre en el hilo de acción.
    // Con el appop, el sistema liga el InCallService para CUALQUIER llamada del cel: los puentes solo
    // actúan si la app tiene una llamada o sesión en curso (si no, una llamada a mano de David se colgaría).
    static void alActivaReal() {
        LlamadaService s = activo;
        if (s != null && s.enCurso) s.enAccion(s::activaReal);
    }

    // DIALING del InCallService: marcando o timbrando (en Telecom el timbre de la red es parte de DIALING).
    static void alMarcandoReal() {
        final LlamadaService s = activo;
        if (s == null || !s.enCurso) return;
        final long ahora = System.currentTimeMillis();
        s.enAccion(() -> s.armarTopeTimbre("DIALING", ahora));
    }

    static void alColgadaReal(String motivo) {
        LlamadaService s = activo;
        if (s != null && s.enCurso) s.enAccion(() -> s.terminar(motivo));
    }

    // Fusible l (solo con el InCallService): arma UNA vez por llamada el tope de timbre desde `desdeMs`.
    private void armarTopeTimbre(String desde, long desdeMs) {
        if (!MODO_LLAMAR.equals(modo) || terminada || !enCurso) return;
        final int seg = maxTimbreS;
        long restaMs = topeTimbre.armar(seg * 1000L, desdeMs, System.currentTimeMillis(), () -> enAccion(() -> vencioTimbre(seg)));
        if (restaMs >= 0) {
            Bitacora.log("Tope de timbre armado desde " + desde + ": " + seg + " s (faltan " + Math.round(restaMs / 1000.0) + " s).");
        }
    }

    private void vencioTimbre(int seg) {
        if (terminada || !enCurso || topeTimbre.contestada()) return;
        Bitacora.log("Timbre de " + seg + " s sin STATE_ACTIVE: no contestaron. Cuelgo antes del buzón y cierro Gemini"
                + (enConversacion ? " (iba la conversación aproximada)." : "; el micrófono no llegó a abrirse."));
        terminar("timbre de " + seg + " s sin contestar");
    }

    // STATE_ACTIVE real (tras contestar): aquí empieza la conversación. Idempotente.
    private void activaReal() {
        if (!MODO_LLAMAR.equals(modo) || terminada || !enCurso) return;
        topeTimbre.contestaron(); // fusible l: ya contestaron (o el buzón; ver README)
        String trasMarcar = tMarcadoMs != 0
                ? String.format(Locale.US, " a los %.1f s de marcar", (System.currentTimeMillis() - tMarcadoMs) / 1000.0) : "";
        if (enConversacion) {
            if (conversacionAprox) {
                // Primero llegó la aproximada (OFFHOOK) y ahora la de verdad: el reloj del saludo vuelve a empezar.
                conversacionAprox = false;
                tConversacionMs = System.currentTimeMillis();
                Bitacora.log("Línea ACTIVA (STATE_ACTIVE real del InCallService" + trasMarcar + ") después de la aproximada: ahora sí contestaron.");
            }
            return;
        }
        estadoLlamada = "active";
        actualizarNotificacion("En llamada", numero);
        Bitacora.log("Línea ACTIVA (STATE_ACTIVE real del InCallService" + trasMarcar + "). Empieza la conversación.");
        if (rutaRaiz) {
            Bitacora.log("Ruta RAÍZ: skip ponerAltavoz (setCommunicationDevice resetea el path del HAL y rompe el mixer Incall_Music).");
        } else {
            ponerAltavoz();
        }
        dispararWavContestar("STATE_ACTIVE real");
        empezarConversacion(false, "contestaron");
    }

    // --- WAV al contestar (2.7, --es wav_contestar) --------------------------

    /**
     * 2.7: si la orden pidió `--es wav_contestar <ruta>` y `--es raiz 1` está activo, dispara UNA sola vez por orden
     * un hilo que lee el WAV (PCM 16-bit mono 16 kHz) y lo manda a la ruta Incall_Music vía SumideroRaiz. El hilo
     * se guarda en `hiloWavContestar` para que terminar()/onDestroy() lo interrumpan y suelten el sumidero.
     */
    private void dispararWavContestar(String desde) {
        if (wavContestarDisparado || terminada) return;
        final String ruta = wavContestar;
        if (ruta == null || ruta.isEmpty()) return;
        if (!rutaRaiz) {
            wavContestarDisparado = true;
            Bitacora.log("wav_contestar: pedido pero --es raiz no está activo; no se dispara el WAV (la ruta I-3 es necesaria).");
            return;
        }
        // L3 (bugs laterales 2.8): si vamos a arrancar también la conversación bidireccional en el
        // mismo flow (modo llamar), DOS SumideroRaiz competirían por hw:0,1 y el segundo tinyplay
        // fallaría con "cannot open device 1". Mientras el WAV no se multiplexe por el mismo
        // SumideroRaiz que la conversación, saltamos el WAV con log claro.
        if (MODO_LLAMAR.equals(modo)) {
            wavContestarDisparado = true;
            Bitacora.log("wav_contestar: pedido con --es llamar en ruta raíz; NO lo disparo (dos tinyplay chocarían por hw:0,1). Usa --es solo_marcar si quieres el WAV.");
            return;
        }
        wavContestarDisparado = true;
        final int uso = usoAudio;
        hiloWavContestar = new Thread(() -> ejecutarWavContestar(ruta, uso, desde), "wav-contestar");
        hiloWavContestar.setDaemon(true);
        hiloWavContestar.start();
    }

    private void ejecutarWavContestar(String ruta, int uso, String desde) {
        File f = new File(ruta);
        if (!f.isFile()) {
            Bitacora.log("wav_contestar: no existe el archivo «" + ruta + "».");
            return;
        }
        byte[] pcm;
        try {
            pcm = leerWavMono16k(f);
        } catch (IOException e) {
            Bitacora.log("wav_contestar: no pude leer «" + ruta + "» (" + e.getMessage() + ").");
            return;
        } catch (IllegalArgumentException e) {
            Bitacora.log("wav_contestar: formato no admitido en «" + ruta + "» (" + e.getMessage() + "). Se espera PCM 16-bit mono a 16 kHz.");
            return;
        }
        if (pcm.length == 0) {
            Bitacora.log("wav_contestar: «" + ruta + "» no tiene datos PCM.");
            return;
        }
        SumideroAudio track = construirReproductor(uso, RATE_MIC);
        if (track == null || track.getState() != SumideroAudio.ESTADO_LISTO) {
            Bitacora.log("wav_contestar: no se pudo abrir el reproductor para el WAV (ruta raíz).");
            if (track != null) track.release();
            return;
        }
        sumideroWavContestar = track;
        Bitacora.log("wav_contestar: disparo (" + desde + ") → " + ruta + " · " + pcm.length + " B PCM a 16 kHz mono.");
        int escrito = 0;
        try {
            track.play();
            while (escrito < pcm.length && !Thread.currentThread().isInterrupted()) {
                int n = track.write(pcm, escrito, pcm.length - escrito);
                if (n <= 0) {
                    Bitacora.log("wav_contestar: la escritura devolvió " + n + "; corto en " + escrito + "/" + pcm.length + " B.");
                    break;
                }
                escrito += n;
            }
            if (Thread.currentThread().isInterrupted()) {
                Bitacora.log("wav_contestar: interrumpido tras " + escrito + "/" + pcm.length + " B.");
            } else {
                // write() mete chunks al cola en memoria; el pacer drena al pipe a 32 B/ms (16 kHz mono 16-bit).
                // Hay que esperar que la cola + el pipe del tinyplay se vacíen ANTES de stop(), si no el stop
                // cierra el pipe y tinyplay va a Draining antes de que el A55 oiga todo el WAV.
                long msEspera = (long)(pcm.length / 32) + 800;
                Bitacora.log("wav_contestar: WAV cargado al pacer (" + escrito + "/" + pcm.length + " B); dreno " + msEspera + " ms…");
                Thread.sleep(msEspera);
                Bitacora.log("wav_contestar: drenaje terminado.");
            }
        } catch (Exception e) {
            Bitacora.log("wav_contestar: error durante la reproducción (" + e.getMessage() + ").");
        } finally {
            try { track.stop(); } catch (Exception ignorado) {}
            try { track.release(); } catch (Exception ignorado) {}
            sumideroWavContestar = null;
        }
    }

    /** Interrumpe el hilo del WAV y suelta el sumidero. Idempotente; vale llamarla aunque nunca se haya disparado. */
    private void detenerWavContestar() {
        Thread h = hiloWavContestar;
        hiloWavContestar = null;
        SumideroAudio t = sumideroWavContestar;
        sumideroWavContestar = null;
        if (h != null) {
            h.interrupt();
            try { h.join(500); } catch (InterruptedException ignorado) { Thread.currentThread().interrupt(); }
        }
        if (t != null) {
            try { t.stop(); } catch (Exception ignorado) {}
            try { t.release(); } catch (Exception ignorado) {}
        }
    }

    /** Lee un WAV RIFF/PCM mono 16-bit a 16 kHz y devuelve los bytes de datos PCM (little-endian). Rechaza otros formatos. */
    private static byte[] leerWavMono16k(File f) throws IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
            long largoArchivo = raf.length();
            if (largoArchivo < 44) throw new IOException("archivo muy chico (" + largoArchivo + " B)");
            byte[] cab = new byte[12];
            raf.readFully(cab);
            if (cab[0] != 'R' || cab[1] != 'I' || cab[2] != 'F' || cab[3] != 'F'
                    || cab[8] != 'W' || cab[9] != 'A' || cab[10] != 'V' || cab[11] != 'E') {
                throw new IllegalArgumentException("no es RIFF/WAVE");
            }
            int formato = -1, canales = -1, rate = -1, bits = -1;
            long posData = -1;
            long tamData = -1;
            while (raf.getFilePointer() + 8 <= largoArchivo) {
                byte[] id = new byte[4];
                raf.readFully(id);
                long tam = leerLE32(raf) & 0xFFFFFFFFL;
                if (id[0] == 'f' && id[1] == 'm' && id[2] == 't' && id[3] == ' ') {
                    if (tam < 16) throw new IllegalArgumentException("fmt corto (" + tam + ")");
                    formato = leerLE16(raf);
                    canales = leerLE16(raf);
                    rate = leerLE32(raf);
                    leerLE32(raf); // byteRate
                    leerLE16(raf); // blockAlign
                    bits = leerLE16(raf);
                    long saltar = tam - 16;
                    if (saltar > 0) raf.skipBytes((int) Math.min(saltar, Integer.MAX_VALUE));
                } else if (id[0] == 'd' && id[1] == 'a' && id[2] == 't' && id[3] == 'a') {
                    posData = raf.getFilePointer();
                    tamData = tam;
                    break;
                } else {
                    raf.skipBytes((int) Math.min(tam, Integer.MAX_VALUE));
                    if ((tam & 1L) == 1L && raf.getFilePointer() < largoArchivo) raf.skipBytes(1);
                }
            }
            if (formato != 1) throw new IllegalArgumentException("no es PCM lineal (formato=" + formato + ")");
            if (canales != 1) throw new IllegalArgumentException("no es mono (" + canales + " canales)");
            if (bits != 16) throw new IllegalArgumentException("no es 16-bit (" + bits + ")");
            if (rate != 16000) throw new IllegalArgumentException("no es 16 kHz (rate=" + rate + ")");
            if (posData < 0 || tamData < 0) throw new IllegalArgumentException("sin bloque data");
            long disponible = largoArchivo - posData;
            int leer = (int) Math.min(tamData, disponible);
            if (leer <= 0) return new byte[0];
            byte[] pcm = new byte[leer];
            raf.seek(posData);
            raf.readFully(pcm);
            return pcm;
        }
    }

    private static int leerLE16(java.io.RandomAccessFile r) throws IOException {
        int b0 = r.readUnsignedByte();
        int b1 = r.readUnsignedByte();
        return b0 | (b1 << 8);
    }

    private static int leerLE32(java.io.RandomAccessFile r) throws IOException {
        int b0 = r.readUnsignedByte();
        int b1 = r.readUnsignedByte();
        int b2 = r.readUnsignedByte();
        int b3 = r.readUnsignedByte();
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    // --- Sesión de Gemini ----------------------------------------------------

    /** Token + apertura en su propio hilo (el Worker puede tardar o pedir esperar); lo demás sigue en `accion`. */
    private void prepararSesion() {
        final int generacion = ++generacionSesion;
        new Thread(() -> {
            final String t = obtenerToken();
            enAccion(() -> {
                if (terminada || generacion != generacionSesion) return;
                if (t == null || t.isEmpty()) {
                    Bitacora.log("Sin token no se abre la sesión de Isabella. Pásalo con --es token … o configura el Worker.");
                    terminar(MODO_LLAMAR.equals(modo) ? "sin token: no marqué" : "sin token", true);
                    return;
                }
                abrirSesionCon(t);
            });
        }, "prepara-sesion").start();
    }

    private void abrirSesionCon(String t) {
        ultimoDelServidor = System.currentTimeMillis();
        ultimoMicrofono = System.currentTimeMillis();
        final SesionGemini[] ref = new SesionGemini[1];
        ref[0] = new SesionGemini(t, apiGemini, (modoPrueba ? wsBase : ""), modeloToken, new OyenteSesion(ref));
        sesion = ref[0];
        sesion.abrir();
        armarAperturaWatchdog();
    }

    // Si no llega setupComplete en 10 s, se trata como cierre antes del setup (espec §9).
    private void armarAperturaWatchdog() {
        cancelar(aperturaWatchdog);
        aperturaWatchdog = temporizadores.schedule(() -> enAccion(() -> {
            if (!terminada && !sesionFueLista && sesion != null) falloSesion(false, "Gemini no confirmó el setup en 10 s");
        }), 10, TimeUnit.SECONDS);
    }

    /**
     * Token para la sesión: el de adb (sirve una vez y se olvida) o el del Worker con la tabla de §2.1.
     * Corre en el hilo «prepara-sesion». Null = no hay token.
     */
    private String obtenerToken() {
        tokenDelWorker = false;
        modeloToken = "";
        String deAdb = token;
        if (!deAdb.isEmpty()) {
            token = ""; // uses: 1 → no se reutiliza en otra llamada
            return deAdb;
        }
        String url = (modoPrueba && !tokenUrl.isEmpty()) ? tokenUrl : workerUrl;
        if (url.isEmpty()) return null;
        if (!modoPrueba && !url.startsWith("https://")) {
            Bitacora.log("El Worker debe ser https:// (solo el modo prueba usa http a la LAN); no pido token.");
            return null;
        }
        String secreto = (modoPrueba && !secretoPrueba.isEmpty()) ? secretoPrueba : Tokens.secreto(this);
        if (secreto.isEmpty()) {
            Bitacora.log("No hay secreto del dispositivo guardado; no se puede pedir token al Worker.");
            return null;
        }
        if (modoPrueba) Bitacora.log("MODO PRUEBA: pido token al Worker local en " + Tokens.urlDeToken(url) + ".");
        Tokens.Resultado r = Tokens.pedirAlWorker(url, secreto, "es", maxMin, modoPreambulo);
        if (!r.ok()) return null;
        tokenDelWorker = true;
        modeloToken = r.modelo;
        venceTokenMs = r.venceMs;
        if (r.maxMin > 0 && r.maxMin < maxMin) {
            Bitacora.log("El token salió para " + r.maxMin + " min y el techo era " + maxMin + ": la llamada se corta a los " + r.maxMin + " min.");
            maxMin = r.maxMin;
        }
        return r.token;
    }

    /** setupComplete: en MODO_LLAMAR ahora sí se marca; en MODO_ISABELLA empieza la conversación. */
    private void sesionLista() {
        sesionFueLista = true;
        cancelar(aperturaWatchdog);
        aperturaWatchdog = null;
        ultimoDelServidor = System.currentTimeMillis();
        avisarGrabacionAIsabella(); // 2.5: antes de marcar o de empezar la conversación
        // El reproductor NO se abre aquí: en MODO_LLAMAR todavía no hay llamada (se abre al empezar la conversación,
        // como en las versiones medidas en el A72).
        if (MODO_ISABELLA.equals(modo)) {
            Bitacora.log("Isabella conectada. Escuchando.");
            armarVigilante();
            empezarConversacion(false, "Isabella sin llamada");
            return;
        }
        if (marcarAlEstarLista) {
            marcarAlEstarLista = false;
            Bitacora.log("Isabella conectada (setupComplete). Ahora sí marco.");
            marcarAhora();
        }
    }

    /**
     * 2.5: con `grabar`, Isabella recibe el aviso como contexto (clientContent sin turnComplete) para decirlo al
     * presentarse. Si no se puede grabar o el aviso no sale, esta llamada no se graba: nunca hay grabación sin aviso.
     */
    private void avisarGrabacionAIsabella() {
        if (!grabar) return;
        String motivo = Grabacion.revisar(getExternalFilesDir(null));
        SesionGemini s = sesion;
        if (motivo == null && (s == null || !s.enviarContexto(Grabacion.TEXTO_AVISO))) motivo = "no pude mandarle el aviso a Isabella";
        if (motivo != null) {
            grabar = false;
            Bitacora.log("Grabación: " + motivo + "; esta llamada sigue SIN grabar.");
            return;
        }
        Bitacora.log("Grabación: le avisé a Isabella (contexto, sin pedirle que hable) que esta llamada se graba; lo dirá al presentarse.");
    }

    /** 2.5: abre la grabación de ESTA llamada (solo si la orden la pidió y ya se le avisó a Isabella). */
    private void abrirGrabacion(String motivo) {
        if (!grabar || grabacion != null) return;
        try {
            Grabacion g = Grabacion.abrir(getExternalFilesDir(null), encabezadoGrabacion(motivo), System.currentTimeMillis());
            grabacion = g;
            Bitacora.log("Grabación abierta en " + g.carpeta.getPath() + " (micrófono, voz de Isabella y transcripción).");
        } catch (IOException | RuntimeException e) {
            grabar = false;
            Bitacora.log("Grabación: no pude abrirla (" + e.getMessage() + "); la llamada sigue SIN grabar y le pido a Isabella que no lo mencione.");
            SesionGemini s = sesion;
            if (s != null) s.enviarContexto(Grabacion.TEXTO_SIN_GRABAR);
        }
    }

    /** Encabezado de la transcripción: qué orden y qué app grabaron. El número marcado NO va. */
    private String encabezadoGrabacion(String motivo) {
        String ruta = MODO_ISABELLA.equals(modo) ? "sin llamada"
                : conversacionAprox ? "ruta clásica (aproximada)" : "ruta estricta (InCallService)";
        return "Orden " + modo + " · modo de Isabella " + modoPreambulo + " · techo " + maxMin + " min · " + ruta
                + " · empieza: " + motivo + " · app " + versionApp() + ". El número marcado no se anota.\n"
                + "Aviso que recibió Isabella al abrir la sesión: «" + Grabacion.TEXTO_AVISO + "»";
    }

    private String versionApp() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0));
            return pi.versionName + "-" + pi.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "?";
        }
    }

    /** 2.5, respaldo del aviso: Isabella terminó un turno sin decir que se graba; se le pide UNA vez. */
    private void recordarAvisoDeGrabacion() {
        SesionGemini s = sesion;
        if (terminada || colgarPendiente || s == null || !s.estaLista() || grabacion == null || grabacionMencionada) return;
        Bitacora.log("Grabación: Isabella terminó su turno sin decir que se graba; le pido que lo diga ahora (una sola vez).");
        s.enviarSistema(Grabacion.SISTEMA_RECORDATORIO);
        anotarSistema(Grabacion.SISTEMA_RECORDATORIO);
    }

    /** 2.5: los avisos «Sistema:» que la app le manda a Isabella también van a la transcripción. */
    private void anotarSistema(String texto) {
        Grabacion g = grabacion;
        if (g != null) g.sistema(texto);
    }

    /** 2.5: cierra la grabación sin esperar al disco (el escritor termina lo pendiente y lo anota). */
    private void cerrarGrabacion() {
        Grabacion g = grabacion;
        grabacion = null;
        if (g != null) g.cerrar();
    }

    /** Cierre o error de la sesión que la app no pidió. Antes del setup: otro token UNA vez (§9) o no se marca. */
    private void falloSesion(boolean error, String motivo) {
        if (terminada) return;
        Bitacora.log(error ? "Gemini: error — " + motivo : "Gemini: sesión cerrada — " + motivo);
        if (!sesionFueLista) {
            cancelar(aperturaWatchdog);
            aperturaWatchdog = null;
            SesionGemini s = sesion;
            sesion = null;
            if (s != null) s.cerrar();
            if (tokenDelWorker && !reintentoTokenUsado) {
                reintentoTokenUsado = true;
                Bitacora.log("Gemini cerró antes del setupComplete: pido otro token al Worker (una sola vez, espec §9).");
                prepararSesion();
                return;
            }
            if (MODO_LLAMAR.equals(modo)) {
                Bitacora.log("No marqué: la sesión de Isabella no abrió.");
                terminar("sin sesión: no marqué", true);
            } else {
                terminar(error ? "error de Gemini" : "sesión de Gemini cerrada");
            }
            return;
        }
        if (!error && motivo != null && motivo.contains("(1011)")) {
            Bitacora.log("Google cerró con 1011 (token vencido): es el fin de la llamada; cuelgo sin reconectar.");
            terminar("token de Gemini vencido (1011)");
            return;
        }
        terminar(error ? "error de Gemini" : "sesión de Gemini cerrada"); // espec §9: fatal, cuelga sin reintentar
    }

    /** Desde aquí el micrófono va a Gemini (sin el tono de llamada) y corren el techo y los avisos. */
    private void empezarConversacion(boolean aprox, String motivo) {
        if (terminada || enConversacion) return;
        SesionGemini s = sesion;
        if (s == null || !s.estaLista()) {
            Bitacora.log("La sesión de Isabella no está lista al empezar la conversación; termino.");
            terminar("sin sesión al contestar");
            return;
        }
        long ahora = System.currentTimeMillis();
        enConversacion = true;
        conversacionAprox = aprox;
        tConversacionMs = ahora;
        ultimoDelServidor = ahora;
        ultimoMicrofono = ahora;
        abrirGrabacion(motivo); // 2.5: antes de la bocina y del micrófono, para que las pistas empiecen juntas
        if (rutaRaiz && muteMicFix == 1) silenciarMicFisicoRaiz(); // v7.1: FIX A opt-in; default 0 preserva v6-exacto (uplink OK, eco acústico presente)
        iniciarReproduccion();
        iniciarCaptura();
        armarTechoLlamada();
        Bitacora.log("Conversación: " + motivo + ". Isabella espera a que hablen (saludo de respaldo a los "
                + (aprox ? 20 : 12) + " s si nadie habla).");
        if (saludarAlContestar) {
            final String texto = (fraseSaludo != null && !fraseSaludo.isEmpty())
                    ? (fraseSaludo.toLowerCase(Locale.ROOT).startsWith("sistema:") ? fraseSaludo : "Sistema: " + fraseSaludo)
                    : SISTEMA_SALUDO;
            String estrategia;
            if (demoraSaludoS == -1) estrategia = "500 ms de margen (compat --es demora_saludo_s -1)";
            else if (demoraSaludoS > 0) estrategia = "delay fijo " + demoraSaludoS + " s (--es demora_saludo_s " + demoraSaludoS + ")";
            else if (aprox) estrategia = "al terminar el ringback o al oír al interlocutor; tope " + (SALUDO_TOPE_MS / 1000) + " s";
            else estrategia = "500 ms de margen (STATE_ACTIVE real: ya contestaron)";
            Bitacora.log("Flag «saludar»: pediré a Isabella que salude · " + estrategia
                    + (texto == SISTEMA_SALUDO ? "." : " · con frase a medida."));
            programarSaludoInmediato(texto, aprox);
        }
    }

    // Umbrales de la detección de fin de ringback para el saludo inmediato (Ajuste V5).
    // 6 s cubre un ciclo completo del tono mx (1 s sí + 4 s no) + margen; mismo umbral que quizaSaludoRespaldo.
    private static final long SALUDO_UMBRAL_FIN_TONO_MS = 6000;
    // Si NUNCA llega el tono (carrier mudo, Bluetooth, etc.) se saluda a los 8 s: no tiene sentido esperar más.
    private static final long SALUDO_UMBRAL_SIN_TONO_MS = 8000;
    private static final long SALUDO_TOPE_MS = 90_000;
    private static final long SALUDO_POLL_MS = 500;

    /**
     * Programa el saludo inmediato cuando --es saludar 1. En ruta estricta (`!aprox`, STATE_ACTIVE real) usa el
     * margen clásico de 500 ms: ya contestaron. En ruta clásica (`aprox`), según `demoraSaludoS`:
     *   -1 → compat 500 ms (como v4);  0 → detección de fin de ringback (default, ver revisarFinDeTonoParaSaludar);
     *   N>0 → delay fijo de N s.
     */
    private void programarSaludoInmediato(final String texto, final boolean aprox) {
        if (terminada || saludoPedido) return;
        if (demoraSaludoS == -1 || !aprox) {
            final String motivo = (demoraSaludoS == -1) ? "compat 500 ms" : "500 ms (STATE_ACTIVE real)";
            temporizadores.schedule(() -> enAccion(() -> enviarSaludoInmediato(texto, motivo)),
                    500, TimeUnit.MILLISECONDS);
            return;
        }
        if (demoraSaludoS > 0) {
            final int seg = demoraSaludoS;
            temporizadores.schedule(() -> enAccion(() -> enviarSaludoInmediato(texto, "delay fijo " + seg + " s")),
                    seg * 1000L, TimeUnit.MILLISECONDS);
            return;
        }
        // Default inteligente: aprox + demora_saludo_s=0.
        revisarFinDeTonoParaSaludar(texto, System.currentTimeMillis());
    }

    /**
     * Reagenda cada 500 ms hasta que se cumpla alguna condición para saludar:
     *   - activaReal() cambió conversacionAprox a false (llegó STATE_ACTIVE real tardío) → saludo ya;
     *   - tPrimeraVozMs != 0 (Gemini oyó al interlocutor) → saludo ya;
     *   - el filtro de tono vio ringback y lleva >=6 s sin oírlo (fin del tono real) → saludo ya;
     *   - nunca se detectó ringback en 8 s (sin ringback, Bluetooth, etc.) → saludo ya;
     *   - tope de seguridad 90 s → saludo ya (en la práctica el fusible «nadie contesto en 90 s» ya cerró la llamada).
     */
    private void revisarFinDeTonoParaSaludar(final String texto, final long t0) {
        if (terminada || saludoPedido || !enConversacion) return;
        SesionGemini s = sesion;
        if (s == null || !s.estaLista()) {
            temporizadores.schedule(() -> enAccion(() -> revisarFinDeTonoParaSaludar(texto, t0)),
                    SALUDO_POLL_MS, TimeUnit.MILLISECONDS);
            return;
        }
        long ahora = System.currentTimeMillis();
        long transcurridoMs = ahora - t0;
        boolean activaRealLlego = !conversacionAprox;
        boolean hablo = tPrimeraVozMs != 0;
        boolean finTono = ultimoTonoMs != 0 && !enTonoAhora && ahora - ultimoTonoMs >= SALUDO_UMBRAL_FIN_TONO_MS;
        boolean sinTonoNunca = ultimoTonoMs == 0 && transcurridoMs >= SALUDO_UMBRAL_SIN_TONO_MS;
        boolean topeAlcanzado = transcurridoMs >= SALUDO_TOPE_MS;
        if (activaRealLlego || hablo || finTono || sinTonoNunca || topeAlcanzado) {
            String motivo = activaRealLlego ? "STATE_ACTIVE real tras la aprox"
                    : hablo ? "oí al interlocutor"
                    : finTono ? "fin del tono de llamada (" + ((ahora - ultimoTonoMs) / 1000) + " s sin ringback)"
                    : sinTonoNunca ? "sin tono en " + (SALUDO_UMBRAL_SIN_TONO_MS / 1000) + " s (¿carrier sin ringback?)"
                    : "tope de seguridad " + (SALUDO_TOPE_MS / 1000) + " s";
            enviarSaludoInmediato(texto, motivo + " a los " + String.format(Locale.US, "%.1f", transcurridoMs / 1000.0) + " s");
            return;
        }
        temporizadores.schedule(() -> enAccion(() -> revisarFinDeTonoParaSaludar(texto, t0)),
                SALUDO_POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void enviarSaludoInmediato(String texto, String motivo) {
        SesionGemini s = sesion;
        if (terminada || saludoPedido || s == null || !s.estaLista()) return;
        saludoPedido = true;
        Bitacora.log("Saludo inmediato (" + motivo + "): pido a Isabella que salude ya.");
        s.enviarSistema(texto);
        anotarSistema(texto);
    }

    /** Oyente de UNA sesión: lo que llegue de una sesión vieja o ya cerrada se ignora. */
    private final class OyenteSesion implements SesionGemini.Oyente {
        private final SesionGemini[] ref;

        OyenteSesion(SesionGemini[] ref) {
            this.ref = ref;
        }

        private boolean vigente() {
            return ref[0] != null && ref[0] == sesion;
        }

        @Override
        public void onLista() {
            if (!vigente()) return;
            ultimoDelServidor = System.currentTimeMillis();
            enAccion(() -> {
                if (vigente() && !terminada) sesionLista();
            });
        }

        @Override
        public void onVozIsabella(byte[] pcm24k) {
            if (!vigente()) return;
            long ahora = System.currentTimeMillis();
            ultimoDelServidor = ahora;
            if (tPrimerAudioMs == 0) tPrimerAudioMs = ahora;
            marcas.primerTrozo(pcm24k); // antes de encolarlo: la bocina lo reconoce al escribirlo
            encolarVoz(pcm24k);
        }

        @Override
        public void onIsabellaHabla(boolean on, String motivo) {
            if (!vigente()) return;
            long ahora = System.currentTimeMillis();
            ultimoDelServidor = ahora;
            isabellaHablando = on;
            if (on) {
                turnoConVoz = true;
                for (String linea : marcas.alPrimerAudio(ahora)) Bitacora.log(linea);
            } else {
                isabellaHablaHastaMs = ahora + 500; // cola del medio dúplex
                ultimaActividadMs = ahora;
                if ("interrumpida".equals(motivo)) vaciarReproduccion();
                Grabacion g = grabacion;
                if (g != null) g.finTurno("interrumpida".equals(motivo));
                synchronized (turnoIsabella) {
                    turnoIsabella.setLength(0);
                }
                boolean conVoz = turnoConVoz;
                turnoConVoz = false;
                // 2.5: terminó un turno con voz sin decir que se graba (el contexto no bastó): se le pide UNA vez.
                if ("fin".equals(motivo) && conVoz && g != null && !grabacionMencionada && !recordatorioGrabacion) {
                    recordatorioGrabacion = true;
                    enAccion(() -> {
                        if (vigente()) recordarAvisoDeGrabacion();
                    });
                }
            }
        }

        @Override
        public void onTexto(String quien, String texto) {
            if (!vigente()) return;
            ultimoDelServidor = System.currentTimeMillis();
            Bitacora.log(("isabella".equals(quien) ? "Isabella" : "Interlocutor") + ": " + texto);
            Grabacion g = grabacion;
            if (g != null) g.texto(quien, texto);
            if ("isabella".equals(quien) && grabar && !grabacionMencionada) {
                synchronized (turnoIsabella) {
                    turnoIsabella.append(texto); // el turno entero: «grab» puede venir partido en dos fragmentos
                    if (Grabacion.mencionaGrabacion(turnoIsabella)) grabacionMencionada = true;
                }
            }
        }

        @Override
        public void onActividadInterlocutor(String tipo) {
            if (!vigente()) return;
            long ahora = System.currentTimeMillis();
            ultimoDelServidor = ahora;
            ultimaVozMs = ahora;
            ultimaActividadMs = ahora;
            if ("inicio".equals(tipo)) interlocutorHablando = true;
            else if ("fin".equals(tipo)) {
                interlocutorHablando = false;
                marcas.alFinDeVozGemini(ahora);
            }
            if (tPrimeraVozMs == 0 && enConversacion) {
                tPrimeraVozMs = ahora;
                Bitacora.log("Primera voz del interlocutor a " + ((ahora - tConversacionMs) / 1000) + " s de empezar la conversación.");
            }
        }

        @Override
        public void onColgar(long topeMs) {
            if (!vigente()) return;
            Bitacora.log("Cuelgo cuando la despedida termine de sonar (tope " + (topeMs / 1000) + " s).");
            programarColgarTrasDespedida(topeMs);
        }

        @Override
        public void onAviso(String aviso) {
            if (vigente()) Bitacora.log(aviso);
        }

        @Override
        public void onError(String motivo) {
            if (!vigente()) return;
            enAccion(() -> {
                if (vigente()) falloSesion(true, motivo);
            });
        }

        @Override
        public void onCerrada(String motivo) {
            if (!vigente()) return;
            enAccion(() -> {
                if (vigente()) falloSesion(false, motivo);
            });
        }
    }

    /** Encola en el hilo de acción; si el servicio ya se está destruyendo, se ignora. */
    private void enAccion(Runnable r) {
        try {
            accion.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException ignorado) {
            // el servicio terminó: no queda nada que hacer
        }
    }

    // --- Fusibles ------------------------------------------------------------

    // Cada segundo: fusibles de inactividad, «nadie contestó», saludo de respaldo y aviso de silencio.
    private void armarVigilante() {
        cancelar(vigilante);
        vigilante = temporizadores.scheduleWithFixedDelay(() -> enAccion(this::vigilar), 1, 1, TimeUnit.SECONDS);
    }

    private void vigilar() {
        if (terminada) return;
        long t = System.currentTimeMillis();
        SesionGemini sw = sesion;
        if (sw != null) sw.revisarHueco(); // anota un hueco del servidor cuya ventana de 300 ms ya cerró
        long trabada = sw != null ? sw.msEscribiendo() : 0;
        if (trabada > RED_ATORADA_MS) { // fusible m: la red no deja salir nada desde hace 5 s
            Bitacora.log("Red atorada: " + (trabada / 1000) + " s sin poder escribirle a Gemini; cierro la sesión y cuelgo.");
            terminar("red atorada");
            return;
        }
        if (!enConversacion) {
            if ("dialing".equals(estadoLlamada) && tMarcadoMs != 0 && t - tMarcadoMs > 90_000) {
                Bitacora.log("Nadie contestó en 90 s; cuelgo.");
                terminar("nadie contestó en 90 s");
            }
            return;
        }
        if (sesion == null) return;
        // El servidor calla mientras nadie habla: este fusible cuenta desde que hubo voz de alguien.
        boolean hablaron = tPrimerAudioMs != 0 || tPrimeraVozMs != 0;
        if (hablaron && t - ultimoDelServidor > 30_000) {
            Bitacora.log("Vigilante: 30 s sin respuesta del servidor; cerrando.");
            terminar("vigilante: servidor callado");
            return;
        }
        if (capturando && t - ultimoMicrofono > 10_000) {
            Bitacora.log("Vigilante: 10 s sin micrófono; cerrando.");
            terminar("vigilante: micrófono callado");
            return;
        }
        if (conversacionAprox && tPrimeraVozMs == 0 && tPrimerAudioMs == 0 && ultimoTonoMs != 0
                && t - ultimoTonoMs < 6000 && tMarcadoMs != 0 && t - tMarcadoMs > 90_000) {
            Bitacora.log("Sigue sonando a los 90 s: nadie contestó; cuelgo.");
            terminar("sigue sonando a los 90 s (nadie contestó)");
            return;
        }
        quizaSaludoRespaldo(t);
        quizaAvisoSilencio(t);
    }

    /** Espec §11.3: solo si Isabella no ha hablado, sin tono reciente, sin voz en curso ni respuesta por llegar. */
    private void quizaSaludoRespaldo(long t) {
        SesionGemini s = sesion;
        if (saludoPedido || tPrimerAudioMs != 0 || s == null || !s.estaLista()) return;
        if (colgarPendiente || isabellaHablando) return;
        long espera = conversacionAprox ? 20_000 : 12_000;
        if (t - tConversacionMs < espera) return;
        if (enTonoAhora || (ultimoTonoMs != 0 && t - ultimoTonoMs < 6000)) return; // sigue sonando (1 s sí, 4 s no)
        if (interlocutorHablando || (ultimaVozMs != 0 && t - ultimaVozMs < 4000)) return; // habla, o Gemini está por contestar
        saludoPedido = true;
        Bitacora.log("Saludo de respaldo: " + ((t - tConversacionMs) / 1000) + " s sin que Isabella hable; le pido que salude.");
        s.enviarSistema(SISTEMA_SALUDO);
        anotarSistema(SISTEMA_SALUDO);
        cancelar(saludoReintento);
        saludoReintento = temporizadores.schedule(() -> enAccion(() -> {
            SesionGemini s2 = sesion;
            if (!terminada && tPrimerAudioMs == 0 && s2 != null && s2.estaLista()) {
                Bitacora.log("Saludo de respaldo: sin audio a los 8 s; lo pido otra vez por realtimeInput.");
                s2.enviarSistemaRealtime(SISTEMA_SALUDO);
                anotarSistema(SISTEMA_SALUDO);
            }
        }), 8, TimeUnit.SECONDS);
    }

    /** Después del primer audio de Isabella: 12 s sin actividad de nadie → pregunta si la escuchan (máx. 2). */
    private void quizaAvisoSilencio(long t) {
        SesionGemini s = sesion;
        if (empujones >= 2 || tPrimerAudioMs == 0 || s == null || !s.estaLista()) return;
        if (colgarPendiente || isabellaHablando || !colaVoz.isEmpty()) return;
        if (ultimaActividadMs == 0 || t - ultimaActividadMs <= 12_000) return;
        empujones++;
        ultimaActividadMs = t;
        Bitacora.log("Silencio: 12 s sin oír a nadie; Isabella pregunta si la escuchan (" + empujones + "/2).");
        s.enviarSistema(SISTEMA_SILENCIO);
        anotarSistema(SISTEMA_SILENCIO);
    }

    // Techo (fusible b) desde que empieza la conversación, con aviso de tiempo antes (como el puente).
    private void armarTechoLlamada() {
        cancelar(techoLlamada);
        cancelar(avisoTiempo);
        long techoMs = maxMin * 60_000L;
        // Fusible n (2.4): Google corta 86 ms después del expireTime del token (sesión 6). Si el token vence antes del
        // techo, el techo se adelanta 5 s antes del vencimiento para que el aviso y la despedida alcancen.
        long vence = venceTokenMs;
        if (vence > 0) {
            long margen = vence - System.currentTimeMillis() - 5_000L;
            if (margen < techoMs) {
                techoMs = Math.max(15_000L, margen);
                Bitacora.log("El token vence antes del techo: corto a los " + (techoMs / 1000) + " s para que Isabella alcance a despedirse.");
            }
        }
        final long maxMs = techoMs;
        final String cual = maxMs == maxMin * 60_000L ? maxMin + " min" : (maxMs / 1000) + " s (vida del token)";
        final long avisoMs = Math.max(maxMs - 25_000L, (long) (maxMs * 0.75));
        final long segs = Math.round((maxMs - avisoMs) / 1000.0);
        avisoTiempo = temporizadores.schedule(() -> enAccion(() -> avisarTiempo(segs, 0)), avisoMs, TimeUnit.MILLISECONDS);
        techoLlamada = temporizadores.schedule(() -> {
            Bitacora.log("Techo de " + cual + " alcanzado; Isabella se despide y la app cuelga.");
            terminar("techo de " + cual);
        }, maxMs, TimeUnit.MILLISECONDS);
    }

    /** turnComplete:true corta lo que Isabella esté diciendo: el aviso espera a que se calle (≤ 8 s). */
    private void avisarTiempo(final long segs, final int intentos) {
        SesionGemini s = sesion;
        if (terminada || colgarPendiente || s == null) return;
        if ((isabellaHablando || !colaVoz.isEmpty()) && intentos < 32) {
            avisoTiempo = temporizadores.schedule(() -> enAccion(() -> avisarTiempo(segs, intentos + 1)), 250, TimeUnit.MILLISECONDS);
            return;
        }
        Bitacora.log("Aviso de tiempo: faltan ~" + segs + " s; le pido a Isabella que se despida.");
        s.enviarSistema(sistemaAviso(segs));
        anotarSistema(sistemaAviso(segs));
    }

    private void cancelarTemporizadores() {
        cancelar(vigilante);
        cancelar(techoLlamada);
        cancelar(marcadoWatchdog);
        cancelar(drenajeColgar);
        cancelar(aperturaWatchdog);
        cancelar(avisoTiempo);
        cancelar(saludoReintento);
        topeTimbre.reiniciar();
        vigilante = null;
        techoLlamada = null;
        marcadoWatchdog = null;
        drenajeColgar = null;
        aperturaWatchdog = null;
        avisoTiempo = null;
        saludoReintento = null;
        colgarPendiente = false;
    }

    private static void cancelar(ScheduledFuture<?> f) {
        if (f != null) f.cancel(false);
    }

    // --- Fin ordenado --------------------------------------------------------

    private void terminar(String motivo) {
        terminar(motivo, false);
    }

    private synchronized void terminar(String motivo, boolean noColgar) {
        if (terminada) return;
        terminada = true;
        generacionSesion++; // una preparación de sesión en curso ya no abre nada
        marcarAlEstarLista = false;
        estadoLlamada = "ended";
        cancelarTemporizadores();
        // Primero cortar el gasto de Gemini (fusibles a/b/d/h/m: en ≤ 1 s) sin esperar a la red: se avisa a la
        // captura y a la bocina, se cierra la sesión (≤ 300 ms aunque la red esté atorada) y se cuelga; después
        // se espera a los hilos de audio.
        capturando = false;
        reproduciendo = false;
        final SesionGemini s = sesion;
        sesion = null;
        // Cada paso con su guardia: si uno truena, los demás (sobre todo colgar) corren igual.
        if (s != null) paso("cerrar la sesión", s::cerrar);
        paso("parar el tono", this::pararTonoSostenido);
        if (!noColgar && !MODO_ISABELLA.equals(modo)) paso("colgar", this::colgarLinea);
        paso("detener la captura", this::detenerCaptura);
        paso("detener la bocina", this::detenerReproduccion);
        paso("parar el WAV al contestar", this::detenerWavContestar);
        paso("cerrar la grabación", this::cerrarGrabacion);
        paso("soltar el altavoz", this::soltarAltavoz);
        if (rutaRaiz && muteMicFix == 1) paso("desmutear el mic físico", this::desmutearMicFisicoRaiz); // v7.1: solo si activamos FIX A por flag. onDestroy aún llama al desmutear como safety net (idempotente por el guard isMicrophoneMute()).
        paso("soltar los candados", this::soltarCandados);
        paso("quitar el seguimiento de la línea", this::quitarCallback);
        if (rutaRaiz) paso("cerrar los mixers de la ruta I-3", RaizUtil::cerrarAmbosMixers);
        if (trozosVozTirados > 0) {
            Bitacora.log("Cola de voz: " + trozosVozTirados + " trozos tirados en la llamada (estaba llena: " + MAX_CHUNKS_VOZ + ").");
        }
        Bitacora.log("Terminado (" + motivo + ").");
        estadoLlamada = "idle";
        enConversacion = false;
        enCurso = false;
        paso("quitar el primer plano", () -> stopForeground(STOP_FOREGROUND_REMOVE));
        enPrimerPlano = false;
        stopSelf();
    }

    /** Un paso de terminar(): si lanza una excepción se anota y se sigue con lo demás. */
    private static void paso(String nombre, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Bitacora.log("Al terminar falló «" + nombre + "» (" + e + "); sigo con lo demás.");
        }
    }

    // endCall() SÍ funciona en AOSP 14 para una app normal con ANSWER_PHONE_CALLS (verificado por
    // `arquitecto`): el fusible b vale aun sin InCallService. Está deprecado a favor de InCallService,
    // pero sigue disponible; con el InCallService ligado preferimos Call.disconnect().
    @SuppressWarnings("deprecation")
    private void colgarLinea() {
        if (ServicioEnLlamada.enLlamada()) {
            ServicioEnLlamada.colgar();
            Bitacora.log("Colgado por InCallService (Call.disconnect()).");
            return;
        }
        try {
            boolean ok = telecom.endCall();
            Bitacora.log("endCall devolvió " + ok + (ok ? "" : " (quizá ya no había llamada)"));
        } catch (SecurityException e) {
            Bitacora.log("endCall rechazado (falta ANSWER_PHONE_CALLS): " + e.getMessage());
        }
    }

    // --- Altavoz -------------------------------------------------------------

    // setSpeakerphoneOn/isSpeakerphoneOn están deprecados desde API 31 a favor de setCommunicationDevice;
    // se llaman a propósito como respaldo y para registrar cuál método quedó en efecto.
    @SuppressWarnings("deprecation")
    private void ponerAltavoz() {
        if (!bocina) { // 2.6: no se pide ninguna ruta; se anota la que puso Telecom
            Bitacora.log("Bocina 0: no pido el altavoz. Ruta de la llamada según Telecom: " + ServicioEnLlamada.rutaActual() + ".");
            return;
        }
        if (ServicioEnLlamada.enLlamada()) {
            ServicioEnLlamada.rutaAltavoz();
            Bitacora.log("Altavoz por InCallService (setAudioRoute ROUTE_SPEAKER).");
            return;
        }
        altavozPedido = true; // se suelta en terminar()/onDestroy(): si no, la petición queda para otras apps
        boolean porDispositivo = false;
        try {
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    porDispositivo = am.setCommunicationDevice(d);
                    break;
                }
            }
            Bitacora.log("Altavoz por setCommunicationDevice: " + (porDispositivo ? "aceptado" : "no aceptado"));
        } catch (Exception e) {
            Bitacora.log("setCommunicationDevice falló: " + e.getMessage());
        }
        try {
            am.setSpeakerphoneOn(true);
        } catch (Exception e) {
            Bitacora.log("setSpeakerphoneOn falló: " + e.getMessage());
        }
        try {
            AudioDeviceInfo dev = am.getCommunicationDevice();
            String tipo = dev != null ? nombreTipoDispositivo(dev.getType()) : "ninguno";
            Bitacora.log("Dispositivo de comunicación en efecto: " + tipo + " · altavoz=" + am.isSpeakerphoneOn());
        } catch (Exception ignorado) {
        }
    }

    /** Suelta lo que pidió ponerAltavoz (Android solo lo suelta al morir el proceso). Hallazgo de `dispositivo`. */
    @SuppressWarnings("deprecation")
    private void soltarAltavoz() {
        if (!altavozPedido) return;
        altavozPedido = false;
        try {
            am.clearCommunicationDevice();
        } catch (Exception ignorado) {
        }
        try {
            am.setSpeakerphoneOn(false);
        } catch (Exception ignorado) {
        }
        Bitacora.log("Altavoz soltado (clearCommunicationDevice y setSpeakerphoneOn(false)).");
    }

    // --- Mute del mic físico en ruta raíz ------------------------------------

    /**
     * En ruta raíz el mic físico NO alimenta nada que la app necesite: el DL del interlocutor
     * entra por tinycap (MM9 ← VOC_REC_DL) y la voz de Isabella sube por tinyplay
     * (PCM 0,1 → MM2 → Incall_Music → modem UP). Dejarlo abierto significa que capta lo que
     * suena por el earpiece del A72 (la voz del interlocutor en DL) y lo retransmite por
     * VoiceMMode1_Tx al UP del modem → el A55 oye eco. Lo muteamos a nivel AudioManager:
     * el camino kernel sigue abierto (el modem no se queja) pero envía silencio digital.
     */
    private void silenciarMicFisicoRaiz() {
        try {
            if (am != null && !am.isMicrophoneMute()) {
                am.setMicrophoneMute(true);
                Bitacora.log("Ruta RAÍZ: mic FÍSICO muteado · el DL llega por tinycap (MM9), el UP sale por tinyplay (MM2); así el earpiece no se re-emite acústicamente por el mic.");
            }
        } catch (Exception e) {
            Bitacora.log("Ruta RAÍZ: no pude mutear el mic físico (" + e.getMessage() + "); el eco acústico puede seguir.");
        }
    }

    private void desmutearMicFisicoRaiz() {
        try {
            if (am != null && am.isMicrophoneMute()) {
                am.setMicrophoneMute(false);
                Bitacora.log("Ruta RAÍZ: mic físico desmuteado al terminar.");
            }
        } catch (Exception ignorado) {}
    }

    // --- Captura del micrófono ----------------------------------------------

    private void iniciarCaptura() {
        if (capturando) return;
        capturando = true;
        hiloCaptura = new Thread(this::bucleCaptura, "captura-mic");
        hiloCaptura.start();
    }

    private void bucleCaptura() {
        FuenteAudio rec = null;
        prioridadDeAudio();
        try {
            int fuente = fuenteParaLlamada();
            rec = construirGrabadora(fuente, RATE_MIC);
            if (rec == null || rec.getState() != FuenteAudio.ESTADO_LISTO) {
                Bitacora.log("No se pudo abrir el micrófono (fuente " + nombreFuente(fuente) + ").");
                return;
            }
            int sid = rec.getAudioSessionId();
            if (sid > 0) aplicarEco(sid);
            rec.startRecording();
            Bitacora.log("Micrófono abierto: " + (rutaRaiz ? "RAÍZ VOC_REC_DL" : nombreFuente(fuente)) + " 16 kHz mono, tramas de 20 ms.");
            byte[] trama = new byte[TRAMA_MIC_BYTES];
            long ultimoAviso = 0;
            boolean dispositivoAnotado = false; // 2.6: de dónde toma Android el micrófono (una vez, tras la primera trama)
            FiltroTono filtroTono = new FiltroTono(RATE_MIC);
            while (capturando) {
                int n = rec.read(trama, 0, trama.length);
                if (n <= 0) {
                    if (n < 0) {
                        Bitacora.log("Lectura del micrófono devolvió error " + n + "; se detiene la captura.");
                        break;
                    }
                    continue;
                }
                ultimoMicrofono = System.currentTimeMillis();
                if (!dispositivoAnotado) {
                    dispositivoAnotado = true;
                    Bitacora.log("Micrófono: Android lo toma de " + nombreDispositivo(rec.getDispositivoRuta()) + ".");
                }
                Grabacion g = grabacion;
                if (g != null) g.microfono(trama, n); // 2.5: lo que capta el micrófono, antes del filtro y del medio dúplex
                // Medio dúplex: no mandar mientras Isabella habla y 500 ms después.
                boolean silenciarEnvio = isabellaHablando || System.currentTimeMillis() < isabellaHablaHastaMs;
                // Filtro del tono de llamada: una copia por trama (el filtro retiene referencias).
                byte[] copia = java.util.Arrays.copyOf(trama, n);
                SesionGemini s = sesion;
                MarcasLatencia m = marcas;
                for (byte[] f : filtroTono.filtra(copia)) {
                    if (!silenciarEnvio && s != null && s.enviarMicrofono(f, f.length)) {
                        m.alEnviarTrama(System.currentTimeMillis(), Wav.rms(f, f.length)); // fin de la voz local
                    }
                }
                boolean tono = filtroTono.enTono();
                enTonoAhora = tono;
                if (tono) ultimoTonoMs = System.currentTimeMillis();

                long ahora = System.currentTimeMillis();
                if (ahora - ultimoAviso > 3000) {
                    ultimoAviso = ahora;
                    boolean silenciado = rec.isClientSilenced();
                    Bitacora.log(String.format(Locale.US, "Mic: RMS %.0f · %s%s%s", Wav.rms(trama, n),
                            silenciado ? "SILENCIADO por Android" : "con señal",
                            silenciarEnvio ? " · (medio dúplex: no se envía)" : "",
                            filtroTono.enTono() ? " · TONO de llamada (no se envía)" : ""));
                }
            }
        } catch (Exception e) {
            Bitacora.log("Error en la captura: " + e.getMessage());
        } finally {
            if (cancelaEco != null) {
                try { cancelaEco.release(); } catch (Exception ignorado) {}
                cancelaEco = null;
            }
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignorado) {}
                rec.release();
            }
        }
    }

    private FuenteAudio construirGrabadora(int fuente, int rate) {
        if (rutaRaiz) {
            FuenteRaiz fr = new FuenteRaiz(rate, getFilesDir().getAbsolutePath());
            if (fr.getState() != FuenteAudio.ESTADO_LISTO) { fr.release(); return null; }
            return fr;
        }
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = TRAMA_MIC_BYTES * 8;
        int buffer = Math.max(min, TRAMA_MIC_BYTES * 8);
        try {
            AudioRecord ar = new AudioRecord.Builder()
                    .setAudioSource(fuente)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(buffer)
                    .build();
            return new FuenteNativa(ar);
        } catch (Exception e) {
            Bitacora.log("No se pudo construir AudioRecord: " + e.getMessage());
            return null;
        }
    }

    private void aplicarEco(int sessionId) {
        if ("default".equals(aec)) return;
        if (!AcousticEchoCanceler.isAvailable()) {
            Bitacora.log("La cancelación de eco por hardware no está disponible en este cel.");
            return;
        }
        try {
            cancelaEco = AcousticEchoCanceler.create(sessionId);
            if (cancelaEco != null) {
                cancelaEco.setEnabled("on".equals(aec));
                Bitacora.log("Cancelación de eco del cel: " + ("on".equals(aec) ? "encendida" : "apagada") + ".");
            }
        } catch (Exception e) {
            Bitacora.log("No se pudo ajustar la cancelación de eco: " + e.getMessage());
        }
    }

    // Hallazgo 6 del arquitecto: captura y bocina con prioridad de audio (la de Android para hilos de audio).
    private static void prioridadDeAudio() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception e) {
            Bitacora.log("No pude subir la prioridad del hilo de audio: " + e.getMessage());
        }
    }

    private void detenerCaptura() {
        capturando = false;
        if (hiloCaptura != null) {
            try { hiloCaptura.join(500); } catch (InterruptedException ignorado) { Thread.currentThread().interrupt(); }
            hiloCaptura = null;
        }
    }

    // --- Reproducción de la voz de Isabella ----------------------------------

    private void iniciarReproduccion() {
        if (reproduciendo) return;
        reproduciendo = true;
        colaVoz.clear();
        hiloReproduccion = new Thread(this::bucleReproduccion, "reproduce-voz");
        hiloReproduccion.start();
    }

    private void bucleReproduccion() {
        SumideroAudio track = null;
        prioridadDeAudio();
        try {
            track = construirReproductor(usoAudio, RATE_VOZ);
            if (track == null || track.getState() != SumideroAudio.ESTADO_LISTO) {
                Bitacora.log("No se pudo abrir el reproductor de la voz de Isabella.");
                return;
            }
            reproductor = track;
            track.play();
            Bitacora.log("Reproductor de voz abierto: 24 kHz mono, uso " + (rutaRaiz ? "RAÍZ Incall_Music" : nombreUso(usoAudio))
                    + (rutaRaiz && gananciaTtsDecimas != 10
                        ? " · ganancia TTS x" + String.format(Locale.US, "%.1f", gananciaTtsDecimas / 10.0f) + " (clamp int16; grabación en nivel natural)"
                        : "")
                    + (rutaRaiz && preenfasisCentesimas > 0
                        ? " · pre-énfasis α=" + String.format(Locale.US, "%.2f", preenfasisCentesimas / 100.0f) + " (brillo contra rolloff AMR-WB)"
                        : "")
                    + (rutaRaiz && softclipKneeDecimas != 10
                        ? " · softclip knee x" + String.format(Locale.US, "%.1f", softclipKneeDecimas / 10.0f) + " (headroom tanh)"
                        : "")
                    + (rutaRaiz && hpfHz > 0
                        ? " · HPF 1er orden a " + hpfHz + " Hz (quita rumble sub-fc antes de la ganancia)"
                        : "")
                    + ".");
            boolean dispositivoAnotado = false; // 2.6: por dónde sale la voz (una vez, tras el primer trozo)
            while (reproduciendo) {
                byte[] chunk = colaVoz.poll(200, TimeUnit.MILLISECONDS);
                if (chunk == null) continue;
                Grabacion g = grabacion;
                if (g != null) g.isabella(chunk); // 2.5: la voz tal como entra a la bocina (nivel natural, antes de la ganancia)
                String marca = marcas.alEscribirBocina(chunk, System.currentTimeMillis());
                if (marca != null) Bitacora.log(marca);
                // Ajuste V5: solo en ruta raíz amplificamos digitalmente la voz de Isabella (en la ruta nativa
                // AudioTrack.setVolume ya cubre el volumen y el camino MM2→Incall_Music no expone un control
                // tipo AudioTrack.setVolume). Si clipa, bajar --es ganancia_tts.
                // V7: pre-énfasis (FIX A) y knee del soft-clip (FIX D) también solo en ruta raíz; con
                // ganancia=10 (×1.0) y preenfasis=0 el chunk pasa sin tocar (comportamiento v6 exacto).
                // V7.4: HPF 1er orden pre-pipe (hpf_hz>0) añadido al condicional; con hpf_hz=0 el loop
                // interno no entra en la rama HPF y el comportamiento es v7.3 bit-a-bit.
                boolean aplicaCadena = rutaRaiz && (gananciaTtsDecimas != 10 || preenfasisCentesimas > 0 || hpfHz > 0);
                byte[] chunkAEscribir = aplicaCadena
                        ? aplicarGananciaPcm16(chunk, gananciaTtsDecimas / 10.0f, preenfasisCentesimas, softclipKneeDecimas, hpfHz)
                        : chunk;
                int escrito = 0;
                while (escrito < chunkAEscribir.length && reproduciendo) {
                    int n = track.write(chunkAEscribir, escrito, chunkAEscribir.length - escrito);
                    if (n <= 0) {
                        Bitacora.log("Error al reproducir: " + n);
                        return;
                    }
                    escrito += n;
                }
                if (!dispositivoAnotado) {
                    dispositivoAnotado = true;
                    Bitacora.log("Voz de Isabella: Android la saca por " + nombreDispositivo(track.getDispositivoRuta()) + ".");
                }
            }
        } catch (InterruptedException ignorado) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Bitacora.log("Error en la reproducción: " + e.getMessage());
        } finally {
            // Si el while se rompió por write<=0 (pacer murió, tinyplay zombie, etc.),
            // bajar reproduciendo deja el estado consistente para getEstado/logs y para
            // cualquier futura reapertura. En cierre ordenado (detenerReproduccion) ya
            // viene en false; esto es defensivo para el camino anormal.
            reproduciendo = false;
            reproductor = null;
            if (track != null) {
                try { track.pause(); track.flush(); track.stop(); } catch (Exception ignorado) {}
                track.release();
            }
        }
    }

    /** Isabella fue interrumpida: tira el audio viejo para que no siga sonando. */
    private synchronized void vaciarReproduccion() {
        colaVoz.clear();
        SumideroAudio t = reproductor;
        if (t != null) {
            try {
                t.pause();
                t.flush();
                t.play();
            } catch (Exception ignorado) {
            }
        }
    }

    private SumideroAudio construirReproductor(int uso, int rate) {
        if (rutaRaiz) {
            SumideroRaiz sr = new SumideroRaiz(rate, getFilesDir().getAbsolutePath(), prefillTtsMs, ditherTts);
            if (sr.getState() != SumideroAudio.ESTADO_LISTO) { sr.release(); return null; }
            return sr;
        }
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = rate / 5 * 2;
        int buffer = Math.max(min, rate / 5 * 2); // ~200 ms
        try {
            AudioTrack at = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(uso)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(buffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            return new SumideroNativo(at);
        } catch (Exception e) {
            Bitacora.log("No se pudo construir AudioTrack: " + e.getMessage());
            return null;
        }
    }

    private void detenerReproduccion() {
        reproduciendo = false;
        if (hiloReproduccion != null) {
            try { hiloReproduccion.join(500); } catch (InterruptedException ignorado) { Thread.currentThread().interrupt(); }
            hiloReproduccion = null;
        }
        colaVoz.clear();
    }

    // La cola de voz guarda hasta 60 trozos (mediana 320 ms cada uno, espec §5); si se llena tira lo más viejo,
    // lo avisa UNA vez y al terminar dice cuántos tiró (hallazgo 5 del arquitecto: antes lo hacía en silencio).
    private void encolarVoz(byte[] pcm) {
        ultimoAudioMs = System.currentTimeMillis();
        if (!reproduciendo) return;
        while (colaVoz.size() >= MAX_CHUNKS_VOZ) {
            if (colaVoz.poll() != null && ++trozosVozTirados == 1) {
                Bitacora.log("Cola de voz llena (" + MAX_CHUNKS_VOZ + " trozos): tiro lo más viejo. Aviso único; el total sale al terminar.");
            }
        }
        colaVoz.offer(pcm);
    }

    /**
     * Ajuste V5 (soft-clip) + V7 (pre-énfasis y knee ajustable): aplica ganancia digital
     * a un chunk PCM 16-bit LE mono con saturación suave (tanh), para que los picos de
     * consonantes oclusivas/silbantes amplificados ×3.0 no entren al modem como armónicos
     * de intermodulación por clip duro. Para muestras pequeñas es casi lineal (y ≈ g·x);
     * para muestras grandes satura a ±32767 con derivada continua. No muta el original (la
     * grabación conserva el nivel natural; eso lo hace la copia en el bucle de reproducción).
     *
     * V7 FIX A — pre-énfasis IIR 1er orden `y[n] = x[n] − α·x[n−1]` (α = alpha/100,
     * alpha=0..30, típico 12 → α=0.12). Compensa el rolloff perceptual del AMR-WB en
     * 4-7 kHz: devuelve brillo a sibilantes y consonantes fricativas. El estado `prev`
     * se guarda en `preenfasisEstado` entre chunks del MISMO reproductor (se resetea en
     * reiniciarEstado()). alpha=0 → sin pre-énfasis (v6 exacto, default v7).
     *
     * V7 FIX D — knee del soft-clip ajustable: `tanh(g·x / (ESCALA · kneeDec/10))`. kneeDec=10
     * → tanh clásico v6; kneeDec=11..15 → knee más alto, más headroom antes de saturar
     * (señal media más lineal, pico final sigue ±ESCALA). Útil con ganancias altas que
     * saturan constantemente el rango medio.
     */
    private byte[] aplicarGananciaPcm16(byte[] in, float g, int alpha, int kneeDec, int hpfHzLocal) {
        byte[] out = new byte[in.length];
        int n = in.length / 2;
        final float ESCALA = 32767f;
        final float ESCALA_KNEE = ESCALA * (kneeDec / 10.0f);
        int prev = preenfasisEstado;
        final boolean conPreenfasis = alpha > 0;
        final float ALPHA = alpha / 100.0f;
        // V7.4 HPF 1er orden topología RC equivalente: y[n] = α·(y[n-1] + x[n] − x[n-1]).
        // Estado (hpfXPrev, hpfYPrev) persiste entre chunks del mismo reproductor; reset en
        // reiniciarEstado(). Con hpf_hz=0, conHpf=false y la rama HPF del loop no se ejecuta:
        // la cadena recorre exactamente las mismas líneas de v7.3 (pre-énfasis, tanh, clamp)
        // y por tanto es bit-a-bit idéntica a v7.3.
        final boolean conHpf = hpfHzLocal > 0;
        final float HPF_ALPHA = conHpf
                ? 1f / (1f + (float) (2.0 * Math.PI) * hpfHzLocal / 24000f)
                : 1f;
        float hpfX1 = hpfXPrev, hpfY1 = hpfYPrev;
        for (int i = 0; i < n; i++) {
            int lo = in[i * 2] & 0xFF;
            int hi = in[i * 2 + 1];
            int muestra = (short) (lo | (hi << 8));
            int x = muestra;
            if (conHpf) {
                // IIR 1er orden pre-pipe: HPF a hpfHzLocal Hz. Atenúa contenido sub-fc que
                // de otro modo robaría headroom al tanh cuando ganancia_tts es alta.
                float xf = (float) x;
                float yf = HPF_ALPHA * (hpfY1 + xf - hpfX1);
                hpfX1 = xf;
                hpfY1 = yf;
                x = Math.round(yf);
            }
            if (conPreenfasis) {
                // IIR 1er orden: y[n] = x[n] − α·x[n−1]. Puede exceder int16 momentáneamente;
                // el soft-clip tanh que viene abajo lo doma de vuelta a ±32767.
                // V7.4: `prev` guarda la señal pre-pre-énfasis (post-HPF si conHpf, equivalente
                // a la muestra original si !conHpf → preserva v7.3 bit-a-bit).
                int preIn = x;
                x = x - (int) (ALPHA * prev);
                prev = preIn;
            }
            float y = (float) (ESCALA * Math.tanh(g * x / ESCALA_KNEE));
            int gan = Math.round(y);
            if (gan > 32767) gan = 32767;
            else if (gan < -32768) gan = -32768;
            out[i * 2]     = (byte) gan;
            out[i * 2 + 1] = (byte) (gan >> 8);
        }
        if (conPreenfasis) preenfasisEstado = prev;
        if (conHpf) {
            hpfXPrev = hpfX1;
            hpfYPrev = hpfY1;
        }
        return out;
    }

    // --- Diagnóstico ---------------------------------------------------------

    private void diagTono() {
        Bitacora.log("Diagnóstico: tono de 1 kHz, 2 s (uso " + nombreUso(usoAudio) + ")"
                + ("active".equals(estadoLlamada) ? "" : " · OJO: no hay llamada activa"));
        reproducirBloqueante(tonoSenoidal(1000, 2.0, RATE_VOZ), RATE_VOZ, usoAudio);
        Bitacora.log("Diagnóstico: tono terminado. ¿El interlocutor lo oyó?");
    }

    // Tono continuo para medir la ruta de audio (sobre todo uso=media) ANTES de marcar
    // y ver si sobrevive al conectar la llamada. Se corta con parar_tono o colgar.
    private void diagTonoSostenido() {
        if (tonoSostenido) { Bitacora.log("Diagnóstico: el tono sostenido ya estaba sonando."); return; }
        tonoSostenido = true;
        final int uso = usoAudio;
        Bitacora.log("Diagnóstico: tono SOSTENIDO iniciado (uso " + nombreUso(uso) + "). "
                + "Marca ahora; se corta con cmd=parar_tono o cmd=colgar.");
        hiloTono = new Thread(() -> {
            SumideroAudio track = construirReproductor(uso, RATE_VOZ);
            if (track == null || track.getState() != SumideroAudio.ESTADO_LISTO) {
                Bitacora.log("Diagnóstico: no se pudo abrir el reproductor del tono sostenido.");
                tonoSostenido = false;
                return;
            }
            byte[] buffer = tonoLoop(1000, RATE_VOZ); // 1 s; con 1 kHz a 24 kHz repite sin clic
            try {
                track.play();
                while (tonoSostenido) {
                    int escrito = 0;
                    while (escrito < buffer.length && tonoSostenido) {
                        int n = track.write(buffer, escrito, buffer.length - escrito);
                        if (n <= 0) { tonoSostenido = false; break; }
                        escrito += n;
                    }
                }
            } catch (Exception e) {
                Bitacora.log("Diagnóstico: tono sostenido interrumpido: " + e.getMessage());
            } finally {
                try { track.stop(); } catch (Exception ignorado) {}
                track.release();
            }
        }, "tono-sostenido");
        hiloTono.start();
    }

    private void pararTonoSostenido() {
        if (!tonoSostenido && hiloTono == null) { Bitacora.log("Diagnóstico: no había tono sostenido."); return; }
        tonoSostenido = false;
        if (hiloTono != null) {
            try { hiloTono.join(500); } catch (InterruptedException ignorado) { Thread.currentThread().interrupt(); }
            hiloTono = null;
        }
        Bitacora.log("Diagnóstico: tono sostenido detenido.");
    }

    private static byte[] tonoLoop(double hz, int rate) {
        int muestras = rate; // 1 s exacto
        byte[] pcm = new byte[muestras * 2];
        double paso = 2 * Math.PI * hz / rate;
        for (int i = 0; i < muestras; i++) {
            short v = (short) (Math.sin(paso * i) * 12000);
            pcm[i * 2] = (byte) v;
            pcm[i * 2 + 1] = (byte) (v >> 8);
        }
        return pcm;
    }

    private void diagTts() {
        final String frase = "Prueba de Isabella: uno, dos, tres.";
        Bitacora.log("Diagnóstico: voz del sistema con uso " + nombreUso(usoAudio) + ".");
        final int uso = usoAudio;
        if (tts != null) { tts.shutdown(); tts = null; }
        tts = new TextToSpeech(this, estado -> {
            if (estado != TextToSpeech.SUCCESS) {
                Bitacora.log("La voz del sistema no está disponible (estado " + estado + ").");
                return;
            }
            if (tts == null) return;
            int disp = tts.setLanguage(new Locale("es", "MX"));
            if (disp == TextToSpeech.LANG_MISSING_DATA || disp == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(new Locale("es", "ES"));
            }
            tts.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(uso)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            tts.speak(frase, TextToSpeech.QUEUE_FLUSH, null, "diag");
            Bitacora.log("Diagnóstico: hablando «" + frase + "». ¿El interlocutor la oyó?");
        });
    }

    private void diagGrabar() {
        boolean reanudar = capturando;
        if (reanudar) detenerCaptura();
        int fuente = fuenteMic;
        String nombre = "mic_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                + "_" + nombreFuente(fuente) + ".wav";
        File destino = new File(getExternalFilesDir(null), nombre);
        Bitacora.log("Diagnóstico: grabando 5 s del micrófono (" + nombreFuente(fuente) + ") → " + nombre
                + ("active".equals(estadoLlamada) ? "" : " · OJO: no hay llamada activa"));
        FuenteAudio rec = null;
        Wav wav = null;
        try {
            rec = construirGrabadora(fuente, RATE_MIC);
            if (rec == null || rec.getState() != FuenteAudio.ESTADO_LISTO) {
                Bitacora.log("Diagnóstico: no se pudo abrir el micrófono.");
                return;
            }
            wav = new Wav(destino.getAbsolutePath(), RATE_MIC);
            rec.startRecording();
            byte[] buf = new byte[TRAMA_MIC_BYTES];
            long fin = System.currentTimeMillis() + 5000;
            double sumaRms = 0, maxRms = 0;
            int bloques = 0;
            boolean silenciado = false;
            while (System.currentTimeMillis() < fin) {
                int n = rec.read(buf, 0, buf.length);
                if (n <= 0) continue;
                wav.escribir(buf, n);
                double r = Wav.rms(buf, n);
                sumaRms += r;
                maxRms = Math.max(maxRms, r);
                bloques++;
                if (bloques == 10) silenciado = rec.isClientSilenced();
            }
            double media = bloques > 0 ? sumaRms / bloques : 0;
            Bitacora.log(String.format(Locale.US, "Diagnóstico: grabados %d KB · RMS medio %.0f · pico %.0f · %s",
                    wav.bytesPcm() / 1024, media, maxRms, silenciado ? "Android SILENCIÓ el micrófono" : "micrófono con señal"));
            Bitacora.log("Baja el WAV: adb pull " + destino.getAbsolutePath());
        } catch (Exception e) {
            Bitacora.log("Diagnóstico: error al grabar: " + e.getMessage());
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignorado) {}
                rec.release();
            }
            if (wav != null) {
                try { wav.cerrar(); } catch (Exception ignorado) {}
            }
            if (reanudar && "active".equals(estadoLlamada)) iniciarCaptura();
        }
    }

    private void reproducirBloqueante(byte[] pcm, int rate, int uso) {
        SumideroAudio track = construirReproductor(uso, rate);
        if (track == null || track.getState() != SumideroAudio.ESTADO_LISTO) {
            Bitacora.log("Diagnóstico: no se pudo abrir el reproductor del tono.");
            return;
        }
        try {
            track.play();
            int escrito = 0;
            while (escrito < pcm.length) {
                int n = track.write(pcm, escrito, pcm.length - escrito);
                if (n <= 0) break;
                escrito += n;
            }
            Thread.sleep(250);
        } catch (InterruptedException ignorado) {
            Thread.currentThread().interrupt();
        } finally {
            try { track.stop(); } catch (Exception ignorado) {}
            track.release();
        }
    }

    private static byte[] tonoSenoidal(double hz, double segundos, int rate) {
        int muestras = (int) (segundos * rate);
        byte[] pcm = new byte[muestras * 2];
        double paso = 2 * Math.PI * hz / rate;
        int r = rate / 100;
        for (int i = 0; i < muestras; i++) {
            double rampa = 1.0;
            if (i < r) rampa = (double) i / r;
            else if (i > muestras - r) rampa = (double) (muestras - i) / r;
            short v = (short) (Math.sin(paso * i) * 24000 * rampa);
            pcm[i * 2] = (byte) v;
            pcm[i * 2 + 1] = (byte) (v >> 8);
        }
        return pcm;
    }

    // --- Estado de la llamada (TelephonyCallback) ----------------------------

    private void registrarCallback() {
        if (callback != null) return;
        if (!tienePermiso(Manifest.permission.READ_PHONE_STATE)) {
            Bitacora.log("Sin READ_PHONE_STATE no se puede seguir el estado de la llamada (fusible a).");
            return;
        }
        callback = new EstadoLlamada();
        try {
            telefonia.registerTelephonyCallback(hiloTelefonia, callback);
        } catch (SecurityException e) {
            Bitacora.log("No se pudo registrar el seguimiento del estado: " + e.getMessage());
            callback = null;
        }
    }

    private void quitarCallback() {
        if (callback != null) {
            try { telefonia.unregisterTelephonyCallback(callback); } catch (Exception ignorado) {}
            callback = null;
        }
    }

    private final class EstadoLlamada extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override
        public void onCallStateChanged(int estado) {
            switch (estado) {
                case TelephonyManager.CALL_STATE_OFFHOOK:
                    if (tOffhookMs == 0) tOffhookMs = System.currentTimeMillis();
                    vioOffhook = true;
                    cancelar(marcadoWatchdog);
                    marcadoWatchdog = null;
                    marcarActiva();
                    break;
                case TelephonyManager.CALL_STATE_IDLE:
                    if (vioOffhook && ("active".equals(estadoLlamada) || "dialing".equals(estadoLlamada))) {
                        Bitacora.log("Fusible a: la línea pasó a IDLE; cerrando Gemini y colgando.");
                        terminar("la línea quedó libre (colgaron)"); // fusible a
                    }
                    break;
                default:
                    // RINGING entrante: no nos toca
            }
        }
    }

    // --- Candados ------------------------------------------------------------

    private void adquirirCandados() {
        try {
            if (!wake.isHeld()) wake.acquire(30 * 60 * 1000L);
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Exception e) {
            Bitacora.log("No se pudieron tomar los candados de energía/Wi-Fi: " + e.getMessage());
        }
    }

    private void soltarCandados() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignorado) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignorado) {}
    }

    // --- Notificación --------------------------------------------------------

    private Notification notificacion(String titulo, String texto) {
        PendingIntent abrir = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_isabella_noti)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setContentIntent(abrir)
                .setOngoing(true)
                .build();
    }

    private void actualizarNotificacion(String titulo, String texto) {
        getSystemService(NotificationManager.class).notify(ID_NOTIFICACION, notificacion(titulo, texto));
    }

    // --- Utilidades ----------------------------------------------------------

    static final int RATE_MIC = 16000, RATE_VOZ = 24000, TRAMA_MIC_BYTES = 640;

    private boolean tienePermiso(String permiso) {
        return checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hayLlamada() {
        LlamadaService s = activo;
        return s != null && ("active".equals(s.estadoLlamada) || "dialing".equals(s.estadoLlamada));
    }

    private static String valor(String s) {
        return s == null ? "" : s.trim();
    }

    private static int parseInt(String s, int porDefecto) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return porDefecto;
        }
    }

    static int fuenteDe(String s) {
        if (s == null) return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        switch (s.toLowerCase(Locale.US)) {
            case "mic": return MediaRecorder.AudioSource.MIC;
            case "reconocimiento":
            case "vr": return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            default: return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        }
    }

    static String nombreFuente(int fuente) {
        if (fuente == MediaRecorder.AudioSource.MIC) return "mic";
        if (fuente == MediaRecorder.AudioSource.VOICE_RECOGNITION) return "reconocimiento";
        return "comunicacion";
    }

    static int usoDe(String s) {
        if (s == null) return AudioAttributes.USAGE_VOICE_COMMUNICATION;
        switch (s.toLowerCase(Locale.US)) {
            case "media": return AudioAttributes.USAGE_MEDIA;
            case "asistente": return AudioAttributes.USAGE_ASSISTANT;
            default: return AudioAttributes.USAGE_VOICE_COMMUNICATION;
        }
    }

    static String nombreUso(int uso) {
        if (uso == AudioAttributes.USAGE_MEDIA) return "media";
        if (uso == AudioAttributes.USAGE_ASSISTANT) return "asistente";
        return "voz";
    }

    private static String nombreTipoDispositivo(int tipo) {
        switch (tipo) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "altavoz";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "auricular";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE: return "altavoz (seguro)";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC: return "micrófono del cel";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "audífonos con cable (con micrófono)";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "audífonos con cable (sin micrófono)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "Bluetooth manos libres (SCO)";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "Bluetooth A2DP";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "Bluetooth LE (audífonos)";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "audífonos USB";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "dispositivo USB";
            default: return "tipo " + tipo;
        }
    }

    /** 2.6: el dispositivo por el que Android enruta un AudioTrack o AudioRecord (null hasta que arranca la ruta). */
    private static String nombreDispositivo(AudioDeviceInfo d) {
        if (d == null) return "ruta todavía no asignada";
        CharSequence producto = d.getProductName();
        return nombreTipoDispositivo(d.getType()) + (producto != null && producto.length() > 0 ? " «" + producto + "»" : "");
    }
}
