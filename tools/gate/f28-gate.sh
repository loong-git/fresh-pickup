#!/usr/bin/env bash
# F-28 修复批门禁（通用回归面）——与 f27-gate.sh 的区别：这条盯"行为没被改坏"，不盯"纯注释"
# F-48：本脚本与它调用的四个审计器 + 两份 DRIFT 基线一起从 logs/（被 .gitignore 忽略，等于不存在）
#       搬进 tools/gate/（tracked）。跑法：在**仓库根**执行 `bash tools/gate/f28-gate.sh`；
#       产物与临时文件仍写 logs/（忽略区，正确）。§1 可用 SKIP_MVN=1 跳过 mvn（并发/不需要时）。
# F-87：§2 可用 SKIP_SMOKE=1 跳过 smoke——它每次会给基线号发一次验证码，而该号 24h 总量只有 100 次
#       （F-65 按用户决定收的），门禁跑一次就烧掉用户手动走查的一次额度。**两个 SKIP 都会在结论区
#       显式列出**：跳过必须留下痕迹，不能让"跳过"因为看起来和跑过一样而变成默认动作。
# 陷阱规避（HANDOFF §8 逐条对应）：
#   退出码一律 `> file 2>&1; echo $?`，绝不接管道
#   grep -c 命中 0 会退出 1 → 全部 || true
#   基线绝不手填，全部现场从 HEAD 或活体请求现算
#   中文不经控制台往返：期望串走文件
FAILS=0
SKIPPED=""
say() { echo "$@"; }
chk() { if [ "$2" = "$3" ]; then say "  OK   $1: $2"; else say "  FAIL $1: 实际=[$2] 期望=[$3]"; FAILS=$((FAILS+1)); fi; }

say "=== 1) 后端全量测试（离线，154 条基线，F-65 后）==="
if [ -n "${SKIP_MVN:-}" ]; then
  say "  SKIP 本次显式跳过 mvn（例如与编辑类代理并发时：两个 mvn 同写一个 target/ 会互相破坏）"
  SKIPPED="$SKIPPED §1(mvn)"
else
export JAVA_HOME="D:\\jdk_17"; export PATH="/d/jdk_17/bin:$PATH"
( cd backEnd && mvn -o test > ../logs/out/f28_test.txt 2>&1 ); T_EXIT=$?
chk "mvn -o test 退出码" "$T_EXIT" "0"
SUM=$(grep -E '^\[INFO\] Tests run: [0-9]+, Failures' logs/out/f28_test.txt | tail -1 || true)
say "  $SUM"
TR=$(sed -n 's/^\[INFO\] Tests run: \([0-9]\+\), Failures.*/\1/p' logs/out/f28_test.txt | tail -1 || true)
TF=$(sed -n 's/^\[INFO\] Tests run: [0-9]\+, Failures: \([0-9]\+\), Errors: \([0-9]\+\).*/\1+\2/p' logs/out/f28_test.txt | tail -1 || true)
chk "测试条数不低于 87" "$([ "${TR:-0}" -ge 87 ] && echo yes || echo no)" "yes"
chk "Failures+Errors" "$( [ "$TF" = "0+0" ] && echo 0 || echo nonzero )" "0"
fi

say "=== 2) C 端全链路冒烟（脚本在 scripts/，不在 logs/）==="
if [ -n "${SKIP_SMOKE:-}" ]; then
  say "  SKIP 本次显式跳过 smoke：它每跑一次就给基线号发一次验证码，而该号 24h 总量只有 100 次（F-65 收的），"
  say "       门禁跑一次就吃掉用户手动走查的一次额度。代价写在结论区：跳过即本批**未经 C 端全链路证明**，"
  say "       提交前必须由主会话不带本开关整跑一次"
  SKIPPED="$SKIPPED §2(smoke)"
else
( bash scripts/smoke.sh > logs/out/f28_smoke.txt 2>&1 ); S_EXIT=$?
chk "smoke.sh 退出码" "$S_EXIT" "0"
tail -1 logs/out/f28_smoke.txt | sed 's/^/  /'
fi

