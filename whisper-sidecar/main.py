import os
import subprocess
import tempfile
from pathlib import Path

import uvicorn
from faster_whisper import WhisperModel
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="Whisper Transcription Sidecar")

MODEL_SIZE = os.getenv("WHISPER_MODEL", "base")
DEVICE = os.getenv("WHISPER_DEVICE", "cuda")
COMPUTE_TYPE = os.getenv("WHISPER_COMPUTE_TYPE", "float16")

print(f"Loading Whisper model '{MODEL_SIZE}' on {DEVICE} ({COMPUTE_TYPE})...", flush=True)
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
print("Model loaded.", flush=True)


class TranscribeRequest(BaseModel):
    file_path: str
    duration_seconds: int = 90


class TranscribeResponse(BaseModel):
    transcript: str


@app.get("/health")
def health():
    return "OK"


@app.post("/transcribe", response_model=TranscribeResponse)
def transcribe(request: TranscribeRequest):
    file_path = Path(request.file_path)
    if not file_path.exists():
        raise HTTPException(status_code=404, detail=f"File not found: {file_path}")

    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        # Extract first N seconds, resample to 16 kHz mono WAV for Whisper
        result = subprocess.run(
            [
                "ffmpeg", "-y",
                "-i", str(file_path),
                "-t", str(request.duration_seconds),
                "-ar", "16000",
                "-ac", "1",
                "-f", "wav",
                tmp_path,
            ],
            capture_output=True,
            timeout=120,
        )
        if result.returncode != 0:
            raise HTTPException(
                status_code=500,
                detail=f"FFmpeg failed: {result.stderr.decode('utf-8', errors='replace')[:500]}",
            )

        # Transcribe
        segments, _ = model.transcribe(
            tmp_path,
            beam_size=5,
            language="en",
            vad_filter=True,
        )
        transcript = " ".join(seg.text.strip() for seg in segments)
        return TranscribeResponse(transcript=transcript)

    finally:
        if os.path.exists(tmp_path):
            os.unlink(tmp_path)


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)
