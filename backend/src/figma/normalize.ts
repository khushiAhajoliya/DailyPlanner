import type { FigmaNode, FigmaPaint } from "./client.js";
import type { Template, TemplateElement, TextElement } from "../schema/template.js";
import { buildWritingSlots, detectCheckboxes } from "./slots.js";

/** An image node that must be rendered by Figma and saved as an asset. */
export interface ImageExport {
  nodeId: string;
  /**
   * Identity of the rendered pixels (image hash + size + rotation). Two layers with the same
   * key render identically, so one template can reuse another's asset. Vectors have none.
   */
  reuseKey: string | null;
  scale: number;
  fileName: string;
}

interface Rect { x: number; y: number; w: number; h: number }

const PAD = 4;
const r1 = (n: number) => Math.round(n * 10) / 10;

function solid(paints: FigmaPaint[] | undefined): { hex: string; opacity: number } | null {
  const p = paints?.find((f) => f.visible !== false && f.type === "SOLID" && f.color);
  if (!p || !p.color) return null;
  const h = (v: number) => Math.round(v * 255).toString(16).padStart(2, "0").toUpperCase();
  return { hex: `#${h(p.color.r)}${h(p.color.g)}${h(p.color.b)}`, opacity: (p.opacity ?? 1) * (p.color.a ?? 1) };
}

const hasImageFill = (n: FigmaNode) => !!n.fills?.some((f) => f.visible !== false && f.type === "IMAGE");

function slug(s: string) {
  return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "") || "node";
}

/** Clockwise rotation in degrees (Android canvas convention). */
function rotationDeg(n: FigmaNode): number {
  let deg = 0;
  if (typeof n.rotation === "number") deg = (n.rotation * 180) / Math.PI;
  else if (n.relativeTransform) deg = (Math.atan2(n.relativeTransform[1][0], n.relativeTransform[0][0]) * 180) / Math.PI;
  return Math.abs(deg) < 0.01 ? 0 : Math.round(deg * 100) / 100;
}

// ---------------------------------------------------------------------------
// Date / time detection so the app can open the right picker and keep the format.
// ---------------------------------------------------------------------------
const MONTHS = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december"];
const DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"];

function detectRole(text: string): Pick<TextElement, "role" | "format" | "prefix" | "uppercase"> {
  const t = text.trim();
  const time = /^(\d{1,2})(:\d{2})?\s?(AM|PM)$/i.exec(t);
  if (time) return { role: "time", format: time[2] ? "h:mm a" : "h a", prefix: "", uppercase: false };

  // "Date: JANUARY 5, 2026"  /  "January 5, 2026"
  const mdy = /^(.*?:\s*)?([A-Za-z]+) (\d{1,2}), (\d{4})$/.exec(t);
  if (mdy && MONTHS.includes(mdy[2].toLowerCase())) {
    const d = mdy[3].length === 2 ? "dd" : "d";
    return { role: "date", format: `MMMM ${d}, yyyy`, prefix: mdy[1] ?? "", uppercase: mdy[2] === mdy[2].toUpperCase() };
  }
  // "Friday, 01 June"
  const wdm = /^([A-Za-z]+), (\d{1,2}) ([A-Za-z]+)$/.exec(t);
  if (wdm && DAYS.includes(wdm[1].toLowerCase()) && MONTHS.includes(wdm[3].toLowerCase())) {
    const d = wdm[2].length === 2 ? "dd" : "d";
    return { role: "date", format: `EEEE, ${d} MMMM`, prefix: "", uppercase: wdm[1] === wdm[1].toUpperCase() };
  }
  return { role: "text", format: undefined, prefix: "", uppercase: false };
}

// ---------------------------------------------------------------------------
// Flatten the Figma tree into paint-ordered elements.
// ---------------------------------------------------------------------------
interface Flat {
  elements: TemplateElement[];
  vectors: { id: string; bounds: Rect; src: string }[];
  exports: ImageExport[];
}

