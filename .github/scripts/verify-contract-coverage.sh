#!/usr/bin/env bash
#
# 校验 API 兼容性检查真的在每个该跑的模块上执行过。
#
# 背景：2026-11-05 发布 0.1.15 时发现 ai-lite-llm / ai-lite-agent 的 pom 里
# 一直写着 <skip>true</skip>（理由是「首次发版 0.1.8 没有 0.1.7 基线」），
# 基线早已前移却没人撤掉这条豁免。这两个模块装着全部公开 API，等于那道检查
# 空跑了很多轮，构建照样 BUILD SUCCESS。
#
# 判定方式：japicmp 每比对一个模块都会写 target/japicmp/api-compatibility-check.diff，
# 首行是「Comparing binary compatibility of <new> against <old>」。模块被 skip 时
# 不产生这个文件，所以「构建了但没有 diff」就是漏检。
#
# 两种情况不算漏检，分别处理：
#   1. 本次根本没构建（没有 target/）：典型是 JDK 21 专用模块在 JDK 8 构建下
#      不参与 reactor。打印出来提醒，不计为失败。
#   2. 显式豁免：每项都要有站得住的理由，不要为了让构建变绿而加。
#
# 豁免名单：
#   ai-lite-core              无源码的聚合坐标，jar 里只有 META-INF，没有可比对的公开类
#   ai-lite-demo              可运行示例，从未发布到 Central，取不到基线制品
#   ai-lite-executor-jdk21    只在 JDK 21+ 构建，从未发布到 Central，无基线
#   ai-lite-http-okhttp-jdk21 同上

set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

exempt="ai-lite-core ai-lite-demo ai-lite-executor-jdk21 ai-lite-http-okhttp-jdk21"
baseline="$(sed -n 's/.*<ai-lite\.release\.version>\([^<]*\)<.*/\1/p' pom.xml | head -1)"

if [ -z "$baseline" ]; then
  echo "无法从 pom.xml 读取 ai-lite.release.version" >&2
  exit 1
fi

missing=""
not_built=""
checked=0

for mod in ai-lite-*; do
  [ -d "$mod/src/main/java" ] || continue
  sources=$(find "$mod/src/main/java" -name "*.java" | wc -l | tr -d ' ')
  [ "$sources" -gt 0 ] || continue

  if [ ! -d "$mod/target" ]; then
    not_built="$not_built $mod"
    continue
  fi

  case " $exempt " in
    *" $mod "*)
      continue
      ;;
  esac

  diff="$mod/target/japicmp/api-compatibility-check.diff"
  if [ ! -f "$diff" ]; then
    missing="$missing\n  $mod（已构建但没有比对结果，检查被跳过了）"
    continue
  fi
  if ! head -1 "$diff" | grep -q "Comparing binary compatibility"; then
    missing="$missing\n  $mod（比对结果格式异常）"
    continue
  fi
  if ! head -1 "$diff" | grep -q "$baseline"; then
    missing="$missing\n  $mod（基线不是 $baseline）"
    continue
  fi
  checked=$((checked + 1))
done

echo "API 兼容性检查覆盖：$checked 个模块，基线 $baseline"

if [ -n "$not_built" ]; then
  echo "本次未构建（不计入覆盖）：$not_built"
fi

if [ -n "$missing" ]; then
  printf '以下模块应当比对但没有有效结果：%b\n' "$missing" >&2
  exit 1
fi

echo "覆盖校验通过。"
