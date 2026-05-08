#!/usr/bin/env python3
"""
Generate Malayalam TTS MP3 files for Project Mudra using Google Cloud Neural2.

Usage:
    python3 generate_letter_sounds.py --key YOUR_GOOGLE_API_KEY

Outputs 70 MP3 files into the 'Letter sounds/' folder:
  - instructions.mp3        (app-open instruction sentence)
  - <unicode-hex>.mp3       (one file per unique keyboard character)

File naming: each character's Unicode codepoints in hex, joined by underscores.
  e.g.  ക  → 0D15.mp3
        ക്ക → 0D15_0D4D_0D15.mp3

Re-running the script skips files that already exist, so it is safe to resume
after a failure without re-generating completed files.
"""

import os
import sys
import json
import base64
import argparse
import time

try:
    import requests
except ImportError:
    sys.exit("Missing dependency: run  pip install requests  then retry.")

# ── Google Cloud TTS config ────────────────────────────────────────────────
API_URL      = "https://texttospeech.googleapis.com/v1/text:synthesize"
VOICE        = {"languageCode": "ml-IN", "name": "ml-IN-Neural2-A"}
AUDIO_CONFIG = {"audioEncoding": "MP3", "speakingRate": 0.85}

# ── Output folder ──────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR    = os.path.join(SCRIPT_DIR, "Letter sounds")

# ── All unique keyboard characters (inner + outer ring + all sub-menus) ────
CHARS = [
    # Outer ring
    'അ', 'ആ', 'ഇ', 'ഈ', 'ഉ', 'ഊ', 'ഋ', 'ൠ',
    'എ', 'ഏ', 'ഐ', 'ഒ', 'ഓ', 'ഔ',
    'അം', 'ം', 'ഃ', 'ഁ', '്',
    # Inner ring — stop consonants
    'ക', 'ഖ', 'ഗ', 'ഘ', 'ങ',
    'ച', 'ഛ', 'ജ', 'ഝ', 'ഞ',
    'ട', 'ഠ', 'ഡ', 'ഢ', 'ണ',
    'ത', 'ഥ', 'ദ', 'ധ', 'ന',
    'പ', 'ഫ', 'ബ', 'ഭ', 'മ',
    # Inner ring — sonorants / sibilants
    'യ', 'ര', 'ല', 'വ',
    'ശ', 'ഷ', 'സ', 'ഹ', 'ള', 'ഴ', 'റ',
    # Chillu (final consonants)
    'ൺ', 'ൻ', 'ർ', 'ൽ', 'ൾ',
    # Geminates (double consonants)
    'ക്ക', 'ച്ച', 'ട്ട', 'ത്ത', 'പ്പ', 'ന്ന', 'ല്ല', 'ള്ള', 'മ്മ',
]

# ── Instruction sentence (read aloud on app open) ─────────────────────────
INSTRUCTIONS_TEXT = (
    "അക്ഷരം ചേർക്കാൻ വിരൽ ഉയർത്തുക. "
    "സബ് മെനു ലഭിക്കാൻ ഒരു സെക്കൻഡ് അമർത്തിപ്പിടിക്കുക."
)


def char_to_filename(char: str) -> str:
    return '_'.join(f'{ord(c):04X}' for c in char) + '.mp3'


def synthesize(text: str, api_key: str) -> bytes:
    payload = {
        "input": {"text": text},
        "voice": VOICE,
        "audioConfig": AUDIO_CONFIG,
    }
    r = requests.post(API_URL, params={"key": api_key}, json=payload, timeout=15)
    if not r.ok:
        raise RuntimeError(f"HTTP {r.status_code}: {r.text}")
    return base64.b64decode(r.json()["audioContent"])


def main():
    parser = argparse.ArgumentParser(description="Generate Malayalam TTS MP3s for Project Mudra")
    parser.add_argument("--key", required=True, help="Google Cloud API key with Cloud TTS enabled")
    args = parser.parse_args()

    os.makedirs(OUT_DIR, exist_ok=True)
    print(f"Output folder: {OUT_DIR}\n")

    tasks = [("instructions.mp3", INSTRUCTIONS_TEXT)] + \
            [(char_to_filename(c), c) for c in CHARS]

    total   = len(tasks)
    skipped = 0
    success = 0
    failed  = []

    for i, (filename, text) in enumerate(tasks, 1):
        out_path = os.path.join(OUT_DIR, filename)
        if os.path.exists(out_path):
            print(f"[{i:02}/{total}] SKIP  {filename}")
            skipped += 1
            continue
        try:
            audio = synthesize(text, args.key)
            with open(out_path, "wb") as f:
                f.write(audio)
            print(f"[{i:02}/{total}] OK    {filename}  ← {text!r}")
            success += 1
        except Exception as e:
            print(f"[{i:02}/{total}] FAIL  {filename}: {e}")
            failed.append(filename)
        time.sleep(0.15)   # ~6 req/s — well within Neural2 quota

    print(f"\n{'─'*50}")
    print(f"Generated : {success}")
    print(f"Skipped   : {skipped}")
    print(f"Failed    : {len(failed)}")
    if failed:
        print("Failed files:", ", ".join(failed))
    print(f"{'─'*50}")
    print(f"Files are in: {OUT_DIR}")


if __name__ == "__main__":
    main()
