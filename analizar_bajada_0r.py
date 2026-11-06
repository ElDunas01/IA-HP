#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
"""Análisis mínimo del WAV capturado por E-1 en la prueba 0r.

Mide:
  - energía (RMS global en dBFS)
  - detección de voz humana (banda 100–3400 Hz vs. ruido >3400 Hz)
  - detección de tono de 1 kHz (confirma que I-1/I-2/I-3 inyectó algo que
    tu otro teléfono recibió y lo tenemos de vuelta por acople: NO esperado si la
    captura es solo la bajada de la red)
  - detección del chirp 500→2000 Hz (lo mismo: NO esperado en bajada limpia)

Veredicto esperado (0r correcta):
  - RMS > −45 dBFS (si hablaste)
  - voz_humana = sí
  - tono_1k = no, chirp = no (la red no mezcla la subida con la bajada)

Si el tono o el chirp aparecen en la bajada, significa que E-1 no está
leyendo la bajada limpia sino un mezclado — algo salió mal con la ruta.

Uso:
    ./analizar_bajada_0r.py captura.wav
"""
import math
import struct
import sys
import wave


def rms_db(muestras: list[int]) -> float:
    if not muestras:
        return -120.0
    e = sum(x * x for x in muestras) / len(muestras)
    if e <= 0:
        return -120.0
    r = math.sqrt(e)
    return 20 * math.log10(r / 32767.0) if r > 0 else -120.0


def energia_banda(muestras: list[int], rate: int, f_lo: float, f_hi: float) -> float:
    """Energía en una banda con DFT ingenua a bins fijos (0.5 s de ventana).

    Para una prueba de 5 s basta; evitamos numpy/scipy para no sumar
    dependencias. 0 Hz excluido para quitar el DC.
    """
    n = min(len(muestras), rate // 2)
    if n < 32:
        return 0.0
    bin_lo = max(1, int(f_lo * n / rate))
    bin_hi = min(n // 2, int(f_hi * n / rate))
    total = 0.0
    for k in range(bin_lo, bin_hi + 1):
        re = 0.0
        im = 0.0
        for i in range(n):
            ang = 2 * math.pi * k * i / n
            re += muestras[i] * math.cos(ang)
            im -= muestras[i] * math.sin(ang)
        total += (re * re + im * im)
    return total


def main() -> int:
    if len(sys.argv) != 2:
        print("Uso: analizar_bajada_0r.py captura.wav", file=sys.stderr)
        return 2

    ruta = sys.argv[1]
    with wave.open(ruta, "rb") as w:
        rate = w.getframerate()
        ch = w.getnchannels()
        sw = w.getsampwidth()
        if ch != 1 or sw != 2:
            print(f"✗ Formato inesperado: {ch} canales, {sw*8} bits (se esperaba 1 canal, 16 bits)", file=sys.stderr)
            return 1
        pcm = w.readframes(w.getnframes())

    muestras = list(struct.unpack(f"<{len(pcm)//2}h", pcm))
    print(f"→ {ruta}: {rate} Hz mono PCM16, {len(muestras)/rate:.2f} s, {len(muestras)} muestras")

    db = rms_db(muestras)
    print(f"→ RMS global: {db:+.1f} dBFS")

    # Para energía por banda, usamos una ventana de 0.5 s desde el centro
    # (donde más probable que haya habido voz continuada).
    centro = len(muestras) // 2
    media_ventana = rate // 4
    v = muestras[max(0, centro - media_ventana): centro + media_ventana]

    e_voz = energia_banda(v, rate, 100.0, 3400.0)
    e_alto = energia_banda(v, rate, 3400.0, min(rate / 2 - 100, 7000.0))
    e_1k = energia_banda(v, rate, 950.0, 1050.0)
    e_chirp = energia_banda(v, rate, 500.0, 2000.0) - e_1k  # 500-2000 menos el bin del tono

    ratio_voz = e_voz / (e_alto + 1e-6)
    print(f"→ Banda voz (100-3400 Hz) / banda alta (>3400 Hz): ratio {ratio_voz:.2f}")
    voz_humana = ratio_voz > 3.0 and db > -45
    print(f"   Voz humana en la bajada: {'SÍ' if voz_humana else 'NO'}")

    print(f"→ Energía en banda 950-1050 Hz (tono): {e_1k:.2e}")
    print(f"→ Energía en banda 500-2000 Hz sin tono (chirp): {e_chirp:.2e}")
    print(f"   Tono 1 kHz en la bajada: {'SÍ (anómalo)' if e_1k > 1e14 else 'no'}")
    print(f"   Chirp en la bajada:      {'SÍ (anómalo)' if e_chirp > 5e13 else 'no'}")

    print()
    if voz_humana and e_1k < 1e14 and e_chirp < 5e13:
        print("✅ Veredicto: bajada contiene tu voz, SIN acople de Isabella (0r pasa por E-1).")
        return 0
    if not voz_humana and db < -55:
        print("⚠️  Bajada casi silenciosa; ¿hablaste? ¿se abrió el mezclador VOC_REC_DL?")
        return 1
    print("⚠️  Veredicto no limpio; revisa los números arriba y el logcat de la llamada.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
