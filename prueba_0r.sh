#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# PRUEBA 0r «La ruta raíz contesta» (AGENTS.md, fase 7: prueba 0r).
#
# Objetivo: con el cel ya rooteado y una llamada activa a TU número de prueba, usar la ruta I-3 (mezclador
# a mano) para inyectar un WAV conocido a la subida y capturar 5 s de la bajada.
#
# Pasa si:
#   1) Oyes el tono y el chirp limpios en tu otro teléfono.
#   2) El WAV capturado de la bajada trae tu voz (banda 100-3400 Hz),
#      SIN rastro del tono de 1 kHz ni del chirp 500-2000 Hz (ésos van para arriba por la red,
#      no deberían regresar por VOC_REC_DL).
#
# Lo que NO prueba:
#   - I-1 (música en llamada vía AudioTrack + flag INCALL_MUSIC) ni I-2 (asistente de llamada
#     vía @SystemApi). Eso lo probaría el modo `raiz` de la app (pendiente, si decidimos ir
#     por una ruta más limpia que I-3).
#
# Uso:
#   herramientas/llamadas/prueba_0r.sh [--forzar] [--rate 16000] [--wav archivo.wav]
#     --forzar: salta el candado una-sola-mano (comun_llamadas.sh).
#     --rate N: sample rate del WAV y la captura (16000 por omisión).
#     --wav archivo.wav: usa un WAV existente en vez de generarlo.
#
# Pre-requisitos verificados en línea:
#   - adb ve a tu cel por USB (CEL=<serie de tu cel> si hay varios).
#   - Hay root: `su -c id` da uid=0.
#   - /vendor/bin/tinymix, tinyplay y tinycap existen.
#   - Hay una llamada activa (mCallState=2) a tu número de prueba (la marcas tú con la app de teléfono).
#
# Reglas cardinales que respeta:
#   - Contestas tú en tu otro teléfono; sin llamada activa el script espera.
#   - Nunca marca por sí mismo (los teléfonos los operas tú).
#   - Nunca ejecuta `rm` sin OK (no borra WAVs; los deja todos en los registros).
#   - Candado de una sola mano por comun_llamadas.sh (--forzar lo salta).

set -u
source "$(dirname "$0")/comun_llamadas.sh"

FORZAR=""; RATE=16000; WAV_ENTRADA=""
while [ $# -gt 0 ]; do
    case "$1" in
        --forzar) FORZAR=--forzar ;;
        --rate) RATE="${2:-16000}"; shift ;;
        --wav) WAV_ENTRADA="${2:-}"; shift ;;
        *) echo "Opción desconocida: $1" >&2; exit 2 ;;
    esac
    shift
done
case "$RATE" in ''|*[!0-9]*) echo "--rate debe ser un número" >&2; exit 2 ;; esac

hora_ms() { python3 -c 'import datetime; print(datetime.datetime.now().strftime("%H:%M:%S.%f")[:-3])'; }
alto() { echo "⛔ $*" >&2; exit 1; }
[ -n "$SERIE_A72" ] || SERIE_A72="$(elegir_celular)" || exit 1
A="adb -s $SERIE_A72"

# 1) Revisión estática y conexión (como el resto del kit).
"$LLAMADAS/revisar.sh" "$LLAMADAS/prueba_0r.sh" >/dev/null || { "$LLAMADAS/revisar.sh" "$LLAMADAS/prueba_0r.sh"; exit 1; }
"$LLAMADAS/conectar.sh" >/dev/null || { "$LLAMADAS/conectar.sh"; exit 1; }

# 2) Candado. La trampa de limpieza se irá armando por capas: primero solo el candado;
#    después del tinymix de encendido añadimos el apagado específico de ese mezclador.
candado_tomar "$SERIE_A72" prueba_0r.sh ${FORZAR} || exit 1
limpiar() { candado_soltar; }
trap 'limpiar' EXIT

