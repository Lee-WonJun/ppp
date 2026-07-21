#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
VIDEO_DIR="$ROOT/docs/submission/video"
SOURCE_DIR="${PPP_VIDEO_SOURCE_DIR:-$ROOT/artifacts/demo-capture/20260720-english-final/edit-segments}"
OUTPUT_ROOT="${PPP_VIDEO_OUTPUT_ROOT:-$ROOT/artifacts/submission-video}"
GENERATED="$OUTPUT_ROOT/generated"
FINAL="$OUTPUT_ROOT/final"
TOOLS_ROOT="${PPP_VIDEO_TOOLS:-/tmp/slopbook-video-tools}"
FFMPEG="${FFMPEG:-$TOOLS_ROOT/node_modules/@ffmpeg-installer/linux-x64/ffmpeg}"
FFPROBE="${FFPROBE:-$TOOLS_ROOT/node_modules/ffprobe-static/bin/linux/x64/ffprobe}"
EDGE_TTS="${EDGE_TTS:-$HOME/.local/bin/edge-tts}"
CHROME="${CHROME:-$(command -v google-chrome)}"

for tool in "$FFMPEG" "$FFPROBE" "$EDGE_TTS" "$CHROME"; do
  if [[ ! -x "$tool" ]]; then
    echo "Missing executable: $tool" >&2
    exit 1
  fi
done

required_segments=(000 002 003 005 006 008 009 011 012 014 015 017)
for segment in "${required_segments[@]}"; do
  source="$SOURCE_DIR/$segment.mp4"
  if [[ ! -s "$source" ]]; then
    echo "Missing verified PPP-025 source segment: $source" >&2
    exit 1
  fi
done

rm -rf "$GENERATED" "$FINAL"
mkdir -p "$GENERATED/slides" "$GENERATED/audio" "$GENERATED/visual" \
  "$GENERATED/parts" "$GENERATED/scenes" "$FINAL"

duration() {
  "$FFPROBE" -v error -show_entries format=duration \
    -of default=noprint_wrappers=1:nokey=1 "$1"
}

max_duration() {
  awk -v a="$1" -v b="$2" 'BEGIN { printf "%.3f", (a > b ? a : b) }'
}

for scene in 01 02 07 08; do
  profile="/tmp/ppp-video-render-$scene-$$"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars \
    --allow-file-access-from-files --window-size=1440,900 \
    --force-device-scale-factor=1 --user-data-dir="$profile" \
    --virtual-time-budget=1000 \
    --screenshot="$GENERATED/slides/$scene.png" \
    "file://$VIDEO_DIR/deck.html?scene=${scene#0}" >/dev/null 2>&1
  rm -rf "$profile"
done

for number in $(seq 1 8); do
  scene="$(printf '%02d' "$number")"
  "$EDGE_TTS" --voice en-US-BrianMultilingualNeural --rate=+4% \
    --file "$VIDEO_DIR/narration/$scene.txt" \
    --write-media "$GENERATED/audio/$scene.mp3" \
    --write-subtitles "$GENERATED/audio/$scene.srt"
done

make_source_part() {
  local output="$1"
  local source="$2"
  local seconds="${3:-}"
  local args=(-y -loglevel error -i "$source")
  if [[ -n "$seconds" ]]; then
    args+=(-t "$seconds")
  fi
  "$FFMPEG" "${args[@]}" \
    -an -vf "scale=1440:900:flags=lanczos,fps=30,format=yuv420p" \
    -c:v libx264 -preset medium -crf 19 -movflags +faststart "$output"
}

make_source_window() {
  local output="$1"
  local source="$2"
  local start="$3"
  local seconds="$4"
  "$FFMPEG" -y -loglevel error -ss "$start" -i "$source" -t "$seconds" \
    -an -vf "scale=1440:900:flags=lanczos,fps=30,format=yuv420p" \
    -c:v libx264 -preset medium -crf 19 -movflags +faststart "$output"
}

