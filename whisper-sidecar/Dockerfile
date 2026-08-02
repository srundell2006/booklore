FROM fedirz/faster-whisper-server:latest-cuda

USER root

# Add FFmpeg for audio extraction (everything else is already in the base image)
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /sidecar
COPY main.py .

EXPOSE 8000
CMD ["python3", "/sidecar/main.py"]
