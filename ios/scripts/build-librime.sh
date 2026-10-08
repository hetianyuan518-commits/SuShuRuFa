#!/bin/sh
# 编译 librime.xcframework 后，把 Headers 加进 HEADER_SEARCH_PATHS，并把库链进两个 target。
set -eu
echo "参考仓输入法的 LibrimeKit：用 GitHub Actions 编好的 librime.xcframework。"
echo "授权是 BSD-3 的 librime 本体，不要把仓输入法的界面代码打进包里。"
echo "放好 framework 之后："
echo "  1. 把 rime_api.h 所在目录加到 HEADER_SEARCH_PATHS"
echo "  2. Link Binary With Libraries 加入 librime"
echo "  3. 键盘会优先合并 librime 候选；官方话术仍由内置引擎插到前面"
