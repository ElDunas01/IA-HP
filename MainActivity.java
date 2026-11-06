// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Pantalla de «Isabella Llamadas» (v2: la app habla directo con Gemini Live desde el cel).
 * Número, token/Worker, techo de minutos, selectores de audio, modo del preámbulo de Isabella, y los modos:
 * llamar, solo marcar e «Isabella sin llamada». Abajo, la bitácora en vivo (la misma que va a logcat).
 *
 * Contra toques fantasma (2.4): «Llamar con Isabella» y «Solo marcar» solo ARMAN la orden por 3 s y muestran el
 * número; marca el botón «Sí, …» que aparece más abajo (otro punto de la pantalla). Ver Confirmacion.
 *
 * Grabar (2.5): el interruptor «Grabar esta llamada» arranca apagado, no se guarda y se apaga después de cada orden;
 * vale para «Llamar con Isabella» e «Isabella sin llamada». Cambiarlo cancela una confirmación armada.
 *
 * Sin bocina (2.6): el interruptor «Sin bocina» arranca apagado y no se guarda; prendido, la orden sale con `bocina 0`
 * y la app no pide la bocina: la ruta la deja Telecom. Se queda como esté entre órdenes (no es de consentimiento);
 * cambiarlo cancela una confirmación armada.
 */
public class MainActivity extends Activity {
    static final int FONDO = 0xFF0E0F14, TARJETA = 0xFF1A1C24, TEXTO = 0xFFECECF1, SUAVE = 0xFF9A9CAB,
            ACENTO = 0xFFFF5D8F, COLGAR = 0xFFE5484D, OK = 0xFF36B37E, TARJETA_BOTON = 0xFF2A2D3A;

    private static final String[] PERMISOS = {
            Manifest.permission.CALL_PHONE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.POST_NOTIFICATIONS,
    };

    private static final String[] FUENTES = {"comunicacion", "mic", "reconocimiento"};
    private static final String[] USOS = {"voz", "media", "asistente"};

    private EditText numero, token, worker, maxmin;
    private TextView bitacora, estadoPermisos;
    private ScrollView scrollBitacora;
    private Button btnFuente, btnUso, btnConfirmar;
    private LinearLayout panelDiag;
    private RadioGroup selModo;
    private CheckBox chkGrabar, chkSinBocina, chkRaiz;
    private EditText gananciaTts, preenfasis, ditherTts, softclipKnee, prefillMs, hpfHz;
    private LinearLayout panelAvanzado;
    private final int[] idsModo = new int[Ajustes.MODOS.length];

    private int iFuente = 0, iUso = 0;

    // Confirmación contra toques fantasma: la orden armada, el número que se mostró y la cuenta regresiva.
    private final Confirmacion confirmacion = new Confirmacion();
    private String numeroArmado = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable tic = this::pintarConfirmar;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        getWindow().setStatusBarColor(FONDO);
        getWindow().setNavigationBarColor(FONDO);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setPadding(dp(18), dp(24), dp(18), dp(28));

        texto(raiz, "Isabella Llamadas", 28, TEXTO).setTypeface(Typeface.DEFAULT_BOLD);
        texto(raiz, "El cel habla directo con Isabella (Gemini Live), sin la Mac", 14, SUAVE);
        texto(raiz, Huella.CREDITO, 12, SUAVE);

        // --- Llamada ---
        LinearLayout t1 = tarjeta(raiz, "Llamada");
        numero = campo(t1, "Número (tuyo o de alguien que aceptó)", "", InputType.TYPE_CLASS_PHONE);
        LinearLayout fila = filaHorizontal(t1);
        maxmin = campoEnFila(fila, "Techo (min)", "3", InputType.TYPE_CLASS_NUMBER, 1);
        LinearLayout filaSel = colEnFila(fila, 2);
        btnFuente = botonTenue(filaSel, "Mic: " + FUENTES[iFuente], v -> {
            iFuente = (iFuente + 1) % FUENTES.length;
            btnFuente.setText("Mic: " + FUENTES[iFuente]);
        });
        btnUso = botonTenue(filaSel, "Salida: " + USOS[iUso], v -> {
            iUso = (iUso + 1) % USOS.length;
            btnUso.setText("Salida: " + USOS[iUso]);
        });

