# Daily Planner

Native Android planner (Kotlin + Jetpack Compose). Its templates are built from Figma and delivered as JSON by the backend.

```
Figma ──REST API──▶ backend/ (Node + TypeScript)
                     ├─ normalize Figma nodes → template JSON (shapes, lines, text, images, checkboxes)
                     ├─ zod schema validation
                     └─ REST: /api/templates  /api/plans  /api/reminders  /assets
                                  │
                                  ▼
                     frontend/ (Android, Kotlin + Compose)
                     └─ one Canvas renderer draws the JSON on an A4 sheet
                        → editor, thumbnails and PDF export all use the same drawing code
```

| Folder | What it is |
|---|---|
| `backend/`  | Figma sync pipeline and REST API. See `backend/README.md` |
| `frontend/` | Android app. See `frontend/README.md` |

## Quick start

> **Fonts:** Comic Sans MS (Microsoft) is not in git. On macOS run
> `frontend/tools/copy-system-fonts.sh` once; without it the app falls back to Patrick Hand.
>
> **Secrets (not in git):** `~/.figma_token` (Figma API), `frontend/keystore/` +
> `frontend/keystore.properties` (release signing).


```bash
# 1. Backend
cd backend && npm install && npm run dev          # http://localhost:4000

# 2. Build the app — templates + images from backend/ are bundled into the APK
cd frontend && ./gradlew installDebug        # test on a USB phone
./gradlew assembleRelease                    # signed APK for sharing
```