make_slow_verified_window() {
  local output="$1"
  local source="$2"
  local start="$3"
  local seconds="$4"
  local factor="$5"
  local font="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
  local output_duration
  output_duration="$(awk -v seconds="$seconds" -v factor="$factor" 'BEGIN { printf "%.3f", seconds * factor }')"
  "$FFMPEG" -y -loglevel error -ss "$start" -i "$source" \
    -an -vf "trim=duration=$seconds,setpts=${factor}*(PTS-STARTPTS),scale=1440:900:flags=lanczos,fps=30,drawbox=x=30:y=30:w=500:h=48:color=0x101310@0.88:t=fill,drawtext=fontfile=$font:text='8x slow playback - real verified frame sequence':fontcolor=white:fontsize=20:x=48:y=43,format=yuv420p" \
    -t "$output_duration" \
    -c:v libx264 -preset medium -crf 19 -movflags +faststart "$output"
}

make_accelerated_wait() {
  local output="$1"
  local source="$2"
  local start="$3"
  local factor="${4:-4}"
  local font="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
  local source_duration
  local output_duration
  source_duration="$(duration "$source")"
  output_duration="$(awk -v total="$source_duration" -v start="$start" -v factor="$factor" 'BEGIN { printf "%.3f", (total - start) / factor }')"
  "$FFMPEG" -y -loglevel error -ss "$start" -i "$source" -an \
    -vf "setpts=(PTS-STARTPTS)/${factor},scale=1440:900:flags=lanczos,fps=30,drawbox=x=30:y=30:w=116:h=42:color=0x101310@0.82:t=fill,drawtext=fontfile=$font:text='${factor}x wait':fontcolor=white:fontsize=18:x=48:y=41,format=yuv420p" \
    -t "$output_duration" \
    -c:v libx264 -preset medium -crf 19 -movflags +faststart "$output"
}

concat_visual() {
  local scene="$1"
  shift
  local list="$GENERATED/parts/$scene.concat.txt"
  : > "$list"
  for source in "$@"; do
    printf "file '%s'\n" "$source" >> "$list"
  done
  "$FFMPEG" -y -loglevel error -f concat -safe 0 -i "$list" \
    -c copy -movflags +faststart "$GENERATED/visual/$scene.mp4"
}

make_source_part "$GENERATED/parts/03-prompt.mp4" "$SOURCE_DIR/000.mp4" 11.000
make_accelerated_wait "$GENERATED/parts/03-wait-4x.mp4" \
  "$SOURCE_DIR/000.mp4" 11.000
make_source_part "$GENERATED/parts/03-outcome.mp4" "$SOURCE_DIR/002.mp4"
concat_visual 03 \
  "$GENERATED/parts/03-prompt.mp4" \
  "$GENERATED/parts/03-wait-4x.mp4" \
  "$GENERATED/parts/03-outcome.mp4"

make_source_part "$GENERATED/parts/04-account-prompt.mp4" "$SOURCE_DIR/003.mp4" 7.000
make_accelerated_wait "$GENERATED/parts/04-account-wait-4x.mp4" \
  "$SOURCE_DIR/003.mp4" 7.000
make_source_part "$GENERATED/parts/04-account-outcome.mp4" "$SOURCE_DIR/005.mp4"
make_source_part "$GENERATED/parts/04-ux-prompt.mp4" "$SOURCE_DIR/006.mp4" 7.000
make_accelerated_wait "$GENERATED/parts/04-ux-wait-4x.mp4" \
  "$SOURCE_DIR/006.mp4" 7.000
make_source_part "$GENERATED/parts/04-ux-outcome.mp4" "$SOURCE_DIR/008.mp4"
concat_visual 04 \
  "$GENERATED/parts/04-account-prompt.mp4" \
  "$GENERATED/parts/04-account-wait-4x.mp4" \
  "$GENERATED/parts/04-account-outcome.mp4" \
  "$GENERATED/parts/04-ux-prompt.mp4" \
  "$GENERATED/parts/04-ux-wait-4x.mp4" \
  "$GENERATED/parts/04-ux-outcome.mp4"

make_source_part "$GENERATED/parts/05-prompt.mp4" "$SOURCE_DIR/009.mp4" 7.000
make_accelerated_wait "$GENERATED/parts/05-wait-4x.mp4" \
  "$SOURCE_DIR/009.mp4" 7.000