function flatten(frame: FigmaNode, templateId: string): Flat {
  const ox = frame.absoluteBoundingBox!.x;
  const oy = frame.absoluteBoundingBox!.y;
  const out: Flat = { elements: [], vectors: [], exports: [] };

  const boundsOf = (n: FigmaNode): Rect => {
    const b = n.absoluteBoundingBox!;
    return { x: r1(b.x - ox), y: r1(b.y - oy), w: r1(b.width), h: r1(b.height) };
  };
  const strokeOf = (n: FigmaNode) => {
    const s = solid(n.strokes);
    if (!s || !n.strokeWeight) return null;
    return { color: s.hex, width: n.strokeWeight, align: n.strokeAlign ?? "INSIDE" };
  };
  const asset = (n: FigmaNode, scale: number) => {
    const fileName = `${templateId}/${slug(n.name)}-${n.id.replace(/[^0-9]+/g, "_")}.png`;
    const img = n.fills?.find((f) => f.visible !== false && f.type === "IMAGE") as (FigmaPaint & { imageRef?: string; scaleMode?: string }) | undefined;
    const b = n.absoluteBoundingBox!;
    const reuseKey = img?.imageRef
      ? [img.imageRef, img.scaleMode, Math.round(b.width / b.height * 100), Math.round((n.rotation ?? 0) * 1000)].join("|")
      : null;
    out.exports.push({ nodeId: n.id, reuseKey, scale, fileName });
    return `/assets/${fileName}`;
  };

  const visit = (n: FigmaNode, isRoot = false) => {
    if (n.visible === false) return;
    const b = n.absoluteBoundingBox ? boundsOf(n) : null;

    switch (n.type) {
      case "TEXT": {
        const st = n.style!;
        const fill = solid(n.fills);
        const rot = rotationDeg(n);
        // Store the un-rotated box, centered where Figma draws the rotated one.
        let rect = b!;
        if (Math.abs(Math.abs(rot) - 90) < 1) {
          const cx = rect.x + rect.w / 2, cy = rect.y + rect.h / 2;
          rect = { x: r1(cx - rect.h / 2), y: r1(cy - rect.w / 2), w: rect.h, h: rect.w };
        }
        const runs: TextElement["runs"] = [];
        const ov = n.characterStyleOverrides ?? [];
        for (let i = 0; i < ov.length; ) {
          const key = ov[i];
          let j = i;
          while (j < ov.length && ov[j] === key) j++;
          const o = key ? n.styleOverrideTable?.[String(key)] : undefined;
          if (o) runs.push({ start: i, end: j, fontWeight: o.fontWeight, fontFamily: o.fontFamily, italic: o.italic });
          i = j;
        }
        const lineTypes = n.lineTypes ?? [];
        const list = lineTypes.includes("ORDERED") ? "ordered" : lineTypes.includes("UNORDERED") ? "unordered" : "none";
        const text = n.characters ?? "";
        out.elements.push({
          type: "text",
          id: n.id,
          name: n.name,
          text,
          ...rect,
          box: rect, // refined later by computeTextBoxes
          maxLines: 1,
          style: {
            fontFamily: st.fontFamily,
            fontWeight: st.fontWeight,
            italic: !!st.italic,
            fontSize: st.fontSize,
            lineHeight: r1(st.lineHeightPx),
            letterSpacing: st.letterSpacing ?? 0,
            color: fill?.hex ?? "#000000",
            align: st.textAlignHorizontal === "JUSTIFIED" ? "LEFT" : st.textAlignHorizontal,
          },
          runs,
          list,
          listStart: 1,
          rotation: Math.abs(Math.abs(rot) - 90) < 1 ? rot : 0,
          ...detectRole(text),
          editable: true,
          slot: false,
        });
        return;
      }
      case "LINE": {
        const s = strokeOf(n);
        if (!s) return;
        const vertical = b!.w < 0.5;
        out.elements.push({
          type: "line",
          id: n.id,
          x1: b!.x,
          y1: b!.y,
          x2: vertical ? b!.x : r1(b!.x + b!.w),
          y2: vertical ? r1(b!.y + b!.h) : b!.y,
          color: s.color,
          width: s.width,
        });
        return;
      }
      case "VECTOR": {
        // Vectors (e.g. check marks) are rendered by Figma to keep them pixel-identical.
        // Placed as an image; buildCheckboxes() removes it again if it turns out to be a check mark.
        const src = asset(n, 3);
        out.vectors.push({ id: n.id, bounds: b!, src });
        out.elements.push({ type: "image", id: n.id, name: n.name, ...b!, src, opacity: n.opacity ?? 1 });
        return;
      }
      case "RECTANGLE":
      case "ELLIPSE": {
        if (hasImageFill(n)) {
          const big = b!.w * b!.h > 500_000; // full-page background art
          out.elements.push({ type: "image", id: n.id, name: n.name, ...b!, src: asset(n, big ? 1 : 2), opacity: n.opacity ?? 1 });
          return;
        }
        const fill = solid(n.fills);
        out.elements.push({
          type: n.type === "ELLIPSE" ? "ellipse" : "rect",
          id: n.id,
          ...b!,
          fill: fill?.hex ?? null,
          fillOpacity: fill?.opacity ?? 1,
          stroke: strokeOf(n),
          radius: n.cornerRadius ?? 0,
        });
        return;
      }
      default: {
        // FRAME / GROUP / COMPONENT / INSTANCE: frames can paint their own fill + stroke.
        if (!isRoot && (n.type === "FRAME" || n.type === "COMPONENT" || n.type === "INSTANCE")) {
          const fill = solid(n.fills);
          const stroke = strokeOf(n);
          if (fill || stroke) {
            out.elements.push({
              type: "rect",
              id: n.id,
              ...b!,
              fill: fill?.hex ?? null,
              fillOpacity: fill?.opacity ?? 1,
              stroke,
              radius: n.cornerRadius ?? 0,
            });
          }
        }
        n.children?.forEach((c) => visit(c));
      }
    }
  };

  visit(frame, true);
  return out;
}