D="$REGISTROS/prueba0r_$(marca_tiempo)"
mkdir -p "$D"
echo "$(hora_ms) PRUEBA 0r arrancando en $D" | tee "$D/marcas.txt"

# 3) Verificar root.
echo "→ Verifico root..." | tee -a "$D/marcas.txt"
uid="$($A shell 'su -c id 2>/dev/null' | tr -d '\r' | grep -oE 'uid=[0-9]+' | head -1)"
[ "$uid" = "uid=0" ] || alto "No hay root (su -c id → '${uid:-nada}'). Primero haz el root (AGENTS.md, fase 5)."

# 4) Verificar tinymix, tinyplay, tinycap.
for b in tinymix tinyplay tinycap; do
    ok="$($A shell "su -c 'ls /vendor/bin/$b 2>/dev/null || which $b 2>/dev/null'" | tr -d '\r')"
    [ -n "$ok" ] || alto "No existe $b en el cel. /vendor/bin/$b faltante. ¿Es un A72 Qualcomm?"
    echo "  $b: $ok" | tee -a "$D/marcas.txt"
done

# 5) Preparar WAV de entrada.
if [ -n "$WAV_ENTRADA" ]; then
    [ -f "$WAV_ENTRADA" ] || alto "No existe el WAV de entrada: $WAV_ENTRADA"
    WAV_LOCAL="$WAV_ENTRADA"
else
    WAV_LOCAL="$D/tono_frase.wav"
    python3 "$LLAMADAS/wav_prueba_0r.py" "$WAV_LOCAL" --rate "$RATE" | tee -a "$D/marcas.txt"
fi
# Push a /sdcard/Download primero (adb shell no es root; /sdcard/pruebas puede quedar sin
# permiso para el shell), luego muevo con su.
WAV_TMP=/sdcard/Download/tono_frase_0r.wav
WAV_CEL=/sdcard/pruebas/tono_frase.wav
$A push "$WAV_LOCAL" "$WAV_TMP" >/dev/null || alto "No pude empujar el WAV al cel."
$A shell "su -c 'mkdir -p /sdcard/pruebas && mv -f $WAV_TMP $WAV_CEL && chmod 0644 $WAV_CEL'" >/dev/null \
    || alto "No pude mover el WAV a $WAV_CEL con su."
echo "  WAV listo en cel: $WAV_CEL" | tee -a "$D/marcas.txt"

# 6) Volcar los PCM disponibles para que podamos auto-detectar devices (y para el registro).
$A shell "su -c 'cat /proc/asound/pcm 2>/dev/null; echo ---; cat /proc/asound/cards 2>/dev/null'" | tr -d '\r' > "$D/asound.txt"
# /proc/asound/pcm trae líneas tipo «00-07: MultiMedia2 (*) : playback 1 : capture 1». El
# device es la 2ª parte del «00-07». Permiten override por entorno (PCM_UP_OVERRIDE, PCM_DN_OVERRIDE).
extraer_device() {
    local etiqueta="$1" tipo="$2" linea card_dev
    linea="$(grep -i "$etiqueta" "$D/asound.txt" | grep -i "$tipo" | head -1 || true)"
    [ -z "$linea" ] && return 1
    card_dev="$(echo "$linea" | awk -F: '{print $1}' | tr -d ' ')"   # «00-07»
    echo "$card_dev" | awk -F- '{print $NF+0}'                       # «7»
}
# MultiMedia2 playback (uplink I-3). Algunos builds usan MultiMedia9 para INCALL_MUSIC.
PCM_UPLINK="$(extraer_device 'MultiMedia2' 'playback' || extraer_device 'MultiMedia9' 'playback' || true)"
# VOC_REC capture: puede aparecer como «QUAT_MI2S_RX» o «VoLTE Capture» en builds Samsung.
PCM_BAJADA="$(extraer_device 'VOC_REC\|VoiceRec\|VOIP_REC' 'capture' || extraer_device 'VoLTE' 'capture' || true)"
[ -n "${PCM_UP_OVERRIDE:-}" ] && PCM_UPLINK="$PCM_UP_OVERRIDE"
[ -n "${PCM_DN_OVERRIDE:-}" ] && PCM_BAJADA="$PCM_DN_OVERRIDE"
if [ -z "$PCM_UPLINK" ] || [ -z "$PCM_BAJADA" ]; then
    echo "⚠️  No pude auto-detectar los PCM devices. Mira $D/asound.txt y dime qué device" >&2
    echo "   corresponde a MultiMedia2/9 (playback, uplink) y a VOC_REC_* o similar (capture, bajada)." >&2
    echo "   Vuelve a correr así: PCM_UP_OVERRIDE=7 PCM_DN_OVERRIDE=22 $0" >&2
