# Módulo Magisk — Isabella Llamadas como app privilegiada

Instala `com.viajatech.lab.isabellallamadas` en `/system/priv-app/` (systemless, por Magisk) y le concede 4 permisos privilegiados mediante una lista blanca XML. Sin esto, con `ro.control_privapp_permissions=enforce` (así viene el SM-A725M TCE), el framework no concede esos permisos aunque la app los declare, y el modo `raiz` no arranca.

**Documento de referencia:** `docs/ARQUITECTURA.md` (rutas de audio) y `AGENTS.md` fase 5 (root).

## Estructura

```
magisk_modulo/
├── module.prop
├── empaquetar.sh                 ← arma el zip final
├── README.md                     ← este archivo
└── system/
    ├── priv-app/
    │   └── IsabellaLlamadas/
    │       └── IsabellaLlamadas.apk    ← lo copia empaquetar.sh desde entregas/
    └── etc/
        └── permissions/
            └── privapp-permissions-com.viajatech.lab.isabellallamadas.xml
```

## Cómo armar el zip

```bash
./empaquetar.sh
# usa el APK más nuevo de ../entregas/; o pasa uno:
./empaquetar.sh ../entregas/isabellallamadas-2.7-XXXXXXXX.apk
```

Salida: `isabellallamadas-privapp-<versionCode>.zip`.

## Cómo instalarlo (opcional; la app funciona sin esto desde la versión raíz)

Después del paso 6 (primer arranque con Magisk, `su -c id` da `uid=0`):

```bash
adb push isabellallamadas-privapp-<N>.zip /sdcard/Download/
```

En el A72: **Magisk → Módulos → Instalar desde almacenamiento → ese zip → Reiniciar**.

Si la app ya estaba instalada como app normal, Magisk la verá en priv-app tras el reinicio y Android la tomará como privilegiada: no hay que desinstalar antes.

## Verificación (tras reiniciar)

```bash
adb shell dumpsys package com.viajatech.lab.isabellallamadas \
  | grep -E 'CALL_AUDIO_INTERCEPTION|MODIFY_PHONE_STATE|CAPTURE_AUDIO_OUTPUT|MODIFY_AUDIO_ROUTING'
```

Los 4 deben decir `granted=true`. Si alguno dice `granted=false`:
- comprobar que la lista blanca XML está en `/system/etc/permissions/` (Magisk mounts): `adb shell ls /system/etc/permissions/ | grep isabellallamadas`
- comprobar que el APK está en `/system/priv-app/IsabellaLlamadas/`: `adb shell ls /system/priv-app/ | grep IsabellaLlamadas`
- revisar `logcat` en el arranque: buscar «NOT_WHITELISTED» o «hidden privapp permission»

## Si falla y quiero desinstalar el módulo

Magisk → Módulos → desactivar `isabellallamadas-privapp` → Reiniciar.
Para quitarlo del todo: desactivar + «Eliminar» + Reiniciar.
La app queda como app normal (si estaba instalada como normal antes), o desaparece (si solo venía por el módulo).

## Lo que NO hace este módulo

- No da root a la app: el root lo da Magisk, y la app solo necesita `su` para la ruta I-3 (mezclador manual). Para I-1 e I-2 basta con los 4 permisos de arriba.
- No modifica `/system` real: todo es overlay systemless de Magisk. Si el módulo se desactiva, `/system` queda como estaba.
- No parcha el firmware: eso se hace con la app de Magisk (AGENTS.md, fase 5).
