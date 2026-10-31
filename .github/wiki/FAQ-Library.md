# Library FAQ

Frequently asked questions regarding library management, updates, categories, and cover art.

---

## Why is Global Update skipping entries?

Background updates can skip entries based on configured Smart Update restrictions (**More → Settings → Library → Global update → Smart update**):

- Has unwatched episodes (`Skip entries with unwatched episodes`).
- Has not been started yet (`Skip entries that haven't been started`).
- Marked with a **Completed** status (`Skip completed series`).
- Outside expected release schedule (`Skip entries outside release period`).

This reduces unnecessary server requests and prevents rate-limiting by extension sources.

> [!TIP]
> Categories included in global updates can be configured under **More → Settings → Library → Global update → Categories**.

---

## Why are background library updates not running?

Android device skins (such as MIUI, ColorOS, or OneUI) apply aggressive background process optimization that terminates background tasks.

To configure background execution:

1. Go to **More → Settings → Advanced**.
2. Select **Disable battery optimization**.
3. Allow Hikaru to run unrestricted in system settings.

> [!NOTE]
> For device-specific configuration, see [Don't Kill My App](https://dontkillmyapp.com/).

---

## How do I display download badges on library entries?

To show badge counts for downloaded episodes on entry cards:

1. Open **Library**.
2. Select the filter icon in the toolbar.
3. Select **Display**.
4. Enable **Downloaded episodes** under **Badges**.

---

## How can I sync my library between devices?

Transfer your library database, watch history, and categories via backup files or cloud sync:

1. On the source device, go to **More → Settings → Data and storage**.
2. Under **Backup and restore**, select **Create backup**.
3. Transfer the `.tachibk` file to the target device.
4. On the target device, go to **More → Settings → Data and storage → Restore backup** and select the file.

> [!NOTE]
> Automatic cloud sync with Google Drive or SyncYomi is available under **More → Settings → Data and storage → Sync**.

---

## Why are cover thumbnails blank or corrupted?

Blank or missing thumbnails indicate incomplete image downloads due to network interruptions.

To reload covers:

1. Go to **More → Settings → Advanced**.
2. Select **Refresh library covers**.
3. Re-open the library to reload cover images.

---

## How do I pause watch history?

1. Go to **More**.
2. Toggle **Incognito mode** to **ON**.
3. An icon in the notification bar indicates watch history recording is paused.


