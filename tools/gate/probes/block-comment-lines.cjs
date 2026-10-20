#!/usr/bin/env node
// F-76｜块注释续行判据的唯一真相源：f27-gate.sh §2 与两个审计器（pointer-audit / symbol-audit）共用本模块，
// 因为三者此前只认「行首是 // * /* <!-- #」，`<!--`…`-->` / `/*`…`*/` 里的裸续行不进面（假 FAIL + 审计盲区）。
// 块种只有两种：`<!--`→`-->`（模板注释）与 `/*`→`*/`（C 块注释，Javadoc 是特例）；不认嵌套，与解析器一致。
// fail-closed：EOF 时仍未闭合的那一段一律算**非注释**；判不准（行号越界、文件读不到、rev 取不到）同样算非注释。
// 用法：
//   node block-comment-lines.cjs --diff-judge [--old REV] [--new REV] <path>   逐条判定该文件 diff 行：KIND\tBODY
//   node block-comment-lines.cjs --stat [path...]                              汇总「块注释续行」行数（默认扫 pointer-audit 那套 ROOTS）
//   node block-comment-lines.cjs --list [path...]                              逐行列出续行（file\tline\ttext）
const fs = require("fs");
const path = require("path");
const cp = require("child_process");

const KINDS = [["<!--", "-->"], ["/*", "*/"]];
// 与 pointer-audit.cjs / symbol-audit.cjs / pointer-nameable.cjs 完全同一套来源面与扩展名，
// 否则 --stat 报的续行数与它们看见的不是同一个面（四处必须一起改，F-77 把 front 升为来源面时就是四处同步）。
const ROOTS = ["backEnd/src/main", "backEnd/src/test", "backEnd/sql", "front-admin/src", "front-merchant", "front-partner", "front"];
const EXTS = new Set([".java", ".vue", ".js", ".ts", ".sql"]);
const SKIP_DIR = new Set(["node_modules", "unpackage", "dist", ".git", "target", "static"]);
// 两处旧的行首判据各自原样保留（R-B：扩面只做并集，原分支一条不许删），本模块只负责补「块注释续行」这一支：
//   RE_HEAD_F27 = f27-gate.sh §2 那一支的形状（**不含 `#`**——CSS 的 `#app {` 是代码，含进来就成放行）
//   RE_HEAD_TOOL = pointer-audit.cjs / symbol-audit.cjs 的形状（含 `#`），只用于 --stat 的差值口径
const RE_HEAD_F27 = /^(\s*(\/\/|\*|\/\*|<!--))/;
const RE_HEAD_TOOL = /^(\/\/|\/\*|\*|<!--|#)/;

// 这两种块注释只在下面这些方言里存在；别的扩展名（.sh/.yml/.bat/.md…的 `#` 才是行注释）一律**不算块续行**，
// 否则 `# front/** 必须零字节改动` 这种 shell 注释会开出幽灵块、把真代码放行（f27 §2 的致命方向）。
const BLOCK_CAPABLE = new Set([".java", ".vue", ".js", ".ts", ".cjs", ".mjs", ".jsx", ".tsx", ".sql", ".html", ".htm", ".css", ".scss", ".less"]);
const blockCapable = (p) => BLOCK_CAPABLE.has(path.extname(String(p)).toLowerCase());

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
function rootFiles() {
  const acc = [];
  for (const r of ROOTS) walk(r, acc);
  return acc;
}

// 逐行扫一遍：inside[i]=该行**开头**时已有一个块打开着（= 续行）；spans 记录每段的起止与是否闭合。
// code 态里从左往右走字符，先遇到谁就认谁：`//`（本行剩余不看）、引号串（跳到配对引号，串里没有块起始符）、
// 块起始符。少了前两条，写在行注释与字符串里的 `/*`（实测 `addResourceHandler("/uploads/**")`）会开出幽灵块，
// 把后面几百行真代码吞成「注释续行」——f27 侧就成了放行，方向正好是错的。
function scan(lines) {
  const n = lines.length;
  const inside = new Array(n).fill(false);
  const spanOf = new Array(n).fill(-1);
  const spans = [];
  let mode = -1, cur = -1;
  const open = (i, kind) => { spans.push({ start: i, end: -1, closed: false, kind }); cur = spans.length - 1; mode = kind; };
  for (let i = 0; i < n; i++) {
    const raw = lines[i] === undefined ? "" : String(lines[i]);
    if (mode !== -1) { inside[i] = true; spanOf[i] = cur; }
    let p = 0;
    while (p < raw.length) {
      if (mode !== -1) {
        const c = KINDS[mode][1];
        const at = raw.indexOf(c, p);
        if (at === -1) break;                                  // 块跨行：本行到此为止，下一行仍是续行
        spans[cur].end = i; spans[cur].closed = true;
        mode = -1; p = at + c.length;
        continue;
      }
      const ch = raw[p];
      if (ch === '"' || ch === "'") {
        let q = p + 1;
        while (q < raw.length) { if (raw[q] === "\\") { q += 2; continue; } if (raw[q] === ch) break; q++; }
        p = q >= raw.length ? raw.length : q + 1;              // 串没配上就当场作废，不跨行猜
        continue;
      }
      if (ch === "/" && raw[p + 1] === "/") break;             // 行注释吃掉本行剩余
      if (ch === "/" && raw[p + 1] === "*") { open(i, 1); p += 2; continue; }
      if (ch === "<" && raw.startsWith("<!--", p)) { open(i, 0); p += 4; continue; }
      p++;
    }
  }
  return { inside, spanOf, spans };
}

// 「块注释续行」的两种口径：
//   insideClosedMask  —— 所属块在 EOF 前闭合（f27 §2 用它把续行判成注释；未闭合那段不进这里 = fail-closed）
//   deltaMask         —— 续行且旧行首判据看不见（就是本批扩出来的那 300 行）
function noMask(lines) { return new Array(lines.length).fill(false); }
function insideClosedMask(lines, p) {
  if (!blockCapable(p)) return noMask(lines);
  const s = scan(lines);
  const out = new Array(s.inside.length).fill(false);
  for (let i = 0; i < s.inside.length; i++) {
    if (s.inside[i] && s.spans[s.spanOf[i]] && s.spans[s.spanOf[i]].closed) out[i] = true;
  }
  return out;
}
function insideMask(lines, p) {
  if (!blockCapable(p)) return noMask(lines);
  return scan(lines).inside;
}
function deltaMask(lines, p) {
  if (!blockCapable(p)) return noMask(lines);
  const s = scan(lines);
  const out = new Array(s.inside.length).fill(false);
  for (let i = 0; i < s.inside.length; i++) {
    const sp = s.spans[s.spanOf[i]];
    if (s.inside[i] && sp && sp.closed && !RE_HEAD_TOOL.test(String(lines[i]).trim()) && String(lines[i]).trim() !== "") out[i] = true;
  }
  return out;
}

function readLinesFromDisk(p) {
  try { return fs.readFileSync(p, "utf8").split(/\r?\n/); } catch (e) { return null; }
}
function git(args) {
  try {
    return cp.execFileSync("git", args, { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, stdio: ["ignore", "pipe", "pipe"] });
  } catch (e) { return null; }
}
function readLinesFromRev(rev, p) {
  const out = git(["show", `${rev}:${p.replace(/\\/g, "/")}`]);
  if (out === null) return null;
  return out.split(/\r?\n/);
}

function gitDiff(revs, p) {
  return git(["diff", "-U0", "--no-color", ...revs, "--", p]);
}
// KIND\tBODY：COMMENT 含旧行首判据命中的行（并集，原分支一字不动）与本模块新认的闭合块续行。
// 新增行用工作区（或 --new REV）文件算注释集合，删除行用 --old REV（默认 HEAD）的文件算；两类都覆盖。
function judgeDiff(p, oldRev, newRev) {
  const revs = oldRev ? (newRev ? [oldRev, newRev] : [oldRev]) : [];
  const out = gitDiff(revs, p);
  if (out === null) return [];
  let oldNo = 0, newNo = 0, inHunk = false;
  const rows = [];
  for (const line of out.split(/\r?\n/)) {
    if (/^[-+]{3}/.test(line)) { inHunk = false; continue; }
    const h = line.match(/^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@/);
    if (h) { oldNo = Number(h[1]); newNo = Number(h[3]); inHunk = true; continue; }
    if (!inHunk) continue;
    const sign = line[0];
    if (sign !== "+" && sign !== "-") continue;
    const body = line.slice(1);
    if (sign === "+") { rows.push({ sign, no: newNo, body }); newNo++; }
    else { rows.push({ sign, no: oldNo, body }); oldNo++; }
  }
  const newLines = newRev ? readLinesFromRev(newRev, p) : readLinesFromDisk(p);
  const oldLines = readLinesFromRev(oldRev || "HEAD", p);
  const newMask = newLines ? insideClosedMask(newLines, p) : null;
  const oldMask = oldLines ? insideClosedMask(oldLines, p) : null;
  return pairInlineComments(rows.map((r) => {
    const body = r.body;
    const t = String(body).trim();
    let kind;
    if (t === "") kind = "BLANK";
    else if (RE_HEAD_F27.test(body)) kind = "COMMENT";
    else {
      const mask = r.sign === "+" ? newMask : oldMask;
      kind = (mask && r.no >= 1 && r.no <= mask.length && mask[r.no - 1]) ? "COMMENT" : "NONCOMMENT";
    }
    return { kind, sign: r.sign, no: r.no, body };
  }));
}

// 剥掉行内注释后的**代码部分**。只处理同一行内的 `/* … */` 与首个 `//` 之后的内容。
// 不做词法分析（字符串里的 `//`、URL 会被一起切掉），这是刻意的：判据要的是「old 与 new 的
// 代码部分**逐字相等**」，同形被切两边仍相等；而真改了代码（`a = 1;` → `a = 2;`）切完还是不等，
// 所以剥得粗只会**少豁免**，不会**多放行**——方向必须是这样。
function codePart(s) {
  let x = String(s).replace(/\/\*[\s\S]*?\*\//g, "");
  const i = x.indexOf("//");
  if (i >= 0) x = x.slice(0, i);
  return x.replace(/\s+$/, "");
}

// F-83 CD5：`z-index: 500; /* … */`、`searchHistory: [], // …` 这类**代码与注释同一行**、
// 只改注释部分的行，行首判据与块续行状态机都判 NONCOMMENT → f27 §2 每批必吃这种假红
// （实测 F-83 一批 3 处 6 行）。配对规则保守：
//   ① 只在同一 hunk 内「连续的 - 段紧跟等长的 + 段」时按顺序配对；配不上就整段保持原判；
//   ② 只有 old/new 的代码部分**逐字相等**才把两行都改判 COMMENT；
//   ③ 两行原本至少有一行被判 NONCOMMENT 才需要这条豁免（纯注释行不受影响）。
function pairInlineComments(rows) {
  const res = rows.slice();
  let i = 0;
  while (i < res.length) {
    if (res[i].sign !== "-") { i++; continue; }
    let j = i; while (j < res.length && res[j].sign === "-") j++;
    let k = j; while (k < res.length && res[k].sign === "+") k++;
    const minus = res.slice(i, j), plus = res.slice(j, k);
    if (minus.length === plus.length && minus.length > 0) {
      for (let n = 0; n < minus.length; n++) {
        const a = minus[n], b = plus[n];
        if (a.kind === "COMMENT" && b.kind === "COMMENT") continue;
        if (a.body === b.body) continue;
        if (codePart(a.body) === codePart(b.body)) {
          res[i + n].kind = "COMMENT"; res[j + n].kind = "COMMENT";
        }
      }
    }
    i = k > i ? k : i + 1;
  }
  return res;
}

function main(argv) {
  const mode = argv[0];
  if (mode === "--diff-judge") {
    const rest = argv.slice(1);
    let oldRev = "", newRev = "";
    const pos = [];
    for (let i = 0; i < rest.length; i++) {
      if (rest[i] === "--old") { oldRev = rest[++i]; continue; }
      if (rest[i] === "--new") { newRev = rest[++i]; continue; }
      pos.push(rest[i]);
    }
    const p = pos[0];
    if (!p) { console.error("usage: --diff-judge [--old REV] [--new REV] <path>"); return 2; }
    for (const r of judgeDiff(p, oldRev, newRev)) process.stdout.write(`${r.kind}\t${r.body}\n`);
    return 0;
  }
  if (mode === "--stat" || mode === "--list") {
    const pos = argv.slice(1).filter((x) => !x.startsWith("--"));
    const files = pos.length ? pos : rootFiles();
    let contLines = 0, contFiles = 0, scanned = 0, unclosed = 0;
    for (const f of files) {
      const lines = readLinesFromDisk(f);
      if (!lines) continue;
      scanned++;
      const s = blockCapable(f) ? scan(lines) : { inside: [], spans: [] };
      if (s.spans.some((x) => !x.closed)) unclosed++;
      const d = deltaMask(lines, f);
      let c = 0;
      for (let i = 0; i < d.length; i++) {
        if (!d[i]) continue;
        c++;
        if (mode === "--list") process.stdout.write(`${f}\t${i + 1}\t${String(lines[i]).trim().slice(0, 90)}\n`);
      }
      if (c) { contFiles++; if (mode === "--stat") process.stdout.write(`  ${f}\t${c}\n`); }
      contLines += c;
    }
    process.stdout.write(`STAT scanned=${scanned} filesWithContinuation=${contFiles} continuationLines=${contLines} filesWithUnclosedBlock=${unclosed}\n`);
    return 0;
  }
  process.stdout.write("usage: --diff-judge [--old REV] [--new REV] <path> | --stat [path...] | --list [path...]\n");
  return 2;
}

module.exports = { KINDS, RE_HEAD_F27, RE_HEAD_TOOL, scan, insideMask, insideClosedMask, deltaMask, rootFiles, judgeDiff };
if (require.main === module) process.exitCode = main(process.argv.slice(2));
