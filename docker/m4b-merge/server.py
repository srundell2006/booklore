#!/usr/bin/env python3
"""
Async job API over `m4b-tool merge` for BookLore.

Why paths and not uploads
-------------------------
Audiobooks run 500MB-5GB and merges take minutes to hours. Streaming that over
HTTP twice would be wasteful and fragile. This service expects to share the same
volume mounts as BookLore at identical paths, so a path BookLore sends means the
same bytes here. Work runs as a background job the caller polls.

Endpoints
---------
GET    /health           -> versions + worker state
POST   /jobs             -> {inputPath, outputPath, options{...}} -> 202 {jobId}
GET    /jobs             -> list of recent jobs
GET    /jobs/{id}        -> {state, progress, logTail, outputPath, error}
DELETE /jobs/{id}        -> cancel a queued or running job

Stdlib only.
"""
import json
import logging
import os
import queue
import re
import shlex
import shutil
import signal
import subprocess
import threading
import time
import uuid
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("m4b-merge")

PORT = int(os.environ.get("PORT", "8080"))
# ffmpeg saturates cores; more than a couple of concurrent merges is counterproductive.
MAX_WORKERS = int(os.environ.get("MAX_WORKERS", "1"))
JOB_TIMEOUT = int(os.environ.get("JOB_TIMEOUT_SECONDS", str(6 * 60 * 60)))
MAX_JOB_HISTORY = int(os.environ.get("MAX_JOB_HISTORY", "100"))
LOG_TAIL_LINES = 40

AUDIO_EXTENSIONS = {".m4b", ".m4a", ".mp3", ".aac", ".flac", ".ogg", ".oga",
                    ".opus", ".wav", ".wma", ".alac", ".mp4"}
# Extensions that are already AAC-family, so a merge can be a lossless remux.
LOSSLESS_SOURCE_EXTENSIONS = {".m4b", ".m4a", ".aac", ".mp4", ".alac"}

_jobs = OrderedDict()
_jobs_lock = threading.Lock()
_work_queue = queue.Queue()

# m4b-tool/ffmpeg emit progress lines with a running timestamp; use them for a coarse percentage.
_TIME_RE = re.compile(r"time=(\d+):(\d+):(\d+)")
_DURATION_RE = re.compile(r"Duration:\s*(\d+):(\d+):(\d+)")


def tool_versions():
    versions = {}
    for name, cmd in (("m4b-tool", ["m4b-tool", "--version"]),
                      ("ffmpeg", ["ffmpeg", "-version"])):
        try:
            out = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            versions[name] = (out.stdout or out.stderr).strip().splitlines()[0]
        except Exception as exc:
            versions[name] = f"unavailable: {exc}"
    return versions


def collect_audio_files(input_path):
    """Returns the audio files m4b-tool will consider, sorted as it would see them."""
    if os.path.isfile(input_path):
        return [input_path]
    found = []
    for root, _dirs, files in os.walk(input_path):
        for name in sorted(files):
            if os.path.splitext(name)[1].lower() in AUDIO_EXTENSIONS:
                found.append(os.path.join(root, name))
    return sorted(found)


def can_remux_losslessly(files):
    """True when every source is AAC-family, so --no-conversion is safe and fast."""
    return bool(files) and all(
        os.path.splitext(f)[1].lower() in LOSSLESS_SOURCE_EXTENSIONS for f in files
    )


def build_command(job):
    """Translates job options into an m4b-tool merge invocation."""
    options = job["options"] or {}
    cmd = ["m4b-tool", "merge", job["inputPath"], "--output-file", job["outputPath"], "-q", "--no-interaction"]

    if job.get("lossless"):
        # Lossless remux: every encoding option is ignored by m4b-tool in this mode.
        cmd.append("--no-conversion")
    else:
        for flag, key in (("--audio-codec", "audioCodec"),
                          ("--audio-bitrate", "audioBitrate"),
                          ("--audio-samplerate", "audioSamplerate"),
                          ("--audio-channels", "audioChannels"),
                          ("--audio-profile", "audioProfile")):
            value = options.get(key)
            if value:
                cmd += [flag, str(value)]

    if options.get("maxChapterLength"):
        cmd += ["--max-chapter-length", str(options["maxChapterLength"])]
    if options.get("useFilenamesAsChapters"):
        cmd.append("--use-filenames-as-chapters")
    if options.get("adjustForIpod"):
        cmd.append("--adjust-for-ipod")
    if options.get("fixMimeType", True):
        cmd.append("--fix-mime-type")
    if options.get("jobs"):
        cmd += ["--jobs", str(options["jobs"])]
    if options.get("force", True):
        cmd.append("--force")

    # Metadata / tagging passthrough. --series and --series-part drive m4b-tool's
    # sort-order generation, which maps cleanly onto BookLore's series fields.
    for flag, key in (("--name", "name"), ("--album", "album"), ("--artist", "artist"),
                      ("--albumartist", "albumArtist"), ("--genre", "genre"),
                      ("--writer", "writer"), ("--year", "year"),
                      ("--description", "description"), ("--comment", "comment"),
                      ("--series", "series"), ("--series-part", "seriesPart"),
                      ("--cover", "cover"), ("--sortname", "sortName"),
                      ("--sortalbum", "sortAlbum"), ("--sortartist", "sortArtist")):
        value = options.get(key)
        if value not in (None, ""):
            cmd += [flag, str(value)]

    if options.get("skipCover"):
        cmd.append("--skip-cover")

    return cmd


