// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
/**
 * agente-isabella-token: acuña tokens efímeros de Gemini Live para la app isabellallamadas del Galaxy A72 de pruebas.
 *
 * - Solo `POST /token` con la cabecera `X-Device-Secret` igual a LAB_DEVICE_SECRET (comparación en tiempo constante).
 *   Todo lo demás, también un secreto incorrecto, responde 404: desde fuera no se distingue de una ruta que no existe.
 * - Topes en KV, 3 por minuto y 30 por día (día de la CDMX), que fallan CERRADOS: si KV no responde, no hay token.
 *   Cuentan los pedidos que llegan a Google, se acuñe o no (un acuñado fallido también le pega a Google); los 400, 404 y
 *   500 de antes no cuentan.
 * - La llave (GEMINI_API_KEY_AQ) solo viaja en la cabecera x-goog-api-key de la petición a Google: nunca en la respuesta
 *   ni en los registros.
 * - Parámetros, en la URL (`?modo=servicios|isabella|flexible&idioma=es|en&max_min=1..5`) o en el cuerpo JSON
 *   (`{"modo":"isabella","idioma":"en","max_min":3}`; la app 2.0 mandaba `{"idioma":…}`). Si vienen en los dos lados,
 *   manda la URL. Por omisión: servicios, es y 3. Un valor fuera de la lista o del rango, o repetido en la URL, es 400 y
 *   no gasta tope.
 * - El modo dice qué hace Isabella: servicios = lo que pongas en CONOCIMIENTO (puente/lib/isabella.mjs);
 *   isabella = platica con su personalidad, registro apto para cualquiera; flexible = asistente general. La
 *   mecánica es la misma en los tres: cuelga con la función, avisa el tiempo, se despide, dice que es una IA y nunca
 *   inventa datos personales.
 * - El token sale AMARRADO al setup de la llamada (src/setup.json, generado con `node ../puente/token.mjs --setup-json`,
 *   una variante por modo, idioma y max_min: 30): modelo, voz, prompt con ese max_min, función colgar, transcripciones y
 *   la VAD de una llamada. La app solo manda el setup mínimo y no puede cambiar nada.
 *   uses 1, 1 min para abrir la sesión y una vida de esa ventana + max_min + 30 s (regla del arquitecto, la misma de
 *   token.mjs): la sesión no sirve después. REST v1beta, como pide la guía.
 */
import SETUP from "./setup.json";

export interface Env {
  TOPES: KVNamespace;
  LAB_DEVICE_SECRET: string;
  GEMINI_API_KEY_AQ: string;
  /** Por defecto https://generativelanguage.googleapis.com. Otra base solo para probar con el Gemini falso de puente/mock-live.mjs. */
  GEMINI_BASE?: string;
  TOPE_MINUTO?: string;
  TOPE_DIA?: string;
}

type Idioma = "es" | "en";
type Modo = "servicios" | "isabella" | "flexible";
type Setup = { model: string } & Record<string, unknown>;
type Setups = Partial<Record<Modo, Partial<Record<Idioma, Record<string, Setup | undefined>>>>>;

/** Ventana para abrir la sesión y colchón después del techo: expireTime = ventana + max_min + colchón (con 3 min, 270 s). */
const VENTANA_MS = 60_000;
const COLCHON_MS = 30_000;
const MAX_MIN_OMISION = 3;
const MODOS: readonly Modo[] = ["servicios", "isabella", "flexible"];
const MODO_OMISION: Modo = "servicios";
const MAX_MIN_TOPE = 5;
const MAX_CUERPO = 1024;
const BASE_GOOGLE = "https://generativelanguage.googleapis.com";

const json = (status: number, cuerpo: unknown, extra: Record<string, string> = {}) =>
  new Response(JSON.stringify(cuerpo), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...extra },
  });
const noExiste = () => json(404, { ok: false, error: "no_existe" });

/** Una línea JSON por evento; nunca la llave, el secreto ni el token. */
function registra(evt: string, extra: Record<string, unknown> = {}): void {
  try {
    console.log(JSON.stringify({ evt, ...extra }));
  } catch {
    /* el registro nunca rompe la petición */
  }
}

/** Compara en tiempo constante (los dos lados pasan por SHA-256: mismo largo siempre). */
async function iguales(a: string, b: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([crypto.subtle.digest("SHA-256", enc.encode(a)), crypto.subtle.digest("SHA-256", enc.encode(b))]);
  return crypto.subtle.timingSafeEqual(ha, hb);
}

