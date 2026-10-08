#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
SDK="$(xcrun --show-sdk-path)"
cd "$ROOT/../windows"
CGO_ENABLED=1 go build -buildmode=c-archive -o "$ROOT/libsusheng.a" ./cmd/engineexport
cd "$ROOT"
rm -rf "苏晟输入法.app"
mkdir -p "苏晟输入法.app/Contents/MacOS" "苏晟输入法.app/Contents/Resources"
cp Info.plist "苏晟输入法.app/Contents/Info.plist"
cp Resources/base-lexicon.json "苏晟输入法.app/Contents/Resources/base-lexicon.json"
cp Resources/susheng_logo.png "苏晟输入法.app/Contents/Resources/susheng_logo.png"
clang -isysroot "$SDK" -fobjc-arc -framework Cocoa -framework InputMethodKit -framework Carbon \
  -o "苏晟输入法.app/Contents/MacOS/SushengIM" \
  Sources/main.m Sources/Store.m Sources/Home.m Sources/InputController.m libsusheng.a
echo "built 苏晟输入法.app"
