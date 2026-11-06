#!/bin/bash
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
# Revisión estática de los scripts de llamadas, ANTES de usarlos en una llamada real (tropiezo de la llamada 2, 27-oct):
#   1) bash -n (sintaxis).
#   2) Una variable sin llaves pegada a un carácter no ASCII (comillas latinas, puntos suspensivos, flechas, punto medio):
#      en macOS el bash 3.2 con locale UTF-8 toma el primer byte del carácter como parte del nombre y, con set -u, el
#      script muere con «unbound variable» justo en esa línea. Se escribe con llaves: ${FRASE} y no $FRASE seguido de «».
# Uso: herramientas/llamadas/revisar.sh [archivo.sh …]   (sin argumentos: todos los .sh de herramientas/llamadas)
# Sale con 1 si encuentra algo; fisica.sh, prueba0.sh y fusible.sh lo corren sobre sí mismos antes de marcar.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
if [ $# -gt 0 ]; then archivos=("$@"); else archivos=("$DIR"/*.sh); fi
fallas=0
for f in "${archivos[@]}"; do
    if ! bash -n "$f" 2>/dev/null; then
        echo "❌ sintaxis: $f"; bash -n "$f" 2>&1 | sed 's/^/   /'; fallas=$((fallas + 1))
    fi
    hallazgos="$(LC_ALL=C grep -n -E '\$[A-Za-z_][A-Za-z0-9_]*[^ -~[:space:]]' "$f")"
    if [ -n "$hallazgos" ]; then
        echo "❌ variable sin llaves pegada a un carácter no ASCII (escríbela con llaves) en $f:"
        echo "$hallazgos" | sed 's/^/   /'
        fallas=$((fallas + 1))
    fi
done
if [ "$fallas" = 0 ]; then echo "✅ ${#archivos[@]} script(s) sin problemas."; else echo "❌ ${fallas} problema(s)."; exit 1; fi
