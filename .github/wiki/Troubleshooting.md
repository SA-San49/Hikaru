# Troubleshooting

Diagnostics and solutions for playback, extension source, download, and system issues in **Hikaru**.

---

## Common Issues & Solutions

### Extension Source 403 Forbidden / Loading Errors
- **Cause**: Anti-bot Cloudflare protection triggered by the target source host.
- **Solution**: Open the source in **Browse**, tap the **WebView** icon in the toolbar, and complete the Cloudflare challenge.

### Video Stuttering or High Battery Consumption
- **Cause**: High GPU load from Anime4K shaders or Jinc scaling (`ewa_lanczossharp`).
- **Solution**: Navigate to **Settings → Player → Anime4K** and select **Balanced (M)** or **Fast (S)** quality presets, or enable **Adaptive Shader Scaling**.

### Subtitles Missing or Rendering Incorrectly
- **Cause**: Missing embedded fonts or unsupported container soft-sub streams.
- **Solution**: Open the player subtitle selector to switch tracks, or select **Load external subtitle...** to load a local `.srt` or `.ass` file.

### Download Fails Immediately (0 MB)
- **Cause**: Expired playback session tokens or unresolved HLS master playlist URL.
- **Solution**: Update Hikaru to the latest release to use updated HLS variant resolving, or re-fetch the stream link from the source.

---

## Cache Maintenance

Options located under **Settings → Data and storage** and **Settings → Advanced**:

- **Clear Chapter Cache**: Flushes cached stream metadata and temporary video segments.
- **Clear Cookies**: Removes stored network cookies across extension WebViews.
- **Clear WebView Data**: Resets browser cache, web storage, form data, and SSL sessions.
- **Clear Database**: Removes non-library anime records and unindexed source metadata.

---

## System Diagnostics & Log Export

### Extension Health Monitor

1. Open **Hikaru** and navigate to **More → Extension Health**.
2. The health monitor tests active sources for latency, IP endpoints, TLS versions, and availability.
3. Tap **Copy Report** to copy the diagnostics report to the clipboard.

### Exporting Crash Logs

1. Navigate to **More → Settings → Advanced**.
2. Tap **Dump crash logs** (or select **Dump crash logs** on the crash handler overlay if app crashes).
3. Hikaru exports `hikaru_crash_logs.txt` containing system specs, installed extension versions, and recent `logcat` error entries.
4. Attach `hikaru_crash_logs.txt` when opening a [GitHub Issue](https://github.com/SA-San49/Hikaru/issues).


