# Browse & Extensions FAQ

Frequently asked questions regarding extension repositories, Cloudflare verification, local storage, DNS configuration, and feed customization.

---

## How do I add extension repositories?

1. Go to **Browse → Extensions → ⋮ (Overflow menu) → Extension Repositories** (or **Settings → Browse → Extension Repositories**).
2. Tap **Add repository** (+).
3. Paste the HTTPS repository URL (ending in `/index.min.json` or base URL).
4. Tap **Add**. Installed extensions appear in **Browse → Extensions**.

---

## What should I do if Cloudflare protection blocks a source?

1. Open the affected source in **Browse**.
2. Tap **WebView** in the top toolbar.
3. Complete the CAPTCHA or verification challenge in WebView.
4. Return to Hikaru. Cloudflare session cookies are saved to the app `CookieJar` automatically.

---

## Can I play local video files stored on my device?

Yes.

1. Create an `Hikaru/localanime/` directory in primary storage.
2. Store files inside folders named after the series title:
   ```
   Hikaru/localanime/
   └── Series Name/
       ├── Episode 01.mp4
       └── Episode 02.mkv
   ```
3. Open **Browse → Anime → Local anime source** to access local files.

---

## Why does global search take longer on certain sources?

Global search queries all enabled extensions concurrently. Responses are limited by the slowest external source endpoint.

> [!TIP]
> Disable unused extensions under **Browse → Extensions** to reduce global search query times.

---

## How do I configure DNS-over-HTTPS (DoH) & Custom User-Agent?

If network providers block extension domains:

1. Open **Settings → Advanced → Network**.
2. Select **DNS-over-HTTPS (DoH)** and pick a provider:
   - **Cloudflare** (`1.1.1.1`)
   - **Google** (`8.8.8.8`)
   - **AdGuard**
   - **NextDNS**
   - **Quad9**
3. Set **Custom User-Agent** if an endpoint requires specific browser header identification.

---

## How do I use the Feed feature?

1. **Pin a Search**: Open a source in **Browse**, enter a query or filter, and tap **Save search** / **Pin to Feed**.
2. **Access Feeds**: Open the **Feed** tab (or enable **Settings → Appearance → Show Feed in Browse**).
3. **Organize**: Reorder or group pinned searches into Feed categories.
4. **Updates**: Pinned rows fetch new uploads directly from sources on refresh.
