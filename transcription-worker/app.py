import os
import subprocess
import threading
import warnings
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException
from faster_whisper import WhisperModel
from pydantic import BaseModel


AUDIO_ROOT = Path(os.environ.get("AUDIO_ROOT", "/data/audio")).resolve()
MODEL_NAME = os.environ.get("WHISPER_MODEL", "turbo")
MODEL_DEVICE = os.environ.get("WHISPER_DEVICE", "cuda")
COMPUTE_TYPE = os.environ.get("WHISPER_COMPUTE_TYPE", "float16")
MODEL_CACHE = os.environ.get("WHISPER_MODEL_CACHE", "/models")
DIARIZATION_MODEL = os.environ.get(
    "PYANNOTE_MODEL", "pyannote/speaker-diarization-community-1"
)
DIARIZATION_DEVICE = os.environ.get("PYANNOTE_DEVICE", MODEL_DEVICE)
HUGGINGFACE_TOKEN = os.environ.get("HUGGINGFACE_TOKEN", "").strip()

app = FastAPI(title="Dashcam audio transcription worker")
model = None
diarization_pipeline = None
model_lock = threading.Lock()
diarization_model_lock = threading.Lock()
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


def get_diarization_pipeline():
    global diarization_pipeline
    if not HUGGINGFACE_TOKEN:
        raise RuntimeError(
            "Speaker separation is not configured. Add HUGGINGFACE_TOKEN after accepting "
            "the pyannote community-1 model terms."
        )
    if diarization_pipeline is None:
        with diarization_model_lock:
            if diarization_pipeline is None:
                import torch
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    from pyannote.audio import Pipeline

                loaded_pipeline = Pipeline.from_pretrained(
                    DIARIZATION_MODEL,
                    token=HUGGINGFACE_TOKEN,
                    cache_dir=MODEL_CACHE,
                )
                if loaded_pipeline is None:
                    raise RuntimeError("The speaker separation model could not be loaded.")
                requested_device = DIARIZATION_DEVICE.lower()
                device = (
                    "cuda"
                    if requested_device.startswith("cuda") and torch.cuda.is_available()
                    else "cpu"
                )
                loaded_pipeline.to(torch.device(device))
                diarization_pipeline = loaded_pipeline
    return diarization_pipeline


def load_audio_waveform(audio_path: Path) -> dict:
    import numpy as np
    import torch

    process = subprocess.run(
        [
            "ffmpeg",
            "-v", "error",
            "-i", str(audio_path),
            "-f", "f32le",
            "-acodec", "pcm_f32le",
            "-ac", "1",
            "-ar", "16000",
            "pipe:1",
        ],
        capture_output=True,
        check=False,
    )
    if process.returncode != 0:
        error = process.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(error or "FFmpeg could not decode the audio for speaker separation.")
    samples = np.frombuffer(process.stdout, dtype=np.float32).copy()
    return {
        "waveform": torch.from_numpy(samples).unsqueeze(0),
        "sample_rate": 16000,
    }


def diarization_turns(result: Any) -> list[dict]:
    annotation = getattr(result, "exclusive_speaker_diarization", None)
    if annotation is None:
        annotation = getattr(result, "speaker_diarization", result)

    turns = []
    if hasattr(annotation, "itertracks"):
        iterator = (
            (turn, speaker)
            for turn, _, speaker in annotation.itertracks(yield_label=True)
        )
    else:
        iterator = iter(annotation)

    for item in iterator:
        turn, speaker = item[0], item[-1]
        start = float(turn.start)
        end = float(turn.end)
        if end > start:
            turns.append({"start": start, "end": end, "speaker": str(speaker)})
    return sorted(turns, key=lambda turn: (turn["start"], turn["end"]))


def speaker_for_word(start: float, end: float, turns: list[dict]) -> str | None:
    if not turns:
        return None
    overlaps = [
        max(0.0, min(end, turn["end"]) - max(start, turn["start"]))
        for turn in turns
    ]
    best_index = max(range(len(turns)), key=overlaps.__getitem__)
    if overlaps[best_index] > 0:
        return turns[best_index]["speaker"]

    midpoint = (start + end) / 2
    return min(
        turns,
        key=lambda turn: abs(midpoint - ((turn["start"] + turn["end"]) / 2)),
    )["speaker"]


def merge_words_by_speaker(words: list[dict], turns: list[dict]) -> tuple[list[dict], int]:
    speaker_names: dict[str, str] = {}
    merged = []

    for word in words:
        raw_speaker = speaker_for_word(word["start"], word["end"], turns)
        if raw_speaker is None:
            continue
        if raw_speaker not in speaker_names:
            speaker_names[raw_speaker] = f"Speaker {len(speaker_names) + 1}"
        speaker = speaker_names[raw_speaker]

        can_merge = (
            merged
            and merged[-1]["speaker"] == speaker
            and word["start"] - merged[-1]["end"] <= 1.5
        )
        if can_merge:
            merged[-1]["end"] = word["end"]
            merged[-1]["parts"].append(word["text"])
        else:
            merged.append({
                "start": word["start"],
                "end": word["end"],
                "speaker": speaker,
                "parts": [word["text"]],
            })

    segments = []
    for segment in merged:
        text = "".join(segment.pop("parts")).strip()
        if text:
            segments.append({
                "start": round(segment["start"], 3),
                "end": round(segment["end"], 3),
                "text": text,
                "speaker": segment["speaker"],
            })
    return segments, len(speaker_names)


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
        "diarizationModel": DIARIZATION_MODEL,
        "diarizationConfigured": bool(HUGGINGFACE_TOKEN),
        "diarizationModelLoaded": diarization_pipeline is not None,
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
            word_timestamps=True,
        )
        segments = []
        words = []
        for segment in segments_iterator:
            text = segment.text.strip()
            if text:
                segments.append({
                    "start": round(float(segment.start), 3),
                    "end": round(float(segment.end), 3),
                    "text": text,
                })
            for word in segment.words or []:
                if word.start is None or word.end is None or not word.word.strip():
                    continue
                words.append({
                    "start": float(word.start),
                    "end": float(word.end),
                    "text": word.word,
                })

        diarization_status = "not_configured"
        diarization_error = ""
        speaker_count = 0
        if HUGGINGFACE_TOKEN:
            try:
                waveform = load_audio_waveform(audio_path)
                turns = diarization_turns(get_diarization_pipeline()(waveform))
                speaker_segments, speaker_count = merge_words_by_speaker(words, turns)
                if speaker_segments:
                    segments = speaker_segments
                diarization_status = "ready"
            except Exception as error:
                diarization_status = "failed"
                diarization_error = str(error)[:1000]

    return {
        "text": " ".join(segment["text"] for segment in segments).strip(),
        "language": info.language or "",
        "languageProbability": float(info.language_probability or 0),
        "model": MODEL_NAME,
        "segments": segments,
        "diarizationStatus": diarization_status,
        "diarizationError": diarization_error,
        "speakerCount": speaker_count,
    }
