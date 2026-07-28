# Audiobook merge sidecar for BookLore.
#
# Wraps sandreas/m4b-tool in a small async job API. The upstream image bundles
# ffmpeg with libfdk_aac, the custom mp4v2 fork and fdkaac -- the dependencies
# that make a manual m4b-tool install painful -- but its entrypoint is a CLI,
# so it cannot be called over the network as-is.
#
# Audiobooks are large (500MB-5GB) and merges run for minutes to hours, so this
# service deliberately does NOT accept file uploads. It takes *paths* on shared
# volumes and runs the work as a background job that BookLore polls.
FROM sandreas/m4b-tool:latest

USER root

# The base image is Alpine-derived; python3 is the only addition needed.
RUN apk add --no-cache python3 || (apt-get update \
    && apt-get install --no-install-recommends -y python3 \
    && apt-get clean && rm -rf /var/lib/apt/lists/*)

WORKDIR /app
COPY server.py /app/server.py

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8080/health', timeout=4).status==200 else 1)"

# Override the upstream CLI entrypoint with the job server.
ENTRYPOINT []
CMD ["python3", "-u", "/app/server.py"]
