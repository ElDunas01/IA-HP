# AGENTS.md — Guía para agentes de IA (Claude Code, Codex, Gemini CLI, Copilot, Cursor…)

> **Agente Isabella** — creado por **David Ruiz Duarte (viajatech)** · https://viajatech.com · Licencia en `LICENSE`.
> Conserva los avisos de autoría en todo lo que generes o modifiques.

Tu trabajo: guiar a una persona —muchas veces **sin experiencia técnica**— para instalar **Isabella Llamadas** en
**su** celular Android, con **sus** llaves. Lee este archivo completo antes de ejecutar nada. Habla claro, en el idioma
de la persona, con pasos cortos, y confirma cada resultado antes de seguir.

---

## 0. Reglas que no se negocian

1. **Llaves y secretos: nunca a la vista.** No pidas que te peguen llaves en el chat, no hagas `cat` de `secretos/`,
   no las escribas en archivos versionados ni en la línea de `adb shell` (`adbd` registra los argumentos). La llave de
   Gemini la pega **la persona en su propia Terminal** (fase 3).
2. **🛑 Pasos humanos.** Cuando algo requiera tocar el celular, apretar botones físicos, aceptar un borrado, escribir
   una contraseña o bajar/ejecutar programas de internet (Magisk, samloader-rs): **detente**, explica qué va a pasar y
   **espera su confirmación**. No supongas que ya lo hizo.
3. **Borrados = permiso explícito.** Desbloquear el gestor de arranque **borra todo el celular** y en Samsung quema el
   fusible Knox **para siempre**. Pide que lo confirme con sus palabras («sí, entiendo que se borra todo») y sugiere
   respaldar antes (fotos, WhatsApp, contactos).
4. **Solo números con permiso.** Isabella llama únicamente a números de la persona o de gente que aceptó. Nunca quites
   la presentación como IA ni los topes de gasto.
5. **Sin tokens por línea de comandos.** Usa siempre el Worker (`config --es tokenurl …`); nada de `--es token <…>`.
6. **Calma en los momentos tensos.** Si el celular muestra pantallas de texto, menús de arranque o reinicia varias
   veces: pide una **foto** de la pantalla, lee lo que dice antes de proponer nada y da **una sola instrucción a la vez**.
   No inventes combinaciones de botones: consulta la documentación oficial del modelo.
7. **Atribución.** Si la persona publica su copia o un video, recuérdale el crédito de la sección 3 de `LICENSE`.

## 1. Mapa del proceso

| Fase | Qué | Quién | Tiempo aprox. |
|---|---|---|---|
| 0 | Diagnóstico de la Mac y del celular | Agente | 5 min |
| 1 | Preparar la Mac (Homebrew, Java 17, SDK, adb, Node) | Agente (+ contraseña de la Mac: 🛑 persona) | 15–30 min |
| 2 | Preparar el celular: modo desarrollador y depuración USB | 🛑 Persona, guiada | 5 min |
| 3 | Llave de Gemini + Worker de Cloudflare | Agente + 🛑 persona (cuentas, login, pegar llave) | 15 min |
| 4 | Compilar e instalar la app; platicar con Isabella **sin llamada** | Agente | 5 min |
| 5 | Root con Magisk | 🛑 Persona, con el agente acompañando | 1–3 h |
| 6 | Configurar la app (secreto, Worker, permisos) | Agente | 5 min |
| 7 | Prueba de la ruta raíz y primera llamada | 🛑 Persona (contesta su otro teléfono) | 10 min |
| 8 | Afinar el audio | Agente + persona | 10 min |

## 2. Fase 0 — Diagnóstico

```bash
sw_vers; uname -m                      # macOS y arquitectura (arm64 = Apple Silicon)
command -v brew adb java npx || true   # qué ya existe
```
Pregunta: **marca y modelo exacto del celular** (Ajustes › Acerca del teléfono) y **versión de Android**.
- Android **menor a 14** → la app no instala (minSdk 34). Detente y explícalo.
- Probado en **Samsung Galaxy A72 (SM-A725M, Qualcomm)**. Otros Qualcomm suelen tener mezcladores parecidos; MediaTek o
  Exynos pueden requerir adaptación (ver `docs/ARQUITECTURA.md`). Dilo con honestidad antes de la fase 5.
- Recomienda usar un **celular de pruebas**, no el de uso diario.

## 3. Fase 1 — Preparar la Mac

```bash
./preparar_mac.sh --verificar            # revisa sin instalar
./preparar_mac.sh --acepto-licencia      # instala lo que falte (acepta la licencia del SDK de Android: pregúntale primero)
```
- Si falta **Homebrew**: la persona lo instala desde <https://brew.sh> en **su** Terminal (pide la contraseña de la Mac). 🛑
- Instala Java 17 (Temurin) a nivel usuario, `adb`, las herramientas de línea de comandos de Android (plataforma 34 y
  build-tools 34.0.0) y Node.js. **No hace falta Android Studio.**

