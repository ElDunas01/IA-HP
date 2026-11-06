<!-- 🌐 REPO PÚBLICO · agente-isabella · versión para redes, SIN llaves ni datos privados. El laboratorio privado de trabajo es otro repositorio. -->

<picture>
  <source media="(max-width: 767px)" srcset="https://raw.githubusercontent.com/viajatech/agente-isabella/main/docs/img/presentacion/hero-movil.svg">
  <img alt="Agente Isabella — la IA que hace llamadas reales desde tu propio Android. Creado por David Ruiz Duarte (viajatech)." src="docs/img/presentacion/hero.svg" width="100%">
</picture>

<p align="center">
  <a href="https://www.youtube.com/shorts/oSUbPbwd6TY"><img src="docs/img/presentacion/btn-video.svg" height="44" alt="Ver el video"></a>
  <a href="#instalar"><img src="docs/img/presentacion/btn-instalar.svg" height="44" alt="Instalar"></a>
  <a href="#como-funciona"><img src="docs/img/presentacion/btn-como.svg" height="44" alt="Cómo funciona"></a>
  <a href="docs/BOOT_RECOVERY.md"><img src="docs/img/presentacion/btn-rescate.svg" height="44" alt="Si no arranca"></a>
</p>

<h3 align="center">Tu celular. Tu SIM. Tu llamada. La voz es de Isabella.</h3>

<p align="center">
  Una app Android que marca desde <b>tu propia línea</b> y conversa en tiempo real con la voz en vivo de <b>Google Gemini</b>.<br>
  Sin Twilio, sin números rentados, sin VoIP de terceros.
</p>

<p align="center">
  <img alt="Android 14+" src="https://img.shields.io/badge/Android-14%2B-1FE03C?style=for-the-badge&logo=android&logoColor=white&labelColor=0E0D14">
  <img alt="Gemini Live, voz Zephyr" src="https://img.shields.io/badge/Gemini_Live-voz_Zephyr-FF2A6D?style=for-the-badge&logo=googlegemini&logoColor=white&labelColor=0E0D14">
  <img alt="Root con Magisk" src="https://img.shields.io/badge/root-Magisk-6C5CFF?style=for-the-badge&labelColor=0E0D14">
  <img alt="Cloudflare Workers" src="https://img.shields.io/badge/Cloudflare-Workers-F38020?style=for-the-badge&logo=cloudflare&logoColor=white&labelColor=0E0D14">
</p>

> **🌐 Repositorio público y limpio** — sin llaves, sin tokens y sin datos privados.
> Para que funcione, **tú pones tus propias llaves** (se explica paso a paso).

## 📞 ¿Qué es esto?

**Isabella Llamadas** es una app Android que marca un número desde **tu propia línea** y conversa en tiempo real con
la persona que contesta, usando la voz en vivo de **Google Gemini**.

La voz de Isabella entra **digitalmente** al canal de la llamada (no por la bocina), así que se oye limpia y sin eco.
Para eso el celular necesita **root con Magisk**: Android no deja que una app normal meta audio a una llamada.

<p align="center">
  <img alt="Ejemplo de llamada de prueba: Isabella saluda diciendo que es una inteligencia artificial, platica y cuelga sola." src="docs/img/presentacion/llamada.svg" width="340">
</p>
<p align="center"><sub>Ejemplo de una llamada de prueba. La primera frase de Isabella <b>siempre</b> dice que es una inteligencia artificial.</sub></p>

| Lo que hace | Cómo |
|---|---|
| 🗣️ Conversa en vivo por teléfono | Gemini Live por WebSocket, audio PCM 16/24 kHz |
| 🔐 Tus llaves nunca viven en el cel | Un Worker de Cloudflare (gratis) entrega tokens efímeros de un solo uso |
| 🤖 Se presenta SIEMPRE como IA | Primera frase obligatoria: «Soy una inteligencia artificial…» |
| ⏱️ No gasta de más | 8 fusibles: techo por llamada, topes por minuto/día, cuelga en silencio, nunca reconecta… |
| 📵 Contra toques accidentales | Confirmación de 3 s antes de marcar |
| 🎚️ Audio afinable | Volumen TTS, pre-énfasis, filtro de graves, soft-clip |
| 💬 Modo sin llamada | Platica con Isabella por el micrófono y la bocina del cel (no requiere root) |
| 🧪 Pruebas sin gastar | Gemini falso y Worker local para probar todo en la Mac |

