#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"

# SDK 探测：xcrun 失败或返回空都给出可操作诊断，而不是把空值传给 clang -isysroot。
SDK="$(xcrun --show-sdk-path 2>/dev/null || true)"
if [ -z "$SDK" ]; then
  echo "error: 无法定位 macOS SDK；请先运行 'xcode-select --install'" >&2
  exit 1
fi

# Go 引擎目录/包可用环境变量覆盖；先校验目录存在，避免布局重命名后只报泛化的 cd/go 错误。
ENGINE_DIR="${SUSHENG_ENGINE_DIR:-$ROOT/../windows}"
ENGINE_PKG="${SUSHENG_ENGINE_PKG:-./cmd/engineexport}"
if [ ! -d "$ENGINE_DIR" ]; then
  echo "error: 找不到 Go 引擎目录 '$ENGINE_DIR'；请用 SUSHENG_ENGINE_DIR 指定" >&2
  exit 1
fi

# 目标架构：默认构建通用二进制（arm64 + x86_64），使产物在 Apple Silicon 与 Intel Mac 上都能运行；
# 可用 SUSHENG_ARCHES 覆盖（如仅 "arm64"）。Go 的 -buildmode=c-archive 两种架构都支持，
# 分别产出后用 lipo 合并为通用静态库。DEPLOY_TARGET 固定最低部署版本，避免落到 SDK 默认目标。
ARCHES="${SUSHENG_ARCHES:-arm64 amd64}"
DEPLOY_TARGET="${MACOSX_DEPLOYMENT_TARGET:-12.0}"

# 用解析后的绝对路径，避免依赖前面的 cd 而对当前工作目录做递归删除。
APP="$ROOT/苏晟输入法.app"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

CLANG_ARCH_FLAGS=""
GO_LIBS=""
for ga in $ARCHES; do
  case "$ga" in
    amd64) clang_arch="x86_64" ;;
    arm64) clang_arch="arm64" ;;
    *) echo "error: 不支持的架构 '$ga'（请用 arm64 或 amd64）" >&2; exit 1 ;;
  esac
  CLANG_ARCH_FLAGS="$CLANG_ARCH_FLAGS -arch $clang_arch"
  lib="$TMP/libsusheng-$ga.a"
  ( cd "$ENGINE_DIR" && CGO_ENABLED=1 GOARCH="$ga" go build -buildmode=c-archive -o "$lib" "$ENGINE_PKG" )
  GO_LIBS="$GO_LIBS $lib"
done

# 合并各架构静态库为通用库（单架构时 lipo -create 直通）。
UNIVERSAL_LIB="$ROOT/libsusheng.a"
# shellcheck disable=SC2086
lipo -create $GO_LIBS -output "$UNIVERSAL_LIB"

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp Info.plist "$APP/Contents/Info.plist"
cp Resources/base-lexicon.json "$APP/Contents/Resources/base-lexicon.json"
cp Resources/susheng_logo.png "$APP/Contents/Resources/susheng_logo.png"
# shellcheck disable=SC2086
clang -isysroot "$SDK" $CLANG_ARCH_FLAGS -mmacosx-version-min="$DEPLOY_TARGET" \
  -fobjc-arc -framework Cocoa -framework InputMethodKit -framework Carbon \
  -o "$APP/Contents/MacOS/SushengIM" \
  Sources/main.m Sources/Store.m Sources/Home.m Sources/InputController.m "$UNIVERSAL_LIB"

# InputMethodKit 输入法必须签名才能在其他机器上不被 Gatekeeper 拦截；此处做 ad-hoc 签名。
# 正式分发需替换为 Developer ID 签名并用 notarytool 公证。
codesign --force --sign - "$APP"

echo "built 苏晟输入法.app (arch: $ARCHES, min macOS $DEPLOY_TARGET)"
