const fs = require("fs");
const path = require("path");

// F-30 具名引用审计器：验「注释里写的符号名到底存不存在」。
// 为什么需要它：F-26/F-27 把 52 条行号指针换成符号名，pointer-audit 的行号审计面从 195 缩到 144，
// 而**换出来的符号名此前没有任何工具验证**。本仓已实测到真实残留：
//   ResponseUtil.java:28 写着 `GlobalExceptionHandler#handleBiz:31-34`，而 GlobalExceptionHandler.java 这个文件**根本不存在**。
// 判据（宁可少报，报出来必须能人工复核）：
//   CLASS_FILE_NOT_FOUND  引用形如 `Foo#bar`，但仓库里找不到 Foo.(java|vue|js) —— 铁定的坏引用
//   SYMBOL_NOT_FOUND      目标文件存在，但成员名在整份文件里一次都没出现 —— 铁定的坏引用
//   SYMBOL_MAYBE          成员名出现过，却没有任何"声明形态"（只被调用/只在注释里提） —— 可疑，人判
//   HYBRID_POINTER        `Foo#bar:NN` 符号后仍挂行号：符号可验，行号仍会漂，应删号只留符号
//   SECTION_NOT_FOUND     「X.vue 的『某小节』」这类中文标题引用，目标文件里没有这段逐字标题
// —— 以下是 F-35/F-43 补的**形态 C**（裸符号引用，F-27 定案「具名化是唯一稳态」之后的主流写法）：
//   NAMED_FILE_NOT_FOUND  「a.vue / b.vue 的 symbolName」里的某个文件在仓库中不存在
//   NAMED_SYMBOL_NOT_FOUND 文件在，但那个符号名在目标文件里一次都没出现
//     ⚠ 符号字符类必须吃连字符（`verify-feedback-ghost` 整名），否则会在 `-` 处被切断、
//     只拿首段去验 —— 实测那样会让一个根本不存在的中缀连字符名**判成合法**（假绿，已修并留自验）。
//   与 pointer-audit 的分工是闭合的：符号后一旦再挂行号（`x.vue 的 foo:12`），本正则匹配不到，
//   那种形态归 pointer-audit 判越界/空行。
// 【ROOTS 必须含 backEnd/src/test】注释里 `WxPayNotifyTest#everyRejectBranch...` 这类指向测试类的具名引用
// 是真实存在的（F-44 出货代码里就有，且方法名逐字对得上），但 test 树此前不在索引里 → 判成
// CLASS_FILE_NOT_FOUND 的**假阳**。F-28 定过同类纪律：索引/扫描面必须覆盖被指向的树，否则计数不可用。
const ROOTS = ["backEnd/src/main", "backEnd/src/test", "backEnd/sql", "front-admin/src", "front-merchant", "front-partner"];
const INDEX_ONLY = ["front"];
const EXTS = new Set([".java", ".vue", ".js", ".ts", ".sql"]);
const SKIP_DIR = new Set(["node_modules", "unpackage", "dist", ".git", "target", "static"]);
const norm = (p) => p.split("\\").join("/");

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

