import os
import glob
import tempfile
import subprocess
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from faster_whisper import WhisperModel

app = FastAPI(title="Whisper Sidecar", description="GPU-accelerated audio transcription for Booklore audiobook verification")

# ---- Configuration (override via environment variables) ----
MODEL_SIZE   = os.environ.get("WHISPER_MODEL",        "large-v3")
DEVICE       = os.environ.get("WHISPER_DEVICE",        "cuda")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE",  "float16")

print(f"Loading Whisper model: {MODEL_SIZE} on {DEVICE} (compute_type={COMPUTE_TYPE}) ...")
model = WhisperModel(MODEL_SIZE, device=DEVICE, compute_type=COMPUTE_TYPE)
print("Whisper model loaded.")


# ---- Request / Response models ----

class TranscribeRequest(BaseModel):
    # Path to the audio file *or* a directory that contains audio files.
    # When a directory is given, the first audio file found (m4b > m4a > mp3 > opus)
    # is used.  The path must be accessible inside this container (use the same
    # volume mount as booklore-api).
    file_path: str
    # How many seconds from the start of the file to transcribe.
    duration_seconds: int = 90

class TranscribeResponse(BaseModel):
    transcript: str


# ---- Helpers ----

AUDIO_EXTENSIONS = [".m4b", ".m4a", ".mp3", ".opus"]

def resolve_audio_file(path: str) -> str | None:
    """Return the path to a concrete audio file, searching inside a directory if needed."""
    if os.path.isfile(path):
        return path
    if os.path.isdir(path):
        for ext in AUDIO_EXTENSIONS:
            matches = sorted(glob.glob(os.path.join(path, "**", f"*{ext}"), recursive=True))
            if matches:
                return matches[0]
    return None


# ---- Endpoints ----

@app.post("/transcribe", response_model=TranscribeResponse)
async def transcribe(request: TranscribeRequest):
    audio_file = resolve_audio_file(request.file_path)
    if audio_file is None:
        raise HTTPException(
            status_code=404,
            detail=f"No supported audio file found at: {request.file_path}"
        )

    tmp_path = None
    try:
        # Extract the first N seconds as 16 kHz mono WAV — Whisper's preferred format.
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            tmp_path = tmp.name

        result = subprocess.run(
            [
                "ffmpeg", "-y",
                "-i", audio_file,
                "-t", str(request.duration_seconds),
                "-ar", "16000",
                "-ac", "1",
                "-f", "wav",
                tmp_path,
            ],
            capture_output=True,
        )
        if result.returncode != 0:
            raise HTTPException(
                status_code=500,
                detail=f"ffmpeg failed: {result.stderr.decode('utf-8', errors='replace')}"
            )

        segments, _ = model.transcribe(tmp_path, beam_size=5, language=None)
        transcript = " ".join(seg.text.strip() for seg in segments)
        return TranscribeResponse(transcript=transcript)

    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))
    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)


@app.get("/health")
async def health():
    return {"status": "ok", "model": MODEL_SIZE, "device": DEVICE, "compute_type": COMPUTE_TYPE}
