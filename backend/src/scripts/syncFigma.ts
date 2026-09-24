/**
 * Figma -> Template JSON pipeline.
 *   1. Fetch the frames listed in data/figma-sources.json from the Figma REST API
 *   2. Normalize nodes into the template schema (shapes, lines, texts, images, checkboxes)
 *   3. Export image layers (backgrounds, stickers, check marks) as PNG assets
 *   4. Validate with zod and write data/templates/<id>.json
 */
import { copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { ASSETS_DIR, DATA_DIR, FIGMA_FILE_KEY, TEMPLATES_DIR } from "../config.js";
import { exportImages, fetchNodes, loadFigmaToken, type FigmaNode } from "../figma/client.js";
import { normalizeFrame } from "../figma/normalize.js";
import { TemplateSchema } from "../schema/template.js";

interface Source { id: string; fileKey?: string; nodeId: string; name: string; category: string }

async function main() {
  const args = process.argv.slice(2);
  // --offline re-normalizes the last Figma response without calling the API.
  const offline = args.includes("--offline");
  const token = offline ? "" : loadFigmaToken();
  const sources: Source[] = JSON.parse(readFileSync(join(DATA_DIR, "figma-sources.json"), "utf8"));
  const options = JSON.parse(readFileSync(join(DATA_DIR, "editor-options.json"), "utf8"));
  // Figma's image render endpoint is heavily rate limited: reuse downloaded assets unless forced.
  const forceAssets = args.includes("--force-assets");
  const only = args.filter((a) => !a.startsWith("--"));
  const selected = only.length ? sources.filter((s) => only.includes(s.id)) : sources;

  // Templates can come from several Figma files; the cache is keyed "<fileKey>:<nodeId>".
  const keyOf = (s: Source) => s.fileKey ?? FIGMA_FILE_KEY;
  const cacheFile = join(DATA_DIR, "figma-cache", "nodes.json");
  const cache: Record<string, FigmaNode> = existsSync(cacheFile) ? JSON.parse(readFileSync(cacheFile, "utf8")) : {};
  const nodes: Record<string, FigmaNode> = {};
  for (const src of selected) {
    const legacy = keyOf(src) === FIGMA_FILE_KEY ? cache[src.nodeId] : undefined;
    const hit = cache[`${keyOf(src)}:${src.nodeId}`] ?? legacy;
    if (hit) nodes[`${keyOf(src)}:${src.nodeId}`] = hit;
  }
  if (offline) {
    console.log("Offline: using data/figma-cache/nodes.json");
  } else {
    for (const fileKey of new Set(selected.map(keyOf))) {
      const ids = selected.filter((s) => keyOf(s) === fileKey).map((s) => s.nodeId);
      console.log(`Fetching ${ids.length} frame(s) from Figma file ${fileKey}...`);
      const fetched = await fetchNodes(fileKey, ids, token);
      for (const [id, n] of Object.entries(fetched)) nodes[`${fileKey}:${id}`] = n;
    }
    mkdirSync(dirname(cacheFile), { recursive: true });
    writeFileSync(cacheFile, JSON.stringify({ ...cache, ...nodes }));
  }

  // Phase 1: normalize every selected frame.
  const built = selected.map((src) => {
    const frame = nodes[`${keyOf(src)}:${src.nodeId}`];
    if (!frame) throw new Error(`Node ${src.nodeId} (${src.name}) not found — run without --offline`);
    return {
      src,
      ...normalizeFrame({
        frame,
        id: src.id,
        name: src.name,
        category: src.category,
        fileKey: keyOf(src),
        palette: options.palette,
        fonts: options.fonts,
      }),
    };
  });

  // Phase 2: assets. Figma's render endpoint has a tiny quota on Starter plans, so an image
  // layer identical to one already rendered (same image hash, shape and rotation) is copied.
  const rendered = new Map<string, string>();
  for (const b of built) for (const e of b.exports) {
    const file = join(ASSETS_DIR, e.fileName);
    if (e.reuseKey && existsSync(file)) rendered.set(e.reuseKey, file);
  }
  for (const b of built) {
    for (const e of b.exports) {
      const out = join(ASSETS_DIR, e.fileName);
      if (forceAssets || existsSync(out) || !e.reuseKey || !rendered.has(e.reuseKey)) continue;
      mkdirSync(dirname(out), { recursive: true });
      copyFileSync(rendered.get(e.reuseKey)!, out);
      console.log(`  reused ${rendered.get(e.reuseKey)!.split("/").slice(-2).join("/")} for ${e.fileName}`);
    }
  }
  // One render call per (file, scale) across all templates.
  const pending = built.flatMap((b) => b.exports.filter((e) => forceAssets || !existsSync(join(ASSETS_DIR, e.fileName))).map((e) => ({ ...e, fileKey: keyOf(b.src) })));
  if (pending.length && offline) throw new Error(`Offline sync is missing ${pending.length} asset(s); run without --offline`);
  const groups = new Map<string, typeof pending>();
  for (const e of pending) groups.set(`${e.fileKey}@${e.scale}`, [...(groups.get(`${e.fileKey}@${e.scale}`) ?? []), e]);
  for (const list of groups.values()) {
    const urls = await exportImages(list[0].fileKey, list.map((e) => e.nodeId), list[0].scale, token);
    for (const e of list) {
      const res = await fetch(urls[e.nodeId]);
      if (!res.ok) throw new Error(`Download failed for ${e.nodeId}: ${res.status}`);
      const out = join(ASSETS_DIR, e.fileName);
      mkdirSync(dirname(out), { recursive: true });
      writeFileSync(out, Buffer.from(await res.arrayBuffer()));
    }
    console.log(`  rendered ${list.length} asset(s) from ${list[0].fileKey} @${list[0].scale}x`);
  }

  // Phase 3: validate and write.
  for (const { src, template, exports } of built) {
    const valid = TemplateSchema.parse(template);
    mkdirSync(TEMPLATES_DIR, { recursive: true });
    writeFileSync(join(TEMPLATES_DIR, `${src.id}.json`), JSON.stringify(valid, null, 2));
    const counts = valid.elements.reduce<Record<string, number>>((m, e) => ((m[e.type] = (m[e.type] ?? 0) + 1), m), {});
    console.log(`  ✓ ${src.name} (${src.nodeId}) -> templates/${src.id}.json`, counts, `${exports.length} asset(s)`);
  }
}

main().catch((err) => {
  console.error("Sync failed:", err.message);
  process.exit(1);
});
