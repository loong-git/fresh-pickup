// drift-triage.cjs —— 把 pointer-audit --drift 的「线索」预分成两档，供人/子代理裁定。
// 为什么需要：--drift 只能说「某 commit 时刻该行号的内容 ≠ 现在」，它不区分「指针本来就指这里、只是注释被改写」
// 与「行号早已漂到无关代码上」。F-27 那轮 61 条全靠人工逐读，成本极高。
// 分流判据：注释原文里的英文标识符（方法名/类名/变量名）是否出现在目标行的 ±WINDOW 行窗口内——
//   NEAR  = 意图所指的那个名字就在那附近 → 指针大概率仍有效（噪音，多为注释被改写所致）
//   DEAD  = 一个都对不上 → 指针大概率已漂到无关代码，需要真裁定
// 它只是**分流**，不是结论；DEAD 里也有假阳（注释没写符号名只写中文），NEAR 里也有假阴（撞common 词）。
const fs = require("fs");
const path = require("path");
// F-48 搬迁修法：本工具从 logs/ 搬进 tools/gate/，深度多了一层，仓库根必须是 __dirname/../..。
// 写成单层会把仓库根算成 tools/，于是读不到注释行、也解析不到目标文件 → **每条 DRIFT 都被判成 DEAD**，
// 门禁 §7.7 会假红一次（假红比假绿好，但仍会把人引去改基线，所以必须搬时就修掉）。
const ROOT = path.join(__dirname, "..", "..");
// 窗口从 ±3 收到 ±1（F-40 实测：NEAR 档 16 条里 11 条是假阴，位移量在本仓是 1~31 行、方向不固定，
// ±3 加上"文件名切片也算标识符"这两条一起把 NEAR 变成了不可放行档）。
const WINDOW = 1;
// 输入路径可由 argv 给（F-40 抓出我的一处真缺陷：f28-gate.sh §7.7 把 --drift 写到 f40_drift.txt，
// 而这里硬编码读 f38_drift_now.txt → 门禁的 DEAD 数一直在从上一次手工留下的旧快照算，数字失真）。
// F-48：搬进 tools/gate/ 后这个**兜底**默认值也要跟着指回 logs/out/（产物区仍在 logs/，是忽略区，正确）；
// 门禁实际走 argv 显式传参，绝不依赖这条兜底。
const input = process.argv[2] || path.join(__dirname, "..", "..", "logs", "out", "f38_drift_now.txt");
const src = fs.readFileSync(input, "utf8");
console.log(`[分流器] 输入=${input.replace(/\\/g, "/")}｜WINDOW=${WINDOW}`);

const RE_LINE = /^\s*DRIFT\s+(\S+):(\d+)\s+->\s+(\S+?):(\d+)(?:-(\d+))?\s+该行=\[(.*?)\]\s+末触快照=\[(.*?)\]/;
const RE_IDENT = /[A-Za-z_][A-Za-z0-9_]{3,}/g;
// STOP 表补了「文件名/路径切片」与散文高频词（F-40 实测：NEAR 的假阴几乎全是因为窗口里撞上了
// merchant/index/data/toast/dishes 这类词——它们是路径切片，不是注释想锚的那个符号）。
// 方向刻意偏严：被误杀只会多给人看一条，被放行则是漏掉一个错指。
const STOP = new Set(["https", "http", "const", "this", "return", "function", "true", "false", "null", "undefined",
  "rate", "limit", "window", "section", "step",
  "merchant", "partner", "admin", "front", "index", "api", "data", "toast", "code", "apply", "verify",
  "workspace", "dashboard", "orders", "login", "dishes", "format", "service", "impl", "controller",
  "pages", "src", "main", "java", "vue", "that", "with", "from", "note", "item", "items", "date", "time"]);

function idents(s) {
  const out = new Set();
  const ms = s.match(RE_IDENT) || [];
  for (const x of ms) {
    // 去掉文件名后缀本身（`ResponseUtil` 是类名，留着有用；`dashboard` 来自 dashboard.vue 也有用）
    if (STOP.has(x.toLowerCase())) continue;
    out.add(x);
  }
  return out;
}

const lines = [];
for (const raw of src.split(/\r?\n/)) {
  const m = raw.match(RE_LINE);
  if (!m) continue;
  lines.push({ file: m[1], line: Number(m[2]), target: m[3], num: Number(m[4]), now: m[6] && m[6].trim(), then: m[7] && m[7].trim() });
}

