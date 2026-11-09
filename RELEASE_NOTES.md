<!-- version: 0.5.221 -->
<!--
  Hand-written release notes for v0.5.221.

  Everything outside this comment block is published to the GitHub release
  and Discord update message. Keep the notes friendly, accurate, and user-facing.
-->

Here’s what’s new in Hikaru v0.5.221. 🚀

### AI assistant
- Ask questions using relevant information from your library, watch history, browse sources, airing schedule, saved feeds, or community directory.
- Choose which information the assistant can use; only enabled and relevant information is included.
- Improved streamed replies and handling of provider errors, cancelled requests, interrupted streams, and empty responses.

### Downloads
- Parallel downloads now check server support and use a single connection when needed.
- Improved HLS download handling and network-request cancellation.
- The download queue now shows progress, speed, estimated time remaining, and pause/resume controls.

### Startup and library
- Removed an unnecessary minimum splash wait and moved migration work off the main thread.
- Tuned image loading for phones with two CPU cores or limited memory.
- Reduced unnecessary cover processing and library refreshes, and improved category handling.
