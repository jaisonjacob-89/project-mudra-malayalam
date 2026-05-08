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

import io
import sys

try:
    from fastapi import FastAPI
    from fastapi.middleware.cors import CORSMiddleware
    from fastapi.responses import StreamingResponse
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
    if not req.text.strip():
        return StreamingResponse(io.BytesIO(), media_type="audio/mpeg")

    buf = io.BytesIO()
    communicate = edge_tts.Communicate(req.text.strip(), VOICE, rate=RATE)
    async for chunk in communicate.stream():
        if chunk["type"] == "audio":
            buf.write(chunk["data"])
    buf.seek(0)
    return StreamingResponse(buf, media_type="audio/mpeg")


# ── Entry point ───────────────────────────────────────────────────────────
if __name__ == "__main__":
    print(f"Gargantua starting on http://{HOST}:{PORT}")
    print(f"Voice: {VOICE}")
    uvicorn.run(app, host=HOST, port=PORT, log_level="warning")
