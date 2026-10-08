#!/bin/sh
# 把 librime 编进 Android。不跑这条时，输入法使用内置拼音引擎。
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
WORKDIR="${LIBRIME_BUILD:-$ROOT/android/.librime}"
mkdir -p "$WORKDIR"
if [ ! -d "$WORKDIR/librime" ]; then
  git clone --depth 1 https://github.com/rime/librime.git "$WORKDIR/librime"
fi
echo "librime 源码在 $WORKDIR/librime"
echo "Android 交叉编译还需要 boost、glog、leveldb、yaml-cpp、marisa、opencc。"
echo "准备好之后执行："
echo "  cd $ROOT/android && ./gradlew :app:assembleDebug -Psusheng.native=true -Plibrime.root=$WORKDIR/librime"
echo "CMake 会包含 rime_api.h 并链接 librime。未就绪时不要打开 susheng.native。"
