# General FAQ

Answers to general questions about Hikaru, installation, platforms, and app origins.

---

## Why isn't Hikaru on the Google Play Store?

**Hikaru** will not be available on the **Google Play Store**.

Third-party extension models conflict with [Google Play Developer Content Policies](https://play.google.com/about/developer-content-policy/). Providing modular source loading could lead to store takedowns, which we avoid by distributing directly via official GitHub releases.

---

## Is Hikaru available for iOS or iPadOS?

No. There is no iOS or iPadOS version, and there are no plans to create one. iOS and Android require completely separate codebases, programming languages, and UI frameworks.

> [!CAUTION]
> Any application claiming to be **"Hikaru for iOS"** is fake and should be treated as a security risk.

---

## What is Hikaru Preview?

**Hikaru Preview** is an automatically built version containing the latest commits from the `preview` branch.

- **Pros**: Access new features, player improvements, and bug fixes before official releases.
- **Cons**: Higher chance of encountering temporary bugs or crashes.

> [!TIP]
> If you run Preview builds, enable **Automatic Backups** under **More → Settings → Backup and restore** to safeguard your library database.

---

## Does Hikaru conflict with Aniyomi or Anikku?

No. Hikaru uses the package identifier `app.hikaru`. It installs and runs side-by-side with Aniyomi (`eu.kanade.tachiyomi`) and Anikku (`app.komikku.anikku`) without sharing data or interfering with their operation.

---

## What is a fork?

A fork is an independent copy of an open-source codebase developed with unique features or architectural changes. Hikaru is a specialized fork combining core foundations from Aniyomi and Anikku with deep MPV player enhancements, GLSL upscaling shaders, and dynamic theming.

---

## How do I customize the bottom navigation bar & tabs?

Hikaru allows complete customization of the main navigation layout:

1. Open **Hikaru** and navigate to **More → Settings → Appearance → Navigation** (or tap **Navigation settings**).
2. **Reorder Tabs**: Drag tabs up or down using the drag handle to change their order on the bottom bar.
3. **Show / Hide Tabs**: Toggle visibility for individual navigation items (**Library**, **Updates**, **History**, **Feed**, **Browse**, **More**).
4. **Label Visibility**: Choose whether tab labels are always visible, visible only when selected, or hidden.
5. **Auto-Hide on Scroll**: Toggle whether the navigation bar hides when scrolling down lists.
6. **Share Layout**: Tap **Copy layout string** to export your custom tab layout or paste a shared configuration code.

---

## How do I use the AI Assistant feature?

Hikaru includes an integrated AI Assistant for grounded app navigation and library queries:

1. Open **Hikaru** and navigate to **More → Settings → Advanced Analytics** (or **AI Assistant**).
2. **Enable AI Assistant**: Toggle the switch ON.
3. **API Key Setup**: Enter a valid Gemini or Groq API key.
4. **App Guidance & Navigation**: Ask the assistant for help with settings, extension configuration, or library statistics.