        // Modo del preámbulo que acuña el Worker (servicios | isabella | flexible). Arranca en el que guardó `config`;
        // lo de aquí vale para la orden que salga de esta pantalla (no se guarda).
        texto(t1, "Modo de Isabella", 12, SUAVE);
        selModo = new RadioGroup(this);
        selModo.setOrientation(RadioGroup.HORIZONTAL);
        String guardado = Ajustes.modo(this);
        for (int k = 0; k < Ajustes.MODOS.length; k++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(Ajustes.MODOS[k]);
            rb.setTextColor(TEXTO);
            rb.setButtonTintList(ColorStateList.valueOf(ACENTO));
            idsModo[k] = View.generateViewId();
            rb.setId(idsModo[k]);
            rb.setPadding(0, 0, dp(14), 0);
            selModo.addView(rb);
            if (Ajustes.MODOS[k].equals(guardado)) selModo.check(idsModo[k]);
        }
        t1.addView(selModo);

        // Grabar (2.5): solo para la orden que sale de aquí; arranca apagado, no se guarda y se apaga al enviar.
        chkGrabar = new CheckBox(this);
        chkGrabar.setText("Grabar esta llamada (Isabella avisa que se graba)");
        chkGrabar.setTextColor(TEXTO);
        chkGrabar.setButtonTintList(ColorStateList.valueOf(ACENTO));
        chkGrabar.setChecked(false);
        chkGrabar.setSaveEnabled(false); // ni al girar la pantalla se restaura
        chkGrabar.setOnCheckedChangeListener((b, encendido) ->
                cancelarConfirmacion("Cambiaste «Grabar»: la confirmación se cancela; no marqué. Vuelve a empezar."));
        t1.addView(chkGrabar);

        // Sin bocina (2.6): la orden sale con bocina 0 y la app no pide la bocina (la ruta la deja Telecom: auricular,
        // audífonos o Bluetooth). Arranca apagado, no se guarda y se queda como esté entre órdenes.
        chkSinBocina = new CheckBox(this);
        chkSinBocina.setText("Sin bocina: dejar la ruta como la ponga el cel (auricular, audífonos o Bluetooth)");
        chkSinBocina.setTextColor(TEXTO);
        chkSinBocina.setButtonTintList(ColorStateList.valueOf(ACENTO));
        chkSinBocina.setChecked(false);
        chkSinBocina.setSaveEnabled(false);
        chkSinBocina.setOnCheckedChangeListener((b, encendido) ->
                cancelarConfirmacion("Cambiaste «Sin bocina»: la confirmación se cancela; no marqué. Vuelve a empezar."));
        t1.addView(chkSinBocina);

        // 2.7 (RAÍZ): opt-in que enciende la ruta root (SumideroRaiz tinyplay MM2 + FuenteRaiz tinycap MM9) para que el
        // audio sí entre al modem; apagado = ruta clásica (AudioTrack bocina + AudioRecord del mic Android), como antes.
        // A diferencia de «Grabar» y «Sin bocina», ESTE SÍ persiste entre sesiones para que David lo deje puesto y marque
        // desde la UI. Equivale al --es raiz 1 que llega por adb. El sondeo real (su + binarios + mixers I-3) lo hace
        // RaizUtil dentro de LlamadaService; si falla, se cae a la ruta nativa (lo registra la bitácora).
        final SharedPreferences prefsUi = getSharedPreferences(Tokens.PREFS, MODE_PRIVATE);
        chkRaiz = new CheckBox(this);
        chkRaiz.setText("Modo RAÍZ (ruta root VoLTE: tinyplay/tinycap vía su)");
        chkRaiz.setTextColor(TEXTO);
        chkRaiz.setButtonTintList(ColorStateList.valueOf(ACENTO));
        chkRaiz.setChecked(prefsUi.getBoolean("ui_raiz_default", false));
        chkRaiz.setSaveEnabled(false); // la persistencia la lleva SharedPreferences, no el restore de la vista
        chkRaiz.setOnCheckedChangeListener((b, encendido) -> {
            prefsUi.edit().putBoolean("ui_raiz_default", encendido).apply();
            cancelarConfirmacion("Cambiaste «Modo RAÍZ»: la confirmación se cancela; no marqué. Vuelve a empezar.");
        });
        t1.addView(chkRaiz);

