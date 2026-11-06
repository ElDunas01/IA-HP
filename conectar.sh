#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Conecta la Mac con el celular por Depuración inalámbrica SIN volver a vincular (vincular se hace una sola vez,
# con ./vincular_cel.sh). Busca por mDNS el servicio _adb-tls-connect que anuncia el cel y hace adb connect.
# Uso: herramientas/llamadas/conectar.sh                    ← busca y conecta (CEL=<serie> si hay dos celulares)
#      herramientas/llamadas/conectar.sh 192.168.X.Y:PUERTO   ← si el mDNS no lo ve: la IP:puerto que muestra el cel en
#                                                            Ajustes › Opciones de desarrollador › Depuración inalámbrica
#      herramientas/llamadas/conectar.sh --esperar 20 [60]   ← reintenta cada 60 s (o lo que digas) hasta 20 min
# Sale con 0 si quedó conectado y con 1 si no (en ese caso explica qué hacer en el cel).
# Por cable USB también cuenta: si adb ya ve el cel en estado «device», no hace falta nada más.
set -u
source "$(dirname "$0")/comun_llamadas.sh"

ESPERAR_MIN=0; CADA=60; DIRECTO=""
case "${1:-}" in
    --esperar) ESPERAR_MIN="${2:-20}"; CADA="${3:-60}" ;;
    *:*) DIRECTO="$1" ;;
    "") ;;
    *) echo "Uso: $0 [IP:PUERTO | --esperar MINUTOS [SEGUNDOS]]" >&2; exit 2 ;;
esac

# Imprime el id de adb si ya hay un celular (el de CEL, si se pidió) en estado «device».
ya_conectado() {
    local d s
    for d in $(adb devices | awk 'NR > 1 && $2 == "device" {print $1}'); do
        s="$(timeout 10 adb -s "$d" shell getprop ro.serialno 2>/dev/null | tr -d '\r')"
        [ -z "$s" ] && continue
        if [ -z "${CEL:-}" ] || [ "$s" = "$CEL" ]; then
            echo "$d"
            return 0
        fi
    done
    return 1
}

intento() {
    local d servicios s
    # Conexiones viejas «offline» (el cel cambió de puerto o se durmió): se sueltan para que no estorben.
    for d in $(adb devices | awk 'NR > 1 && $2 == "offline" {print $1}'); do
        adb disconnect "$d" >/dev/null 2>&1
    done
    ya_conectado && return 0
    if [ -n "$DIRECTO" ]; then
        timeout 15 adb connect "$DIRECTO" >/dev/null 2>&1
    fi
    servicios="$(adb mdns services | awk -v s="${CEL:-}" '/_adb-tls-connect/ && (s == "" || index($1, s)) {print $NF}')"
    for s in $servicios; do
        timeout 15 adb connect "$s" >/dev/null 2>&1
    done
    sleep 2
    ya_conectado
}

reportar() {
    local d="$1" modelo serie ip
    modelo="$(adb -s "$d" shell getprop ro.product.model | tr -d '\r')"
    serie="$(adb -s "$d" shell getprop ro.serialno | tr -d '\r')"
    ip="$(adb -s "$d" shell ip -f inet addr show wlan0 2>/dev/null | awk '/inet / {sub("/.*", "", $2); print $2; exit}' | tr -d '\r')"
    echo "✅ Conectado: $modelo (serie $serie) como $d — IP del cel ${ip:-desconocida}"
}

limite=$(( $(date +%s) + ESPERAR_MIN * 60 ))
while :; do
    if d="$(intento)"; then
        reportar "$d"
        exit 0
    fi
    [ "$(date +%s)" -ge "$limite" ] && break
    echo "$(date +%T) todavía no aparece; reintento en $CADA s"
    sleep "$CADA"
done

echo "❌ No encuentro el celular por adb." >&2
if adb devices | awk 'NR > 1 && $2 == "unauthorized"' | grep -q .; then
    echo "   Hay un celular por cable SIN AUTORIZAR: en su pantalla sale «¿Permitir depuración por USB?» → Permitir" >&2
    echo "   (marcar «Permitir siempre desde esta computadora»). Si no está la Depuración USB: Opciones de desarrollador." >&2
fi
if adb mdns services | grep -q _adb-tls-pairing; then
    echo "   El cel tiene abierta la ventana de vinculación: ciérrala (no hace falta volver a vincular)." >&2
fi
if ping -c 1 -t 2 "$IP_CONOCIDA" >/dev/null 2>&1; then
    echo "   Algo responde en $IP_CONOCIDA (la IP de la última vez), pero sin depuración inalámbrica." >&2
else
    echo "   Nada responde en $IP_CONOCIDA (la IP de la última vez): el cel puede estar dormido, en otra red o con otra IP." >&2
fi
cat >&2 <<'TXT'
   Qué hacer en el cel (no hace falta volver a vincular):
     1. Desbloquearlo y revisar que esté en el Wi-Fi de casa (el mismo de la Mac).
     2. Ajustes › Opciones de desarrollador › Depuración inalámbrica → encender (o el mosaico de configuración rápida).
   Si el mDNS del router no lo deja ver: correr este script con la IP:puerto que muestra esa pantalla.
TXT
exit 1
