#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Lo compartido por las herramientas de llamadas. Se carga con: source "$(dirname "$0")/comun_llamadas.sh"
export PATH="/opt/homebrew/bin:$PATH"
LLAMADAS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RAIZ="$(cd "$LLAMADAS/../.." && pwd)"
REGISTROS="$LLAMADAS/registros"
PAQUETE=com.viajatech.lab.isabellallamadas
# Tu configuración local (NO va a git): copia mi_config.env.example a mi_config.env y llénalo.
[ -f "$RAIZ/mi_config.env" ] && source "$RAIZ/mi_config.env"
SERIE_A72="${SERIE_CEL:-${CEL:-}}"   # serie de TU cel (adb devices); vacío = el único conectado
IP_CONOCIDA="${IP_CEL:-}"            # IP de tu cel en tu Wi-Fi (opcional; puede cambiar)
# Únicos números a los que las herramientas dejan marcar: SOLO tuyos o de personas que aceptaron.
NUMEROS_PERMITIDOS="${NUMEROS_PERMITIDOS:-}"
mkdir -p "$REGISTROS"
source "$RAIZ/comun.sh"

# Quita espacios, guiones y lada internacional (+52 / 52) para comparar números.
normalizar_numero() {
    local n
    n="$(echo "$1" | tr -cd '0-9')"
    case "$n" in 52??????????) n="${n#52}" ;; esac
    echo "$n"
}

numero_permitido() {
    local n
    n="$(normalizar_numero "$1")"
    case " $NUMEROS_PERMITIDOS " in *" $n "*) return 0 ;; esac
    return 1
}

marca_tiempo() { date "+%Y-%m-%d_%H%M%S"; }

# --- Candado de UNA SOLA MANO (orden del director, 27-oct 21:45) -------------------------------------------------
# Ningún guion que abre sesión o marca arranca si hay otra prueba en curso. ALTO si registros/.en_curso trae algo y
# es de hace menos de 3 min, o si el logcat del cel trae una apertura de sesión o una marcación en los últimos 120 s.
# --forzar lo salta. Al arrancar se escribe .en_curso (hora, guion, pid); al salir se deja en 0 bytes, NUNCA se borra.
# Los guiones que llaman a otro (fisica/matriz/fusible → llamar.sh) le heredan el candado con CANDADO_DE=<pid>.
EN_CURSO="$REGISTROS/.en_curso"
RE_SESION_CEL='abriendo la sesión|Abro la sesión|Isabella conectada|Marcando a '
CANDADO_MIO=""

candado_tomar() {   # candado_tomar <id del cel> <guion> [--forzar]
    local cel="$1" guion="$2" forzar="${3:-}" edad ahora lineas
    if [ -n "${CANDADO_DE:-}" ] && [ -f "$EN_CURSO" ] && grep -q " pid ${CANDADO_DE}\$" "$EN_CURSO" 2>/dev/null; then
        return 0     # lo tiene el guion que me llamó
    fi
    if [ "$forzar" = "--forzar" ]; then
        echo "⚠️  Candado saltado con --forzar (${guion})." >&2
    else
        if [ -s "$EN_CURSO" ]; then
            edad=$(( $(date +%s) - $(stat -f %m "$EN_CURSO") ))
            if [ "$edad" -lt 180 ]; then
                echo "⛔ Candado: hay otra prueba en curso según registros/.en_curso («$(cat "$EN_CURSO")», hace ${edad} s)." >&2
                echo "   Si de verdad no corre nada: --forzar." >&2
                return 1
            fi
        fi
        ahora="$(adb -s "$cel" shell date +%s 2>/dev/null | tr -d '\r')"
        case "$ahora" in ''|*[!0-9]*) echo "⛔ Candado: no pude leer la hora del cel." >&2; return 1 ;; esac
        lineas="$(adb -s "$cel" logcat -d -v time -t "$((ahora - 120)).000" -s IsabellaLlamadas:I 2>/dev/null | tr -d '\r' \
            | grep -a -E "$RE_SESION_CEL" | tail -2)"
        if [ -n "$lineas" ]; then
            echo "⛔ Candado: el cel abrió una sesión o marcó en los últimos 120 s:" >&2
            echo "$lineas" | sed 's/^/     /' >&2
            echo "   Espera a que pasen 2 min desde esa línea, o --forzar si sabes que ya terminó." >&2
            return 1
        fi
    fi
    echo "$(date '+%H:%M:%S') ${guion} pid $$" > "$EN_CURSO"
    CANDADO_MIO=$$
    export CANDADO_DE=$$
    return 0
}

candado_soltar() {  # en la trampa de salida: deja .en_curso en 0 bytes si es mío (no lo borra)
    if [ -n "$CANDADO_MIO" ] && [ -f "$EN_CURSO" ] && grep -q " pid ${CANDADO_MIO}\$" "$EN_CURSO" 2>/dev/null; then
        : > "$EN_CURSO"
    fi
}
