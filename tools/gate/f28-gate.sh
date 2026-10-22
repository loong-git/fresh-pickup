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
PUSH_BLOCK=""
# EXPOSED = 「已经消不掉的公开存量」，与 PUSH_BLOCK 分开记：PUSH_BLOCK 是"本批不具备 push 条件"（推前可修），
# EXPOSED 是"推不推它都已在公开面上"（只能 GitHub 侧处置）。混在一起会让未来的我误以为清掉红字就干净了。
EXPOSED=""
say() { echo "$@"; }
chk() { if [ "$2" = "$3" ]; then say "  OK   $1: $2"; else say "  FAIL $1: 实际=[$2] 期望=[$3]"; FAILS=$((FAILS+1)); fi; }
# 「形似手机号但不是手机号」的已裁定集合——§10（扫 diff 新增行）与 §10c（扫树）**必须共用这一份**。
# 起因是 2026-10-21 实测到的自己打自己：把用户裁定的那个演示号只写进 §10c 的排除，同一片新增行
# 立刻让 §10 从 0 变 1（注释里出现号码字面量是不可避免的，因为它就是被讨论的对象）。
# 每一项都要有出处，绝不为绿而加：① 本仓注册的测试基线号（多处 smoke/契约引用它）；
# ② 2026-10-21 用户就 §10c 报出的 4 行答「不是」（定为演示号）；③ gcj02.js 里椭圆偏心率常数的子串，
# 数学常量必然形似号码；④ 伪造微信流水号的 420000+18 位形态。反向纪律照旧：这几项**不许**塞进
# logs/.secret-list 或 logs/.pii-list——那两个清单是"命中即异常"，塞进去等于废掉判据本身。
demo_num_exclude() {
  grep -v -e '13800001234' -e '13800138000' -e '0.00669342162296594326' \
    | grep -vE '420000[0-9]{18}'
}

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
# 而 printf 出来的哨兵与真凭据逐字节同形，判据杀伤力一点没减。
# 为什么值得为"源码自身干净"再拆一次：本仓要公开，仓库里每一行都是别人会拿扫描器扫的东西；
# 一条"看起来像凭据"的行会让人去查它、也会让自动化扫描误报。真值只在运行时组装，不落源码。
# **F-104：脱敏占位串已移出凭据清单，改放 logs/.secret-sentinel。** 起因是一次实测事故：
# 我在一条提交说明里写了「替换成 [REDACTED_…]」，§10b 的 message 扫描立刻命中、准备阻断推送。
# 那个串是脱敏动作的产物而不是凭据；把它当凭据登记，等于给每一次"描述脱敏"的文本埋雷。
# 现在凭据清单只管凭据，占位符有自己的清单，两枚哨兵分别验证两套。
printf 'token=%s.%s.%s\nx=%s\n' eyJhbGciOiJIUzI1NiJ9 eyJzdWIiOiIxIn0 abcdefghijklmnopqrstuvwx 'REDACT'"ED_" > "$SENTINEL_DIR/sentinel.txt"
HIT=$(grep -cE -f logs/.secret-list "$SENTINEL_DIR/sentinel.txt" </dev/null 2>/dev/null || true)
chk "哨兵文件必须命中 1 行（凭据清单里的 JWT 规则）" "$HIT" "1"
if [ -f logs/.secret-sentinel ]; then
  HIT_S=$(grep -cF -f logs/.secret-sentinel "$SENTINEL_DIR/sentinel.txt" </dev/null 2>/dev/null || true)
  chk "哨兵文件必须命中 1 行（占位符清单）" "$HIT_S" "1"
else
  SKIPPED="$SKIPPED §7.5(无 logs/.secret-sentinel)"
fi
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

