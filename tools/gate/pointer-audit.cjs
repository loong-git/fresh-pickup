const fs = require("fs");
const path = require("path");

// 扫描四端源码里「注释中的跨文件行号指针」，逐条与真实代码对账，只吐可疑项。
// 判据（保守，宁可多报由人判）：
//   SUSPECT_EMPTY  目标行是空行 / 只有 } ) ] ; 等收尾符 —— 引用某处却落在无意义行，几乎必是漂移
//   AMBIGUOUS      目标文件名在仓库里有多份，无法确定指的是哪一个
//   DRIFT(--drift)  注释写下那一刻目标行的内容，与现在同一行号的内容不同 —— 行号漂到别的有效代码上，
//                  isJunk 抓不到（那是 junk 判据的结构性盲区，F-26 里靠子代理 git 考古才补上的）
const ROOTS = ["backEnd/src/main", "front-admin/src", "front-merchant", "front-partner"];
// 索引面要比扫描面宽：三端注释里大量引用 C 端 front/ 的文件（detail.vue/order.vue/search.vue…），
// 不把 front 收进索引就会把它们误报成 NOT_FOUND（我第一版就误报了 19 条）。front 只被索引、不被扫描。
const INDEX_ONLY = ["front"];
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
for (const r of INDEX_ONLY) walk(r, indexed);

// 建索引：basename -> 全路径列表
const byName = new Map();
for (const f of indexed) {
  const b = path.basename(f);
  if (!byName.has(b)) byName.set(b, []);
  byName.get(b).push(f);
}

// 【第六轮盲区修法｜无扩展名的类名指针】F-45 的页注释整片写的是 `MerchantDishServiceImpl:166/263/310/333`、
// `AdminServiceImpl:521`、`AdminV2ServiceImpl:99` 这种「类名 + 号、不带 .java」的形态。主 RE 与裸号回溯都要求
// anchor 带扩展名（`RE_ANCHOR` 的后缀是 .java|.vue|.js|.ts），symbol-audit 又只管 `Foo#bar` 这种带成员的形态
// → 这类指针此前**在两个审计器之间是漏缝**（实测：F-45 改后扫描面与计数一字未动，即一条都没被看过）。
// 判据沿用主 RE 那一路（范围越界 / 命中空行或纯收尾符 / 区间反序），但**解不出唯一文件就静默跳过**：
// `HTTP:400`、`ERROR:1142`、`JDK:17` 这类"大写字母 + 冒号 + 数字"在注释里大量是错误码而非指针，
// 报出来就是几千条噪音——本工具的铁律是宁可少报，报出来必须能人工复核。
const byBase = new Map();
for (const f of indexed) {
  const b = path.basename(f).replace(/\.(java|vue|js|ts)$/, "");
  if (!byBase.has(b)) byBase.set(b, []);
  byBase.get(b).push(f);
}
const RE_CLASS = /([A-Z][A-Za-z0-9_]{2,}):([0-9]+(?:-[0-9]+)?(?:\s*\/\s*[0-9]+(?:-[0-9]+)?)*)/g;

const isJunk = (line) => {
  const s = (line || "").trim();
  return s === "" || /^[\}\)\];,]+$/.test(s) || /^<\/(template|script|style)>$/.test(s);
};

// 目标写法可以是全路径/子路径/裸文件名：先按尾部路径匹配，匹配不上才退回 basename。
// 之前的正则字符类不含 "/"，`front/api/index.js:70` 会被截成 `index.js:70`，
// 于是四端同名的 index.js 让跨端引用天然落进 AMBIGUOUS（假阳性，F-26 清单里的 #17 就是它造成的）。
const RE = /((?:[A-Za-z0-9_.@-]+[\\/]+)*[A-Za-z0-9_.-]+\.(?:java|vue|js|ts))[:：]([0-9]+(?:\s*[-/]\s*[0-9]+)*)/g;

