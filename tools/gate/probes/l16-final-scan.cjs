// L-16 终验：找「非注释行里出现的、带具体时长的用户可见文案」——写死秒数冒充 429 恢复时机就是这类。
const fs = require("fs"), path = require("path");
const ROOTS = process.argv[2] ? [process.argv[2]] : ["front-merchant", "front-partner", "front-admin/src", "front"];
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

const DUR = /(半分钟|[0-9]+\s*(秒|分钟|s)\s*(后|再)|[0-9]+\s*秒后可|[0-9]+\s*秒内)/;
const hits = [];
for (const f of files) {
  fs.readFileSync(f, "utf8").split(/\r?\n/).forEach((raw, i) => {
    const t = raw.trim();
    if (/^(\/\/|\/\*|\*|<!--|#)/.test(t)) return;              // 整行注释：不算文案
    const noLineComment = t.replace(/\/\/.*$/, "").replace(/\/\*[\s\S]*?\*\//g, "");
    if (!DUR.test(noLineComment)) return;
    // 只关心会进用户视线的：字符串字面量、模板插值、uni.showToast/showModal 的 title
    const m = noLineComment.match(/['"`][^'"`]{0,40}['"`]/g);
    if (!m) return;
    const lit = m.find((s) => DUR.test(s));
    if (!lit) return;
    hits.push({ f, line: i + 1, lit, ctx: noLineComment.slice(0, 90) });
  });
}
console.log(`扫描 ${files.length} 个前端文件，命中「带具体时长的可见文案」${hits.length} 处`);
for (const h of hits) console.log(`  ${h.f}:${h.line}  ${h.lit}\n        ${h.ctx}`);
