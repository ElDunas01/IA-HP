#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Prueba en la Mac, sin cel ni cuota de Gemini: Tokens contra un Worker falso, SesionGemini + ClienteWebSocket
# contra puente/mock-live.mjs, TopeTimbre con un reloj real, las piezas en Java puro de la 2.4 (Ruta, Confirmacion,
# MarcasLatencia, HuecoServidor) y de la 2.5 (Wav, Grabacion), y candados sobre la fuente de LlamadaService, con las fuentes REALES de la app y
# sustitutos mínimos de android.util.Base64, android.content y org.json (src/ de esta carpeta). Escucha solo en
# 127.0.0.1. Uso: ./correr.sh [puerto_mock] [puerto_worker]
set -euo pipefail
AQUI="$(cd "$(dirname "$0")" && pwd)"
APP="$AQUI/../src/com/viajatech/lab/isabellallamadas"
T="$(mktemp -d "${TMPDIR:-/tmp}/isabellallamadas-pruebas.XXXXXX")"
javac -encoding UTF-8 -d "$T" $(find "$AQUI/src" -name '*.java') "$APP/SesionGemini.java" "$APP/ClienteWebSocket.java" "$APP/Tokens.java" "$APP/ArchivoSecreto.java" "$APP/Ajustes.java" "$APP/TopeTimbre.java" "$APP/Ruta.java" "$APP/Confirmacion.java" "$APP/MarcasLatencia.java" "$APP/HuecoServidor.java" "$APP/Wav.java" "$APP/Grabacion.java"
node "$AQUI/correr_arnes.mjs" "${1:-7399}" "${2:-8798}" "$T"
