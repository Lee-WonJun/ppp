#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
VIDEO_DIR="$ROOT/docs/submission/video"
CAPTURE_ROOT="${PPP_PUBLIC_CAPTURE_ROOT:-$ROOT/artifacts/public-demo-capture/20260721-072904}"
OUTPUT_ROOT="${PPP_VIDEO_OUTPUT_ROOT:-$ROOT/artifacts/submission-video}"
GENERATED="$OUTPUT_ROOT/generated"
FINAL="$OUTPUT_ROOT/final"
TOOLS_ROOT="${PPP_VIDEO_TOOLS:-/tmp/slopbook-video-tools}"
FFMPEG="${FFMPEG:-$TOOLS_ROOT/node_modules/@ffmpeg-installer/linux-x64/ffmpeg}"
FFPROBE="${FFPROBE:-$TOOLS_ROOT/node_modules/ffprobe-static/bin/linux/x64/ffprobe}"
EDGE_TTS="${EDGE_TTS:-$HOME/.local/bin/edge-tts}"
CHROME="${CHROME:-$(command -v google-chrome)}"
OBSERVATIONS="$CAPTURE_ROOT/observations.json"
FIRST_SOURCE="$CAPTURE_ROOT/playwright/demo-story-real-Codex-evol-4795d--a-persistent-game-platform/video.webm"
SUCCESS_SOURCE=""
SHOWCASE_SOURCE="$CAPTURE_ROOT/showcase/public-demo-showcase-show--7d698-ic-product-outcomes-clearly/video.webm"

for tool in "$FFMPEG" "$FFPROBE" "$EDGE_TTS" "$CHROME"; do
  if [[ ! -x "$tool" ]]; then
    echo "Missing executable: $tool" >&2
    exit 1
  fi
done

while IFS= read -r candidate; do
  candidate_dir="$(dirname "$candidate")"
  if [[ ! -e "$candidate_dir/test-failed-1.png" ]]; then
    SUCCESS_SOURCE="$candidate"
    break
  fi
done < <(find "$CAPTURE_ROOT" -path '*/playwright-resume-*/demo-story-*/video.webm' \
  -type f -print | sort -r)

for source in "$OBSERVATIONS" "$FIRST_SOURCE" "$SUCCESS_SOURCE" "$SHOWCASE_SOURCE"; do
  if [[ -z "$source" || ! -s "$source" ]]; then
    echo "Missing verified public-server capture input: $source" >&2
    exit 1
  fi
done

if ! node -e '
  const x=require(process.argv[1]);
  const expected=["DEMO-01","PUBLIC-02","PUBLIC-03","PUBLIC-04","PUBLIC-05"];
  if(JSON.stringify(x.records.map(r=>r.scenario))!==JSON.stringify(expected)) process.exit(1);
  if(!x.records.every(r=>r["browser-outcome"]===true && r.outcomes["semantic-repair-count"]===0)) process.exit(1);
' "$OBSERVATIONS"; then
  echo "Public-server observations do not contain the verified five-step run" >&2
  exit 1
fi

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

marker() {
  node -e '
    const x=require(process.argv[1]);
    const record=x.records.find(r=>r.scenario===process.argv[2]);
    if(!record) process.exit(1);
    const value=record.capture[process.argv[3]];
    if(typeof value!=="number") process.exit(1);
    process.stdout.write((value/1000).toFixed(3));
  ' "$OBSERVATIONS" "$1" "$2"
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
  local end="$4"
  local factor="${5:-6}"
  local font="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
  local source_duration
  local output_duration
  source_duration="$(awk -v start="$start" -v end="$end" 'BEGIN { printf "%.3f", end - start }')"
  output_duration="$(awk -v source="$source_duration" -v factor="$factor" 'BEGIN { printf "%.3f", source / factor }')"
  "$FFMPEG" -y -loglevel error -ss "$start" -i "$source" -t "$source_duration" -an \
    -vf "setpts=(PTS-STARTPTS)/${factor},scale=1440:900:flags=lanczos,fps=30,drawbox=x=30:y=30:w=116:h=42:color=0x101310@0.82:t=fill,drawtext=fontfile=$font:text='${factor}x wait':fontcolor=white:fontsize=18:x=48:y=41,format=yuv420p" \
    -t "$output_duration" \
    -c:v libx264 -preset medium -crf 19 -movflags +faststart "$output"
}

make_verified_outcome() {
  local output="$1"
  local source="$2"
  local start="$3"
  local end="$4"
  local hold="${5:-3.5}"
  local seconds
  seconds="$(awk -v start="$start" -v end="$end" 'BEGIN { printf "%.3f", end - start }')"
  "$FFMPEG" -y -loglevel error -ss "$start" -i "$source" -t "$seconds" -an \
    -vf "scale=1440:900:flags=lanczos,fps=30,tpad=stop_mode=clone:stop_duration=$hold,format=yuv420p" \
    -c:v libx264 -preset medium -crf 19 -movflags +faststart "$output"
}

