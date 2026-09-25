#!/usr/bin/env bash
# Rewrap Drive Assist's orphaned dash_*.h264 files into ordinary MP4 files.
# Requires FFmpeg. It never changes or deletes the source recordings.
set -euo pipefail

usage() {
  echo "Usage: $0 <clip.h264 | directory> [more clips or directories]" >&2
  echo "Writes <clip>.recovered.mp4 next to each source file." >&2
  exit 2
}

command -v ffmpeg >/dev/null || { echo "ffmpeg is required (https://ffmpeg.org/)" >&2; exit 127; }
[ "$#" -gt 0 ] || usage

recover() {
  local input="$1" stem output
  stem="${input%.h264}"
  output="${stem}.recovered.mp4"
  if [ -e "$output" ]; then
    echo "SKIP: $output already exists" >&2
    return
  fi
  echo "Recovering: $input"
  # DashRecorder is CFR at 25 fps but raw H.264 has no timestamps.  Generate
  # them on input; copy avoids another lossy encode and is fast on any PC.
  ffmpeg -nostdin -hide_banner -loglevel warning -fflags +genpts -r 25 -err_detect ignore_err \
    -i "$input" -map 0:v:0 -c copy -movflags +faststart "$output"
  echo "OK: $output"
}

for target in "$@"; do
  if [ -d "$target" ]; then
    found=0
    while IFS= read -r -d '' clip; do recover "$clip"; found=1; done \
      < <(find "$target" -maxdepth 1 -type f -name '*.h264' -print0)
    [ "$found" = 1 ] || echo "No .h264 files in: $target" >&2
  elif [ -f "$target" ] && [[ "$target" == *.h264 ]]; then
    recover "$target"
  else
    echo "SKIP: not an .h264 file or directory: $target" >&2
  fi
done
