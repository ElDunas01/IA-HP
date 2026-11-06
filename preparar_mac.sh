#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Prepara una Mac para compilar Agente Isabella. Se puede correr las veces que sea: solo
# instala lo que falta y al final verifica todo.
#   ./preparar_mac.sh --verificar          solo revisa, no instala nada
#   ./preparar_mac.sh --acepto-licencia    instala lo que falte; aceptar la licencia del SDK de Android
#                                          (Google) es decisión del usuario, por eso va como bandera explícita
set -uo pipefail

MODO="${1:-}"
JVM="$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk"
source "$(dirname "$0")/comun.sh"
SDK="$(ruta_sdk)"
ARQ="$(uname -m)"; [ "$ARQ" = "arm64" ] && ARQ=aarch64; [ "$ARQ" = "x86_64" ] && ARQ=x64
PAQUETES=("platforms;android-34" "build-tools;34.0.0")
falla=0

instalar_java() {
    # Temurin 17 de Adoptium, a nivel usuario: la versión de Homebrew es un .pkg que pide contraseña de administrador.
    local info url sha dl
    info="$(curl -s "https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=${ARQ}&image_type=jdk&os=mac&vendor=eclipse")"
    url="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)[0]["binary"]["package"]["link"])' <<<"$info")"
    sha="$(python3 -I -c 'import json,sys; print(json.load(sys.stdin)[0]["binary"]["package"]["checksum"])' <<<"$info")"
    dl="$(mktemp -d)"
    curl -fL --retry 3 -o "$dl/jdk.tar.gz" "$url" || return 1
    echo "$sha  $dl/jdk.tar.gz" | shasum -a 256 -c - || return 1
    mkdir -p "$dl/x" "$(dirname "$JVM")"
    tar -xzf "$dl/jdk.tar.gz" -C "$dl/x" && mv "$dl"/x/jdk-17* "$JVM"
}

if [ "$MODO" = "--acepto-licencia" ]; then
    command -v brew >/dev/null || { echo "⛔ Falta Homebrew: instálalo desde https://brew.sh (pide la contraseña de la Mac; eso lo escribe el usuario)"; exit 1; }
    [ -x "$JVM/Contents/Home/bin/java" ] || instalar_java || { echo "⛔ no se pudo instalar Java"; exit 1; }
    export JAVA_HOME="$JVM/Contents/Home"
    command -v adb >/dev/null || brew install --cask android-platform-tools
    command -v npx >/dev/null || brew install node
    [ -d "$SDK/cmdline-tools" ] || brew install --cask android-commandlinetools
    # OJO: `yes | sdkmanager` con pipefail mata el script (yes muere por SIGPIPE). Por eso aquí no hay set -e
    # y la licencia se comprueba después por su archivo.
    yes | sdkmanager --sdk_root="$SDK" --licenses >/dev/null 2>&1
    sdkmanager --sdk_root="$SDK" "${PAQUETES[@]}" >/dev/null
elif [ "$MODO" != "--verificar" ]; then
    echo "Uso: ./preparar_mac.sh --verificar | --acepto-licencia"; exit 2
fi

echo "== Verificación"
revisa() { if eval "$2" >/dev/null 2>&1; then echo "✓ $1"; else echo "✗ $1"; falla=1; fi; }
revisa "Java 17 (Temurin)"              "[ -x '$JVM/Contents/Home/bin/java' ]"
revisa "adb"                            "command -v adb"
revisa "Herramientas de Android"        "[ -d '$SDK/cmdline-tools' ]"
revisa "Licencia del SDK aceptada"      "[ -f '$SDK/licenses/android-sdk-license' ]"
revisa "Plataforma Android 14"          "[ -f '$SDK/platforms/android-34/android.jar' ]"
revisa "build-tools 34 (aapt2, d8…)"    "[ -x '$SDK/build-tools/34.0.0/aapt2' ] && [ -f '$SDK/build-tools/34.0.0/core-lambda-stubs.jar' ]"
revisa "Homebrew"                       "command -v brew"
revisa "Llavero de macOS (security)"    "command -v security"
revisa "Node.js (para el Worker)"       "command -v npx"
[ -f "$(dirname "$0")/llaves/agente-isabella.jks" ] && echo "✓ Llave de firma (tuya)" || \
    echo "• Sin llave de firma: compilar.sh crea una nueva, TUYA, la primera vez (respáldala después)"
exit $falla
