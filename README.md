# token-worker — Worker de tokens de Agente Isabella

> Agente Isabella — creado por David Ruiz Duarte (viajatech) · https://viajatech.com

Guarda **tu** llave de Gemini (`GEMINI_API_KEY_AQ`) y entrega a la app tokens efímeros de un solo uso con la persona de
Isabella amarrada (`src/setup.json`). La llave nunca llega al celular.

```bash
npm install
npx wrangler login
npx wrangler kv namespace create TOPES        # pega el id en wrangler.jsonc
npx wrangler secret put GEMINI_API_KEY_AQ     # en TU Terminal (entrada oculta)
npx wrangler deploy                           # → https://agente-isabella-token.<subdominio>.workers.dev/token
npm run genera-setup                          # tras editar ../puente/lib/isabella.mjs
npm run prueba                                # 72 verificaciones locales, sin llaves reales (requiere ../puente: npm install)
```
`LAB_DEVICE_SECRET` lo sube `../configurar_app.sh`. Guía completa: `../AGENTS.md` (fase 3).
