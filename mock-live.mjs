#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
/**
 * Gemini falso, local y sin llaves, para probar sin cuota el cliente crudo del puente, token.mjs, el Worker de tokens y la
 * app. Copia lo que Google hizo de verdad en las sesiones s4, s5 y s6 del 27-oct-2026 (registros en pruebas/s*-crudo.jsonl):
 *   POST /v1beta/auth_tokens            → {"name":"auth_tokens/falso-N", …} (revisa la forma del cuerpo; la llave no se guarda)
 *                                         con la llave «clave-de-fallas»: 429, luego 200 sin name, luego conexión cortada
 *   WebSocket …BidiGenerateContent…      → setupComplete y sessionResumptionUpdate (marcos binarios, como Google) y una
 *     conversación con guion: 1.ª voz → saludo · 2.ª → respuesta larga · 3.ª → corta · 4.ª → despedida + colgar
 *   - La voz se detecta por energía en realtimeInput.audio: voiceActivity ACTIVITY_START al empezar; con el silencio de
 *     fin de turno, {} + inputTranscription + ACTIVITY_END y la respuesta. El silencio es el del token, si lo acuñó este
 *     mock (600 ms en el de la llamada); si no, 800 ms, el que la guía da por omisión.
 *   - La respuesta llega en ráfaga, a unas 7 veces el tiempo real, en trozos de 0.32 s a 24 kHz, con {} intercalados y
 *     outputTranscription en algunos trozos; luego toolCall (si toca colgar) y generationComplete. turnComplete, con el
 *     usageMetadata, llega cuando terminaría de sonar. Si le hablan en ese lapso: ACTIVITY_START, interrupted y turnComplete.
 *   - Los turnos de texto (clientContent o realtimeInput.text) se contestan igual, sin inputTranscription.
 *   - Con un token que acuñó este mock, cierra con 1011 «auth token has expired» al llegar su expireTime, sin goAway.
 *   - goAway solo si se pide (opción goAwayTrasS / --goaway-s): Google no lo mandó en ninguna sesión.
 * No copia (no está medido): la ventana para abrir (newSessionExpireTime), un segundo uso del token, la reanudación, el
 * rechazo de un token desconocido ni los límites de tasa. Acepta cualquier token y cualquier ruta con BidiGenerateContent.
 * Las cifras de usageMetadata son de juguete, pero siguen la regla que se vio en s4 y s6: el audio del prompt crece con lo que
 * Isabella dijo antes y con la voz detectada; el silencio, con ceros o sin tramas, no suma. Registra todo en `stats`.
 *
 *   node mock-live.mjs [--puerto 7398] [--ip 127.0.0.1] [--goaway-s <s>]     (--ip 0.0.0.0 para que el cel lo alcance por Wi-Fi)
 */
import http from "node:http";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";

const require = createRequire(import.meta.url);
const { WebSocketServer } = require("ws"); // cd puente && npm install

const TASA = 24000;
// Fin del turno de quien habla, medido en tiempo de audio (sirve igual con tramas de 20, 40 o 100 ms). Google: «The server's
// internal default is approximately 800ms». Con 480 ms el mock partía en dos la pausa de 520 ms de pruebas/entrada.wav, que
// Gemini de verdad no partió (sesión 4).
const SILENCIO_OMISION_MS = 800;
const TROZO = 15360; // 0.32 s a 24 kHz: un tamaño típico de los trozos de Google
const CADA_MS = 45; // un trozo cada 45 ms ≈ 7 veces el tiempo real, como las ráfagas de s4, s5 y s6
const tono = (s, hz = 330) => {
  const n = Math.round(TASA * s);
  const b = Buffer.alloc(n * 2);
  for (let i = 0; i < n; i++) b.writeInt16LE(Math.round(4000 * Math.sin((2 * Math.PI * hz * i) / TASA)), i * 2);
  return b;
};
const GUION = [
  { s: 1.2, texto: "Hola, buenas, habla Isabella, una asistente de inteligencia artificial. Soy una inteligencia artificial y esta es una llamada de prueba. ¿Me escuchas bien?", oyo: "¿Bueno?" },
  { s: 5, texto: "Agente Isabella convierte un celular Android en una asistente de voz que hace llamadas.", oyo: "Cuéntame qué es Agente Isabella." },
  { s: 1, texto: "Claro: hace sitios web y agentes de IA.", oyo: "¿Me lo dices más corto?" },
  { s: 0.8, texto: "¡Gracias por tu tiempo, hasta luego!", oyo: "Adiós.", colgar: true },
];

function rmsB64(b64) {
  const b = Buffer.from(b64, "base64");
  let acc = 0;
  const n = Math.floor(b.length / 2);
  for (let i = 0; i < n; i++) {
    const v = b.readInt16LE(i * 2);
    acc += v * v;
  }
  return n ? Math.sqrt(acc / n) : 0;
}

