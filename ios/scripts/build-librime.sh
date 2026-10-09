#!/bin/sh
# 编译 librime.xcframework 后，把 Headers 加进 HEADER_SEARCH_PATHS，并把库链进两个 target。
set -eu
echo "参考仓输入法的 LibrimeKit：用 GitHub Actions 编好的 librime.xcframework。"
echo "授权是 BSD-3 的 librime 本体，不要把仓输入法的界面代码打进包里。"
echo "放好 framework 之后："
echo "  1. 把 rime_api.h 所在目录加到 HEADER_SEARCH_PATHS"
echo "  2. Link Binary With Libraries 加入 librime"
echo "  3. 键盘会优先合并 librime 候选；官方话术仍由内置引擎插到前面"

# 授权校验：librime 本体为 BSD-3，参考仓「仓输入法」的界面代码不得打进产物。
# 仅靠人工自律会让授权违规悄悄发布，故对已编好的产物做结构性校验：
# 通过 SUSHENG_RIME_FRAMEWORK 指定 xcframework 路径时，校验其确为 librime（含 rime_api.h），
# 且不含仓输入法界面代码/资源的标记；无法校验时明确失败，而不是静默放行。
verify_framework() {
  fw="${SUSHENG_RIME_FRAMEWORK:-}"
  if [ -z "$fw" ]; then
    echo "提示：未设置 SUSHENG_RIME_FRAMEWORK，无法自动校验产物授权；手动集成时请自行确认只含 BSD-3 的 librime 本体。" >&2
    return 1
  fi
  if [ ! -e "$fw" ]; then
    echo "错误：SUSHENG_RIME_FRAMEWORK 指向的路径不存在：$fw" >&2
    return 1
  fi
  if ! find "$fw" -name 'rime_api.h' | grep -q .; then
    echo "错误：$fw 中找不到 rime_api.h，可能不是 librime 产物。" >&2
    return 1
  fi
  # 仓输入法界面代码/资源的常见标记（Hamster / 仓 的 UI 前缀与资源名）；命中即判定违规。
  if find "$fw" \( -iname '*hamster*' -o -iname '*HamsterKit*' -o -iname '*仓输入法*' -o -iname '*fcitx5-android*' \) | grep -q .; then
    echo "错误：$fw 含疑似仓输入法界面代码/资源，违反「仅打包 BSD-3 librime 本体」的授权约束。" >&2
    return 1
  fi
  echo "已通过 librime 产物授权校验：$fw" >&2
  return 0
}

# 本脚本不编译/链接 librime.xcframework；仅在提供了产物时对其做授权校验。
# 未提供产物（或校验失败）时以非零状态退出，避免被误当成构建步骤时“静默成功”而无任何产物。
if verify_framework; then
  echo "librime.xcframework 校验通过。仍需手动完成上面的 HEADER_SEARCH_PATHS 与链接步骤。"
  exit 0
fi
echo "错误：本脚本不执行构建；请按上面的步骤手动集成 librime.xcframework，并用 SUSHENG_RIME_FRAMEWORK 指定产物以通过授权校验。" >&2
exit 1
