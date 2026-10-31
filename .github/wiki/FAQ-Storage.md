# Storage FAQ

Frequently asked questions regarding download locations, cache clearing, and storage permissions.

---

## Where does Hikaru store application data and downloads?

- **Database & Cache**: `/data/data/app.hikaru/` (internal app storage).
- **Storage Location Subdirectories**: `Hikaru/` in primary shared storage by default, containing:
  - `downloads/`: Episode video downloads (contains `.nomedia` file to exclude from gallery apps).
  - `autobackup/`: Automatic library backup files.
  - `localanime/`: Local media files.
  - `mpv-config/`: MPV configuration files, scripts, and fonts.
  - `logs/`: Application error logs.

---

## How do I change the default storage / download location?

1. Go to **More → Settings → Data and storage**.
2. Select **Storage location**.
3. Use the system file picker to select a directory or SD card path via Storage Access Framework (SAF).

---

## How do I clear cached episode files?

1. Go to **More → Settings → Data and storage**.
2. Select **Clear episode cache**.
3. Confirm the action. This removes temporary cached episode data without affecting your saved library database or downloaded video files.

---

## How do I use an external SD card with Storage Access Framework (SAF)?

To store downloads or local anime files on an external SD card:

1. Go to **More → Settings → Data and storage → Storage location**.
2. Select **Custom location** or **External SD card**.
3. In the Android Storage Access Framework picker, open the navigation drawer (top left menu).
4. Select the SD card.
5. Select or create an `Hikaru` folder on the SD card, then select **Use this folder**.
6. Select **Allow** to grant persistent SAF read/write permissions.

