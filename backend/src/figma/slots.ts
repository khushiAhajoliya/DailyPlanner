import type { TemplateElement, TextElement } from "../schema/template.js";

/**
 * Blank planner templates are mostly ruled lines, empty boxes and empty checkboxes.
 * This turns them into things the user can write on / tick, like a paper planner.
 */

interface Rect { x: number; y: number; w: number; h: number }
type Shape = Extract<TemplateElement, { type: "rect" | "ellipse" | "checkbox" }>;
type Line = Extract<TemplateElement, { type: "line" }>;

const r1 = (n: number) => Math.round(n * 10) / 10;
const cx = (r: Rect) => r.x + r.w / 2;
const cy = (r: Rect) => r.y + r.h / 2;
const inside = (x: number, y: number, r: Rect) => x >= r.x && x <= r.x + r.w && y >= r.y && y <= r.y + r.h;
const overlapArea = (a: Rect, b: Rect) =>
  Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)) * Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y));

/** Repeating small empty circles / squares with an outline are checkboxes. */
export function detectCheckboxes(els: TemplateElement[]): TemplateElement[] {
  const texts = els.filter((e): e is TextElement => e.type === "text");
  const small = els.filter((e): e is Extract<TemplateElement, { type: "rect" | "ellipse" }> =>
    (e.type === "ellipse" || e.type === "rect") && !!e.stroke && e.w <= 100 && e.h <= 100 && e.w >= 16 &&
    Math.abs(e.w - e.h) <= 0.2 * e.w && !texts.some((t) => inside(cx(t), cy(t), e)));
  const groups = new Map<string, typeof small>();
  for (const e of small) {
    const k = `${e.type}|${Math.round(e.w)}|${Math.round(e.h)}`;
    groups.set(k, [...(groups.get(k) ?? []), e]);
  }
  const ids = new Set([...groups.values()].filter((g) => g.length >= 3).flat().map((e) => e.id));
  return els.map((e) =>
    (e.type === "ellipse" || e.type === "rect") && ids.has(e.id)
      ? {
          type: "checkbox" as const,
          id: e.id, x: e.x, y: e.y, w: e.w, h: e.h,
          fill: e.fill, stroke: e.stroke, checked: false,
          shape: e.type, radius: e.radius,
          // Same proportions as the Frame 2 check mark; empty src = tick drawn by the app.
          check: { src: "", dx: r1(e.w * 0.24), dy: r1(e.h * 0.31), w: r1(e.w * 0.53), h: r1(e.h * 0.37) },
        }
      : e,
  );
}

interface BodyStyle { fontFamily: string; fontWeight: number; fontSize: number; color: string }