say "=== 3) 统一信封活体基线（HTTP 恒 200 + 401 不带 data 载荷）==="
BASE=http://127.0.0.1:3001
curl -s -m 8 -o logs/out/f28_now_401.json -w "" "$BASE/api/merchant/dishes"
curl -s -m 8 -o logs/out/f28_now_v2.json -w "" "$BASE/api/admin/v2/orders?page=1&size=1"
for pair in "f28_base_401:f28_now_401" "f28_v2_probe:f28_now_v2"; do
  ref=${pair%%:*}; now=${pair##*:}
  # 基线本身入库（F-48：数据依赖留在 logs/ 就等于 clone 出来后这条判据不存在）
  rb=$(grep -c '' "tools/gate/baselines/$ref.json" 2>/dev/null || true)
  [ -f "tools/gate/baselines/$ref.json" ] || { say "      FAIL 缺基线文件 tools/gate/baselines/$ref.json（判据空转=没有判据，必须现取再比）"; FAILS=$((FAILS+1)); continue; }
  same=$(cmp -s "tools/gate/baselines/$ref.json" "logs/out/$now.json" && echo yes || echo no)
  size=$(grep -c '' "logs/out/$now.json" 2>/dev/null || true)
  say "  $now vs $ref : 逐字节相同=$same (行数 ref=${rb:-NA} now=${size:-NA})"
  [ "$same" = "yes" ] || { say "      FAIL 出参形状变了（要人判是缺陷还是基线过期；确认是后者才 cp logs/out/$now.json tools/gate/baselines/$ref.json）"; FAILS=$((FAILS+1)); }
done
C401=$(grep -o '"code":401' logs/out/f28_now_401.json | head -1 || true)
chk "无凭证商家端仍是 401" "${C401:-none}" '"code":401'
NUL=$(grep -c '"data":null' logs/out/f28_now_401.json || true)
chk "401 仍显式带 data:null（不是缺键）" "$NUL" "1"

say "=== 4) L-16 静态终验：非注释行里不许出现带具体时长的可见文案 ==="
L16=$(node tools/gate/probes/l16-final-scan.cjs 2>&1 | head -1 || true)
say "  $L16"
HITS=$(sed -n 's/.*命中「带具体时长的可见文案」\([0-9]\+\) 处.*/\1/p' <<<"$L16" || true)
chk "命中处数" "${HITS:-NA}" "0"
# 反向自证：判据必须能红（拿 F-23-fix 改前的真实代码当 fixture）
mkdir -p logs/scratch32/fixture
git show f7cea7c:front-partner/pages/apply/apply.vue > logs/scratch32/fixture/apply.vue 2>/dev/null
MUT=$(node tools/gate/probes/l16-final-scan.cjs logs/scratch32/fixture 2>&1 | head -1 || true)
say "  变异自验（改前代码必须命中）: $MUT"
echo "$MUT" | grep -q '命中「带具体时长的可见文案」[1-9]' || { say "      FAIL 判据失去杀伤力，0 命中不可信"; FAILS=$((FAILS+1)); }

say "=== 5) 指针面与取值层份数（F-23/F-25 的成果不许回退）==="
PA=$(node tools/gate/pointer-audit.cjs 2>&1 | head -1 || true)
SUS=$(sed -n 's/.*可疑 \([0-9]\+\) 条.*/\1/p' <<<"$PA" || true)
say "  $PA"
chk "跨文件指针可疑条数" "${SUS:-NA}" "0"
PM=$(grep -rn 'function retrySecondsOf' front-partner --include=*.js --include=*.vue 2>/dev/null | wc -l || true)
chk "front-partner 内 retrySecondsOf 定义份数" "$PM" "1"
FM=$(grep -rn 'function retrySecondsOf' front-merchant --include=*.js --include=*.vue 2>/dev/null | wc -l || true)
chk "front-merchant 内 retrySecondsOf 定义份数" "$FM" "1"
IMP=$(grep -rln 'retrySecondsOf' front-partner/pages 2>/dev/null | wc -l || true)
chk "主理人端引用它的页面数" "$IMP" "6"

say "=== 5b) 具名引用面：symbol-audit（F-30 建的审计器，F-48 才接上门禁）==="
# 为什么这一节必须存在：F-26/27/31/34/35/41 六批把行号指针换成「Foo#bar」「a.vue 的 foo」这类具名引用，
# pointer-audit 只判**带行号**的指针（它明确要求 anchor 带扩展名 + 冒号 + 数字），符号名的真伪**此前没有任何门禁覆盖**，
# 一直靠主会话手动跑 node tools/gate/symbol-audit.cjs——没有门禁的审计器等于没有审计器。
# 判据与 §5 同一条 sed 姿势（抽汇总行的「可疑 N 条」），再加 F-27 定案的未尽部分「符号后仍挂行号 = 0」。
# 分工（别把两条判据混为一谈）：`Foo#bar:NN` 这种**具名后挂行号**由 symbol-audit 的 HYBRID_POINTER 判，
# pointer-audit 的裸号回溯遇到 `#xxx:` 结尾会主动跳过；反过来 `Foo.java:NN` 归 pointer-audit。
# 产物落 logs/out/（忽略区），判据读数从这里取，保证「跑一次、两处复用」。
node tools/gate/symbol-audit.cjs > logs/out/f48_symbol.txt 2>&1; SYM_EXIT=$?
chk "symbol-audit 退出码" "$SYM_EXIT" "0"
SYM_SUM=$(head -1 logs/out/f48_symbol.txt || true)
say "  $SYM_SUM"
SSUS=$(sed -n 's/.*可疑 \([0-9]\+\) 条.*/\1/p' logs/out/f48_symbol.txt | head -1 || true)
chk "具名引用可疑条数" "${SSUS:-NA}" "0"
# 第二行是分类行：「其中符号后仍挂行号 = N」= HYBRID_POINTER 计数，F-27 定案「具名化后删净行号」的机器判据。
SYM_CLS=$(sed -n '2p' logs/out/f48_symbol.txt || true)
say "  $SYM_CLS"
SHYB=$(sed -n 's/.*其中符号后仍挂行号 = \([0-9]\+\).*/\1/p' logs/out/f48_symbol.txt | head -1 || true)
chk "符号后仍挂行号条数" "${SHYB:-NA}" "0"
# 红的时候必须把逐条出处吐出来，否则下一轮还得手跑一次才能定位（判据要能自证，也要能自定位）。
if [ "${SSUS:-0}" != "0" ] || [ "${SHYB:-0}" != "0" ]; then
  say "  逐条出处（kind  file:line  引用）："
  grep -E '^  [A-Z][A-Z_]+  ' logs/out/f48_symbol.txt | head -20 | sed 's/^/    /'
fi
# 空转自检：读数抽不出来（工具改名/汇总行换措辞）就是判据失效，绝不能当成「0 可疑」放行。
[ -n "${SSUS:-}" ] || { say "      FAIL 汇总行读不到「可疑 N 条」——判据空转，不是绿"; FAILS=$((FAILS+1)); }

say "=== 6) 运营端 429 文案不许重新长出写死时长（F-24-A 成果）==="
ADM=$(grep -rn '请等半分钟' front-admin/src 2>/dev/null | grep -vE ':[0-9]+: *(//|\*)' | wc -l || true)
chk "front-admin 写死的「请等半分钟」残留" "$ADM" "0"

say "=== 6b) 闸门未被削弱：每页锁引用数不得少于基线（基线现场从 HEAD 算，不手填）==="
BASE_COMMIT=f0a97af
# 模式必须**大小写不敏感**：片 1 把写侧拆成 writeRetryLock，而 `grep 'retryLock'` 匹配不到它
# （w-r-i-t-e-R... 是大写 R），第一次跑就把五页正确拆锁的改动报成 REDUCED 假警报。
for f in front-merchant/pages/dishes/dishes.vue front-merchant/pages/plan-orders/plan-orders.vue front-merchant/pages/pickup-plan/pickup-plan.vue front-merchant/pages/marketing/marketing.vue front-merchant/pages/dashboard/dashboard.vue front-merchant/pages/login/login.vue front-merchant/pages/dish-edit/dish-edit.vue front-partner/pages/apply/apply.vue front-partner/pages/arrival/arrival.vue front-partner/pages/store/store.vue front-partner/pages/verify/verify.vue front-partner/pages/workspace/workspace.vue front-partner/pages/login/login.vue; do
  # 只数**早退闸门语句**：`if (...retryLock...) {` / `return`、模板 `v-if="!retryLock"`、`:disabled="…retryLock…"`。
  # 为什么不数"含锁名的行数"：文案批会把带数的句子搬进 api 层（marketing 19→18、partner login 18→17 都是
  # 良性减少——把同锁同屏两处带数收成一处），数行数会把改进报成削弱。本判据已做变异自验：
  # 删掉 dishes 的 `if (this.retryLock <= 0) return false` 后 4→3，能红。
  GATEPAT='if[[:space:]]*\([^)]*[Rr]etry[Ll]ock[^)]*\)[[:space:]]*(\{|return)|v-if="![Rr]etry[Ll]ock|:disabled="[^"]*[Rr]etry[Ll]ock'
  b=$(git show "$BASE_COMMIT:$f" 2>/dev/null | grep -vE '^[[:space:]]*(//|\*|/\*|<!--)' | grep -cE "$GATEPAT" || true)
  n=$(grep -vE '^[[:space:]]*(//|\*|/\*|<!--)' "$f" 2>/dev/null | grep -cE "$GATEPAT" || true)
  flag=$([ "${n:-0}" -ge "$b" ] && echo ok || echo REDUCED)
  [ "$flag" = ok ] || FAILS=$((FAILS+1))
  say "  $(printf '%-46s' "$f") HEAD=$b NOW=${n:-0} $flag"
done
say "  ^ 判据是「引用数不减」。**它抓不到『闸门被换成别种写法』**（F-28.2 实测：「retryLock > 0」这种计数会把新写的 this.retryLock <= 0 判成 0 处，我差点因此误判 dishes 页丢了闸门）——真正的覆盖证明只能来自各片的逐入口矩阵。"

say "=== 7) X-2 判据：任何错误分派里有「检查网络」却没有 500 分流 => 把服务端错说成网络故障 ==="
N5=$(node tools/gate/probes/net500-audit.cjs 2>&1 | head -1 || true)
say "  $N5"
NH=$(sed -n 's/.*命中 \([0-9]\+\) 处.*/\1/p' <<<"$N5" || true)
# 改前基线（HEAD=f0a97af）实测 **13 处 / 8 页**：front-merchant 的 dishes(1) pickup-plan(2) plan-orders(2)，
# front-partner 的 apply(2) arrival(1) store(2) verify(2) workspace(1)。dashboard 与 marketing 不在其中（前者已分流）。
# 本批目标是 0。注意 partner 端**五页全中、一页都没分流**，而我的分片只把 X-2 派给了商家端片——已另开 F-28.3 补。
if [ -z "${NH:-}" ]; then say "      FAIL 判据没能读数（脚本或路径变了）"; FAILS=$((FAILS+1));
elif [ "$NH" != "0" ]; then say "      FAIL 仍有 $NH 处「500 被说成网络故障」"; FAILS=$((FAILS+1)); fi
# 反向自证：同一判据跑在改前 HEAD 上必须命中（否则 0 命中不可信）
MUT5=$(node tools/gate/probes/net500-audit.cjs f0a97af 2>&1 | head -1 || true)
say "  变异自验（改前 HEAD 必须命中）: $MUT5"
echo "$MUT5" | grep -qE '命中 ([1-9][0-9]*) 处' || { say "      FAIL 判据失去杀伤力"; FAILS=$((FAILS+1)); }

say "=== 7.5) 敏感串判据自证（防止"检查存在但从不生效"）==="
# 背景：本仓的提交前敏感串检查曾长期写成 `grep -f LIST -e PAT`，而 -f 与 -e 不叠加 →
# JWT 那一半从来没生效过，每次报"0 命中"都看着很健康（契约 F-36b）。
# 所以从现在起，每次门禁都要**主动喂一个哨兵**证明这条 grep 真的会红。
SENTINEL_DIR=logs/scratch55
mkdir -p "$SENTINEL_DIR"
# 两枚哨兵串都拆成参数拼（F-48 拆 JWT、F-56 拆脱敏占位串）：**脚本源码里不再有任何一行命中清单**，
# 而 printf 出来的哨兵与真凭据逐字节同形，判据杀伤力一点没减（下面仍要求命中 2 行）。
# 为什么值得为"源码自身干净"再拆一次：本仓要公开，仓库里每一行都是别人会拿扫描器扫的东西；
# 一条"看起来像凭据"的行会让人去查它、也会让自动化扫描误报。真值只在运行时组装，不落源码。
printf 'token=%s.%s.%s\nx=%s\n' eyJhbGciOiJIUzI1NiJ9 eyJzdWIiOiIxIn0 abcdefghijklmnopqrstuvwx 'REDACT'"ED_" > "$SENTINEL_DIR/sentinel.txt"
HIT=$(grep -cE -f logs/.secret-list "$SENTINEL_DIR/sentinel.txt" </dev/null 2>/dev/null || true)
chk "哨兵文件必须命中 2 行（清单串 + JWT 各一）" "$HIT" "2"
rm -f "$SENTINEL_DIR/sentinel.txt"

say "=== 7.6) 冷却时长的生成处与页内外溢（F-38 新增；判据自身要先过 --selftest）==="
# 模式一律 ASCII：本仓吃过「把中文塞进 grep 命令行被 GBK 往返骗过一次」的亏（HANDOFF §8）
ST=$(node tools/gate/cooldown-format-audit.cjs --selftest 2>&1 | grep -c "SELFTEST_PASS" || true)
chk "自验项应全红（6 条 SELFTEST_PASS）" "${ST:-0}" "6"
node tools/gate/cooldown-format-audit.cjs > logs/out/f38_coolfmt.txt 2>&1; CF_EXIT=$?
say "  审计末行: $(tail -1 logs/out/f38_coolfmt.txt)"
chk "cooldown-format-audit 退出码" "$CF_EXIT" "0"

say "=== 7.7) DRIFT 线索分流（--drift 每批必跑；判据只取 DEAD 不升，绝对数按 F-27 定案当线索用）==="
# 为什么单列这一节：pointer-audit 的 SUSPECT_EMPTY 看不见「行号漂到另一段有效代码上」，
# 那类错指 F-38 片 A 实测抓出三条（verify.vue:533/384-389/740-745 全指歪）——盲区一直存在，只是我没把 --drift 进循环
node tools/gate/pointer-audit.cjs --drift > logs/out/f40_drift.txt 2>&1
# 输入路径必须显式传给分流器（F-40 抓出的缺陷：它原先硬编码读 f38_drift_now.txt，
# 于是这道门禁算的一直是上一次手工留下的旧快照，不是本批刚跑出来的那份）
node tools/gate/drift-triage.cjs logs/out/f40_drift.txt > logs/out/f40_triage.txt 2>&1
DEAD=$(grep "^TRIAGE" logs/out/f40_triage.txt | grep -oE "dead=[0-9]+" | grep -oE "[0-9]+" || true)
BASE=$(cat tools/gate/baselines/drift_dead_baseline.txt 2>/dev/null || echo 25)
chk "DEAD 数不高于基线 ${BASE}（实测=${DEAD:-NA}）" "$([ "${DEAD:-999}" -le "${BASE:-25}" ] && echo ok || echo over)" "ok"

say "=== 8) 敏感串（只扫本批改到的文件；空清单必须短路，否则 grep 会递归全仓）==="
CHANGED=$(git diff --name-only 2>/dev/null)
if [ -z "$CHANGED" ]; then say "  SKIP 工作区无改动"; else
  SENS=$(echo "$CHANGED" | xargs grep -nsE -f logs/.secret-list 2>/dev/null | grep -v 'SENTINEL_DIR' | wc -l || true)
  chk "敏感串命中行数" "${SENS:-0}" "0"
fi

say "=== 9) 工作区意外文件自检（历史上曾每跑一次就在仓库根留下一个空文件「0」，真凶见下面那条反引号 lint）==="
if [ -f ./0 ]; then say "  FAIL 仓库根存在名为 0 的文件（mtime: $(stat -c %y ./0 2>/dev/null | cut -c1-19)）——须查是谁创建的"; FAILS=$((FAILS+1)); else say "  OK   仓库根无 0 文件"; fi
# 反引号写在 say "…" 的双引号串里会被 bash 当命令替换执行。实测后果：本脚本第 94 行那句
# 「retryLock > 0」被包了反引号 → 每次跑门禁都执行一次 `retryLock > 0`，报 command not found
# 并在仓库根**创建一个名为 0 的空文件**、同时把那行自述文本静默删空。藏了十几批才被 mtime 定位。
SELF="${BASH_SOURCE[0]}"
BADTICK=$(grep -c 'say "[^"]*`' "$SELF" 2>/dev/null || true)
chk "say 的双引号串里不得含反引号（会被命令替换执行）" "${BADTICK:-0}" "0"

say "=== 9b) 行尾一致性：本批改到的文件，CR 字节数必须与 HEAD 版本相等（F-82 新增）==="
# 为什么必须有这条：本机 core.autocrlf=true 且仓库**没有 .gitattributes**，编辑工具能把一份纯 LF 的 .vue
# 整份转成 CRLF；此时 git diff --numstat 仍显示 1/1、f27 §2/§3/§5 与两个审计器**全绿**——入库 blob 确实还是 LF，
# 但工作区已悄悄换了一套行尾，后续任何「按字节比对 git show HEAD:f 与工作区」的探针都会得到假差异。
# 实测来源：F-82 执行者把 index.vue 改成 2110 个 CR 字节，靠自查 CR 计数才发现（当时门禁无一变红）。
CRLFDRIFT=0
for f in $(git diff --name-only 2>/dev/null); do
  case "$f" in
    *.java|*.vue|*.js|*.ts|*.cjs|*.mjs|*.sh|*.sql|*.md|*.yml|*.json) ;;
    *) continue ;;
  esac
  WT=$(tr -dc '\r' < "$f" 2>/dev/null | wc -c | tr -d ' ')
  HB=$(git show "HEAD:$f" 2>/dev/null | tr -dc '\r' | wc -c | tr -d ' ')
  if [ "${WT:-NA}" != "${HB:-NA}" ]; then say "      行尾漂移: $f 工作区CR=${WT:-NA} HEADCR=${HB:-NA}"; CRLFDRIFT=$((CRLFDRIFT+1)); fi