const norm = (p) => p.replace(/\\/g, "/");
function resolveCandidates(target, fromFile) {
  const t = norm(target);
  if (t.includes("/")) {
    const tail = indexed.filter((f) => norm(f).endsWith(t));
    if (tail.length !== 1) return { cands: tail, kind: tail.length ? "AMBIGUOUS" : "NOT_FOUND" };
    return { cands: tail };
  }
  let cands = byName.get(target) || [];
  if (cands.length === 0) return { cands, kind: "NOT_FOUND" };
  if (cands.length > 1) {
    // 同端优先消歧：引用方与目标在同一端目录时，几乎必然指本端那一份
    const mine = norm(fromFile).split("/")[0];
    const sameEnd = cands.filter((c) => norm(c).split("/")[0] === mine);
    if (sameEnd.length === 1) cands = sameEnd;
    else return { cands, kind: "AMBIGUOUS" };
  }
  return { cands };
}

// --drift：把注释行的 blame commit 当作「这句指针被写下（或最后触碰）的时刻」，
// 用该 commit 下目标文件的同一行号内容与现在比对。不同 => 行号从写下起已漂走。
const WANT_DRIFT = process.argv.includes("--drift");
const cp = require("child_process");
const blameCache = new Map();
function blameCommit(file, line) {
  if (!blameCache.has(file)) {
    const map = {};
    try {
      // porcelain 每条元数据块以一行 \t 开头的内容行收尾；块头是 "<sha> <原行号> <当前行号>"
      const out = cp.execFileSync("git", ["blame", "--line-porcelain", "--", file], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
      let sha = null, lnNo = null;
      for (const ln of out.split(/\r?\n/)) {
        const h = ln.match(/^([0-9a-f]{40}) \d+ (\d+)/);
        if (h) { sha = h[1]; lnNo = Number(h[2]); continue; }
        if (ln.startsWith("\t")) { if (lnNo != null) map[lnNo] = sha; sha = null; lnNo = null; }
      }
    } catch (e) { /* 不在 git 管理下 / blame 失败 */ }
    blameCache.set(file, map);
  }
  return blameCache.get(file)[line] || null;
}

const showCache = new Map();
function lineAtCommit(commit, file, line) {
  const k = commit + "|" + file + "|" + line;
  if (showCache.has(k)) return showCache.get(k);
  let v = null;
  try {
    const out = cp.execFileSync("git", ["show", `${commit}:${norm(file)}`], { encoding: "utf8", maxBuffer: 32 * 1024 * 1024 });
    v = out.split(/\r?\n/)[line - 1] || "";
  } catch (e) { v = null; }
  showCache.set(k, v);
  return v;
}

// 第五轮盲区的修法（F-34 执行者指出）：**号与所属文件名在文本上分离**时，上面的 RE 根本命中不了。
// 两种真实形态：
//   ①「照 dishes.vue:187-188（data）/ :417 / :464（startRetryLock）」——anchor 带名，续号裸写且被括号打断
//   ②「母版调 getMerchantMe()（:291 与 :341 两处）」——号裸写，文件名只在更早的散文里出现（甚至省扩展名）
// 做法：一行里先按 RE 处理"名:号"；再把行内**剩余的** `:NNN` 回溯绑定到它左边最近的 anchor 文件，按同判据验。
// 报出来的 kind 前缀 BARE_ 以区别于紧邻式指针，方便我判断是新盲区还是老问题。
const RE_BARE = /[:：]([0-9]+)(?:\s*[-–]\s*([0-9]+))?/g;
const RE_ANCHOR = /([A-Za-z0-9_.-]+\.(?:java|vue|js|ts))/g;
const findings = [];
let total = 0;
let totalMulti = 0;
let bareTotal = 0;
let classTotal = 0;
// blame 给的是「该注释行最后一次被触碰」的 commit，不等于「指针被写下的那一刻」——
// 这条差别在 F-27 里被执行者用 #2 抓到（blame 到 d8b94af 时目标行是另一段 throw，既不是今天的常量，
// 也从来不是它引用的"反面教材"）。所以 `--drift` 的「末触快照」列只能当**线索**，必须连 commit 日期/标题一起看。
const whenCache = new Map();
function commitAt(commit) {
  if (whenCache.has(commit)) return whenCache.get(commit);
  let v = "?";
  try { v = cp.execFileSync("git", ["log", "-1", "--format=%ad %s", "--date=short", commit], { encoding: "utf8" }).trim().slice(0, 46); } catch (e) { /* noop */ }
  whenCache.set(commit, v);
  return v;
}

for (const f of files) {
  const lines = fs.readFileSync(f, "utf8").split(/\r?\n/);
  lines.forEach((raw, idx) => {
    const trimmed = raw.trim();
    // 只看注释行，避免把代码里的字符串常量当指针
    if (!/^(\/\/|\/\*|\*|<!--|#)/.test(trimmed) && !/(\*|\/\/).*[A-Za-z0-9_-]+\.(java|vue|js|ts):[0-9]/.test(raw)) return;
    let m;
    RE.lastIndex = 0;
    while ((m = RE.exec(raw)) !== null) {
      const [, target, nums] = m;
      // 一条指针里可以并列多个号：`X:213/229/241`、`X:199/358`、`X:145-147`。
      // 旧版只审计第一个号（F-27 执行者指出：#3/#49/#58 的续号从未被看过）——现在逐个号都审。
      const segs = [];
      String(nums).split("/").forEach((p) => {
        const mm = p.trim().match(/^([0-9]+)(?:-([0-9]+))?$/);
        if (mm) segs.push({ a: Number(mm[1]), b: mm[2] ? Number(mm[2]) : null });
      });
      if (!segs.length) continue;
      if (segs.length > 1) totalMulti++;
      const rc = resolveCandidates(target, f);
      if (rc.kind) { findings.push({ file: f, line: idx + 1, kind: rc.kind, target, a: segs[0].a, n: rc.cands.length }); continue; }
      const tl = fs.readFileSync(rc.cands[0], "utf8").split(/\r?\n/);
      const commit = WANT_DRIFT ? blameCommit(f, idx + 1) : null;
      for (const s of segs) {
        const a = s.a;
        total++;
        const hit = tl[a - 1];
        if (a > tl.length) { findings.push({ file: f, line: idx + 1, kind: "OUT_OF_RANGE", target, a, len: tl.length }); continue; }
        if (isJunk(hit)) { findings.push({ file: f, line: idx + 1, kind: "SUSPECT_EMPTY", target, a, at: (hit || "").trim().slice(0, 40) }); continue; }
        if (WANT_DRIFT && commit && !/^0+$/.test(commit)) {
          const then = lineAtCommit(commit, rc.cands[0], a);
          if (then != null) {
            const tt = String(then).trim(), nt = String(hit || "").trim();
            if (tt !== nt) findings.push({ file: f, line: idx + 1, kind: "DRIFT", target, a, at: nt.slice(0, 44), then: tt.slice(0, 44), commit: commit.slice(0, 7), when: commitAt(commit) });
          }
        }
        if (s.b != null) {
          // 区间引用（X:N-M）的末行是 } 或空行是**正常的**——它恰恰说明区间覆盖到一个块结尾。
          // 只有「末行超出文件长度」或「末行 < 首行」才是坏指针。（我第一版把末行 junk 也算可疑，误报 24 条）
          if (s.b > tl.length) findings.push({ file: f, line: idx + 1, kind: "OUT_OF_RANGE", target, a, b: s.b, len: tl.length });
          else if (s.b < a) findings.push({ file: f, line: idx + 1, kind: "REVERSED_RANGE", target, a, b: s.b });
        }
      }
    }
    // —— 无扩展名类名指针（第六轮盲区修法，判据与主 RE 同一条）——
    RE_CLASS.lastIndex = 0;
    let cm;
    while ((cm = RE_CLASS.exec(raw)) !== null) {
      const cname = cm[1];
      const csegs = [];
      String(cm[2]).split("/").forEach((p) => {
        const mm = p.trim().match(/^([0-9]+)(?:-([0-9]+))?$/);
        if (mm) csegs.push({ a: Number(mm[1]), b: mm[2] ? Number(mm[2]) : null });
      });
      if (!csegs.length) continue;
      const ccands = byBase.get(cname) || [];
      if (ccands.length !== 1) continue;                       // 解不出唯一文件（错误码、外部类、同名多份）→ 静默跳过
      const ctl = fs.readFileSync(ccands[0], "utf8").split(/\r?\n/);
      for (const s of csegs) {
        classTotal++;
        const chit = ctl[s.a - 1];
        if (s.a > ctl.length) { findings.push({ file: f, line: idx + 1, kind: "CLASS_OUT_OF_RANGE", target: cname, a: s.a, len: ctl.length }); continue; }
        if (isJunk(chit)) { findings.push({ file: f, line: idx + 1, kind: "CLASS_SUSPECT_EMPTY", target: cname, a: s.a, at: (chit || "").trim().slice(0, 40) }); continue; }
        if (s.b != null && s.b > ctl.length) findings.push({ file: f, line: idx + 1, kind: "CLASS_OUT_OF_RANGE", target: cname, a: s.a, b: s.b, len: ctl.length });
        else if (s.b != null && s.b < s.a) findings.push({ file: f, line: idx + 1, kind: "CLASS_REVERSED_RANGE", target: cname, a: s.a, b: s.b });
      }
    }
    // —— 裸号回溯绑定（第五轮盲区修法）——
    const anchors = [];
    RE_ANCHOR.lastIndex = 0;
    let am;
    while ((am = RE_ANCHOR.exec(raw)) !== null) anchors.push({ at: am.index, name: am[1] });
    if (anchors.length) {
      RE_BARE.lastIndex = 0;
      let bm;
      while ((bm = RE_BARE.exec(raw)) !== null) {
        const pos = bm.index;
        const prevChar = raw[pos - 1];
        if (prevChar === '"' || prevChar === "'") continue;              // JSON 示例里的 "code":401 不是指针
        if (/#([A-Za-z0-9_]+)$/.test(raw.slice(Math.max(0, pos - 40), pos))) continue; // Foo#bar:NN 归 symbol-audit 管
        if (anchors.some((x) => x.at + x.name.length === pos)) continue;  // 紧邻式已由主 RE 处理过
        const left = anchors.filter((x) => x.at < pos).pop();
        if (!left) continue;
        const a = Number(bm[1]);
        const b = bm[2] ? Number(bm[2]) : null;
        bareTotal++;
        const rc = resolveCandidates(left.name, f);
        if (rc.kind) continue;                                            // 名字歧义的交给主 RE 那一路报，不重复计
        const tl = fs.readFileSync(rc.cands[0], "utf8").split(/\r?\n/);
        const hit = tl[a - 1];
        if (a > tl.length) { findings.push({ file: f, line: idx + 1, kind: "BARE_OUT_OF_RANGE", target: left.name, a }); continue; }
        if (isJunk(hit)) { findings.push({ file: f, line: idx + 1, kind: "BARE_SUSPECT_EMPTY", target: left.name, a, at: (hit || "").trim().slice(0, 40) }); continue; }
        if (b != null && b > tl.length) findings.push({ file: f, line: idx + 1, kind: "BARE_OUT_OF_RANGE", target: left.name, a, b, len: tl.length });
        else if (b != null && b < a) findings.push({ file: f, line: idx + 1, kind: "BARE_REVERSED_RANGE", target: left.name, a, b });
      }
    }
  });
}

const byKind = {};
for (const x of findings) byKind[x.kind] = (byKind[x.kind] || 0) + 1;
console.log(`扫描文件 ${files.length} 个，发现跨文件行号指针 ${total} 条（含 ${totalMulti} 条并列多号），另经回溯绑定收到裸号 ${bareTotal} 条、无扩展名类名指针 ${classTotal} 条，可疑 ${findings.length} 条`);
console.log("分类:", JSON.stringify(byKind, null, 0));
console.log("");
for (const x of findings.sort((p, q) => p.kind.localeCompare(q.kind) || p.file.localeCompare(q.file))) {
  console.log(`  ${x.kind}  ${x.file}:${x.line}  -> ${x.target}:${x.a}${x.b ? "-" + x.b : ""}${x.at !== undefined ? "  该行=[" + x.at + "]" : ""}${x.then !== undefined ? "  末触快照=[" + x.then + "]" : ""}${x.commit ? "  @" + x.commit + (x.when ? " (" + x.when + ")" : "") : ""}${x.n ? "  同名文件数=" + x.n : ""}`);
}
