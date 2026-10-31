# Player FAQ

Frequently asked questions regarding MPV playback, Anime4K upscaling, video synchronization, and subtitles.

---

## Why does video stutter when Anime4K is enabled?

Anime4K shaders place high demand on GPU execution units.

If playback stutters:

1. Reduce the preset quality in **Settings → Player → Anime4K** to **Balanced (M)** or **Fast (S)**.
2. Avoid **Plus (+)** variants (`A+`, `B+`, `C+`) on mid-range hardware.
3. Enable **Adaptive Shader Scaling** to automatically reduce shader preset complexity when frame drops occur.

---

## What is the difference between `audio` and `display-resample` video sync?

- **`audio` (Hikaru Default)**: Syncs video frames to the audio clock, keeping CPU overhead low.
- **`display-resample`**: Resamples audio to match the display refresh rate. Required for **Motion Interpolation**, but increases CPU and GPU load.

Hikaru uses `audio` sync by default and automatically switches to `display-resample` when Motion Interpolation is enabled.

---

## What is the difference between HW (`mediacodec-copy`) and HW+ (`mediacodec`)?

In **Settings → Player → Decoder**:

- **HW (`mediacodec-copy`)**: Hardware decodes video frames into system RAM before sending them to the GPU renderer. This increases compatibility with software video filters and GLSL shaders on certain GPUs.
- **HW+ (`mediacodec`)**: Direct hardware decoding that renders directly to a Surface. Offers maximum battery efficiency and lower memory usage, but may bypass certain custom post-processing filters depending on GPU driver capabilities.

Hikaru allows switching between `mediacodec`, `mediacodec-copy`, `auto`, and software decoding directly in the player decoder menu.


---

## How do I load external subtitle files mid-playback?

To add external subtitles during video playback:

1. Tap the screen to display player controls.
2. Tap the **Subtitles** icon.
3. Select **Add external subtitles**.
4. Choose an `.srt`, `.ass`, or `.vtt` file from internal storage.

---

## How do I configure Player Settings in Hikaru?

Navigate to **Settings → Player**:

- **Player Engine**: Switch between the built-in MPV engine and external players (e.g., VLC, MX Player).
- **Decoder Mode**: Select hardware (`mediacodec`), hardware copy (`mediacodec-copy`), or software decoding.
- **Skip Duration**: Set seek intervals for double-tap gestures (e.g., 5s, 10s, 30s, 85s).
- **AniSkip**: Enable automatic skipping or manual skip buttons for opening and ending sequences.
- **Auto-Play Next Episode**: Start the next episode automatically on stream completion.
- **Track Persistence**: Save audio and subtitle language preferences per title.
