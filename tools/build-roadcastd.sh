#!/usr/bin/env bash
# Builds roadcastd (third_party/roadcast) from source.
#
#   tools/build-roadcastd.sh            # Android arm64 / API 28, needs an NDK
#   HOST=1 tools/build-roadcastd.sh     # host build with cc, for tests only
#
# Output: build/roadcast/<target>/roadcastd (+ roadcastctl), and for the
# Android target also modehelper/assets/roadcastd, which build-modehelper.sh
# packs into the helper apk.
#
# Why this script and not upstream's build-libuv-android.sh: that one needs
# cmake. We compile libuv's sources directly with the same compiler, so the
# only tool the car build needs is the NDK clang.
#
# libuv (MIT, widely used: Node.js runs on it) is downloaded, not copied into
# this repo: the official v1.51.0 source archive, checked against a fixed
# SHA-256. Any other file is refused, so the build never uses code nobody
# pinned. If GitHub ever regenerates the archive, the build stops with
# "checksum mismatch"; check the new archive and update LIBUV_SHA256.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RC="$ROOT/third_party/roadcast"

LIBUV_VERSION=1.51.0
LIBUV_SHA256=27e55cf7083913bfb6826ca78cde9de7647cded648d35f24163f2d31bb9f51cd
DEPS="$ROOT/build/roadcast/deps"
UV="$DEPS/libuv-$LIBUV_VERSION"

if [ "${HOST:-0}" = 1 ]; then
  TARGET=host
  CC="${CC:-cc}"
  EXTRA_UV_DEFS=(-D_GNU_SOURCE)
  EXTRA_UV_SRC=(src/unix/random-getrandom.c)
  PIE=()
else
  TARGET=android-arm64-api28
  NDK="${ANDROID_NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}}"
  if [ -z "$NDK" ]; then
    for d in "${ANDROID_HOME:-}/ndk" "${ANDROID_SDK:-}/ndk" "${GEELY_TOOLS:-$HOME/dev/geely}/sdk/ndk"; do
      [ -d "$d" ] && NDK="$(ls -d "$d"/*/ 2>/dev/null | sort -V | tail -1)" && NDK="${NDK%/}" && break
    done
  fi
  [ -n "$NDK" ] && [ -d "$NDK" ] || { echo "build-roadcastd: no Android NDK found (set ANDROID_NDK)" >&2; exit 1; }
  CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android28-clang"
  [ -x "$CC" ] || { echo "build-roadcastd: compiler not found: $CC" >&2; exit 1; }
  EXTRA_UV_DEFS=(-D_GNU_SOURCE)
  EXTRA_UV_SRC=(src/unix/random-getentropy.c src/unix/random-getrandom.c)
  PIE=(-fPIE -pie)
fi

OUT="$ROOT/build/roadcast/$TARGET"
mkdir -p "$OUT/uv"

# --- libuv source, pinned by SHA-256 ----------------------------------------
mkdir -p "$DEPS"
if [ ! -f "$UV/include/uv.h" ]; then
  tgz="$DEPS/libuv-$LIBUV_VERSION.tar.gz"
  [ -f "$tgz" ] || curl -fsSL --retry 3 -o "$tgz" \
    "https://github.com/libuv/libuv/archive/refs/tags/v$LIBUV_VERSION.tar.gz"
  got="$(sha256sum "$tgz" | cut -d' ' -f1)"
  [ "$got" = "$LIBUV_SHA256" ] || { echo "build-roadcastd: libuv checksum mismatch ($got)" >&2; rm -f "$tgz"; exit 1; }
  tar -xzf "$tgz" -C "$DEPS"
fi

UV_SRC=(
  src/fs-poll.c src/idna.c src/inet.c src/random.c src/strscpy.c src/strtok.c
  src/thread-common.c src/threadpool.c src/timer.c src/uv-common.c
  src/uv-data-getter-setters.c src/version.c
  src/unix/async.c src/unix/core.c src/unix/dl.c src/unix/fs.c
  src/unix/getaddrinfo.c src/unix/getnameinfo.c src/unix/loop-watcher.c
  src/unix/loop.c src/unix/pipe.c src/unix/poll.c src/unix/process.c
  src/unix/random-devurandom.c src/unix/signal.c src/unix/stream.c
  src/unix/tcp.c src/unix/thread.c src/unix/tty.c src/unix/udp.c
  src/unix/linux.c src/unix/procfs-exepath.c src/unix/random-sysctl-linux.c
  src/unix/proctitle.c "${EXTRA_UV_SRC[@]}"
)
objs=()
for f in "${UV_SRC[@]}"; do
  o="$OUT/uv/$(echo "$f" | tr '/' '_').o"
  "$CC" -O2 -fPIC -std=gnu11 -w "${EXTRA_UV_DEFS[@]}" -D_FILE_OFFSET_BITS=64 -D_LARGEFILE_SOURCE \
    -I"$UV/include" -I"$UV/src" -c "$UV/$f" -o "$o"
  objs+=("$o")
done

# --- roadcast ---------------------------------------------------------------
# The generated catalog (catalog_generated.c + headers) is checked in, so no
# python step is needed here.
FLAGS=(-std=c11 -O2 -Wall -Wextra -Wpedantic -Werror -I"$RC/include")
"$CC" "${FLAGS[@]}" "${PIE[@]}" -I"$UV/include" -o "$OUT/roadcastd" \
  "$RC/src/roadcastd.c" "$RC/src/vhal_source.c" "$RC/src/source_session.c" \
  "$RC/src/protocol.c" "$RC/src/catalog.c" "$RC/src/catalog_generated.c" \
  "${objs[@]}" -pthread -ldl
"$CC" "${FLAGS[@]}" "${PIE[@]}" -o "$OUT/roadcastctl" \
  "$RC/src/roadcastctl.c" "$RC/src/protocol.c"

if [ "$TARGET" != host ]; then
  mkdir -p "$ROOT/modehelper/assets/licenses"
  cp "$OUT/roadcastd" "$ROOT/modehelper/assets/roadcastd"
  # The apk that ships the binary also carries its licenses and notices.
  cp "$RC/LICENSE" "$ROOT/modehelper/assets/licenses/roadcast-LICENSE"
  cp "$RC/NOTICE" "$ROOT/modehelper/assets/licenses/roadcast-NOTICE"
  cp "$UV/LICENSE" "$ROOT/modehelper/assets/licenses/libuv-LICENSE"
  cp "$UV/LICENSE-extra" "$ROOT/modehelper/assets/licenses/libuv-LICENSE-extra"
  cp "$ROOT/THIRD-PARTY-NOTICES.md" "$ROOT/modehelper/assets/licenses/THIRD-PARTY-NOTICES.md"
fi
echo "roadcastd ($TARGET): $OUT/roadcastd"
sha256sum "$OUT/roadcastd"
