# Installation & Setup Guide

Technical instructions for installing Hikaru, adding extension repositories, managing library titles, and performing global searches.

---

## Requirements

- **Android Version**: Android 8.0 (API 26) or higher.
- **Architectures**: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`, or `universal`.
- **Storage Space**: ~150 MB internal storage.

---

## Installation

1. Download the latest APK matching your device architecture from [GitHub Releases](https://github.com/SA-San49/Hikaru/releases).
2. Open the downloaded `.apk` file.
3. Enable **Install from unknown sources** if prompted by Android OS.
4. Complete system package installation.

> [!NOTE]
> Hikaru uses package name `app.hikaru`. It installs independently alongside Tachiyomi, Aniyomi, or Anikku without shared data collisions.

---

## Adding Extension Repositories

1. Go to **Browse → Extensions → ⋮ (Overflow menu) → Extension Repositories** (or **Settings → Browse → Extension Repositories**).
2. Tap **Add repository** (+).
3. Enter the HTTPS extension repository index URL.
4. Under **Browse → Extensions**, select an available extension and tap **Install**.

---

## Library Management

1. Open **Browse** and select an installed source.
2. Find a series via **Popular**, **Latest**, or search.
3. Open the series details page and tap **Add to library**.

> [!TIP]
> Group saved titles into custom categories in **Settings → Library → Categories**.

---

## Global Search & Troubleshooting

### Performing Global Search
1. Open **Browse**.
2. Tap **Global search** in the top toolbar (or tap the Browse navigation icon).
3. Type a query. Search queries execute across all enabled sources concurrently.

### Search Resolution Tips
- **Romanized Titles**: Sources often index Japanese titles in Romaji rather than English translations (e.g., *Boku no Hero Academia* vs. *My Hero Academia*).
- **Keyword Filtering**: Shorten queries if specific keywords fail to match exact provider titles (e.g., *3-gatsu no Lion* vs. *Sangatsu no Lion*).
