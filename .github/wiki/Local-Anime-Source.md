# Local Anime Source Guide

Technical guide for playing offline video files stored on local device storage, including file naming conventions, side-loaded subtitles, artwork, and JSON metadata schemas.

---

## Directory Structure

Hikaru indexes local media files from `Hikaru/localanime/` located in root storage.

1. Create `Hikaru/localanime/` in primary internal storage.
2. Group files into subfolders named after each series:

```
Hikaru/
└── localanime/
    ├── Frieren/
    │   ├── Episode 01.mp4
    │   ├── Episode 02.mkv
    │   └── cover.jpg
    └── Jujutsu Kaisen/
        ├── S01E01.mp4
        └── S01E02.mp4
```

---

## File Naming Conventions & Supported Formats

- **Episode Pattern**: Use standard numbering formats: `Episode 01`, `E01`, `S01E01`, or `[01]`.
- **Supported Formats**: `.mp4`, `.mkv`, `.webm`, `.avi`, `.flv`, `.mov`, `.wmv`, `.torrent`, `.m3u`, `.m3u8`.
- **Unsupported Formats**: `.ts` streams are not supported.

---

## Subtitles & Custom Artwork

- **Series Cover**: Add `cover.jpg`, `cover.png`, `cover.jpeg`, `Cover.jpg`, `Cover.png`, or `Cover.jpeg` inside the series subfolder.
- **Episode Thumbnails**: Place `<Episode Name>-thumbnail.jpg`, `<Episode Name>.jpg`, or `<Episode Name>-cover.jpg` in the same directory as the video file. If missing, FFmpeg extracts a static frame.
- **Subtitles**: Place `.ass`, `.srt`, or `.vtt` files alongside the video file using identical base filenames (e.g., `Episode 01.ass` with `Episode 01.mp4`).

---

## Local Metadata Files (`details.json` & `episodes.json`)

Optional JSON configurations inside `Hikaru/localanime/<Series Title>/` override default parsed values.

### `details.json`
Path: `Hikaru/localanime/<Series Title>/details.json`
```json
{
  "title": "Frieren: Beyond Journey's End",
  "author": "Kanehito Yamada",
  "artist": "Tsukasa Abe",
  "description": "An elf mage and her fellow adventurers have defeated the Demon King...",
  "genre": ["Adventure", "Drama", "Fantasy"],
  "status": 1
}
```

### `episodes.json`
Path: `Hikaru/localanime/<Series Title>/episodes.json`
```json
[
  {
    "episode_number": 1.0,
    "name": "The Journey's End",
    "summary": "After a 10-year quest, the Hero Party returns victorious...",
    "date_upload": "2023-09-29T00:00:00",
    "preview_url": "file:///path/to/custom-thumbnail.jpg"
  }
]
```
