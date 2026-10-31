# Wiki Style Guide

Formatting and writing standards for the Hikaru wiki.

> [!NOTE]
> These rules apply exclusively to pages within the Wiki (`.github/wiki/` and the GitHub Wiki repository). They do not affect root project documentation, source code comments, or external repository files.

---

## Page Structure & Hierarchy

- **Title (H1)**: Every page must start with a single `# Title`.
- **Tagline Sub-description**: Immediately below the H1 title, include a single concise sentence describing the page's purpose.
- **Dividers (`---`)**: Place a `---` horizontal rule after the tagline sub-description and between major H2 sections.
- **Heading Order**:
  - `## H2`: Major topic divisions on the page.
  - `### H3`: Sub-topics within an H2 section.
  - Never skip heading levels (e.g. do not jump from H1 directly to H3).

```markdown
# Page title

Single concise sentence explaining what this page covers.

---

## Major section title

### Sub-section title
```

---

## Voice, Tone & UI Formatting

- **Voice & Tone**: Direct, concise, technical, and neutral.
- **Action Verbs**: Use direct imperative verbs for step-by-step instructions (*"Open Hikaru"*, *"Navigate to Settings"*, *"Select the APK"*).
- **UI Elements**: **Bold** all UI labels, button names, tab titles, and screen names.
- **Navigation Paths**: Format in-app navigation paths with bold text and right arrows (`→`):
  - **More → Settings → Advanced**
  - **Browse → Extension Repositories**
  - **Library → Filter → Display**

---

## Step-by-Step Instructions

- Use numbered lists (`1.`, `2.`, `3.`) for procedures, installation guides, and setup workflows.
- Keep each step single-sentence or focused on a single actionable step.

```markdown
1. Open **Hikaru** and navigate to **More → Settings → Browse**.
2. Select **Extension Repositories** from the top menu.
3. Paste your repository URL and tap **Add**.
```

---

## Callouts & Admonitions

Use standard GitHub blockquote callouts to highlight important information:

- `> [!NOTE]` — General background context, package IDs, or default application behaviors.
- `> [!TIP]` — Efficiency tips, shortcuts, or recommended user workflows.
- `> [!WARNING]` — High GPU usage, battery consumption, or resource-heavy settings.
- `> [!IMPORTANT]` — Mandatory prerequisites or essential setup instructions.
- `> [!CAUTION]` — Critical warnings regarding potential data loss or app crashes.

---

## Title Search Examples

When providing title search examples or spelling variations, format them using blockquotes with bold keywords:

```markdown
> Example: **Boku no Hero Academia** instead of **My Hero Academia**.
```

---

## Tables & Specifications

Use clean Markdown tables for presenting option comparisons, performance profiles, filter presets, or hardware requirements:

```markdown
| Preset | Sliders | Recommended Use |
|:---|:---|:---|
| **Vivid Anime** | Contrast +5, Saturation +20 | Enhances line art and color vibrance |
| **Cinema** | Brightness -5, Contrast +15 | Darker, moody cinematic atmosphere |
```

---

## Scope & Isolation

- **Wiki Scope Only**: These guidelines strictly govern `.github/wiki/*.md` and `Hikaru.wiki.git`.
- **Non-Interference**: Do not apply or enforce these rules to `README.md`, code comments, pull request titles, or commit messages outside the wiki folder.
