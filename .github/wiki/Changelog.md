# Changelog

Release notes and version history for Hikaru.

---

## [Unreleased]

### Added
- **External Player Support**: Added `mpvKt` support ([@Secozzi](https://github.com/Secozzi)).
- **Video Filters**: Added real-time adjustment sliders for debanding, brightness, contrast, and saturation ([@abdallahmehiz](https://github.com/abdallahmehiz)).
- **Subtitle Selection**: Improved automatic subtitle track selection rules ([@Secozzi](https://github.com/Secozzi)).

### Fixed
- Fixed 0 MB download issues when downloading HLS master playlists natively.
- Fixed auto-download enqueuing logic for newly released episodes.
- Fixed issue where hidden library categories reset order after deletion ([@cuong-tran](https://github.com/cuong-tran)).
- Fixed Jellyfin enhanced tracking synchronization ([@Secozzi](https://github.com/Secozzi)).

### Improved
- Adjusted long-press gesture speed slider sensitivity.
- Updated timestamp strings to show "Now" instead of "0 minutes ago".

---

## [v0.16.4.0] — 2024-07-01

### Fixed
- Fixed Picture-in-Picture intent broadcasting on Android 14+.
- Fixed crash when opening advanced player settings on Android 10 and lower.

### Improved
- Auto-hide the skip intro button when the skipped interval evaluates to 0 seconds.
