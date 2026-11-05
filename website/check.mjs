import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const websiteDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(websiteDir, "..");
const outputDir = resolve(repoRoot, "_site");
const basePath = (process.env.SITE_BASE_PATH ?? "/Hikaru").replace(/\/$/, "");
const assetBase = basePath ? `${basePath}/` : "/";
const htmlFiles = ["index.html", "privacy/index.html"];
const pages = new Map();

const outputPathFor = (pathOnly, pageDir) => {
  if (pathOnly.startsWith(assetBase)) return resolve(outputDir, pathOnly.slice(assetBase.length));
  if (pathOnly.startsWith("/")) return resolve(outputDir, pathOnly.slice(1));
  return resolve(pageDir, pathOnly);
};

for (const relativePath of htmlFiles) {
  pages.set(relativePath, await readFile(resolve(outputDir, relativePath), "utf8"));
}

const home = pages.get("index.html");
const privacy = pages.get("privacy/index.html");
assert.match(home, /https:\/\/github\.com\/SA-San49\/Hikaru\/releases\/latest/);
assert.match(home, /https:\/\/github\.com\/SA-San49\/Hikaru\/releases(?:["/])/);
assert.match(home, /https:\/\/github\.com\/aniyomiorg\/aniyomi/);
assert.match(home, /https:\/\/github\.com\/komikku-app\/anikku/);
assert.match(home, /https:\/\/github\.com\/salmanbappi\/AniZen/);
assert.doesNotMatch(home, /github\.com\/SA-San49\/Hikaru\/releases\/download\//);
assert.doesNotMatch(home, /screens-image|hikaru-screens|REAL APP SCREENS/i);
assert.ok(home.includes(`href="${assetBase}assets/site.css"`), "Homepage must use the deployment-base stylesheet URL");
assert.ok(privacy.includes(`href="${assetBase}assets/site.css"`), "Privacy page must use the deployment-base stylesheet URL");
await assert.rejects(access(resolve(outputDir, "assets/hikaru-screens.png")));
assert.match(privacy, /Privacy Policy/);
assert.match(privacy, /<article class="policy-content[\s\S]*?<\/article>/);

const galleryImages = [...home.matchAll(/data-image="([^"]+)"/g)].map(([, path]) => path);
const galleryLabels = [...home.matchAll(/data-label="([^"]+)"/g)].map(([, label]) => label);
const galleryTitles = [...home.matchAll(/data-title="([^"]+)"/g)].map(([, title]) => title);
assert.deepEqual(galleryImages, ["library", "details", "schedule", "announcements", "assistant"].map((name) => `${assetBase}assets/gallery/${name}.webp`), "The carousel must follow Library, Details, Schedule, Announcements, Assistant order");
assert.deepEqual(galleryLabels, ["Library", "Anime Details and Episode Page", "Weekly Airing Schedule", "Announcements", "Diagnostic Assistant"]);
assert.deepEqual(galleryTitles, [
  "Your home for the anime you follow.",
  "Open a title. Find the episode.",
  "Your weekly airing schedule.",
  "Know when an anime is confirmed.",
  "An AI agent that works with your keys.",
]);
for (const reference of galleryImages) {
  await access(outputPathFor(reference, resolve(outputDir)));
}
const generatedScript = await readFile(resolve(outputDir, "assets/site.js"), "utf8");
assert.match(generatedScript, /setTimeout\(\(\) => showSlide\(currentIndex \+ 1, \{ automatic: true \}\), 3000\)/);
assert.match(generatedScript, /data-zoom-in/);

const policySource = await readFile(resolve(repoRoot, "policy.md"), "utf8").catch((error) => {
  if (error.code === "ENOENT") return null;
  throw error;
});
if (policySource === null) {
  assert.match(privacy, /Policy source not found/);
  console.warn("policy.md is absent; the published route will explicitly report that no approved policy text is available.");
} else {
  assert.ok(policySource.trim(), "policy.md must not be empty");
  assert.doesNotMatch(privacy, /Policy source not found/);
}

for (const [pagePath, html] of pages) {
  const pageDir = dirname(resolve(outputDir, pagePath));
  for (const [, reference] of html.matchAll(/\b(?:href|src)="([^"#]+)"/g)) {
    if (/^(?:https?:|mailto:|tel:|data:|javascript:)/i.test(reference)) continue;
    const [pathOnly] = reference.split(/[?#]/, 1);
    if (!pathOnly) continue;
    const destination = outputPathFor(pathOnly, pageDir);
    assert.ok(
      destination === outputDir || destination.startsWith(`${outputDir}${sep}`),
      `Internal reference escapes generated site: ${pagePath} -> ${reference}`,
    );
    let target = destination;
    try {
      await access(target);
    } catch {
      target = resolve(destination, "index.html");
      await access(target);
    }
  }
}

console.log("Website checks passed: generated home/privacy routes, release destinations, upstream credits, and local assets are valid.");