// ---------------------------------------------------------------------------
// Check marks sitting inside circles become toggleable checkboxes. Empty circles
// with the same look in the same column become unchecked checkboxes.
// ---------------------------------------------------------------------------
function buildCheckboxes(flat: Flat): TemplateElement[] {
  const els = flat.elements;
  const center = (r: Rect) => ({ x: r.x + r.w / 2, y: r.y + r.h / 2 });
  const inside = (p: { x: number; y: number }, r: Rect) => p.x >= r.x && p.x <= r.x + r.w && p.y >= r.y && p.y <= r.y + r.h;

  const checked = new Map<string, { src: string; dx: number; dy: number; w: number; h: number }>();
  for (const v of flat.vectors) {
    const host = els.find((e) => e.type === "ellipse" && inside(center(v.bounds), e));
    if (host && host.type === "ellipse") {
      checked.set(host.id, { src: v.src, dx: r1(v.bounds.x - host.x), dy: r1(v.bounds.y - host.y), w: v.bounds.w, h: v.bounds.h });
    }
  }
  const texts = els.filter((e): e is TextElement => e.type === "text");

  // Check-mark vectors are drawn by their checkbox, not as free images.
  const consumed = new Set(flat.vectors.filter((v) => [...checked.values()].some((c) => c.src === v.src)).map((v) => v.id));
  els.splice(0, els.length, ...els.filter((e) => !(e.type === "image" && consumed.has(e.id))));

  if (checked.size === 0) return els;

  const protos = els.filter((e) => e.type === "ellipse" && checked.has(e.id));
  const sameLook = (a: TemplateElement, b: TemplateElement) =>
    a.type === "ellipse" && b.type === "ellipse" &&
    Math.abs(a.x - b.x) < 1 && Math.abs(a.w - b.w) < 1 && Math.abs(a.h - b.h) < 1 &&
    a.fill === b.fill && a.stroke?.color === b.stroke?.color;

  return els.map((e) => {
    if (e.type !== "ellipse") return e;
    const own = checked.get(e.id);
    const proto = own ? null : protos.find((p) => sameLook(p, e));
    const hasText = texts.some((t) => inside(center(t), e));
    if (!own && (!proto || hasText)) return e;
    const check = own ?? checked.get(proto!.id)!;
    return {
      type: "checkbox" as const,
      id: e.id,
      x: e.x, y: e.y, w: e.w, h: e.h,
      fill: e.fill,
      stroke: e.stroke,
      checked: !!own,
      shape: "ellipse" as const,
      radius: 0,
      check,
    };
  });
}

