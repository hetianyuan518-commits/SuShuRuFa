#!/bin/sh
# 清掉上次构建，再打可安装的 debug 包到 dist/su-ime-<versionCode>-debug.apk
set -eu

ANDROID_DIR="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ROOT="$(CDPATH= cd -- "$ANDROID_DIR/.." && pwd)"

STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
if [ -x "$STUDIO_JBR/bin/java" ]; then
  JAVA_HOME="$STUDIO_JBR"
  export JAVA_HOME
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_DIR/local.properties" ]; then
  SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$ANDROID_DIR/local.properties" | head -1 | tr -d '\\')
  if [ -n "$SDK_DIR" ]; then
    ANDROID_HOME="$SDK_DIR"
    export ANDROID_HOME
  fi
fi

GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-8.10.2-bin/*/gradle-8.10.2/bin/gradle 2>/dev/null | head -1 || true)
if [ -z "$GRADLE" ] && command -v gradle >/dev/null 2>&1; then
  GRADLE=$(command -v gradle)
fi
if [ -z "${GRADLE:-}" ] || [ ! -x "$GRADLE" ]; then
  echo "找不到 Gradle。请先用 Android Studio 同步一次工程。" >&2
  exit 1
fi

VERSION_CODE=$(sed -n 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$ANDROID_DIR/app/build.gradle.kts" | head -1)
if [ -z "$VERSION_CODE" ]; then
  echo "读不到 app/build.gradle.kts 里的 versionCode。" >&2
  exit 1
fi

cd "$ANDROID_DIR"
"$GRADLE" clean assembleDebug

APK="$ANDROID_DIR/app/build/outputs/apk/debug/su-ime-${VERSION_CODE}-debug.apk"
if [ ! -f "$APK" ]; then
  echo "构建完成，但没有找到 $APK" >&2
  exit 1
fi

mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/su-ime-${VERSION_CODE}-debug.apk"
cp "$APK" "$OUT"
echo "$OUT"