const partesCdmx = (d: Date) =>
  Object.fromEntries(
    new Intl.DateTimeFormat("en-CA", {
      timeZone: "America/Mexico_City",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hourCycle: "h23",
    })
      .formatToParts(d)
      .map((p) => [p.type, p.value]),
  );

const entero = (v: string | undefined, def: number) => (v !== undefined && Number.isInteger(Number(v)) && Number(v) >= 0 ? Number(v) : def);

/** modo, idioma y max_min de la URL o, si no vienen ahí, del cuerpo. Devuelve el código de error si alguno no sirve. */
function parametros(url: URL, cuerpo: Record<string, unknown>): { modo: Modo; idioma: Idioma; maxMin: number } | { error: string } {
  const q = url.searchParams;
  if (q.getAll("idioma").length > 1) return { error: "idioma_invalido" };
  if (q.getAll("max_min").length > 1) return { error: "max_min_invalido" };
  if (q.getAll("modo").length > 1) return { error: "modo_invalido" };
  const idioma = q.has("idioma") ? q.get("idioma") : cuerpo.idioma;
  const maxMin = q.has("max_min") ? q.get("max_min") : cuerpo.max_min;
  const modo = q.has("modo") ? q.get("modo") : cuerpo.modo;
  if (idioma !== undefined && idioma !== "es" && idioma !== "en") return { error: "idioma_invalido" };
  let m = MAX_MIN_OMISION;
  if (maxMin !== undefined) {
    // En la URL llega como texto («3»); en el cuerpo, como número o como texto. Solo enteros de 1 a MAX_MIN_TOPE.
    m = typeof maxMin === "number" ? maxMin : typeof maxMin === "string" && /^\d{1,2}$/.test(maxMin) ? Number(maxMin) : NaN;
    if (!Number.isInteger(m) || m < 1 || m > MAX_MIN_TOPE) return { error: "max_min_invalido" };
  }
  // Solo uno de la lista, tal cual (sin mayúsculas ni espacios): nada de lo que llegue sirve para indexar otra cosa.
  if (modo !== undefined && !MODOS.includes(modo as Modo)) return { error: "modo_invalido" };
  return { modo: (modo as Modo | undefined) ?? MODO_OMISION, idioma: (idioma as Idioma | undefined) ?? "es", maxMin: m };
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    if (req.method !== "POST" || url.pathname !== "/token") return noExiste();

    const secreto = req.headers.get("x-device-secret") ?? "";
    if (!env.LAB_DEVICE_SECRET || !secreto || !(await iguales(secreto, env.LAB_DEVICE_SECRET))) {
      registra("rechazo", { motivo: secreto ? "secreto_incorrecto" : "sin_secreto" });
      return noExiste();
    }
    if (!env.GEMINI_API_KEY_AQ) {
      registra("sin_llave");
      return json(503, { ok: false, error: "sin_llave" });
    }

    // Cuerpo opcional: {"modo":"servicios"|"isabella"|"flexible","idioma":"es"|"en","max_min":1..5}. Lo de la URL manda.
    if (Number(req.headers.get("content-length") ?? 0) > MAX_CUERPO) return json(400, { ok: false, error: "cuerpo_grande" });
    const texto = await req.text();
    if (texto.length > MAX_CUERPO) return json(400, { ok: false, error: "cuerpo_grande" });
    let cuerpoApp: Record<string, unknown> = {};
    if (texto.trim()) {
      try {
        const b: unknown = JSON.parse(texto);
        if (!b || typeof b !== "object" || Array.isArray(b)) return json(400, { ok: false, error: "json_invalido" });
        cuerpoApp = b as Record<string, unknown>;
      } catch {
        return json(400, { ok: false, error: "json_invalido" });
      }
    }
    const pedido = parametros(url, cuerpoApp);
    if ("error" in pedido) return json(400, { ok: false, error: pedido.error });
    const { modo, idioma, maxMin } = pedido;
    const setup = (SETUP as unknown as Setups)[modo]?.[idioma]?.[String(maxMin)];
    if (!setup?.model) {
      // src/setup.json no trae esa variante: se generó con otra versión de token.mjs. Falla cerrado, sin gastar tope.
      registra("setup_faltante", { modo, idioma, maxMin });
      return json(500, { ok: false, error: "setup_faltante" });
    }

    // Topes, fallando cerrados.
    const ahora = new Date();
    const topeMinuto = entero(env.TOPE_MINUTO, 3);
    const topeDia = entero(env.TOPE_DIA, 30);
    const p = partesCdmx(ahora);
    const kMin = `min:${ahora.toISOString().slice(0, 16)}`;
    const kDia = `dia:${p.year}-${p.month}-${p.day}`;
    let nMin: number;
    let nDia: number;
    try {
      const [a, b] = await Promise.all([env.TOPES.get(kMin), env.TOPES.get(kDia)]);
      nMin = Number(a ?? 0);
      nDia = Number(b ?? 0);
      if (!Number.isFinite(nMin) || !Number.isFinite(nDia)) throw new Error("contador ilegible");
    } catch {
      registra("topes_caidos", { fase: "leer" });
      return json(503, { ok: false, error: "topes_no_disponibles" });
    }
    if (nDia >= topeDia) {
      const resta = 86_400 - (Number(p.hour) * 3600 + Number(p.minute) * 60 + Number(p.second));
      registra("tope", { cual: "dia", n: nDia, tope: topeDia });
      return json(429, { ok: false, error: "tope_dia", tope: topeDia }, { "retry-after": String(Math.max(60, resta)) });
    }
    if (nMin >= topeMinuto) {
      registra("tope", { cual: "minuto", n: nMin, tope: topeMinuto });
      return json(429, { ok: false, error: "tope_minuto", tope: topeMinuto }, { "retry-after": String(60 - ahora.getUTCSeconds()) });
    }
    try {
      await Promise.all([
        env.TOPES.put(kMin, String(nMin + 1), { expirationTtl: 120 }),
        env.TOPES.put(kDia, String(nDia + 1), { expirationTtl: 2 * 86_400 }),
      ]);
    } catch {
      registra("topes_caidos", { fase: "escribir" });
      return json(503, { ok: false, error: "topes_no_disponibles" });
    }

    // Acuñar (REST v1beta, setup amarrado).
    const t0 = Date.now();
    const cuerpo = {
      uses: 1,
      expireTime: new Date(t0 + VENTANA_MS + maxMin * 60_000 + COLCHON_MS).toISOString(),
      newSessionExpireTime: new Date(t0 + VENTANA_MS).toISOString(),
      bidiGenerateContentSetup: setup,
    };
    const base = (env.GEMINI_BASE || BASE_GOOGLE).replace(/\/+$/, "");
    let r: Response;
    try {
      r = await fetch(`${base}/v1beta/auth_tokens`, {
        method: "POST",
        headers: { "x-goog-api-key": env.GEMINI_API_KEY_AQ, "content-type": "application/json" },
        body: JSON.stringify(cuerpo),
        signal: AbortSignal.timeout(10_000),
      });
    } catch {
      registra("gemini_sin_respuesta");
      return json(502, { ok: false, error: "gemini_sin_respuesta" });
    }
    const respuesta = await r.text();
    if (!r.ok) {
      // El mensaje de Google se registra (sirve para cuota o llave rechazada), tachando la llave por si viniera.
      registra("gemini_error", { status: r.status, mensaje: respuesta.split(env.GEMINI_API_KEY_AQ).join("«llave»").slice(0, 400) });
      return json(502, { ok: false, error: r.status === 429 ? "cuota_gemini" : "gemini", status: r.status });
    }
    let nombre: unknown = null;
    try {
      nombre = (JSON.parse(respuesta) as { name?: unknown }).name;
    } catch {
      /* se trata abajo */
    }
    if (typeof nombre !== "string" || !nombre.startsWith("auth_tokens/")) {
      registra("gemini_sin_token");
      return json(502, { ok: false, error: "gemini_sin_token" });
    }
    registra("token", { modo, idioma, maxMin, ms: Date.now() - t0, hoy: nDia + 1, tope_dia: topeDia });
    return json(200, {
      ok: true,
      token: nombre,
      modelo: setup.model.replace(/^models\//, ""),
      modo,
      idioma,
      maxMin,
      expira: cuerpo.expireTime,
      abrirAntes: cuerpo.newSessionExpireTime,
      quedanHoy: Math.max(0, topeDia - nDia - 1),
    });
  },
} satisfies ExportedHandler<Env>;
