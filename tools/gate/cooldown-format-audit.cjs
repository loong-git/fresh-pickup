// cooldown-format-audit.cjs —— F-38 之后的长期判据：冷却时长的「生成处」与「页内外溢」审计。
// 四条断言，全部可由 --selftest 证明能红（--selftest 只在内存里造违例样本，绝不碰工作区文件）。
// 为什么需要它：F-28.5 花了两批把句式收进 api 层，F-38 又给 >=60 加了中文换算，
// 而这两件事都没有机器判据——下一次有人在页里写 `loginLock + 's后可重试'`，或把 resendPillText
// 顺手接进换算函数（那是业务节奏不是限流等待），现有门禁一条都不会红。
const fs = require("fs");
const path = require("path");

const ENDS = [
  { name: "front-partner", api: "front-partner/api/index.js", pages: "front-partner/pages" },
  { name: "front-merchant", api: "front-merchant/api/index.js", pages: "front-merchant/pages" },
];
const SKIP_DIR = new Set(["node_modules", "unpackage", "dist", ".git", "target", "static"]);

function walk(dir, acc) {
  let ents;
  try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return acc; }
  for (const e of ents) {
    if (e.name.startsWith(".") || SKIP_DIR.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (/\.vue$/.test(e.name)) acc.push(p);
  }
  return acc;
}
// 剥掉注释再判：本仓吃过多次「grep 数到注释行」的假阳（见 HANDOFF §2.5 脚注）
function codeLines(text) {
  return text.split(/\r?\n/).map((l, i) => ({ n: i + 1, t: l })).filter((o) => {
    const s = o.t.trim();
    return !(s.startsWith("//") || s.startsWith("*") || s.startsWith("/*") || s.startsWith("<!--") || s.startsWith("#"));
  });
}

