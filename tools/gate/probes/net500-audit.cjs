// F-28.2/X-2 的静态判据：「把服务端 500 说成网络故障」这类因果倒置，不许在三端任何一页出现。
// 用法：node logs/scratch38/net500-audit.cjs [基线commit]     —— 给了 commit 就按 `git show <commit>:<file>` 审历史版本（变异自验用）
const fs = require("fs"), path = require("path"), cp = require("child_process");
const BASE = process.argv[2] || null;
const ROOTS = ["front-merchant", "front-partner", "front-admin/src", "front"];
const SKIP = new Set(["node_modules", "unpackage", "dist", ".git", "target", "static"]);
const files = [];
for (const r of ROOTS) (function w(d) {
  let es; try { es = fs.readdirSync(d, { withFileTypes: true }); } catch (e) { return; }
  for (const e of es) {
    if (e.name.startsWith(".") || SKIP.has(e.name)) continue;
    const p = path.join(d, e.name);
    if (e.isDirectory()) w(p); else if (/\.(vue|js)$/.test(e.name)) files.push(p);
  }
})(r);

const NET_WORDS = ["检查网络", "网络异常", "网络不通", "网络问题", "检查代理", "请检查网络", "Wi-Fi", "wifi"];
const BAD = [];
let reviewed = 0;

// 取一个函数的花括号体（起点是首个 { ，按嵌套配平）
function bodyOf(src, fromIdx) {
  let i = src.indexOf("{", fromIdx);
  if (i < 0) return null;
  let depth = 0, start = i;
  for (; i < src.length; i++) {
    if (src[i] === "{") depth++;
    else if (src[i] === "}") { depth--; if (depth === 0) return src.slice(start + 1, i); }
  }
  return null;
}
// 去掉整行注释与块注释，避免"注释里提到检查网络"被误判成文案
function stripComments(src) {
  return src.split(/\r?\n/).filter((l) => !/^\s*(\/\/|\*|\/\*|<!--)/.test(l))
    .join("\n").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/[^\n]*/g, "");
}

const PATTERNS = [/errorLeadText\s*\(/, /errorText\s*[(:]/, /CODE_HINTS/, /writeHintOf\s*\(/, /stateDesc|hintOf\s*\(/];
for (const f of files) {
  let src;
  // stdio 必须显式 pipe：execFileSync 的 stderr 默认是 **inherit**，git 的 `fatal: path ... exists on disk,
  // but not in '<commit>'` 会直接漏进父进程 stderr。门禁取的是 `... | head -1`，于是那行 fatal 成了"第一行输出"，
  // 真正的「命中 N 处」读数被挤掉 → 变异自验假红（F-55 实测：新增 front-admin/src/constants.js 后必现）。
  try { src = BASE ? cp.execFileSync("git", ["show", `${BASE}:${normf(f)}`], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }) : fs.readFileSync(f, "utf8"); } catch (e) { continue; }
  const clean = stripComments(src);
  for (const re of PATTERNS) {
    let m;
    const R = new RegExp(re.source, "g");
    while ((m = R.exec(clean)) !== null) {
      const body = bodyOf(clean, m.index);
      if (!body) continue;
      reviewed++;
      const hasNet = NET_WORDS.some((w) => body.includes(w));
      const has500 = /500/.test(body);
      // 坏形态：这段错误分派里有「网络」类措辞，却完全没有 500 一支 → 500 必然落进那句网络话
      if (hasNet && !has500) {
        const line = src.slice(0, src.indexOf(body.trim().slice(0, 40))).split(/\r?\n/).length;
        BAD.push({ f, fn: re.source, line, sample: body.split(/\r?\n/).filter((l) => NET_WORDS.some((w) => l.includes(w))).map((s) => s.trim())[0] });
      }
    }
  }
}
function normf(p) { return p.split("\\").join("/"); }
console.log(`审查 ${files.length} 个文件、${reviewed} 段错误分派逻辑，判据「有网络措辞但无 500 分流」命中 ${BAD.length} 处`);
for (const b of BAD) console.log(`  NET_FOR_500?  ${b.f}  (${b.fn})  ${b.sample ? "「" + b.sample.slice(0, 60) + "」" : ""}`);
