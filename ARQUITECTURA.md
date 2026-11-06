# Arquitectura de Agente Isabella

> Agente Isabella — creado por David Ruiz Duarte (viajatech) · https://viajatech.com

## Vista general

```
 Mac (solo para compilar/instalar)        Cloudflare Worker (tuyo)          Google Gemini Live
 adb · compilar.sh · instalar.sh          guarda GEMINI_API_KEY_AQ          gemini-3.8-live
            │ USB                         acuña tokens de 1 uso   ──HTTPS──▶ voz «Zephyr» 24 kHz
            ▼                                    ▲                                ▲
 ┌─────────────────────────────┐   POST /token   │                                │ WebSocket (TLS)
 │ Celular Android 14+ con root│─────────────────┘                                │
 │ app Isabella Llamadas       │──────────────────────────────────────────────────┘
 │ (Modo RAÍZ: tinyplay/tinycap│
 │  vía su, Magisk)            │──── llamada VoLTE por tu SIM ───▶ tu otro teléfono
 └─────────────────────────────┘
```

## Subida: voz de Isabella → llamada
```
Gemini Live (PCM16 24 kHz) → SesionGemini (WebSocket) → LlamadaService (cola de voz)
 → ganancia + soft-clip tanh [+ pre-énfasis + filtro de graves opcionales]
 → SumideroRaiz → marcapasos de 20 ms → stdin de:
   su -c "tinymix 'Incall_Music Audio Mixer MultiMedia2' 1 && exec timeout 600 tinyplay /dev/stdin -D 0 -d 1"
 → caso de uso INCALL_MUSIC_UPLINK → la red → tu otro teléfono
```

## Bajada: voz de quien contesta → Gemini
```
la red → módem → VOC_REC_DL → MultiMedia9 → PCM hw:0,23
 → su -c "tinymix 'MultiMedia9 Mixer VOC_REC_DL' 1 && exec timeout 180 tinycap <FIFO> -D 0 -d 23 -c 1 -r 16000 -b 16 -T 7200"
 → FuenteRaiz (tramas de 20 ms, 16 kHz) → medio dúplex (no envía mientras Isabella habla + 500 ms) → Gemini Live
```
Los nombres de los mezcladores y los números de PCM son los del Galaxy A72 (Qualcomm). En otro celular,
`herramientas/llamadas/prueba_0r.sh` los busca por patrón en la salida de `tinymix` y prueba la ruta sin la app.
Si tu equipo no es Qualcomm, probablemente tendrás que investigar sus mezcladores y ajustar `audio/RaizUtil.java`,
`audio/SumideroRaiz.java` y `audio/FuenteRaiz.java`.

## Flujo del token
1. La app manda `POST <worker>/token` con la cabecera `X-Device-Secret` y `{"idioma","max_min","modo"}`.
2. El Worker compara el secreto en tiempo constante, revisa los topes en KV (3/min, 30/día) y elige la variante de
   `setup.json` (modo × idioma × minutos).
3. Pide a Google un token efímero **de un solo uso** con esa configuración amarrada (voz, prompt, función `colgar`, VAD).
4. La app abre `BidiGenerateContentConstrained` con ese token. La persona y la voz no se pueden cambiar desde el cel.

## Los 8 fusibles de gasto
| | Fusible | Qué hace |
|---|---|---|
| a | Estado de la llamada | Si pasa a inactiva antes de contestar, cuelga |
| b | Techo por llamada | 1 a 5 min (por omisión 3) |
| c | Vigilante de 30 s | Sin audio en 30 s, cuelga |
| d | Función `colgar` | Gemini cuelga sola después de despedirse |
| e | Token efímero | Vive unos minutos y sirve una vez |
| f | Topes del Worker | 3 por minuto y 30 por día |
| g | Límite de Google | Las sesiones Live tienen un máximo propio |
| h | Sin reconexión | Si la red cae, la app cuelga; nunca reconecta |

## Palancas de audio (todas opcionales; por omisión = comportamiento probado)
| Palanca | Valores | Efecto |
|---|---|---|
| `ganancia_tts` | décimas, 10–50 (30) | Volumen digital antes del soft-clip |
| `preenfasis` | α×100, 0–… (0) | Realza agudos que el códec telefónico atenúa |
| `hpf_hz` | 0–200 (0) | Filtro de graves: quita retumbe y libera margen |
| `softclip_knee` | (10) | Más alto = más margen antes del recorte suave |
| `mute_mic_fix` | 0 | **No usar en A72**: silencia la voz de Isabella hacia la llamada |

Recomendado para máxima claridad: `ganancia_tts 45 · preenfasis 12 · hpf_hz 100`.

## Cambiar de llave, modelo o proveedor
- **Llave:** `npx wrangler secret put GEMINI_API_KEY_AQ`. El cel no se toca.
- **Modelo o persona:** edita `puente/lib/isabella.mjs` → `npm run genera-setup` → `npx wrangler deploy`.
- **Otro proveedor de voz en vivo:** cambia solo el Worker y conserva el contrato con la app (`POST /token`, respuesta
  `{ok, token, modelo, …}`, audio PCM16 mono 16 kHz de subida / 24 kHz de bajada, función `colgar`, textos «Sistema:»).

## Pruebas sin celular ni cuota
- Worker: `cd puente && npm install && cd ../token-worker && npm install && npm run prueba` (72 verificaciones).
- App: `apps/isabellallamadas/pruebas_mac/correr.sh` (Java 162 + Node 50). Nota honesta: 4 verificaciones estáticas de
  Node se escribieron para la versión 2.6 y no se actualizaron tras el modo raíz (fallan igual en el código original).
