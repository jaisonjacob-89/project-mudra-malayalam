#!/usr/bin/env python3
"""
Generate Malayalam TTS MP3 files for Project Mudra via Gargantua.

Requires Gargantua to be running:
    python3 gargantua.py

Usage:
    python3 generate_letter_sounds.py

Outputs 70 MP3 files into the 'Letter sounds/' folder:
  - instructions.mp3        (app-open instruction sentence)
  - <unicode-hex>.mp3       (one file per unique keyboard character)

Re-running skips files that already exist — safe to resume after failure.
"""

import base64
import os
import sys
import urllib.request
import urllib.error
import json

# ── Gargantua endpoint ────────────────────────────────────────────────────
GARGANTUA = 'http://localhost:5050'

# ── Output folder ─────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR    = os.path.join(SCRIPT_DIR, 'Letter sounds')

# ── All unique keyboard characters (inner + outer ring + all sub-menus) ───
CHARS = [
    # Outer ring — vowels
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
    # Numbers root key + digits
    '123', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0',
    # Symbols root key + all sub-menu items
    '&', '@', '#', '!', '?', '.', ',', '"', "'", '_',
]

# ── Instruction sentence (read aloud on app open) ─────────────────────────
INSTRUCTIONS_TEXT = (
    'അക്ഷരം ചേർക്കാൻ വിരൽ ഉയർത്തുക. '
    'സബ് മെനു ലഭിക്കാൻ ഒരു സെക്കൻഡ് അമർത്തിപ്പിടിക്കുക.'
)

# ── Spoken labels for characters that can't be pronounced in isolation ────
SPOKEN_OVERRIDE = {
    # Malayalam special characters
    'ൠ': 'ദീർഘ ഋ',
    'ം': 'അനുസ്വാരം',
    'ഃ': 'വിസർഗം',
    'ഁ': 'ചന്ദ്രബിന്ദു',
    '്': 'ചന്ദ്രക്കല',
    # Numbers — Malayalam pronunciation
    '123': 'അക്കങ്ങൾ',
    '1': 'ഒന്ന്', '2': 'രണ്ട്',  '3': 'മൂന്ന്', '4': 'നാല്',
    '5': 'അഞ്ച്', '6': 'ആറ്',   '7': 'ഏഴ്',    '8': 'എട്ട്',
    '9': 'ഒമ്പത്', '0': 'പൂജ്യം',
    # Symbols — English pronunciation
    '&': 'symbols',      '@': 'at',          '#': 'hash',
    '!': 'exclamation',  '?': 'question mark', '.': 'dot',
    ',': 'comma',        '"': 'quote',        "'": 'apostrophe',
    '_': 'underscore',
}


def char_to_filename(char: str) -> str:
    return '_'.join(f'{ord(c):04X}' for c in char) + '.mp3'


def check_gargantua():
    try:
        with urllib.request.urlopen(f'{GARGANTUA}/health', timeout=3) as r:
            data = json.loads(r.read())
            print(f"Gargantua  : running — voice: {data.get('voice', '?')}")
            return True
    except Exception:
        print('ERROR: Gargantua is not running.')
        print('       Start it with:  python3 gargantua.py')
        return False


def synthesize_via_gargantua(text: str, out_path: str) -> None:
    spoken  = SPOKEN_OVERRIDE.get(text, text)
    payload = json.dumps({'text': spoken}).encode()
    req = urllib.request.Request(
        f'{GARGANTUA}/tts',
        data=payload,
        headers={'Content-Type': 'application/json'},
    )
    with urllib.request.urlopen(req, timeout=15) as resp:
        data = json.loads(resp.read())
    audio_b64 = data.get('audio', '')
    if not audio_b64:
        raise RuntimeError('Empty audio in Gargantua response')
    with open(out_path, 'wb') as f:
        f.write(base64.b64decode(audio_b64))


def main():
    if not check_gargantua():
        sys.exit(1)

    os.makedirs(OUT_DIR, exist_ok=True)
    print(f"Output dir : {OUT_DIR}\n")

    tasks = [('instructions.mp3', INSTRUCTIONS_TEXT)] + \
            [(char_to_filename(c), c) for c in CHARS]

    total   = len(tasks)
    skipped = 0
    success = 0
    failed  = []

    for i, (filename, text) in enumerate(tasks, 1):
        out_path = os.path.join(OUT_DIR, filename)
        if os.path.exists(out_path):
            print(f'[{i:02}/{total}] SKIP  {filename}')
            skipped += 1
            continue
        try:
            synthesize_via_gargantua(text, out_path)
            print(f'[{i:02}/{total}] OK    {filename}  ← {text!r}')
            success += 1
        except Exception as e:
            print(f'[{i:02}/{total}] FAIL  {filename}: {e}')
            failed.append(filename)

    print(f"\n{'─'*50}")
    print(f'Generated : {success}')
    print(f'Skipped   : {skipped}')
    print(f'Failed    : {len(failed)}')
    if failed:
        print('Failed files:', ', '.join(failed))
    print(f"{'─'*50}")
    print(f'Files are in: {OUT_DIR}')


if __name__ == '__main__':
    main()
