<!-- SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) -->
<!-- SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0 -->

# La odisea del boot — qué hacer si tras flashear no arranca

> Esta guía recoge los parches que **sí funcionaron** cuando el celular de pruebas
> (Galaxy A72, Android 14, One UI 6.1) quedó atorado tras el flasheo del firmware
> parchado con Magisk. Se escribe por si a otra persona le pasa lo mismo con un
> Samsung Snapdragon moderno. **Fuentes y método vienen al final; nada aquí es
> receta secreta, todo está documentado en los repos de Magisk, samloader-rs,
> AOSP y en los hilos de XDA.**
>
> Para la ruta feliz (sin tropiezos), sigue la guía oficial de Magisk:
> <https://topjohnwu.github.io/Magisk/install.html>. Si llegas aquí es porque
> «ya instalé Magisk y el celular no arranca» o «samloader se cuelga».

## 0. Síntomas que esta guía resuelve

- Tras `samloader flash` del firmware parchado, el celular enciende, pasa el
  logo del fabricante, llega a «Powered by Android» y **cae en bucle a stock
  recovery** una y otra vez. En recovery, «Reboot system now» repite el ciclo.
- `samloader` termina con `exit 0` pero el log no muestra las líneas de
  «Flashing BL/AP/CP/CSC» (todo quedó en silencio tras «PIT flash successful»).
- `samloader` aborta en el pre-flight con `CheckSuperSize insufficient free
  space` o con `session_response 0x38000` incluso con el firmware correcto.

Si tus síntomas son **otros** (brick seco, bootloader que no entra en modo
descarga, Knox que volvió a cerrar OEM): mira la §5 — ahí hay un recovery
genérico.

## 1. Causa raíz (en una línea cada una)

Dos cosas distintas, y pueden pasar juntas:

**A) samloader-rs no manda `super_used_size` al bootloader.**
En los firmwares Samsung Snapdragon recientes (protocol version 3,
`session_response 0x38000`), el bootloader exige saber `super_used_size`
**antes** de aceptar escribir el `super.img`. Sin ese valor, o se aborta el
pre-flight (`CheckSuperSize`), o la escritura ocurre sin la metadata de
dynamic partitions. Resultado: `system`/`vendor`/`product` quedan físicamente
escritos pero lógicamente inválidos; Android no monta y cae a recovery.
Issues públicos: **topjohnwu/samloader-rs #43 y #51**.

**B) Magisk solo parcha `boot.img`; `vbmeta` sigue encadenada al original.**
Al parchar el AP con la app de Magisk, Magisk reescribe `boot.img` pero
**no regenera los descriptores `hashtree` dentro de `vbmeta.img` /
`vbmeta_system.img` / `vbmeta_vendor.img`**. En un Snapdragon con bootloader
desbloqueado, la `vbmeta` principal degrada a warning naranja (eso **es
esperado y benigno**: la pantallita «This phone is not running the official
software» no es el error). Pero las **vbmeta encadenadas**
(`vbmeta_system`/`vbmeta_vendor`) **siguen aplicando `hashtree`** sobre
`system`/`vendor` incluso con el candado abierto, y el hash del `boot`
parchado ya no cuadra con el que ellas tienen escrito. Resultado: Android
rechaza montar y cae a recovery. Issue público: **topjohnwu/Magisk #6230**.

Hay una tercera cosa que confunde el diagnóstico: a veces basta con un
factory reset desde recovery (si la metadata de `fscrypt` quedó inconsistente
tras el flasheo). Es gratis probar y lo descartamos antes de pasar a los
parches invasivos.

## 2. Parche en orden (del más barato al más invasivo)

### 2.1 Prueba A — factory reset desde recovery (gratis, no toca nada en disco)

En recovery, con los botones de volumen y encendido:

1. `Vol−` navega hasta **Wipe data / factory reset**.
2. **Power** para seleccionar.
3. Confirmar («Factory data reset» / «Yes»).
4. Esperar a que termine.
5. **Reboot system now** → mirar si llega a **Welcome / Hola**.

Si llega al Welcome: terminaste. Si **vuelve a recovery**, pasa a 2.2.
No pierdes nada: el `/data` ya se había borrado al desbloquear el bootloader.

