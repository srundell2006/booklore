# BookLore Audiobook Merge Sidecar

Async job API over [`m4b-tool`](https://github.com/sandreas/m4b-tool), so BookLore can
merge audiobooks into single `.m4b` files with chapters and tags.

Built on `sandreas/m4b-tool:latest`, which bundles ffmpeg with `libfdk_aac`, the custom
`mp4v2` fork and `fdkaac` — the dependencies that make a manual install painful.

## Why paths instead of uploads

Audiobooks are 500 MB–5 GB and merges take minutes to hours. This service shares the
same volume mounts as BookLore **at identical paths**, so BookLore sends a path and both
containers mean the same bytes. Nothing is streamed over HTTP.

## Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/health` | Tool versions, worker count, active job count |
| `POST` | `/jobs` | Queue a merge → `202` with `jobId` |
| `GET` | `/jobs` | List recent jobs |
| `GET` | `/jobs/{id}` | State, progress %, log tail, output size, error |
| `DELETE` | `/jobs/{id}` | Cancel a queued or running job |

### Queue a merge

```bash
curl -X POST http://m4b-merge:8080/jobs -H 'Content-Type: application/json' -d '{
  "inputPath":  "/books/A/Author/Some Audiobook",
  "outputPath": "/books/A/Author/Some Audiobook.m4b",
  "options": {
    "audioBitrate": "64k",
    "audioSamplerate": "22050",
    "audioChannels": "1",
    "maxChapterLength": "300,900",
    "jobs": 2,
    "name": "Some Audiobook",
    "artist": "Author",
    "series": "Some Series",
    "seriesPart": "2",
    "cover": "/books/A/Author/Some Audiobook/cover.jpg"
  }
}'
```

## Supported cases

| Case | Behaviour |
|------|-----------|
| N × m4b/m4a → 1 m4b | Auto-detects AAC-family sources and uses `--no-conversion` — a **lossless remux**, minutes not hours |
| N × mp3 → 1 m4b | Full transcode to AAC using the supplied encoding options |
| 1 × mp3 → m4b | Same path; still gains chapters and proper tags |

Set `"forceTranscode": true` to override lossless detection, or
`"preferLossless": false` to disable it.

## Environment

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Listen port |
| `MAX_WORKERS` | `1` | Concurrent merges (ffmpeg saturates cores; 1–2 is sensible) |
| `JOB_TIMEOUT_SECONDS` | `21600` | Kill a job running longer than this |
| `MAX_JOB_HISTORY` | `100` | Completed jobs retained for polling |

## Notes

- `--series` / `--series-part` drive m4b-tool's sort-order generation and map directly
  onto BookLore's `seriesName` / `seriesNumber`.
- `cover.jpg` and `description.txt` inside the input directory are embedded automatically.
- `maxChapterLength: "300,900"` splits over-long chapters at detected silences — useful
  when source files are one-chapter-per-hour.
