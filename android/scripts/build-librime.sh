#!/bin/sh
# 把 librime 编进 Android。不跑这条时，输入法使用内置拼音引擎。
set -eu
ROOT="$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)"
WORKDIR="${LIBRIME_BUILD:-$ROOT/android/.librime}"

# 依赖预检：缺 git 时尽早报错，而不是在 set -e 深处抛出难以理解的错误。
if ! command -v git >/dev/null 2>&1; then
  echo "找不到 git，请先安装 git 再构建 librime。" >&2
  exit 1
fi

# 固定到一个明确的 tag，保证可复现并降低供应链风险；可用 LIBRIME_REF 覆盖。
LIBRIME_REF="${LIBRIME_REF:-rime-1.3.1}"

mkdir -p "$WORKDIR"
# 只认带 .git 的完整克隆；中断/失败的克隆会留下空目录，需清掉重新拉取。
if [ ! -d "$WORKDIR/librime/.git" ]; then
  rm -rf "$WORKDIR/librime"
  git clone --depth 1 --branch "$LIBRIME_REF" https://github.com/rime/librime.git "$WORKDIR/librime"
fi
echo "librime 源码在 $WORKDIR/librime（ref: $LIBRIME_REF）"
echo "Android 交叉编译还需要 boost、glog、leveldb、yaml-cpp、marisa、opencc。"
echo "准备好之后执行："
echo "  cd \"$ROOT/android\" && ./gradlew :app:assembleDebug -Psusheng.native=true -Plibrime.root=\"$WORKDIR/librime\""
echo "CMake 会包含 rime_api.h 并链接 librime。未就绪时不要打开 susheng.native。"
