#!/bin/sh
# 清掉上次构建，再打可安装的 debug 包到 dist/su-ime-<versionCode>-debug.apk
set -eu

ANDROID_DIR="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
ROOT="$(CDPATH= cd -- "$ANDROID_DIR/.." && pwd)"

STUDIO_JBR="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# 仅当调用方未显式设置 JAVA_HOME 时才回退到 Android Studio 自带 JBR，
# 避免覆盖用户故意配置的 JDK。
if [ -z "${JAVA_HOME:-}" ] && [ -x "$STUDIO_JBR/bin/java" ]; then
  JAVA_HOME="$STUDIO_JBR"
  export JAVA_HOME
fi

if [ -z "${ANDROID_HOME:-}" ] && [ -f "$ANDROID_DIR/local.properties" ]; then
  # 只去 CR/首尾空白，并还原 local.properties 的转义（\: 和 \\）；
  # 不能用 tr -d '\\' 否则会把 Windows 路径的合法分隔符一并删掉。
  SDK_DIR=$(sed -n 's/^sdk\.dir=//p' "$ANDROID_DIR/local.properties" | head -1 | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/\\:/:/g; s/\\\\/\\/g')
  if [ -n "$SDK_DIR" ]; then
    ANDROID_HOME="$SDK_DIR"
    export ANDROID_HOME
  fi
fi

# 解析到的 SDK 目录不存在时给出警告，避免后续构建报难以理解的错误。
if [ -n "${ANDROID_HOME:-}" ] && [ ! -d "$ANDROID_HOME" ]; then
  echo "警告：ANDROID_HOME=$ANDROID_HOME 不存在，构建可能失败。" >&2
fi

GRADLE=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*-bin/*/gradle-*/bin/gradle 2>/dev/null | head -1 || true)
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

# APK 名称与 app/build.gradle.kts 的 archivesName 命名规则重复，可能漂移；
# 先按约定名查找，找不到时用 glob 兜底。
APK="$ANDROID_DIR/app/build/outputs/apk/debug/su-ime-${VERSION_CODE}-debug.apk"
if [ ! -f "$APK" ]; then
  APK=$(ls -t "$ANDROID_DIR"/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)
fi
if [ -z "${APK:-}" ] || [ ! -f "$APK" ]; then
  echo "构建完成，但没有找到 debug APK。" >&2
  exit 1
fi

mkdir -p "$ROOT/dist"
OUT="$ROOT/dist/su-ime-${VERSION_CODE}-debug.apk"
cp "$APK" "$OUT"
echo "$OUT"
