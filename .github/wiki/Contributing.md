# Contributing

Guidelines for reporting issues, contributing code, and managing translations.

---

## Code contributions

Pull requests are welcome. Comment on an issue ticket before starting work to avoid duplicate effort.

### Prerequisites

Hikaru development requires familiarity with:

- **Android Development** using Android Studio and Gradle.
- **Kotlin** (Coroutines, Flow).
- **Jetpack Compose** UI framework.

### Development setup

1. Clone the repository:
   ```bash
   git clone https://github.com/SA-San49/Hikaru.git
   ```
2. Open the project in **Android Studio**.
3. Connect an Android device or launch an emulator.
4. Run `./gradlew assembleDebug` to build and install the debug APK.

---

## Reporting issues

Check existing [GitHub Issues](https://github.com/SA-San49/Hikaru/issues) before opening a new ticket.

Include the following details in bug reports:
- Device model and Android version.
- Exact Hikaru version string.
- Steps to reproduce the issue.
- Relevant logs or export files from **More → Settings → Diagnostics**.

---

## Translations

Translations are managed through Hikaru's configured [Weblate project](https://hosted.weblate.org/projects/salmanbappi/anizen/). Submitted strings are automatically synchronized to the repository.
