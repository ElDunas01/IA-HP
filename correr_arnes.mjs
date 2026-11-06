// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
// Prueba en la Mac, sin cel ni cuota: Gemini falso (puente/mock-live.mjs) + Worker falso (tabla de la espec §2.1)
// + el lado Java de la app (SesionGemini, ClienteWebSocket, Tokens). Se corre con ./correr.sh; ver el README de la app.
import { spawn } from "node:child_process";
import fs from "node:fs";
import { arrancaMock } from "../../../puente/mock-live.mjs";
import { SISTEMA, setupMinimo } from "../../../puente/lib/isabella.mjs";
import { arrancaTrabajador } from "./trabajador_falso.mjs";

const [PM, PW, CLASES] = [Number(process.argv[2]), Number(process.argv[3]), process.argv[4]];
const APP = new URL("../src/com/viajatech/lab/isabellallamadas/", import.meta.url).pathname;
let fallas = 0, pasan = 0;
const comprueba = (ok, que) => { console.log(`${ok ? "PASA " : "FALLA"} ${que}`); ok ? pasan++ : fallas++; };

const mock = await arrancaMock({ puerto: PM, ip: "127.0.0.1" });
const trab = await arrancaTrabajador({ puerto: PW });
let salida = "";
const codigo = await new Promise((r) => {
  const j = spawn("java", ["-cp", CLASES, "com.viajatech.lab.isabellallamadas.PruebaMac", String(PM), String(PW), CLASES], { stdio: ["ignore", "pipe", "inherit"] });
  j.stdout.on("data", (d) => { salida += d; process.stdout.write(d); });
  j.on("exit", (c) => r(c));
});
const st = mock.stats;
console.log("\n--- Lo que vio el Gemini falso ---");
comprueba(st.conexiones.length === 1, `una sola conexión al mock (${st.conexiones.length})`);
const con = st.conexiones[0] ?? {};
comprueba(con.ruta === "BidiGenerateContentConstrained", `método ${con.ruta}`);
comprueba(con.token === "auth_tokens/falso-1" && !con.llaveEnCabecera && !con.llaveEnUrl, "token efímero en ?access_token=, sin llave");
comprueba(con.cierre?.code === 1000, `la app cerró con 1000 (${JSON.stringify(con.cierre)})`);
comprueba(JSON.stringify(st.setups[0]) === JSON.stringify(setupMinimo("gemini-3.8-live")), `setup idéntico al setupMinimo del puente: ${JSON.stringify(st.setups[0])}`);
comprueba(st.marcosCliente.binario === 0 && st.marcosCliente.texto > 100, `la app manda solo marcos de texto (${JSON.stringify(st.marcosCliente)})`);
// El mock cuenta floor(largo_base64·3/4): con 640 bytes de PCM son 856 caracteres → 642 (incluye 2 de relleno).
comprueba(st.audio > 100 && st.audioBytes === st.audio * 642, `micrófono en tramas de 640 bytes de PCM (${st.audio} mensajes, ${st.audioBytes} bytes según el mock)`);
const tr = st.toolResponses[0]?.functionResponses?.[0];
// El id es el del toolCall del mock («fc-colgar-N» hasta el 27-oct 21:50, «call_falso_<turno>» desde entonces); lo demás, exacto.
const idDelMock = typeof tr?.id === "string" && /^(fc-colgar-\d+|call_falso_\d+)$/.test(tr.id);
comprueba(idDelMock && JSON.stringify({ ...tr, id: "ID" }) === JSON.stringify({ id: "ID", name: "colgar", response: { resultado: "ok" }, scheduling: "SILENT" }), `toolResponse exacto, con el id del toolCall (${JSON.stringify(tr)})`);
comprueba(st.interrupciones >= 1, `interrupciones en el mock: ${st.interrupciones}`);
const cc = st.clientContent[0];
comprueba(cc?.turnComplete === true && cc?.turns?.[0]?.role === "user" && cc?.turns?.[0]?.parts?.[0]?.text === SISTEMA.aviso("es", 25, true), "aviso por clientContent con el texto exacto del puente");
comprueba(st.textos.includes("Sistema: prueba por realtimeInput."), "aviso por realtimeInput.text llegó");
const grabFuente = fs.readFileSync(APP + "Grabacion.java", "utf8");
const avisoGrab = /TEXTO_AVISO = "([^"]*)";/.exec(grabFuente)?.[1];
const ctxGrab = st.clientContent.filter((c) => c.turns?.[0]?.parts?.[0]?.text === avisoGrab);
comprueba(!!avisoGrab && ctxGrab.length === 1 && ctxGrab[0].turnComplete === false && ctxGrab[0].turns[0].role === "user",
  `2.5: el aviso de grabación llegó una vez como clientContent con turnComplete:false (${JSON.stringify(ctxGrab)})`);
