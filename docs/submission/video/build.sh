#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
VIDEO_DIR="$ROOT/docs/submission/video"
CAPTURE_ROOT="${PPP_PUBLIC_CAPTURE_ROOT:?Set PPP_PUBLIC_CAPTURE_ROOT to the new verified public capture}"
OUTPUT_ROOT="${PPP_VIDEO_OUTPUT_ROOT:-$ROOT/artifacts/submission-video}"
GENERATED="$OUTPUT_ROOT/generated"
FINAL="$OUTPUT_ROOT/final"
TOOLS_ROOT="${PPP_VIDEO_TOOLS:-/tmp/slopbook-video-tools}"
FFMPEG="${FFMPEG:-$TOOLS_ROOT/node_modules/@ffmpeg-installer/linux-x64/ffmpeg}"
FFPROBE="${FFPROBE:-$TOOLS_ROOT/node_modules/ffprobe-static/bin/linux/x64/ffprobe}"
EDGE_TTS="${EDGE_TTS:-$HOME/.local/bin/edge-tts}"
CHROME="${CHROME:-$(command -v google-chrome)}"
OBSERVATIONS="$CAPTURE_ROOT/observations.json"

for tool in "$FFMPEG" "$FFPROBE" "$EDGE_TTS" "$CHROME"; do
  [[ -x "$tool" ]] || { echo "Missing executable: $tool" >&2; exit 1; }
done

CAPTURE_SOURCE="$(find "$CAPTURE_ROOT/playwright" -type f -name video.webm -print | sort | head -n 1)"
SHOWCASE_SOURCE="$(find "$CAPTURE_ROOT/showcase" -type f -name video.webm -print | sort | head -n 1)"

for source in "$OBSERVATIONS" "$CAPTURE_SOURCE" "$SHOWCASE_SOURCE"; do
  [[ -s "$source" ]] || { echo "Missing verified public capture input: $source" >&2; exit 1; }
done

node -e '
  const x=require(process.argv[1]);
  const expected=["PUBLIC-01","PUBLIC-02","PUBLIC-03","PUBLIC-04","PUBLIC-05"];
  if(JSON.stringify(x.records.map(r=>r.scenario))!==JSON.stringify(expected)) process.exit(1);
  if(!x.records.every(r=>r["browser-outcome"]===true &&
      r["client-stage-valid"]===true &&
      r.outcomes["semantic-repair-count"]<=1)) process.exit(1);
' "$OBSERVATIONS" || {
  echo "Public observations do not contain the verified five-step run" >&2
  exit 1
}

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
  "$EDGE_TTS" --voice en-US-BrianMultilingualNeural --rate=+5% \
    --file "$VIDEO_DIR/narration/$scene.txt" \
    --write-media "$GENERATED/audio/$scene.mp3" \
    --write-subtitles "$GENERATED/audio/$scene.srt"
done

make_source_window() {
  local output="$1" source="$2" start="$3" seconds="$4"
  "$FFMPEG" -y -loglevel error -ss "$start" -i "$source" -t "$seconds" \
    -an -vf "scale=1440:900:flags=lanczos,fps=30,format=yuv420p" \
    -c:v libx264 -preset medium -crf 19 -movflags +faststart "$output"
}

make_accelerated_wait() {
  local output="$1" source="$2" start="$3" end="$4" factor="${5:-30}"
  local font="/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
  local source_duration output_duration
  source_duration="$(awk -v start="$start" -v end="$end" 'BEGIN { d=end-start; printf "%.3f", d > 0.3 ? d : 0.3 }')"
  output_duration="$(awk -v source="$source_duration" -v factor="$factor" 'BEGIN { printf "%.3f", source / factor }')"
  "$FFMPEG" -y -loglevel error -ss "$start" -i "$source" -t "$source_duration" -an \
    -vf "setpts=(PTS-STARTPTS)/${factor},scale=1440:900:flags=lanczos,fps=30,drawbox=x=30:y=30:w=148:h=42:color=0x101310@0.84:t=fill,drawtext=fontfile=$font:text='${factor}x wait':fontcolor=white:fontsize=18:x=48:y=41,format=yuv420p" \
    -t "$output_duration" -c:v libx264 -preset medium -crf 19 \
    -movflags +faststart "$output"
}