// 类名 -> 文件列表（Foo -> [path/Foo.java, ...]）
const byClass = new Map();
for (const f of indexed) {
  const base = path.basename(f).replace(/\.[^.]+$/, "");
  if (!byClass.has(base)) byClass.set(base, []);
  byClass.get(base).push(f);
}
const contentCache = new Map();
function content(f) {
  if (!contentCache.has(f)) contentCache.set(f, fs.readFileSync(f, "utf8"));
  return contentCache.get(f);
}
function isCommentLine(line) {
  const t = line.trim();
  // `--` 是 SQL 的现行注释（F-49 补：m20/init.sql 的整段口径注释里全是 `Xxx.java:NN` 与 `Foo#bar` 引用，
  // 旧版把 .sql 加了进 EXTS 却漏了这一条 → SQL 注释里的引用一条都没验过，还打着「可疑 0」的绿）。
  return /^(\/\/|\/\*|\*|<!--|--|#)/.test(t) || /(?:\/\/|\*)[^\n]*[A-Za-z0-9_]+[#.]/.test(line);
}

const RE_HASH = /([A-Za-z0-9_]{3,})#([a-zA-Z_][a-zA-Z0-9_]*)((?::[0-9]+(?:\s*[-/]\s*[0-9]+)?))?/g;
const RE_SECTION = /([A-Za-z0-9_.-]+\.(?:java|vue|js|ts|sql))\s*(?:的|里)\s*[「『]([^」』]{2,24})[」』]/g;
// 形态 C：`(文件[ / 、 与]*N+) 的|里 符号名`，符号名不含中文、至少 3 字符。
// 连字符复合名必须整体吃掉（CSS 类族 `.verify-feedback-ok` 就是这样）：第一版字符类没有 `-`，
// 于是 `verify.vue 的 verify-feedback-ghost` 被切成 `verify` 去验——ghost 名照样判合法，实测假绿。
const RE_NAMED = /((?:[A-Za-z0-9_./@-]+\.(?:java|vue|js|ts|cjs|mjs|sql)(?:\s*(?:\/|、|与|和|,)\s*)?)+)\s*(?:的|里)\s*([A-Za-z_][A-Za-z0-9_]{2,}(?:-[A-Za-z0-9_]+)*)(?![\w-])/g;

// 全仓 import 过的类名集合：注释里 `Foo#bar` 的 Foo 若在其中，就是 JDK/Spring/MyBatis-Plus 等**外部类**，
// 不该要求它在本仓有源文件。跨端引用（前端 .vue 的注释提后端 Java 类）也靠这条覆盖——
// 只查"本文件有没有 import"会漏（我第一版就这么误报了 LambdaUpdateWrapper#set）。
const importedNames = new Set();
for (const f of indexed) {
  const t = content(f);
  let im;
  const RE_JAVA_IMPORT = /^\s*import\s+(?:static\s+)?([\w.]+)\s*;/gm;
  while ((im = RE_JAVA_IMPORT.exec(t)) !== null) importedNames.add(im[1].split(".").pop());
  const RE_JS_IMPORT = /import\s+\{([^}]*)\}\s+from/g;
  while ((im = RE_JS_IMPORT.exec(t)) !== null) im[1].split(",").forEach((x) => { const n = x.trim().split(/\s+as\s+/)[0]; if (n) importedNames.add(n); });
}
const findings = [];
const infos = [];
let checked = 0, hybrid = 0, sections = 0, external = 0, named = 0;