say "=== 9b) 入库面行尾纯净：全仓被跟踪文件在 HEAD 里的 blob 一律不得含 CR（F-82 新增；F-98c 重定口径）==="
# 原判据（F-82）是「本批改到的文件，工作区 CR 字节数必须与 HEAD 版本相等」。2026-10-22 逐条实测后判定它防错了对象：
#   ① 本机 core.autocrlf=true 且无 .gitattributes，把一份纯 LF 的 .js 整份转成 CRLF（工作区 CR=599、blob CR=0）后，
#      git status 与 git diff --name-only **都不再报这个文件** → 按 git 取待判集必然漏判（原实现就是这么写的，
#      因此它从未真正有效过）；反过来文件只要 mtime 变过而内容相同，status 又会把它列进来 → 同口径既漏也噪声。
#   ② 全仓普查：4 个被跟踪文件（README.md、application.yml、application-dev.yml、deploy/backup.sh）工作区带 CRLF，
#      但它们在 HEAD 里的 blob **全是 LF** —— 那是 git 检出时自己加的，属正常行为，按工作区判会常年假红。
#      （顺带否证一个我当场误判的结论：deploy/backup.sh 工作区 shebang 确实是 `#!/bin/bash\r\n`，
#       但 prod 上跑的是 checkout 后的 LF 版，不会 bad interpreter。）
#   ③ 更关键：`git hash-object -w` 默认应用 clean 过滤器，实测同一份 x\r\ny\r\n 经它入库后 CR=0，
#      加 --no-filters 才保留 2 → **本机把 CRLF 文件 commit 进去这条路 git 已经自己堵上了**，F-82 担心的形态在
#      这台机器上不可达。还可达的路径是别的检出配置（autocrlf=false/input、CI、Linux 侧直推），
#      所以判据重定为**只看入库 blob**：它防的是"从别的机器推进来的 CRLF"，而不是本机编辑器。
# 基线（2026-10-22 实测）：290 个被跟踪源码/配置文件的 blob 含 CR 数 = 0。
BLOB_CR=$(git grep -l -P '\r' HEAD -- '*.java' '*.vue' '*.js' '*.ts' '*.cjs' '*.mjs' '*.sh' '*.sql' '*.md' '*.yml' '*.json' 2>/dev/null)
SCANNED_9B=$(git ls-files | grep -cE '\.(java|vue|js|ts|cjs|mjs|sh|sql|md|yml|json)$')
# 自证（本仓纪律：0 命中必须配同轮正例，否则是自检无效不是通过）：造一枚**不经过滤器**的含 CR 对象喂同一条判据
PROBE_9B=$(printf 'q\r\nw\r\n' | git hash-object -w --no-filters --stdin 2>/dev/null)
if [ -z "$PROBE_9B" ] || ! git grep -q -P '\r' "$PROBE_9B" 2>/dev/null; then
  say "   🔴 判据自证失败：连含 CR 的临时对象都报不出来 → 本节的 0 命中不可信"
  chk "行尾判据自证（必须能报出含 CR 的探针对象）" "fail" "ok"
else
  say "   判据自证 OK：探针 blob ${PROBE_9B:0:8} 含 CR 能被本节报出"
  [ "$SCANNED_9B" = "0" ] && say "      普查文件数=0 → 本节没看任何文件，判据失效"
  chk "普查文件数（必须 >0）" "$([ "$SCANNED_9B" -gt 0 ] && echo ok || echo fail)" "ok"
  if [ -n "$BLOB_CR" ]; then echo "$BLOB_CR" | sed 's/^/      blob 含 CR: /'; fi
  chk "blob 含 CR 的被跟踪文件数" "$([ -z "$BLOB_CR" ] && echo 0 || echo "$BLOB_CR" | grep -c .)" "0"
fi
# 探针对象不被任何引用指向，交由 git gc 的默认 prune 周期回收（约 20 字节，不每次门禁 gc）

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
  # 手机号形态扫描的放过项集中在 demo_num_exclude（文件头），§10 与 §10c 共用——原因见那段的注释。
  # 反过来也**不许**为了绿而把它们塞进 logs/.secret-list——那会废掉"命中即异常"这条判据本身
  PHONE=$(printf '%s\n' "$ADD" | grep -E '1[3-9][0-9]{9}' | demo_num_exclude | grep -c . || true)
  chk "非测试基线的手机号形态命中" "${PHONE:-0}" "0"
fi

