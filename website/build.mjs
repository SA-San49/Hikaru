import { cp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { marked } from "marked";

const websiteDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(websiteDir, "..");
const outputDir = resolve(repoRoot, "_site");
const basePath = (process.env.SITE_BASE_PATH ?? "/Hikaru").replace(/\/$/, "");
const publicOrigin = (process.env.SITE_URL ?? "https://sa-san49.github.io").replace(/\/$/, "");
const canonicalRoot = `${publicOrigin}${basePath}`;

await rm(outputDir, { recursive: true, force: true });
await mkdir(resolve(outputDir, "assets"), { recursive: true });
await mkdir(resolve(outputDir, "privacy"), { recursive: true });

for (const name of ["index.html", "assets/site.css", "assets/site.js"]) {
  await cp(resolve(websiteDir, name), resolve(outputDir, name), { recursive: true });
}
await cp(resolve(repoRoot, ".github/assets/icon.png"), resolve(outputDir, "assets/hikaru-icon.png"));

const homepage = await readFile(resolve(outputDir, "index.html"), "utf8");
await writeFile(resolve(outputDir, "index.html"), homepage
  .replaceAll("{{CANONICAL_HOME}}", `${canonicalRoot}/`)
  .replaceAll("{{SOCIAL_IMAGE}}", `${canonicalRoot}/assets/hikaru-icon.png`));

let policyHtml;
try {
  const policyMarkdown = await readFile(resolve(repoRoot, "policy.md"), "utf8");
  policyHtml = marked.parse(policyMarkdown, { gfm: true, breaks: false });
  if (!policyHtml.trim()) throw new Error("policy.md is empty");
} catch (error) {
  if (error.code && error.code !== "ENOENT") throw error;
  policyHtml = `<div class="policy-missing" role="status"><strong>Policy source not found.</strong><p>The Privacy Policy page is generated directly from the repository-root <code>policy.md</code>. That file is not present yet, so approved policy text has not been published here.</p></div>`;
}

const privacyTemplate = await readFile(resolve(websiteDir, "privacy.html"), "utf8");
const privacyPage = privacyTemplate
  .replace("<!-- POLICY_CONTENT -->", policyHtml)
  .replaceAll("{{CANONICAL_PRIVACY}}", `${canonicalRoot}/privacy/`)
  .replaceAll("{{SOCIAL_IMAGE}}", `${canonicalRoot}/assets/hikaru-icon.png`);
await writeFile(resolve(outputDir, "privacy/index.html"), privacyPage);

console.log(`Built ${outputDir} (base path: ${basePath || "/"}; policy source: ${policyHtml.includes("policy-missing") ? "missing" : "policy.md"})`);
