import { readFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const API = "https://api.figma.com/v1";

export function loadFigmaToken(): string {
  const fromEnv = process.env.FIGMA_TOKEN?.trim();
  if (fromEnv) return fromEnv;
  for (const p of [join(process.cwd(), ".figma_token"), join(homedir(), ".figma_token")]) {
    if (existsSync(p)) {
      const t = readFileSync(p, "utf8").trim();
      if (t) return t;
    }
  }
  throw new Error("No Figma token. Set FIGMA_TOKEN in backend/.env or save it to ~/.figma_token");
}

async function figmaGet<T>(path: string, token: string, attempts = 6): Promise<T> {
  let lastErr: unknown;
  for (let i = 0; i < attempts; i++) {
    const res = await fetch(`${API}${path}`, { headers: { "X-Figma-Token": token } });
    if (res.ok) return (await res.json()) as T;
    // Figma intermittently returns 500, and 429 when rate limited; honour Retry-After.
    lastErr = new Error(`Figma ${res.status} for ${path}: ${await res.text()}`);
    if (res.status !== 429 && res.status < 500) break;
    const retryAfter = Number(res.headers.get("retry-after"));
    if (res.status === 429 && retryAfter > 300) {
      const h = (retryAfter / 3600).toFixed(1);
      throw new Error(`Figma rate limit reached (${res.headers.get("x-figma-plan-tier")} plan). Try again in ~${h} h, or use another account's token.`);
    }
    const waitMs = Number.isFinite(retryAfter) && retryAfter > 0 ? Math.min(retryAfter, 120) * 1000 : 5000 * (i + 1);
    console.log(`  Figma ${res.status}, retrying in ${Math.round(waitMs / 1000)}s...`);
    await new Promise((r) => setTimeout(r, waitMs));
  }
  throw lastErr;
}

// Minimal typing of the Figma REST node shape we rely on.
export interface FigmaColor { r: number; g: number; b: number; a: number }
export interface FigmaPaint { type: string; visible?: boolean; opacity?: number; color?: FigmaColor }
export interface FigmaTypeStyle {
  fontFamily: string;
  fontWeight: number;
  italic?: boolean;
  fontSize: number;
  lineHeightPx: number;
  letterSpacing: number;
  textAlignHorizontal: "LEFT" | "CENTER" | "RIGHT" | "JUSTIFIED";
  textCase?: string;
}
export interface FigmaNode {
  id: string;
  name: string;
  type: string;
  visible?: boolean;
  children?: FigmaNode[];
  absoluteBoundingBox?: { x: number; y: number; width: number; height: number };
  relativeTransform?: number[][];
  /** Radians; the REST API reports visual counter-clockwise turns as negative values. */
  rotation?: number;
  fills?: FigmaPaint[];
  strokes?: FigmaPaint[];
  strokeWeight?: number;
  strokeAlign?: "INSIDE" | "CENTER" | "OUTSIDE";
  cornerRadius?: number;
  opacity?: number;
  clipsContent?: boolean;
  characters?: string;
  style?: FigmaTypeStyle;
  characterStyleOverrides?: number[];
  styleOverrideTable?: Record<string, Partial<FigmaTypeStyle>>;
  lineTypes?: ("NONE" | "ORDERED" | "UNORDERED")[];
}

export async function fetchNodes(fileKey: string, ids: string[], token: string): Promise<Record<string, FigmaNode>> {
  const data = await figmaGet<{ nodes: Record<string, { document: FigmaNode }> }>(
    `/files/${fileKey}/nodes?ids=${encodeURIComponent(ids.join(","))}`,
    token,
  );
  return Object.fromEntries(Object.entries(data.nodes).map(([k, v]) => [k, v.document]));
}

export async function exportImages(
  fileKey: string,
  ids: string[],
  scale: number,
  token: string,
): Promise<Record<string, string>> {
  if (ids.length === 0) return {};
  const data = await figmaGet<{ err: string | null; images: Record<string, string | null> }>(
    `/images/${fileKey}?ids=${encodeURIComponent(ids.join(","))}&format=png&scale=${scale}&use_absolute_bounds=true`,
    token,
  );
  if (data.err) throw new Error(`Figma image export failed: ${data.err}`);
  const out: Record<string, string> = {};
  for (const [id, url] of Object.entries(data.images)) {
    if (!url) throw new Error(`Figma returned no image for node ${id}`);
    out[id] = url;
  }
  return out;
}