say "=== 10b) commit message 隐私普查：本地领先 origin 的**提交说明**里不许有真实门店/人名/执照号 ==="
# 为什么单开一条：§10 扫的是 git diff 的新增行，而 **commit message 根本不在 diff 里**。
# F-90 那次为了论证「法人名与门店名同源、两条一拼能定位到人」，把真实人名写进了提交说明——
# 一片以清除隐私为目的的改动，自己造了一个新的泄露载体，而 §10 当时全绿。这条就是那次自打脸之后补的。
# 判成 WARN 而不是 FAIL，理由要说清：未推送历史的改写（amend / rebase）在本仓是红线，处置权在用户，
# 拿它卡住所有本地提交等于把"待裁定"变成"永久红"；但它同时必须是 **push 前置阻断项**——
# 一旦推上去就和 §5 的 bcc2b8f 一样进公开历史、删不掉了。所以它进结论区的 push 阻断清单。
if [ ! -f logs/.pii-list ]; then
  SKIPPED="$SKIPPED §10b(无 logs/.pii-list)"
  say "  SKIP logs/.pii-list 不存在（与 .secret-list 同处理：清单只在本地，绝不入库）"
else
  git log origin/master..HEAD --format='%h %s%n%b' > logs/out/push_precheck.msgs.txt 2>/dev/null
  # 口径统一成 -F（2026-10-22 实测）：这里原先是 `grep -f`（BRE）。对隐私清单恰好无害——13 条锚
  # 经逐条测试**一个正则元字符都没有**，两口径命中数完全相同。但同一测法在凭据清单上打出
  # 第 8 条 -F=1 / BRE=0：含正则形态的锚在 BRE 下**静默失配**，那正是 §10c 那次假阴性的根因。
  # 所以这里固定 -F，并让它同时扫凭据清单——否则将来谁往隐私清单里加一条带括号/点号的锚，
  # 这条判据就会无声地变成 0 命中，而且没人会知道。
  MHIT=$(grep -F -f logs/.pii-list logs/out/push_precheck.msgs.txt 2>/dev/null | grep -c . || true)
  if [ "${MHIT:-0}" = "0" ]; then
    say "  OK   未推送提交说明里的隐私锚命中: 0"
  else
    say "  WARN 提交说明里的隐私锚命中 ${MHIT} 行 → **push 前必须由用户裁定处置，未推历史不许自行 amend**："
    grep -n -F -f logs/.pii-list logs/out/push_precheck.msgs.txt 2>/dev/null | cut -c1-100 | sed 's/^/      /'
    PUSH_BLOCK="$PUSH_BLOCK 提交说明含隐私锚 ${MHIT} 行"
  fi
  SHIT=0
  if [ -f logs/.secret-list ]; then
    SHIT=$(grep -F -f logs/.secret-list logs/out/push_precheck.msgs.txt 2>/dev/null | grep -c . || true)
  fi
  chk "未推送提交说明里的凭据命中" "${SHIT:-0}" "0"

  # —— 第二面：已公开全链（§10b 上方只扫 origin/master..HEAD，结构上永远看不见已经在公开历史里的那几条）——
  # 活性自测用「清单自匹配」而不是某个历史点：不依赖对象不被 gc、也不随历史清理而失效。
  SELF=$([ -f logs/.pii-list ] && grep -F -f logs/.pii-list logs/.pii-list 2>/dev/null | grep -c . || echo 0)
  if [ "${SELF:-0}" = "0" ]; then
    say "  🔴 隐私清单自匹配为 0 → 清单坏了或为空，下面这个数不可信"
    chk "清单自匹配活性（必须 >0）" "0" "1"
  elif git rev-parse --verify --quiet refs/remotes/origin/master >/dev/null; then
    PUB=0; PUBT=0
    for h in $(git rev-list refs/remotes/origin/master 2>/dev/null); do
      PUBT=$((PUBT+1))
      n=$(git log -1 --format='%h %s%n%b' "$h" | grep -F -f logs/.pii-list 2>/dev/null | grep -c . || true)
      [ "${n:-0}" != "0" ] && PUB=$((PUB+1))
    done
    say "   已公开提交说明普查：$PUBT 条里 $PUB 条含隐私锚（判据活性自测=$SELF，须 >0）"
    if [ "$PUB" != "0" ]; then
      # 不判 FAIL：已发布历史的改写（filter-branch/force-push）在本仓是红线，处置权在用户。
      # 但它必须摆成存量登记，而不是让人误以为「§10b 绿 = 提交说明全干净」。
      say "  WARN 公开历史里仍有 ${PUB} 条提交说明含隐私锚 → 只能 GitHub 侧处置（转 private / 删库 / 另起无锚新仓）；"
      say "       这一面不在 PUSH_BLOCK 里，因为无论推不推它都已经公开了，卡住本地提交只会把待裁定变成永久红"
      EXPOSED="$EXPOSED 公开提交说明含锚 ${PUB} 条"
    fi
    # 显式记录本节扫的是哪一面（本仓纪律）：**只扫 origin 的提交说明，不扫 origin 各历史点的树**。
    # 后者 2026-10-22 实测是 159 个提交点里 138 个含锚（每点恒 29 行），但要现扫得对 170 个 rev 各跑一次
    # git grep -F 全树，成本明显；而那一面**读数再多也不改变动作**（同样只能 GitHub 侧处置），
    # 所以它登记在 docs/上线与二期要求清单.md §6 与 logs/scratch/pii-history-scan.txt，不在这里重复。
  else
    SKIPPED="$SKIPPED §10b(公开全链：无 origin/master ref)"
    say "  SKIP 本地没有 refs/remotes/origin/master，公开全链这一面没扫"
  fi
