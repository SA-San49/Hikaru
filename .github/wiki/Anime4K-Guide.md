# Anime4K Guide

Shader-based upscaling modes, performance presets, and configuration options.

---

## Overview

**Anime4K** is an open-source set of GLSL upscaling and denoising shaders optimized for anime. Hikaru applies these shaders directly inside the MPV player rendering pipeline.

---

## Upscaling Modes

Anime4K algorithms target specific source video conditions:

### Mode A (Faithful Reconstruction)
- **Target Video**: 720p or 1080p source material.
- **Function**: Reconstructs line art edges while maintaining original detail.
- **Use Case**: HD animation viewed on 1440p or 4K screens.

### Mode B (Deblur)
- **Target Video**: Soft or blurry 720p and SD source material.
- **Function**: Applies perceptual sharpening to soft lines.
- **Use Case**: Older anime releases with soft focus.

### Mode C (Denoise & Restore)
- **Target Video**: 480p, DVD rips, or heavily compressed video.
- **Function**: Removes compression artifacts and blocking noise before scaling.
- **Use Case**: Low-bitrate web streams with compression noise.

### Mode A+, B+, and C+
- Applies a second reconstruction pass for higher sharpness.
- **Hardware Requirement**: High-end GPUs (e.g., Snapdragon 8 Gen 1 or equivalent).

---

## Quality Profiles

Shader load varies by profile size:

| Profile | Shader Size | GPU Load | Recommended Hardware |
|:---|:---|:---|:---|
| **Fast (S)** | Small | Low | Mid-range SoCs (Snapdragon 7xx) |
| **Balanced (M)** | Medium | Moderate | Upper mid-range / older flagships (Snapdragon 865/870/888) |
| **High (L)** | Large | High | Flagship SoCs (Snapdragon 8 Gen 1+) |

---

## Performance Troubleshooting

If video stutters or audio desynchronizes during playback:

1. Reduce profile quality from **High (L)** to **Balanced (M)** or **Fast (S)** in **Settings → Player → Anime4K**.
2. Switch from **Plus (+)** variants to standard modes (e.g., Mode A+ to Mode A).
3. Disable **High-quality scaling** (`spline36`) to lower total GPU load.
4. Enable **Adaptive Shader Scaling** in **Settings → Player → Anime4K** so Hikaru can automatically reduce shader complexity when frame drops are detected.

> [!NOTE]
> Anime4K shaders require the standard `gpu` renderer. If `gpu-next` is active, Hikaru bypasses Anime4K shaders to avoid pipeline conflicts. Select the `gpu` renderer to use Anime4K.
