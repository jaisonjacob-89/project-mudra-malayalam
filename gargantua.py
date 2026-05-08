#!/usr/bin/env python3
"""
Gargantua — local TTS server for Project Mudra Malayalam
Synthesises Malayalam text using edge-tts (ml-IN-SobhanaNeural).

Usage:
    pip install fastapi uvicorn edge-tts
    python3 gargantua.py

Server runs on http://localhost:5050
preview.html calls POST /tts with {"text": "..."} and plays the returned MP3.
"""

import base64
import io
import sys

try:
    from fastapi import FastAPI
    from fastapi.middleware.cors import CORSMiddleware
    from pydantic import BaseModel
    import uvicorn
except ImportError:
    sys.exit("Missing dependencies. Run:  pip install fastapi uvicorn edge-tts")

try:
    import edge_tts
except ImportError:
    sys.exit("edge-tts not found. Run:  pip install edge-tts")

# ── Config ────────────────────────────────────────────────────────────────
VOICE = "ml-IN-SobhanaNeural"
RATE  = "-10%"
HOST  = "127.0.0.1"
PORT  = 5050

# ── App ───────────────────────────────────────────────────────────────────
app = FastAPI(title="Gargantua", description="Project Mudra Malayalam TTS server")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],   # allow calls from localhost and GitHub Pages
    allow_methods=["POST", "GET", "OPTIONS"],
    allow_headers=["*"],
)


class TTSRequest(BaseModel):
    text: str


@app.get("/health")
async def health():
    return {"status": "ok", "voice": VOICE}


@app.post("/tts")
async def synthesize(req: TTSRequest):
    text = req.text.strip()
    if not text:
        return {"audio": "", "boundaries": []}

    buf         = io.BytesIO()
    word_bounds = []   # populated if edge-tts sends WordBoundary (non-Malayalam)
    sent_events = []   # populated from SentenceBoundary (Malayalam path)
    wb_from     = 0

    communicate = edge_tts.Communicate(text, VOICE, rate=RATE)
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            buf.write(chunk["data"])
        elif chunk["type"] == "WordBoundary":
            word    = chunk.get("text", "")
            time_ms = chunk["offset"] // 10000
            if word:
                idx = text.find(word, wb_from)
                if idx >= 0:
                    word_bounds.append({"charIndex": idx, "timeMs": time_ms})
                    wb_from = idx + len(word)
        elif chunk["type"] == "SentenceBoundary":
            sent_events.append({
                "timeMs": chunk["offset"]   // 10000,
                "durMs":  chunk["duration"] // 10000,
                "text":   chunk.get("text", ""),
            })

    # Malayalam: edge-tts emits SentenceBoundary but not WordBoundary.
    # Distribute each sentence's duration across its space-separated words
    # proportionally by character length to synthesise per-word timing.
    if word_bounds:
        boundaries = word_bounds
    else:
        boundaries = []
        sb_from    = 0
        for ev in sent_events:
            words = ev["text"].split()
            if not words:
                continue
            total_chars = sum(len(w) for w in words)
            if not total_chars:
                continue
            t = float(ev["timeMs"])
            for word in words:
                idx = text.find(word, sb_from)
                if idx >= 0:
                    boundaries.append({"charIndex": idx, "timeMs": int(t)})
                    sb_from = idx + len(word)
                t += ev["durMs"] * len(word) / total_chars

    return {
        "audio":      base64.b64encode(buf.getvalue()).decode(),
        "boundaries": boundaries,
    }


# ── Entry point ───────────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"Gargantua starting on http://{HOST}:{PORT}")
    print(f"Voice: {VOICE}")
    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