        // v7.3: palanca directa de volumen de la voz de Isabella en ruta RAÍZ. Default 30 (×3.0) =
        // v6-exacto; subir a 45-60 (×4.5-×6.0) cuando el A55 reporta que suena bajito. Clamp del
        // servicio: 10..50; tanh protege picos. Persiste en Tokens.PREFS (ui_ganancia_tts) y se
        // manda en cada orden como --es ganancia_tts N. No cancela la confirmación (es un número que
        // David puede estar ajustando mientras arma la orden).
        int gananciaDef = prefsUi.getInt("ui_ganancia_tts", 30);
        gananciaTts = campo(t1, "Volumen TTS (décimas; 10=×1.0 … 30=×3.0 default … 50=×5.0; sube a 45-60 si suena bajito)",
                String.valueOf(gananciaDef), InputType.TYPE_CLASS_NUMBER);

        // v7.3: bloque "Audio avanzado" oculto por default. Expone las palancas v7 que hoy solo
        // llegaban por --es desde adb (pre-énfasis, dither, soft-clip knee, prefill). Todas tienen
        // default v6-exacto; mientras no se toquen, el Intent sale idéntico a v7.2. Patrón de
        // panelDiag (línea 190): botón que alterna visibilidad.
        boton(t1, "Audio avanzado (opt-in; solo ruta RAÍZ)", TARJETA_BOTON, v -> {
            boolean ver = panelAvanzado.getVisibility() != View.VISIBLE;
            panelAvanzado.setVisibility(ver ? View.VISIBLE : View.GONE);
        });
        panelAvanzado = new LinearLayout(this);
        panelAvanzado.setOrientation(LinearLayout.VERTICAL);
        panelAvanzado.setVisibility(View.GONE);
        t1.addView(panelAvanzado);
        int preenDef = prefsUi.getInt("ui_preenfasis", 0);
        preenfasis = campo(panelAvanzado, "Pre-énfasis α×100 (0..30; 0=off default v6; 12=α=0.12 compensa rolloff AMR-WB)",
                String.valueOf(preenDef), InputType.TYPE_CLASS_NUMBER);
        int dithDef = prefsUi.getInt("ui_dither_tts", 0);
        ditherTts = campo(panelAvanzado, "Dither TTS ±int16 (0..8; 0=cero puro v6; 2=anti-DTX ≈-84 dBFS)",
                String.valueOf(dithDef), InputType.TYPE_CLASS_NUMBER);
        int kneeDef = prefsUi.getInt("ui_softclip_knee", 10);
        softclipKnee = campo(panelAvanzado, "Soft-clip knee ×0.1 (10..20; 10=v6; 11..15 más headroom)",
                String.valueOf(kneeDef), InputType.TYPE_CLASS_NUMBER);
        int prefillDef = prefsUi.getInt("ui_prefill_ms", 500);
        prefillMs = campo(panelAvanzado, "Prefill ms (100..2000; 500=v6; 250 recorta latencia del primer saludo)",
                String.valueOf(prefillDef), InputType.TYPE_CLASS_NUMBER);
        // v7.4: HPF 1er orden pre-pipe. Default 0=off (v7.3 bit-a-bit). 80..120 Hz típico para
        // quitar rumble sub-grave que roba headroom al tanh cuando ganancia_tts es alta.
        int hpfDef = prefsUi.getInt("ui_hpf_hz", 0);
        hpfHz = campo(panelAvanzado, "HPF Hz (0..200; 0=off default v7.3; 80-120 quita rumble sub-grave y libera headroom al tanh)",
                String.valueOf(hpfDef), InputType.TYPE_CLASS_NUMBER);

