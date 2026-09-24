import express from "express";
import cors from "cors";
import { ASSETS_DIR, PORT } from "./config.js";
import { templatesRouter } from "./routes/templates.js";
import { plansRouter } from "./routes/plans.js";
import { remindersRouter } from "./routes/reminders.js";

const app = express();
app.use(cors());
app.use(express.json({ limit: "1mb" }));
app.use((req, res, next) => {
  res.on("finish", () => { if (req.path.startsWith("/api")) console.log(`${req.method} ${req.path} -> ${res.statusCode}`); });
  next();
});

app.get("/api/health", (_req, res) => res.json({ ok: true }));
app.use("/assets", express.static(ASSETS_DIR, { maxAge: "7d", immutable: true }));
app.use("/api/templates", templatesRouter);
app.use("/api/plans", plansRouter);
app.use("/api/reminders", remindersRouter);

app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  console.error(err);
  res.status(500).json({ error: "Internal error" });
});

// 0.0.0.0 so the Android emulator (10.0.2.2) and phones on the same Wi-Fi can connect.
app.listen(PORT, "0.0.0.0", () => console.log(`Daily Planner API on http://localhost:${PORT}`));
