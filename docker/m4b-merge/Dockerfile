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

# The base image is frozen on EOL Alpine 3.20: move it to Alpine 3.23 (php83 8.3.36,
# openssl 3.5) and apply the release files apk leaves as .apk-new. ffmpeg/fdkaac in the base
# are static, mp4v2 only needs libstdc++, so the branch move is safe (merge tested).
RUN sed -i 's#/v3\.20/#/v3.23/#' /etc/apk/repositories \
    && apk upgrade --no-cache --available \
    && apk add --no-cache --upgrade alpine-release python3 \
    && for f in alpine-release os-release issue secfixes.d/alpine ssl/certs/ca-certificates.crt; do if [ -f "/etc/$f.apk-new" ]; then mv -f "/etc/$f.apk-new" "/etc/$f"; fi; done \
    && adduser -D -u 1000 -h /home/m4b -s /sbin/nologin m4b

WORKDIR /app
COPY server.py /app/server.py

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8080/health', timeout=4).status==200 else 1)"

# /merge is a CIFS share mounted with noperm, so the job server does not need root.
USER m4b

# Override the upstream CLI entrypoint with the job server.
ENTRYPOINT []
CMD ["python3", "-u", "/app/server.py"]