        boton(t1, "Llamar con Isabella", ACENTO, v -> tocarMarcar("llamar"));
        boton(t1, "Solo marcar (sin Isabella)", TARJETA_BOTON, v -> tocarMarcar("solo_marcar"));
        boton(t1, "Isabella sin llamada (prueba)", TARJETA_BOTON, v -> pedirYEnviar("isabella"));
        boton(t1, "Colgar", COLGAR, v -> {
            cancelarConfirmacion(null);
            enviar("colgar");
        });
        // Segundo paso de la confirmación: aparece aquí abajo, lejos de los botones que la arman.
        btnConfirmar = boton(t1, "", OK, v -> confirmarMarcar());
        btnConfirmar.setVisibility(View.GONE);

        // --- Token ---
        LinearLayout tt = tarjeta(raiz, "Token de Isabella (la llave nunca va al cel)");
        token = campo(tt, "Token efímero (se pega o llega por adb; no se guarda)", "", InputType.TYPE_CLASS_TEXT);
        worker = campo(tt, "URL del Worker (opcional; acuña el token con el secreto del dispositivo)", "", InputType.TYPE_CLASS_TEXT);

        // --- Diagnóstico ---
        LinearLayout t2 = tarjeta(raiz, "Diagnóstico (para la matriz del audio en llamada)");
        texto(t2, "Durante una llamada activa: ¿el interlocutor me oye?, ¿yo grabo lo que sale del altavoz?", 12, SUAVE);
        boton(t2, "Abrir diagnóstico", TARJETA_BOTON, v -> {
            boolean ver = panelDiag.getVisibility() != View.VISIBLE;
            panelDiag.setVisibility(ver ? View.VISIBLE : View.GONE);
        });
        panelDiag = new LinearLayout(this);
        panelDiag.setOrientation(LinearLayout.VERTICAL);
        panelDiag.setVisibility(View.GONE);
        t2.addView(panelDiag);
        boton(panelDiag, "Tono de 1 kHz (2 s)", TARJETA_BOTON, v -> enviar("tono"));
        boton(panelDiag, "Voz de prueba (uno, dos, tres)", TARJETA_BOTON, v -> enviar("tts"));
        boton(panelDiag, "Grabar 5 s del micrófono", TARJETA_BOTON, v -> enviar("grabar"));