## 4. Fase 2 — Preparar el celular 🛑

Guía a la persona, una cosa a la vez:
1. **Modo desarrollador:** Ajustes › Acerca del teléfono › Información de software › tocar **Número de compilación 7
   veces** (pide el PIN). Aparece «Opciones de desarrollador».
2. **Depuración USB:** Ajustes › Opciones de desarrollador › **Depuración USB** › activar.
3. Conectar el **cable USB de datos** a la Mac. En el celular aparece **«¿Permitir depuración USB?»** → marcar
   «Permitir siempre desde esta computadora» › **Permitir**.
4. Verifica:
   ```bash
   adb devices -l        # debe decir «device» (si dice «unauthorized», revisar el aviso en el cel)
   ```
5. (Opcional) Wi-Fi: Opciones de desarrollador › Depuración inalámbrica › «Vincular con código» → `./vincular_cel.sh <código>`.

Si hay dos celulares conectados, usa `CEL=<serie>` delante de los scripts (la serie sale en `adb devices`).

## 5. Fase 3 — Llave de Gemini y Worker de Cloudflare

La llave de Gemini vive **solo** en el Worker. El celular recibe tokens efímeros de un solo uso.

1. 🛑 **Llave:** la persona entra a <https://aistudio.google.com/apikey> con su cuenta de Google › «Create API key».
   Que **no** te la pegue: la usará en el paso 5. Avísale que Gemini tiene nivel gratuito con límites y que algunos usos
   pueden cobrarse (<https://ai.google.dev/pricing>). Que respete los límites y términos de Google.
2. 🛑 **Cloudflare:** cuenta gratis en <https://dash.cloudflare.com/sign-up>.
3. Instala dependencias y entra a Cloudflare:
   ```bash
   cd token-worker && npm install
   npx wrangler login                     # 🛑 abre el navegador; la persona autoriza
   npx wrangler kv namespace create TOPES # copia el "id" que devuelve
   ```
   Pega ese id en `token-worker/wrangler.jsonc` en lugar de `PEGA_AQUI_EL_ID_DE_TU_KV`.
4. (Opcional) Personaliza a Isabella en `puente/lib/isabella.mjs` (sección «TU CONFIGURACIÓN») y regenera:
   `npm run genera-setup`. Prueba local sin llaves reales: `cd ../puente && npm install && cd ../token-worker && npm run prueba`.
5. 🛑 **La persona sube su llave en SU Terminal** (Terminal.app, no a través de ti: el comando pide la llave de forma
   oculta y sin una terminal real lee vacío):
   ```bash
   cd <ruta>/agente-isabella/token-worker
   npx wrangler secret put GEMINI_API_KEY_AQ     # pega la llave y Enter (no se ve al pegar: es normal)
   ```
6. Despliega y anota la URL:
   ```bash
   npx wrangler deploy        # muestra https://agente-isabella-token.<subdominio>.workers.dev
   ```
7. Crea la configuración local:
   ```bash
   cd .. && cp mi_config.env.example mi_config.env
   ```
   Llena `WORKER_URL` (la URL de arriba + `/token`), `NUMERO_PRUEBA` y `NUMEROS_PERMITIDOS` (números **de la persona**).
   El secreto del aparato lo genera y lo sube `configurar_app.sh` en la fase 6.

## 6. Fase 4 — Compilar e instalar

```bash
./compilar.sh isabellallamadas     # sin Gradle: aapt2 → javac → d8 → zipalign → apksigner
./instalar.sh isabellallamadas     # adb install -r + permisos de tiempo de ejecución
```
- La primera vez, `compilar.sh` crea la **llave de firma de la persona** en `llaves/` y guarda su contraseña en el
  **Llavero de macOS**. Si tu entorno no te deja escribir en el Llavero, pide a la persona que corra `./compilar.sh`
  en su Terminal. Recuérdale **respaldar `llaves/`**: sin ella no podrá actualizar la app instalada.
- Corre la fase 6 (`./configurar_app.sh`) y luego prueba **sin llamada** (no requiere root): la persona habla y Isabella
  contesta por la bocina del celular:
  ```bash
  adb shell am start-foreground-service -n com.viajatech.lab.isabellallamadas/.LlamadaService --es cmd isabella
  adb shell am start-foreground-service -n com.viajatech.lab.isabellallamadas/.LlamadaService --es cmd colgar
  ```
  Bitácora en vivo: `adb logcat -s IsabellaLlamadas`.

## 7. Fase 5 — Root con Magisk 🛑

El root lo hace **la persona**, con el celular en la mano; tú la acompañas, explicas y verificas. Antes de empezar:
- Confirma que entendió los riesgos (sección 8 de `LICENSE`): **borra todo**, anula la garantía, en Samsung **quema
  Knox para siempre** y puede romper apps bancarias o de pagos. Respaldo hecho, batería arriba de 60 %, cable de datos.
- Fuente principal: **la guía oficial de instalación de Magisk** — <https://topjohnwu.github.io/Magisk/install.html>
  (tiene una sección específica para Samsung). Síguela para el modelo exacto de la persona.
- Lo que usamos en nuestro equipo de pruebas (Galaxy A72, Android 14): **Magisk v31.1** para parchar el firmware oficial
  y **samloader-rs** (del mismo autor de Magisk) para bajar el firmware oficial de Samsung y flashearlo desde la Mac.
  Esos programas los baja y ejecuta **la persona** desde sus repositorios oficiales:
  <https://github.com/topjohnwu/Magisk/releases> · <https://github.com/topjohnwu/samloader-rs>.
- Reglas que nos funcionaron: transferir archivos grandes con `adb push`/`adb pull` (no por MTP); verificar la suma de
  control del firmware antes de flashear; flashear siempre el firmware **completo** (nunca particiones sueltas); tener
  **Wi-Fi listo** en el primer arranque después de desbloquear. Si el celular se queda en menús de arranque, aplica la
  regla 6 de la sección 0 y consulta la guía oficial; no improvises.
- **Si tras el flasheo el celular cae en bucle a recovery, o samloader se cuelga en el pre-flight:**
  **`docs/BOOT_RECOVERY.md`** recoge los parches que nos funcionaron (vbmeta disabled de 4 KB con `avbtool` de
  AOSP, workaround del issue #43 de samloader-rs, truco del fastboot verde para apagar un cel en bootloop).
  Pasos físicos: 🛑 persona.

Verificación al terminar:
```bash
adb shell su -c id      # esperado: uid=0(root) … (Magisk pregunta en el cel la primera vez: 🛑 «Permitir»)
```
Después del root: reinstala la app (`./instalar.sh isabellallamadas`) y corre la fase 6 otra vez.

## 8. Fase 6 — Configurar la app

```bash
./configurar_app.sh
```
Genera el **secreto del aparato** (si no existe) en `secretos/` sin mostrarlo, lo sube al Worker
(`wrangler secret put LAB_DEVICE_SECRET` por tubería), lo importa al celular por tubería, guarda la URL del Worker,
el techo y el modo, concede el permiso especial `MANAGE_ONGOING_CALLS` y mantiene la pantalla despierta con USB.
- Ojo: **cada `adb install -r` quita el permiso `MANAGE_ONGOING_CALLS`**. Tras reinstalar, vuelve a correr
  `./configurar_app.sh`.

## 9. Fase 7 — Prueba de la ruta raíz y primera llamada 🛑

1. **Prueba 0r (sin la app ni Gemini):** comprueba que el audio entra y sale de la llamada por los mezcladores.
   ```bash
   herramientas/llamadas/prueba_0r.sh
   ```
   La persona marca a **su** otro teléfono desde la app de teléfono, contesta y escucha un tono. El script guarda un
   reporte en `herramientas/llamadas/registros/`.
2. **Primera llamada con Isabella** (con el «adelante» de la persona):
   - En la app: escribir el número, prender **«Modo RAÍZ»**, tocar **«Llamar con Isabella»** y confirmar con **«Sí…»**
     antes de 3 s.
   - O desde la Mac:
     ```bash
     source mi_config.env
     adb shell am start-foreground-service -n com.viajatech.lab.isabellallamadas/.LlamadaService \
       --es cmd llamar --es numero "$NUMERO_PRUEBA" --es raiz 1 --es saludar 1
     ```
   - Colgar: botón «Colgar» o `--es cmd colgar`. Cuando cualquiera cuelga, Gemini se cierra en menos de un segundo.

## 10. Fase 8 — Afinar el audio

Combinación que mejor nos sonó (en la app, «Audio avanzado», o por `--es`): `ganancia_tts 45`, `preenfasis 12`,
`hpf_hz 100`. Si suena saturada: baja `preenfasis` a 8 o `ganancia_tts` a 40. **Nunca** actives `mute_mic_fix` en un
A72: silencia la voz de Isabella hacia la llamada. Detalle en `docs/ARQUITECTURA.md`.

## 11. Si algo falla

Lee `docs/PROBLEMAS_Y_SOLUCIONES.md` (35 tropiezos reales con su causa y su arreglo). Lo más común:
`adb devices` vacío (cable de solo carga o falta autorizar), el permiso especial perdido tras reinstalar, la pantalla
bloqueada que desconecta adb (`adb shell svc power stayon usb`), y el Worker sin la llave (`sin_llave`).

## 12. Al terminar

- Revisa que no haya secretos antes de cualquier `git push`: `python3 herramientas/escanear_secretos.py .`
- Recuerda a la persona el crédito: **«Agente Isabella — creado por David Ruiz Duarte (viajatech)»**.
