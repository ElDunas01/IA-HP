#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Compila una app SIN Gradle ni Android Studio: aapt2 → javac → d8 → zipalign → apksigner.
# Uso: ./compilar.sh <app>        (p. ej. ./compilar.sh isabellallamadas)
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")" && pwd)"
source "$RAIZ/comun.sh"
APP="${1:?Uso: ./compilar.sh <app>}"
DIR="$RAIZ/apps/$APP"
[ -f "$DIR/app.conf" ] || { echo "No existe $DIR/app.conf" >&2; exit 1; }
source "$DIR/app.conf"   # NOMBRE, PAQUETE, VERSION_NOMBRE

export JAVA_HOME="${JAVA_HOME:-$HOME/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="$(ruta_sdk)"
BT="$SDK/build-tools/34.0.0"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
LLAVE="$RAIZ/llaves/agente-isabella.jks"
ALIAS=agente-isabella

# Llave de firma: es TUYA. Se crea una sola vez en llaves/ (fuera de git). Su contraseña vive en el Llavero de macOS
# (servicio «agente-isabella-keystore») y nunca se imprime. Respalda llaves/ : sin esa llave no podrás ACTUALIZAR la
# app ya instalada (habría que desinstalarla y volver a instalar).
contrasena_llave() { security find-generic-password -a "$USER" -s agente-isabella-keystore -w 2>/dev/null; }
if [ ! -f "$LLAVE" ]; then
    mkdir -p "$RAIZ/llaves"; chmod 700 "$RAIZ/llaves"
    if ! contrasena_llave >/dev/null; then
        security add-generic-password -a "$USER" -s agente-isabella-keystore -U -w "$(openssl rand -base64 24)"
    fi
    KS_PASS="$(contrasena_llave)" keytool -genkeypair -keystore "$LLAVE" \
        -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
        -dname "CN=Agente Isabella, OU=Basado en Agente Isabella de David Ruiz Duarte - viajatech, C=MX" \
        -storepass:env KS_PASS -keypass:env KS_PASS
    echo "Llave nueva (tuya): $LLAVE  — respáldala; su contraseña quedó en el Llavero de macOS."
fi

# Todo lo intermedio va a una carpeta temporal nueva; solo el APK final queda en apps/<app>/build/.
T="$(mktemp -d "${TMPDIR:-/tmp}/agente-isabella-$APP.XXXXXX")"
mkdir -p "$T/gen" "$T/clases" "$T/dex" "$DIR/build"
VERSION_CODIGO=$(( $(date +%s) / 60 ))   # crece solo: cada compilación puede actualizar a la anterior

"$BT/aapt2" compile --dir "$DIR/res" -o "$T/res.zip"
"$BT/aapt2" link -o "$T/base.apk" -I "$ANDROID_JAR" --manifest "$DIR/AndroidManifest.xml" -R "$T/res.zip" \
    --java "$T/gen" --auto-add-overlay --min-sdk-version 34 --target-sdk-version 34 \
    --version-code "$VERSION_CODIGO" --version-name "$VERSION_NOMBRE"

find "$DIR/src" "$T/gen" -name '*.java' > "$T/fuentes.txt"
# core-lambda-stubs: sin él javac no puede compilar lambdas contra android.jar (d8 las convierte después).
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options -bootclasspath "$ANDROID_JAR:$BT/core-lambda-stubs.jar" \
    -d "$T/clases" @"$T/fuentes.txt"
(cd "$T/clases" && jar cf "$T/clases.jar" .)
"$BT/d8" --release --min-api 34 --lib "$ANDROID_JAR" --output "$T/dex" "$T/clases.jar"
(cd "$T/dex" && zip -q -j "$T/base.apk" classes.dex)

"$BT/zipalign" -f -p 4 "$T/base.apk" "$T/alineado.apk"
KS_PASS="$(contrasena_llave)" "$BT/apksigner" sign --ks "$LLAVE" \
    --ks-key-alias "$ALIAS" --ks-pass env:KS_PASS --out "$DIR/build/$APP.apk" "$T/alineado.apk"

echo "Listo: $DIR/build/$APP.apk ($NOMBRE $VERSION_NOMBRE, código $VERSION_CODIGO)"