// ---------------------------------------------------------------------------
// Writable area for every text: grow the box to the nearest line, cell border,
// container edge or neighbouring content so user text stays inside the design.
// ---------------------------------------------------------------------------
function computeTextBoxes(els: TemplateElement[], pageW: number, pageH: number): TemplateElement[] {
  const shapes = els.filter((e) => e.type === "rect" || e.type === "ellipse" || e.type === "checkbox") as (Rect & { id: string; stroke: { width: number; align: string } | null })[];
  const lines = els.filter((e) => e.type === "line") as Extract<TemplateElement, { type: "line" }>[];
  const texts = els.filter((e): e is TextElement => e.type === "text");

  return els.map((e) => {
    if (e.type !== "text" || e.rotation !== 0) return e;
    const t = e;
    const tx1 = t.x, ty1 = t.y, tx2 = t.x + t.w, ty2 = t.y + t.h;
    const cx = t.x + t.w / 2, cy = t.y + t.h / 2;
    const lh = t.style.lineHeight;

    let L = 16, R = pageW - 16, B = pageH - 16;

    const containers = shapes
      .filter((s) => cx >= s.x && cx <= s.x + s.w && cy >= s.y && cy <= s.y + s.h)
      .sort((a, b) => a.w * a.h - b.w * b.h);
    const container = containers[0];
    if (container) {
      const inset = (container.stroke?.align === "INSIDE" ? container.stroke.width : 0) + PAD;
      L = Math.max(L, container.x + inset);
      R = Math.min(R, container.x + container.w - inset);
      B = Math.min(B, container.y + container.h - inset);
    }

    const overlapsRow = (y1: number, y2: number) => y1 < ty2 - 2 && y2 > ty1 + 2;
    const obstacles: Rect[] = [
      ...shapes.filter((s) => !containers.includes(s)),
      ...texts.filter((o) => o.id !== t.id),
    ];
    for (const o of obstacles) {
      if (!overlapsRow(o.y, o.y + o.h)) continue;
      if (o.x >= tx2 - 2) R = Math.min(R, o.x - PAD);
      else if (o.x + o.w <= tx1 + 2) L = Math.max(L, o.x + o.w + PAD);
    }
    for (const l of lines) {
      if (l.x1 === l.x2) {
        if (!overlapsRow(Math.min(l.y1, l.y2), Math.max(l.y1, l.y2))) continue;
        if (l.x1 >= tx2 - 2) R = Math.min(R, l.x1 - l.width / 2 - PAD);
        else if (l.x1 <= tx1 + 2) L = Math.max(L, l.x1 + l.width / 2 + PAD);
      } else if (l.y1 > ty1 && l.y1 <= ty1 + 2.2 * lh && l.x1 <= tx1 + 2 && l.x2 >= tx1 + 2) {
        // Row underline: the cell ends where the line ends.
        R = Math.min(R, l.x2);
        L = Math.max(L, l.x1);
      }
    }
    // Bottom: first thing below the first line inside the horizontal span.
    const below = (y: number, x1: number, x2: number) => y >= ty1 + lh * 0.5 && x1 < R && x2 > L;
    for (const o of obstacles) if (below(o.y, o.x, o.x + o.w)) B = Math.min(B, o.y - PAD);
    for (const l of lines) {
      if (l.y1 === l.y2 && below(l.y1, l.x1, l.x2)) B = Math.min(B, l.y1 - l.width / 2 - PAD);
    }

    // Left-aligned texts that share their x with other left-aligned texts form a column
    // (schedule rows, list items) and must stay left-aligned when edited.
    const inLeftColumn = t.style.align === "LEFT" &&
      texts.filter((o) => o.id !== t.id && o.style.align === "LEFT" && Math.abs(o.x - t.x) < 1).length >= 2;
    const centeredInContainer = !inLeftColumn && container && Math.abs(cx - (container.x + container.w / 2)) <= 12;
    const centeredOnPage = !container && Math.abs(cx - pageW / 2) <= 12;
    let box: Rect;
    let align = t.style.align;
    if (t.list === "none" && (align === "CENTER" || centeredInContainer || centeredOnPage)) {
      const half = Math.max(t.w / 2, Math.min(cx - L, R - cx));
      box = { x: cx - half, y: ty1, w: half * 2, h: 0 };
      align = "CENTER";
    } else if (align === "RIGHT") {
      const left = Math.min(L, tx1);
      box = { x: left, y: ty1, w: tx2 - left, h: 0 };
    } else {
      box = { x: tx1, y: ty1, w: Math.max(t.w, R - tx1), h: 0 };
    }
    box.h = Math.max(t.h, B - ty1);
    const origLines = Math.max(1, Math.round(t.h / lh));
    const maxLines = Math.max(origLines, Math.floor((box.h + 0.5) / lh));

    return {
      ...t,
      box: { x: r1(box.x), y: r1(box.y), w: r1(box.w), h: r1(box.h) },
      maxLines,
      style: { ...t.style, align },
    };
  });
}