fi

say "=== 10c) 树级隐私普查：本地 HEAD 的树与已公开的 origin/master 的树整体扫清单（F-94 新）==="
# 为什么 §10/§10b 不足以证明"隐私已清"：那两条都只看 origin/master..HEAD 这一段的**增量**
# （新增行 / 提交说明），它们能证明"这批没带入"，**证明不了"当前内容不含"**。
# 2026-10-21 第一次把这条盲区跑出来：§10/§10b 当时全绿，而 `git grep -f logs/.pii-list origin/master`
# 实测**已公开那棵树本身命中 6 文件 / 去重 29 行**（脱敏片当时还没推）。我据此把 F-90 的定级
# 从"唯一残余是公开历史"订正成"公开 HEAD 的当前内容就含真名"——判据没错，错在把"判据绿"当成"性质成立"。
# 所以这里扫的是**两个 ref 的树**，不扫工作区（工作区含并行代理的半成品，读数不可归因）。
# 0 命中不许直接引用：同轮必须有一个**正例 ref** 非空，否则等于没测（本仓「自检返空=自检无效」那条）。
if [ ! -f logs/.pii-list ]; then
  SKIPPED="$SKIPPED §10c(无 logs/.pii-list)"
  say "  SKIP logs/.pii-list 不存在（清单只在本地，绝不入库）"
elif [ ! -s logs/.pii-list ]; then
  # 空清单必须红，不能 SKIP：实测 `git grep -F -f <空文件>` 会**匹配一切**（拿 HEAD 试出 52 行假命中，
  # 面里都是 TraceIdFilter.java 这类毫不相关的文件）。判据坏的方向是"假绿"最可怕，但这里连"假红"都带着
  # 一堆无意义命中面，读的人无法分辨，所以直接判失败并停止扫描——与 §7.5 把"清单没了"变红同一个思路。
  chk "logs/.pii-list 存在且非空（空清单会让 git grep 匹配一切）" "1" "0"
