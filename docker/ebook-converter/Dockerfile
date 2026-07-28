# Ebook conversion sidecar for BookLore.
#
# Wraps Calibre's `ebook-convert` CLI in a minimal HTTP service so BookLore can
# convert MOBI/AZW3/PDF/etc. to EPUB without bundling Calibre into its own image
# and without needing Docker socket access.
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive

# calibre from Debian repos gives headless conversion tooling without the Qt desktop app.
# poppler-utils is required for PDF input; fonts and xdg-utils are runtime deps of ebook-convert.
RUN apt-get update && apt-get install --no-install-recommends -y \
        calibre \
        python3 \
        poppler-utils \
        fonts-liberation \
        xdg-utils \
        ca-certificates \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY server.py /app/server.py

RUN useradd -m -u 1000 converter \
    && mkdir -p /app/work /home/converter/.config/calibre \
    && chown -R converter:converter /app /home/converter
USER converter
ENV CALIBRE_CONFIG_DIRECTORY=/home/converter/.config/calibre
ENV HOME=/home/converter

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8080/health', timeout=4).status==200 else 1)"

CMD ["python3", "-u", "/app/server.py"]