/** Ordered-list rows stacked in one column continue numbering: 1., 2., 3. */
function numberOrderedColumns(els: TemplateElement[]): TemplateElement[] {
  const ordered = els.filter((e): e is TextElement => e.type === "text" && e.list === "ordered");
  const start = new Map<string, number>();
  const columns = new Map<number, TextElement[]>();
  for (const t of ordered) columns.set(Math.round(t.x), [...(columns.get(Math.round(t.x)) ?? []), t]);
  for (const col of columns.values()) {
    let n = 1;
    for (const t of col.sort((a, b) => a.y - b.y)) {
      start.set(t.id, n);
      n += t.text.split("\n").length;
    }
  }
  return els.map((e) => (e.type === "text" && start.has(e.id) ? { ...e, listStart: start.get(e.id)! } : e));
}

// ---------------------------------------------------------------------------

export interface NormalizeInput {
  frame: FigmaNode;
  id: string;
  name: string;
  category: string;
  fileKey: string;
  palette: { name: string; hex: string }[];
  fonts: string[];
}

export function normalizeFrame(input: NormalizeInput): { template: Template; exports: ImageExport[] } {
  const { frame } = input;
  const b = frame.absoluteBoundingBox!;
  const flat = flatten(frame, input.id);
  let elements = buildCheckboxes(flat);
  elements = computeTextBoxes(elements, b.width, b.height);
  elements = numberOrderedColumns(elements);
  elements = detectCheckboxes(elements);
  elements = buildWritingSlots(elements);

  const texts = elements.filter((e): e is TextElement => e.type === "text");
  const designFonts = [...new Set(texts.flatMap((t) => [t.style.fontFamily, ...t.runs.map((r) => r.fontFamily).filter(Boolean) as string[]]))];
  const designColors = [
    ...new Set(
      elements.flatMap((e) =>
        e.type === "text" ? [e.style.color] : e.type === "line" ? [e.color] : e.type === "rect" && e.stroke ? [e.stroke.color] : [],
      ),
    ),
  ].map((hex, i) => ({ name: `Design ${i + 1}`, hex }));

  const seen = new Set<string>();
  const colors = [...designColors, ...input.palette].filter((c) => !seen.has(c.hex) && seen.add(c.hex));

  const template: Template = {
    id: input.id,
    name: input.name,
    category: input.category,
    version: 1,
    source: { fileKey: input.fileKey, nodeId: frame.id, syncedAt: new Date().toISOString() },
    page: {
      width: b.width,
      height: b.height,
      background: solid(frame.fills)?.hex ?? "#FFFFFF",
      paper: { name: "A4", widthPt: 595, heightPt: 842 },
    },
    editorOptions: { colors, fonts: [...new Set([...designFonts, ...input.fonts])] },
    elements,
  };
  return { template, exports: flat.exports };
}
