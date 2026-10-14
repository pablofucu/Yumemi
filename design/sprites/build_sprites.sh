#!/usr/bin/env bash
# Builds the app's sprite layers from the planetarian HD Yumemi sprite pack.
# Usage: build_sprites.sh <folder with YU_*.png>
# All source layers share one 1280x960 canvas, so cropping every layer with the
# same frame keeps them aligned; the app just stacks them.
set -euo pipefail
SRC="$1"
HERE="$(cd "$(dirname "$0")" && pwd)"
RES="$HERE/../../android/app/src/main/res"
OUT="$RES/drawable-nodpi"
mkdir -p "$OUT"

# Frame (source px): x=400 y=0 w=880 h=960, scaled to 60% -> 528x576
FRAME="880x960+400+0"
SCALE="60%"

layer() { # <source name> <resource name>
  convert "$SRC/$1.png" -crop "$FRAME" +repage -resize "$SCALE" \
    -define webp:lossless=false -quality 92 -define webp:alpha-quality=100 \
    "$OUT/$2.webp"
}

# bodies
layer YU_B00 yu_body_stand
layer YU_B20 yu_body_shy
# arm overlays (drawn over the standing body)
layer YU_A00 yu_arms_rest
layer YU_A01 yu_arms_think
layer YU_A05 yu_arms_wrench
# faces for the standing body
layer YU_F00 yu_face_smile
layer YU_F01 yu_face_talk
layer YU_F02 yu_face_worried
layer YU_F03 yu_face_focus
layer YU_F04 yu_face_ponder
layer YU_F05 yu_face_surprised
layer YU_F06 yu_face_wink
# face for the shy body
layer YU_F20 yu_face_sleep
# ribbons (drawn on top), colour per mood
layer YU_R003 yu_ribbon_stand_lilac
layer YU_R004 yu_ribbon_stand_violet
layer YU_R005 yu_ribbon_stand_teal
layer YU_R000 yu_ribbon_stand_gold
layer YU_R001 yu_ribbon_stand_pink
layer YU_R002 yu_ribbon_stand_green
layer YU_R204 yu_ribbon_shy_violet

# Launcher icon foreground: head crop of body + smile, centred in a 432px canvas
# (adaptive icon safe zone is the middle 66%).
TMP="$(mktemp -d)"
convert "$SRC/YU_B00.png" "$SRC/YU_F00.png" -background none -layers flatten \
  -crop 380x380+815+60 +repage "$TMP/head.png"
for spec in mdpi:108 hdpi:162 xhdpi:216 xxhdpi:324 xxxhdpi:432; do
  d="${spec%%:*}"; s="${spec##*:}"; inner=$(( s * 72 / 108 ))
  mkdir -p "$RES/mipmap-$d"
  convert "$TMP/head.png" -resize "${inner}x${inner}" -background none -gravity center \
    -extent "${s}x${s}" "$RES/mipmap-$d/ic_launcher_foreground.png"
done
rm -rf "$TMP"
echo "sprites written to $OUT"
