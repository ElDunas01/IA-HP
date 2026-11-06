#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 David Ruiz Duarte (viajatech) <https://viajatech.com>
# SPDX-License-Identifier: LicenseRef-Agente-Isabella-1.0
# Agente Isabella · Autor: David Ruiz Duarte (viajatech). Uso no comercial y con atribución: ver LICENSE y NOTICE.
"""Genera un WAV de prueba para la 0r (AGENTS.md, fase 7).

Patrón de 4 s, inconfundible y fácil de identificar en tu otro teléfono:
    0.0 – 2.0 s   tono senoidal 1 kHz a −6 dBFS
    2.0 – 4.0 s   chirp lineal 500 Hz → 2000 Hz a −6 dBFS

Formato PCM 16-bit mono. Rate configurable (16000 por omisión: coincide con el
puerto incall_music_uplink / voice_tx del A72).

Uso:
    ./wav_prueba_0r.py salida.wav [--rate 16000]
"""
import argparse
import math
import struct
import sys
import wave


def tono_senoidal(rate: int, hz: float, segundos: float, amp: float) -> bytes:
    n = int(rate * segundos)
    pico = int(amp * 32767)
    out = bytearray()
    for i in range(n):
        v = int(pico * math.sin(2 * math.pi * hz * i / rate))
        out += struct.pack("<h", v)
    return bytes(out)


def chirp_lineal(rate: int, hz0: float, hz1: float, segundos: float, amp: float) -> bytes:
    n = int(rate * segundos)
    pico = int(amp * 32767)
    k = (hz1 - hz0) / segundos
    out = bytearray()
    for i in range(n):
        t = i / rate
        fase = 2 * math.pi * (hz0 * t + 0.5 * k * t * t)
        v = int(pico * math.sin(fase))
        out += struct.pack("<h", v)
    return bytes(out)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("salida", help="archivo .wav de salida")
    ap.add_argument("--rate", type=int, default=16000, help="sample rate (16000 por omisión)")
    ap.add_argument("--amp", type=float, default=0.5, help="amplitud 0.0–1.0 (−6 dBFS por omisión)")
    args = ap.parse_args()

    pcm = tono_senoidal(args.rate, 1000.0, 2.0, args.amp)
    pcm += chirp_lineal(args.rate, 500.0, 2000.0, 2.0, args.amp)

    with wave.open(args.salida, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(args.rate)
        w.writeframes(pcm)

    print(f"✓ {args.salida}: {args.rate} Hz mono PCM16, 4.0 s, {len(pcm)} bytes")
    print("  0.0–2.0 s: tono 1 kHz · 2.0–4.0 s: chirp 500→2000 Hz")
    return 0


if __name__ == "__main__":
    sys.exit(main())