make_outcome() {
  local output="$1" source="$2" start="$3" end="$4" hold="${5:-2.0}"
  local seconds
  seconds="$(awk -v start="$start" -v end="$end" 'BEGIN { d=end-start; printf "%.3f", d > 0.5 ? d : 0.5 }')"
  "$FFMPEG" -y -loglevel error -ss "$start" -i "$source" -t "$seconds" -an \
    -vf "scale=1440:900:flags=lanczos,fps=30,tpad=stop_mode=clone:stop_duration=$hold,format=yuv420p" \
    -c:v libx264 -preset medium -crf 19 -movflags +faststart "$output"
}

concat_visual() {
  local scene="$1"; shift
  local list="$GENERATED/parts/$scene.concat.txt"
  : > "$list"
  for source in "$@"; do printf "file '%s'\n" "$source" >> "$list"; done
  "$FFMPEG" -y -loglevel error -f concat -safe 0 -i "$list" \
    -c copy -movflags +faststart "$GENERATED/visual/$scene.mp4"
}

scenario_segment() {
  local prefix="$1" scenario="$2" prompt_seconds="$3" hold="$4"
  local started generated verified prompt_end outcome_start
  started="$(marker "$scenario" scenario-start-ms)"
  generated="$(marker "$scenario" generation-complete-ms)"
  verified="$(marker "$scenario" verification-complete-ms)"
  prompt_end="$(awk -v start="$started" -v d="$prompt_seconds" 'BEGIN { printf "%.3f", start+d }')"
  outcome_start="$(awk -v generated="$generated" 'BEGIN { s=generated-0.6; printf "%.3f", s > 0 ? s : 0 }')"
  make_source_window "$GENERATED/parts/$prefix-prompt.mp4" "$CAPTURE_SOURCE" "$started" "$prompt_seconds"
  make_accelerated_wait "$GENERATED/parts/$prefix-wait.mp4" "$CAPTURE_SOURCE" "$prompt_end" "$generated" 30
  make_outcome "$GENERATED/parts/$prefix-outcome.mp4" "$CAPTURE_SOURCE" "$outcome_start" "$verified" "$hold"
}

scenario_segment 03-theme PUBLIC-01 4.0 1.5
scenario_segment 03-snake PUBLIC-02 4.0 2.0
concat_visual 03 \
  "$GENERATED/parts/03-theme-prompt.mp4" \
  "$GENERATED/parts/03-theme-wait.mp4" \
  "$GENERATED/parts/03-theme-outcome.mp4" \
  "$GENERATED/parts/03-snake-prompt.mp4" \
  "$GENERATED/parts/03-snake-wait.mp4" \
  "$GENERATED/parts/03-snake-outcome.mp4"

scenario_segment 04-auth PUBLIC-03 4.5 2.5
concat_visual 04 \
  "$GENERATED/parts/04-auth-prompt.mp4" \
  "$GENERATED/parts/04-auth-wait.mp4" \
  "$GENERATED/parts/04-auth-outcome.mp4"

scenario_segment 05-account PUBLIC-04 4.5 1.5
concat_visual 05 \
  "$GENERATED/parts/05-account-prompt.mp4" \
  "$GENERATED/parts/05-account-wait.mp4" \
  "$GENERATED/parts/05-account-outcome.mp4"

