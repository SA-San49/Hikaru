# Backups Guide

Manage manual and automatic library backups in Hikaru.

---

## Creating a Backup

1. Go to **More → Settings → Data and storage**.
2. Under **Backup and restore**, select **Create backup**.
3. Select the data to include (Library entries, Categories, Episodes, Tracking, History, App settings, Extension repos, Custom buttons, Source settings) and choose an output directory.

> [!NOTE]
> Backups are saved as Gzip-compressed Protobuf binary files (`.tachibk`). JSON format is not supported.

---

## Backup Contents

Depending on your selection, backups contain:

- **Library Titles**: Saved anime and movie entries (and non-library seen entries if selected).
- **Categories**: Custom categories and category flags.
- **Watch History & Progress**: Episode watch state, bookmarks, and timestamps.
- **Tracker Connections**: Active tracking accounts and series IDs.
- **App & Source Settings**: Player configuration, themes, app settings, private settings, and source settings.
- **Extensions & Repositories**: Installed extension identifiers and repository URLs.
- **Custom Buttons**: Player button configurations.

### Excluded Data

- Downloaded video files and local media.
- Custom cover images (re-downloaded on restore).
- Search history and cache files.

---

## Restoring a Backup

1. Transfer the `.tachibk` file to the target device.
2. Log into tracking services under **More → Settings → Tracking** if restoring tracker data.
3. Go to **More → Settings → Data and storage**.
4. Under **Backup and restore**, select **Restore backup** and pick the `.tachibk` file.

---

## Automatic Backups

### Configuration

1. Go to **More → Settings → Data and storage**.
2. Under **Backup and restore**, select **Backup frequency**: *Every 6 hours*, *Every 12 hours*, *Daily (24 hours)*, *Every 2 days (48 hours)*, or *Weekly (168 hours)*.
3. Select **Backup slots** to set the retained copy limit (1 to 5 files).
4. Files are saved in the `autobackup/` directory inside your configured **Storage location**.

> [!TIP]
> The `autobackup/` folder can be synchronized to cloud services using third-party tools (e.g., FolderSync) or Hikaru's built-in Google Drive / SyncYomi sync under **Data and storage → Sync**.

