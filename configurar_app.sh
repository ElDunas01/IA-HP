#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
#
# Configura Isabella Llamadas en el cel (fase 6 de AGENTS.md). Nunca imprime secretos.
#  1. Genera el secreto del aparato en secretos/ (si no existe; 43 caracteres aleatorios).
#  2. Lo sube al Worker como LAB_DEVICE_SECRET (por tubería; SUBIR_AL_WORKER=0 para saltarlo).
#  3. Lo importa a la app por tubería (la app lo guarda en privado y deja el archivo en 0 bytes).
#  4. Guarda en la app la URL del Worker, el techo y el modo; da MANAGE_ONGOING_CALLS; pantalla despierta con USB.
# Uso: ./configurar_app.sh          (lee mi_config.env; CEL=<serie> si hay dos cels)
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")" && pwd)"
source "$RAIZ/comun.sh"
[ -f "$RAIZ/mi_config.env" ] || { echo "Falta mi_config.env: cp mi_config.env.example mi_config.env y llénalo." >&2; exit 1; }
source "$RAIZ/mi_config.env"
WORKER_URL="${WORKER_URL:-}"
case "$WORKER_URL" in
    https://*TU-SUBDOMINIO*|"") echo "Pon tu WORKER_URL real en mi_config.env (https://…workers.dev/token)." >&2; exit 1 ;;
    https://*) ;;
    *) echo "WORKER_URL debe empezar con https://" >&2; exit 1 ;;
esac

P=com.viajatech.lab.isabellallamadas
S=$P/.LlamadaService
DISPOSITIVO="$(elegir_celular)"
A="adb -s $DISPOSITIVO"
$A shell pm path $P >/dev/null 2>&1 || { echo "La app no está instalada: ./compilar.sh isabellallamadas && ./instalar.sh isabellallamadas" >&2; exit 1; }

SECRETO="$RAIZ/secretos/device_secret.txt"
mkdir -p "$RAIZ/secretos"; chmod 700 "$RAIZ/secretos"
if [ ! -s "$SECRETO" ]; then
    (umask 077; openssl rand -base64 32 | tr -d '\n=' | tr '+/' '-_' > "$SECRETO")
    echo "✓ Secreto del aparato nuevo en secretos/ (no se muestra)."
fi

if [ "${SUBIR_AL_WORKER:-1}" = "1" ]; then
    echo "→ Subiendo el secreto al Worker (LAB_DEVICE_SECRET)…"
    (cd "$RAIZ/token-worker" && npx wrangler secret put LAB_DEVICE_SECRET < "$SECRETO" >/dev/null)
    echo "✓ Secreto en el Worker."
fi

echo "→ Importando el secreto a la app (por tubería)…"
$A shell am start-foreground-service -n $S --es cmd estado >/dev/null   # crea la carpeta files/ de la app
sleep 2
$A shell "cat > /sdcard/Android/data/$P/files/secreto.txt" < "$SECRETO"
$A shell am start-foreground-service -n $S --es cmd importar_secreto >/dev/null
sleep 2
TAM="$($A shell stat -c %s /sdcard/Android/data/$P/files/secreto.txt 2>/dev/null | tr -d '\r' || echo '?')"
[ "$TAM" = "0" ] && echo "✓ Secreto importado (el archivo quedó en 0 bytes)." || echo "⚠️  Revisa la bitácora: adb logcat -d -s IsabellaLlamadas | tail"

$A shell am start-foreground-service -n $S --es cmd config --es tokenurl "$WORKER_URL" \
    --es maxmin "${TECHO_MIN:-3}" --es max_timbre_s 20 --es modo "${MODO:-servicios}" >/dev/null
$A shell appops set $P MANAGE_ONGOING_CALLS allow
$A shell svc power stayon usb
echo "✓ Worker, techo (${TECHO_MIN:-3} min) y modo (${MODO:-servicios}) guardados; permiso de llamadas concedido."
echo "Prueba sin llamada:  adb shell am start-foreground-service -n $S --es cmd isabella"
echo "Recuerda: cada ./instalar.sh quita el permiso de llamadas → vuelve a correr ./configurar_app.sh"
