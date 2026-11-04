# Hikaru website

A lightweight static website built with Node.js and `marked`. There is no client-side framework, backend, or runtime data fetch. The source lives under `website/`; the Android application, its build configuration, and its existing workflows are left alone.

## Build and check locally

```sh
cd website
npm ci
SITE_BASE_PATH=/Hikaru SITE_URL=https://sa-san49.github.io npm run build
npm test
```

The build writes the complete static site to the repository-root `_site/` directory. It uses the existing Hikaru app icon and screenshot strip from `.github/assets/icon.png` and `.github/readme-images/screens.png`; no application screenshots are fabricated. The `/privacy/` route is generated directly from repository-root `policy.md` on each build. When that source file is absent, the page clearly reports that approved policy text has not been published; no privacy claims are invented.

## GitHub Pages

`.github/workflows/pages.yml` builds and tests on pull requests and deploys on pushes to `main` or a manual workflow dispatch. The project-site base path is `/Hikaru`, and the workflow uses GitHub Pages Actions deployment.

The repository's Pages API currently reports the older **Deploy from a branch (`main` / `/`)** configuration. Before the Actions workflow can publish, switch **Settings → Pages → Build and deployment → Source** to **GitHub Actions**. The workflow does not alter any Android build or release workflows.

Target addresses once Pages deployment is enabled:

- Website: <https://SA-San49.github.io/Hikaru/>
- Privacy: <https://SA-San49.github.io/Hikaru/privacy/>
