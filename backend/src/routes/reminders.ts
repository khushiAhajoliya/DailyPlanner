import { Router } from "express";
import { join } from "node:path";
import { DATA_DIR } from "../config.js";
import { JsonCollection, type Entity } from "../store/jsonStore.js";
import { ReminderInputSchema } from "../schema/template.js";
import type { z } from "zod";

export type Reminder = Entity & z.infer<typeof ReminderInputSchema>;
const reminders = new JsonCollection<Reminder>(join(DATA_DIR, "reminders.json"));

export const remindersRouter = Router();

remindersRouter.get("/", (_req, res) => {
  res.json(reminders.all().sort((a, b) => a.remindAt.localeCompare(b.remindAt)));
});

remindersRouter.post("/", (req, res) => {
  const parsed = ReminderInputSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "Invalid reminder", issues: parsed.error.issues });
  res.status(201).json(reminders.create(parsed.data));
});

remindersRouter.put("/:id", (req, res) => {
  const parsed = ReminderInputSchema.safeParse(req.body);
  if (!parsed.success) return res.status(400).json({ error: "Invalid reminder", issues: parsed.error.issues });
  // Upsert: the app creates reminders offline with its own id and syncs them later.
  res.json(reminders.upsert(req.params.id, parsed.data));
});

remindersRouter.delete("/:id", (req, res) => {
  return reminders.remove(req.params.id) ? res.status(204).end() : res.status(404).json({ error: "Reminder not found" });
});
