// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
// Worker de tokens FALSO para probar el cliente de la app contra la tabla de §2.1 (sin wrangler, sin llaves).
// Contrato nuevo (2.4): idioma, max_min y modo en la URL (manda) o en el cuerpo; responde maxMin y expira =
// ahora + 60 + max_min·60 + 30 s, como token-worker/src/index.ts. El caso «viejo» responde como el Worker de las 21:03.
import http from "node:http";
const SECRETO = "secreto-falso-123";
export function arrancaTrabajador({ puerto, ip = "127.0.0.1" }) {
  const cuentas = {};
  const peticiones = [];
  let n = 0;
  const json = (res, code, obj, extra = {}) => { res.writeHead(code, { "content-type": "application/json", ...extra }); res.end(JSON.stringify(obj)); };
  const ok = (res, maxMin, modo) => json(res, 200, { ok: true, token: `auth_tokens/falso-w${++n}`, modelo: "gemini-3.8-live", idioma: "es", maxMin, modo, expira: new Date(Date.now() + (60 + maxMin * 60 + 30) * 1000).toISOString(), abrirAntes: new Date(Date.now() + 60e3).toISOString(), quedanHoy: 29 });
  const viejo = (res) => json(res, 200, { ok: true, token: `auth_tokens/falso-w${++n}`, modelo: "gemini-3.8-live", idioma: "es", expira: new Date(Date.now() + 360e3).toISOString(), abrirAntes: new Date(Date.now() + 60e3).toISOString(), quedanHoy: 29 });
  const srv = http.createServer((req, res) => {
    let cuerpo = "";
    req.on("data", (d) => (cuerpo += d));
    req.on("end", () => {
      const [u, consulta = ""] = req.url.split("?");
      let m = /^\/cuenta\/(\w+)$/.exec(u);
      if (m) { res.writeHead(200); return res.end(String(cuentas[m[1]] ?? 0)); }
      peticiones.push({ metodo: req.method, ruta: u, consulta, secretoOk: req.headers["x-device-secret"] === SECRETO, tipo: req.headers["content-type"], cuerpo });
      if (req.method !== "POST") return json(res, 404, { ok: false, error: "no_existe" });
      if (req.headers["x-device-secret"] !== SECRETO) return json(res, 404, { ok: false, error: "no_existe" });
      let j; try { j = JSON.parse(cuerpo || "{}"); } catch { return json(res, 400, { ok: false, error: "json_invalido" }); }
      if (j.idioma !== "es") return json(res, 400, { ok: false, error: "json_invalido" });
      const q = new URLSearchParams(consulta);
      const maxMin = Number(q.get("max_min") ?? j.max_min ?? 3);
      const modo = q.get("modo") ?? j.modo ?? "servicios";
      if (!Number.isInteger(maxMin) || maxMin < 1 || maxMin > 5) return json(res, 400, { ok: false, error: "max_min_invalido" });
      if (u === "/token") return ok(res, maxMin, modo);
      m = /^\/caso\/(\w+)\/token$/.exec(u);
      if (!m) return json(res, 404, { ok: false, error: "no_existe" });
      const caso = m[1];
      const k = (cuentas[caso] = (cuentas[caso] ?? 0) + 1);
      switch (caso) {
        case "503": return k === 1 ? json(res, 503, { ok: false, error: "topes_no_disponibles" }) : ok(res, maxMin, modo);
        case "429": return k === 1 ? json(res, 429, { ok: false, error: "tope_minuto", tope: 3 }, { "retry-after": "1" }) : ok(res, maxMin, modo);
        case "viejo": return viejo(res);
        case "corto": return ok(res, Math.max(1, maxMin - 1), modo);
        case "maxmin": return json(res, 400, { ok: false, error: "max_min_invalido" });
        case "setup": return json(res, 500, { ok: false, error: "setup_faltante" });
        case "dia": return json(res, 429, { ok: false, error: "tope_dia", tope: 30 }, { "retry-after": "3600" });
        case "cuota": return json(res, 502, { ok: false, error: "cuota_gemini" });
        case "502doble": return json(res, 502, { ok: false, error: "gemini" });
        case "sintoken": return json(res, 200, { ok: true });
        case "sinllave": return json(res, 503, { ok: false, error: "sin_llave" });
        default: return json(res, 404, { ok: false, error: "no_existe" });
      }
    });
  });
  return new Promise((r) => srv.listen(puerto, ip, () => r({ peticiones, cuentas, cierra: () => new Promise((z) => srv.close(() => z())) })));
}
