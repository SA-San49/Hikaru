# Tracking

Configuration and synchronization details for external tracking services in **Hikaru**.

---

## Supported Services

Hikaru integrates with the following tracking services:

- **MyAnimeList (MAL)**
- **AniList**
- **Kitsu**
- **Shikimori**
- **Bangumi**
- **Simkl**
- **Jellyfin**
- **Trakt**
- **TMDB**

---

## Tracker Authentication

1. Open **Hikaru** and navigate to **More → Settings → Tracking**.
2. Select the target service (e.g., **AniList**).
3. Tap **Log in** to open the OAuth authorization flow in the browser.
4. Authorize Hikaru. Upon redirection, status will update to **Logged in**.

---

## Series Binding

1. Open an anime entry from the **Library** or **Browse** tab.
2. Select the **Tracking** tab (or tap the tracking icon on the series detail page).
3. Select the service. If auto-matching fails, use the search field to query the title manually.
4. Select the matching entry to establish the binding.

---

## Auto-Tracking Configuration

When a series is bound, episode progress updates automatically upon reaching the configured threshold.

Settings path: **More → Settings → Tracking**

- **Update tracking when watching**: Toggles automatic progress dispatching when reaching the trigger threshold.
- **Trigger threshold**: Minimum percentage of episode duration watched (default: **85%**) before sending progress updates.

---

## Multi-Service Sync & Status Mapping

Hikaru supports binding a title to multiple services concurrently (e.g., AniList and Trakt):

- **Status Mapping**: Watch status (*Watching*, *Completed*, *On Hold*, *Dropped*, *Plan to Watch*) synchronizes across all bound accounts.
- **Score Synchronization**: Ratings map to each platform's scoring system (10-point decimal for MAL/AniList, 5-star, or 100-point scales).
- **Media Format Tracking**: Services such as **Trakt**, **TMDB**, **Simkl**, and **Jellyfin** track both episodic series and standalone movie/OVA entries.


