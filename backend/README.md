# Backend: Figma → Template JSON → REST API

## Setup
```bash
npm install
cp .env.example .env      # or keep your token in ~/.figma_token
npm run dev               # http://0.0.0.0:4000
```

## Sync templates from Figma
Frames to import are listed in `data/figma-sources.json` (Frame 1 = `1:5`, Frame 2 = `33:2`).
To add a template, add its Figma node id there and re-run the sync.

```bash
npm run sync:figma                  # fetch frames + export image layers
npm run sync:figma -- --force-assets  # re-download every image
npm run sync:figma:offline          # re-normalize from data/figma-cache (no API calls)
npm run validate                    # zod-check data/templates/*.json
```

Figma rate-limits `/images` heavily (HTTP 429). The sync reuses assets it has already
downloaded and caches the raw node JSON, so re-runs normally make only one API call.

### What the normalizer does (`src/figma/normalize.ts`)
- Walks each frame in paint order and emits: `rect`, `ellipse`, `line`, `text`, `image`, `checkbox`.
- Keeps exact Figma values: font family, weight, size, line height, colors, stroke width/alignment, radii, rotation.
- Mixed styling inside one text (e.g. **Date:** medium + **JANUARY 5, 2026** semibold) → `runs`.
- Bullet / numbered lists → `list` + `listStart` (numbering continues down a column).
- `role: "time"` / `"date"` with the original `format`, so the app opens a picker and keeps "8:00 AM" vs "6 AM".
- Computes a writable `box` + `maxLines` for every text from the nearest row line, cell border,
  container or neighbour, so user text wraps / ellipsizes inside the design's lines.
- A check mark inside a circle becomes a toggleable `checkbox`; matching empty circles become unchecked ones.
- Image layers (background art, stickers, check marks) are rendered by Figma to `public/assets/`.

## API
| Method | Path | |
|---|---|---|
| GET | `/api/templates`, `/api/templates/:id` | template JSON |
| GET/POST | `/api/plans` | list / create `{templateId, title, values, checks}` |
| GET/PUT/DELETE | `/api/plans/:id` | |
| GET/POST | `/api/reminders` | `{title, note, remindAt (ISO+offset), enabled, planId}` |
| PUT/DELETE | `/api/reminders/:id` | |
| GET | `/assets/...` | template images |

Plans store only the user's overrides (`values[elementId] = {text, color, fontFamily}`, `checks[id]`),
so a re-synced template updates every plan built on it.
Data lives in `data/plans.json` and `data/reminders.json`. This file store is meant for a
single-instance deployment; swap `src/store/jsonStore.ts` for a database if you run more than one.

The optional "AI model" step from the original flow is not used: Figma's API already gives exact values,
and an LLM pass could only make the design less exact. If you want AI for field naming or
tagging, add it after `normalizeFrame()` and before validation.