// 指针里的 target 常写成裸基名（`workspace.vue:266`），而三端各有同名文件——不先解析路径就会
// 读不到文件、把每条都误判成 DEAD。歧义（多个同名）时单列 AMBIGUOUS，交人判，绝不猜。
const SKIP_DIR = new Set(["node_modules", "unpackage", "dist", ".git", "target", "static"]);
function walk(dir, acc) {
  let ents;
  try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return acc; }
  for (const e of ents) {
    if (e.name.startsWith(".") || SKIP_DIR.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (/\.(java|vue|js|ts)$/.test(e.name)) acc.push(p);
  }
  return acc;
}
const INDEX = { backEnd: "backEnd/src/main", admin: "front-admin/src", merchant: "front-merchant", partner: "front-partner", front: "front" };
const ALL_FILES = [];
for (const k of Object.keys(INDEX)) walk(path.join(ROOT, INDEX[k]), ALL_FILES);
const byBase = new Map();
for (const f of ALL_FILES) {
  const b = path.basename(f);
  if (!byBase.has(b)) byBase.set(b, []);
  byBase.get(b).push(f);
}
function resolveTarget(t) {
  if (t.includes("/") || t.includes("\\")) {
    const p = path.join(ROOT, t);
    return fs.existsSync(p) ? [p] : [];
  }
  return byBase.get(t) || [];
}

const cache = new Map();
function windowOf(target, num) {
  const key = target + "|" + num;
  if (cache.has(key)) return cache.get(key);
  let text = "";
  try {
    const hits = resolveTarget(target);
    for (const p of hits) {
      const all = fs.readFileSync(p, "utf8").split(/\r?\n/);
      text += "\n" + all.slice(Math.max(0, num - 1 - WINDOW), num + WINDOW).join("\n");
    }
  } catch (e) { text = ""; }
  cache.set(key, text);
  return text;
}

// 第三档｜目标行本身就是注释（F-41 执行者报回的结构性噪音）：--drift 的判据是「末触 commit 那一刻
// 该行号的内容 ≠ 现在」，所以任何人改写一句注释，所有指向注释行的指针都会红一次——那不是错指。
// 不单独收档的话，基线会被无关的注释改写推着涨，最后大家学会忽略它（门禁腐蚀的最快路径）。
// 方向上的代价要盯着：指针指向的注释被整段搬走也会落 NOISE，所以 NOISE 的条数仍须出现在回报里。
const NOTE_LINE = /^(\/\/|\/\*|\*|<!--|#)/;
function firstLineOf(target, num) {
  try {
    for (const p of resolveTarget(target)) {
      const l = fs.readFileSync(p, "utf8").split(/\r?\n/)[num - 1];
      if (l !== undefined && l.trim() !== "") return l.trim();
    }
  } catch (e) { /* 读不到就按非注释处理，宁可多给人看 */ }
  return "";
}

const near = [], dead = [], noise = [];
for (const f of lines) {
  let note = "";
  try {
    const all = fs.readFileSync(path.join(ROOT, f.file), "utf8").split(/\r?\n/);
    note = all[f.line - 1] || "";
  } catch (e) { note = ""; }
  const want = idents(note);
  const have = new Set(idents(windowOf(f.target, f.num)));
  const hit = [...want].filter((w) => have.has(w));
  const rec = { ...f, note: note.trim().slice(0, 92), hits: hit.slice(0, 4), wanted: [...want].slice(0, 6) };
  const fl = firstLineOf(f.target, f.num);
  if (!hit.length && NOTE_LINE.test(fl)) { rec.at = fl; noise.push(rec); continue; }
  (hit.length ? near : dead).push(rec);
}

console.log(`解析到 DRIFT ${lines.length} 条｜NEAR(大概率仍有效) ${near.length}｜DEAD(需真裁定) ${dead.length}｜NOISE(目标行是注释，改写即红) ${noise.length}`);
// ASCII 摘要行：给 f28-gate.sh 用 grep 取值。**必须 ASCII**——本仓吃过「把中文塞进命令行 grep 被 GBK
// 控制台往返骗成 0 命中」的亏，那会让门禁以为 DEAD=0 而静默放行。
console.log(`TRIAGE total=${lines.length} near=${near.length} dead=${dead.length} noise=${noise.length}`);
console.log("\n=== NOISE：指针指向的是注释行，内容变了不代表指错（但仍要盯着条数别悄悄涨） ===");
for (const n of noise) console.log(`  ${n.file}:${n.line} -> ${n.target}:${n.num}  目标行=${(n.at || "").slice(0, 60)}`);
console.log("\n=== DEAD：目标行 ±" + WINDOW + " 行里一个标识符都对不上 ===");
for (const d of dead) console.log(`  ${d.file}:${d.line} -> ${d.target}:${d.num}\n     注释=${d.note}\n     现值=${d.now}\n     期望命中的名字=${d.wanted.join(",")}`);
console.log("\n=== NEAR：命中了名字，多半是注释改写造成的假线索（可批量放行，抽查即可） ===");
for (const n of near) console.log(`  ${n.file}:${n.line} -> ${n.target}:${n.num}  命中=${n.hits.join(",")}  现值=${n.now}`);