fi
[ -n "$PCM_UPLINK" ] || alto "Falta PCM_UPLINK (device number, no 'MultiMedia2'). Dime cuál es de $D/asound.txt."
[ -n "$PCM_BAJADA" ] || alto "Falta PCM_BAJADA (device number). Dime cuál es de $D/asound.txt."
echo "  PCM uplink (I-3): $PCM_UPLINK · PCM bajada: $PCM_BAJADA" | tee -a "$D/marcas.txt"

# También nombres exactos de los mezcladores: pueden variar entre builds. Buscamos los
# controles que existen en este cel (tinymix lista todos) y vamos con el primero que empate.
$A shell "su -c tinymix" | tr -d '\r' > "$D/mixers.txt" 2>&1 || true
# Formato tinymix: "NUM\tTIPO\tCOUNT\tNOMBRE   VALOR" (tabs, sin comillas). Awk extrae col 4 hasta primer bloque de 3+ espacios.
UPLINK_MIXER="$(grep -i 'Incall.*Music.*Audio Mixer.*MultiMedia2' "$D/mixers.txt" | head -1 | awk -F'\t' '{sub(/   +.*/,"",$4); print $4}' || true)"
DOWNLINK_MIXER="$(grep -i 'MultiMedia9 Mixer.*VOC_REC_DL' "$D/mixers.txt" | head -1 | awk -F'\t' '{sub(/   +.*/,"",$4); print $4}' || true)"
# Fallback: nombres literales de la documentación.
UPLINK_MIXER="${UPLINK_MIXER:-Incall_Music Audio Mixer MultiMedia2}"
DOWNLINK_MIXER="${DOWNLINK_MIXER:-MultiMedia9 Mixer VOC_REC_DL}"
echo "  Mixer uplink: «${UPLINK_MIXER}»" | tee -a "$D/marcas.txt"
echo "  Mixer bajada: «${DOWNLINK_MIXER}»" | tee -a "$D/marcas.txt"

# 7) Esperar a que haya llamada activa. Tú marcas desde la app de teléfono.
echo
echo "→ Marca a TU número de prueba (tu otro teléfono) desde la app de teléfono de este cel."
echo "   Cuando tu otro teléfono CONTESTE, el script detecta la llamada activa y sigue solo."
echo "   (si te atoras, Ctrl-C aquí y cuelga a mano)."
echo

inicio=$(date +%s)
while :; do
    estado="$($A shell dumpsys telephony.registry 2>/dev/null | tr -d '\r' | grep -m1 -oE 'mCallState=[0-9]+' | cut -d= -f2)"
    if [ "$estado" = 2 ]; then
        break
    fi
    [ $(( $(date +%s) - inicio )) -gt 180 ] && alto "Pasaron 3 min sin llamada activa; abortando."
    sleep 1
done
echo "$(hora_ms) ✅ Llamada activa (mCallState=2); abro la ruta I-3" | tee -a "$D/marcas.txt"

# 8) Lanzar logcat para la bitácora.
$A logcat -c >/dev/null 2>&1 || true
$A logcat -v time -s AudioFlinger:I audio_hw_primary:I audio_route:I AudioHardwareQSD:I AudioPolicyManager:I > "$D/logcat_audio.txt" 2>&1 &
PID_LOG=$!
# Refinamos la trampa ahora que sabemos los mezcladores exactos y hay PID_LOG.
limpiar() {
    kill -TERM "${PID_LOG:-}" 2>/dev/null; wait "${PID_LOG:-}" 2>/dev/null
    $A shell "su -c \"tinymix '$UPLINK_MIXER' 0; tinymix '$DOWNLINK_MIXER' 0\"" >/dev/null 2>&1 || true
    candado_soltar
}

