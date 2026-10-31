# Shizuku Setup Guide

Configure Shizuku to allow silent background installation and updating of extension packages without manual system prompt confirmation.

---

## System Requirements

- **Android Version**: Android 8.0 (API 26)+ required.
- **Wireless Debugging**: Android 11 (API 30)+ required (or ADB over USB for Android 8.0–10).

---

## Configuration Steps

1. Install **Shizuku** from Google Play or F-Droid.
2. Start the Shizuku service via Wireless Debugging or ADB.
3. In Hikaru, go to **Settings → Advanced → Extension installer**.
4. Select **Shizuku**.
5. Grant permission when the Shizuku authorization prompt appears.

---

## Wireless Debugging Setup (Android 11+)

1. Open **Android Settings → About phone** and tap **Build number** 7 times to enable Developer Options.
2. Navigate to **Developer options → Wireless debugging** and enable it.
3. Open **Shizuku** and select **Start via Wireless Debugging**.
4. Tap **Pairing** and enter the 6-digit code shown in the system notification.
5. Confirm Shizuku status reports `Running`, then set **Extension installer** to **Shizuku** in Hikaru.
