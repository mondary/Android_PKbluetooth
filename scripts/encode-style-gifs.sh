#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
FRAMES="${1:?Usage: encode-style-gifs.sh FRAMES_DIR}"
for style in lovely fanciest; do
  ffmpeg -hide_banner -loglevel error -y -framerate 30 -i "$FRAMES/$style/%04d.png" \
    -filter_complex "[0:v]fps=20,scale=480:400:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128:stats_mode=diff[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" \
    -loop 0 "$ROOT/store/gifs/style-$style.gif"
done
ffmpeg -hide_banner -loglevel error -y -i "$FRAMES/minimal/0000.png" \
  -vf "crop=1014:150:0:0,scale=480:71:flags=lanczos,pad=480:400:0:164:color=0x0D1117" \
  -frames:v 1 /tmp/pkbt-style-minimal.png
ffmpeg -hide_banner -loglevel error -y -i /tmp/pkbt-style-minimal.png \
  -vf "split[s0][s1];[s0]palettegen=max_colors=64[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3" \
  -loop 0 "$ROOT/store/gifs/style-minimal.gif"
for style in minimal lovely fanciest; do
  ffmpeg -hide_banner -loglevel error -y -i "$ROOT/store/gifs/style-$style.gif" \
    -frames:v 1 -q:v 3 "$ROOT/store/assets/style-$style.jpg"
done
echo "Style GIFs and reduced-motion posters written to $ROOT/store"