### 2.2 Prueba B — reflashear `vbmeta` «disabled» (lo que a nosotros nos arrancó)

La idea: generar un `vbmeta.img` con el flag `AVB_VBMETA_IMAGE_FLAGS_HASHTREE_DISABLED`
(y, cuando aplica, `VERIFICATION_DISABLED`). Esto **no desactiva Magisk**: solo le
dice al bootloader que no aplique `hashtree` sobre `system`/`vendor`, de modo
que las vbmeta encadenadas dejen de rechazar la imagen por el hash del `boot`
parchado.

**Herramienta oficial de Google**: `avbtool.py` del repo AOSP
`platform/external/avb` — <https://android.googlesource.com/platform/external/avb>.
Es un script Python de una sola pieza, se descarga suelto, no requiere compilar
nada. Lo baja y lo corre la persona (no un agente): escribir el `vbmeta` lo
hacen tus manos, no las nuestras.

**Generar `vbmeta_disabled.img` (4 KB, en tu Mac):**

```bash
# 1) avbtool.py suelto, carpeta nueva fuera del repo (p. ej. ~/Downloads/avb/)
curl -o ~/Downloads/avb/avbtool.py \
  'https://android.googlesource.com/platform/external/avb/+/refs/heads/android14-release/avbtool.py?format=TEXT' \
  | base64 -D > ~/Downloads/avb/avbtool.py
chmod +x ~/Downloads/avb/avbtool.py

# 2) El vbmeta disabled (4 KB, hace lo mismo que el --disable-verity --disable-verification)
python3 -I ~/Downloads/avb/avbtool.py make_vbmeta_image \
  --flag 2 \
  --padding_size 4096 \
  --output ~/Downloads/avb/vbmeta_disabled.img
# flag 2 = HASHTREE_DISABLED (bit 1); flag 3 añade VERIFICATION_DISABLED si lo necesitas
```

**Flashear SOLO la partición `VBMETA`:**

Lleva el celular a Download Mode (azul). Si está en bootloop a recovery y no
se puede apagar, usa el **truco del fastboot verde** de §4.

```bash
# El nombre de la partición en el PIT es MAYÚSCULAS: VBMETA (no "vbmeta")
# Verifica el nombre exacto con samloader print-pit si tienes dudas.
samloader flash --skip-md5 -p VBMETA ~/Downloads/avb/vbmeta_disabled.img
```

El flash es cortísimo (4 KB al slot `VBMETA`): verás `Downloading PIT →
Rebooting → exit 0` casi al instante. **No es sospechoso que acabe rápido**:
son 4 KB, no 7 GB.

**El primer boot después es largo** (regen de la crypto key, `dex2oat` de las
apps del sistema, media scan): fabricante → operadora → «Powered by Android»
→ animación → **Welcome / Hola** = ganaste. Si el touch del Welcome no responde
al principio, es normal: el launcher todavía está cargando. Mantén `Power`
sostenido para un apagado profundo y vuelve a encender si no destraba.

### 2.3 Si samloader se te colgó en el flasheo original (parche al parche)

Dos variantes que vimos:

**Variante «silenciosa»**: `samloader flash --repartition --skip-md5` termina
con `exit 0`, pero el log solo lista «PIT flash successful» y después 35 saltos
de línea en blanco. Significa que **flasheó solo el PIT** y saltó BL/AP/CP/CSC.
Confusamente, el cel reinicia a modo descarga como si todo hubiera salido
bien. Fix: **relanza sin `--repartition`** y mira el log con
`script -q -F <log> samloader flash …` para que los `\r` del progreso no se
pierdan (en macOS `script` necesita `-F`).

**Variante «CheckSuperSize insufficient free space»**: en samloader-rs 2.2.0,
el pre-flight necesita leer `meta-data/super_used_size.txt` del `.tar` del
firmware. Si falta o es `0`, aborta. Hay un **workaround del issue #43**: crear
un `.tar` extra con `meta-data/super_used_size.txt` que literalmente contenga
el carácter `0`. En el código fuente de samloader 2.2.0 (`odin/src/flash.rs`),
el pre-flight se **salta** cuando `super_used_size == 0` (la condición es
`&& super_used_size > 0`), y el `super.img` se escribe con `tag 0`, limpio.

