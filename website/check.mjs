import assert from "node:assert/strict";
import { access, readFile } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const websiteDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(websiteDir, "..");
const outputDir = resolve(repoRoot, "_site");
const htmlFiles = ["index.html", "privacy/index.html"];
const pages = new Map();

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
assert.match(privacy, /Privacy Policy/);
assert.match(privacy, /<article class="policy-content[\s\S]*?<\/article>/);

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
    const destination = resolve(pageDir, pathOnly);
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
