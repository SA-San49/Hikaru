# Visual Identity & Engine Optimization Plan

## Tasks
- [x] **Revert to Stable Baseline**: Reverted codebase to commit `5acfe13831`.
- [x] **Visual Identity Overhaul**:
    - [x] Processed new Black & White icons from "Serious" folder.
    - [x] Implemented non-adaptive app icon (centered, zoomed-in fit).
    - [x] Created theme-responsive splash screen (Black on White / White on Black).
    - [x] Fixed notification and banner branding.
- [x] **Player Engine Upgrade**:
    - [x] Upgraded to mpv 1.18.n and FFmpeg 1.18 (Android 15 support).
    - [x] Implemented 60fps smooth speed ramping (0.1x increments).
    - [x] Optimized hardware decoding (`hwdec=auto`) and profile (`gpu`).
- [x] **Stability & Fixes**:
    - [x] Resolved `IllegalArgumentException: Key was already used` in player panels and library grids.
    - [x] Resolved `Resources$NotFoundException` by disabling resource shrinking for preview.
    - [x] Restored `minSdk 21` support while keeping SDK 36 features.
- [x] **Performance Optimization**:
    - [x] Increased image loading parallelism to 12 threads.
    - [x] Optimized player cache for instant startup.
- [x] **Automation**:
    - [x] Established automated GitHub releases for Hikaru.
