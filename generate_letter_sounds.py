#!/usr/bin/env python3
"""
Generate Malayalam TTS MP3 files for Project Mudra using edge-tts.
No API key or registration required.

Usage:
    pip install edge-tts
    python3 generate_letter_sounds.py

Outputs 70 MP3 files into the 'Letter sounds/' folder:
  - instructions.mp3        (app-open instruction sentence)
  - <unicode-hex>.mp3       (one file per unique keyboard character)

File naming: each character's Unicode codepoints in hex, joined by underscores.
  e.g.  ക  → 0D15.mp3
        ക്ക → 0D15_0D4D_0D15.mp3

Re-running the script skips files that already exist, so it is safe to resume
after a failure without re-generating completed files.
"""

import asyncio
import os
import sys

try:
    import edge_tts
except ImportError:
    sys.exit("Missing dependency: run  pip install edge-tts  then retry.")

# ── Voice config ──────────────────────────────────────────────────────────
VOICE = "ml-IN-MidhunNeural"   # Microsoft Edge neural Malayalam voice
RATE  = "-10%"                 # Slightly slower for clarity

# ── Output folder ─────────────────────────────────────────────────────────
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_DIR    = os.path.join(SCRIPT_DIR, "Letter sounds")

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
]

# ── Instruction sentence (read aloud on app open) ─────────────────────────
INSTRUCTIONS_TEXT = (
    "അക്ഷരം ചേർക്കാൻ വിരൽ ഉയർത്തുക. "
    "സബ് മെനു ലഭിക്കാൻ ഒരു സെക്കൻഡ് അമർത്തിപ്പിടിക്കുക."
)


# Spoken labels for characters that can't be pronounced in isolation
SPOKEN_OVERRIDE = {
    'ൠ': 'ദീർഘ ഋ',
    'ം': 'അനുസ്വാരം',
    'ഃ': 'വിസർഗം',
    'ഁ': 'ചന്ദ്രബിന്ദു',
    '്': 'ചന്ദ്രക്കല',
}


def char_to_filename(char: str) -> str:
    return '_'.join(f'{ord(c):04X}' for c in char) + '.mp3'


async def synthesize(text: str, out_path: str) -> None:
    spoken = SPOKEN_OVERRIDE.get(text, text)
    communicate = edge_tts.Communicate(spoken, VOICE, rate=RATE)
    await communicate.save(out_path)


async def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    print(f"Voice      : {VOICE}")
    print(f"Output dir : {OUT_DIR}\n")

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
            await synthesize(text, out_path)
            print(f"[{i:02}/{total}] OK    {filename}  ← {text!r}")
            success += 1
        except Exception as e:
            print(f"[{i:02}/{total}] FAIL  {filename}: {e}")
            failed.append(filename)

    print(f"\n{'─'*50}")
    print(f"Generated : {success}")
    print(f"Skipped   : {skipped}")
    print(f"Failed    : {len(failed)}")
    if failed:
        print("Failed files:", ", ".join(failed))
    print(f"{'─'*50}")
    print(f"Files are in: {OUT_DIR}")


if __name__ == "__main__":
    asyncio.run(main())