## 🎬 Míralo en acción

<p align="center">
  <a href="https://www.youtube.com/shorts/oSUbPbwd6TY"><img alt="Ver el video «Agentes autónomos que marcan por ti» en YouTube Shorts" src="docs/img/presentacion/video.svg" width="100%"></a>
</p>

<a id="como-funciona"></a>

## ⚙️ Cómo funciona

<picture>
  <source media="(max-width: 767px)" srcset="https://raw.githubusercontent.com/viajatech/agente-isabella/main/docs/img/presentacion/como-funciona-movil.svg">
  <img alt="Diagrama: tu Worker de Cloudflare guarda la llave y entrega tokens de un solo uso; el celular conversa con Gemini Live y mete la voz de Isabella a la llamada VoLTE por tu SIM; la Mac solo compila, instala y configura." src="docs/img/presentacion/como-funciona.svg" width="100%">
</picture>

1. **La app pide un token a tu Worker de Cloudflare.** El Worker revisa el secreto del aparato y los topes (3 por minuto,
   30 por día). Tu llave de Gemini vive **solo** ahí.
2. **Google entrega un token efímero de un solo uso**, con la voz y la persona ya amarradas: desde el cel no se pueden cambiar.
3. **El celular abre Gemini Live** con ese token: audio en vivo, voz «Zephyr».
4. **La voz de Isabella entra digital a la llamada** por los mezcladores de audio del celular (root con Magisk): tu SIM, tu línea.

La Mac solo compila, instala y configura. El detalle técnico está en **[docs/ARQUITECTURA.md](docs/ARQUITECTURA.md)**.

<a id="instalar"></a>

## 🚀 Instálalo con tu agente de IA

Este repo está escrito para que **tu agente de IA te guíe** (Claude Code, Codex, Gemini CLI, Copilot, Cursor…):

```bash
git clone https://github.com/viajatech/agente-isabella.git
cd agente-isabella
# Abre aquí tu agente y escríbele:
#   «Lee AGENTS.md y guíame paso a paso para instalar Isabella Llamadas en mi celular.»
```

El agente hace lo de la computadora; **tú** haces lo que solo una persona puede hacer: tocar el celular, apretar los
botones de volumen y encendido, aceptar borrados y escribir tus contraseñas. En cada uno de esos momentos el agente
**se detiene, te explica y espera**.

<picture>
  <source media="(max-width: 767px)" srcset="https://raw.githubusercontent.com/viajatech/agente-isabella/main/docs/img/presentacion/fases-movil.svg">
  <img alt="El camino en fases: 0 diagnóstico, 1 preparar la Mac, 2 preparar el celular (tú), 3 llaves y Worker (tú pegas tu llave), 4 compilar e instalar (ya platica contigo), 5 root con Magisk (tú), 6 configurar la app, 7 primera llamada (tú), 8 afinar el audio." src="docs/img/presentacion/fases.svg" width="100%">
</picture>

<p align="center"><sub>Todo el detalle de cada fase está en <a href="AGENTS.md"><b>AGENTS.md</b></a>.</sub></p>

### Lo que necesitas

| | |
|---|---|
| 📱 Un celular Android **14 o más nuevo** que puedas rootear | Probado en **Samsung Galaxy A72** (Qualcomm). Úsalo de pruebas: el root **borra todo**. |
| 💻 Una Mac (Apple Silicon o Intel) | Los scripts están hechos para macOS + Homebrew. |
| 🔌 Un cable USB **de datos** | Uno que solo carga no sirve. |
| 🔑 Tu llave de **Google AI Studio** | Se crea gratis en <https://aistudio.google.com/apikey>. Gemini tiene nivel gratuito con límites; algunos usos pueden cobrarse: revisa <https://ai.google.dev/pricing>. |
| ☁️ Una cuenta de **Cloudflare** | Gratis. Ahí vive el Worker que guarda tu llave. |
| 📞 Un segundo teléfono **tuyo** | Para las pruebas: Isabella llama a ese número. |

