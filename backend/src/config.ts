import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { readFileSync, existsSync } from "node:fs";

export const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
export const DATA_DIR = join(ROOT, "data");
export const TEMPLATES_DIR = join(DATA_DIR, "templates");
export const ASSETS_DIR = join(ROOT, "public", "assets");

// Tiny .env loader (avoids an extra dependency).
const envFile = join(ROOT, ".env");
if (existsSync(envFile)) {
  for (const line of readFileSync(envFile, "utf8").split("\n")) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
    if (m && m[2] && !process.env[m[1]]) process.env[m[1]] = m[2];
  }
}

export const PORT = Number(process.env.PORT ?? 4000);
export const FIGMA_FILE_KEY = process.env.FIGMA_FILE_KEY ?? "l2Nfg93ynvXv8OaHjMoumN";
