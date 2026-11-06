// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
package com.viajatech.lab.isabellallamadas;

import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;

/**
 * InCallService OPCIONAL y NO visual (meta-data android.telecom.IN_CALL_SERVICE_UI = false).
 *
 * El sistema solo liga este servicio si el appop MANAGE_ONGOING_CALLS está concedido (por adb en
 * el cel de pruebas de David). Cuando está ligado sirve para cuatro cosas que la ruta clásica no da
 * bien: empezar la conversación en Call.STATE_ACTIVE real (al contestar, no al empezar a marcar),
 * el tope de timbre (DIALING sin ACTIVE en max_timbre_s → cuelga antes del buzón), poner el altavoz
 * con setAudioRoute(ROUTE_SPEAKER) (salvo con `--es bocina 0`, 2.6: entonces no se toca la ruta y solo se
 * anota la que puso Telecom, y cada cambio) y colgar con Call.disconnect().
 *
 * Solo sigue la llamada SALIENTE de la app: con el appop, el sistema también le avisa de las entrantes
 * (o de una en espera), y esas se ignoran para no colgar ni confundir la de Isabella.
 *
 * Si el appop no está concedido, el sistema no liga esto y la app funciona igual que antes por la
 * ruta clásica de LlamadaService (OFFHOOK + altavoz al marcar). LlamadaService.EstadoLlamada
 * (TelephonyCallback) sigue como fusible de colgado independiente aunque esto reporte DISCONNECTED.
 */
public class ServicioEnLlamada extends InCallService {

    // volatile: los escribe el hilo principal (callbacks del sistema) y los lee el hilo de acción (hallazgo 4).
    private static volatile ServicioEnLlamada instancia;
    private static volatile Call llamada;

    static boolean enLlamada() {
        return instancia != null && llamada != null;
    }

    // setAudioRoute está deprecado en API 34 a favor de requestCallEndpointChange, pero sigue
    // siendo el camino directo y funcional para forzar el altavoz; se usa a propósito.
    @SuppressWarnings("deprecation")
    static void rutaAltavoz() {
        ServicioEnLlamada s = instancia;
        if (s != null) {
            try {
                s.setAudioRoute(CallAudioState.ROUTE_SPEAKER);
            } catch (Exception e) {
                Bitacora.log("InCallService: setAudioRoute falló: " + e.getMessage());
            }
        }
    }

    /** 2.6: la ruta de audio que tiene la llamada según Telecom (para la bitácora con bocina 0). */
    static String rutaActual() {
        ServicioEnLlamada s = instancia;
        if (s == null) return "InCallService no ligado";
        try {
            return describir(s.getCallAudioState());
        } catch (Exception e) {
            return "no se pudo leer (" + e.getMessage() + ")";
        }
    }

    private static String describir(CallAudioState a) {
        if (a == null) return "desconocida";
        return CallAudioState.audioRouteToString(a.getRoute())
                + (a.getActiveBluetoothDevice() != null ? " (Bluetooth activo)" : "")
                + (a.isMuted() ? " · silenciada" : "");
    }

    static void colgar() {
        Call c = llamada;
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception e) {
                Bitacora.log("InCallService: disconnect falló: " + e.getMessage());
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instancia = this;
        Bitacora.log("InCallService ligado (appop MANAGE_ONGOING_CALLS concedido).");
    }

    @Override
    public void onDestroy() {
        instancia = null;
        llamada = null;
        Bitacora.log("InCallService desligado.");
        super.onDestroy();
    }

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);
        if (esEntrante(call)) {
            Bitacora.log("InCallService: llamada entrante; no es de la app, la ignoro.");
            return;
        }
        llamada = call;
        call.registerCallback(callback);
        int estado = call.getState();
        Bitacora.log("InCallService: llamada agregada (estado " + nombreEstado(estado) + ").");
        if (estado == Call.STATE_ACTIVE) LlamadaService.alActivaReal();
        else if (estado == Call.STATE_DIALING) LlamadaService.alMarcandoReal();
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        if (call != llamada) return; // una entrante ignorada, o una que ya no se sigue
        try {
            call.unregisterCallback(callback);
        } catch (Exception ignorado) {
        }
        llamada = null;
        Bitacora.log("InCallService: llamada quitada.");
        LlamadaService.alColgadaReal("la línea se desconectó (InCallService)");
    }

    /** 2.6: cada cambio de ruta de la llamada que la app sigue queda en la bitácora (p. ej. BLUETOOTH → SPEAKER). */
    @Override
    @SuppressWarnings("deprecation")
    public void onCallAudioStateChanged(CallAudioState audioState) {
        super.onCallAudioStateChanged(audioState);
        if (llamada != null) Bitacora.log("InCallService: ruta de audio → " + describir(audioState) + ".");
    }

    private static boolean esEntrante(Call call) {
        try {
            Call.Details d = call.getDetails();
            return d != null && d.getCallDirection() == Call.Details.DIRECTION_INCOMING;
        } catch (Exception e) {
            return false;
        }
    }

    private final Call.Callback callback = new Call.Callback() {
        @Override
        public void onStateChanged(Call call, int estado) {
            Bitacora.log("InCallService: estado → " + nombreEstado(estado) + ".");
            if (estado == Call.STATE_ACTIVE) {
                LlamadaService.alActivaReal();
            } else if (estado == Call.STATE_DIALING) {
                LlamadaService.alMarcandoReal(); // marcando o timbrando: arma el tope de timbre
            } else if (estado == Call.STATE_DISCONNECTED) {
                LlamadaService.alColgadaReal("la línea se desconectó (InCallService)");
            }
        }
    };

    private static String nombreEstado(int e) {
        switch (e) {
            case Call.STATE_NEW: return "NEW";
            case Call.STATE_CONNECTING: return "CONNECTING";
            case Call.STATE_DIALING: return "DIALING";
            case Call.STATE_RINGING: return "RINGING";
            case Call.STATE_ACTIVE: return "ACTIVE";
            case Call.STATE_HOLDING: return "HOLDING";
            case Call.STATE_DISCONNECTING: return "DISCONNECTING";
            case Call.STATE_DISCONNECTED: return "DISCONNECTED";
            default: return "estado " + e;
        }
    }
}