```bash
# Workaround issue #43 (ejecuta tú, en una carpeta fuera del repo):
mkdir -p /tmp/sl_wkr/meta-data
printf '0' > /tmp/sl_wkr/meta-data/super_used_size.txt
tar -C /tmp/sl_wkr -cf /tmp/sl_wkr.tar meta-data

# Flashea pasando los 4 originales (BL/AP parchado/CP/CSC) **y además** el .tar workaround.
# Consulta `samloader flash -h` para el orden exacto de tu versión.
```

No se usa `--repartition` (empeora el síntoma) ni `--skip-size-check` (con el
workaround el check se salta solo).

## 3. Qué NO hacer (brickea de verdad)

- **No regreses `boot`, `vbmeta` o `recovery` «sueltos» a stock.** Con Knox ya
  quemado, el bootloader rechazará esas particiones firmadas por el fabricante
  y el cel se queda duro. Si quieres volver a stock, **reflashea el firmware
  completo** (BL + AP + CP + CSC original) con borrado desde modo descarga.
- **No uses `HOME_CSC` después de desbloquear.** Fuerza a saltarse el borrado;
  en nuestro caso dejó el cel en estado intermedio. Siempre `CSC` original.
- **No conectes el cable mientras intentas apagarlo** (si está en recovery).
  Mientras el USB da corriente, el bootloader reinicia solo y vuelves al loop.
- **No te saltes el Wi-Fi en el primer arranque** post-desbloqueo: Knox Guard
  puede volver a cerrar el OEM si el cel pasa demasiado tiempo sin internet
  (estado «prenormal»).

## 4. El truco del fastboot verde (apagar un cel en bootloop)

Si el celular está en bucle a recovery y no se puede apagar con `Power` normal
(hace reset en cuanto suelta el botón, porque no hay sistema que apagar):

1. En recovery, con `Vol±` navega hasta **Reboot to bootloader** → `Power`.
2. En algunos modelos esto lleva directo a Download Mode azul.
3. En otros (incluido el A72) **cae a fastboot verde** («START» en pantalla).
   Ahí:
   - Con `Vol−` navega las opciones (`Start`, `Restart bootloader`, `Recovery
     mode`, `Power off`, `Download mode`).
   - Selecciona **`Power off`** con `Power`. El celular se apaga de verdad
     (en fastboot no hay bootloop porque no intenta arrancar sistema).
4. Cel apagado, **desconecta el USB**. Déjalo quieto un momento.
5. **Pon un dedo en `Vol+` y otro en `Vol−` al mismo tiempo**, los dos.
6. **Sin soltarlos**, conecta el USB-C. Verás la pantalla **Warning naranja**.
7. Suelta las teclas. **`Vol+`** una sola vez → **Download Mode azul**.

Clave: `Vol+ Vol−` tienen que estar presionados **antes** de que entre el
cable. Si los aprietas después, el cel ya empezó a arrancar y cae a recovery.

## 5. Si todo salió mal — recovery genérico

Si tras aplicar lo anterior el celular sigue sin bootear o no entra a modo
descarga:

1. Verifica que entras a **Download Mode** (fuerza bruta: cel apagado, sin
   cable, `Vol+ Vol−` sostenidos, conectar USB). Si no entra: el cable, el
   puerto o el combo. Prueba otro cable **con datos** (no de solo carga).
2. **Reflashea el firmware completo** (BL + AP parchado + CP + CSC original)
   con samloader. Nunca particiones sueltas de stock.
3. **CSC original**, no `HOME_CSC`: fuerza borrado. Un borrado adicional
   sobre un cel ya borrado no pierde nada.
4. **Primer arranque con internet obligatorio**: Knox Guard. Sin internet
   pronto, Knox Guard vuelve a cerrar OEM.
5. **Si Knox Guard volvió a cerrar OEM**, hay que esperar **7 días en línea**
   desde el reset antes de que el toggle «Desbloqueo OEM» vuelva a aparecer.
6. **Si el `boot` parchado fue el problema**: vuelve a parchar el AP con la
   app de Magisk (quizá otra versión de Magisk, p. ej. una beta canary) y
   verifica el flag «Ramdisk: Sí/No» **antes** de parchar.