def run_job(job_id):
    with _jobs_lock:
        job = _jobs.get(job_id)
        if not job or job["state"] == "cancelled":
            return
        job["state"] = "running"
        job["startedAt"] = time.time()

    cmd = build_command(job)
    log.info("Job %s starting: %s", job_id, " ".join(shlex.quote(c) for c in cmd))
    with _jobs_lock:
        job["command"] = " ".join(shlex.quote(c) for c in cmd)

    try:
        os.makedirs(os.path.dirname(job["outputPath"]) or "/", exist_ok=True)
    except Exception as exc:
        _fail(job_id, f"cannot create output directory: {exc}")
        return

    try:
        process = subprocess.Popen(
            cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, bufsize=1, start_new_session=True,
        )
    except Exception as exc:
        _fail(job_id, f"failed to start m4b-tool: {exc}")
        return

    with _jobs_lock:
        job["pid"] = process.pid

    total_seconds = None
    deadline = time.time() + JOB_TIMEOUT
    try:
        for line in process.stdout:
            line = line.rstrip()
            if not line:
                continue
            with _jobs_lock:
                job["log"].append(line)
                if len(job["log"]) > 500:
                    del job["log"][:-500]

            if total_seconds is None:
                match = _DURATION_RE.search(line)
                if match:
                    h, m, s = (int(g) for g in match.groups())
                    total_seconds = h * 3600 + m * 60 + s
            match = _TIME_RE.search(line)
            if match and total_seconds:
                h, m, s = (int(g) for g in match.groups())
                elapsed = h * 3600 + m * 60 + s
                with _jobs_lock:
                    job["progress"] = max(0.0, min(99.0, round(elapsed / total_seconds * 100, 1)))

            if time.time() > deadline:
                _kill(process)
                _fail(job_id, f"job exceeded timeout of {JOB_TIMEOUT}s")
                return

        returncode = process.wait(timeout=60)
    except Exception as exc:
        _kill(process)
        _fail(job_id, f"error while running m4b-tool: {exc}")
        return

    with _jobs_lock:
        job = _jobs.get(job_id)
        if not job:
            return
        if job["state"] == "cancelled":
            return
        job["finishedAt"] = time.time()
        if returncode == 0 and os.path.exists(job["outputPath"]):
            job["state"] = "completed"
            job["progress"] = 100.0
            job["outputSizeBytes"] = os.path.getsize(job["outputPath"])
            log.info("Job %s completed -> %s (%d bytes)",
                     job_id, job["outputPath"], job["outputSizeBytes"])
        else:
            job["state"] = "failed"
            job["error"] = (f"m4b-tool exited with code {returncode}"
                            if returncode != 0 else "output file was not produced")
            log.warning("Job %s failed: %s", job_id, job["error"])


def _kill(process):
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except Exception:
        try:
            process.kill()
        except Exception:
            pass


def _fail(job_id, message):
    with _jobs_lock:
        job = _jobs.get(job_id)
        if job:
            job["state"] = "failed"
            job["error"] = message
            job["finishedAt"] = time.time()
    log.warning("Job %s failed: %s", job_id, message)


def worker_loop():
    while True:
        job_id = _work_queue.get()
        try:
            run_job(job_id)
        except Exception:
            log.exception("Unhandled error in job %s", job_id)
            _fail(job_id, "unhandled internal error")
        finally:
            _work_queue.task_done()