else
  # 去重到「文件:行号」再数：同一行被多条锚命中只能算一行，否则读数会随清单长度漂移。
  # 两个 ref 各扫一次；命中面打印必须复用同一个 ref 变量，否则改一处漏一处（坏例实测踩过）。
  tree_hits() {
    git grep -n -F -f logs/.pii-list "$1" 2>/dev/null \
      | sed "s|^$1:||" | awk -F: '{print $1":"$2}' | sort -u | grep -c . || true
  }
  SCAN_REV=HEAD
  HTREE=$(tree_hits "$SCAN_REV")
  chk "本地 HEAD 树隐私命中行数" "${HTREE:-0}" "0"
  if [ "${HTREE:-0}" != "0" ]; then
    say "  ↓ 处置：脱敏片本身没做完就被提交了——先补内容再提交，别推。命中面："
    git grep -l -F -f logs/.pii-list "$SCAN_REV" 2>/dev/null | sed 's/^/      /'
  fi
  # 正例对照。**2026-10-22 F-104 重写后不再引用写死的历史 SHA。**
  # 旧写法是 tree_hits 'cf57c33~1'——那个提交之所以可用，只是因为"脱敏前那一版"还躺在历史里；
  # 而 F-104 为清高德凭据把全历史重写了一遍（173 个 SHA 全换），这个 ref 当场不存在，
  # 本节立刻报「正例对照 bad」。判据本身没坏，坏的是**它挂在一个会腐烂的锚上**：
  # 只要有人重写历史、或那个提交被 GC，正例就无声变成 0。
  # 改成与 §10e 同构——现造内容、用**同一个工具、同一份模式文件**扫，不依赖任何提交：
  # 拿清单本身当被扫内容，每条锚都该命中自己，所以期望值是清单行数，而不是随便一个 >0。
  # （"清单漏登一项"那一类缺口不归本节管，归 §10e 的形状普查。）
  # 路径自带 mkdir：F-95 脚手架会把本节**逐字抽出来单独跑**，那时 §7.5 定义的
  # SENTINEL_DIR 并不存在（set -u 直接炸）。判据不能只在完整脚本里才活得了。
  PII_PROBE=logs/scratch55/pii-probe.txt
  mkdir -p "$(dirname "$PII_PROBE")"
  cat logs/.pii-list > "$PII_PROBE"
  PRE=$(git grep -F -c -f logs/.pii-list --no-index -- "$PII_PROBE" 2>/dev/null \
    | awk -F: '{s+=$NF} END{print s+0}')
  PII_N=$(grep -c . logs/.pii-list || true)
  rm -f "$PII_PROBE"
  case "${PRE:-}" in
    ''|*[!0-9]*)
      say "   🔴 正例读数不是整数（=${PRE:-空}）→ 本条普查不可信"
      chk "正例读数可解析性" "bad" "ok" ;;
    *)
      say "   正例对照实测读数：$PRE 行（期望 = 清单行数 $PII_N，每条锚命中自己）"
      if [ "$PRE" -eq "${PII_N:-0}" ] && [ "$PRE" -gt 0 ]; then
        chk "正例对照（必须等于清单行数，才算判据和模式文件都活着）" "ok" "ok"
      else
        say "      ↓ 不等于清单行数只可能是判据/模式文件坏了（清单失联、参数顺序错、漏了 -F），不是隐私真的没了"
        chk "正例对照（必须等于清单行数，才算判据和模式文件都活着）" "bad" "ok"
      fi ;;
  esac
  if git rev-parse --verify --quiet origin/master >/dev/null; then
    OTREE=$(tree_hits origin/master)
    if [ "${OTREE:-0}" != "0" ]; then
      # 不做 FAIL：公开树上的旧内容只能靠「把脱敏片推上去」或 GitHub 侧处置来消，两者都在用户手里，
      # 门禁无权替用户决定，但必须把它摆成 push 前置阻断项——否则推上去等于把脏内容继续留在公开 HEAD。
      say "  WARN 已公开的 origin/master 树仍含隐私锚 ${OTREE} 行 → **推上脱敏片后本行应自动转 0；在那之前本批不具备 push 条件**"
      PUSH_BLOCK="$PUSH_BLOCK 公开 origin/master 树含隐私锚 ${OTREE} 行"
    else
      chk "origin/master 树隐私命中行数" "0" "0"
    fi
  else
    SKIPPED="$SKIPPED §10c(origin/master 不存在)"
    say "  SKIP 无 origin/master 引用，公开树那一半无法判定"
  fi
  # 同一棵树再按**形态**扫一遍：清单只防"已知的那几个串"，防不住将来新写进来的另一个门店或另一个号码。
  # 2026-10-21 首测逐层：形态命中 15 行 → 减基线号与流水号 = 6 → 减 gcj02 椭圆偏心率常数的**整串精确** = 4
  #   → 那 4 行是同一个号（`front/main.js` 的 M3-T-10 兼容分支），库里 orders 8 行 + user 1 行，
  #   形态上与"注册过并下过单的正常账号"无法区分，所以当时**只报阻断、不替用户判定**。
  #   同日用户就这 4 行答「不是」真实号码 → 它降为演示号，进 demo_num_exclude，最终读数 **0**。
  # ⚠ **只有最终读数 0 与「③→④ 的差 = front/main.js 那 4 行」是可钉的**，中间层 15/6 会漂：
  #   这片注释自己就得把被讨论的号码写成字面量，于是每次改 §10c 都会往 HEAD 树里添几条形似号码的串
  #   （2026-10-21 再测已是 **21→9→4→0**）。推论：将来谁在本片注释里写一个陌生的 11 位数字，
  #   §10c 会把它当未裁定号报 BLOCK——那是**设计如此**（宁可挡住改门禁的人），不是判据坏了。
  # 同一棵 HEAD 树再用**凭据**清单扫一遍，与上面隐私那条对称：§10 只扫 ahead diff，
  # 「当前内容里有没有凭据」这句话此前没有任何判据管过（2026-10-21 首次扫：origin 树 0、HEAD 树也 0）。
  # 窄排除只放行「判据代码自己」那一行——实测清单第 8 条就是 JWT 形态的**正则字面量本身**，
  # 而 redact-for-release.sh:16 是定义它的那行（JWT_RE=…），不移植就会永久自指命中。
  # 去掉这条 grep -v 立刻从 0 变 1，所以它是"放行一行"，不是"放行一个文件"。
  if [ -f logs/.secret-list ]; then
    SEC_TREE=$(git grep -n -F -f logs/.secret-list HEAD 2>/dev/null | grep -v 'JWT_RE=' | grep -c . || true)
    chk "本地 HEAD 树凭据命中行数" "${SEC_TREE:-0}" "0"
  else
    SKIPPED="$SKIPPED §10c(无 logs/.secret-list)"
  fi
  # 手机号形态逐层实测（每层都给数，便于复核我没有多放）：① HEAD 树形态命中 15 行
  #   → ② 减测试基线号与伪造流水号 = 6 → ③ 减 gcj02 椭圆偏心率常数的整串 = 4
  #   → ④ 减**已裁定为演示号**的那一串 = 0。④ 是 2026-10-21 拿用户回答落的：§10c 报出那 4 行后
  #   用户答「不是」（不是他的真实号码），所以这个号与 13800001234 同级，进精确排除而不是进 .pii-list。
  # 排除口径只做**整串精确**，实测过整文件豁免的危害：把第③层换成 'front/main.js' 之后命中从 4 变 **0**
  # ——那个陌生号就永远看不见了。与 §10 的 SENTINEL_DIR、上面凭据那条 JWT_RE 是同一条纪律。
  PHONE_HITS=$(git grep -n -E '1[3-9][0-9]{9}' HEAD 2>/dev/null | demo_num_exclude || true)
  # 计数与打印**必须共用同一份结果**：两条管道各写一遍时，我只改过计数那条，
  # 结果是 WARN 说 4 行而打印面列 0 行，看起来像判据自己坏了（F-94c 实测踩到）。
  PHONE_TREE=$(printf '%s\n' "$PHONE_HITS" | grep -c . || true)
  if [ "${PHONE_TREE:-0}" != "0" ]; then
    say "  WARN HEAD 树有 ${PHONE_TREE} 行手机号形态不在已裁定集合内 → 逐条确认是不是真实号码："
    printf '%s\n' "$PHONE_HITS" | sed 's|^HEAD:||' | awk -F: '{print "      " $1 ":" $2}'
    PUSH_BLOCK="$PUSH_BLOCK HEAD 树含未裁定手机号形态 ${PHONE_TREE} 行"
  else
    chk "HEAD 树手机号形态未裁定命中行数" "0" "0"
  fi