# 9) Captura de la bajada EN EL CEL, en background, 5 s.
BAJADA_CEL=/sdcard/pruebas/bajada.wav
echo "$(hora_ms) tinymix «${DOWNLINK_MIXER}»=1 (bajada por mezclador)" | tee -a "$D/marcas.txt"
$A shell "su -c \"tinymix '$DOWNLINK_MIXER' 1\"" 2>&1 | tee -a "$D/marcas.txt"
# tinycap: 5 s a 16 kHz mono. -D 0 = card 0 (los Qualcomm en A72 son card 0).
# Si tu config requiere otro, ajusta DCARD.
DCARD="${DCARD:-0}"
echo "$(hora_ms) tinycap -> $BAJADA_CEL (card $DCARD, pcm $PCM_BAJADA, 16 kHz, 5 s)" | tee -a "$D/marcas.txt"
$A shell "su -c 'tinycap $BAJADA_CEL -D $DCARD -d $PCM_BAJADA -c 1 -r $RATE -b 16 -t 5'" > "$D/tinycap.txt" 2>&1 &
PID_CAP=$!
sleep 0.5

# 10) Inyectar el WAV por el mezclador INCALL_MUSIC (I-3).
echo "$(hora_ms) tinymix «${UPLINK_MIXER}»=1 (uplink por mezclador)" | tee -a "$D/marcas.txt"
$A shell "su -c \"tinymix '$UPLINK_MIXER' 1\"" 2>&1 | tee -a "$D/marcas.txt"
echo "$(hora_ms) tinyplay $WAV_CEL -D $DCARD -d $PCM_UPLINK (deberías oír tono+chirp en tu otro teléfono)" | tee -a "$D/marcas.txt"
$A shell "su -c 'tinyplay $WAV_CEL -D $DCARD -d $PCM_UPLINK'" > "$D/tinyplay.txt" 2>&1
echo "$(hora_ms) tinyplay terminado" | tee -a "$D/marcas.txt"

# 11) Esperar que termine la captura.
wait "$PID_CAP" 2>/dev/null || true
echo "$(hora_ms) tinycap terminado" | tee -a "$D/marcas.txt"

# 12) Cerrar los mezcladores (la trampa también lo intenta).
$A shell "su -c \"tinymix '$UPLINK_MIXER' 0; tinymix '$DOWNLINK_MIXER' 0\"" >/dev/null 2>&1
echo "$(hora_ms) mezcladores en 0 (ruta I-3 cerrada)" | tee -a "$D/marcas.txt"

# 13) Pull de la bajada y análisis.
$A pull "$BAJADA_CEL" "$D/bajada.wav" >/dev/null 2>&1 || alto "No pude recuperar la bajada del cel."
echo "$(hora_ms) bajada.wav traído a la Mac" | tee -a "$D/marcas.txt"
cp "$WAV_LOCAL" "$D/entrada.wav" 2>/dev/null || true

echo | tee -a "$D/marcas.txt"
echo "=== ANÁLISIS DE LA BAJADA ===" | tee -a "$D/marcas.txt"
python3 "$LLAMADAS/analizar_bajada_0r.py" "$D/bajada.wav" 2>&1 | tee -a "$D/analisis.txt" | tee -a "$D/marcas.txt"

# 14) Logcat se cierra en limpiar() (trap EXIT).

echo
echo "→ Registros: $D"
echo "→ Pasa si: oíste el tono y el chirp en tu otro teléfono, y"
echo "   el análisis dice 'bajada contiene tu voz, SIN acople de Isabella'."
echo "→ Cuelga tú la llamada cuando quieras."
