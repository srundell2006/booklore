FROM fedirz/faster-whisper-server:latest-cuda

USER root

# Add FFmpeg for audio extraction and ensure FastAPI/uvicorn are present
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN pip install --no-cache-dir fastapi==0.115.0 "uvicorn[standard]==0.30.6"

WORKDIR /sidecar
COPY main.py .

EXPOSE 8000
CMD ["python3", "/sidecar/main.py"]