fi

say "=== 10e) 形状普查（F-103 新）：不读清单，只按凭据的**长相**扫 HEAD 树 ==="
# 这一节存在的理由是一条已经发生过的事故，不是假想：logs/.secret-list 少登了高德那两把值，
# 于是 §6/§10/§10c 连着多轮报「凭据 0 命中」，而我拿这个 0 当过 push 依据；
# 同一时刻 front/api/config.js 里躺着两把活 key。**清单驱动的判据，绿只证明清单里没有，
# 不证明树里没有。** 形状普查与清单无关，所以「清单漏一项」不再是假绿的成因。
# 首次实跑读数（2026-10-22，同一条命令跑两个 ref）：HEAD 树命中 4 行，全部落在 */src/test/*
# ——那是伪造微信回调的 fixture；HEAD~1（凭据出仓前那一版）同口径命中 6 行，
# 多出来的正是 front/api/config.js:26 与 :27。所以放行规则只需要「测试路径」一条，
# 而判据在出仓前那一版上是红的、出仓后是绿的——这个方向差本身就是本节有效的证明。
SHAPE_DIR=logs/scratch55
mkdir -p "$SHAPE_DIR"
# 普查的 rev 走变量而不是字面量：本节的变异自验要能把它换成 HEAD~1（凭据出仓前那一版）。
# 写成字面量时，变异只能靠 sed 改抽出来的片段，结果是放行与打印那两条管道里的
# 'HEAD:' 前缀也跟着错位——一次实测跑出了 6 行"未放行"，看着像判据坏了，其实是脚手架的副作用。
SHAPE_REF="${SHAPE_REF:-HEAD}"
cat > "$SHAPE_DIR/shapes.txt" <<'SHAPES'
[0-9a-f]{32}
-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----
eyJ[a-zA-Z0-9_\-]{8,}\.[a-zA-Z0-9_\-]{8,}\.[a-zA-Z0-9_\-]{8,}
(AKIA|LTAI)[0-9A-Za-z]{12,}
(?i)["']?(api[_-]?key|app[_-]?key|secret|access[_-]?token|passwd|password)["']?[[:space:]]*[:=][[:space:]]*["'][0-9A-Za-z_\-]{20,}["']
SHAPES
# JWT 那一族的字符类**故意写成 [a-zA-Z0-9_\-] 而不是 [A-Za-z0-9_-]**：两者语义等价，
# 但后者与 logs/.secret-list 第 8 条逐字重合（那条就是 redact-for-release.sh:16 的 JWT_RE 正则本身）。
# 照抄会让本脚本这一行命中清单，而 §10c 的豁免键只认 'JWT_RE=' 那一行，放行不到这里来 →
# 提交后 §10c 立刻 FAIL。这不是绕判据，是 §7.5 那条老纪律「源码里不许有任何一行命中清单」的延续。
SHAPE_SCANNED=$(git ls-files | grep -c . || true)
chk "形状普查面（跟踪文件数必须 >0）" "$([ "${SHAPE_SCANNED:-0}" -gt 0 ] && echo ok || echo bad)" "ok"
# 活性正例：**不依赖树里恰好有一个真凭据**。§10c 原先挂在一个历史 SHA 上（"脱敏前那次提交"），
# F-104 重写全历史后它当场失效，那边也已改成"清单自身当探针"。这里现造一条与真凭据同形的串喂回**同一个工具、
# 同一份模式文件**——正例必须和被验的判据同构，否则证的是另一条命令。
# 这条纪律下面有个实打实的踩坑：普查用 git grep（PCRE 实现支持 -f 多模式），
# 而我第一版正例写成 `printf ... | grep -P -f shapes.txt`，Git Bash 的 grep 直接拒绝：
# 「the -P option only supports a single pattern」，exit=2 → 正例恒 bad，本节一跑就红。
# 两个 grep 不是同一个 grep。
# 串仍必须拆成两段拼——照 §7.5 的纪律，本脚本源码里不能有任何一行命中自己的判据：
# 两段各 16 字符既不满足 hex32、也不满足「引号内 20+ 位」，运行时拼出的 32 位才满足。
printf 'appSecret="%s%s"\n' deadbeefdeadbeef deadbeefdeadbeef > "$SHAPE_DIR/probe.txt"
printf 'controlLine=noCredentialHere\n' > "$SHAPE_DIR/probe-neg.txt"
SHAPE_PROBE=$(git grep -I -c -P -f "$SHAPE_DIR/shapes.txt" --no-index -- "$SHAPE_DIR/probe.txt" 2>/dev/null \
  | awk -F: '{s+=$NF} END{print s+0}')
chk "形状判据活性正例（合成同形串必须被同一条命令报出）" "$([ "${SHAPE_PROBE:-0}" -gt 0 ] && echo ok || echo bad)" "ok"
# 负例对照：同一命令扫一个无凭据的文件必须 0，否则"正例 >0"只证明它会恒返回真。
SHAPE_NEG=$(git grep -I -c -P -f "$SHAPE_DIR/shapes.txt" --no-index -- "$SHAPE_DIR/probe-neg.txt" 2>/dev/null \
  | awk -F: '{s+=$NF} END{print s+0}')
chk "形状判据负例对照（无凭据文件必须 0）" "${SHAPE_NEG:-0}" "0"
SHAPE_ALL=$(git grep -I -n -P -f "$SHAPE_DIR/shapes.txt" "$SHAPE_REF" 2>/dev/null || true)
# 放行只认「测试夹具路径」这一个键，且**逐轮打印放行在哪些文件**——放行必须可见，
# 否则未来的我读到的又是一个无源的 0。
SHAPE_TESTF=$(printf '%s\n' "$SHAPE_ALL" | grep "$SHAPE_REF:[^:]*src/test/" | grep -c . || true)
SHAPE_LIVE=$(printf '%s\n' "$SHAPE_ALL" | grep -v "$SHAPE_REF:[^:]*src/test/" | grep -c . || true)
if [ "${SHAPE_TESTF:-0}" != "0" ]; then
  say "   测试夹具放行 ${SHAPE_TESTF} 行，涉及文件："
  printf '%s\n' "$SHAPE_ALL" | grep "$SHAPE_REF:[^:]*src/test/" \
    | sed "s|^$SHAPE_REF:||" | awk -F: '{print "      " $1}' | sort | uniq -c
fi
if [ "${SHAPE_LIVE:-0}" != "0" ]; then
  say "  FAIL 非测试面出现凭据形状 ${SHAPE_LIVE} 行 → 逐条裁定；确认是真凭据就移出仓库并轮换："
  printf '%s\n' "$SHAPE_ALL" | grep -v "$SHAPE_REF:[^:]*src/test/" \
    | sed "s|^$SHAPE_REF:||" | awk -F: '{print "      " $1 ":" $2}'
  PUSH_BLOCK="$PUSH_BLOCK ${SHAPE_REF} 树含凭据形状 ${SHAPE_LIVE} 行"
  FAILS=$((FAILS+1))
else
  chk "非测试面凭据形状命中行数" "0" "0"
fi
rm -f "$SHAPE_DIR/shapes.txt" "$SHAPE_DIR/probe.txt" "$SHAPE_DIR/probe-neg.txt"

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

say "=== 12) 门禁自述与 README 同步：本脚本每个节号都必须被 README 提到（F-93d 新）==="
# 起因是 F-93c 那次手工核对：f28 实有 15 个节号，而 README 漏了 §6b 一句。
# 这类漂移不会让任何行为出错，所以只能靠判据盯着——加一节而不写文档，本来不会有人发现。
# 本节自指：它自己也在被检查之列，所以今后新加一节就必须同时改 README，否则这里直接红。
MISS=""
for n in $(grep -oE 'say "=== [0-9]+(\.[0-9]+)?[a-z]?\)' tools/gate/f28-gate.sh | sed 's/.*=== //; s/)//' | sort -u); do
  grep -q "§$n" tools/gate/README.md || MISS="$MISS §$n"
done
chk "README 漏提的节号数" "$([ -z "$MISS" ] && echo 0 || printf '%s' "$MISS" | wc -w | tr -d ' ')" "0"
[ -n "$MISS" ] && say "      漏提：$MISS —— 到 tools/gate/README.md 的 f28 节「判什么」里补上它"

say "=== 结论 ==="
git status --porcelain | sed 's/^/  /'
if [ -n "$SKIPPED" ]; then
  say "  WARN 本批被显式跳过的节：${SKIPPED# } —— 这些节没有产生任何证明，读数全绿不等于它们过了"
  say "       （SKIP_MVN / SKIP_SMOKE 是给并发跑与省发码额度用的，不是给「这次应该不用跑吧」用的）"
fi
if [ -n "$PUSH_BLOCK" ]; then
  say "  BLOCK push 前置阻断项：${PUSH_BLOCK# } —— 本批**可以本地提交**，但**不具备 push 条件**，"
  say "       未推送历史的 amend/rebase 需用户裁定（本仓红线：不改写历史、不 force-push）"
fi
if [ -n "$EXPOSED" ]; then
  say "  存量 已公开、本地不可消的面：${EXPOSED# }"
  say "       这些不是 FAIL 也不是 push 阻断——它们只能靠 GitHub 侧处置（转 private / 删库 / 另起无锚新仓）；"
  say "       记在这里是为了别把「§10b/§10c 全绿」读成「公开面干净」：绿的是**本批没新增泄露**，不是**历史没有泄露**。"
fi
say "FAIL 计数=$FAILS"
[ "$FAILS" -gt 0 ] && say "有 $FAILS 条 FAIL——先复核门禁自身再判实现"
exit 0
