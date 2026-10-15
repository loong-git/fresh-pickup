// F-32 闸门覆盖判据（替代我那条只认 `retryLock > 0` 的计数判据——它抓不到 `<= 0`、`!retryLock`、
// 以及经 retryCoolingBlocked() 间接读闸门的写法；片 2b 实测指出后改成"按登记入口逐个断言"）。
// 判据：对每个**已登记的用户入口 / 生命周期出口**，取其方法体全部非注释语句，断言其中引用了闸门符号。
// 不判断写法（`> 0` / `<= 0` / `!x` / `*RetryLock` / `xxxBlocked()` 都算），只判断"这个出口过没过闸门"。
// 用法：node logs/gate-check-locks.cjs              → 审工作区
//       node logs/gate-check-locks.cjs <commit>     → 审指定 commit（变异自验用）
const fs = require("fs"), path = require("path"), cp = require("child_process");
const BASE = process.argv[2] || null;

// 只登记「会自发请求的出口」：生命周期 + 用户操作。内部的 load* 方法**不登记**——
// 闸门按本仓设计装在调用方（一处判一次、toast 不重复），把 load* 列进来会产生结构性误报。
const ENTRIES = {
  "front-merchant/pages/dishes/dishes.vue": ["onShow", "applyFilters", "clearKeyword", "switchTab", "onCategoryChange", "resetFilters", "retryCategories"],
  "front-merchant/pages/plan-orders/plan-orders.vue": ["onShow", "loadMore", "switchTab", "onDateChange", "clearDate", "resetFilters"],
  "front-merchant/pages/pickup-plan/pickup-plan.vue": ["onShow", "stepDay", "goToday"],
  "front-merchant/pages/marketing/marketing.vue": ["onShow"],
  "front-merchant/pages/dashboard/dashboard.vue": ["onShow", "switchDays"],
  "front-partner/pages/verify/verify.vue": ["onShow", "submitVerify", "loadMore", "fetchList"],
  "front-partner/pages/store/store.vue": ["save", "load"],
  "front-partner/pages/arrival/arrival.vue": ["onShow", "confirmBatch", "reloadOneBatch", "loadBatches"],
  "front-partner/pages/apply/apply.vue": ["onLoad", "submit", "loadMine", "refreshMineQuiet"],
  "front-partner/pages/workspace/workspace.vue": ["onShow"],
};
// 已登记的例外：该出口刻意不过闸门，且理由已在契约里裁过。
// ⚠ F-28.3 实测更正：`apply.vue` 只有 `onLoad`（我曾登记不存在的 `onShow`，恒报 NO_METHOD）；
//   另外**委托判据（gateThrough）在这里是假的"通过"**——`fetchList`/`loadBatches` 的 catch 里天生就出现
//   startRetryLock / coolingText(this.retryLock)，所以改前那两处 onShow 旁路被判成 GATE_VIA 放行。
//   结论：**本脚本只能证"闸门没被删"，不能证"出口都被闸"**；覆盖证明请用动态探针
//   （logs/scratch44/x2b-x4b-partner-probe.cjs：把真实方法体抠出来跑，retryLock>0 时一发不发才算数）。
const EXEMPT = { "front-merchant/pages/workspace/workspace.vue": ["onShow"],
  "front-partner/pages/arrival/arrival.vue": ["loadBatches"],
  "front-partner/pages/verify/verify.vue": ["fetchList"] };
const GATE = /(retryLock|RetryLock|CoolingBlocked|_categoryRetryAt|retryGate|catRetryLock)/;

function grabBody(src, name) {
  const lines = src.split(/\r?\n/);
  const start = lines.findIndex((l) => new RegExp("^\\s*(?:async\\s+)?" + name + "\\s*\\(").test(l));
  if (start < 0) return null;
  let depth = 0, seen = false;
  const out = [];
  for (let i = start; i < lines.length; i++) {
    out.push(lines[i]);
    for (const ch of lines[i]) { if (ch === "{") { depth++; seen = true; } else if (ch === "}") depth--; }
    if (seen && depth === 0) break;
  }
  return out.filter((l, ix) => ix > 0 && !/^\s*(\/\/|\*|\/\*|<!--)/.test(l) && l.trim() !== "");
}
// 委托一层：薄壳生命周期（onShow 只调 this.load()）自己不含闸门不算缺陷，只要它调的那个方法含闸门。
// 不加这层会产生结构性误报——partner/workspace.onShow 就是这样被我误判过一次。
function gateThrough(src, body, name, depthLeft) {
  if (body.some((l) => GATE.test(l))) return "SELF";
  if (depthLeft <= 0) return null;
  const called = new Set();
  body.forEach((l) => { const mm = l.match(/this\.([A-Za-z_][A-Za-z0-9_]*)\s*\(/g); if (mm) mm.forEach((x) => called.add(x.replace(/this\.|\s*\($/g, ""))); });
  for (const c of called) {
    if (c === name) continue;
    const sub = grabBody(src, c);
    if (!sub) continue;
    const r = gateThrough(src, sub, c, depthLeft - 1);
    if (r) return "VIA " + c;
  }
  return null;
}
let ok = 0, miss = 0, nofile = 0, nomethod = 0, exempt = 0, via = 0;
const rows = [];
for (const f of Object.keys(ENTRIES)) {
  let src;
  try { src = BASE ? cp.execFileSync("git", ["show", `${BASE}:${f}`], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }) : fs.readFileSync(f, "utf8"); }
  catch (e) { nofile++; rows.push(`  NOFILE ${f}`); continue; }
  for (const name of ENTRIES[f]) {
    if ((EXEMPT[f] || []).includes(name)) { exempt++; continue; }
    const body = grabBody(src, name);
    if (body === null) { nomethod++; rows.push(`  NO_METHOD  ${path.basename(f)} :: ${name}（登记的方法名在本版本不存在，要人工核对是真名还是已删）`); continue; }
    const g = gateThrough(src, body, name, 2);
    if (g) { ok++; if (g !== "SELF") { via++; rows.push(`  GATE_${g.replace(/ /g, "_")}(委托)  ${path.basename(f)} :: ${name}`); } }
    else { miss++; rows.push(`  NO_GATE  ${f} :: ${name}   首句=[${(body[0] || "").trim().slice(0, 56)}]`); }
  }
}
console.log(`审 ${Object.keys(ENTRIES).length} 个文件、登记出口 ${ok + miss + nomethod + exempt} 个（基线=${BASE || "工作区"}）：过闸门 ${ok}｜未过 ${miss}｜方法名对不上 ${nomethod}｜已登记例外 ${exempt}｜缺文件 ${nofile}`);
rows.forEach((r) => console.log(r));
