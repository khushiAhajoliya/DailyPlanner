#!/bin/sh
# Comic Sans MS is a Microsoft font and is not stored in git.
# On macOS, copy the system copy into the app (needed by the "Daily Planner" templates).
set -e
DIR="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/assets/fonts"
SRC="/System/Library/Fonts/Supplemental"
cp "$SRC/Comic Sans MS.ttf" "$DIR/comic_sans.ttf"
cp "$SRC/Comic Sans MS Bold.ttf" "$DIR/comic_sans_bold.ttf"
echo "Copied Comic Sans MS into $DIR"
