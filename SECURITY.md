# Seguridad

## Este repositorio no trae llaves
No contiene llaves de API, tokens, secretos ni datos personales. Todo lo que es secreto lo pones **tú** y vive fuera
de git (ver `.gitignore`):

| Secreto | Dónde vive |
|---|---|
| Llave de Gemini (`GEMINI_API_KEY_AQ`) | Solo en tu Worker de Cloudflare (`npx wrangler secret put`) |
| Secreto del aparato (`LAB_DEVICE_SECRET`) | En tu Worker, en `secretos/` y en el almacenamiento privado de la app |
| Llave de firma del APK | `llaves/` + contraseña en el Llavero de macOS |
| URL de tu Worker y tus números | `mi_config.env` |

## Antes de publicar tu copia
```bash
python3 herramientas/escanear_secretos.py .
```
Puedes pasarle `--denylist <archivo>` con tus propios valores (tu número, la serie de tu cel), guardado fuera del repo.

## Si se filtra algo
- **Llave de Gemini:** bórrala en <https://aistudio.google.com/apikey>, crea otra y súbela de nuevo con
  `npx wrangler secret put GEMINI_API_KEY_AQ`. El celular no se toca.
- **Secreto del aparato:** el daño máximo son los topes del Worker (30 tokens al día). Genera otro borrando solo
  `secretos/device_secret.txt` y corre `./configurar_app.sh`.

## Reportar una vulnerabilidad
Escribe por los canales de contacto de <https://viajatech.com>. No publiques detalles explotables antes de que se
corrijan.