make_source_part "$GENERATED/parts/05-outcome.mp4" "$SOURCE_DIR/011.mp4"
concat_visual 05 \
  "$GENERATED/parts/05-prompt.mp4" \
  "$GENERATED/parts/05-wait-4x.mp4" \
  "$GENERATED/parts/05-outcome.mp4"

make_source_part "$GENERATED/parts/06-library-prompt.mp4" "$SOURCE_DIR/012.mp4" 7.000
make_accelerated_wait "$GENERATED/parts/06-library-wait-4x.mp4" \
  "$SOURCE_DIR/012.mp4" 7.000
make_source_part "$GENERATED/parts/06-library-outcome.mp4" "$SOURCE_DIR/014.mp4"
make_source_part "$GENERATED/parts/06-tetris-prompt.mp4" "$SOURCE_DIR/015.mp4" 7.000
make_accelerated_wait "$GENERATED/parts/06-tetris-wait-4x.mp4" \
  "$SOURCE_DIR/015.mp4" 7.000
make_slow_verified_window "$GENERATED/parts/06-tetris-outcome.mp4" \
  "$SOURCE_DIR/017.mp4" 1.050 0.500 8
make_source_window "$GENERATED/parts/06-preserved-snake.mp4" \
  "$SOURCE_DIR/017.mp4" 1.600 3.000
concat_visual 06 \
  "$GENERATED/parts/06-library-prompt.mp4" \
  "$GENERATED/parts/06-library-wait-4x.mp4" \
  "$GENERATED/parts/06-library-outcome.mp4" \
  "$GENERATED/parts/06-tetris-prompt.mp4" \
  "$GENERATED/parts/06-tetris-wait-4x.mp4" \
  "$GENERATED/parts/06-tetris-outcome.mp4" \
  "$GENERATED/parts/06-preserved-snake.mp4"

for scene in 01 02 07 08; do
  audio_duration="$(duration "$GENERATED/audio/$scene.mp3")"
  visual_duration="$(awk -v d="$audio_duration" 'BEGIN { printf "%.3f", d + 1.000 }')"
  "$FFMPEG" -y -loglevel error -loop 1 -framerate 30 \
    -i "$GENERATED/slides/$scene.png" -t "$visual_duration" -an \
    -vf "fps=30,format=yuv420p" -c:v libx264 -preset medium -crf 19 \
    -movflags +faststart "$GENERATED/visual/$scene.mp4"
done

: > "$GENERATED/durations.tsv"
: > "$GENERATED/scenes.concat.txt"

for number in $(seq 1 8); do
  scene="$(printf '%02d' "$number")"
  audio_duration="$(duration "$GENERATED/audio/$scene.mp3")"
  visual_duration="$(duration "$GENERATED/visual/$scene.mp4")"
  padded_audio="$(awk -v d="$audio_duration" 'BEGIN { printf "%.3f", d + 0.700 }')"
  scene_duration="$(max_duration "$padded_audio" "$visual_duration")"
  fade_out="$(awk -v d="$scene_duration" 'BEGIN { printf "%.3f", d - 0.300 }')"

  "$FFMPEG" -y -loglevel error \
    -i "$GENERATED/visual/$scene.mp4" \
    -i "$GENERATED/audio/$scene.mp3" \
    -filter_complex "[0:v]tpad=stop_mode=clone:stop_duration=60,fade=t=in:st=0:d=0.18,fade=t=out:st=$fade_out:d=0.30,fps=30,format=yuv420p[v];[1:a]afade=t=in:st=0:d=0.12,apad[a]" \
    -map "[v]" -map "[a]" -t "$scene_duration" -r 30 \
    -c:v libx264 -preset medium -crf 19 -c:a aac -b:a 160k \
    -movflags +faststart "$GENERATED/scenes/$scene.mp4"

  printf '%s\t%s\n' "$scene" "$scene_duration" >> "$GENERATED/durations.tsv"
  printf "file '%s'\n" "$GENERATED/scenes/$scene.mp4" >> "$GENERATED/scenes.concat.txt"
done

node "$VIDEO_DIR/merge-subtitles.mjs" "$GENERATED"

