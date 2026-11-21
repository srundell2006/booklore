FROM fedirz/faster-whisper-server:latest-cuda

# ffmpeg, faster-whisper, fastapi, and uvicorn are all already present
# in the base image at /root/faster-whisper-server/.venv

# Security updates: the base image is frozen (Oct 2024, Ubuntu 22.04.3, Python 3.12.6, Gradio 4.44).
# --force-overwrite: deadsnakes moved files from python3.12-minimal into python3.12. CUDA, cuDNN
# and NCCL keep their apt holds. software-properties-common (and with it python3.10, PyJWT 2.3,
# oauthlib, systemd, polkit, packagekit) is only needed at build time.
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get -y -o Dpkg::Options::=--force-overwrite upgrade \
    && apt-get -y purge --auto-remove software-properties-common python3-jwt python3-oauthlib \
    && apt-get clean && rm -rf /var/lib/apt/lists/*
# main.py only imports faster_whisper, fastapi, pydantic and uvicorn: drop the unused Gradio UI.
RUN uv pip uninstall --python /root/faster-whisper-server/.venv/bin/python3 gradio gradio-client \
    && uv pip install --no-cache --python /root/faster-whisper-server/.venv/bin/python3 \
       "fastapi==0.162.4" "starlette==1.10.0" "h11==0.16.0" "httpcore>=1.0.9" "pillow==13.0.0" \
       "urllib3==2.8.0" "requests==2.34.2" "certifi>=2026.10.18" "jinja2>=3.1.6" "python-multipart>=0.0.30" \
       "setuptools>=78.1.1,<81" "protobuf>=5.29.5,<6" "fonttools>=4.60.2" \
       "anyio>=4.14.2" "orjson>=3.11.6" "fsspec>=2026.6.0"

WORKDIR /sidecar
COPY main.py .

EXPOSE 8000
CMD ["/root/faster-whisper-server/.venv/bin/python3", "/sidecar/main.py"]