## 🔐 Dónde van TUS llaves (y dónde NO)

| Qué | Dónde va | Cómo |
|---|---|---|
| Llave de Gemini (AI Studio) | **Solo** en el Worker de Cloudflare | `npx wrangler secret put GEMINI_API_KEY_AQ` (la pegas tú en tu Terminal) |
| Secreto del aparato | En el Worker y en la app | `./configurar_app.sh` lo genera y lo pasa por tubería, sin mostrarlo |
| URL de tu Worker | `mi_config.env` (local, fuera de git) | `cp mi_config.env.example mi_config.env` |
| Llave de firma del APK | `llaves/` (local, fuera de git) | `./compilar.sh` la crea la primera vez; su contraseña va al Llavero de macOS |

> ⚠️ **Nunca** pegues una llave en un chat, en un archivo que vaya a git ni en la línea de `adb shell`.
> Antes de publicar tu copia, corre `python3 herramientas/escanear_secretos.py .`

## 🛟 ¿Flasheaste y no arranca?

Si después del root el celular cae en bucle a recovery, o samloader se atora en el pre-flight,
**[La odisea del boot](docs/BOOT_RECOVERY.md)** junta los parches reales que revivieron nuestro Galaxy A72:

- un `vbmeta` desactivado de 4 KB hecho con `avbtool` de AOSP;
- el atajo para el issue #43 de samloader-rs;
- el truco del fastboot verde para apagar un cel atorado en bucle;
- y quién hace qué entre la persona y los agentes mientras el cel está en el limbo.

<details>
<summary><b>🗂️ Estructura del repo</b></summary>

```
apps/isabellallamadas/   App Android (Java, sin Gradle): llamada, audio raíz, Gemini Live, UI
  magisk_modulo/         Módulo Magisk opcional (app privilegiada)
  pruebas_mac/           Pruebas de la app en la Mac, sin cel ni cuota
token-worker/            Worker de Cloudflare: guarda tu llave y acuña tokens efímeros
puente/                  Persona de Isabella (EDITABLE), generador de setup y Gemini falso
herramientas/            Diagnóstico de la ruta raíz (prueba 0r) y escáner de secretos
compilar.sh · instalar.sh · preparar_mac.sh · vincular_cel.sh · configurar_app.sh
docs/                    Arquitectura, problemas resueltos y rescate si el cel no arranca tras el root
  img/                   Arte del proyecto, gráficos de esta portada y la vista previa para redes
```

</details>

## 🎨 Personaliza a Isabella

Edita la sección **«TU CONFIGURACIÓN»** de [`puente/lib/isabella.mjs`](puente/lib/isabella.mjs): a nombre de quién
llama, qué sabe (tu negocio, tu proyecto) y la voz. Luego `cd token-worker && npm run genera-setup && npx wrangler deploy`.
El celular no se toca: la persona viaja dentro de cada token.

## 🤝 Uso responsable

Isabella **siempre** dice que es una IA. Llama **solo** a números tuyos o de personas que aceptaron. Nada de spam,
fraude, suplantación ni grabaciones sin aviso. Lee **[USO_RESPONSABLE.md](USO_RESPONSABLE.md)**: el mal uso revoca la
licencia.

## 📜 Licencia y créditos

<img alt="Licencia: no comercial y con atribución" src="https://img.shields.io/badge/licencia-no_comercial_%2B_atribuci%C3%B3n-FF2A6D?style=for-the-badge&labelColor=0E0D14">

**Licencia Agente Isabella 1.0** — atribución obligatoria · uso no comercial · uso responsable ([LICENSE](LICENSE)).
Si lo usas, lo compartes o haces un video, da crédito:

