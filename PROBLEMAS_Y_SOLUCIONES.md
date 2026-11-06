# Problemas y soluciones (35 tropiezos reales)

> Agente Isabella — creado por David Ruiz Duarte (viajatech). Formato: **síntoma** → causa → arreglo.

## Mac, compilación e instalación
1. **La instalación del SDK se corta sin error** → `yes | sdkmanager` con `pipefail` muere por SIGPIPE → `preparar_mac.sh`
   no usa `set -e` ahí y verifica la licencia por su archivo.
2. **`javac`: Unable to find method metafactory** → `android.jar` no trae `LambdaMetafactory` → `compilar.sh` agrega
   `core-lambda-stubs.jar` al bootclasspath.
3. **`am start-foreground-service`: not exported** → el servicio debe ser `exported=true` con permiso `DUMP` (ya está).
4. **`adb devices` vacío** → cable de solo carga, o falta aceptar «¿Permitir depuración USB?» en el cel.
5. **«unauthorized»** → revisar el aviso en el cel y marcar «Permitir siempre».
6. **La pantalla se bloquea y adb pierde el cel** → `adb shell svc power stayon usb` (lo hace `configurar_app.sh`).
7. **Tras `adb install -r` la app ya no marca** → Android quita el permiso `MANAGE_ONGOING_CALLS` en cada reinstalación
   → `./configurar_app.sh` otra vez.
8. **Tras desinstalar se perdió el secreto** → `install -r` solo conserva datos si no hubo desinstalación →
   `./configurar_app.sh` lo vuelve a importar.
9. **No se puede actualizar la app** → la firmaste con otra llave → usa siempre la de `llaves/` (respáldala).
10. **adb por Wi-Fi cambia de nombre** → usa `CEL=<serie>`; los scripts eligen el aparato por su serie.
11. **`adb shell` dentro de un heredoc se traga el guion** → usar `adb shell -n`.
12. **En el bash de macOS, `"$var»"` con `set -u` rompe el guion** → escribir `${var}` siempre.
13. **`logcat -d -b all` por Wi-Fi tarda muchísimo** → usar `-b main,system` con `timeout` o filtrar por etiqueta.
14. **Dos pruebas arrancaron a la vez** → dos «manos» lanzando órdenes → candado de una sola mano en
    `herramientas/llamadas/comun_llamadas.sh`; una persona/agente a la vez.
15. **Respuestas del agente en otro idioma** → pídele que conteste siempre en el tuyo.

## Root y arranque
16. **Archivos grandes corruptos al copiarlos** → MTP falla con archivos grandes → `adb push` / `adb pull`.
17. **Flashear sin verificar** → verifica la suma de control del firmware antes de flashear.
18. **Particiones sueltas** → regresar `boot`, `vbmeta` o `recovery` sueltos puede inutilizar el equipo → flashea
    siempre el firmware completo.
19. **El desbloqueo OEM no aparece** → algunos Samsung lo muestran solo después de varios días en línea; ten paciencia.
20. **Primer arranque sin internet tras desbloquear** → en Samsung puede volver a bloquearse → ten Wi-Fi listo.
21. **No sabes en qué modo está el cel** → toma foto de la pantalla y compártela con tu agente antes de tocar nada;
    consulta la guía oficial de Magisk para tu modelo.

## Ruta raíz y audio
22. **`tinyplay` no acepta `-` como entrada** → usar `/dev/stdin` (tampoco acepta `-i wav`).
23. **`tinymix` imprime columnas separadas por TAB** → el extractor usa `awk -F'\t'`.
24. **Subir el volumen con `tinymix Incall_Music`** → ese mezclador es 0/1 → la ganancia va en software.
25. **Dos `tinyplay` sobre `hw:0,1`** → solo se permite uno → la app lo impide; para probar WAV usa `solo_marcar`.
26. **`mute_mic_fix=1` deja muda a Isabella en el A72** → el silencio del DSP va después del mezclador → nunca activarlo.
27. **Pedir dos sesiones de Gemini a la vez** → gasto doble y audio cruzado → `--es cmd colgar` antes de otra.
28. **Tokens en el registro del cel** → `adbd` registra `--es token …` → usar siempre el Worker.
29. **Isabella saluda pero del otro lado no se oye** → falta root o el Modo RAÍZ → `adb shell su -c id` y el switch.
30. **Del otro lado hablan y Gemini no responde** → mezclador de bajada cerrado → corre `prueba_0r.sh`.
31. **Saturada o metálica** → bajar `preenfasis` a 8 o `ganancia_tts` a 40; subir `softclip_knee`.
32. **Bajita** → subir `ganancia_tts` hasta 50 (arriba de 45 rinde poco).

## Worker y llaves
33. **`sin_llave` (503)** → no subiste `GEMINI_API_KEY_AQ` → `npx wrangler secret put GEMINI_API_KEY_AQ` en tu Terminal.
34. **`wrangler secret put` guarda vacío** → lo corriste sin una terminal real (desde un agente) → hazlo en Terminal.app
    o por tubería desde un archivo local.
35. **`wrangler dev` no arranca por la fecha de compatibilidad** → actualiza wrangler (`npm install` en `token-worker/`)
    o usa una fecha soportada en `wrangler.jsonc`.

## Señales útiles en la bitácora
```bash
adb logcat -s IsabellaLlamadas          # bitácora en vivo
adb shell su -c id                      # ¿hay root?
adb shell appops get com.viajatech.lab.isabellallamadas MANAGE_ONGOING_CALLS
adb shell su -c 'tinymix' | grep -iE 'Incall_Music|VOC_REC_DL'
```