scenario_segment 06-library PUBLIC-05 4.5 1.5
showcase_duration="$(duration "$SHOWCASE_SOURCE")"
make_source_window "$GENERATED/parts/06-showcase.mp4" "$SHOWCASE_SOURCE" 0 "$showcase_duration"
concat_visual 06 \
  "$GENERATED/parts/06-library-prompt.mp4" \
  "$GENERATED/parts/06-library-wait.mp4" \
  "$GENERATED/parts/06-library-outcome.mp4" \
  "$GENERATED/parts/06-showcase.mp4"

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
    -i "$GENERATED/visual/$scene.mp4" -i "$GENERATED/audio/$scene.mp3" \
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

"$FFMPEG" -y -loglevel error -i "$GENERATED/ppp-captioned.mp4" \
  -i "$GENERATED/ppp-build-week-demo.en.srt" \
  -map 0:v:0 -map 0:a:0 -map 1:0 -c:v copy -c:a copy -c:s mov_text \
  -metadata:s:s:0 language=eng -movflags +faststart \
  "$FINAL/ppp-build-week-demo.mp4"

cp "$GENERATED/ppp-build-week-demo.en.srt" "$FINAL/ppp-build-week-demo.en.srt"

final_duration="$(duration "$FINAL/ppp-build-week-demo.mp4")"
video_codec="$($FFPROBE -v error -select_streams v:0 -show_entries stream=codec_name -of default=noprint_wrappers=1:nokey=1 "$FINAL/ppp-build-week-demo.mp4")"
audio_codec="$($FFPROBE -v error -select_streams a:0 -show_entries stream=codec_name -of default=noprint_wrappers=1:nokey=1 "$FINAL/ppp-build-week-demo.mp4")"
subtitle_codec="$($FFPROBE -v error -select_streams s:0 -show_entries stream=codec_name -of default=noprint_wrappers=1:nokey=1 "$FINAL/ppp-build-week-demo.mp4")"
dimensions="$($FFPROBE -v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 "$FINAL/ppp-build-week-demo.mp4")"
fps="$($FFPROBE -v error -select_streams v:0 -show_entries stream=r_frame_rate -of default=noprint_wrappers=1:nokey=1 "$FINAL/ppp-build-week-demo.mp4")"

awk -v duration="$final_duration" 'BEGIN { exit !(duration > 120 && duration < 180) }' || {
  echo "Final duration is outside the 120-180 second contract: $final_duration" >&2
  exit 1
}

if [[ "$video_codec" != "h264" || "$audio_codec" != "aac" || \
      "$subtitle_codec" != "mov_text" || "$dimensions" != "1440x900" || \
      "$fps" != "30/1" || ! -s "$FINAL/ppp-build-week-demo.en.srt" ]]; then
  echo "Final media stream contract failed" >&2
  exit 1
fi

"$FFMPEG" -y -loglevel error -i "$FINAL/ppp-build-week-demo.mp4" -f null -
"$FFMPEG" -y -loglevel error -i "$FINAL/ppp-build-week-demo.mp4" \
  -vf "fps=1/15,scale=360:225:flags=lanczos,tile=4x3" -frames:v 1 \
  "$FINAL/contact-sheet.png"

for index in $(seq 1 8); do
  second="$(awk -v d="$final_duration" -v i="$index" 'BEGIN { printf "%.3f", d*i/9 }')"
  "$FFMPEG" -y -loglevel error -ss "$second" -i "$FINAL/ppp-build-week-demo.mp4" \
    -frames:v 1 "$FINAL/frame-$(printf '%02d' "$index").png"
done

"$FFPROBE" -v error -show_entries format=duration,size,bit_rate \
  -show_entries stream=index,codec_name,codec_type,width,height,r_frame_rate:stream_tags=language \
  -of json "$FINAL/ppp-build-week-demo.mp4" | tee "$FINAL/probe.json"

printf 'Final video: %s\n' "$FINAL/ppp-build-week-demo.mp4"
printf 'English SRT: %s\n' "$FINAL/ppp-build-week-demo.en.srt"
printf 'Duration: %s seconds\n' "$final_duration"
