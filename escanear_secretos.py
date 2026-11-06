#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech).
"""Escáner de secretos antes de publicar. Uso: python3 herramientas/escanear_secretos.py <carpeta> [--denylist archivo] [--full]
La denylist es un archivo con valores TUYOS (tu número, tu serie…), uno por línea; guárdala FUERA del repo.
Imprime archivo:línea  [patrón]  valor-enmascarado. Sale con 1 si encuentra algo."""
import os, re, sys

PATS = {
    "google_api_key": r"AIza[0-9A-Za-z_\-]{30,}",
    "github_token": r"\b(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})",
    "anthropic_openai": r"\bsk-(ant-)?[A-Za-z0-9_\-]{16,}",
    "slack_aws": r"\b(xox[abpr]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16})",
    "jwt": r"\beyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.",
    "private_key": r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    "asignacion_secreto": r"(?i)(secret|secreto|token|password|passwd|contrase(ñ|n)a|api[_-]?key|clave)[\"']?\s*[:=]\s*[\"'][A-Za-z0-9_\-+/=.]{12,}[\"']",
    "hex32+": r"\b[0-9a-fA-F]{32,}\b",
    "telefono_mx": r"(?<![\d.])(\+?52[\s-]?)?(1[\s-]?)?(55|56|33|81|7\d|9\d)[\s-]?\d{4}[\s-]?\d{4}(?![\d.])",
    "imei15": r"(?<![\d.])\d{15}(?![\d.])",
    "ip_privada": r"\b(192\.168|10\.\d{1,3}|172\.(1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b",
    "mac": r"\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\b",
    "correo": r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+\.[A-Za-z0-9.\-]+",
    "ruta_personal": r"(/Users/[A-Za-z0-9_.-]+|/Volumes/[A-Za-z0-9_.-]+|/home/[a-z0-9_]+)",
    "serie_samsung": r"\bR5[0-9A-Z]{9}\b",
    "adb_mdns": r"adb-[A-Za-z0-9]+-[A-Za-z0-9]{6}",
    "workers_dev": r"[a-z0-9\-]+\.workers\.dev",
}
IGN_DIRS = {".git", "node_modules"}
BIN_EXT = {".apk", ".gz", ".tar", ".jks", ".keystore", ".png", ".jpg", ".wav", ".dex", ".zip", ".so", ".jar", ".class"}

def mask(s):
    return s if len(s) <= 6 else s[:4] + "…" + s[-2:] + f"({len(s)})"

def main():
    root = sys.argv[1]
    full = "--full" in sys.argv
    deny = []
    if "--denylist" in sys.argv:
        with open(sys.argv[sys.argv.index("--denylist") + 1], encoding="utf-8") as fh:
            deny = [l.strip() for l in fh if l.strip() and not l.startswith("#")]
    hits = 0
    bins = []
    for dp, dns, fns in os.walk(root):
        dns[:] = [d for d in dns if d not in IGN_DIRS]
        for fn in fns:
            p = os.path.join(dp, fn)
            rel = os.path.relpath(p, root)
            if os.path.splitext(fn)[1].lower() in BIN_EXT:
                bins.append(rel)
                continue
            try:
                txt = open(p, encoding="utf-8", errors="replace").read()
            except Exception:
                continue
            for i, line in enumerate(txt.splitlines(), 1):
                for name, pat in PATS.items():
                    for m in re.finditer(pat, line):
                        v = m.group(0)
                        hits += 1
                        print(f"{rel}:{i}  [{name}]  {v if full else mask(v)}")
                for d in deny:
                    if d.lower() in line.lower():
                        hits += 1
                        print(f"{rel}:{i}  [DENYLIST]  {mask(d)}")
    if bins:
        print("\nBINARIOS (no escaneados por texto):")
        for b in bins:
            print("  ", b)
    print(f"\nTOTAL coincidencias: {hits}")
    sys.exit(1 if hits else 0)

main()
