#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
for name in airpods_white open_ear_black shokz peugeot; do
  input="$ROOT/app/src/main/res/raw/demo_$name.gif"
  ffmpeg -hide_banner -loglevel error -y -i "$input" \
    -vf "scale='trunc(min(720,iw)/2)*2':-2,fps=20" -an \
    -c:v libx264 -preset medium -crf 24 -pix_fmt yuv420p -movflags +faststart \
    "$ROOT/store/assets/$name.mp4"
  ffmpeg -hide_banner -loglevel error -y -i "$input" -frames:v 1 \
    -vf "scale='trunc(min(720,iw)/2)*2':-2" -q:v 5 "$ROOT/store/assets/$name.jpg"
done
