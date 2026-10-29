<div align="center">

<img src="./.github/assets/icon.png" alt="Hikaru icon" width="100"/>

# Hikaru

### A personal anime & movie client for Android.
*A custom media player built with Jetpack Compose, designed for fluid interactions, smart offline playback, and experimental integrations.*

[![Discord](https://img.shields.io/badge/Discord-Community-5865F2?logo=discord&logoColor=FFFFFF&style=flat-square)](https://discord.gg/DISCORD_INVITE_URL)
[![Preview Build](https://img.shields.io/github/actions/workflow/status/SA-San49/Hikaru/preview.yml?branch=preview&label=Preview%20Build&style=flat-square)](https://github.com/SA-San49/Hikaru/actions/workflows/preview.yml)
[![Release](https://img.shields.io/github/v/release/SA-San49/Hikaru?style=flat-square)](https://github.com/SA-San49/Hikaru/releases)
[![Downloads](https://img.shields.io/github/downloads/SA-San49/Hikaru/total?style=flat-square)](https://github.com/SA-San49/Hikaru/releases)
[![Views](https://komarev.com/ghpvc/?username=SA-San49&repo=Hikaru&style=flat-square&color=0877d2)](https://github.com/SA-San49/Hikaru)
[![License](https://img.shields.io/github/license/SA-San49/Hikaru?label=License&color=0877d2&style=flat-square)](/LICENSE)
</div>

---

## 📖 About Hikaru

Hikaru is an independent fork of the AniZen source, built on upstream work from Aniyomi and Anikku. It is developing its own app identity while retaining the Apache-2.0 license and required attributions. 

It aims to offer a premium, responsive, and completely personalized viewing space while remaining lightweight and respectful of hardware resources.

---

## Features

<div align="left">

### Features include:

* **Hikaru**:
  * **🎬 Media Player & Video Engine**:
    * `Anime4K Neural Shaders` built-in upscaling quality levels (Fast, Balanced, High) and multiple processing modes (A, B, C, A+, B+, C+) for real-time video upscaling.
    * `Motion Interpolation` temporal interpolation scaling (oversample, mitchell, catmull-rom) generating smooth frames matching the display refresh rate (up to 120fps/144fps).
    * `MPVFX Filter Suite` card-based interface in the player for Debanding, Video Adjustments (Brightness, Saturation, Contrast, Gamma, Hue, Sharpen), and integrated Anime4K controls.
    * `Dynamic Mediacodec Switching` automatic fallback to software decoding if active filters exceed hardware capability.
    * `Adaptive Shader Scaling` automatically downgrades Anime4K quality if high frame drops/delayed frames are detected during playback to keep rendering smooth.
    * `AniSkip Integration` skippable intros and Netflix-style skip button based on the online AniSkip database.
    * `Filler Episode Skipping` automatically skips filler episodes based on tracking information.
    * `Native Picture-in-Picture (PiP)` background playback support with custom controls.
    * `Advanced Config & Script Editor` in-app code editor for `mpv.conf` and `input.conf` files, and support for running custom scripts.
    * `Pinch-to-Zoom Gesture` pinch gestures and precise scale control (up to 3x) with dedicated zoom sheet adjustments during playback.
    * `On-Demand Subtitles` loading of external/custom local subtitle files directly into the active player session via URI.
    * `Default Stream Memory` automatically remembers preferred stream hoster and quality per anime to fast-forward selection for subsequent episodes.
    * `Fluid Playback Gestures` long-press to activate jitter-free 2x speed with release animation, and horizontal slide speed adjustments.
    * `Volume Boosting & Pitch Correction` volume boosting up to 200% with pitch correction to preserve original voices at high speeds.
    * `Custom Aspect Ratios & Sleep Timer` define custom aspect ratio values and schedule playback sleep timers.
    * `Custom Player Layout` fully configurable action buttons and player interface layout reordering.
  * **📥 Resilient Downloader & Storage**:
    * `1DM-Style Downloader` multi-threaded chunked download engine utilizing byte-range splitting.
    * `Resilient Part-File Recovery` per-part file size verification, automatic 5x retry logic with exponential backoff, and robust `BufferPool` recycling.
    * `External Downloader Handoff` seamless delegation of downloads to external managers (like 1DM or ADM), automatically passing custom stream headers, filenames, and download directories.
    * `Native HLS & DASH Engines` multi-threaded HLS segment downloader with on-the-fly AES-128 decryption, variant playlist resolution, and native DASH muxing via FFmpeg with duration-based progress estimation.
    * `Pro-Active Stream Pre-fetching` background stream URL resolution for queued downloads, minimizing delay between transitions.
    * `Pre-Flight Storage Protection` automatic space allocation checks (maintaining a 200MB safety buffer, or 1.5x for FFmpeg operations) before download execution to prevent system instability.
    * `Early Soft Subtitles Retrieval` downloads and packages VTT, ASS, and SRT subtitle tracks automatically and non-fatally alongside the video file.
    * `Atomic Directory Assembly` downloads are isolated in a sandbox cache using temporary folder renames (`_tmp`) to prevent partial downloads from cluttering public storage.
    * `Preload Next Episode` pre-resolves stream links and hoster lists in the background with network-aware throttling to prevent playback stutter.
  * **📰 Feed & Personalization**:
    * `Category-Styled Feeds` feed homepage organizes saved searches and popular content under custom category sections with drag-and-drop ordering.
    * `Saved Search Feeds` pins specific keyword queries and filter configurations directly as auto-updating feed rows.
    * `Unified Feed Tab` view the latest entries or saved searches from multiple sources simultaneously.
    * `Custom Cover Art` set custom covers using local files or web URLs.
    * `Auto theme color` based on each entry's cover for entry View.
    * `Dynamic Player Theme` automatically themes the media player interface colors based on the active cover art.
    * `App custom theme` with `22 Color palettes` for endless customization.
    * `Panorama cover` showing wide cover in full.
    * `Library Folders` group specific anime into custom collapsible sub-folders inside library categories.
    * `UI Container Styles` choose card-like container layouts per-tab (Library, Updates, History, Browse, Details, Settings).
    * `Haze Glassmorphism` toggleable glass-blur styling effects for top and bottom navigation bars.
  * **📊 Statistics & Maintenance**:
    * `Behavioral Watch Statistics` tracks rich watch habits (weekly heatmaps, genre affinity, status breakdowns, rolling 30-day feed activity logs, preferred viewing times, and top-viewed titles) and infrastructure metrics (throughput distribution, latency matrices, and topology breakdowns).
    * `Extension Health Monitoring` live reports detailing extension latency, online node status, and connections metrics.
    * `AI Diagnostics & Assistant` conversational troubleshooting assistant capable of digesting exception trace logs and library context.
    * `Unified Rating Distribution` calculates and displays score distributions and mean ratings by combining local ratings and synced tracker data.
    * `Extension Repository Source Mapping` resolves and tracks the specific GitHub repository (owner/repo) from which extensions are installed.
    * `Diagnostics Report Export` formats and exports extension health statistics, resolve statuses, and logs directly to the clipboard.
    * `Adaptive Navigation` suggests layout presets (Default, Minimal, Power) dynamically based on network connectivity and time-of-day.

</div>

---

## 🛠️ Technical Architecture

Hikaru follows **Clean Architecture** principles to separate business logic, UI, and data handling into a modular structure:

```
app                   # Entry point, dependency injection configuration
├── core              # Shared helpers, common extensions, and base utilities
├── data              # Repositories, database (SQLDelight), and networking (OkHttp)
├── domain            # Core business logic, use cases, and domain models
├── presentation-core # Reusable UI components, themes, and design tokens (Compose)
├── source-api        # Extension API interfaces and definitions
└── source-local      # Local storage and media indexers
```

### Technical Stack & Decisions
*   **Development Platform:** 100% Kotlin with Jetpack Compose for declarative UI.
*   **Concurrency:** Kotlin Coroutines & Flow for asynchronous tasks and state streaming.
*   **Database:** SQLDelight for compile-time safe SQL queries.
*   **Core Shaders:** High-quality `ewa_lanczossharp` scaling for sharpest anime lines.
*   **Scrolling Performance:** Implements `Precision.INEXACT` cover scaling to delegate image processing to the GPU, removing micro-stutter and keeping scrolling fluid on 120/144Hz displays.

---

## 🚀 Getting Started

### For Users
1. Head over to the [Releases](https://github.com/SA-San49/Hikaru/releases) tab.
2. Download the latest `arm64-v8a` release APK.
3. Install the APK (requires enabling *Install from Unknown Sources*).
4. Hikaru installs under package ID `app.hikaru`.

### For Developers
Clone the repository and build using Gradle:
```bash
git clone https://github.com/SA-San49/Hikaru.git
cd Hikaru
./gradlew assembleDebug
```
Automated preview builds can also be found in the [Actions tab](https://github.com/SA-San49/Hikaru/actions/workflows/preview.yml).

---

## 🤝 Contributing & Support

*   **Bug Reports:** Report issues and attach logs via [GitHub Issues](https://github.com/SA-San49/Hikaru/issues).
*   **Community:** Join our [Discord Server](https://discord.gg/DISCORD_INVITE_URL) to ask questions, chat, or suggest features.

---

## 💖 Credits

Hikaru would not be possible without the incredible open-source projects it builds upon:
*   [Aniyomi](https://github.com/aniyomiorg/aniyomi) & [Anikku](https://github.com/komikku-app/anikku) (Core codebase foundations)
*   [Anime4K](https://github.com/bloc97/Anime4K) (Real-time shaders)
*   [mpvEx](https://github.com/marlboro-advance/mpvEx) (MPV integration patterns)

---

## 📄 License

Hikaru is open-source software licensed under the [Apache-2.0 License](LICENSE).

---

<div align="center">
<sub>Designed, directed, and built solo. Every detail intentional.</sub>
</div>
