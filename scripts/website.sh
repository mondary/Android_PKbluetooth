#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
DEST="${1:-$ROOT/store/website}"
mkdir -p "$DEST/assets"
cp "$ROOT/store/index.html" "$DEST/index.html"
cp "$ROOT/store/assets/icon.png" "$DEST/assets/icon.png"
for name in airpods_white open_ear_black shokz peugeot; do
  cp "$ROOT/store/assets/$name.mp4" "$DEST/assets/$name.mp4"
  cp "$ROOT/store/assets/$name.jpg" "$DEST/assets/$name.jpg"
done
printf 'Landing PK Bluetooth : %s\n' "$DEST"
