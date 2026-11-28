#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
assets=src/main/assets
mkdir -p "$assets"
base=https://github.com/dscripka/openWakeWord/releases/download/v0.5.1

fetch() {
  local file="$1" expected="$2"
  if [ ! -f "$assets/$file" ]; then
    curl -fL --retry 3 -o "$assets/$file" "$base/$file"
  fi
  printf '%s  %s\n' "$expected" "$assets/$file" | sha256sum -c -
}

fetch melspectrogram.tflite 96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8
fetch embedding_model.tflite c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8
fetch hey_jarvis_v0.1.tflite 14bff778604985e1b5c19f0f7bbe477a69cf281d8db34b232b3b972411f710e2

tools=.model-tools
if [ ! -x "$tools/bin/python" ]; then
  python3 -m venv "$tools"
  "$tools/bin/pip" install --disable-pip-version-check \
    ai-edge-litert==2.2.0 flatbuffers==25.12.19
fi
"$tools/bin/python" freeze-melspectrogram.py \
  "$assets/melspectrogram.tflite" "$assets/melspectrogram_1760.tflite"
printf '%s  %s\n' \
  cbb584e11ba55d374b2f4462caf984805cd8c354168ee62532a848ca4c42aaf4 \
  "$assets/melspectrogram_1760.tflite" | sha256sum -c -
