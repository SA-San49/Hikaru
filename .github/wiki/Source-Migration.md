# Source Migration Guide

Migrate series entries between extension sources while preserving watch history, tracker links, and bookmarks.

---

## Overview

Source migration allows transferring series data between extensions when:
- An extension domain is offline or defunct.
- A replacement source offers higher resolution or faster mirrors.
- Standardizing library items to a single provider.

---

## Single Series Migration

1. Open the target anime details page.
2. Tap **⋮ (Overflow menu) → Migrate**.
3. Select the destination extension.
4. Select the corresponding title from search results.
5. Review episode mapping and tap **Migrate**.

---

## Mass Migration

1. Go to **Browse → Migrate**.
2. Select the source to migrate from (or use long-press selection for multiple sources).
3. Select titles to transfer.
4. Choose the target source.
5. Confirm batch migration. Hikaru maps episode numbers and watch progress automatically.

---

## Migration Flags & Options

Selectable attributes during migration:

- **Episodes & Watch History**: Copies watch progress (`seen`), timestamps (`seenAt`, `watchDuration`), bookmarks, and fetch dates.
- **Categories**: Retains category assignments.
- **Tracking**: Rebinds active trackers (AniList, MyAnimeList) to the new source entry.
- **Delete Downloads**: Removes downloaded media files associated with the old source upon completion.
- **Custom Cover**: Retains custom cover art.
- **Extra Flags**: Transfers display flags and viewer preferences.

> [!TIP]
> Verify episode number alignments in your library after batch migrating across sources with differing release numbering.
