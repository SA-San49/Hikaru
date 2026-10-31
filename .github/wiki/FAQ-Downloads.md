# Downloads FAQ

Technical details regarding download engines, stream handling, external downloader delegation, and storage verification.

---

## How does Hikaru's chunked download engine work?

Hikaru includes a multi-threaded internal download engine:

- **Byte-Range Chunking**: Splits video files into parallel streams using HTTP `Range` headers (1 to 64 threads, configurable in **More → Settings → Downloads → Internal Downloader**).
- **Part-File Recovery**: Writes chunk progress into temporary `.part` files (`$filename.part$i`) and retries network failures up to 5 times with exponential backoff and jitter.
- **Atomic Renaming**: Downloads are written to temporary `_tmp` directories and sandbox files (`.tmp`), and atomically renamed upon completion to avoid corrupting media storage.

---

## Can downloads be delegated to an external download manager?

Yes. Hikaru can hand off stream URLs to external download applications.

### Setup

1. Go to **More → Settings → Downloads**.
2. Under **External Downloader**, enable **Use external downloader**.
3. Select **External downloader** and choose an installed application (**1DM / 1DM+** or **ADM**).

Hikaru forwards the stream URL, required HTTP headers, User-Agent strings, and target file paths to the selected manager.

---

## Why does a download report 0 MB or fail immediately?

Immediate 0 MB download failures occur when an HLS master playlist variant cannot be resolved due to expired stream tokens.

- **Fix**: Update Hikaru to the latest version. The HLS engine dynamically resolves playlist variants and decrypts AES-128 key tokens before fetching segments.

---

## What is Pre-Flight Storage Protection?

Before starting downloads or muxing operations, Hikaru checks available disk space on the target volume.

If free storage drops below **200 MB** plus the target video size (or **1.5×** total size for FFmpeg/DASH muxing operations), the download aborts and returns an insufficient space error (`MR.strings.download_insufficient_space`).


