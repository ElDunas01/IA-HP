#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
/**
 * Prueba local del Worker de tokens: sin llaves reales, sin cuota y sin desplegar nada.
 * Arranca el Gemini falso de ../puente/mock-live.mjs y `wrangler dev --local` con secretos FALSOS (un --env-file
 * temporal, que wrangler trata como .dev.vars y muestra «(hidden)»). Antes de arrancar, revisa las 30 variantes de
 * src/setup.json (modo × idioma × max_min). En siete arranques revisa:
 *   1. la puerta (todo 404 salvo POST /token con el secreto), cuerpos y parámetros malos (400 sin gastar tope; también el
 *      modo inválido o repetido), tokens con idioma y max_min en la URL o en el cuerpo, la vida del token (ventana +
 *      max_min + 30 s), el tope por minuto, la forma exacta del cuerpo que le llega a Google (prompt con su max_min y la
 *      VAD de una llamada) y la llave en x-goog-api-key;
 *   2. los tres modos: servicios por omisión, isabella y flexible en la URL o en el cuerpo (manda la URL), cada uno con
 *      el prompt EXACTO de su variante (el Gemini falso guarda el sha256 del prompt que le llegó);
 *   3. los errores de Google (429 de cuota, 200 sin token, conexión cortada) y el tope por día, que cuenta los fallidos;
 *   4. KV caído: 503 sin pegarle a Google (falla cerrado);
 *   5. la llave sin configurar: 503 sin_llave;
 *   6. el secreto sin configurar: todo 404;
 *   7. un setup.json al que le falta una variante o un modo: 500 setup_faltante, sin gastar tope ni pegarle a Google.
 * Y que ni las respuestas ni la salida de wrangler traigan la llave o el secreto.
 *
 *   node prueba-local.mjs [--wrangler <ruta>] [--estado <dir>] [--puerto 8797] [--puerto-mock 7398] [--verboso]
 *                         [--main <otro index.ts>]   (prueba otra versión del Worker sin tocar src/; junto a ella, su setup.json)
 *
 * wrangler: --wrangler, la variable WRANGLER o ./node_modules/.bin/wrangler (npm install en token-worker/; no instala nada solo).
 * Cada arranque usa un estado nuevo (KV vacío) dentro de una carpeta nueva en --estado o en el directorio temporal del
 * sistema. La prueba no borra nada: esa carpeta se queda (es pequeña) y la limpia el sistema.
 */
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs } from "node:util";
import { arrancaMock } from "../puente/mock-live.mjs";
import { MODOS, PROHIBIDAS } from "../puente/lib/isabella.mjs";

const AQUI = path.dirname(fileURLToPath(import.meta.url));
const { values: op } = parseArgs({
  options: {
    wrangler: { type: "string" },
    estado: { type: "string" },
    main: { type: "string" },
    puerto: { type: "string", default: "8797" },
    "puerto-mock": { type: "string", default: "7398" },
    verboso: { type: "boolean", default: false },
  },
});

// Valores FALSOS, solo para esta prueba.
const SECRETO = "secreto-de-prueba-123";
const LLAVE = "clave-falsa-de-prueba";
const LLAVE_FALLAS = "clave-de-fallas"; // el Gemini falso responde 429 (con la llave en el mensaje), luego 200 sin name, luego corta
const PUERTO = Number(op.puerto);
const PUERTO_INSPECTOR = PUERTO + 1000;
const PUERTO_MOCK = Number(op["puerto-mock"]);
const BASE = `http://127.0.0.1:${PUERTO}`;
const BASE_MOCK = `http://127.0.0.1:${PUERTO_MOCK}`;
const MAIN = op.main ? path.resolve(op.main) : path.join(AQUI, "src/index.ts");
const SETUP = JSON.parse(fs.readFileSync(path.join(path.dirname(MAIN), "setup.json"), "utf8"));
const VAD = { automaticActivityDetection: { prefixPaddingMs: 250, silenceDurationMs: 600 } };
const textoDe = (v) => String(v?.systemInstruction?.parts?.[0]?.text ?? "");
/** El mismo sha256 que guarda el Gemini falso (stats.tokens[].hashPrompt) del prompt que le llegó. */
const hashDe = (v) => createHash("sha256").update(textoDe(v)).digest("hex");

const espera = (ms) => new Promise((r) => setTimeout(r, ms));
const sale = (msg) => (console.error(msg), process.exit(2));

const WRANGLER =
  [op.wrangler, process.env.WRANGLER, path.join(AQUI, "node_modules/.bin/wrangler")]
    .filter(Boolean)
    .find((c) => fs.existsSync(c)) ?? sale("No encontré wrangler: pásalo con --wrangler <ruta> o la variable WRANGLER.");

// Si hubiera .dev.vars o .env aquí, wrangler podría mezclarlos con los falsos: mejor no correr.
const sobrantes = fs.readdirSync(AQUI).filter((f) => /^\.dev\.vars|^\.env/.test(f));
if (sobrantes.length) sale(`Hay ${sobrantes.join(", ")} en token-worker/: esta prueba solo usa secretos falsos; muévelos a otro lado antes de correrla.`);

