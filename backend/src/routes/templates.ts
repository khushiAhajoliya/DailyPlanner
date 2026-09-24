import { Router } from "express";
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { DATA_DIR, TEMPLATES_DIR } from "../config.js";
import { TemplateSchema, type Template } from "../schema/template.js";

/** Templates are listed in the order of data/figma-sources.json. */
const order = (): string[] =>
  (JSON.parse(readFileSync(join(DATA_DIR, "figma-sources.json"), "utf8")) as { id: string }[]).map((s) => s.id);

function loadAll(): Template[] {
  return readdirSync(TEMPLATES_DIR)
    .filter((f) => f.endsWith(".json"))
    .map((f) => TemplateSchema.parse(JSON.parse(readFileSync(join(TEMPLATES_DIR, f), "utf8"))))
    .sort((a, b) => order().indexOf(a.id) - order().indexOf(b.id));
}

// Templates are validated at startup and re-read on every request so a Figma re-sync
// shows up without restarting the server.
loadAll();

export const templatesRouter = Router();

templatesRouter.get("/", (_req, res) => {
  res.json(loadAll());
});

templatesRouter.get("/:id", (req, res) => {
  const t = loadAll().find((x) => x.id === req.params.id);
  if (!t) return res.status(404).json({ error: "Template not found" });
  res.json(t);
});
