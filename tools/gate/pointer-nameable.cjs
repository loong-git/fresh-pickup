// pointer-nameable.cjs —— 给「仍然有效的跨文件行号指针」分类：哪些能安全具名化、哪些必须留行号。
// 动机：F-26/27/31/34/35 五批一直在还同一笔债——每次代码改动都顶掉几条行号，改对行号只是把雷重埋一遍。
// 要一次收干净，先得知道 144 条里有多少条的目标行**本身就是某个符号的声明行**（那才能换成符号名），
// 有多少条指的是语句/区间/模板行（那行号是唯一表达力，具名化会丢信息）。
// 这个脚本只读、不改任何文件，输出两张清单供派单用。
const fs = require("fs");
const path = require("path");

const ROOTS = ["backEnd/src/main", "front-admin/src", "front-merchant", "front-partner", "front"]; // front 自 F-77 与其余三个审计器同面，四处必须一起改
const EXTS = new Set([".java", ".vue", ".js", ".ts"]);
const SKIP_DIR = new Set(["node_modules", "unpackage", "dist", ".git", "target", "static"]);

function walk(dir, acc) {
  let ents;
  try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return acc; }
  for (const e of ents) {
    if (e.name.startsWith(".") || SKIP_DIR.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (EXTS.has(path.extname(e.name))) acc.push(p);
  }
  return acc;
}
const files = [];
for (const r of ROOTS) walk(r, files);
const indexed = files.slice();

const byName = new Map();
for (const f of indexed) {
  const b = path.basename(f);
  if (!byName.has(b)) byName.set(b, []);
  byName.get(b).push(f);
}
const contentCache = new Map();
function content(f) {
  if (!contentCache.has(f)) contentCache.set(f, fs.readFileSync(f, "utf8"));
  return contentCache.get(f);
}
function lines(f) { return content(f).split(/\r?\n/); }

const RE_POINTER = /((?:[A-Za-z0-9_.@-]+[\\/]+)*[A-Za-z0-9_.-]+\.(?:java|vue|js|ts))[:：]([0-9]+(?:\s*[-/]\s*[0-9]+)*)/g;