def job_view(job, include_log=True):
    view = {
        "jobId": job["jobId"],
        "state": job["state"],
        "progress": job["progress"],
        "inputPath": job["inputPath"],
        "outputPath": job["outputPath"],
        "lossless": job.get("lossless", False),
        "sourceFileCount": job.get("sourceFileCount"),
        "createdAt": job["createdAt"],
        "startedAt": job.get("startedAt"),
        "finishedAt": job.get("finishedAt"),
        "outputSizeBytes": job.get("outputSizeBytes"),
        "error": job.get("error"),
        "command": job.get("command"),
    }
    if include_log:
        view["logTail"] = job["log"][-LOG_TAIL_LINES:]
    return view


class Handler(BaseHTTPRequestHandler):
    server_version = "booklore-m4b-merge/1.0"
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

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        return json.loads(self.rfile.read(length))

    def do_GET(self):
        path = self.path.rstrip("/")
        if path in ("/health", ""):
            with _jobs_lock:
                active = sum(1 for j in _jobs.values() if j["state"] in ("queued", "running"))
            self._send_json(200, {
                "status": "ok",
                "versions": tool_versions(),
                "workers": MAX_WORKERS,
                "activeJobs": active,
            })
        elif path == "/jobs":
            with _jobs_lock:
                jobs = [job_view(j, include_log=False) for j in _jobs.values()]
            self._send_json(200, {"jobs": jobs})
        elif path.startswith("/jobs/"):
            job_id = path.split("/jobs/", 1)[1]
            with _jobs_lock:
                job = _jobs.get(job_id)
                view = job_view(job) if job else None
            if view:
                self._send_json(200, view)
            else:
                self._send_json(404, {"error": "job not found"})
        else:
            self._send_json(404, {"error": "not found"})

    def do_POST(self):
        if self.path.rstrip("/") != "/jobs":
            self._send_json(404, {"error": "not found"})
            return
        try:
            payload = self._read_json()
        except Exception as exc:
            self._send_json(400, {"error": f"invalid JSON: {exc}"})
            return

        input_path = payload.get("inputPath")
        output_path = payload.get("outputPath")
        options = payload.get("options") or {}

        if not input_path or not output_path:
            self._send_json(400, {"error": "inputPath and outputPath are required"})
            return
        if not os.path.exists(input_path):
            self._send_json(400, {"error": f"inputPath does not exist in this container: {input_path}"})
            return

        sources = collect_audio_files(input_path)
        if not sources:
            self._send_json(400, {"error": f"no audio files found at {input_path}"})
            return

        # Prefer a lossless remux when every source is already AAC-family. This is the
        # difference between a couple of minutes and a couple of hours.
        lossless = bool(options.get("preferLossless", True)) and can_remux_losslessly(sources)
        if options.get("forceTranscode"):
            lossless = False

        job_id = uuid.uuid4().hex
        job = {
            "jobId": job_id,
            "state": "queued",
            "progress": 0.0,
            "inputPath": input_path,
            "outputPath": output_path,
            "options": options,
            "lossless": lossless,
            "sourceFileCount": len(sources),
            "createdAt": time.time(),
            "log": [],
        }
        with _jobs_lock:
            _jobs[job_id] = job
            while len(_jobs) > MAX_JOB_HISTORY:
                oldest_id, oldest = next(iter(_jobs.items()))
                if oldest["state"] in ("queued", "running"):
                    break
                _jobs.pop(oldest_id)

        _work_queue.put(job_id)
        log.info("Job %s queued: %d source file(s), lossless=%s -> %s",
                 job_id, len(sources), lossless, output_path)
        self._send_json(202, job_view(job))

    def do_DELETE(self):
        path = self.path.rstrip("/")
        if not path.startswith("/jobs/"):
            self._send_json(404, {"error": "not found"})
            return
        job_id = path.split("/jobs/", 1)[1]
        with _jobs_lock:
            job = _jobs.get(job_id)
            if not job:
                self._send_json(404, {"error": "job not found"})
                return
            if job["state"] in ("completed", "failed", "cancelled"):
                self._send_json(409, {"error": f"job already {job['state']}"})
                return
            job["state"] = "cancelled"
            job["finishedAt"] = time.time()
            pid = job.get("pid")
        if pid:
            try:
                os.killpg(os.getpgid(pid), signal.SIGKILL)
            except Exception as exc:
                log.warning("Could not kill job %s (pid %s): %s", job_id, pid, exc)
        log.info("Job %s cancelled", job_id)
        self._send_json(200, {"jobId": job_id, "state": "cancelled"})


def main():
    for index in range(MAX_WORKERS):
        threading.Thread(target=worker_loop, name=f"merge-worker-{index}", daemon=True).start()
    log.info("Starting m4b merge service on port %s with %d worker(s)", PORT, MAX_WORKERS)
    for name, version in tool_versions().items():
        log.info("%s: %s", name, version)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
