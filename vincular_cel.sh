#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Vincula un celular con esta Mac por Wi-Fi (una sola vez por celular).
# En el cel: Ajustes › Opciones de desarrollador › Depuración inalámbrica › «Vincular dispositivo con
# código de vinculación». Deja esa ventana abierta y corre:  ./vincular_cel.sh <código de 6 dígitos>
# El puerto no hace falta: la Mac lo encuentra sola (mDNS). Después adb se reconecta solo.
set -euo pipefail

CODIGO="${1:?Uso: ./vincular_cel.sh <código de 6 dígitos que muestra el celular>}"
SERVICIO="$(adb mdns services | awk '/_adb-tls-pairing/ {print $NF; exit}')"
if [ -z "$SERVICIO" ]; then
    echo "No veo ningún celular esperando vinculación. Revisa que el cel y la Mac estén en la misma red Wi-Fi" >&2
    echo "y que la ventana «Vincular dispositivo con código» siga abierta en el celular." >&2
    exit 1
fi
adb pair "$SERVICIO" "$CODIGO"
sleep 3   # adb se conecta solo por mDNS unos segundos después de vincular
adb devices -l