console.log("\n--- Textos «Sistema:» contra puente/lib/isabella.mjs ---");
const aviso25 = /^AVISO25=(.*)$/m.exec(salida)?.[1];
comprueba(aviso25 === SISTEMA.aviso("es", 25, true), "SesionGemini.sistemaAviso(25) = SISTEMA.aviso('es', 25, true)");
const fuente = fs.readFileSync(APP + "LlamadaService.java", "utf8");
const lit = (n) => new RegExp(`${n} = "([^"]*)";`).exec(fuente)?.[1];
comprueba(lit("SISTEMA_SALUDO") === SISTEMA.saludo.es, "SISTEMA_SALUDO = SISTEMA.saludo.es");
comprueba(lit("SISTEMA_SILENCIO") === SISTEMA.silencio.es, "SISTEMA_SILENCIO = SISTEMA.silencio.es");
console.log("\n--- Candados de fuente: micrófono y tope de timbre (LlamadaService, ServicioEnLlamada) ---");
// Método que encierra la línea i (declaraciones con 4 espacios de sangría: los de la clase, no los del oyente).
const lineas = fuente.split("\n");
const metodoDe = (i) => {
  for (let k = i; k >= 0; k--) {
    const m = /^    (?:(?:private|public|protected|static|final|synchronized) )*[\w<>\[\]]+ (\w+)\([^)]*\)\s*\{\s*$/.exec(lineas[k]);
    if (m) return m[1];
  }
  return "?";
};
// Una declaración (de cualquier tipo de retorno) no es una llamada.
const esDecl = (l, n) => {
  const m = new RegExp(`^\\s*(?:(?:private|public|protected|static|final|synchronized) )*([\\w<>\\[\\]]+) ${n}\\(`).exec(l);
  return !!m && !["return", "new", "else", "throw", "case"].includes(m[1]);
};
const llamadasA = (n) => lineas.flatMap((l, i) => (new RegExp(`\\b${n}\\(`).test(l) && !esDecl(l, n) ? [metodoDe(i)] : [])).sort();
const igual = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const conv = llamadasA("empezarConversacion");
comprueba(igual(conv, ["activaReal", "aperturaClasicaAprox", "sesionLista"]), `la conversación empieza solo en STATE_ACTIVE real, en la ruta clásica o sin llamada (${conv.join(", ")})`);
const capt = llamadasA("iniciarCaptura");
comprueba(igual(capt, ["diagGrabar", "empezarConversacion"]), `el micrófono a Gemini solo se abre al empezar la conversación (y al reanudar tras diagGrabar) (${capt.join(", ")})`);
const refActiva = lineas.flatMap((l, i) => (/\bactivaReal\b/.test(l) && !/void activaReal\(/.test(l) ? [metodoDe(i)] : []));
comprueba(igual(refActiva, ["alActivaReal"]), `activaReal solo la llama el puente del InCallService (${refActiva.join(", ")})`);
const tope = llamadasA("armarTopeTimbre");
comprueba(igual(tope, ["alMarcandoReal", "decidirTrasOffhook", "ultimaOportunidadLigado"]), `el tope de timbre solo se arma desde el InCallService (DIALING u OFFHOOK ligado, también si liga tarde) (${tope.join(", ")})`);
const ics = fs.readFileSync(APP + "ServicioEnLlamada.java", "utf8").split("\n");
const junto = (n, estado) => ics.every((l, i) => !l.includes(`LlamadaService.${n}()`) || l.includes(estado) || (ics[i - 1] ?? "").includes(estado));
const cuantas = (n) => ics.filter((l) => l.includes(`LlamadaService.${n}()`)).length;
comprueba(cuantas("alActivaReal") === 2 && junto("alActivaReal", "Call.STATE_ACTIVE"), "el InCallService avisa la activa solo con Call.STATE_ACTIVE");
comprueba(cuantas("alMarcandoReal") === 2 && junto("alMarcandoReal", "Call.STATE_DIALING"), "el InCallService arma el tope solo con Call.STATE_DIALING");
comprueba(ics.some((l) => l.includes("DIRECTION_INCOMING")) && ics.some((l) => l.includes("if (call != llamada) return;")), "el InCallService ignora entrantes y solo reporta el colgado de la llamada que sigue");
// Cuerpo del método (hasta su «    }»), sin comentarios: tiene que leer s.enCurso antes de actuar.
const cuerpo = (n) => {
  const i = lineas.findIndex((l) => l.includes(`static void ${n}(`));
  if (i < 0) return [];
  const fin = lineas.findIndex((l, k) => k > i && /^    \}\s*$/.test(l));
  return lineas.slice(i + 1, fin).map((l) => l.replace(/\/\/.*$/, ""));
};
const puentes = ["alActivaReal", "alMarcandoReal", "alColgadaReal"].every((n) => cuerpo(n).some((l) => /\bs\.enCurso\b/.test(l)));
comprueba(puentes, "los tres puentes del InCallService exigen una llamada o sesión de la app en curso");
console.log("\n--- Candados de la 2.4: ruta estricta y fin ordenado (LlamadaService) ---");
// Cuerpo de un método de la clase (cualquier modificador), sin comentarios; `params` distingue sobrecargas.
const cuerpoDe = (n, params = "[^)]*") => {
  const decl = new RegExp(`^    (?:(?:private|public|protected|static|final|synchronized) )*[\\w<>\\[\\]]+ ${n}\\(${params}\\)\\s*\\{\\s*$`);
  const i = lineas.findIndex((l) => decl.test(l));
  if (i < 0) return [];
  const fin = lineas.findIndex((l, k) => k > i && /^    \}\s*$/.test(l));
  return lineas.slice(i + 1, fin).map((l) => l.replace(/\/\/.*$/, ""));
};
// (1) En iniciar(), la ruta se decide ANTES de abrir la sesión (que pide el token): sin el appop no se gasta nada.
const ini = cuerpoDe("iniciar");
const iDecide = ini.findIndex((l) => /\bRuta\.decidir\(/.test(l));
const iNoMarcar = ini.findIndex((l, k) => k > iDecide && /Ruta\.NO_MARCAR\.equals\(ruta\)/.test(l));
const tramoNoMarcar = iNoMarcar >= 0 ? ini.slice(iNoMarcar, iNoMarcar + 6).join("\n") : "";
const prep = ini.flatMap((l, k) => (/\bprepararSesion\(\)/.test(l) ? [k] : []));
const deIsabellaSola = (k) => { for (let j = k; j >= 0; j--) if (/^\s*if \(/.test(ini[j])) return /MODO_ISABELLA\.equals\(modo\)/.test(ini[j]); return false; };
comprueba(iDecide >= 0 && iNoMarcar > iDecide && /terminar\([^;]*, true\);/.test(tramoNoMarcar) && /\breturn;/.test(tramoNoMarcar)
  && prep.some((k) => k > iNoMarcar) && prep.filter((k) => k < iDecide).every(deIsabellaSola),
  "iniciar(): Ruta.decidir va antes de prepararSesion (el token) y NO_MARCAR termina sin colgar ni pedir nada (salvo «Isabella sin llamada», que no marca)");
comprueba(igual(llamadasA("pedirAlWorker"), ["obtenerToken"]) && igual(llamadasA("obtenerToken"), ["prepararSesion"]),
  `el token solo se pide en obtenerToken, y a este solo lo llama prepararSesion (${llamadasA("pedirAlWorker").join(", ")} · ${llamadasA("obtenerToken").join(", ")})`);
const marc = cuerpoDe("marcarAhora");
const iRevisa = marc.findIndex((l) => /Ruta\.NO_MARCAR\.equals\(Ruta\.decidir\(/.test(l));
const iMarca = marc.findIndex((l) => /^\s*marcar\(\);/.test(l));
comprueba(iRevisa >= 0 && iMarca > iRevisa && /terminar\(/.test(marc.slice(iRevisa, iRevisa + 4).join("\n")), "marcarAhora(): vuelve a revisar el appop justo antes de marcar");
// (2) La ruta clásica tiene candado propio: sin --es ruta_clasica 1 nunca abre la conversación.
const apc = cuerpoDe("aperturaClasicaAprox");
const iGuardia = apc.findIndex((l) => /if \(!rutaClasica\)/.test(l));
const tramoGuardia = iGuardia >= 0 ? apc.slice(iGuardia, iGuardia + 5).join("\n") : "";
const iActiva = apc.findIndex((l) => /estadoLlamada = "active"/.test(l));
const iConvClasica = apc.findIndex((l) => /\bempezarConversacion\(/.test(l));
comprueba(iGuardia >= 0 && /terminar\(/.test(tramoGuardia) && /\breturn;/.test(tramoGuardia) && iActiva > iGuardia && iConvClasica > iGuardia,
  "aperturaClasicaAprox(): sin --es ruta_clasica 1 cuelga antes de dar la línea por activa o abrir la conversación");
// (3) terminar(): cada paso con su guardia; colgar va después de cerrar la sesión y nada se llama suelto.
const ter = cuerpoDe("terminar", "String motivo, boolean noColgar");
const iCierra = ter.findIndex((l) => /paso\("cerrar la sesión", s::cerrar\)/.test(l));
const iCuelga = ter.findIndex((l) => /paso\("colgar", this::colgarLinea\)/.test(l));
const sueltos = ter.filter((l) => /\b(?:s\.cerrar|colgarLinea|pararTonoSostenido|detenerCaptura|detenerReproduccion|soltarAltavoz|soltarCandados|quitarCallback|cerrarGrabacion)\(\)/.test(l));
comprueba(iCierra >= 0 && iCuelga > iCierra && sueltos.length === 0,
  `terminar(): cerrar la sesión y colgar van en su paso(…) (si uno truena, el otro corre), colgar después (${sueltos.length} llamadas sueltas)`);
console.log("\n--- Candados de la 2.5: grabación manual (LlamadaService, MainActivity, Grabacion) ---");
const sinComentario = (l) => l.replace(/\/\/.*$/, "");
// Método del oyente (8 espacios de sangría) que encierra la línea i.
const metodoInternoDe = (i) => {
  for (let k = i; k >= 0; k--) {
    const m = /^        (?:(?:private|public|protected|static|final|synchronized) )*[\w<>\[\]]+ (\w+)\([^)]*\)\s*\{\s*$/.exec(lineas[k]);
    if (m) return m[1];
  }
  return "?";
};
// (1) La grabación solo se abre en abrirGrabacion, detrás de la guardia de la orden, y esta solo al empezar la
// conversación, antes de la bocina y del micrófono.
const abren = lineas.flatMap((l, i) => (sinComentario(l).includes("Grabacion.abrir(") ? [metodoDe(i)] : []));
const ag = cuerpoDe("abrirGrabacion", "String motivo");
const iGuardiaG = ag.findIndex((l) => /if \(!grabar\b[^)]*\) return;/.test(l));
const iAbreG = ag.findIndex((l) => l.includes("Grabacion.abrir("));
comprueba(igual(abren, ["abrirGrabacion"]) && iGuardiaG >= 0 && iAbreG > iGuardiaG,
  `la grabación solo se abre en abrirGrabacion, detrás de «if (!grabar …) return;» (${abren.join(", ")})`);
const ec = cuerpoDe("empezarConversacion");
const iAbrirG = ec.findIndex((l) => /\babrirGrabacion\(/.test(l));
const iRepro = ec.findIndex((l) => /\biniciarReproduccion\(\)/.test(l));
const iCaptEc = ec.findIndex((l) => /\biniciarCaptura\(\)/.test(l));
comprueba(igual(llamadasA("abrirGrabacion"), ["empezarConversacion"]) && iAbrirG >= 0 && iAbrirG < iRepro && iAbrirG < iCaptEc,
  `abrirGrabacion solo al empezar la conversación y antes de la bocina y el micrófono (${llamadasA("abrirGrabacion").join(", ")})`);
// (2) «grabar» se prende solo con el extra de ESTA orden (config no lo guarda) y cada orden empieza en falso.
const prenden = lineas.flatMap((l, i) => (/\bgrabar = (?!false;)/.test(sinComentario(l)) ? [metodoDe(i)] : []));
const acTxt = cuerpoDe("aplicarConfig", "Intent intent, boolean persistir").join("\n");
comprueba(igual(prenden, ["aplicarConfig"]) && /if \(persistir\) Bitacora\.log\([^;]*\);\s*else grabar = "1"\.equals\(/.test(acTxt)
  && cuerpoDe("prepararOrden").some((l) => /^\s*grabar = false;/.test(l)),
  `«grabar» solo lo prende el extra de la orden (config no lo guarda) y prepararOrden lo apaga (${prenden.join(", ")})`);
// (3) Nunca hay grabación sin aviso: el aviso va en sesionLista antes de marcar o de empezar, y si falla no se graba.
const sl = cuerpoDe("sesionLista");
const iAvisoG = sl.findIndex((l) => /\bavisarGrabacionAIsabella\(\)/.test(l));
const iMarcarSl = sl.findIndex((l) => /\bmarcarAhora\(\)/.test(l));
const iConvSl = sl.findIndex((l) => /\bempezarConversacion\(/.test(l));
comprueba(igual(llamadasA("avisarGrabacionAIsabella"), ["sesionLista"]) && iAvisoG >= 0 && iAvisoG < iMarcarSl && iAvisoG < iConvSl,
  "sesionLista(): el aviso de grabación va antes de marcar y antes de empezar la conversación");
const avTxt = cuerpoDe("avisarGrabacionAIsabella").join("\n");
comprueba(/^\s*if \(!grabar\) return;/.test(cuerpoDe("avisarGrabacionAIsabella").find((l) => l.trim()) ?? "") && /Grabacion\.revisar\(/.test(avTxt)
  && /enviarContexto\(Grabacion\.TEXTO_AVISO\)/.test(avTxt) && /if \(motivo != null\) \{\s*grabar = false;/.test(avTxt),
  "avisarGrabacionAIsabella(): revisa la carpeta, manda el contexto y, si algo falla, apaga «grabar»");
const agTxt = ag.join("\n");
comprueba(/catch \(IOException \| RuntimeException e\) \{\s*grabar = false;/.test(agTxt) && /enviarContexto\(Grabacion\.TEXTO_SIN_GRABAR\)/.test(agTxt),
  "abrirGrabacion(): si no abre, apaga «grabar» y le pide a Isabella que no lo mencione");
// (4) Respaldo del aviso: solo al terminar un turno con voz sin haberlo dicho, una vez.
const recs = lineas.flatMap((l, i) => (/\brecordarAvisoDeGrabacion\(\);/.test(sinComentario(l)) ? [i] : []));
const tramoRec = recs.length === 1 ? lineas.slice(recs[0] - 4, recs[0]).join("\n") : "";
comprueba(recs.length === 1 && metodoInternoDe(recs[0]) === "onIsabellaHabla"
  && tramoRec.includes(`if ("fin".equals(motivo) && conVoz && g != null && !grabacionMencionada && !recordatorioGrabacion) {`)
  && tramoRec.includes("recordatorioGrabacion = true;"),
  `el recordatorio solo sale del oyente al terminar un turno con voz sin la mención, una vez (${recs.map(metodoInternoDe).join(", ")})`);
// (5) Lo que se graba: el micrófono al leerlo, la voz al entrar a la bocina, las transcripciones, los fines de turno
// y cada aviso «Sistema:» que la app le manda a Isabella.
comprueba(/Grabacion g = grabacion;\s*if \(g != null\) g\.microfono\(trama, n\);/.test(cuerpoDe("bucleCaptura").join("\n"))
  && /Grabacion g = grabacion;\s*if \(g != null\) g\.isabella\(chunk\);/.test(cuerpoDe("bucleReproduccion").join("\n"))
  && fuente.includes("if (g != null) g.texto(quien, texto);") && fuente.includes(`if (g != null) g.finTurno("interrumpida".equals(motivo));`),
  "ganchos: micrófono, bocina, transcripciones y fines de turno van a la grabación");
const envios = lineas.flatMap((l, i) => (/\.enviarSistema(?:Realtime)?\(/.test(sinComentario(l)) ? [i] : []));
const anotados = envios.filter((i) => /^\s*anotarSistema\(/.test(lineas[i + 1] ?? ""));
comprueba(envios.length === 5 && anotados.length === envios.length, `cada aviso «Sistema:» de la app va también a la transcripción (${anotados.length}/${envios.length})`);
// (6) Se cierra en terminar() con su paso (después de la bocina) y en onDestroy.
const iBocinaT = ter.findIndex((l) => /paso\("detener la bocina", this::detenerReproduccion\)/.test(l));
const iCierraG = ter.findIndex((l) => /paso\("cerrar la grabación", this::cerrarGrabacion\)/.test(l));
comprueba(iBocinaT >= 0 && iCierraG > iBocinaT && cuerpoDe("onDestroy").some((l) => /^\s*cerrarGrabacion\(\);/.test(l)),
  "terminar(): «cerrar la grabación» en su paso, después de la bocina; onDestroy también la cierra");
// (7) El número no va a la grabación y nada de la app borra archivos.
const egTxt = cuerpoDe("encabezadoGrabacion", "String motivo").join("\n");
comprueba(egTxt.length > 0 && !/\bnumero\b/.test(egTxt) && !/\bnumero\b/.test(grabFuente.split("\n").map(sinComentario).join("\n")),
  "el número marcado no va a la grabación (ni el encabezado ni Grabacion.java lo tocan)");
const javas = fs.readdirSync(APP).filter((f) => f.endsWith(".java"));
const borran = javas.filter((f) => /\.delete\(|deleteOnExit|Files\.delete|deleteIfExists/.test(fs.readFileSync(APP + f, "utf8")));
comprueba(javas.length >= 20 && borran.length === 0, `ningún .java de la app borra archivos (${javas.length} revisados${borran.length ? "; borran: " + borran.join(", ") : ""})`);
// (8) La pantalla: el interruptor arranca apagado, no se guarda, se apaga al enviar y cancela una confirmación armada.
const ma = fs.readFileSync(APP + "MainActivity.java", "utf8");
const apagados = (ma.match(/chkGrabar\.setChecked\(false\);/g) ?? []).length;
comprueba(apagados === 2 && ma.includes("chkGrabar.setSaveEnabled(false);") && ma.includes(`if (grabaLaOrden(cmd)) i.putExtra(LlamadaService.EXTRA_GRABAR, "1");`)
  && /chkGrabar\.setOnCheckedChangeListener\(\(b, encendido\) ->\s*cancelarConfirmacion\(/.test(ma) && !/getSharedPreferences|Ajustes\.guardar/.test(ma),
  `pantalla: «Grabar» arranca apagado, no se guarda, se apaga al enviar y cancela la confirmación (${apagados} apagados)`);
console.log("\n--- Candados de la 2.6: bocina 0 (LlamadaService, ServicioEnLlamada, MainActivity) ---");
// (1) Cada orden empieza pidiendo la bocina; solo el extra de ESA orden la apaga, y config no lo guarda.
const prendenBocina = lineas.flatMap((l, i) => (/\bbocina = (?!true;)/.test(sinComentario(l)) ? [metodoDe(i)] : []));
comprueba(igual(prendenBocina, ["aplicarConfig"]) && /if \(persistir\) Bitacora\.log\([^;]*bocina[^;]*\);\s*else bocina = !"0"\.equals\(/.test(acTxt)
  && cuerpoDe("prepararOrden").some((l) => /^\s*bocina = true;/.test(l)),
  `«bocina 0» solo lo pone el extra de la orden (config no lo guarda) y prepararOrden vuelve a pedir la bocina (${prendenBocina.join(", ")})`);
// (2) Con bocina 0 nadie pide ruta: ponerAltavoz regresa antes de tocar nada, y es el único que llama a rutaAltavoz.
const pa = cuerpoDe("ponerAltavoz");
const iGuardiaPa = pa.findIndex((l) => /^\s*if \(!bocina\) \{/.test(l));
const iRutaAltavoz = pa.findIndex((l) => /ServicioEnLlamada\.rutaAltavoz\(\)/.test(l));
const antesGuardia = pa.slice(0, Math.max(iGuardiaPa, 0)).filter((l) => l.trim());
comprueba(iGuardiaPa >= 0 && antesGuardia.length === 0 && /\breturn;/.test(pa.slice(iGuardiaPa, iGuardiaPa + 4).join("\n")) && iRutaAltavoz > iGuardiaPa
  && igual(llamadasA("rutaAltavoz"), ["ponerAltavoz"]) && !pa.slice(0, iGuardiaPa + 1).some((l) => /setCommunicationDevice|setSpeakerphoneOn/.test(l)),
  `ponerAltavoz(): con bocina 0 regresa antes de pedir nada; rutaAltavoz solo se llama desde ahí (${llamadasA("rutaAltavoz").join(", ")})`);
// (3) Al marcar, el extra de altavoz de Telecom solo va con bocina (placeCall y el respaldo ACTION_CALL).
const extrasAltavoz = cuerpoDe("marcar").filter((l) => /EXTRA_START_CALL_WITH_SPEAKERPHONE/.test(l));
comprueba(extrasAltavoz.length === 2 && extrasAltavoz.every((l) => /^\s*if \(bocina\) /.test(l)),
  `marcar(): EXTRA_START_CALL_WITH_SPEAKERPHONE solo con bocina (${extrasAltavoz.length} usos, todos detrás de «if (bocina)»)`);
// (4) El InCallService anota la ruta en efecto (rutaActual) y cada cambio de ruta de la llamada que sigue.
const icsTxt = ics.join("\n");
comprueba(/static String rutaActual\(\)/.test(icsTxt) && /public void onCallAudioStateChanged\(CallAudioState audioState\)/.test(icsTxt)
  && /if \(llamada != null\) Bitacora\.log\("InCallService: ruta de audio → "/.test(icsTxt),
  "ServicioEnLlamada: rutaActual() y onCallAudioStateChanged anotan la ruta de Telecom en la bitácora");
// (5) La pantalla: «Sin bocina» arranca apagado, no se guarda, manda bocina 0 solo prendido y cancela la confirmación.
comprueba(ma.includes("chkSinBocina.setChecked(false);") && ma.includes("chkSinBocina.setSaveEnabled(false);")
  && ma.includes(`if (chkSinBocina.isChecked()) i.putExtra(LlamadaService.EXTRA_BOCINA, "0");`)
  && /chkSinBocina\.setOnCheckedChangeListener\(\(b, encendido\) ->\s*cancelarConfirmacion\(/.test(ma),
  "pantalla: «Sin bocina» arranca apagado, no se guarda, manda bocina 0 solo prendido y cancela la confirmación");
console.log("\n--- Lo que vio el Worker falso ---");
const posts = trab.peticiones.filter((p) => p.metodo === "POST");
// 2.4: idioma, max_min y modo en la URL (manda) y lo mismo en el cuerpo (para el Worker viejo).
const igualALaUrl = (p) => { const q = new URLSearchParams(p.consulta); return p.cuerpo === JSON.stringify({ idioma: q.get("idioma"), max_min: Number(q.get("max_min")), modo: q.get("modo") }); };
comprueba(posts.length > 0 && posts.every((p) => p.tipo === "application/json" && igualALaUrl(p)), `el cuerpo repite la consulta en las ${posts.length} peticiones`);
const otras = posts.filter((p) => p.consulta !== "idioma=es&max_min=3&modo=servicios");
comprueba(posts[0]?.cuerpo === '{"idioma":"es","max_min":3,"modo":"servicios"}' && otras.length === 1 && otras[0].consulta === "idioma=es&max_min=5&modo=flexible",
  `consulta idioma=es&max_min=3&modo=servicios en todas menos la del techo 5 (${otras.map((p) => p.consulta).join(" · ")})`);
comprueba(posts.filter((p) => !p.secretoOk).length === 1, "solo la petición del secreto malo llegó sin el secreto correcto");
comprueba(posts[0]?.ruta === "/token", `la URL sin ruta fue a /token (${posts[0]?.ruta})`);
await mock.cierra();
await trab.cierra();
console.log(`\nRESUMEN NODE: ${pasan} pasan, ${fallas} fallan · Java salió con ${codigo}`);
process.exit(fallas === 0 && codigo === 0 ? 0 : 1);