        // --- Bitácora ---
        LinearLayout t3 = tarjeta(raiz, "Bitácora");
        estadoPermisos = texto(t3, "", 12, SUAVE);
        bitacora = texto(t3, "Listo. Pon el token y toca «Isabella sin llamada» para probar la voz antes de marcar.", 12, TEXTO);
        bitacora.setTypeface(Typeface.MONOSPACE);
        scrollBitacora = new ScrollView(this);
        t3.removeView(bitacora); // texto() ya lo agregó a t3; aquí va dentro del ScrollView
        scrollBitacora.addView(bitacora);
        LinearLayout.LayoutParams lpB = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(260));
        lpB.topMargin = dp(8);
        t3.addView(scrollBitacora, lpB);
        boton(t3, "Limpiar pantalla", TARJETA_BOTON, v -> bitacora.setText(""));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(FONDO);
        scroll.addView(raiz);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bitacora.escuchar(this::pintarBitacora);
        pintarBitacora();
        mostrarPermisos();
    }

    @Override
    protected void onPause() {
        cancelarConfirmacion("La pantalla se ocultó: la confirmación se cancela; no marqué.");
        Bitacora.dejarDeEscuchar();
        super.onPause();
    }

    // --- Confirmación contra toques fantasma -----------------------------------

    /** Primer paso: arma la orden 3 s y muestra el número; un segundo toque en el mismo botón cancela. */
    private void tocarMarcar(String orden) {
        String num = valor(numero, "");
        if (num.replaceAll("[^0-9+]", "").isEmpty()) {
            Bitacora.log("Escribe primero el número (tuyo o de alguien que aceptó la llamada). No marqué.");
            return;
        }
        boolean armado = confirmacion.tocarBoton(orden, SystemClock.uptimeMillis());
        if (!armado) {
            cancelarConfirmacion("Segundo toque en el mismo botón: cancelado, no marqué (contra toques fantasma).");
            return;
        }
        numeroArmado = num;
        Bitacora.log("Toque en «" + nombreOrden(orden) + "»: para marcar al " + Confirmacion.numeroLegible(num)
                + (grabaLaOrden(orden) ? " y GRABAR" : "") + (chkSinBocina.isChecked() ? " SIN BOCINA" : "")
                + " toca «Sí» abajo antes de 3 s (contra toques fantasma).");
        ui.removeCallbacks(tic);
        pintarConfirmar();
    }

    /** Segundo paso: «Sí, …». Marca solo si la orden sigue vigente y el número es el mismo que se mostró. */
    private void confirmarMarcar() {
        String orden = confirmacion.confirmar(SystemClock.uptimeMillis());
        ui.removeCallbacks(tic);
        btnConfirmar.setVisibility(View.GONE);
        if (orden == null) {
            Bitacora.log("La confirmación ya había vencido: no marqué.");
            return;
        }
        if (!numeroArmado.equals(valor(numero, ""))) {
            Bitacora.log("El número cambió desde el primer toque: no marqué. Vuelve a empezar.");
            return;
        }
        Bitacora.log("Confirmado: " + nombreOrden(orden) + " al " + Confirmacion.numeroLegible(numeroArmado) + ".");
        pedirYEnviar(orden);
    }

    /** Pinta «Sí, … (N s)» cada segundo; al vencer, lo esconde y lo anota. */
    private void pintarConfirmar() {
        long ahora = SystemClock.uptimeMillis();
        String orden = confirmacion.armado(ahora);
        if (orden == null) {
            if (btnConfirmar.getVisibility() == View.VISIBLE) {
                btnConfirmar.setVisibility(View.GONE);
                Bitacora.log("Confirmación vencida: no marqué.");
            }
            return;
        }
        long resta = confirmacion.restaMs(ahora);
        btnConfirmar.setText("Sí, " + ("llamar".equals(orden) ? "llamar con Isabella" : "solo marcar")
                + (grabaLaOrden(orden) ? " y GRABAR" : "") + (chkSinBocina.isChecked() ? " sin bocina" : "") + " al "
                + Confirmacion.numeroLegible(numeroArmado) + " (" + ((resta + 999) / 1000) + " s)");
        btnConfirmar.setVisibility(View.VISIBLE);
        ui.postDelayed(tic, Math.min(1000, resta + 50));
    }

    private void cancelarConfirmacion(String motivo) {
        boolean habia = confirmacion.armado(SystemClock.uptimeMillis()) != null || btnConfirmar.getVisibility() == View.VISIBLE;
        confirmacion.cancelar();
        ui.removeCallbacks(tic);
        btnConfirmar.setVisibility(View.GONE);
        if (habia && motivo != null) Bitacora.log(motivo);
    }

    /** ¿Esta orden sale con «grabar»? Solo «llamar» e «isabella»: solo marcar no tiene sesión ni captura. */
    private boolean grabaLaOrden(String cmd) {
        return chkGrabar.isChecked() && ("llamar".equals(cmd) || "isabella".equals(cmd));
    }

    private static String nombreOrden(String orden) {
        return "llamar".equals(orden) ? "Llamar con Isabella" : "Solo marcar";
    }

    private String modoElegido() {
        int id = selModo.getCheckedRadioButtonId();
        for (int k = 0; k < idsModo.length; k++) if (idsModo[k] == id) return Ajustes.MODOS[k];
        return Ajustes.modo(this);
    }

    /** Revisa permisos y, si están, manda el comando; si no, los pide y pide reintentar. */
    private void pedirYEnviar(String cmd) {
        List<String> faltan = new ArrayList<>();
        // «Isabella sin llamada» no necesita CALL_PHONE ni READ_PHONE_STATE.
        for (String p : PERMISOS) {
            boolean deLlamada = p.equals(Manifest.permission.CALL_PHONE)
                    || p.equals(Manifest.permission.READ_PHONE_STATE)
                    || p.equals(Manifest.permission.ANSWER_PHONE_CALLS);
            if ("isabella".equals(cmd) && deLlamada) continue;
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) faltan.add(p);
        }
        if (!faltan.isEmpty()) {
            Bitacora.log("Pidiendo permisos: " + faltan.size() + " pendientes. Acepta y vuelve a tocar el botón.");
            requestPermissions(faltan.toArray(new String[0]), 1);
            return;
        }
        enviar(cmd);
    }

    /** Arma el intent de control y arranca el servicio (la Activity está visible: el FGS de micrófono es válido). */
    private void enviar(String cmd) {
        Intent i = new Intent(this, LlamadaService.class);
        i.putExtra(LlamadaService.EXTRA_CMD, cmd);
        if ("llamar".equals(cmd) || "solo_marcar".equals(cmd) || "isabella".equals(cmd)) {
            i.putExtra(LlamadaService.EXTRA_NUMERO, valor(numero, ""));
            i.putExtra(LlamadaService.EXTRA_MAXMIN, valor(maxmin, "3"));
            i.putExtra(LlamadaService.EXTRA_FUENTE, FUENTES[iFuente]);
            i.putExtra(LlamadaService.EXTRA_USO, USOS[iUso]);
            i.putExtra(LlamadaService.EXTRA_MODO, modoElegido());
            String t = valor(token, "");
            String w = valor(worker, "");
            if (!t.isEmpty()) i.putExtra(LlamadaService.EXTRA_TOKEN, t);
            if (!w.isEmpty()) i.putExtra(LlamadaService.EXTRA_WORKER, w);
            if (grabaLaOrden(cmd)) i.putExtra(LlamadaService.EXTRA_GRABAR, "1");
            if (chkSinBocina.isChecked()) i.putExtra(LlamadaService.EXTRA_BOCINA, "0"); // 2.6: solo esta orden
            if (chkRaiz.isChecked()) i.putExtra(LlamadaService.EXTRA_RAIZ, "1");         // 2.7: ruta root (I-1/I-2/I-3), persiste en prefs
            // v7.3: palancas de calidad del TTS. Persisten en Tokens.PREFS para que David las
            // ajuste una vez y se queden; cada orden las lee y las manda al servicio. Ganancia_tts
            // va SIEMPRE (default 30 = v6-exacto, así David ve el valor en la bitácora del servicio).
            // Las avanzadas sólo van si cambian el default-del-servicio, para no llenar la bitácora
            // de ruido cuando David deja la pantalla sencilla.
            SharedPreferences prefsCal = getSharedPreferences(Tokens.PREFS, MODE_PRIVATE);
            int ganancia = parseIntUi(gananciaTts, 30, 10, 50);
            int preen    = parseIntUi(preenfasis, 0, 0, 30);
            int dith     = parseIntUi(ditherTts, 0, 0, 8);
            int knee     = parseIntUi(softclipKnee, 10, 10, 20);
            int prefill  = parseIntUi(prefillMs, 500, 100, 2000);
            int hpf      = parseIntUi(hpfHz, 0, 0, 200);
            prefsCal.edit()
                    .putInt("ui_ganancia_tts", ganancia)
                    .putInt("ui_preenfasis", preen)
                    .putInt("ui_dither_tts", dith)
                    .putInt("ui_softclip_knee", knee)
                    .putInt("ui_prefill_ms", prefill)
                    .putInt("ui_hpf_hz", hpf)
                    .apply();
            i.putExtra(LlamadaService.EXTRA_GANANCIA_TTS, String.valueOf(ganancia));
            if (preen    != 0)   i.putExtra(LlamadaService.EXTRA_PREENFASIS,    String.valueOf(preen));
            if (dith     != 0)   i.putExtra(LlamadaService.EXTRA_DITHER_TTS,    String.valueOf(dith));
            if (knee     != 10)  i.putExtra(LlamadaService.EXTRA_SOFTCLIP_KNEE, String.valueOf(knee));
            if (prefill  != 500) i.putExtra(LlamadaService.EXTRA_PREFILL_MS,    String.valueOf(prefill));
            if (hpf      != 0)   i.putExtra(LlamadaService.EXTRA_HPF_HZ,        String.valueOf(hpf));
        } else if ("grabar".equals(cmd)) {
            i.putExtra(LlamadaService.EXTRA_FUENTE, FUENTES[iFuente]);
        }
        startForegroundService(i);
        // 2.5: «Grabar» vale para UNA orden: se apaga al salir cualquier llamada o sesión.
        if ("llamar".equals(cmd) || "solo_marcar".equals(cmd) || "isabella".equals(cmd)) chkGrabar.setChecked(false);
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        super.onRequestPermissionsResult(codigo, permisos, resultados);
        mostrarPermisos();
        boolean todos = true;
        for (int r : resultados) if (r != PackageManager.PERMISSION_GRANTED) todos = false;
        Bitacora.log(todos ? "Permisos concedidos. Vuelve a tocar el botón." : "Faltan permisos; algunos se dan desde Ajustes.");
    }

    private void mostrarPermisos() {
        List<String> faltan = new ArrayList<>();
        for (String p : PERMISOS) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) faltan.add(p.substring(p.lastIndexOf('.') + 1));
        }
        if (faltan.isEmpty()) {
            estadoPermisos.setText("Permisos: todos concedidos.");
            estadoPermisos.setTextColor(OK);
        } else {
            estadoPermisos.setText("Permisos pendientes: " + String.join(", ", faltan));
            estadoPermisos.setTextColor(COLGAR);
        }
    }

    private void pintarBitacora() {
        bitacora.setText(Bitacora.texto());
        scrollBitacora.post(() -> scrollBitacora.fullScroll(View.FOCUS_DOWN));
    }

    // --- Construcción de vistas ---------------------------------------------

    private LinearLayout tarjeta(LinearLayout padre, String titulo) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(TARJETA);
        fondo.setCornerRadius(dp(18));
        t.setBackground(fondo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        padre.addView(t, lp);
        texto(t, titulo, 13, ACENTO).setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private LinearLayout filaHorizontal(LinearLayout padre) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        padre.addView(f, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return f;
    }

    private LinearLayout colEnFila(LinearLayout fila, int peso) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, peso);
        lp.leftMargin = dp(6);
        fila.addView(col, lp);
        return col;
    }

    private TextView texto(LinearLayout padre, String contenido, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(contenido);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setLineSpacing(0, 1.15f);
        v.setPadding(0, dp(4), 0, dp(4));
        padre.addView(v);
        return v;
    }

    private EditText campo(LinearLayout padre, String etiqueta, String porDefecto, int tipo) {
        texto(padre, etiqueta, 12, SUAVE);
        EditText e = new EditText(this);
        e.setText(porDefecto);
        e.setInputType(tipo);
        e.setTextColor(TEXTO);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(0xFF12131A);
        fondo.setCornerRadius(dp(10));
        fondo.setStroke(dp(1), 0xFF333646);
        e.setBackground(fondo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(6);
        padre.addView(e, lp);
        return e;
    }

    private EditText campoEnFila(LinearLayout fila, String etiqueta, String porDefecto, int tipo, int peso) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, peso);
        fila.addView(col, lp);
        return campo(col, etiqueta, porDefecto, tipo);
    }

    private Button boton(LinearLayout padre, String etiqueta, int color, View.OnClickListener accion) {
        Button b = new Button(this);
        b.setText(etiqueta);
        b.setAllCaps(false);
        b.setTextColor(color == TARJETA_BOTON ? TEXTO : 0xFF0E0F14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(color);
        fondo.setCornerRadius(dp(14));
        b.setBackground(fondo);
        b.setOnClickListener(accion);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        padre.addView(b, lp);
        return b;
    }

    private Button botonTenue(LinearLayout padre, String etiqueta, View.OnClickListener accion) {
        Button b = new Button(this);
        b.setText(etiqueta);
        b.setAllCaps(false);
        b.setTextColor(TEXTO);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setGravity(Gravity.CENTER);
        GradientDrawable fondo = new GradientDrawable();
        fondo.setColor(Color.TRANSPARENT);
        fondo.setCornerRadius(dp(12));
        fondo.setStroke(dp(1), 0xFF3A3D4C);
        b.setBackground(fondo);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setOnClickListener(accion);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(8);
        padre.addView(b, lp);
        return b;
    }

    private String valor(EditText e, String porDefecto) {
        String s = e.getText().toString().trim();
        return s.isEmpty() ? porDefecto : s;
    }

    /** v7.3: lee un entero del EditText con clamp a [min, max]; input vacío o malformado cae al default. */
    private int parseIntUi(EditText e, int def, int min, int max) {
        if (e == null) return def;
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            if (v < min) v = min;
            if (v > max) v = max;
            return v;
        } catch (Exception ex) {
            return def;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
