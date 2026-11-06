#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Funciones compartidas por los scripts de Agente Isabella. Se carga con: source "$RAIZ/comun.sh"

# Imprime el id de adb del celular a usar.
# - Con CEL=<número de serie> (getprop ro.serialno) usa ese celular.
# - Sin CEL: si todo lo conectado es UN solo celular (por Wi-Fi y/o cable) lo usa, prefiriendo el Wi-Fi;
#   si hay dos celulares distintos, pide elegir con CEL para no instalar en el equivocado.
elegir_celular() {
    local lista d serie series="" elegido="" preferido=""
    lista="$(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')"
    if [ -z "$lista" ]; then
        echo "No hay celular conectado: prende la Depuración inalámbrica (misma red Wi-Fi) o conecta el cable." >&2
        return 1
    fi
    for d in $lista; do
        serie="$(adb -s "$d" shell getprop ro.serialno 2>/dev/null | tr -d '\r')"
        case " $series " in *" $serie "*) ;; *) series="$series $serie" ;; esac
        if [ -n "${CEL:-}" ]; then
            [ "$serie" = "$CEL" ] && { echo "$d"; return 0; }
            continue
        fi
        [ -z "$elegido" ] && elegido="$d"
        case "$d" in *_adb-tls-connect*|*:*) preferido="$d" ;; esac
    done
    if [ -n "${CEL:-}" ]; then
        echo "No encuentro el celular con serie $CEL. Conectados:$series" >&2
        return 1
    fi
    if [ "$(echo $series | wc -w | tr -d ' ')" -gt 1 ]; then
        echo "Hay varios celulares conectados:$series — elige uno: CEL=<serie> $0 ..." >&2
        return 1
    fi
    echo "${preferido:-$elegido}"
}

# Carpeta del SDK de Android: ANDROID_HOME si existe; si no, la de Homebrew (Apple Silicon: /opt/homebrew, Intel: /usr/local).
ruta_sdk() {
    if [ -n "${ANDROID_HOME:-}" ]; then echo "$ANDROID_HOME"; return 0; fi
    local pre
    pre="$(brew --prefix 2>/dev/null || echo /opt/homebrew)"
    echo "$pre/share/android-commandlinetools"
}

# id de compilación del kit (no tocar)
_KIT_ID="4167656e74652049736162656c6c61202d204461766964205275697a2044756172746520287669616a617465636829"
