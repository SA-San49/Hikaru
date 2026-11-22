<!-- version: 0.5.223 -->
<!--
  Hand-written release notes for v0.5.223 (PR #16 + PR #17).
  Everything outside this comment block is published verbatim to GitHub Releases
  and the Discord update channel.
-->

Hey everyone! **Hikaru v0.5.223** is here with one of our biggest visual & performance updates yet — featuring custom launcher icons, 8 animated bottom bar styles, 5 brand-new themes, an Expressive Material 3 settings makeover, and dramatically faster Local Source loading! 🚀

### 🎨 Appearance, App Icons & Bottom Bar Studio
- **Customizable App Icons (6 Styles)**: Choose between **Default**, **Blood Moon**, **Sakura Lantern**, **Enso Sakura**, **Violet Twilight**, and **Moonlit Cat** inside **Settings → Appearance → App Icon**, complete with a live launcher preview hero stage and one-tap **Restart** dock.
- **8 Animated Bottom Navigation Bar Styles**: Customize your navigation bar in **Settings → Appearance → Bottom Navigation Bar** using a swipeable live studio with unique tab-sliding animations: **Classic Material**, **Glass Dock**, **Capsule Bar**, **Elevated Bubble**, **Floating Center**, **Wave Dock**, **Floating Pill**, and **Center Dock**.
- **5 New Color Themes & Modern Previews**: Redesigned the theme selector cards with a cleaner modern layout and added **Slate Mist** (our new sleek default accent), **Cyberpunk**, **Aurora**, **Sakura**, and **Crimson Noir**.

### ✨ Expressive Material 3 Settings & About
- **Redesigned Settings & More**: Refreshed **More**, **Settings**, **Player Settings**, sub-settings screens, **About**, and selection dialogs with grouped rounded cards, color-coded icon badges, smooth check-thumb switches, and tonal value pills — while keeping the classic transparent Hikaru kanji header.
- **Working In-App Update Checker**: Tapping **Check for updates** in **About** now properly checks for new Hikaru releases and safely launches the APK installer, while Discord and GitHub icons preserve their original brand colors.

### ⚡ Local Anime Source Overhaul
- **Instant Episode Loading**: Removed blocking per-episode scans so series with hundreds of episodes open almost instantly instead of freezing or getting stuck at 0 episodes.
- **Batch Folder Scanning**: Replaced hundreds of slow Android storage lookups with single-query folder scanning and smart caching, keeping browsing fast even on budget devices.
- **Lazy On-Demand Video Thumbnails**: Episode thumbnails now generate only for items visible on screen using hardware decoding first, with strict memory caps, bounded disk caching, and instant scroll cancellation.
- **Richer Format & Subtitle Support**: Added background cover generation for local series without covers, 1-level season subfolder support, wider video formats (`.m4v`, `.ts`, `.m2ts`, `.3gp`, `.mpg`, `.mpeg`, `.ogm`, `.ogv`), and automatic local `.srt`/`.ass`/`.ssa`/`.vtt`/`.sub` subtitle detection.

### 🛠️ Under the Hood & Cleanup
- **Over 4 MB Smaller APKs**: Converted icon assets to WebP and stripped unused packaging metadata for lighter downloads.
- **Faster Episode Sync & Smooth Scrolling**: Upgraded episode database syncing to linear time and optimized **More** & **Settings** rendering to eliminate lag.
- **Launcher Icon Reliability**: Fixed icon switching across stable, beta, and preview variants while preserving app labels, shortcuts, and full-color adaptive icon artwork.
- **Jimaku Removal**: Removed deprecated Jimaku subtitle integration and unused API settings.