done
chk "行尾漂移文件数" "$CRLFDRIFT" "0"

say "=== 10) push 前凭据普查：本地领先 origin 的**全部**新增行（不只本批 staged）==="
AHEAD=$(git rev-list --count origin/master..HEAD 2>/dev/null || echo 0)
if [ "$AHEAD" = "0" ]; then
  say "  SKIP 本地与 origin/master 同步，无待推内容"
else
  git diff origin/master..HEAD > logs/out/push_precheck.diff 2>/dev/null
  ADD=$(grep -E '^\+' logs/out/push_precheck.diff | grep -v '^+++' || true)
  # 窄排除：本脚本 §7.5 自己造的哨兵行必然命中清单（脱敏占位串与 JWT 形态各一条），它是**证明扫描会红的道具**，
  # 不是凭据。排除只认 SENTINEL_DIR 这一个键——不为绿而往清单里塞东西，也不做整文件级豁免。
  # （F-48 实测：写这句注释时若把占位串的字面量照抄进来，本行自己就会命中——注释里也不要写真值。）
  HIT=$(printf '%s\n' "$ADD" | grep -f logs/.secret-list | grep -v 'SENTINEL_DIR' | grep -c . || true)
  chk "清单命中行数（ahead=$AHEAD）" "${HIT:-0}" "0"
  # 手机号形态扫描要显式放过**测试基线号**与伪造微信流水号（否则每跑必假红，而假红的判据等于没有判据）；
  # 反过来也**不许**为了绿而把它们塞进 logs/.secret-list——那会废掉"命中即异常"这条判据本身
  PHONE=$(printf '%s\n' "$ADD" | grep -E '1[3-9][0-9]{9}' | grep -v '13800001234' | grep -vcE '420000[0-9]{18}' || true)
  chk "非测试基线的手机号形态命中" "${PHONE:-0}" "0"
