#!/usr/bin/env bash

set -e

cd "$(dirname "$0")"

MODE="${1:-debug}"
SDK_DIR=""
TASK=""
OUTPUT_PATTERN=""
INSTALL_RELEASE=0

log() {
  echo "[PacilRead] $*"
}

fail() {
  echo "[PacilRead] $*" >&2
  exit 1
}

ensure_java() {
  if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    log "Using JAVA_HOME: $JAVA_HOME"
    return
  fi

  if JAVA_17_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null); then
    export JAVA_HOME="$JAVA_17_HOME"
    export PATH="$JAVA_HOME/bin:$PATH"
    log "Detected JDK 17: $JAVA_HOME"
    return
  fi

  if command -v java >/dev/null 2>&1; then
    log "JAVA_HOME not set. Falling back to java from PATH."
    return
  fi

  fail "JDK 17 not found. Please install JDK 17 or set JAVA_HOME."
}

load_sdk_from_local_properties() {
  [[ -f local.properties ]] || return 1

  SDK_DIR=$(
    sed -n 's/^sdk\.dir=//p' local.properties |
      head -n 1
  )

  [[ -n "$SDK_DIR" ]] || fail "local.properties exists but sdk.dir is missing."

  # Handle escaped values if local.properties was copied from another environment.
  SDK_DIR="${SDK_DIR//\\\\/\\}"

  return 0
}

ensure_sdk() {
  if load_sdk_from_local_properties; then
    log "Using existing local.properties"
  else
    if [[ -n "${ANDROID_HOME:-}" ]]; then
      SDK_DIR="$ANDROID_HOME"
    elif [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
      SDK_DIR="$ANDROID_SDK_ROOT"
    elif [[ -d "$HOME/Library/Android/sdk" ]]; then
      SDK_DIR="$HOME/Library/Android/sdk"
    else
      fail "Android SDK not found. Set ANDROID_HOME or create local.properties."
    fi

    printf 'sdk.dir=%s\n' "$SDK_DIR" > local.properties

    log "Detected Android SDK: $SDK_DIR"
    log "Generated local.properties automatically."
  fi

  [[ -d "$SDK_DIR" ]] || fail "Android SDK directory does not exist: $SDK_DIR"

  export ANDROID_HOME="$SDK_DIR"
  export ANDROID_SDK_ROOT="$SDK_DIR"
  export PATH="$SDK_DIR/platform-tools:$SDK_DIR/cmdline-tools/latest/bin:$PATH"
}

find_latest() {
  local pattern="$1"
  local latest=""

  while IFS= read -r file; do
    latest="$file"
    break
  done < <(
    find "$(dirname "$pattern")" \
      -maxdepth 1 \
      -type f \
      -name "$(basename "$pattern")" \
      -print0 2>/dev/null |
      xargs -0 ls -t 2>/dev/null || true
  )

  [[ -n "$latest" ]] || return 1

  printf '%s\n' "$latest"
}

preflight_install() {
  local adb="$SDK_DIR/platform-tools/adb"

  [[ -x "$adb" ]] || \
    fail "adb not found under $SDK_DIR/platform-tools. Install Android SDK Platform-Tools first."

  "$adb" start-server >/dev/null 2>&1 || true

  local online=""
  local unauthorized=""
  local offline=""

  while read -r serial state rest; do
    [[ -z "${serial:-}" ]] && continue
    [[ "$serial" == "List" ]] && continue

    case "${state:-}" in
      device)
        online="$serial"
        ;;
      unauthorized)
        unauthorized="$serial"
        ;;
      offline)
        offline="$serial"
        ;;
    esac
  done < <("$adb" devices)

  if [[ -n "$online" ]]; then
    log "Android device online: $online"
    return
  fi

  echo

  if [[ -n "$unauthorized" ]]; then
    fail "Device $unauthorized is connected but not authorized. Unlock the phone and allow USB debugging."
  fi

  if [[ -n "$offline" ]]; then
    fail "Device $offline is offline. Replug the cable or restart adb."
  fi

  fail "No online Android device found."
}

open_output() {
  local target="$1"

  [[ -e "$target" ]] || return

  if [[ -d "$target" ]]; then
    open "$target"
  else
    open -R "$target"
  fi
}

case "$MODE" in
  debug)
    TASK="assembleDebug"
    OUTPUT_PATTERN="app/build/outputs/apk/debug/PacilRead-v*-debug.apk"
    ;;

  release)
    TASK="assembleRelease"
    OUTPUT_PATTERN="app/build/outputs/apk/release/PacilRead-v*-release.apk"
    ;;

  bundle)
    TASK="bundleRelease"
    OUTPUT_PATTERN="app/build/outputs/bundle/release/*.aab"
    ;;

  install)
    TASK="installDebug"
    ;;

  install-release|release-install)
    TASK="assembleRelease"
    OUTPUT_PATTERN="app/build/outputs/apk/release/PacilRead-v*-release.apk"
    INSTALL_RELEASE=1
    ;;

  clean)
    TASK="clean"
    ;;

  *)
    cat <<USAGE
Usage:
  ./pack.sh debug            (default, builds debug APK)
  ./pack.sh release          (builds signed release APK)
  ./pack.sh bundle           (builds release AAB)
  ./pack.sh install          (builds and installs debug APK)
  ./pack.sh install-release  (builds and installs signed release APK)
  ./pack.sh clean            (clears build outputs)
USAGE
    exit 1
    ;;
esac

ensure_sdk
ensure_java

if [[ "$MODE" == "install" || "$INSTALL_RELEASE" -eq 1 ]]; then
  preflight_install
fi

[[ -x ./gradlew ]] || chmod +x ./gradlew

echo
log "Running Gradle task: $TASK"

./gradlew "$TASK" --no-daemon --console plain

OUTPUT=""

if [[ -n "$OUTPUT_PATTERN" ]]; then
  OUTPUT="$(find_latest "$OUTPUT_PATTERN" || true)"

  [[ -n "$OUTPUT" ]] || fail "Build completed but output not found: $OUTPUT_PATTERN"
fi

if [[ "$INSTALL_RELEASE" -eq 1 ]]; then
  ADB="$SDK_DIR/platform-tools/adb"

  echo
  log "Installing release APK: $OUTPUT"

  "$ADB" install -r "$OUTPUT"
fi

echo
log "Done: $TASK"

case "$MODE" in
  install)
    log "Output: installed-to-device"
    ;;

  clean)
    log "Output: build artifacts cleared"
    ;;

  *)
    log "Output: $OUTPUT"
    open_output "$OUTPUT"
    ;;
esac
