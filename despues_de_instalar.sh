#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Permisos de «Isabella Llamadas» después de instalarla (lo llama instalar.sh con $ADB definido).
# Da por adb lo que se puede sin tocar la pantalla y dice cómo probar. No activa la accesibilidad:
# eso lo decide el dueño del cel y se prende con el comando de abajo.
set -euo pipefail
P=com.viajatech.lab.isabellallamadas

# Permisos de ejecución (runtime). En One UI algunos pueden pedir confirmación en pantalla; si uno
# falla, no se corta el script: la app los vuelve a pedir al usarse.
for permiso in \
    android.permission.CALL_PHONE \
    android.permission.RECORD_AUDIO \
    android.permission.READ_PHONE_STATE \
    android.permission.POST_NOTIFICATIONS \
    android.permission.ANSWER_PHONE_CALLS ; do
    $ADB shell pm grant $P $permiso 2>/dev/null && echo "  grant $permiso" || echo "  (pendiente, se pedirá en pantalla) $permiso"
done

# Que el ahorro de batería no mate el servicio ni el socket durante la llamada.
$ADB shell cmd deviceidle whitelist +$P >/dev/null 2>&1 || true

cat <<FIN

Isabella Llamadas instalada (v2: la app habla directo con Gemini Live, sin la Mac).
Tag de logcat: IsabellaLlamadas · WAV del diagnóstico en /sdcard/Android/data/$P/files/

Probar (ejemplos; todo el detalle en el README de la app):
  • Primero, SIN marcar, para probar la voz de Isabella con el micrófono y el altavoz del cel:
      $ADB shell am start-foreground-service -n $P/.LlamadaService --es cmd isabella
  • Llamada real a TU otro teléfono (ruta raíz), con techo de 3 minutos:
      $ADB shell am start-foreground-service -n $P/.LlamadaService --es cmd llamar --es numero <TU_NUMERO_DE_PRUEBA> --es raiz 1 --es saludar 1 --es maxmin 3
  • Colgar (corta la sesión de Gemini en el acto):
      $ADB shell am start-foreground-service -n $P/.LlamadaService --es cmd colgar
  • Bitácora en vivo:
      $ADB logcat -s IsabellaLlamadas

Servicio de accesibilidad (OPCIONAL; solo para que el micrófono no llegue en silencio durante la
llamada). Encender / apagar desde la Mac:
  Encender:
    $ADB shell settings put secure enabled_accessibility_services $P/$P.ServicioAccesibilidad
    $ADB shell settings put secure accessibility_enabled 1
  Apagar (quita SOLO la entrada de la app, sin borrar otros servicios tuyos):
    herramientas/llamadas/permisos.sh --revertir
  (solo si NO hay otro servicio de accesibilidad activo, equivale a:
    $ADB shell settings put secure enabled_accessibility_services ""
    $ADB shell settings put secure accessibility_enabled 0 )

InCallService (OPCIONAL; da altavoz fiable, apertura de Gemini en STATE_ACTIVE real y colgado con
Call.disconnect()). Se concede/revoca desde la Mac; es por
PAQUETE, no por componente:
  Conceder:
    $ADB shell appops set $P MANAGE_ONGOING_CALLS allow
  Revertir (dejar como estaba):
    $ADB shell appops set $P MANAGE_ONGOING_CALLS default
  Comprobar:
    $ADB shell appops get $P MANAGE_ONGOING_CALLS
Con el appop concedido, al marcar verás en logcat «InCallService ligado». Sin él, ruta clásica.

El token efímero NUNCA se guarda en archivos ni se imprime fuera de aquí; caduca en minutos.
FIN