export function arrancaMock({ puerto = 7398, ip = "127.0.0.1", log = () => {}, goAwayTrasS = null } = {}) {
  const stats = { tokens: [], conexiones: [], setups: [], audio: 0, audioBytes: 0, textos: [], clientContent: [], toolResponses: [], interrupciones: 0, turnos: 0, marcosCliente: { texto: 0, binario: 0 }, vencidos: 0 };
  const acunados = new Map(); // nombre → { expira, setup }: solo los tokens que acuñó este mock
  let nTok = 0;
  let fallas = 0;
  const srv = http.createServer((req, res) => {
    let cuerpo = "";
    req.on("data", (d) => (cuerpo += d));
    req.on("end", () => {
      if (req.method === "POST" && req.url.split("?")[0] === "/v1beta/auth_tokens") {
        let j = null;
        try {
          j = JSON.parse(cuerpo);
        } catch {}
        const llave = req.headers["x-goog-api-key"];
        const malo = !llave ? "falta x-goog-api-key" : !j ? "cuerpo que no es JSON" : !j.bidiGenerateContentSetup?.model ? "falta bidiGenerateContentSetup.model" : !j.expireTime || !j.newSessionExpireTime ? "faltan expireTime/newSessionExpireTime" : null;
        const prompt = String(j?.bidiGenerateContentSetup?.systemInstruction?.parts?.[0]?.text ?? "");
        stats.tokens.push({
          ok: !malo,
          malo,
          uses: j?.uses,
          expireTime: j?.expireTime,
          newSessionExpireTime: j?.newSessionExpireTime,
          modelo: j?.bidiGenerateContentSetup?.model,
          claves: j ? Object.keys(j) : [],
          setup: j?.bidiGenerateContentSetup ? Object.keys(j.bidiGenerateContentSetup) : [],
          inicioPrompt: prompt.slice(0, 40),
          dura: prompt.match(/(?:La llamada dura máximo|The call lasts at most) ([^.]+)\./)?.[1] ?? null, // el max_min que oye Isabella
          modoPrompt: prompt.match(/^## (?:MODO DE ESTA LLAMADA|MODE OF THIS CALL): (.+)$/m)?.[1] ?? null, // null = servicios
          hashPrompt: createHash("sha256").update(prompt).digest("hex"), // para comprobar la variante exacta que se amarró
          vad: j?.bidiGenerateContentSetup?.realtimeInputConfig ?? null,
          llaveLargo: llave ? String(llave).length : 0,
          t: Date.now(),
        });
        if (llave === "clave-de-fallas") {
          // Para probar el manejo de errores de quien acuña (token-worker/prueba-local.mjs), en este orden:
          // 1.º un 429 con la forma del de Google y la llave dentro del mensaje (quien lo registre debe tacharla),
          // 2.º un 200 sin `name`, 3.º y siguientes: la conexión se corta sin respuesta.
          const n = ++fallas;
          if (n === 1) {
            res.writeHead(429, { "content-type": "application/json" });
            return res.end(JSON.stringify({ error: { code: 429, message: `Resource has been exhausted (e.g. check quota). key=${llave}`, status: "RESOURCE_EXHAUSTED" } }));
          }
          if (n === 2) {
            res.writeHead(200, { "content-type": "application/json" });
            return res.end(JSON.stringify({ uses: j?.uses }));
          }
          return req.socket.destroy();
        }
        if (malo) {
          res.writeHead(400, { "content-type": "application/json" });
          return res.end(JSON.stringify({ error: { code: 400, message: malo, status: "INVALID_ARGUMENT" } }));
        }
        const name = `auth_tokens/falso-${++nTok}`;
        acunados.set(name, { expira: Date.parse(j.expireTime), setup: j.bidiGenerateContentSetup });
        log("mock", `token acuñado ${name} (uses ${j.uses})`);
        // Google devuelve solo `name` (s4, s5, s6); el mock agrega el resto para que las pruebas lo revisen.
        res.writeHead(200, { "content-type": "application/json" });
        return res.end(JSON.stringify({ name, uses: j.uses, expireTime: j.expireTime, newSessionExpireTime: j.newSessionExpireTime }));
      }
      res.writeHead(404, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: { code: 404, message: "no existe en el Gemini falso" } }));
    });
  });
  const wss = new WebSocketServer({ noServer: true });
  srv.on("upgrade", (req, sock, head) => {
    if (!/BidiGenerateContent/.test(req.url)) return sock.destroy();
    wss.handleUpgrade(req, sock, head, (ws) => sesion(ws, req));
  });

  function sesion(ws, req) {
    const u = new URL(req.url, "http://x");
    const con = { ruta: u.pathname.split(".").pop(), token: u.searchParams.get("access_token"), llaveEnCabecera: !!req.headers["x-goog-api-key"], llaveEnUrl: u.searchParams.has("key") };
    stats.conexiones.push(con);
    log("mock", `conexión ${con.ruta}${con.token ? " con access_token" : ""}${con.llaveEnCabecera ? " con llave en cabecera" : ""}`);
    const delToken = con.token ? acunados.get(con.token) : null;
    const timers = new Set();
    const despues = (ms, fn) => {
      const t = setTimeout(() => (timers.delete(t), fn()), ms);
      timers.add(t);
      return t;
    };
    let setup = false;
    let silencioFinMs = SILENCIO_OMISION_MS;
    let enVoz = false;
    let silencio = 0;
    let turno = 0;
    let hablando = null; // { timer, fin, uso }: desde el primer trozo hasta su turnComplete
    let audioEntrada_s = 0; // audio del micrófono recibido (para audioOffset)
    let vozEntrada_s = 0; // solo la voz detectada (para el usageMetadata: el silencio no cuenta)
    let audioSalida_s = 0;
    let vozDesde_s = 0;
    const manda = (o) => ws.readyState === 1 && ws.send(Buffer.from(JSON.stringify(o)), { binary: true }); // Google manda binario
    const offset = (s) => `${s.toFixed(3)}s`;

    if (delToken) {
      // Google cerró 86 ms después del expireTime (s6), con 1011 y sin goAway.
      despues(Math.max(0, delToken.expira - Date.now() + 86), () => {
        stats.vencidos++;
        log("mock", "venció el token: cierro con 1011");
        ws.close(1011, "auth token has expired");
      });
    }
    if (goAwayTrasS !== null) despues(goAwayTrasS * 1000, () => manda({ goAway: { timeLeft: "10s" } }));

    /** usageMetadata con la forma del de Google (de juguete: 25 tokens por s de la voz recibida y de lo que Isabella dijo antes). */
    const uso = (salida_s) => {
      const promptAudio = Math.round(25 * (vozEntrada_s + audioSalida_s));
      const salidaAudio = Math.round(25 * salida_s);
      const promptTexto = 4300 + 20 * turno;
      return {
        promptTokenCount: promptTexto + promptAudio,
        responseTokenCount: salidaAudio,
        totalTokenCount: promptTexto + promptAudio + salidaAudio + 80,
        promptTokensDetails: [{ modality: "TEXT", tokenCount: promptTexto }, { modality: "AUDIO", tokenCount: promptAudio }],
        responseTokensDetails: [{ modality: "AUDIO", tokenCount: salidaAudio }],
        thoughtsTokenCount: 80,
      };
    };
    const responde = () => {
      const g = GUION[Math.min(turno, GUION.length - 1)];
      turno++;
      stats.turnos++;
      const audio = tono(g.s);
      const nTrozos = Math.ceil(audio.length / TROZO);
      const t0 = Date.now();
      let i = 0;
      manda({ serverContent: {} });
      hablando = { fin: t0 + g.s * 1000, s: g.s };
      hablando.timer = setInterval(() => {
        if (i >= nTrozos) {
          clearInterval(hablando.timer);
          if (g.colgar) {
            manda({});
            manda({ toolCall: { functionCalls: [{ name: "colgar", args: {}, id: `call_falso_${turno}` }] } });
          }
          manda({ serverContent: { generationComplete: true } });
          const h = hablando;
          // turnComplete cuando el audio terminaría de sonar (Google lo manda a tiempo real, no al final de la ráfaga).
          h.cierre = despues(Math.max(0, h.fin - Date.now()), () => {
            if (hablando !== h) return;
            hablando = null;
            const u = uso(g.s); // como Google: lo que Isabella dice en este turno entra al prompt del siguiente
            audioSalida_s += g.s;
            manda({ serverContent: { turnComplete: true }, usageMetadata: u });
          });
          return;
        }
        const trozo = audio.subarray(i * TROZO, (i + 1) * TROZO);
        const parte = { serverContent: { modelTurn: { parts: [{ inlineData: { mimeType: "audio/pcm;rate=24000", data: trozo.toString("base64") } }] } } };
        // La transcripción viaja en algunos trozos; aquí, en el primero y en el último.
        if (i === 0) parte.serverContent.outputTranscription = { text: g.texto.slice(0, 20) };
        else if (i === nTrozos - 1) parte.serverContent.outputTranscription = { text: g.texto.slice(20) };
        if (i % 2 === 1) manda({});
        manda(parte);
        if (nTrozos === 1) manda({ serverContent: { outputTranscription: { text: g.texto.slice(20) } } });
        i++;
      }, CADA_MS);
    };
    const interrumpe = () => {
      const h = hablando;
      clearInterval(h.timer);
      if (h.cierre) clearTimeout(h.cierre);
      hablando = null;
      stats.interrupciones++;
      const dicho = Math.min(h.s, Math.max(0, (Date.now() - (h.fin - h.s * 1000)) / 1000));
      const u = uso(dicho);
      audioSalida_s += dicho;
      manda({ serverContent: { interrupted: true } });
      manda({ serverContent: { turnComplete: true }, usageMetadata: u });
    };

    ws.on("message", (d, binario) => {
      stats.marcosCliente[binario ? "binario" : "texto"]++;
      let m;
      try {
        m = JSON.parse(String(d));
      } catch {
        return ws.close(1007, "Request contains an invalid argument.");
      }
      if (!setup) {
        if (!m.setup?.model) return ws.close(1007, "Request contains an invalid argument.");
        setup = true;
        stats.setups.push(m.setup);
        // Con token amarrado manda el setup del token; sin token, el del cliente.
        const vad = (delToken?.setup ?? m.setup).realtimeInputConfig?.automaticActivityDetection;
        silencioFinMs = Number(vad?.silenceDurationMs) || SILENCIO_OMISION_MS;
        manda({ setupComplete: {} });
        manda({ sessionResumptionUpdate: { newHandle: `falso-${Math.random().toString(36).slice(2).padEnd(30, "0")}`, resumable: true } });
        return;
      }
      if (m.realtimeInput?.audio) {
        stats.audio++;
        const bytes = Math.floor((m.realtimeInput.audio.data.length * 3) / 4);
        stats.audioBytes += bytes;
        const ms = (bytes / 2 / 16000) * 1000; // el micrófono llega a 16 kHz
        const voz = rmsB64(m.realtimeInput.audio.data) > 300;
        if (voz) {
          silencio = 0;
          if (!enVoz) {
            enVoz = true;
            vozDesde_s = audioEntrada_s;
            manda({ voiceActivity: { type: "ACTIVITY_START", audioOffset: offset(audioEntrada_s) } });
            if (hablando) interrumpe();
          }
        } else if (enVoz && (silencio += ms) >= silencioFinMs) {
          enVoz = false;
          silencio = 0;
          vozEntrada_s += audioEntrada_s + ms / 1000 - vozDesde_s;
          const g = GUION[Math.min(turno, GUION.length - 1)];
          manda({});
          manda({ serverContent: { inputTranscription: { text: g.oyo } } });
          manda({ voiceActivity: { type: "ACTIVITY_END", audioOffset: offset(audioEntrada_s + ms / 1000) } });
          if (!hablando) responde();
        }
        audioEntrada_s += ms / 1000;
      } else if (m.realtimeInput?.text || m.clientContent) {
        stats.textos.push(m.realtimeInput?.text ?? m.clientContent?.turns?.[0]?.parts?.[0]?.text);
        if (m.clientContent) stats.clientContent.push(m.clientContent);
        if (!hablando) responde();
      } else if (m.toolResponse) {
        stats.toolResponses.push(m.toolResponse);
      }
    });
    ws.on("close", (code, razon) => {
      if (hablando) clearInterval(hablando.timer);
      for (const t of timers) clearTimeout(t);
      timers.clear();
      con.cierre = { code, razon: String(razon) };
      log("mock", `cerró: ${code} ${razon}`);
    });
  }

  return new Promise((resolve) =>
    srv.listen(puerto, ip, () =>
      resolve({
        stats,
        puerto,
        ip,
        cierra: () => new Promise((r) => (wss.clients.forEach((c) => c.terminate()), srv.close(() => r()))),
      }),
    ),
  );
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { values } = parseArgs({ options: { puerto: { type: "string", default: "7398" }, ip: { type: "string", default: "127.0.0.1" }, "goaway-s": { type: "string" } } });
  const m = await arrancaMock({ puerto: Number(values.puerto), ip: values.ip, goAwayTrasS: values["goaway-s"] === undefined ? null : Number(values["goaway-s"]), log: (e, t) => console.log(`${e} ${t}`) });
  console.log(`mock escuchando en ${m.ip}:${m.puerto}${m.ip === "127.0.0.1" ? "" : " (también desde la red local)"}`);
  process.on("SIGINT", async () => {
    await m.cierra();
    console.log(JSON.stringify({ tokens: m.stats.tokens.map(({ vad, ...t }) => t), conexiones: m.stats.conexiones, turnos: m.stats.turnos, interrupciones: m.stats.interrupciones, vencidos: m.stats.vencidos, toolResponses: m.stats.toolResponses }));
    process.exit(0);
  });
}
