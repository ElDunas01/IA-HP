#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Instala o actualiza una app de Agente Isabella en el celular (por Wi-Fi si está, si no por cable).
# Uso: ./instalar.sh <app>                 (si hay dos celulares conectados: CEL=<serie> ./instalar.sh <app>)
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")" && pwd)"
source "$RAIZ/comun.sh"
APP="${1:?Uso: ./instalar.sh <app>}"
APK="$RAIZ/apps/$APP/build/$APP.apk"
[ -f "$APK" ] || { echo "Falta $APK: corre primero ./compilar.sh $APP" >&2; exit 1; }

DISPOSITIVO="$(elegir_celular)"
MODELO="$(adb -s "$DISPOSITIVO" shell getprop ro.product.model | tr -d '\r')"
adb -s "$DISPOSITIVO" install -r "$APK"
if [ -f "$RAIZ/apps/$APP/despues_de_instalar.sh" ]; then
    ADB="adb -s $DISPOSITIVO" bash "$RAIZ/apps/$APP/despues_de_instalar.sh"
fi
echo "Instalada: $APP en $MODELO ($DISPOSITIVO)"
echo "Siguiente paso: permisos especiales y configuración (AGENTS.md, fase 6)."
