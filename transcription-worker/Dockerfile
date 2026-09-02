FROM nvidia/cuda:12.3.2-cudnn9-runtime-ubuntu22.04

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg python3 python3-pip \
    && rm -rf /var/lib/apt/lists/*

RUN --mount=type=cache,target=/root/.cache/pip \
    python3 -m pip install \
        torch==2.11.0 \
        torchaudio==2.11.0 \
        --index-url https://download.pytorch.org/whl/cu128

RUN --mount=type=cache,target=/root/.cache/pip \
    python3 -m pip install \
        faster-whisper==1.2.1 \
        torchcodec==0.11.1 \
        pyannote.audio==4.0.7 \
        fastapi==0.116.1 \
        "uvicorn[standard]==0.35.0"

WORKDIR /app
COPY transcription-worker/app.py /app/app.py

EXPOSE 8000
CMD ["python3", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