const libre = (p) =>
  new Promise((r) => {
    const s = net.createServer();
    s.once("error", () => r(false));
    s.once("listening", () => s.close(() => r(true)));
    s.listen(p, "127.0.0.1");
  });
for (const p of [PUERTO, PUERTO_INSPECTOR, PUERTO_MOCK]) if (!(await libre(p))) sale(`El puerto ${p} está ocupado: elige otro con --puerto o --puerto-mock.`);

// wrangler sin métricas, sin las variables del proceso dentro del Worker y sin ninguna llave real a la mano.
const envHijo = { ...process.env, WRANGLER_SEND_METRICS: "false", CLOUDFLARE_INCLUDE_PROCESS_ENV: "false", NO_COLOR: "1", FORCE_COLOR: "0" };
for (const k of Object.keys(envHijo)) if (/GEMINI|GOOGLE|^CLOUDFLARE_API|^CF_/.test(k)) delete envHijo[k];

let raiz;
if (op.estado) {
  fs.mkdirSync(op.estado, { recursive: true });
  raiz = fs.mkdtempSync(path.join(op.estado, "prueba-"));
} else raiz = fs.mkdtempSync(path.join(os.tmpdir(), "token-worker-prueba-"));

/** Copia de wrangler.jsonc en la carpeta de la corrida, con `main` absoluto (y, si se pide, sin el KV). */
function copiaConfig(nombre, { sinKv = false } = {}) {
  const cfg = JSON.parse(
    fs
      .readFileSync(path.join(AQUI, "wrangler.jsonc"), "utf8")
      .replace(/^\s*\/\/.*$/gm, "")
      .replace(/\/\*[\s\S]*?\*\//g, ""),
  );
  if (sinKv) delete cfg.kv_namespaces;
  cfg.main = op.main ? path.resolve(op.main) : path.join(AQUI, cfg.main);
  const ruta = path.join(raiz, nombre);
  fs.writeFileSync(ruta, JSON.stringify(cfg, null, 2));
  return ruta;
}
const CONFIG = op.main ? copiaConfig("wrangler-main.json") : path.join(AQUI, "wrangler.jsonc");

// ── Resultados ───────────────────────────────────────────────────────────────────────────────────────────────
let pasan = 0;
const fallan = [];
function revisa(cond, desc, detalle = "") {
  if (cond) {
    pasan++;
    console.log(`  ✓ ${desc}`);
  } else {
    fallan.push(desc);
    console.log(`  ✗ ${desc}${detalle ? ` · ${detalle}` : ""}`);
  }
}

const respuestas = []; // todo lo que respondió el Worker, para buscar fugas al final
async function pide({ metodo = "POST", ruta = "/token", secreto, cuerpo } = {}) {
  const headers = {};
  if (secreto !== undefined) headers["x-device-secret"] = secreto;
  if (cuerpo !== undefined) headers["content-type"] = "application/json";
  const r = await fetch(BASE + ruta, { method: metodo, headers, body: cuerpo });
  const texto = await r.text();
  respuestas.push(texto + JSON.stringify([...r.headers]));
  let j = null;
  try {
    j = JSON.parse(texto);
  } catch {}
  return { status: r.status, j, texto, cabeceras: r.headers };
}
const corto = (r) => `${r.status} ${r.texto.slice(0, 180)}`;
const noExiste = (r) => r.status === 404 && r.j?.ok === false && r.j?.error === "no_existe";

/** Las líneas de registro del Worker (JSON con `evt`) dentro de la salida de wrangler. */
const eventos = (texto) =>
  texto
    .split("\n")
    .map((l) => l.trim())
    .filter((l) => l.startsWith('{"evt"'))
    .map((l) => {
      try {
        return JSON.parse(l);
      } catch {
        return null;
      }
    })
    .filter(Boolean);

// ── Un arranque de wrangler dev ──────────────────────────────────────────────────────────────────────────────
let nArranque = 0;
async function conWorker(titulo, { config = CONFIG, secretos = {}, vars = {} }, prueba) {
  const dir = path.join(raiz, String(++nArranque));
  fs.mkdirSync(dir);
  const envFile = path.join(dir, "secretos-falsos.env");
  fs.writeFileSync(envFile, Object.entries(secretos).map(([k, v]) => `${k}=${v}\n`).join(""), { mode: 0o600 });
  const args = ["dev", "--local", "--config", config, "--ip", "127.0.0.1", "--port", String(PUERTO), "--inspector-port", String(PUERTO_INSPECTOR)];
  args.push("--persist-to", path.join(dir, "estado"), "--env-file", envFile, "--show-interactive-dev-session=false");
  for (const [k, v] of Object.entries(vars)) args.push("--var", `${k}:${v}`);

  console.log(`\n${nArranque}. ${titulo}`);
  const t0 = Date.now();
  const hijo = spawn(WRANGLER, args, { cwd: AQUI, env: envHijo, detached: true, stdio: ["ignore", "pipe", "pipe"] });
  const s = { texto: "" };
  const junta = (d) => {
    s.texto += d;
    if (op.verboso) process.stdout.write(String(d).replace(/^(?=.)/gm, `      [wrangler] `));
  };
  hijo.stdout.on("data", junta);
  hijo.stderr.on("data", junta);
  let vivo = true;
  const salio = new Promise((r) => hijo.once("exit", (code, signal) => ((vivo = false), r({ code, signal }))));
  try {
    await new Promise((resolve, reject) => {
      const listo = `Ready on ${BASE}`;
      const fin = Date.now() + 45_000;
      const t = setInterval(() => {
        if (s.texto.includes(listo)) clearInterval(t), resolve();
        else if (Date.now() > fin) clearInterval(t), reject(new Error(`wrangler no quedó listo en 45 s:\n${s.texto.slice(-1500)}`));
      }, 100);
      salio.then(({ code, signal }) => (clearInterval(t), reject(new Error(`wrangler terminó antes de quedar listo (código ${code}, señal ${signal}):\n${s.texto.slice(-1500)}`))));
    });
    console.log(`  · wrangler dev listo en ${((Date.now() - t0) / 1000).toFixed(1)} s`);
    await prueba(s);
    await espera(300); // que la última línea de registro alcance a llegar
  } finally {
    if (vivo) {
      try {
        process.kill(-hijo.pid, "SIGINT");
      } catch {}
      const t = setTimeout(() => {
        try {
          process.kill(-hijo.pid, "SIGKILL");
        } catch {}
      }, 8000);
      await salio;
      clearTimeout(t);
    }
  }
  const fugas = Object.entries(secretos)
    .filter(([, v]) => v && s.texto.includes(v))
    .map(([k]) => k);
  revisa(fugas.length === 0, `la salida de wrangler (${s.texto.split("\n").length} líneas) no trae la llave ni el secreto`, `aparece el valor de ${fugas.join(", ")}`);
  return s.texto;
}

// ── Las pruebas ──────────────────────────────────────────────────────────────────────────────────────────────
const mock = await arrancaMock({ puerto: PUERTO_MOCK, log: op.verboso ? (e, t) => console.log(`      [${e}] ${t}`) : () => {} });
console.log(`Worker de tokens: prueba local · wrangler ${WRANGLER} · Gemini falso en ${BASE_MOCK} · estado en ${raiz}`);
if (op.main) console.log(`OJO: probando ${path.resolve(op.main)}, no src/index.ts`);
const modelo = SETUP.servicios?.es?.["3"]?.model?.replace(/^models\//, "") ?? sale("src/setup.json no trae servicios.es.3: regenéralo con npm run genera-setup");
const inicioPrompt = (idioma) => textoDe(SETUP.servicios[idioma]["3"]).slice(0, 40);

// Antes de arrancar: las 30 variantes de src/setup.json (modo × idioma × max_min), ninguna fija una sensibilidad de la VAD
// (decisión del director del 27-oct: con START_SENSITIVITY_LOW, la sesión 6 no oyó un «¿Bueno?») y cada modo trae lo suyo.
console.log("src/setup.json");
const DURA = { es: ["un minuto", "dos minutos", "tres minutos", "cuatro minutos", "cinco minutos"], en: ["one minute", "two minutes", "three minutes", "four minutes", "five minutes"] };
const ENCABEZADO = { servicios: { es: "## MODO DE ESTA LLAMADA: SERVICIOS", en: "## MODE OF THIS CALL: SERVICES" }, isabella: { es: "## MODO DE ESTA LLAMADA: ISABELLA", en: "## MODE OF THIS CALL: ISABELLA" }, flexible: { es: "## MODO DE ESTA LLAMADA: ASISTENTE GENERAL", en: "## MODE OF THIS CALL: GENERAL ASSISTANT" } };
const PRIMERA = { es: "## ESTA SESIÓN ES UNA LLAMADA TELEFÓNICA", en: "## THIS SESSION IS A PHONE CALL" };
const variantes = MODOS.flatMap((modo) => ["es", "en"].flatMap((idioma) => ["1", "2", "3", "4", "5"].map((m) => ({ modo, idioma, m, v: SETUP[modo]?.[idioma]?.[m] }))));
const nombre = ({ modo, idioma, m }) => `${modo}/${idioma}/${m}`;
revisa(
  variantes.length === 30 && variantes.every(({ v }) => v?.model && JSON.stringify(v.realtimeInputConfig) === JSON.stringify(VAD)) && Object.keys(SETUP).filter((k) => !["generado", "fuente", "uso"].includes(k)).join() === MODOS.join(),
  `30 variantes (${MODOS.join(", ")} × es y en × max_min de 1 a 5), todas con la VAD de una llamada y ningún modo de más`,
  variantes.filter(({ v }) => JSON.stringify(v?.realtimeInputConfig) !== JSON.stringify(VAD)).map(nombre).join(", ") || Object.keys(SETUP).join(),
);
revisa(!JSON.stringify(SETUP).includes("START_SENSITIVITY"), "ninguna variante fija la sensibilidad de inicio o fin de voz: se quedan las de Google");
const malIdioma = variantes.filter(({ idioma, m, v }) => !textoDe(v).startsWith(PRIMERA[idioma]) || !textoDe(v).includes(idioma === "es" ? `La llamada dura máximo ${DURA.es[m - 1]}.` : `The call lasts at most ${DURA.en[m - 1]}.`));
revisa(malIdioma.length === 0, "cada variante abre con el preámbulo de llamada en su idioma y dice su max_min con letra", malIdioma.map(nombre).join(", "));
const malModo = variantes.filter(({ modo, idioma, v }) => {
  const t = textoDe(v);
  return !t.includes(`\n${ENCABEZADO[modo][idioma]}\n`) || PROHIBIDAS.some((r) => r.test(t)) || !t.includes("David Ruiz Duarte (viajatech)");
});
revisa(
  malModo.length === 0,
  "cada modo trae su encabezado, nada de la lista PROHIBIDAS («única misión», «sensual»…) y la atribución a David Ruiz Duarte (viajatech)",
  malModo.map(nombre).join(", "),
);
revisa(new Set(variantes.map(({ v }) => hashDe(v))).size === 30, "los 30 prompts son distintos entre sí");

try {
  await conWorker("Puerta, cuerpos, tokens y tope por minuto (wrangler.jsonc tal cual: 3 por minuto, 30 por día)", { secretos: { LAB_DEVICE_SECRET: SECRETO, GEMINI_API_KEY_AQ: LLAVE }, vars: { GEMINI_BASE: BASE_MOCK } }, async (s) => {
    let r = await pide({ metodo: "GET", secreto: SECRETO });
    revisa(noExiste(r) && r.cabeceras.get("cache-control") === "no-store", "GET /token con el secreto: 404 no_existe y sin caché", corto(r));
    r = await pide({ ruta: "/", secreto: SECRETO });
    revisa(noExiste(r), "POST / con el secreto: 404", corto(r));
    r = await pide({ ruta: "/token/", secreto: SECRETO });
    revisa(noExiste(r), "POST /token/ con el secreto: 404", corto(r));
    r = await pide({});
    revisa(noExiste(r), "POST /token sin secreto: 404, igual que una ruta que no existe", corto(r));
    r = await pide({ secreto: "otro" });
    revisa(noExiste(r), "POST /token con un secreto incorrecto: 404", corto(r));
    r = await pide({ secreto: SECRETO.slice(0, -1) });
    revisa(noExiste(r), "con el secreto sin su último carácter: 404", corto(r));
    r = await pide({ secreto: `${SECRETO}4` });
    revisa(noExiste(r), "con el secreto y un carácter de más: 404", corto(r));
    r = await pide({ secreto: SECRETO, cuerpo: "{idioma" });
    revisa(r.status === 400 && r.j?.error === "json_invalido", "cuerpo que no es JSON: 400 json_invalido", corto(r));
    r = await pide({ secreto: SECRETO, cuerpo: JSON.stringify({ idioma: "es", relleno: "x".repeat(2000) }) });
    revisa(r.status === 400 && r.j?.error === "cuerpo_grande", "cuerpo de más de 1 KB: 400 cuerpo_grande", corto(r));
    r = await pide({ secreto: SECRETO, cuerpo: "[1,2]" });
    revisa(r.status === 400 && r.j?.error === "json_invalido", "cuerpo JSON que no es un objeto: 400 json_invalido", corto(r));
    const malos = [
      ["/token?max_min=0", undefined, "max_min_invalido"],
      ["/token?max_min=6", undefined, "max_min_invalido"],
      ["/token?max_min=2.5", undefined, "max_min_invalido"],
      ["/token?max_min=abc", undefined, "max_min_invalido"],
      ["/token?max_min=3&max_min=4", undefined, "max_min_invalido"],
      ["/token?idioma=fr", undefined, "idioma_invalido"],
      ["/token?idioma=", undefined, "idioma_invalido"],
      ["/token?idioma=es&idioma=en", undefined, "idioma_invalido"],
      ["/token", JSON.stringify({ idioma: "fr" }), "idioma_invalido"],
      ["/token", JSON.stringify({ max_min: 9 }), "max_min_invalido"],
      ["/token", JSON.stringify({ max_min: "tres" }), "max_min_invalido"],
      ["/token?modo=otro", undefined, "modo_invalido"],
      ["/token?modo=", undefined, "modo_invalido"],
      ["/token?modo=Isabella", undefined, "modo_invalido"],
      ["/token?modo=%20isabella", undefined, "modo_invalido"],
      ["/token?modo=__proto__", undefined, "modo_invalido"],
      ["/token?modo=isabella&modo=flexible", undefined, "modo_invalido"],
      ["/token?modo=servicios&modo=servicios", undefined, "modo_invalido"],
      ["/token", JSON.stringify({ modo: "otro" }), "modo_invalido"],
      ["/token", JSON.stringify({ modo: 1 }), "modo_invalido"],
      ["/token", JSON.stringify({ modo: ["isabella"] }), "modo_invalido"],
      ["/token", JSON.stringify({ modo: "constructor" }), "modo_invalido"],
    ];
    const resMalos = [];
    for (const [ruta, cuerpo] of malos) resMalos.push(await pide({ ruta, secreto: SECRETO, cuerpo }));
    revisa(
      resMalos.every((x, i) => x.status === 400 && x.j?.error === malos[i][2]),
      "max_min fuera de 1 a 5, no entero o repetido, idioma que no es es ni en, y modo fuera de la lista (mayúsculas, espacio, __proto__, constructor, no texto) o repetido, en la URL o en el cuerpo: 400 con su código",
      resMalos.map((x, i) => `${malos[i][0]} ${malos[i][1] ?? ""} → ${corto(x)}`).filter((_, i) => !(resMalos[i].status === 400 && resMalos[i].j?.error === malos[i][2])).join(" · "),
    );
    revisa(mock.stats.tokens.length === 0, "nada de lo anterior llegó al Gemini falso", `${mock.stats.tokens.length} peticiones`);

    // Los tres tokens y el cuarto intento deben caer en el mismo minuto UTC (la llave del tope es por minuto).
    const seg = new Date().getUTCSeconds();
    if (seg > 50) {
      const ms = (60 - seg) * 1000 + 300;
      console.log(`  · espero ${Math.round(ms / 1000)} s al minuto siguiente para que los cuatro caigan en el mismo`);
      await espera(ms);
    }
    const t0 = Date.now();
    const a = await pide({ secreto: SECRETO });
    const b = await pide({ ruta: "/token?idioma=en&max_min=5", secreto: SECRETO });
    const c = await pide({ ruta: "/token?max_min=2", secreto: SECRETO, cuerpo: JSON.stringify({ idioma: "en", max_min: 1 }) });
    const d = await pide({ secreto: SECRETO });
    const bien = (x, idioma, maxMin, quedan) => x.status === 200 && x.j?.ok === true && /^auth_tokens\/falso-\d+$/.test(x.j.token) && x.j.modelo === modelo && x.j.modo === "servicios" && x.j.idioma === idioma && x.j.maxMin === maxMin && x.j.quedanHoy === quedan;
    revisa(bien(a, "es", 3, 29), `token sin parámetros: 200, auth_tokens/…, ${modelo}, modo servicios, idioma es, maxMin 3, quedanHoy 29 (los 400 no gastaron tope)`, corto(a));
    const vida = (x) => Date.parse(x.j?.expira) - t0;
    const ventana = (x) => Date.parse(x.j?.abrirAntes) - t0;
    const cerca = (v, esperado) => v > esperado - 10_000 && v <= esperado + 2_000;
    revisa(cerca(vida(a), 270_000) && cerca(ventana(a), 60_000), "max_min 3: expira a los 270 s (60 s + 3 min + 30 s) y la sesión debe abrirse antes de 1 min", `vida ${vida(a)} ms · ventana ${ventana(a)} ms`);
    revisa(a.cabeceras.get("cache-control") === "no-store", "el token sale con cache-control no-store", String(a.cabeceras.get("cache-control")));
    revisa(bien(b, "en", 5, 28) && cerca(vida(b), 390_000), "?idioma=en&max_min=5: 200, en, maxMin 5, quedanHoy 28 y 390 s de vida", `${corto(b)} · vida ${vida(b)} ms`);
    revisa(bien(c, "en", 2, 27) && cerca(vida(c), 210_000), 'URL ?max_min=2 con cuerpo {"idioma":"en","max_min":1}: manda la URL; el idioma sale del cuerpo; 210 s de vida', `${corto(c)} · vida ${vida(c)} ms`);
    revisa(new Set([a, b, c].map((x) => x.j?.token)).size === 3, "los tres tokens son distintos");
    const ra = Number(d.cabeceras.get("retry-after"));
    revisa(d.status === 429 && d.j?.error === "tope_minuto" && d.j?.tope === 3 && ra >= 1 && ra <= 60, "el cuarto en el mismo minuto: 429 tope_minuto con retry-after de hasta 60 s", `${corto(d)} · retry-after ${ra}`);

    // Lo que le llegó a «Google».
    const tk = mock.stats.tokens;
    revisa(tk.length === 3, "al Gemini falso le llegaron 3 acuñados: el del tope no salió", String(tk.length));
    const claves = "uses,expireTime,newSessionExpireTime,bidiGenerateContentSetup";
    revisa(tk.every((t) => t.ok && t.uses === 1 && t.claves.join() === claves), "cuerpo REST v1beta: uses 1, expireTime, newSessionExpireTime y bidiGenerateContentSetup, nada más", JSON.stringify(tk.map((t) => t.claves)));
    revisa(tk.every((t) => t.modelo === SETUP.servicios.es["3"].model && t.setup.join() === Object.keys(SETUP.servicios.es["3"]).join()), "el setup amarrado va completo: modelo, voz, prompt, colgar, transcripciones y VAD", JSON.stringify(tk[0]?.setup));
    revisa(
      tk.map((t) => t.hashPrompt).join() === [SETUP.servicios.es["3"], SETUP.servicios.en["5"], SETUP.servicios.en["2"]].map(hashDe).join(),
      "sin modo, los tres prompts son exactamente servicios/es/3, servicios/en/5 y servicios/en/2 de setup.json",
      tk.map((t) => t.hashPrompt?.slice(0, 12)).join(" · "),
    );
    revisa(tk[0]?.inicioPrompt === inicioPrompt("es") && tk[1]?.inicioPrompt === inicioPrompt("en") && tk[2]?.inicioPrompt === inicioPrompt("en"), "prompt en español, inglés e inglés, en ese orden", tk.map((t) => JSON.stringify(t.inicioPrompt)).join(" · "));
    revisa(tk.map((t) => t.dura).join() === "tres minutos,five minutes,two minutes", "cada prompt dice su max_min: tres minutos, five minutes, two minutes", tk.map((t) => t.dura).join(" · "));
    revisa(tk.every((t) => JSON.stringify(t.vad) === JSON.stringify(VAD)), "la VAD de una llamada va amarrada: 250 ms y 600 ms, con las sensibilidades de Google", JSON.stringify(tk.map((t) => t.vad)));
    revisa(tk.every((t) => t.llaveLargo === LLAVE.length), "la llave falsa llega en x-goog-api-key", tk.map((t) => t.llaveLargo).join(","));
    const margen = tk.map((t) => Date.parse(t.expireTime) - Date.parse(t.newSessionExpireTime));
    revisa(margen.join() === "210000,330000,150000", "expireTime queda max_min + 30 s después de newSessionExpireTime: 210, 330 y 150 s", margen.join(","));

    await espera(300);
    const ev = eventos(s.texto);
    const n = (evt, f = () => true) => ev.filter((x) => x.evt === evt && f(x)).length;
    revisa(
      n("token") === 3 && ev.filter((x) => x.evt === "token").map((x) => `${x.modo}/${x.maxMin}`).join() === "servicios/3,servicios/5,servicios/2" && n("tope", (x) => x.cual === "minuto") === 1 && n("rechazo", (x) => x.motivo === "sin_secreto") === 1 && n("rechazo", (x) => x.motivo === "secreto_incorrecto") === 3,
      "registros: 3 token con su modo y su maxMin, 1 tope de minuto, 1 rechazo sin secreto y 3 con secreto incorrecto",
      JSON.stringify(ev),
    );
  });

  await conWorker("Los tres modos, en la URL o en el cuerpo (TOPE_MINUTO 20 para que quepan los seis pedidos)", { secretos: { LAB_DEVICE_SECRET: SECRETO, GEMINI_API_KEY_AQ: LLAVE }, vars: { GEMINI_BASE: BASE_MOCK, TOPE_MINUTO: "20" } }, async (s) => {
    const antes = mock.stats.tokens.length;
    // [ruta, cuerpo, modo, idioma, max_min, qué prueba]
    const casos = [
      ["/token", undefined, "servicios", "es", 3, "sin modo: servicios (lo que pide la app 2.3)"],
      ["/token?modo=servicios", JSON.stringify({ idioma: "es" }), "servicios", "es", 3, "?modo=servicios con el cuerpo de la app 2.3"],
      ["/token?modo=isabella", undefined, "isabella", "es", 3, "?modo=isabella"],
      ["/token?modo=flexible&idioma=en&max_min=4", undefined, "flexible", "en", 4, "?modo=flexible&idioma=en&max_min=4"],
      ["/token", JSON.stringify({ modo: "isabella", idioma: "en", max_min: 2 }), "isabella", "en", 2, 'cuerpo {"modo":"isabella","idioma":"en","max_min":2}'],
      ["/token?modo=flexible", JSON.stringify({ modo: "isabella", max_min: 5 }), "flexible", "es", 5, 'URL ?modo=flexible con cuerpo {"modo":"isabella","max_min":5}: manda la URL; max_min sale del cuerpo'],
    ];
    const res = [];
    const inicios = [];
    for (const [ruta, cuerpo] of casos) {
      inicios.push(Date.now());
      res.push(await pide({ ruta, secreto: SECRETO, cuerpo }));
    }
    const tk = mock.stats.tokens.slice(antes);
    revisa(tk.length === casos.length && tk.every((t) => t.ok), `al Gemini falso le llegaron ${casos.length} acuñados bien formados`, String(tk.length));
    casos.forEach(([, , modo, idioma, maxMin, desc], i) => {
      const r = res[i];
      const esperado = hashDe(SETUP[modo][idioma][String(maxMin)]);
      const vida = Date.parse(r.j?.expira) - inicios[i];
      revisa(
        r.status === 200 && r.j?.ok === true && r.j.modo === modo && r.j.idioma === idioma && r.j.maxMin === maxMin && tk[i]?.hashPrompt === esperado && vida > (90 + maxMin * 60) * 1000 - 15_000 && vida <= (90 + maxMin * 60) * 1000 + 2_000,
        `${desc}: 200 con modo ${modo}, ${idioma} y maxMin ${maxMin}; el prompt amarrado es EXACTAMENTE ${modo}/${idioma}/${maxMin} y vive ${90 + maxMin * 60} s`,
        `${corto(r)} · prompt ${tk[i]?.hashPrompt?.slice(0, 12)} vs ${esperado.slice(0, 12)} · vida ${vida} ms`,
      );
    });
    revisa(
      tk.map((t) => t.modoPrompt ?? "-").join() === "SERVICIOS,SERVICIOS,ISABELLA,GENERAL ASSISTANT,ISABELLA,ASISTENTE GENERAL",
      "lo que le llegó a Google: SERVICIOS en servicios; ISABELLA, GENERAL ASSISTANT, ISABELLA y ASISTENTE GENERAL en los demás",
      tk.map((t) => t.modoPrompt).join(" · "),
    );
    revisa(tk.map((t) => t.dura).join() === "tres minutos,tres minutos,tres minutos,four minutes,two minutes,cinco minutos", "cada prompt dice su max_min en su idioma", tk.map((t) => t.dura).join(" · "));
    revisa(
      tk.every((t) => JSON.stringify(t.vad) === JSON.stringify(VAD) && t.setup.join() === Object.keys(SETUP.servicios.es["3"]).join() && t.modelo === SETUP.servicios.es["3"].model),
      "los tres modos amarran lo mismo salvo el prompt: modelo, voz, colgar, transcripciones y la VAD de una llamada",
      JSON.stringify(tk.map((t) => [t.modelo, t.setup.length, t.vad])),
    );
    await espera(300);
    const ev = eventos(s.texto).filter((x) => x.evt === "token");
    revisa(
      ev.map((x) => `${x.modo}/${x.idioma}/${x.maxMin}`).join() === casos.map(([, , modo, idioma, maxMin]) => `${modo}/${idioma}/${maxMin}`).join(),
      "registros: un token por pedido, con su modo, idioma y maxMin",
      JSON.stringify(ev),
    );
  });

  await conWorker("Errores de Google y tope por día (TOPE_DIA 3; los intentos fallidos cuentan)", { secretos: { LAB_DEVICE_SECRET: SECRETO, GEMINI_API_KEY_AQ: LLAVE_FALLAS }, vars: { GEMINI_BASE: BASE_MOCK, TOPE_DIA: "3" } }, async (s) => {
    const antes = mock.stats.tokens.length;
    const a = await pide({ secreto: SECRETO });
    const b = await pide({ secreto: SECRETO });
    const c = await pide({ secreto: SECRETO });
    const d = await pide({ secreto: SECRETO });
    revisa(a.status === 502 && a.j?.error === "cuota_gemini" && a.j?.status === 429, "Google responde 429: 502 cuota_gemini", corto(a));
    revisa(b.status === 502 && b.j?.error === "gemini_sin_token", "Google responde 200 sin name: 502 gemini_sin_token", corto(b));
    revisa(c.status === 502 && c.j?.error === "gemini_sin_respuesta", "Google corta la conexión: 502 gemini_sin_respuesta", corto(c));
    // Segundos que faltan para la medianoche de la CDMX, como los calcula el Worker.
    const p = Object.fromEntries(new Intl.DateTimeFormat("en-CA", { timeZone: "America/Mexico_City", hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23" }).formatToParts(new Date()).map((x) => [x.type, x.value]));
    const esperado = Math.max(60, 86_400 - (Number(p.hour) * 3600 + Number(p.minute) * 60 + Number(p.second)));
    const ra = Number(d.cabeceras.get("retry-after"));
    revisa(d.status === 429 && d.j?.error === "tope_dia" && d.j?.tope === 3, "el cuarto intento: 429 tope_dia, porque los tres fallidos contaron", corto(d));
    revisa(Math.abs(ra - esperado) <= 5, "retry-after del tope por día: lo que falta para la medianoche de la CDMX", `retry-after ${ra} · esperado ${esperado}`);
    revisa(mock.stats.tokens.length - antes === 3, "al Gemini falso le llegaron 3 intentos: el del tope no salió", String(mock.stats.tokens.length - antes));
    await espera(300);
    const ev = eventos(s.texto);
    const err = ev.find((x) => x.evt === "gemini_error");
    revisa(err?.status === 429 && String(err?.mensaje).includes("«llave»") && !String(err?.mensaje).includes(LLAVE_FALLAS), "el mensaje de Google queda en el registro, con la llave tachada", JSON.stringify(err));
    revisa(["gemini_sin_token", "gemini_sin_respuesta"].every((e) => ev.some((x) => x.evt === e)) && ev.some((x) => x.evt === "tope" && x.cual === "dia"), "registros de gemini_sin_token, gemini_sin_respuesta y del tope por día", JSON.stringify(ev.map((x) => x.evt)));
  });

  const cfgSinKv = copiaConfig("wrangler-sin-kv.json", { sinKv: true });
  await conWorker("Sin KV: los topes fallan cerrados", { config: cfgSinKv, secretos: { LAB_DEVICE_SECRET: SECRETO, GEMINI_API_KEY_AQ: LLAVE }, vars: { GEMINI_BASE: BASE_MOCK } }, async (s) => {
    const antes = mock.stats.tokens.length;
    const r = await pide({ secreto: SECRETO });
    revisa(r.status === 503 && r.j?.error === "topes_no_disponibles", "sin KV: 503 topes_no_disponibles", corto(r));
    revisa(mock.stats.tokens.length === antes, "y no le pega a Google");
    await espera(300);
    revisa(eventos(s.texto).some((x) => x.evt === "topes_caidos" && x.fase === "leer"), "registro topes_caidos al leer", JSON.stringify(eventos(s.texto)));
  });

  await conWorker("Sin GEMINI_API_KEY_AQ configurada", { secretos: { LAB_DEVICE_SECRET: SECRETO }, vars: { GEMINI_BASE: BASE_MOCK } }, async () => {
    let r = await pide({ secreto: SECRETO });
    revisa(r.status === 503 && r.j?.error === "sin_llave", "con el secreto bien: 503 sin_llave", corto(r));
    r = await pide({ secreto: "otro" });
    revisa(noExiste(r), "con un secreto incorrecto la puerta sigue cerrada: 404", corto(r));
  });

  await conWorker("Sin LAB_DEVICE_SECRET configurado", { secretos: { GEMINI_API_KEY_AQ: LLAVE }, vars: { GEMINI_BASE: BASE_MOCK } }, async () => {
    const antes = mock.stats.tokens.length;
    const r1 = await pide({ secreto: SECRETO });
    const r2 = await pide({});
    revisa(noExiste(r1) && noExiste(r2), "todo 404, con cualquier secreto o sin él", `${corto(r1)} · ${corto(r2)}`);
    revisa(mock.stats.tokens.length === antes, "y no le pega a Google");
  });

  // Un setup.json viejo o a medias: la variante que falta da 500 y no gasta tope ni le pega a Google.
  const dirFaltante = path.join(raiz, "setup-faltante");
  fs.mkdirSync(dirFaltante);
  fs.copyFileSync(MAIN, path.join(dirFaltante, "index.ts"));
  fs.writeFileSync(path.join(dirFaltante, "setup.json"), JSON.stringify({ servicios: { es: { 3: SETUP.servicios.es["3"] }, en: {} } }));
  const cfgFaltante = (() => {
    const ruta = copiaConfig("wrangler-setup-faltante.json");
    const cfg = JSON.parse(fs.readFileSync(ruta, "utf8"));
    cfg.main = path.join(dirFaltante, "index.ts");
    fs.writeFileSync(ruta, JSON.stringify(cfg, null, 2));
    return ruta;
  })();
  await conWorker("setup.json sin la variante o sin el modo pedido", { config: cfgFaltante, secretos: { LAB_DEVICE_SECRET: SECRETO, GEMINI_API_KEY_AQ: LLAVE }, vars: { GEMINI_BASE: BASE_MOCK } }, async (s) => {
    const antes = mock.stats.tokens.length;
    const r1 = await pide({ ruta: "/token?idioma=en", secreto: SECRETO });
    const r2 = await pide({ ruta: "/token?max_min=4", secreto: SECRETO });
    const r2b = await pide({ ruta: "/token?modo=isabella", secreto: SECRETO });
    const faltan = [r1, r2, r2b].every((x) => x.status === 500 && x.j?.error === "setup_faltante");
    revisa(faltan, "servicios/en, servicios/es/4 y el modo isabella entero faltan: 500 setup_faltante", [r1, r2, r2b].map(corto).join(" · "));
    revisa(mock.stats.tokens.length === antes, "y no le pega a Google");
    const r3 = await pide({ secreto: SECRETO });
    revisa(r3.status === 200 && r3.j?.quedanHoy === 29 && r3.j?.maxMin === 3, "la que sí está (es, 3) sale, con quedanHoy 29: los 500 no gastaron tope", corto(r3));
    await espera(300);
    revisa(
      eventos(s.texto).filter((x) => x.evt === "setup_faltante").map((x) => `${x.modo}/${x.idioma}/${x.maxMin}`).join() === "servicios/en/3,servicios/es/4,isabella/es/3",
      "registro setup_faltante, uno por pedido, con su modo, idioma y maxMin",
      JSON.stringify(eventos(s.texto)),
    );
  });

  console.log("\nAl final");
  const conFuga = respuestas.filter((t) => [LLAVE, LLAVE_FALLAS, SECRETO].some((v) => t.includes(v))).length;
  revisa(conFuga === 0, `ninguna de las ${respuestas.length} respuestas del Worker trae la llave o el secreto`, `${conFuga} con fuga`);
} catch (e) {
  fallan.push(`error: ${e.message}`);
  console.log(`  ✗ ${e.stack ?? e.message}`);
} finally {
  await mock.cierra();
}

console.log(`\n${pasan} pasan · ${fallan.length} fallan${fallan.length ? `:\n  - ${fallan.join("\n  - ")}` : ""}`);
console.log(`Estado de los arranques (KV local y secretos falsos): ${raiz}`);
process.exit(fallan.length ? 1 : 0);