function bodyStyle(texts: TextElement[]): BodyStyle {
  const count = new Map<string, number>();
  for (const t of texts) {
    const k = JSON.stringify([t.style.fontFamily, t.style.fontWeight, t.style.fontSize, t.style.color]);
    count.set(k, (count.get(k) ?? 0) + 1);
  }
  const best = [...count.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
  const [fontFamily, fontWeight, fontSize, color] = best ? JSON.parse(best) : ["Inter", 400, 32, "#000000"];
  return { fontFamily, fontWeight, fontSize, color };
}

const isLabel = (t: TextElement) => t.role === "time" || /:\s*$/.test(t.text.trim());

function slot(id: string, box: Rect, style: BodyStyle, lines: number, label?: TextElement): TextElement {
  const size = style.fontSize;
  const isDate = !!label && /^date\s*:?$/i.test(label.text.trim());
  return {
    type: "text",
    id,
    name: label ? `${label.text.trim()} …` : "Write here",
    text: "",
    ...box,
    box,
    maxLines: lines,
    style: {
      fontFamily: style.fontFamily,
      fontWeight: Math.min(style.fontWeight, 500),
      italic: false,
      fontSize: r1(size),
      lineHeight: r1(size * 1.25),
      letterSpacing: 0,
      color: style.color,
      align: "LEFT",
    },
    runs: [],
    list: "none",
    listStart: 1,
    rotation: 0,
    role: isDate ? "date" : "text",
    format: isDate ? "MM/dd/yy" : undefined,
    prefix: "",
    uppercase: false,
    editable: true,
    slot: true,
  };
}

export function buildWritingSlots(input: TemplateElement[]): TemplateElement[] {
  const els = input.map((e) => (e.type === "text" ? { ...e } : e));
  const texts = els.filter((e): e is TextElement => e.type === "text");
  const shapes = els.filter((e): e is Shape => e.type === "rect" || e.type === "ellipse" || e.type === "checkbox");
  const lines = els.filter((e): e is Line => e.type === "line");
  const hLines = lines.filter((l) => l.y1 === l.y2 && l.x2 - l.x1 >= 150);
  const vLines = lines.filter((l) => l.x1 === l.x2);
  const body = bodyStyle(texts);
  const out: TextElement[] = [];

  // Existing text (by its drawn bounds, not its stretched writable box) or another slot.
  const takenBy = (box: Rect) =>
    texts.some((t) => overlapArea(t, box) > 0.3 * box.w * box.h) || out.some((s) => overlapArea(s.box, box) > 0.3 * box.w * box.h);
  // A label's writable box must stop where the writing area next to it starts.
  const trimLabel = (label: TextElement | undefined, x: number) => {
    if (label && label.box.x + label.box.w > x - 6) label.box = { ...label.box, w: r1(Math.max(label.w, x - 6 - label.box.x)) };
  };

  // ---- ruled lines: one writing line sitting on each rule
  const gaps: number[] = [];
  const prevLine = (l: Line) =>
    hLines
      .filter((o) => o !== l && o.y1 < l.y1 - 4 && Math.min(o.x2, l.x2) - Math.max(o.x1, l.x1) > 0.5 * (l.x2 - l.x1))
      .sort((a, b) => b.y1 - a.y1)[0];
  for (const l of hLines) { const p = prevLine(l); if (p && l.y1 - p.y1 <= 200) gaps.push(l.y1 - p.y1); }
  gaps.sort((a, b) => a - b);
  const medianGap = gaps.length ? gaps[Math.floor(gaps.length / 2)] : 80;

  for (const l of hLines) {
    const p = prevLine(l);
    const bandH = Math.min(p ? l.y1 - p.y1 : medianGap, medianGap * 1.3);
    const band: Rect = { x: l.x1, y: l.y1 - bandH, w: l.x2 - l.x1, h: bandH };
    let x1 = l.x1, x2 = l.x2;
    let label: TextElement | undefined;
    const obstacles: (Rect & { text?: TextElement })[] = [
      ...texts.map((t) => ({ x: t.x, y: t.y, w: t.w, h: t.h, text: t })),
      ...shapes.filter((s) => s.w < band.w * 0.6),
      ...vLines.map((v) => ({ x: v.x1, y: Math.min(v.y1, v.y2), w: 0, h: Math.abs(v.y2 - v.y1) })),
    ];
    for (const o of obstacles) {
      const vOverlap = Math.min(o.y + o.h, band.y + band.h) - Math.max(o.y, band.y);
      if (vOverlap < Math.min(o.h, band.h) * 0.4 || o.x + o.w < x1 - 2 || o.x > x2 + 2) continue;
      if (cx(o) < l.x1 + (l.x2 - l.x1) * 0.5) {
        if (o.x + o.w + 12 > x1) { x1 = o.x + o.w + 12; if (o.text) label = o.text; }
      } else x2 = Math.min(x2, o.x - 12);
    }
    // A label just before the rule's start ("Date: ______").
    if (!label) label = texts.find((t) => t.x + t.w <= l.x1 + 4 && t.x + t.w > l.x1 - 60 && cy(t) > band.y && cy(t) < l.y1 + 4);
    // Only real labels (times, "Something:") get a writing area next to them; other text in
    // the row is content that is already editable itself.
    if (label && !isLabel(label)) continue;
    if (x2 - x1 < 120) continue;
    const style = label ? { ...label.style, fontWeight: label.style.fontWeight } : body;
    const size = Math.min(style.fontSize, bandH * 0.62);
    const lh = size * 1.25;
    const box = { x: r1(x1), y: r1(l.y1 - lh - l.width / 2 - 2), w: r1(x2 - x1), h: r1(lh) };
    if (takenBy(box)) continue;
    trimLabel(label, box.x);
    out.push(slot(`slot-${l.id}`, box, { ...style, fontSize: size }, 1, label));
  }

  // ---- boxes: empty or labelled rectangles
  const rects = shapes.filter((s): s is Extract<Shape, { type: "rect" }> => s.type === "rect" && s.w >= 150 && s.h >= 50);
  rects.forEach((R, idx) => {
    // A same-size rect painted later on top (drop-shadow pattern) wins.
    if (rects.slice(idx + 1).some((o) => overlapArea(o, R) > 0.7 * R.w * R.h)) return;
    // Only shapes fully inside count; header tabs straddling the top edge don't make it a container.
    const innerShapes = shapes.filter((s) => s !== R && s.w * s.h < R.w * R.h &&
      s.x >= R.x - 1 && s.y >= R.y - 1 && s.x + s.w <= R.x + R.w + 1 && s.y + s.h <= R.y + R.h + 1);
    const innerH = hLines.filter((l) => inside((l.x1 + l.x2) / 2, l.y1, R));
    if (innerShapes.length || innerH.length) return;
    const innerT = texts.filter((t) => inside(cx(t), cy(t), R));
    const pad = Math.min(24, R.h * 0.12);
    let top = R.y + pad, left = R.x + pad;
    const right = R.x + R.w - pad, bottom = R.y + R.h - pad;
    let label: TextElement | undefined;

    if (innerT.length === 1) {
      const t = innerT[0];
      const centered = Math.abs(cx(t) - cx(R)) < 0.12 * R.w;
      if (centered && R.h > t.h * 4 && t.y < R.y + R.h * 0.35) top = t.y + t.h + pad / 2; // header inside the box
      else if (!centered && t.x < R.x + R.w * 0.4 && t.x + t.w < cx(R)) {
        label = t;
        left = t.x + t.w + 12;
        for (const v of vLines) if (v.x1 > left - 40 && v.x1 < cx(R) && inside(v.x1, cy(R), R)) left = Math.max(left, v.x1 + 12);
      } else return;
      if (label && !isLabel(label)) return;
    } else if (innerT.length > 1) return;

    // Header tabs / titles straddling the top edge push the writing area down.
    for (const o of [...shapes, ...texts]) {
      if (o === R || innerT.includes(o as TextElement)) continue;
      if (o.y < R.y + 40 && o.y + o.h > R.y && o.y + o.h < R.y + R.h * 0.5 && Math.min(o.x + o.w, right) - Math.max(o.x, left) > 20) {
        top = Math.max(top, o.y + o.h + pad / 2);
      }
    }
    const style = label ? label.style : body;
    const availH = bottom - top;
    if (right - left < 100 || availH < 20) return;
    const size = Math.min(style.fontSize, (label || availH < style.fontSize * 2.5) ? R.h * 0.6 : style.fontSize);
    const lh = size * 1.25;
    const multi = !label && availH >= lh * 2;
    const box = multi
      ? { x: r1(left), y: r1(top), w: r1(right - left), h: r1(availH) }
      : { x: r1(left), y: r1(R.y + (R.h - lh) / 2), w: r1(right - left), h: r1(lh) };
    if (takenBy(box)) return;
    trimLabel(label, box.x);
    out.push(slot(`slot-${R.id}`, box, { ...style, fontSize: size }, multi ? Math.floor(availH / lh) : 1, label));
  });

  return [...els, ...out];
}
