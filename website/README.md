# Hikaru website

A small static site built with Node.js and `marked`. No client-side framework or runtime data fetches are used.

## Build locally

```sh
npm ci
SITE_BASE_PATH= SITE_URL=https://sa-san49.github.io npm run build
```

The build writes the complete static site to `../_site`. It copies the original app icon and screenshot strip from `.github/assets/icon.png` and `.github/readme-images/screens.png`. The privacy route is generated from the repository-root `policy.md`; when that source file is missing, the page states that it has not been published rather than inventing policy text.

## GitHub Pages

`.github/workflows/pages.yml` builds from `main` and deploys the artifact with GitHub Pages Actions. The workflow uses `/Hikaru` as the project-site base path. If the repository's Pages source is still set to a branch, switch **Settings → Pages → Build and deployment → Source** to **GitHub Actions** before the first Actions deployment.
