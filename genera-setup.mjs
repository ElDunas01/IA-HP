#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
// SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
// Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
//
// Genera token-worker/src/setup.json: las 30 variantes (modo × idioma × max_min) que el Worker amarra en cada token.
// Uso (desde token-worker/):  npm run genera-setup
import { MODOS, MODELO_LIVE, setupCable } from "./lib/isabella.mjs";

const out = {
  generado: new Date().toISOString(),
  fuente: "puente/genera-setup.mjs (persona en puente/lib/isabella.mjs) · Agente Isabella por David Ruiz Duarte (viajatech)",
  uso: 'setup = modo (servicios|isabella|flexible) → idioma (es|en) → "max_min" (1 a 5); el Worker elige la variante y la amarra en el token',
};
for (const modo of MODOS) {
  out[modo] = {};
  for (const idioma of ["es", "en"]) {
    out[modo][idioma] = {};
    for (let m = 1; m <= 5; m++) out[modo][idioma][String(m)] = setupCable({ modelo: MODELO_LIVE, idioma, maxMin: m, conColgar: true, modo });
  }
}
process.stdout.write(JSON.stringify(out, null, 2) + "\n");
