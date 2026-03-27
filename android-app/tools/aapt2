#!/usr/bin/env bash
set -euo pipefail

GRADLE_HOME_CANDIDATE="${GRADLE_USER_HOME:-/codes/music_worker/.gradle-user}"
AAPT2_MAVEN_PATH="$(find "$GRADLE_HOME_CANDIDATE" -path '*aapt2-8.7.3-12006047-linux/aapt2' -type f 2>/dev/null | head -n 1 || true)"
AAPT2_BUILD_TOOLS_PATH="${ANDROID_SDK_ROOT:-/opt/android-sdk}/build-tools/36.1.0/aapt2"

if [[ -n "$AAPT2_MAVEN_PATH" && -x "$AAPT2_MAVEN_PATH" ]]; then
  exec qemu-x86_64 "$AAPT2_MAVEN_PATH" "$@"
fi

if [[ -x "$AAPT2_BUILD_TOOLS_PATH" ]]; then
  exec qemu-x86_64 "$AAPT2_BUILD_TOOLS_PATH" "$@"
fi

echo "aapt2 executable not found for qemu wrapper" >&2
exit 127