for (const f of files) {
  const lines = content(f).split(/\r?\n/);
  lines.forEach((raw, idx) => {
    if (!isCommentLine(raw)) return;
    let m;
    RE_HASH.lastIndex = 0;
    while ((m = RE_HASH.exec(raw)) !== null) {
      const cls = m[1], member = m[2], withLine = m[3];
      // Lombok / 常量 / CSS 选择器等形态先排除：成员名全大写视为常量引用
      const cands = byClass.get(cls) || [];
      checked++;
      if (cands.length === 0) {
        // 外部类（JDK/Spring/MyBatis-Plus…）在本仓没有源文件是**正常的**，按全仓 import 集合放行
        if (importedNames.has(cls)) { external++; continue; }
        findings.push({ kind: "CLASS_FILE_NOT_FOUND", file: f, line: idx + 1, ref: cls + "#" + member });
        continue;
      }
      // 同端优先，再退化到"任一候选里能找到就算活"
      const mine = norm(f).split("/")[0];
      const pool = cands.filter((c) => norm(c).split("/")[0] === mine).length ? cands.filter((c) => norm(c).split("/")[0] === mine) : cands;
      const anywhere = pool.some((c) => new RegExp("\\b" + member + "\\b").test(content(c)));
      // Lombok 例外：`Store#getLng` 的 getter 由 @Data/@Getter 生成，源码里**一次都不会出现** getLng，
      // 所以"成员名没出现"对它必然误报。判据改回字段本身：get/is/set + Prop → 存在同名 prop 字段即合法。
      const ACCESSOR = member.match(/^(?:get|is|set)([A-Z][A-Za-z0-9_]*)$/);
      if (!anywhere && ACCESSOR) {
        const prop = ACCESSOR[1].charAt(0).toLowerCase() + ACCESSOR[1].slice(1);
        const hasField = pool.some((c) => new RegExp("(?:private|protected|public)\\s+[\\w<>,\\[\\] ?.]+\\s+" + prop + "\\s*;").test(content(c)));
        if (hasField) { infos.push({ kind: "LOMBOK_ACCESSOR", file: f, line: idx + 1, ref: cls + "#" + member }); continue; }
      }
      if (!anywhere) {
        findings.push({ kind: "SYMBOL_NOT_FOUND", file: f, line: idx + 1, ref: cls + "#" + member, in: pool.map((p) => path.basename(p)).join(",") });
        continue;
      }
      // 声明形态：方法定义 / 字段 / 类与接口 / CSS 类 / data 键
      const DECL = new RegExp("(?:^|\\s)(?:public|private|protected|static|final|void|int|long|boolean|String|LocalDate|Map|List|def|function|const|let|var)[\\s<][^\\n]*\\b" + member + "\\s*(?:\\(|[;=])");
      const DECL2 = new RegExp("^\\s*(?:" + member + ")\\s*\\(|(?:class|interface|enum)\\s+" + member + "\\b|^\\s*" + member + "\\s*:");
      const declared = pool.some((c) => {
        const t = content(c);
        return DECL.test(t) || DECL2.test(t) || new RegExp("\\." + member + "\\s*\\{|" + member + "\\s*\\(").test(t);
      });
      if (!declared) infos.push({ kind: "SYMBOL_MAYBE", file: f, line: idx + 1, ref: cls + "#" + member });
      if (withLine) { hybrid++; findings.push({ kind: "HYBRID_POINTER", file: f, line: idx + 1, ref: cls + "#" + member + withLine }); }
    }
    RE_SECTION.lastIndex = 0;
    while ((m = RE_SECTION.exec(raw)) !== null) {
      const target = m[1], title = m[2];
      sections++;
      const cands = (byClass.get(target.replace(/\.[^.]+$/, "")) || []).filter((c) => norm(c).endsWith(target.split("/").pop()));
      const full = indexed.filter((c) => norm(c).endsWith(norm(target)));
      const poolF = full.length ? full : cands;
      if (!poolF.length) { findings.push({ kind: "CLASS_FILE_NOT_FOUND", file: f, line: idx + 1, ref: target + " 的「" + title + "」" }); continue; }
      if (!poolF.some((c) => content(c).includes(title))) {
        findings.push({ kind: "SECTION_NOT_FOUND", file: f, line: idx + 1, ref: target + " 的「" + title + "」" });
      }
    }

    // 形态 C｜`a.vue / b.vue 的 symbolName`——**裸符号引用**（无 `#`、无书名号、无行号）。
    // 这是 F-27 定案"具名化是唯一稳态"之后的主流写法，但本工具此前只认形态 A(#)/B(「小节」)，
    // 于是 F-35 把 5 条指针改成这个形式后，`可疑 0` 对它等于**没验**（假绿灯）。
    // 与 pointer-audit 的分工：一旦符号后又挂了行号，本正则就匹配不上（`\s*(?:的|里)` 要求紧跟），
    // 那种由 pointer-audit 判越界/空行——所以这里抓到的一定是"纯具名"，验的就是"那个名字还在不在"。
    RE_NAMED.lastIndex = 0;
    while ((m = RE_NAMED.exec(raw)) !== null) {
      const filesPart = m[1], sym = m[2];
      const targets = [...filesPart.matchAll(/[A-Za-z0-9_./@-]+\.(?:java|vue|js|ts|cjs|mjs|sql)/g)].map((x) => x[0]);
      for (const target of targets) {
        named++;
        const base = target.split("/").pop();
        const full = indexed.filter((c) => norm(c).endsWith(norm(target)));
        const poolF = full.length ? full : (byClass.get(base.replace(/\.[^.]+$/, "")) || []);
        if (!poolF.length) { findings.push({ kind: "NAMED_FILE_NOT_FOUND", file: f, line: idx + 1, ref: target + " 的 " + sym }); continue; }
        if (!poolF.some((c) => new RegExp("\\b" + sym + "\\b").test(content(c)))) {
          findings.push({ kind: "NAMED_SYMBOL_NOT_FOUND", file: f, line: idx + 1, ref: target + " 的 " + sym, in: poolF.map((p) => path.basename(p)).join(",") });
        }
      }
    }
  });
}

const byKind = {};
for (const x of findings) byKind[x.kind] = (byKind[x.kind] || 0) + 1;
console.log(`扫描 ${files.length} 个文件，验具名引用 ${checked} 处 + 中文小节标题 ${sections} 处 + 裸符号引用 ${named} 处，可疑 ${findings.length} 条（另有 ${external} 处是指向 JDK/Spring/MyBatis 等外部类，已按 import 语句判为合法跳过）`);
console.log("分类:", JSON.stringify(byKind, null, 0), "｜ 其中符号后仍挂行号 =", hybrid, "｜ 低置信单列(不当可疑) =", infos.length);
console.log("");
for (const x of infos.sort((p, q) => p.kind.localeCompare(q.kind) || p.file.localeCompare(q.file))) {
  console.log(`  [info] ${x.kind}  ${x.file}:${x.line}  ${x.ref}`);
}
console.log("");
for (const x of findings.sort((p, q) => p.kind.localeCompare(q.kind) || p.file.localeCompare(q.file))) {
  console.log(`  ${x.kind}  ${x.file}:${x.line}  ${x.ref}${x.in ? "  (候选文件=" + x.in + ")" : ""}`);
}
