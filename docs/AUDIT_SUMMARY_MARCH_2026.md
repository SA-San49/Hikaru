# Hikaru Architecture & Performance Audit - March 20, 2026

## 1. Summary
Hikaru's core engine is highly optimized for modern Android hardware, focusing on fluidity (120Hz/144Hz support) and high-throughput concurrent operations.

## 2. Key Architecture Wins
- **Fluidity Layer:** Uses `Precision.INEXACT` for zero-math image scaling.
- **Concurrency:** Aggressive image decoding threads (up to 12-16 depending on core count).
- **Download Core:** 1DM-style multi-threaded chunking via `FileChannel.transferTo`.
- **Stability:** Solved SAF race conditions in `DownloadProvider.kt` using Mutex locks.

## 3. Future Roadmap (Proposed)
- SAF Directory Caching (`LRUCache` for UniFile).
- Early Disk Pre-allocation (`setLength`).
- Adaptive Thermal-Aware Shader Scaling.
- Scroll Baseline Profiles.