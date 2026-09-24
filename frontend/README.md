# Android app (Kotlin + Jetpack Compose)

## Run
```bash
# Phone over USB (recommended): the phone's localhost:4000 is forwarded to the Mac
adb reverse tcp:4000 tcp:4000            # re-run after re-plugging the phone
./gradlew installDebug -PplannerBackendUrl=http://localhost:4000/

# Emulator: default URL http://10.0.2.2:4000/
./gradlew installDebug
```
Debug builds use the id `com.dailyplanner.app.dev`, so they install next to any existing `com.dailyplanner.app`.

## Screens
- **Templates**: A4 previews rendered from the backend JSON. Tap one to open the editor.
- **Editor**: tap any text (headings, labels, times, dates, your own text) to open the edit panel:
  - edit the text (or use a time/date picker for time and date fields; the original format is kept)
  - **Color**: the design's colors plus a curated muted palette
  - **Font**: Fredoka, Fredoka One, Nunito, Comic Sans MS, Quicksand, Poppins, Caveat, Patrick Hand, Playfair Display
  - checkboxes in the To-Do list toggle when tapped
  - text wraps inside its row or box and is cut off with "…" at the design's line limit
  - **Create** asks for a name, saves the plan and opens it in My Plans
- **My Plans**: saved plans. Top-right icons: **Edit**, **Share** (A4 PDF via share sheet), **Save** (A4 PDF to Downloads/DailyPlanner).
  Long-press a plan to delete it.
- **Reminders**: date and time notifications (AlarmManager, re-armed after reboot).

## A4
Every page is an A4 sheet (595 × 842 pt). The Figma frames are 1080 × 2424 (a tall phone ratio),
so the design is scaled uniformly to fit the sheet height and centered, with nothing stretched or cropped.
The side margins use the template's paper color (#FFFDF5).

## Key code
- `render/TemplateRenderer.kt`: draws template JSON on a Canvas (screen and PDF)
- `render/FontRegistry.kt`: Figma family + weight → bundled font (variable axes set to the exact weight)
- `render/PdfExporter.kt`: A4 PDF, share and Downloads
- `ui/components/PlannerPage.kt`: zoom/pan, tap hit-testing, keeps the edited text in view

## Fonts
Google Fonts (OFL) are bundled in `assets/fonts`. **Comic Sans MS** (Frame 2) was copied from macOS
for development. It is a Microsoft font, so get a license before publishing the app, or replace it.
