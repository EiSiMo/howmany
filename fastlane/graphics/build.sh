#!/usr/bin/env bash
# Builds the store graphics F-Droid and Play show, into ../metadata/android/en-US/images/:
# the phone screenshots (phoneScreenshots/1.png ...) and the feature graphic (featureGraphic.png).
#
# Sources are app screenshots taken on the phone, with the status bar cut off and resized to
# the iPhone 17 Pro screen frameit frames them with (1206x2622):
#   screenshots/en-US/0N.png        one per phone screenshot, captions in title.strings
#                                   (05.png is built here from the house screenshots)
#   feature-graphic/house-*.png     the house before and after counting
#   feature-graphic/wood.png        the firewood after counting
#
# Needs fastlane (frameit), ImageMagick and uv.
set -euo pipefail
cd "$(dirname "$0")"

TOP='#23252D'
BOTTOM='#0F1013'
IMAGES=../metadata/android/en-US/images
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
export FASTLANE_SKIP_UPDATE_CHECK=1 FASTLANE_HIDE_CHANGELOG=1

# frameit and ImageMagick draw a variable font in its default (light) weight, so cut static
# weights out of the app's font. frameit only takes font paths relative to the Framefile.
FONTS=screenshots/fonts
mkdir -p "$FONTS"
for weight in Regular:400 Bold:700; do
  uvx --from fonttools fonttools varLib.instancer -q ../../android/app/src/main/res/font/space_grotesk.ttf \
    "wght=${weight#*:}" -o "$FONTS/SpaceGrotesk-${weight%:*}.ttf"
done

# Splits the house screenshot into before (above) and after (below) along a line from (0, $2)
# to (1206, $3), written to $1.
split_house() {
  magick -size 1206x2622 xc:black -fill white -draw "polygon 0,0 1206,0 1206,$3 0,$2" "$TMP/mask.png"
  magick "$5" \( "$4" "$TMP/mask.png" -alpha off -compose CopyOpacity -composite \) \
    -compose over -composite -stroke white -strokewidth 6 -draw "line 0,$2 1206,$3" "$1"
}

## Phone screenshots

# The line crosses the photo (y=430 to 1880) where the one in the feature graphic does: its
# bottom at x=108, its top at x=1028.
split_house screenshots/en-US/05.png 2050 150 feature-graphic/house-before.png feature-graphic/house-after.png

# A vertical gradient extends sideways without a seam, unlike a radial one.
magick -size 1206x2622 "gradient:$TOP-$BOTTOM" screenshots/background.png
(cd screenshots && fastlane frameit ios)

mkdir -p "$IMAGES/phoneScreenshots"
rm -f "$IMAGES"/phoneScreenshots/*.png
for framed in screenshots/en-US/*_framed.png; do
  name=$(basename "$framed" _framed.png)
  # frameit leaves empty space below the device: cut it off (to 91.5% of the height) and
  # continue the gradient with the colour it has there. Play wants 9:16 without alpha.
  magick -size 1440x2560 "gradient:$TOP-#111215" \
    \( "$framed" -crop 1206x2400+0+0 +repage -resize x2560 \) \
    -gravity center -composite -alpha off -depth 8 "$IMAGES/phoneScreenshots/${name#0}.png"
done

## Feature graphic

# The graphic shows only the upper part of each device. Cut black space above the photo and
# between the photo (ends at y=1880) and the count (starts at 2283) with the hint in between,
# so the count shows, and pad the bottom.
TOP_CUT=150
GAP_CUT_START=1990
GAP_CUT_END=2275
tighten() {
  magick "$1" \( -clone 0 -crop 1206x100+0+0 \) \
    \( -clone 0 -crop "1206x$((GAP_CUT_START - 100 - TOP_CUT))+0+$((100 + TOP_CUT))" \) \
    \( -clone 0 -crop "1206x$((2622 - GAP_CUT_END))+0+$GAP_CUT_END" \) -delete 0 +repage -append \
    -background black -gravity north -extent 1206x2622 "$2"
}
mkdir -p "$TMP/devices"
tighten feature-graphic/house-before.png "$TMP/before.png"
tighten feature-graphic/house-after.png "$TMP/after.png"
tighten feature-graphic/wood.png "$TMP/devices/wood.png"
split_house "$TMP/devices/house.png" 1900 0 "$TMP/before.png" "$TMP/after.png"

# Without a Framefile.json, frameit renders just the device on a transparent background.
(cd "$TMP/devices" && fastlane frameit ios)

# Place each device so the graphic ends just below its count, above the home indicator: in the
# app the count ends at y=2504 and the indicator starts at 2584 of 2622. Tightening moves both
# up, and the screen sits 69px down in the 2760px high frame.
VISIBLE_BOTTOM=$((69 + 2560 - TOP_CUT - (GAP_CUT_END - GAP_CUT_START)))
top_for_height() { awk -v h="$1" -v b=$VISIBLE_BOTTOM 'BEGIN { printf "%d", 500 - b * h / 2760 + 0.5 }'; }
HOUSE_HEIGHT=600
WOOD_HEIGHT=540

magick -size 1024x500 "gradient:$TOP-$BOTTOM" \
  \( ../../assets/icon.png -resize 88x88 \) -geometry +56+92 -composite \
  -font "$FONTS/SpaceGrotesk-Bold.ttf" -fill '#F2F2F5' -pointsize 64 -annotate +52+262 'how many?' \
  -font "$FONTS/SpaceGrotesk-Regular.ttf" -pointsize 30 -annotate +56+314 'Count anything in a photo' \
  -fill '#B8C4F2' -pointsize 22 -annotate +56+360 'Fully offline · Open source' \
  \( "$TMP/devices/wood_framed.png" -resize "x$WOOD_HEIGHT" \) -geometry "+748+$(top_for_height $WOOD_HEIGHT)" -composite \
  \( "$TMP/devices/house_framed.png" -resize "x$HOUSE_HEIGHT" \) -geometry "+496+$(top_for_height $HOUSE_HEIGHT)" -composite \
  -alpha off -depth 8 "$IMAGES/featureGraphic.png"