**Nunca:**
- Dejar el cel sin internet en el primer arranque post-desbloqueo.
- Correr `samloader flash` sin verificar el **MD5** del firmware descargado.
- Modificar `/system` real tras rootear: Magisk es `systemless` por diseño,
  los módulos van a `/data/adb/modules`; tocar `/system` dispara `dm-verity`
  o rompe las verificaciones de arranque.

## 6. Reparto humano ↔ agentes y método de diagnóstico

Esta guía existe porque un equipo chico (una persona dueña del celular y
varios agentes de IA en paralelo) atacó el bootloop y lo resolvió en una
sola sesión. Este reparto es útil **para quien replique**: con mucha
probabilidad vas a tropezar con los mismos síntomas, y la forma de pensarlos
y debuggearlos es parte del valor de esta guía.

### 6.1 Reparto físico vs digital (quién hace qué)

| Rol | Qué le toca | Qué NUNCA le toca al otro |
|---|---|---|
| **Persona** (dueña del cel) | Combos de botones (`Vol+ Vol−` + USB), confirmar cada borrado, aceptar «Permitir siempre» en el cel, bajar binarios externos (**Magisk**, **samloader-rs**, **`avbtool.py`**) desde sus repos oficiales, flashear con `samloader` desde Terminal, leer qué sale en pantalla del cel y describirlo con palabras exactas. Decide los **Sí/No** irreversibles (desbloqueo, borrado, Knox). | No programa, no edita el código de la app, no genera `vbmeta`. |
| **Agente director** (uno) | Diagnóstico por `adb` (`getprop`, `dumpsys`, `cat /proc/...` cuando se puede), preparar los comandos exactos, verificar el estado después de cada paso, decidir qué parche se intenta, coordinar los subagentes, **detenerse** antes de cualquier paso irreversible y pedir confirmación explícita a la persona. | No aprieta botones físicos ni baja los binarios externos. No decide por la persona ninguna acción destructiva. |
| **Agentes consultores en paralelo** (2–3) | Buscar causas en repos y foros (issues de Magisk, samloader-rs, hilos de XDA, código AOSP), proponer hipótesis con evidencia, **debatir entre sí** sin que el director les «imponga» su hipótesis. | No tocan el cel. No ejecutan nada. Solo texto. |
| **Teammate consultor en vivo** (otro modelo, si está disponible) | Afinar un detalle técnico específico cuando el director no está seguro (p. ej., mayúsculas en el nombre del PIT, estructura del tar de Magisk). | Lo mismo que el consultor. |

### 6.2 Lo que a nosotros nos tropezó (y lo que lo resolvió)

Para que al equipo que replique no le pase lo mismo:

| Problema que apareció | Lo que se intentó primero (y falló) | Lo que lo resolvió |
|---|---|---|
| `samloader` termina con `exit 0` pero el log solo lista «PIT flash successful» y después líneas en blanco (saltos de `\r` perdidos) | Reintentar «a ciegas» sin saber qué partición escribió | Capturar la salida real con `script -q -F <archivo> samloader flash …` (en macOS `script` necesita `-F` para preservar los `\r`); así se ve si BL/AP/CP/CSC se escribieron. Quitar `--repartition` del comando. |
| Pre-flight `CheckSuperSize insufficient free space` | Pasar `--skip-size-check`, `--erase` | **Workaround issue #43 de samloader-rs** (§2.3 de esta guía): un `.tar` extra con `meta-data/super_used_size.txt` que contenga el carácter `0`. Un **subagente consultor** encontró el issue leyendo `odin/src/flash.rs`. |
| Cel llega a «Powered by Android» y cae a stock recovery, loop | Factory reset desde recovery (**Prueba A** §2.1) | **Prueba B** §2.2: `vbmeta_disabled` generado con `avbtool.py` de AOSP y flasheado solo a la partición `VBMETA`. **Otro subagente consultor** encontró el issue **#6230** de Magisk (vbmeta encadenadas no re-firmadas) y propuso el parche. |
| `samloader flash -p vbmeta` acaba casi al instante y el flash «no imprime» que haya tocado vbmeta | Asumir que falló y relanzar | El **teammate consultor en vivo** avisó: el nombre de la partición en el PIT va en **mayúsculas** (`VBMETA`), no `vbmeta`. El flash sí había pasado, pero quería escribir en una partición que el PIT no lista con ese nombre. |
| Celular en bootloop a recovery que no se puede apagar con `Power` normal (el USB le da corriente al bootloader) | Mantener `Power` sostenido, forzar reinicios | **Truco del fastboot verde** §4: Reboot to bootloader → fastboot verde → `Power off`. En fastboot sí se apaga de verdad porque no intenta arrancar sistema. |
| Combo `Vol+ Vol−` + USB no entra a Download Mode y cae a recovery | Repetir el combo | Las dos teclas tienen que estar presionadas **antes** de conectar el cable (no después). Si lo haces después, el cel ya empezó a arrancar y aborta al modo descarga. |
| Touch del Welcome no responde al primer boot | Pensar «se jodió el flasheo» | Normal: el launcher todavía está cargando. Deslizar en lugar de tocar; mantener `Power` sostenido para apagado profundo y volver a encender si no destraba. |
| El clasificador de seguridad del agente director bloquea bajar `avbtool.py` | — | La persona lo baja y lo ejecuta; es un script Python de Google en los repos oficiales de AOSP. Los agentes **no** tocan binarios externos del rooteo. |

