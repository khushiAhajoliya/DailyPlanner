import { Router } from "express";
import { join } from "node:path";
import { readdirSync } from "node:fs";
import { DATA_DIR, TEMPLATES_DIR } from "../config.js";
import { JsonCollection, type Entity } from "../store/jsonStore.js";
import { PlanInputSchema } from "../schema/template.js";
import type { z } from "zod";

export type Plan = Entity & z.infer<typeof PlanInputSchema>;
const plans = new JsonCollection<Plan>(join(DATA_DIR, "plans.json"));
const templateExists = (id: string) => readdirSync(TEMPLATES_DIR).includes(`${id}.json`);

export const plansRouter = Router();

plansRouter.get("/", (_req, res) => res.json(plans.all()));

plansRouter.get("/:id", (req, res) => {
  const p = plans.get(req.params.id);
  return p ? res.json(p) : res.status(404).json({ error: "Plan not found" });
});

plansRouter.post("/", (req, res) => {
  const parsed = PlanInputSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "Invalid plan", issues: parsed.error.issues });
  if (!templateExists(parsed.data.templateId)) return res.status(400).json({ error: "Unknown templateId" });
  res.status(201).json(plans.create(parsed.data));
});

plansRouter.put("/:id", (req, res) => {
  const parsed = PlanInputSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "Invalid plan", issues: parsed.error.issues });
  if (!templateExists(parsed.data.templateId)) return res.status(400).json({ error: "Unknown templateId" });
  // Upsert: the app saves plans on the phone with its own id and syncs them later.
  res.json(plans.upsert(req.params.id, parsed.data));
});

plansRouter.delete("/:id", (req, res) => {
  return plans.remove(req.params.id) ? res.status(204).end() : res.status(404).json({ error: "Plan not found" });
});