// 目标行 → 符号名（能提出就代表"这一行就是那个符号的声明处"，可以具名化）
function symbolOf(line) {
  const t = line.trim();
  if (!t) return null;
  // Vue 模板 / 标签行：行号是唯一表达力
  if (/^[<{]/.test(t)) return null;
  let m;
  // 缩进 >=6 的 const/let/var 几乎必然在方法体内部（顶层声明缩进 0、Vue 选项方法缩进 2~4），
  // 具名化对局部变量没意义——名字有了还得问"哪一次赋值"。第一版没这条，冒出的假阳是
  // workspace.vue:243 的 `const url`、plan-orders.vue:452 的 `const s`、Login.vue:125 的 `const retryAfter`。
  const IND = (line.match(/^[ \t]*/)[0] || "").length;
  const LOCAL_DECL = IND >= 2;
  if ((m = t.match(/^(?:export\s+)?(?:async\s+)?function\s+([\w$]+)/))) return m[1];
  if ((m = t.match(/^(?:export\s+)?(?:default\s+)?(?:const|let|var)\s+([\w$]+)/))) return LOCAL_DECL ? null : m[1];
  if ((m = t.match(/^(?:public|private|protected|static|final|synchronized|abstract)\s[\w<>\[\],. ?]*\s([\w$]+)\s*\(/))) return m[1];
  if ((m = t.match(/^(?:@\w+.*\s)?(?:public\s+|private\s+|protected\s+)?(?:class|interface|enum|record)\s+(\w+)/))) return m[1];
  if ((m = t.match(/^([\w$]+)\s*\([^)]*\)\s*\{/))) {
    // 控制流关键字长得和方法声明一模一样——第一版没排除，于是 `if (lockedAt != null) {` 被当成
    // 一个叫 if 的符号，`data.put(...)` / `const s = …` 这类方法体内语句同样混进来（分类器假阳 6 条）。
    if (CONTROL_FLOW.has(m[1])) return null;
    return m[1];
  }
  if ((m = t.match(/^([\w$]+)\s*:\s*(?:async\s*)?(?:function|\(|[\w$]+\.call)/)) && !CONTROL_FLOW.has(m[1])) return m[1]; // 对象方法
  if ((m = t.match(/^(onShow|onLoad|onReady|onUnload|onPullDownRefresh|onReady)\b/))) return m[1];
  return null;
}
const CONTROL_FLOW = new Set(["if", "for", "while", "switch", "catch", "return", "else", "new", "do", "try", "methods", "computed", "data", "watch", "mounted", "created"]);

const nameable = [];
const keep = [];
const seen = new Set();
for (const f of files) {
  const tl = lines(f);
  tl.forEach((raw, idx) => {
    let m;
    RE_POINTER.lastIndex = 0;
    while ((m = RE_POINTER.exec(raw)) !== null) {
      const target = m[1], nums = m[2];
      const first = Number(nums.match(/\d+/)[0]);
      const isRange = /\d\s*[-/]\s*\d/.test(nums);
      const key = f + "|" + (idx + 1) + "|" + target + "|" + nums;
      if (seen.has(key)) continue;
      seen.add(key);
      const base = path.basename(target);
      const cands = byName.get(base) || [];
      if (!cands.length) { keep.push({ file: f, line: idx + 1, target, nums, why: "FILE_NOT_INDEXED" }); continue; }
      // 多份同名文件时按指针里的路径尾段收敛
      const pool = cands.length === 1 ? cands : cands.filter((c) => c.replace(/\\/g, "/").endsWith(target.replace(/\\/g, "/")));
      const use = pool.length ? pool : cands;
      const at = lines(use[0])[first - 1];
      if (at === undefined) { keep.push({ file: f, line: idx + 1, target, nums, why: "OUT_OF_RANGE" }); continue; }
      const sym = symbolOf(at);
      if (!sym) {
        // 区间引用（X:N-M）的首行常常正是那个方法的声明行——那它同样能具名化（「的 foo 那一段」），
        // 我第一版把 81 条区间一律判成"必须留行号"，那是分类器过严而非事实。
        keep.push({ file: f, line: idx + 1, target, nums, why: isRange ? "RANGE_NOT_A_DECLARATION" : "NOT_A_DECLARATION", at: at.trim().slice(0, 46) });
        continue;
      }
      nameable.push({ file: f, line: idx + 1, target, num: first, sym, at: at.trim().slice(0, 46), range: isRange });
    }
  });
}

const byWhy = {};
for (const k of keep) byWhy[k.why] = (byWhy[k.why] || 0) + 1;
const byWorkspace = {};
for (const n of nameable) { const w = n.file.split(/[\\/]/)[0]; byWorkspace[w] = (byWorkspace[w] || 0) + 1; }

console.log(`指针总数 ${nameable.length + keep.length} 条｜可安全具名化 ${nameable.length}｜必须留行号 ${keep.length}`);
console.log("可具名化按工程:", JSON.stringify(byWorkspace));
console.log("留行号的原因分布:", JSON.stringify(byWhy));
const mode = process.argv.includes("--list") ? "list" : "brief";
if (mode === "list") {
  console.log("\n=== 可具名化（建议改写为「<target> 的 <sym>」） ===");
  for (const n of nameable.sort((a, b) => a.file.localeCompare(b.file) || a.line - b.line)) {
    console.log(`  ${n.file}:${n.line}  ->  ${n.target}:${n.num}  [${n.sym}]  行内容=${n.at}`);
  }
  console.log("\n=== 必须留行号 ===");
  for (const k of keep.sort((a, b) => a.why.localeCompare(b.why) || a.file.localeCompare(b.file))) {
    console.log(`  ${k.why}  ${k.file}:${k.line}  ->  ${k.target}:${k.nums}${k.at ? "  行内容=" + k.at : ""}`);
  }
}
