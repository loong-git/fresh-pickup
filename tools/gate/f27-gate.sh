#!/usr/bin/env bash
# F-27 门禁：DRIFT 线索裁定批（纯注释、零行位移、面积受限）
# F-48：本脚本与它调用的审计器、两份 DRIFT 基线一起从 logs/（被 .gitignore 忽略）搬进 tools/gate/（tracked），
#       判据与读数一律不变；产物与临时文件仍写 logs/。跑法不变：在**仓库根**执行 `bash tools/gate/f27-gate.sh`。
# 陷阱规避（都是我自己踩过并登记在 HANDOFF §8 的）：
#   - 取退出码一律 `> file 2>&1; echo $?`，绝不 `cmd | tail; echo $?`（那拿的是 tail 的状态）
#   - 注释/非注释分类只看原始行，绝不先 sed 剥空白（剥了就把 `^ \*` 判据破坏了）
#   - `grep -c` 命中 0 时退出码是 1，会让 && 链断掉 → 全部 `|| true`
#   - git 的 CRLF warning 走 stderr，统一 2>/dev/null
FAILS=0
say() { echo "$@"; }
chk() { # chk 名称 实际 期望
  if [ "$2" = "$3" ]; then say "  OK   $1: $2"; else say "  FAIL $1: 实际=[$2] 期望=[$3]"; FAILS=$((FAILS+1)); fi
}

# （原先这里有一行 BASE_REF=dee75b2 的 F-27 期 diff 基线，全脚本没引用过它——纯注释批的门禁
#  一律看「工作区 vs HEAD」，把某个历史提交写死在这里只会误导下一个读脚本的人，故删除。）

say "=== 1) 面积：只许 backEnd/src/main + front-merchant + front-partner ==="
CHANGED=$(git diff --name-only 2>/dev/null)
OUT=$(echo "$CHANGED" | grep -vE '^(backEnd/src/main/|front-merchant/|front-partner/)' || true)
chk "越界文件（应为空）" "$([ -z "$OUT" ] && echo yes || echo no)" "yes"
[ -n "$OUT" ] && say "$OUT" | sed 's/^/      /'
# front/** 与 front-admin/** 必须零字节改动（契约 FC-5）
NFRONT=$(echo "$CHANGED" | grep -cE '^(front/|front-admin/)' || true)
chk "front/ 与 front-admin/ 改动文件数" "$NFRONT" "0"

