# Player Settings

Internal player configuration, performance defaults, video filters, and gesture controls.

---

## Internal Player

### Performance Defaults

Hikaru defaults player options for lower resource usage:

- **Audio Sync**: Default `video-sync` is set to `audio`. `display-resample` is automatically enabled only when **Motion Interpolation** is active.
- **Font Caching**: Caches subtitle fonts across player sessions to reduce player launch time.
- **Stats Overlay**: Technical stats overlay polls hardware stats only while visible, eliminating background CPU overhead when hidden.

### Scaling and Dithering (`spline36`)

Replaces standard bilinear scaling with a 36-tap cubic spline scaler (`spline36`) and fruit dithering to reduce line-art pixelation and gradient banding.

> [!WARNING]
> Spline36 scaling increases GPU load compared to bilinear scaling. If the device throttles or drains battery quickly, disable this option.

---

## Video Filters & Presets

Adjust video controls in the player using the **Filters (MPVFX)** panel.

### Available Filters
- **Debanding**: Smooths color transitions to reduce banding in dark gradients.
- **Adjustments**: Sliders for Brightness, Contrast, Saturation, Gamma, Hue, and Sharpening.

### Preset Profiles

| Preset | Adjustments | Target Material |
|:---|:---|:---|
| **Vivid Anime** | Contrast +5, Saturation +20, Sharpen +1 | Low-saturation or flat color line art |
| **Cinema** | Brightness -5, Contrast +15, Saturation -10, Gamma -5 | High-contrast dark scenes |
| **Vintage** | Contrast +10, Saturation -30, Gamma -10, Hue -5 | Older or desaturated animation |

---

## Touch Gestures

- **Horizontal Swipe**: Seek forward or backward on the timeline.
- **Vertical Swipe (Left)**: Adjust screen brightness.
- **Vertical Swipe (Right)**: Adjust volume level.
- **Long-Press**: Accelerate playback to 2x speed (resumes normal speed on release).
- **Pinch-to-Zoom**: Magnify video up to 3x.
- **Double-Tap**: Seek by the configured skip interval.

---

## Custom MPV Configuration (`mpv.conf`)

Custom MPV settings can be loaded via a configuration file:

- **Path**: Place `mpv.conf` at `Hikaru/mpv-config/mpv.conf` in your selected storage location.
- **Directives**: Supports MPV options such as `demuxer-max-bytes`, `sub-font`, `sub-color`, `audio-delay`, and hardware decoder properties.
- **Runtime Adjustments**: Audio delay (ms) and subtitle offset can also be adjusted on the fly in the player controls overlay.

---

## Player Control Shortcuts

Customize quick action buttons on the video player overlay:

1. Go to **Settings → Player → Player controls**.
2. **Reorder Buttons**: Drag actions to reorder quick-access items.
3. **Available Actions**: Includes **Anime4K**, **MPVFX filters**, **AniSkip**, **Audio track selection**, **Playback speed**, and **Aspect ratio**.