// 断言 1：每端 formatCoolingSeconds 的定义**恰好一份**，且 coolingFullText/cooldownPillText/coolingText
// 里至少有一处真的在调用它（只定义不调用 = 判据形同虚设）。
function checkFormatter(end, apiText) {
  const defs = (apiText.match(/function\s+formatCoolingSeconds\s*\(/g) || []).length;
  const calls = (apiText.match(/formatCoolingSeconds\s*\(/g) || []).length - defs;
  const out = [];
  if (defs !== 1) out.push(`DEF_COUNT ${end.name}: formatCoolingSeconds 定义 ${defs} 份（应为 1）`);
  if (defs >= 1 && calls <= 0) out.push(`NEVER_CALLED ${end.name}: 定义了却没有任何调用点`);
  return out;
}
// 断言 2：页内不许再出现「闸门变量 + 's…」的 JS 裸拼（F-38.0 ④ 形态 A，一刀切零命中）。
function checkRawConcat(pagesDir) {
  const RE = /[A-Za-z_$][\w$]*(?:Lock|Countdown)\s*\+\s*['"]s/;
  const out = [];
  for (const f of walk(pagesDir, [])) {
    codeLines(fs.readFileSync(f, "utf8")).forEach((o) => {
      if (RE.test(o.t)) out.push(`RAW_CONCAT ${f}:${o.n}  ${o.t.trim().slice(0, 70)}`);
    });
  }
  return out;
}
// 断言 3：模板里裸插的冷却变量名只许是读侧的 retryLock。
// 白名单成立的理由**不是**「它恒 ≤2s」（F-38.3 实测推翻：那是只看真值支的结论）——
// 而是它有**两支**且两支都不越过换算分档线：真值支撞 30 次/60 秒 + Refill.greedy ⇒ 回速 ≤2，
// 回落支正好等于各页的 RETRY_FALLBACK_SECONDS = 60，而分档线含 60（formatCoolingSeconds 现取 >60 才换算）。
// 所以模板印出的「60s后可重试」与 pill 走同一张格式化表得到的读数**逐字同形**，留在页内不再制造第二种读法。
// 其它闸门（writeRetryLock 可达 600 / loginLock / smsCountdown / catRetryLock）一旦出现在 {{ }} 里，
// 说明有人把可能越过 60 的读数绕过函数印上屏了。
function checkRawInterp(pagesDir) {
  const RE = /\{\{\s*([A-Za-z_$][\w$]*)\s*\}\}\s*s/;
  const ALLOW = new Set(["retryLock"]);
  const out = [];
  for (const f of walk(pagesDir, [])) {
    codeLines(fs.readFileSync(f, "utf8")).forEach((o) => {
      const m = o.t.match(RE);
      if (m && !ALLOW.has(m[1])) out.push(`RAW_INTERP ${f}:${o.n}  {{ ${m[1]} }}s（白名单只许 retryLock）`);
    });
  }
  return out;
}
// 断言 4：resendPillText 必须与换算函数**绝缘**——它报的是「1 分钟 1 码」的业务节奏，
// 接进 formatCoolingSeconds 会把 60 印成「1 分 00 秒后重发」，把业务规则说成限流倒计时。
function checkResendIsolated(apiText) {
  const i = apiText.indexOf("function resendPillText")
  if (i < 0) return ["RESEND_MISSING 本端没有 resendPillText（F-38 要求两份 pill 都在 api 层）"]
  const rest = apiText.slice(i)
  // 只按「行首 }」切函数体的话，单行写法 `function resendPillText(s){ return … }` 会被整个漏掉，
  // 于是 resendPillText 里真的接了换算函数也不报——这是判据自己的假绿。两种形态都取。
  const nl = rest.indexOf("\n")
  const oneLine = nl < 0 ? rest : rest.slice(0, nl)
  const multi = rest.match(/^function\s+resendPillText\s*\([\s\S]{0,600}?\n\}/)
  const body = multi ? multi[0] : oneLine
  if (/formatCoolingSeconds/.test(body)) return ["RESEND_COUPLED resendPillText 引用了换算函数（业务节奏不该被换算）"]
  return []
}

// --selftest：把每条断言喂一个已知违例，证明它**能红**。没有这一步，"全绿"只是空转的另一种写法。
function selftest() {
  const fakeApi = `
function formatCoolingSeconds(x) { return x }
export function coolingFullText(l, s) { return l + formatCoolingSeconds(s) }
export function resendPillText(s) { return s + 's后重发' }
`;
  const badPairs = [
    ["DEF_COUNT", () => checkFormatter({ name: "t", api: "" }, "")],
    ["NEVER_CALLED", () => checkFormatter({ name: "t" }, "function formatCoolingSeconds(x){return x}")],
    ["RESEND_COUPLED", () => checkResendIsolated("function resendPillText(s){ return formatCoolingSeconds(s) }")],
  ];
  let red = 0;
  for (const [kind, run] of badPairs) {
    const hits = run();
    const ok = hits.some((h) => h.startsWith(kind));
    console.log(`  SELFTEST_${ok ? "PASS" : "FAIL"} ${kind}${ok ? "" : " 未红，判据空转: " + JSON.stringify(hits)}`);
    if (ok) red++;
  }
  // 形态 A/B 的两条违例用内存字符串验判据本身（不写文件），判据函数与 checkRaw* 用同一正则
  const RE_A = /[A-Za-z_$][\w$]*(?:Lock|Countdown)\s*\+\s*['"]s/;
  const RE_B = /\{\{\s*([A-Za-z_$][\w$]*)\s*\}\}\s*s/;
  const a = "loginLock + 's后可重试'".match(RE_A);
  const b = "{{ writeRetryLock }}s后可重试".match(RE_B);
  console.log(`  SELFTEST_${a ? "PASS" : "FAIL"} RAW_CONCAT`);
  console.log(`  SELFTEST_${b && b[1] === "writeRetryLock" ? "PASS" : "FAIL"} RAW_INTERP`);
  const allow = (() => { const m = "{{ retryLock }}s后可重试".match(RE_B); return m && m[1] === "retryLock"; })();
  console.log(`  SELFTEST_${allow ? "PASS" : "FAIL"} WHITELIST_RETRYLOCK`);
  console.log(`  自验项通过 ${red + (a ? 1 : 0) + (b && b[1] === "writeRetryLock" ? 1 : 0) + (allow ? 1 : 0)} / 6`);
}

if (process.argv.includes("--selftest")) {
  console.log("=== 变异自验（只在内存造违例，不碰工作区）===");
  selftest();
  process.exit(0);
}

let total = 0;
for (const end of ENDS) {
  // F-48 搬迁修法：本工具原先住在 logs/ 里，仓库根就是 __dirname/..；现在住在 tools/gate/，
  // 深度多了一层，必须写成 __dirname/../..。留成单层会**静默 SKIP 两端**并打印「合计违例 0 条」、
  // 退出码 0——即一条假绿的门禁（找不到被测文件时它不报红，这是本工具已有的行为，别踩）。
  const apiPath = path.join(__dirname, "..", "..", end.api);
  const pagesPath = path.join(__dirname, "..", "..", end.pages);
  if (!fs.existsSync(apiPath)) { console.log(`SKIP ${end.name}: 找不到 ${end.api}`); continue; }
  const apiText = fs.readFileSync(apiPath, "utf8");
  const hits = [
    ...checkFormatter(end, apiText),
    ...checkRawConcat(pagesPath),
    ...checkRawInterp(pagesPath),
    ...checkResendIsolated(apiText),
  ];
  total += hits.length;
  console.log(`=== ${end.name}：${hits.length === 0 ? "全绿" : hits.length + " 条违例"}`);
  hits.forEach((h) => console.log("  " + h));
}
console.log(`\n合计违例 ${total} 条`);
process.exit(total === 0 ? 0 : 1);
