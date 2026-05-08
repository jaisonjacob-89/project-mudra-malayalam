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
    boundaries  = []
    search_from = 0

    communicate = edge_tts.Communicate(text, VOICE, rate=RATE)
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            buf.write(chunk["data"])
        elif chunk["type"] == "WordBoundary":
            word     = chunk.get("text", "")
            time_ms  = chunk["offset"] // 10000   # 100-ns ticks → ms
            if word:
                idx = text.find(word, search_from)
                if idx >= 0:
                    boundaries.append({"charIndex": idx, "timeMs": time_ms})
                    search_from = idx + len(word)

    return {
        "audio":      base64.b64encode(buf.getvalue()).decode(),
        "boundaries": boundaries,
    }


# ── Entry point ───────────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"Gargantua starting on http://{HOST}:{PORT}")
    print(f"Voice: {VOICE}")
    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
