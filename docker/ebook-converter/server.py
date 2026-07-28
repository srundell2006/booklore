#!/usr/bin/env python3
"""
Minimal HTTP wrapper around Calibre's `ebook-convert`.

Endpoints
---------
GET  /health   -> {"status": "ok", "calibre": "<version>", ...}
POST /convert  -> multipart/form-data; returns the converted file bytes

POST /convert fields:
    file    (required) source ebook
    target  (optional) output extension, default "epub"

Stdlib only: no pip dependencies, no supply chain, small image. Multipart is
parsed by hand rather than via `cgi`, which was removed in Python 3.13.
"""
import json
import logging
import os
import shutil
import subprocess
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("ebook-converter")

PORT = int(os.environ.get("PORT", "8080"))
WORK_DIR = os.environ.get("WORK_DIR", "/app/work")
MAX_UPLOAD_BYTES = int(os.environ.get("MAX_UPLOAD_MB", "256")) * 1024 * 1024
CONVERT_TIMEOUT = int(os.environ.get("CONVERT_TIMEOUT_SECONDS", "600"))

SUPPORTED_INPUT = [
    "mobi", "azw", "azw3", "azw4", "pdf", "epub", "fb2", "lit", "lrf",
    "htmlz", "html", "rtf", "txt", "pdb", "prc", "docx", "odt", "cbz", "cbr",
]
SUPPORTED_OUTPUT = ["epub", "mobi", "azw3", "docx", "txt", "htmlz", "pdf", "fb2", "lrf", "rtf"]


def calibre_version() -> str:
    try:
        out = subprocess.run(["ebook-convert", "--version"],
                             capture_output=True, text=True, timeout=30)
        return (out.stdout or out.stderr).strip().splitlines()[0]
    except Exception as exc:
        return f"unavailable: {exc}"


def parse_multipart(body: bytes, content_type: str):
    """Returns (files, fields): files[name] = (filename, bytes); fields[name] = str."""
    marker = "boundary="
    if marker not in content_type:
        raise ValueError("missing multipart boundary")
    boundary = content_type.split(marker, 1)[1].strip().strip('"')
    delimiter = b"--" + boundary.encode()

    files, fields = {}, {}
    for part in body.split(delimiter):
        if part in (b"", b"--", b"--\r\n", b"\r\n"):
            continue
        part = part.lstrip(b"\r\n")
        if part.startswith(b"--"):
            continue
        if b"\r\n\r\n" not in part:
            continue
        raw_headers, payload = part.split(b"\r\n\r\n", 1)
        if payload.endswith(b"\r\n"):
            payload = payload[:-2]

        disposition = ""
        for line in raw_headers.decode("utf-8", "replace").split("\r\n"):
            if line.lower().startswith("content-disposition:"):
                disposition = line
                break
        if not disposition:
            continue

        name, filename = None, None
        for chunk in disposition.split(";"):
            chunk = chunk.strip()
            if chunk.startswith("name="):
                name = chunk[5:].strip('"')
            elif chunk.startswith("filename="):
                filename = chunk[9:].strip('"')
        if not name:
            continue

        if filename is not None:
            files[name] = (filename, payload)
        else:
            fields[name] = payload.decode("utf-8", "replace").strip()

    return files, fields


class Handler(BaseHTTPRequestHandler):
    server_version = "booklore-ebook-converter/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log.info("%s - %s", self.address_string(), fmt % args)

    def _send_json(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.rstrip("/") in ("/health", ""):
            self._send_json(200, {
                "status": "ok",
                "calibre": calibre_version(),
                "inputFormats": SUPPORTED_INPUT,
                "outputFormats": SUPPORTED_OUTPUT,
            })
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path.rstrip("/") != "/convert":
            self._send_json(404, {"error": "not found"})
            return

        content_length = int(self.headers.get("Content-Length") or 0)
        if content_length <= 0:
            self._send_json(400, {"error": "empty request body"})
            return
        if content_length > MAX_UPLOAD_BYTES:
            self._send_json(413, {"error": f"upload exceeds {MAX_UPLOAD_BYTES // (1024*1024)} MB limit"})
            return

        content_type = self.headers.get("Content-Type", "")
        if not content_type.startswith("multipart/form-data"):
            self._send_json(400, {"error": "expected multipart/form-data"})
            return

        job_dir = os.path.join(WORK_DIR, uuid.uuid4().hex)
        os.makedirs(job_dir, exist_ok=True)
        try:
            body = self.rfile.read(content_length)
            files, fields = parse_multipart(body, content_type)
            del body

            if "file" not in files:
                self._send_json(400, {"error": "missing 'file' field"})
                return

            source_name, payload = files["file"]
            source_name = os.path.basename(source_name or "input")
            source_ext = os.path.splitext(source_name)[1].lstrip(".").lower()
            if not source_ext:
                self._send_json(400, {"error": "source file has no extension"})
                return
            if source_ext not in SUPPORTED_INPUT:
                self._send_json(415, {"error": f"unsupported input format '{source_ext}'",
                                      "supported": SUPPORTED_INPUT})
                return

            target_ext = (fields.get("target") or "epub").lstrip(".").lower()
            if target_ext not in SUPPORTED_OUTPUT:
                self._send_json(400, {"error": f"unsupported target format '{target_ext}'",
                                      "supported": SUPPORTED_OUTPUT})
                return

            source_path = os.path.join(job_dir, f"source.{source_ext}")
            with open(source_path, "wb") as handle:
                handle.write(payload)
            del payload

            target_path = os.path.join(job_dir, f"output.{target_ext}")
            log.info("Converting %s (%s -> %s, %d bytes)", source_name, source_ext,
                     target_ext, os.path.getsize(source_path))

            result = subprocess.run(["ebook-convert", source_path, target_path],
                                    capture_output=True, text=True, timeout=CONVERT_TIMEOUT)

            if result.returncode != 0 or not os.path.exists(target_path):
                tail = (result.stderr or result.stdout or "")[-1500:]
                log.warning("Conversion failed for %s: %s", source_name, tail)
                self._send_json(500, {"error": "conversion failed", "detail": tail})
                return

            size = os.path.getsize(target_path)
            log.info("Converted %s -> %s (%d bytes)", source_name, target_ext, size)

            out_name = os.path.splitext(source_name)[0] + "." + target_ext
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(size))
            self.send_header("Content-Disposition", f'attachment; filename="{out_name}"')
            self.end_headers()
            with open(target_path, "rb") as handle:
                shutil.copyfileobj(handle, self.wfile)

        except subprocess.TimeoutExpired:
            log.warning("Conversion timed out after %ss", CONVERT_TIMEOUT)
            self._send_json(504, {"error": f"conversion timed out after {CONVERT_TIMEOUT}s"})
        except Exception as exc:
            log.exception("Unexpected error during conversion")
            self._send_json(500, {"error": str(exc)})
        finally:
            shutil.rmtree(job_dir, ignore_errors=True)


def main():
    os.makedirs(WORK_DIR, exist_ok=True)
    log.info("Starting ebook converter on port %s", PORT)
    log.info("Calibre: %s", calibre_version())
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
