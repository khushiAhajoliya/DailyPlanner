import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { TEMPLATES_DIR } from "../config.js";
import { TemplateSchema } from "../schema/template.js";

let failed = 0;
for (const f of readdirSync(TEMPLATES_DIR).filter((f) => f.endsWith(".json"))) {
  const r = TemplateSchema.safeParse(JSON.parse(readFileSync(join(TEMPLATES_DIR, f), "utf8")));
  if (r.success) console.log(`✓ ${f}`);
  else { failed++; console.error(`✗ ${f}`, r.error.issues.slice(0, 5)); }
}
process.exit(failed ? 1 : 0);
