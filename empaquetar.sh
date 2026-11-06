#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# empaquetar.sh — arma el zip del módulo Magisk para instalar la app como privilegiada.
#
# Uso:
#   ./empaquetar.sh                          # usa el APK más nuevo de ../entregas/
#   ./empaquetar.sh ../entregas/isabellallamadas-2.7-XXXXXXXX.apk
#
# Salida: isabellallamadas-privapp-vN.zip en la misma carpeta.
#
# El zip se instala desde Magisk Manager → Módulos → «Instalar desde almacenamiento», o
# por recovery con `adb sideload`. Reinicia solo. Verificación tras el arranque:
#   adb shell dumpsys package com.viajatech.lab.isabellallamadas | grep -E \
#     'CALL_AUDIO_INTERCEPTION|MODIFY_PHONE_STATE|CAPTURE_AUDIO_OUTPUT|MODIFY_AUDIO_ROUTING'
# Los 4 deben decir «granted=true».
#
# Convención del repo: `set -euo pipefail`, mensajes en español, rutas absolutas al final.

set -euo pipefail

AQUI="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENTREGAS="$(cd "${AQUI}/../entregas" && pwd)"

if [[ $# -ge 1 ]]; then
    APK="$1"
    [[ -f "${APK}" ]] || { echo "✗ No existe el APK: ${APK}" >&2; exit 1; }
else
    # Más nuevo por nombre (ordena lexicográficamente; versiones a 2 dígitos y versionCodes crecientes).
    APK="$(ls -1 "${ENTREGAS}"/isabellallamadas-*.apk 2>/dev/null | tail -1)"
    [[ -n "${APK}" ]] || { echo "✗ No se encontró ningún APK en ${ENTREGAS}" >&2; exit 1; }
fi

echo "→ APK:    ${APK}"
SHA="$(shasum -a 256 "${APK}" | cut -d' ' -f1)"
echo "→ sha256: ${SHA}"

DESTINO="${AQUI}/system/priv-app/IsabellaLlamadas/IsabellaLlamadas.apk"
cp -f "${APK}" "${DESTINO}"
chmod 0644 "${DESTINO}"

# Versión del módulo: ligamos versionCode al del APK empaquetado para que cada rebuild
# produzca un módulo distinto que Magisk considera «actualización».
VERSION_CODE_APK="$(basename "${APK}" | sed -E 's/^.*-([0-9]+)\.apk$/\1/')"
if [[ "${VERSION_CODE_APK}" =~ ^[0-9]+$ ]]; then
    sed -i.bak -E "s/^versionCode=.*/versionCode=${VERSION_CODE_APK}/" "${AQUI}/module.prop"
    rm -f "${AQUI}/module.prop.bak"
    echo "→ module.prop versionCode=${VERSION_CODE_APK}"
fi

ZIP="${AQUI}/isabellallamadas-privapp-${VERSION_CODE_APK:-v1}.zip"
rm -f "${ZIP}"

# Zip con la estructura relativa que espera Magisk: module.prop en la raíz, system/… debajo.
(
    cd "${AQUI}"
    zip -r "${ZIP}" module.prop system -x '*.DS_Store' -x '*.bak'
) >/dev/null

echo "✓ Módulo: ${ZIP}"
echo "  sha256: $(shasum -a 256 "${ZIP}" | cut -d' ' -f1)"
echo
echo "Para instalar:"
echo "  adb push '${ZIP}' /sdcard/Download/"
echo "  → Magisk Manager → Módulos → Instalar desde almacenamiento → el zip"
echo "  → Reiniciar"
