# Features

Technical overview of media playback, download pipeline, UI components, and diagnostic tools in **Hikaru**.

---

## Video Player & Engine

Media playback engine based on `mpv-android` (`libmpv` native bindings).

### Upscaling & Shaders
- **Anime4K Shaders**: Real-time upscaling supporting modes `A`, `B`, `C`, `A+`, `B+`, and `C+`.
- **Adaptive Shader Scaling**: Reduces upscaling quality preset when frame drops are detected.
- **Dynamic MediaCodec Switching**: Falls back to software decoding if active GLSL filters exceed GPU capabilities.

### Playback Options
- **Motion Interpolation**: Generates intermediate frames matching target display refresh rates (60Hz, 90Hz, 120Hz, 144Hz).
- **AniSkip Integration**: Displays skip prompts for opening and ending themes via AniSkip API.
- **Filler Episode Skipping**: Detects and skips filler episodes based on tracking database metadata.
- **Default Stream Memory**: Retains selected hoster and stream quality per series.

---

## Downloader & Storage Engine

Subsystem for managing media downloads and local storage:

- **Chunked Downloading**: Multi-threaded engine using HTTP byte-range requests.
- **Part-File Recovery**: Validates segment sizes and retries failed chunk downloads up to 5 times with exponential backoff.
- **Native HLS & DASH**: Muxes video, audio, and AES-128 decrypted streams into local MP4 containers.
- **External Downloader Handoff**: Exports download requests to external managers (such as 1DM or ADM), preserving HTTP headers and cookies.
- **Storage Pre-Flight Validation**: Verifies available device storage prior to download initiation.

---

## Customization & UI

- **Category Feeds**: Pin saved searches and category queries as home screen feed rows.
- **Theme Engine**: 21 preset color schemes and dynamic color extraction from entry cover artwork.
- **Glassmorphism Styling**: Optional Haze blur composables for top app bars and bottom navigation bars.
- **Custom Player Layout**: Reorder action buttons and controls in the player interface.

---

## Diagnostics & Analytics

- **Watch Statistics**: Displays genre charts, status ratios, score distributions, watch history timelines, and extension performance analytics.
- **Extension Health Monitor**: Measures real-time latency, node status, and response metrics for active source extensions.
- **Diagnostics Export**: Formats and copies error logs and extension diagnostics directly to the system clipboard.

