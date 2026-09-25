import { z } from "zod";

/**
 * Template JSON contract shared with the Android app.
 * All coordinates are in design units (the Figma frame's pixel space, origin top-left).
 * Colors are "#RRGGBB".
 */

const hex = z.string().regex(/^#[0-9A-F]{6}$/i, "color must be #RRGGBB");

export const StrokeSchema = z.object({
  color: hex,
  width: z.number().positive(),
  align: z.enum(["INSIDE", "CENTER", "OUTSIDE"]),
});

const Bounds = {
  x: z.number(),
  y: z.number(),
  w: z.number().nonnegative(),
  h: z.number().nonnegative(),
};

export const ImageElementSchema = z.object({
  type: z.literal("image"),
  id: z.string(),
  name: z.string(),
  ...Bounds,
  src: z.string(),
  opacity: z.number().min(0).max(1).default(1),
});

export const ShapeElementSchema = z.object({
  type: z.enum(["rect", "ellipse"]),
  id: z.string(),
  ...Bounds,
  fill: hex.nullable(),
  fillOpacity: z.number().min(0).max(1).default(1),
  stroke: StrokeSchema.nullable(),
  radius: z.number().nonnegative().default(0),
  /** The design's "selected day" marker; the app moves it to the weekday of the date. */
  weekdayHighlight: z.boolean().default(false),
});

export const LineElementSchema = z.object({
  type: z.literal("line"),
  id: z.string(),
  x1: z.number(),
  y1: z.number(),
  x2: z.number(),
  y2: z.number(),
  color: hex,
  width: z.number().positive(),
});

export const CheckboxElementSchema = z.object({
  type: z.literal("checkbox"),
  id: z.string(),
  ...Bounds,
  fill: hex.nullable(),
  stroke: StrokeSchema.nullable(),
  checked: z.boolean(),
  shape: z.enum(["ellipse", "rect"]).default("ellipse"),
  radius: z.number().nonnegative().default(0),
  /** Check mark image, positioned relative to the checkbox origin. */
  check: z.object({ src: z.string(), dx: z.number(), dy: z.number(), w: z.number(), h: z.number() }),
});

export const TextStyleSchema = z.object({
  fontFamily: z.string(),
  fontWeight: z.number().int().min(100).max(900),
  italic: z.boolean(),
  fontSize: z.number().positive(),
  lineHeight: z.number().positive(),
  letterSpacing: z.number(),
  color: hex,
  align: z.enum(["LEFT", "CENTER", "RIGHT"]),
});

export const TextRunSchema = z.object({
  start: z.number().int().nonnegative(),
  end: z.number().int().positive(),
  fontWeight: z.number().int().optional(),
  fontFamily: z.string().optional(),
  italic: z.boolean().optional(),
});

export const TextElementSchema = z.object({
  type: z.literal("text"),
  id: z.string(),
  name: z.string(),
  text: z.string(),
  /** Original Figma text bounds (before rotation is applied). */
  ...Bounds,
  /** Writable area: user text wraps / ellipsizes inside this box. */
  box: z.object(Bounds),
  maxLines: z.number().int().positive(),
  style: TextStyleSchema,
  runs: z.array(TextRunSchema).default([]),
  list: z.enum(["none", "ordered", "unordered"]).default("none"),
  /** Number of the first ordered-list item (Figma continues numbering across stacked rows). */
  listStart: z.number().int().positive().default(1),
  /** Clockwise rotation in degrees around the box center. */
  rotation: z.number().default(0),
  /** "time" opens a time picker, "date" a date picker; both keep the original format. */
  role: z.enum(["text", "time", "date"]).default("text"),
  /** java.time DateTimeFormatter pattern used for role time/date. */
  format: z.string().optional(),
  /** Literal prefix kept in front of a date/time value, e.g. "Date: ". */
  prefix: z.string().default(""),
  uppercase: z.boolean().default(false),
  editable: z.boolean().default(true),
  /** Empty writing area generated from a ruled line or an empty box. */
  slot: z.boolean().default(false),
  /** Weekday letter (1 = Monday … 7 = Sunday); highlighted from the page's date, not editable. */
  weekday: z.number().int().min(1).max(7).optional(),
});

export const ElementSchema = z.discriminatedUnion("type", [
  ImageElementSchema,
  ShapeElementSchema.extend({ type: z.literal("rect") }),
  ShapeElementSchema.extend({ type: z.literal("ellipse") }),
  LineElementSchema,
  CheckboxElementSchema,
  TextElementSchema,
]);

export const TemplateSchema = z.object({
  id: z.string(),
  name: z.string(),
  category: z.string(),
  version: z.number().int().positive(),
  source: z.object({ fileKey: z.string(), nodeId: z.string(), syncedAt: z.string() }),
  page: z.object({
    width: z.number().positive(),
    height: z.number().positive(),
    background: hex,
    /** Output paper. The design is scaled uniformly to fit and centered on the sheet. */
    paper: z.object({ name: z.literal("A4"), widthPt: z.literal(595), heightPt: z.literal(842) }),
  }),
  editorOptions: z.object({
    colors: z.array(z.object({ name: z.string(), hex })),
    fonts: z.array(z.string()),
  }),
  elements: z.array(ElementSchema),
});

export type Template = z.infer<typeof TemplateSchema>;
export type TemplateElement = z.infer<typeof ElementSchema>;
export type TextElement = z.infer<typeof TextElementSchema>;

/** Plans store only what the user changed on top of a template. */
export const TextOverrideSchema = z.object({
  text: z.string().max(2000).optional(),
  color: hex.optional(),
  fontFamily: z.string().max(64).optional(),
});

export const PlanInputSchema = z.object({
  templateId: z.string().min(1),
  title: z.string().min(1).max(120),
  values: z.record(z.string(), TextOverrideSchema).default({}),
  checks: z.record(z.string(), z.boolean()).default({}),
});

export const ReminderInputSchema = z.object({
  title: z.string().min(1).max(120),
  note: z.string().max(500).default(""),
  remindAt: z.string().datetime({ offset: true }),
  enabled: z.boolean().default(true),
  planId: z.string().nullable().default(null),
  /** Text element the reminder was set from (task on a plan). */
  elementId: z.string().nullable().default(null),
});