### 6.3 Patrones de diagnóstico que sí funcionaron

- **Dos consultores en paralelo con hipótesis distintas** sobre el mismo
  síntoma. Uno asume que el culpable es el `super.img` (metadata LPDT
  corrupta); otro asume que es `vbmeta` (hashtree enforce). El director
  escucha a los dos, descarta el barato primero (Prueba A), y aplica el
  parche del que haya traído evidencia de código leído (no hipótesis sueltas).
- **Nunca mandar el parche irreversible primero.** Prueba A (factory reset)
  es gratis. Si no funciona, Prueba B (`vbmeta_disabled`) solo toca 4 KB.
  Si no funciona, Plan C (reflashear completo). Si no funciona, Plan D
  (replan: ¿cambió el firmware?). Nunca saltar niveles.
- **Leer el log del flash de verdad.** `exit 0` no significa que flasheó;
  significa que no crasheó. Las líneas de progreso (`\r`) de samloader se
  pierden sin `script -F`. Si no sabes qué escribió, no sabes qué parchar.
- **Describir con palabras exactas lo que ve el cel.** «Pantalla naranja»
  no es lo mismo que «pantalla azul»; «se reinició» no es lo mismo que
  «llegó al logo y se quedó». El agente no ve la pantalla; solo tiene las
  palabras de la persona. Fotos cuando se pueda.
- **No improvisar bajo presión.** Si el cel está en recovery loop, se puede
  dejar enchufado y quieto mientras el equipo busca y debate. No pasa nada
  por esperar; cada reintento mal dado puede empeorar las cosas.

### 6.4 Fuentes verificables

- AOSP, `platform/external/avb`:
  <https://android.googlesource.com/platform/external/avb/>
  (y la referencia a `AvbVBMetaImageFlags` en el header `libavb/avb_vbmeta_image.h`).
- Magisk, guía de instalación oficial:
  <https://topjohnwu.github.io/Magisk/install.html>.
- Magisk, repo e issues: <https://github.com/topjohnwu/Magisk>
  (**issue #6230**: vbmeta encadenadas no re-firmadas).
- samloader-rs, repo e issues: <https://github.com/topjohnwu/samloader-rs>
  (**issues #43 y #51**: `super_used_size` y `CheckSuperSize`).
- SamMobile (firmwares oficiales de Samsung): <https://www.sammobile.com/>.
- XDA Developers (hilos del A72 y de la familia Snapdragon moderna):
  <https://xdaforums.com/>.

Nada de esto se publica para «vencer protecciones» del fabricante: todo
está en los repos oficiales, se usa **sobre un celular que es tuyo** y
el precio son los avisos de §4 del README y de la LICENSE (borrado,
Knox quemado, pérdida de garantía, apps bancarias/pagos que se niegan).
Si no estás dispuesto a eso, no sigas; el camino sin root también está
documentado — simplemente deja de haber «ruta raíz» en la app.