fi

say "=== 11) 除 README 族外，任何 .md 不得被跟踪（用户 2026-10-19 定：md 除 README 一律不提交不推送）==="
# 判据只数「非 README 的已跟踪 .md」=0，**不写死 README 的个数**（将来加一个 README 就假红，
# 而假红的判据等于没有判据）。README 族 = README.md 与 README-<说明>.md，含任意子目录下的。
BADMD=$(git ls-files '*.md' | grep -vE '(^|/)README(-[A-Za-z0-9._-]+)?\.md$' | grep -c . || true)
chk "非 README 族的已跟踪 .md" "${BADMD:-0}" "0"
if [ "${BADMD:-0}" != "0" ]; then
  say "  ↓ 处置：git rm --cached <文件> 并在 .gitignore 补规则；已进公开历史的须另行裁定（force-push 是红线）"
  git ls-files '*.md' | grep -vE '(^|/)README(-[A-Za-z0-9._-]+)?\.md$' | sed 's/^/    /'
fi
# 工作区里存在内部 .md 是正常的（docs/ 整目录本就被忽略），这里只保证它们进不了 git 索引
say "  ℹ 当前已跟踪的 .md 清单：$(git ls-files '*.md' | tr '\n' ' ')"

say "=== 结论 ==="
git status --porcelain | sed 's/^/  /'
if [ -n "$SKIPPED" ]; then
  say "  WARN 本批被显式跳过的节：${SKIPPED# } —— 这些节没有产生任何证明，读数全绿不等于它们过了"
  say "       （SKIP_MVN / SKIP_SMOKE 是给并发跑与省发码额度用的，不是给「这次应该不用跑吧」用的）"
fi
say "FAIL 计数=$FAILS"
[ "$FAILS" -gt 0 ] && say "有 $FAILS 条 FAIL——先复核门禁自身再判实现"
exit 0