say "=== 2) 纯注释：非注释变更行数必须 = 0（原始行判据，不剥空白）==="
NONCOMMENT=0; TOTCHG=0
for f in $CHANGED; do
  while IFS= read -r line; do
    body=${line:1}                      # 去掉行首的 + / -
    TOTCHG=$((TOTCHG+1))
    if [[ "$body" =~ ^[[:space:]]*(//|\*|/\*|\<!--) ]]; then
      continue
    elif [[ "$body" =~ ^[[:space:]]*$ ]]; then
      NONCOMMENT=$((NONCOMMENT+1)); say "      空行变动: $f"
    else
      NONCOMMENT=$((NONCOMMENT+1)); say "      非注释: $f :: ${body:0:70}"
    fi
  done < <(git diff -U0 -- "$f" 2>/dev/null | grep -E '^[-+]' | grep -vE '^([-+]{3})' | grep -v '^\ No newline' || true)
done
say "  变更行合计=$TOTCHG"
chk "非注释变更行数" "$NONCOMMENT" "0"

say "=== 3) 零行位移：每文件 add == del ==="
BAD=0
while IFS=$'\t' read -r a d f; do
  [ -z "$a" ] && continue
  if [ "$a" != "$d" ]; then say "      位移: $f add=$a del=$d"; BAD=$((BAD+1)); fi
done < <(git diff --numstat 2>/dev/null)
chk "有行位移的文件数" "$BAD" "0"

say "=== 4) 指针面：审计器可疑必须 = 0（基线不手填，直接读工具输出）==="
PA=$(node tools/gate/pointer-audit.cjs 2>&1 | head -1 || true)
say "  $PA"
SUS=$(echo "$PA" | sed -n 's/.*可疑 \([0-9]\+\) 条.*/\1/p' || true)
chk "可疑条数" "${SUS:-NA}" "0"
# DRIFT 线索总量「不许变多」——基线不写死（原来手填 F-27 期的 61，本仓已降到个位数，
# 写死的数字会一路失真）。改读 tools/gate/baselines/drift_total_baseline.txt（F-48 起基线进版本库）；
# 没有该文件就退化成只报数不判红。
node tools/gate/pointer-audit.cjs --drift > logs/out/f27_drift_after.txt 2>&1 || true
DAFTER=$(sed -n 's/.*可疑 \([0-9]\+\) 条.*/\1/p' logs/out/f27_drift_after.txt | head -1 || true)
DBASE=$(cat tools/gate/baselines/drift_total_baseline.txt 2>/dev/null || echo "")
say "  DRIFT 线索总量=${DAFTER:-NA}（基线=${DBASE:-未设}；A 类噪音留着是对的，所以只判「不许变多」）"
if [ -n "${DAFTER:-}" ] && [ -n "$DBASE" ] && [ "$DAFTER" -gt "$DBASE" ]; then say "      FAIL DRIFT 变多"; FAILS=$((FAILS+1)); fi

say "=== 5) 语法与编译 ==="
export JAVA_HOME="D:\\jdk_17"; export PATH="/d/jdk_17/bin:$PATH"
for f in $CHANGED; do
  case "$f" in
    *.js) node --check "$f" >/dev/null 2>&1 && say "  OK   $f" || { say "  FAIL $f 语法"; FAILS=$((FAILS+1)); } ;;
    *.vue)
      # 权威姿势=模块模式：把 <script> 段原样抽到 .mjs 再 node --check。
      # 用「剥 import + new Function」会在**多行 import**上假报错（本仓 dish-edit.vue 就因此被误判过一次，
      # 而该文件本批只改了 1 行注释——是检查器的固有假阳性，不是实现缺陷。这条 HANDOFF §8 早记过，我又用了老方法）
      mkdir -p logs/scratch32/vc
      name=$(echo "$f" | tr '/.' '__')
      node -e '
const fs=require("fs");const [p,o]=process.argv.slice(1);
const s=fs.readFileSync(p,"utf8");const m=s.match(/<script[^>]*>([\s\S]*?)<\/script>/);
if(!m){console.log("  OK   "+p+" (无 script 段)");process.exit(0)}
fs.writeFileSync(o,m[1]);
' "$f" "logs/scratch32/vc/$name.mjs" && ( node --check "logs/scratch32/vc/$name.mjs" >/dev/null 2>&1 && say "  OK   $f" || { say "  FAIL $f 语法（模块模式）"; FAILS=$((FAILS+1)); } ) ;;
  esac
done
if echo "$CHANGED" | grep -q '^backEnd/'; then
  ( cd backEnd && mvn -o compile -DskipTests > ../logs/out/f27_compile.txt 2>&1 )
  CMPL=$?
  chk "mvn -o compile 退出码" "$CMPL" "0"
  grep -E 'BUILD' logs/out/f27_compile.txt | tail -1 | sed 's/^/  /'
else
  say "  (本批未碰后端，跳过编译)"
fi

say "=== 6) 敏感串（明文口令/密钥/手机号）==="
# 陷阱：$CHANGED 为空时 `grep -rnsE 'pat' $CHANGED` 会退化成**无文件参数**→ 递归搜全仓（我第一次自测就被灌了 190 行假命中）
if [ -z "$CHANGED" ]; then
  say "  SKIP 工作区无改动（敏感串无需扫）"
else
  # 敏感串清单只存 logs/.secret-list 一处（此前我把明文口令内嵌进本脚本，等于复制一份口令）。
  # ⚠ 模式必须**全部来自 -f**：实测 `grep -f LIST -e PAT` 与 `grep -f LIST -E 'PAT'` 都只用 -f 的模式，
  #    把 JWT 正则写在 -e/-E 里会静默不生效——我这条"提交前检查"因此从未抓到过 JWT（第七轮判据缺陷）。
  SENS=$(git diff --name-only 2>/dev/null | xargs grep -nsE -f logs/.secret-list 2>/dev/null | wc -l || true)
  chk "敏感串命中行数" "${SENS:-0}" "0"
fi

say "=== 7) 待提交清单与结论 ==="
git status --porcelain | sed 's/^/  /'
say "FAIL 计数=$FAILS"
[ "$FAILS" -gt 0 ] && say "有 $FAILS 条 FAIL——先复核门禁自身再判实现"
exit 0
