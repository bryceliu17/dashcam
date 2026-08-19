import os
import threading
from pathlib import Path

from fastapi import FastAPI, HTTPException
from faster_whisper import WhisperModel
from pydantic import BaseModel


AUDIO_ROOT = Path(os.environ.get("AUDIO_ROOT", "/data/audio")).resolve()
MODEL_NAME = os.environ.get("WHISPER_MODEL", "turbo")
MODEL_DEVICE = os.environ.get("WHISPER_DEVICE", "cuda")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "float16")
MODEL_CACHE = os.environ.get("WHISPER_MODEL_CACHE", "/models")

app = FastAPI(title="Dashcam audio transcription worker")
model = None
model_lock = threading.Lock()
transcription_lock = threading.Lock()


class TranscriptionRequest(BaseModel):
    path: str


def get_model() -> WhisperModel:
    global model
    if model is None:
        with model_lock:
            if model is None:
                model = WhisperModel(
                    MODEL_NAME,
                    device=MODEL_DEVICE,
                    compute_type=COMPUTE_TYPE,
                    download_root=MODEL_CACHE,
                )
    return model


def validate_audio_path(value: str) -> Path:
    candidate = Path(value).resolve()
    try:
        candidate.relative_to(AUDIO_ROOT)
    except ValueError as error:
        raise HTTPException(status_code=400, detail="Audio path is outside the configured archive.") from error
    if candidate.suffix.lower() != ".m4a":
        raise HTTPException(status_code=400, detail="Only M4A audio is supported.")
    if not candidate.is_file():
        raise HTTPException(status_code=404, detail="Audio file was not found.")
    return candidate


@app.get("/health")
def health():
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "device": MODEL_DEVICE,
        "computeType": COMPUTE_TYPE,
        "modelLoaded": model is not None,
    }


@app.post("/transcribe")
def transcribe(request: TranscriptionRequest):
    audio_path = validate_audio_path(request.path)
    with transcription_lock:
        segments_iterator, info = get_model().transcribe(
            str(audio_path),
            task="transcribe",
            language=None,
            multilingual=True,
            beam_size=5,
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 500},
            condition_on_previous_text=True,
        )
        segments = [
            {
                "start": round(float(segment.start), 3),
                "end": round(float(segment.end), 3),
                "text": segment.text.strip(),
            }
            for segment in segments_iterator
            if segment.text.strip()
        ]

    return {
        "text": " ".join(segment["text"] for segment in segments).strip(),
        "language": info.language or "",
        "languageProbability": float(info.language_probability or 0),
        "model": MODEL_NAME,
        "segments": segments,
    }