"$FFMPEG" -y -loglevel error -f concat -safe 0 \
  -i "$GENERATED/scenes.concat.txt" -c copy -movflags +faststart \
  "$GENERATED/ppp-uncaptioned.mp4"

"$FFMPEG" -y -loglevel error -i "$GENERATED/ppp-uncaptioned.mp4" \
  -vf "subtitles=$GENERATED/ppp-build-week-demo.en.srt:force_style='FontName=DejaVu Sans,FontSize=10,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BackColour=&H88000000,BorderStyle=3,Outline=1,Shadow=0,MarginV=22,Alignment=2'" \
  -c:v libx264 -preset medium -crf 19 -c:a copy -movflags +faststart \
  "$GENERATED/ppp-captioned.mp4"

"$FFMPEG" -y -loglevel error \
  -i "$GENERATED/ppp-captioned.mp4" \
  -i "$GENERATED/ppp-build-week-demo.en.srt" \
  -map 0:v:0 -map 0:a:0 -map 1:0 \
  -c:v copy -c:a copy -c:s mov_text \
  -metadata:s:s:0 language=eng -movflags +faststart \
  "$FINAL/ppp-build-week-demo.mp4"

cp "$GENERATED/ppp-build-week-demo.en.srt" \
  "$FINAL/ppp-build-week-demo.en.srt"

final_duration="$(duration "$FINAL/ppp-build-week-demo.mp4")"
video_codec="$($FFPROBE -v error -select_streams v:0 -show_entries stream=codec_name \
  -of default=noprint_wrappers=1:nokey=1 "$FINAL/ppp-build-week-demo.mp4")"
audio_codec="$($FFPROBE -v error -select_streams a:0 -show_entries stream=codec_name \
  -of default=noprint_wrappers=1:nokey=1 "$FINAL/ppp-build-week-demo.mp4")"
subtitle_codec="$($FFPROBE -v error -select_streams s:0 -show_entries stream=codec_name \
  -of default=noprint_wrappers=1:nokey=1 "$FINAL/ppp-build-week-demo.mp4")"
dimensions="$($FFPROBE -v error -select_streams v:0 -show_entries stream=width,height \
  -of csv=s=x:p=0 "$FINAL/ppp-build-week-demo.mp4")"
fps="$($FFPROBE -v error -select_streams v:0 -show_entries stream=r_frame_rate \
  -of default=noprint_wrappers=1:nokey=1 "$FINAL/ppp-build-week-demo.mp4")"

if ! awk -v duration="$final_duration" 'BEGIN { exit !(duration > 120 && duration < 180) }'; then
  echo "Final duration is outside the 120-180 second contract: $final_duration" >&2
  exit 1
fi

if [[ "$video_codec" != "h264" || "$audio_codec" != "aac" || \
      "$subtitle_codec" != "mov_text" || "$dimensions" != "1440x900" || \
      "$fps" != "30/1" || ! -s "$FINAL/ppp-build-week-demo.en.srt" ]]; then
  echo "Final media stream contract failed" >&2
  exit 1
fi

"$FFMPEG" -y -loglevel error -i "$FINAL/ppp-build-week-demo.mp4" \
  -vf "fps=1/15,scale=360:225:flags=lanczos,tile=4x3" -frames:v 1 \
  "$FINAL/contact-sheet.png"

for entry in "01:7" "02:27" "03:49" "04:77" "05:106" "06a:126" "06b:140" "07:153" "08:165"; do
  label="${entry%%:*}"
  second="${entry##*:}"
  "$FFMPEG" -y -loglevel error -ss "$second" \
    -i "$FINAL/ppp-build-week-demo.mp4" -frames:v 1 \
    "$FINAL/frame-$label.png"
done

"$FFPROBE" -v error \
  -show_entries format=duration,size,bit_rate \
  -show_entries stream=index,codec_name,codec_type,width,height,r_frame_rate:stream_tags=language \
  -of json "$FINAL/ppp-build-week-demo.mp4" | tee "$FINAL/probe.json"

printf 'Final video: %s\n' "$FINAL/ppp-build-week-demo.mp4"
printf 'English SRT: %s\n' "$FINAL/ppp-build-week-demo.en.srt"
printf 'Duration: %s seconds\n' "$final_duration"