> **Agente Isabella — creado por David Ruiz Duarte (viajatech)** · <https://viajatech.com> · <https://github.com/viajatech/agente-isabella>

Sin garantía: rootear y flashear puede borrar tus datos o dañar tu equipo. No nos hacemos responsables del uso que le des.

El arte y los gráficos de este repositorio (`docs/img/`) son de **viajatech** y quedan cubiertos por la misma licencia:
no los uses para otra cosa ni les quites el crédito.

<br>

<picture>
  <source media="(max-width: 767px)" srcset="https://raw.githubusercontent.com/viajatech/agente-isabella/main/docs/img/presentacion/pie-movil.svg">
  <img alt="Hecho en México por David Ruiz Duarte · viajatech" src="docs/img/presentacion/pie.svg" width="100%">
</picture>

<p align="center">
  <a href="https://viajatech.com"><img alt="viajatech.com" src="https://img.shields.io/badge/viajatech.com-0E0D14?style=for-the-badge&logo=googlechrome&logoColor=FF2A6D"></a>
  <a href="https://viajatech.com/isabella"><img alt="Isabella en vivo" src="https://img.shields.io/badge/Isabella_en_vivo-0E0D14?style=for-the-badge&logo=googlegemini&logoColor=1FE03C"></a>
  <br>
  <a href="https://www.youtube.com/@viajatech"><img alt="YouTube" src="https://img.shields.io/badge/YouTube-0E0D14?style=for-the-badge&logo=youtube&logoColor=FF2A6D"></a>
  <a href="https://www.instagram.com/viajatech/"><img alt="Instagram" src="https://img.shields.io/badge/Instagram-0E0D14?style=for-the-badge&logo=instagram&logoColor=FF2A6D"></a>
  <a href="https://www.tiktok.com/@viajatech"><img alt="TikTok" src="https://img.shields.io/badge/TikTok-0E0D14?style=for-the-badge&logo=tiktok&logoColor=FF2A6D"></a>
  <a href="https://x.com/viajatech"><img alt="X" src="https://img.shields.io/badge/X-0E0D14?style=for-the-badge&logo=x&logoColor=FF2A6D"></a>
  <a href="https://www.threads.com/@viajatech"><img alt="Threads" src="https://img.shields.io/badge/Threads-0E0D14?style=for-the-badge&logo=threads&logoColor=FF2A6D"></a>
  <a href="https://github.com/viajatech"><img alt="GitHub" src="https://img.shields.io/badge/GitHub-0E0D14?style=for-the-badge&logo=github&logoColor=FF2A6D"></a>
</p>

<p align="center"><b>⭐ Si te gustó, regálale una estrella al repo: así le llega a más gente.</b></p>

---

<details>
<summary><b>English summary</b></summary>

**Agente Isabella** turns a rooted Android phone (Android 14+) into an AI voice assistant that places **real phone
calls** over your own SIM, powered by **Google Gemini Live** (voice "Zephyr"). Audio is injected digitally into the
call through the Qualcomm audio mixers via Magisk root (`tinyplay`/`tinycap`), so there's no echo and no speakerphone.
Your Gemini API key never touches the phone: a free **Cloudflare Worker** stores it and mints single-use ephemeral
tokens. The app is built **without Gradle or Android Studio** (aapt2 → javac → d8 → apksigner) on macOS.
Clone it, open your AI coding agent and say: *"Read AGENTS.md and guide me step by step."* Bring your own keys.
Non-commercial use with mandatory attribution to **David Ruiz Duarte (viajatech)**; misuse revokes the license.
Tested on a Samsung Galaxy A72 (Snapdragon); other phones may need mixer adaptation (see `docs/ARQUITECTURA.md`).

Watch it in action: <https://www.youtube.com/shorts/oSUbPbwd6TY>

Keywords: AI phone call agent, Gemini Live Android, AI voice assistant phone calls, Magisk root audio injection,
VoLTE AI caller, Android without Gradle, Cloudflare Worker ephemeral tokens, vibe coding.
</details>
