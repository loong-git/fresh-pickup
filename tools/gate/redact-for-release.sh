#!/usr/bin/env bash
# 发布前脱敏（默认 dry-run，只列不改；--apply 才动文件）。
# 三条设计决定（都是实测逼出来的）：
#  1) 敏感串清单**只存一处**：logs/.secret-list（grep -f 读）。此前我把明文口令内嵌进 f27-gate.sh /
#     f28-gate.sh / 本脚本，等于把口令抄了四份——dry-run 自扫时这三个脚本都命中了，属于工具自伤。
#  2) **只脱敏数据文件**（.log/.json/.txt/.md）。代码类（.sh/.cjs/.js）里的命中是"判据需要能匹配明文"，
#     把它们改掉会直接废掉门禁；它们该走"发布时排除"，不是"就地脱敏"。
#  3) **现役验收资产（PT_* 与 out/tok/）绝不改写**——logs/cdp-pc.cjs 与 F-19/F-25 门禁脚本正在读它们，
#     就地替换会让浏览器复验静默失效（表现为"token 长度 9"那类假故障）。
set -u
APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1
cd "$(dirname "$0")/.." || exit 1
LIST=logs/.secret-list
[ -f "$LIST" ] || { echo "缺清单 $LIST"; exit 1; }
JWT_RE='eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}'
LIVE='logs/PT_ADMIN logs/PT_MERCHANT logs/PT_NONE logs/PT_OWNER logs/PT_PARTNER logs/out/tok'

# 类 A：数据文件（排除清单自身、代码类、以及现役资产）
ALL=$(grep -rlsE -f "$LIST" logs/ 2>/dev/null | grep -vE '\.(sh|cjs|js|ts)$' | grep -v 'secret-list')
CLASSA=$(while read -r f; do
  [ -n "$f" ] || continue
  skip=0
  for live in $LIVE; do case "$f" in "$live"*) skip=1;; esac; done
  [ "$skip" = 0 ] && echo "$f"
done <<< "$ALL")

echo "=== 类 A：可就地脱敏的数据留证 ==="
echo "$CLASSA" | grep -c . | sed 's/^/  文件数: /'
echo "$CLASSA" | while read -r f; do
  [ -n "$f" ] || continue
  n=$(grep -cE -f "$LIST" "$f" </dev/null 2>/dev/null || true)
  echo "    $f  命中行=$n"
done
echo "=== 类 B：现役验收资产（只登记，绝不改写）==="
for live in $LIVE; do [ -e "$live" ] && echo "    $live"; done
echo "=== 类 C：判据代码，命中是设计使然（发布时整目录排除，不许就地改）==="
grep -rls -f "$LIST" logs/*.sh logs/*.cjs 2>/dev/null | sed 's/^/    /'
echo "    $LIST  ← 明文的唯一存放处"

[ "$APPLY" = 0 ] && { echo "=== DRY-RUN：未改动任何文件。真要脱敏加 --apply ==="; exit 0; }
echo "=== APPLY ==="
sed -i -E "s/$JWT_RE/JWT_REDACTED/g" $CLASSA 2>/dev/null
echo "$CLASSA" | while read -r f; do [ -n "$f" ] && sed -i -f <(awk '{print "s/" $0 "/REDACTED/g"}' "$LIST") "$f" 2>/dev/null && echo "    已脱敏 $f"; done
echo "=== 复扫类 A（应为 0）==="
grep -rcsE -f "$LIST" $(echo "$CLASSA" | tr '\n' ' ') 2>/dev/null | grep -v ':0$' | wc -l