make_held_source_frame() {
  local output="$1"
  local source="$2"
  local at="$3"
  local hold="$4"
  local frame="$GENERATED/parts/$(basename "$output" .mp4).png"
  "$FFMPEG" -y -loglevel error -ss "$at" -i "$source" -frames:v 1 \
    -vf "scale=1440:900:flags=lanczos" "$frame"
  "$FFMPEG" -y -loglevel error -loop 1 -framerate 30 -i "$frame" -t "$hold" \
    -an -vf "fps=30,format=yuv420p" -c:v libx264 -preset medium -crf 19 \
    -movflags +faststart "$output"
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

snake_start="$(marker DEMO-01 scenario-start-ms)"
snake_generated="$(marker DEMO-01 generation-complete-ms)"
snake_verified="$(marker DEMO-01 verification-complete-ms)"
server_start="$(marker PUBLIC-02 scenario-start-ms)"
server_generated="$(marker PUBLIC-02 generation-complete-ms)"
server_verified="$(marker PUBLIC-02 verification-complete-ms)"
rule_start="$(marker PUBLIC-03 scenario-start-ms)"
rule_generated="$(marker PUBLIC-03 generation-complete-ms)"
rule_verified="$(marker PUBLIC-03 verification-complete-ms)"
library_start="$(marker PUBLIC-04 scenario-start-ms)"
library_generated="$(marker PUBLIC-04 generation-complete-ms)"
library_verified="$(marker PUBLIC-04 verification-complete-ms)"
tetris_start="$(marker PUBLIC-05 scenario-start-ms)"
tetris_generated="$(marker PUBLIC-05 generation-complete-ms)"
tetris_verified="$(marker PUBLIC-05 verification-complete-ms)"

snake_prompt_end="$(awk -v start="$snake_start" 'BEGIN { printf "%.3f", start + 8.0 }')"
make_source_window "$GENERATED/parts/03-prompt.mp4" "$FIRST_SOURCE" \
  "$snake_start" 8.000
make_accelerated_wait "$GENERATED/parts/03-wait-7x.mp4" "$FIRST_SOURCE" \
  "$snake_prompt_end" "$snake_generated" 7
make_verified_outcome "$GENERATED/parts/03-outcome.mp4" "$FIRST_SOURCE" \
  "$(awk -v generated="$snake_generated" 'BEGIN { printf "%.3f", generated - 1.0 }')" \
  "$snake_verified" 4.0
concat_visual 03 \
  "$GENERATED/parts/03-prompt.mp4" \
  "$GENERATED/parts/03-wait-7x.mp4" \
  "$GENERATED/parts/03-outcome.mp4"

server_prompt_end="$(awk -v start="$server_start" 'BEGIN { printf "%.3f", start + 8.0 }')"
make_source_window "$GENERATED/parts/04-prompt.mp4" "$SUCCESS_SOURCE" \
  "$server_start" 8.000
make_accelerated_wait "$GENERATED/parts/04-wait-7x.mp4" "$SUCCESS_SOURCE" \
  "$server_prompt_end" "$server_generated" 7
server_result_visible="$(awk -v generated="$server_generated" 'BEGIN { printf "%.3f", generated + 0.320 }')"
make_verified_outcome "$GENERATED/parts/04-outcome.mp4" "$SUCCESS_SOURCE" \
  "$(awk -v generated="$server_generated" 'BEGIN { printf "%.3f", generated - 1.0 }')" \
  "$server_result_visible" 0
make_held_source_frame "$GENERATED/parts/04-result-hold.mp4" "$SUCCESS_SOURCE" \
  "$server_result_visible" 3.5
concat_visual 04 \
  "$GENERATED/parts/04-prompt.mp4" \
  "$GENERATED/parts/04-wait-7x.mp4" \
  "$GENERATED/parts/04-outcome.mp4" \
  "$GENERATED/parts/04-result-hold.mp4"

rule_prompt_end="$(awk -v start="$rule_start" 'BEGIN { printf "%.3f", start + 7.0 }')"
make_source_window "$GENERATED/parts/05-prompt.mp4" "$SUCCESS_SOURCE" \
  "$rule_start" 7.000
make_accelerated_wait "$GENERATED/parts/05-wait-6x.mp4" "$SUCCESS_SOURCE" \
  "$rule_prompt_end" "$rule_generated" 6
make_source_window "$GENERATED/parts/05-outcome.mp4" "$SHOWCASE_SOURCE" \
  13.500 5.200
concat_visual 05 \
  "$GENERATED/parts/05-prompt.mp4" \
  "$GENERATED/parts/05-wait-6x.mp4" \
  "$GENERATED/parts/05-outcome.mp4"

library_prompt_end="$(awk -v start="$library_start" 'BEGIN { printf "%.3f", start + 7.0 }')"
tetris_prompt_end="$(awk -v start="$tetris_start" 'BEGIN { printf "%.3f", start + 7.0 }')"
make_source_window "$GENERATED/parts/06-library-prompt.mp4" "$SUCCESS_SOURCE" \
  "$library_start" 7.000
make_accelerated_wait "$GENERATED/parts/06-library-wait-7x.mp4" "$SUCCESS_SOURCE" \
  "$library_prompt_end" "$library_generated" 7
make_source_window "$GENERATED/parts/06-library-outcome.mp4" "$SHOWCASE_SOURCE" \
  1.200 2.800
make_source_window "$GENERATED/parts/06-tetris-prompt.mp4" "$SUCCESS_SOURCE" \
  "$tetris_start" 7.000
make_accelerated_wait "$GENERATED/parts/06-tetris-wait-8x.mp4" "$SUCCESS_SOURCE" \
  "$tetris_prompt_end" "$tetris_generated" 8
make_source_window "$GENERATED/parts/06-tetris-outcome.mp4" "$SHOWCASE_SOURCE" \
  4.000 8.200
concat_visual 06 \
  "$GENERATED/parts/06-library-prompt.mp4" \
  "$GENERATED/parts/06-library-wait-7x.mp4" \
  "$GENERATED/parts/06-library-outcome.mp4" \
  "$GENERATED/parts/06-tetris-prompt.mp4" \
  "$GENERATED/parts/06-tetris-wait-8x.mp4" \
  "$GENERATED/parts/06-tetris-outcome.mp4"

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
