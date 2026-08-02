FROM fedirz/faster-whisper-server:latest-cuda

# ffmpeg, faster-whisper, fastapi, and uvicorn are all already present
# in the base image at /root/faster-whisper-server/.venv
WORKDIR /sidecar
COPY main.py .

EXPOSE 8000
CMD ["/root/faster-whisper-server/.venv/bin/python3", "/sidecar/main.py"]
