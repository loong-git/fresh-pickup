#!/usr/bin/env node
// P10-4 B5｜对比度审计（报告型）——契约 docs/十期规划/P10-4-四端视觉对齐与质量防线契约.md §B5
// 读三端 App.vue 的变量定义（商家/主理人 :root 浅组 + 运营端 :root 浅组与 html.dark 深组），
// 对写死在下面的语义对清单算 WCAG 2.x 对比度，报告写 logs/out/contrast-report.txt。
// **退出码恒 0、不进 f28 FAILS**：这是给用户过目的报告，不是门禁——契约 §B5 定案
// 「不改 P7/P9 定稿 token 值本身（弱提示文字弱化是设计意图），低于 AA 的对逐条列出并标『设计取舍』」。
// 语义对清单只引用变量名，值从 App.vue 现场解析——token 改值后报告自动跟上，不用改本脚本。
const fs = require("fs");
const path = require("path");

// —— sRGB 相对亮度（WCAG 2.x 官方公式）——
// 线性化：c/255 ≤ 0.03928 → c/12.92；否则 ((c+0.055)/1.055)^2.4；L = 0.2126R + 0.7152G + 0.0722B。
// 对比度 = (L亮 + 0.05) / (L暗 + 0.05)，1:1 ~ 21:1。
function luminance(hex) {
  const h = expandHex(hex);
  const c = [0, 2, 4].map((i) => parseInt(h.slice(i + 1, i + 3), 16) / 255);
  const lin = c.map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)));
  return 0.2126 * lin[0] + 0.7152 * lin[1] + 0.0722 * lin[2];
}
function contrastRatio(fgHex, bgHex) {
  const a = luminance(fgHex), b = luminance(bgHex);
  const [hi, lo] = a >= b ? [a, b] : [b, a];
  return (hi + 0.05) / (lo + 0.05);
}
// #abc → #aabbcc（3 位缩写展开）
function expandHex(hex) {
  const h = hex.replace("#", "");
  if (h.length === 3) return "#" + h.split("").map((c) => c + c).join("");
  return "#" + h.slice(0, 6);
}

// —— 变量解析：从 App.vue 指定选择器块抽 `--name: value;`（复用 theme-audit 的块定位口径）——
const bc = require("./probes/block-comment-lines.cjs");
function codeOf(raw, insideOpen) {
  let s = String(raw);
  if (insideOpen) {
    const atH = s.indexOf("-->"), atC = s.indexOf("*/");
    const at = atH === -1 ? atC : atC === -1 ? atH : Math.min(atH, atC);
    if (at === -1) return "";
    s = s.slice(at + (s.startsWith("-->", at) ? 3 : 2));
  }
  s = s.replace(/\/\*[\s\S]*?\*\//g, "");
  s = s.replace(/<!--[\s\S]*?-->/g, "");
  const cut = s.indexOf("/*");
  if (cut >= 0) s = s.slice(0, cut);
  const cutH = s.indexOf("<!--");
  if (cutH >= 0) s = s.slice(0, cutH);
  const i = s.indexOf("//");
  if (i >= 0) s = s.slice(0, i);
  return s;
}
// 返回 { vars: Map(name → 原始值), unresolved: [缺定义的变量名] }
function parseSelectorVars(file, selector) {
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
  const mask = bc.insideClosedMask(lines, file);
  const selRe = selector === ":root.dark" ? /^:root\.dark\s*\{/ : selector === ":root" ? /^:root\s*\{/ : new RegExp("^" + selector.replace(/\./g, "\\.") + "\\s*\\{");
  const vars = new Map();
  let cur = false, depth = 0;
  for (let i = 0; i < lines.length; i++) {
    const code = codeOf(lines[i], mask[i]);
    if (cur) {
      for (const ch of code) {
        if (ch === "{") depth++;
        else if (ch === "}") { depth--; if (depth <= 0) { cur = false; depth = 0; break; } }
      }
      const m = code.match(/^\s*(--[\w-]+)\s*:\s*([^;]+);/);
      if (m && !vars.has(m[1])) vars.set(m[1], m[2].trim());
      continue;
    }
    if (selRe.test(code.trim())) { cur = true; depth = 1; }
  }
  return vars;
}

// 值解析：字面 hex 直收；var(--x) 引用则递归查同组定义（引用链深度防环）
function resolveValue(raw, vars, depth = 0) {
  const v = String(raw).trim();
  const ref = v.match(/^var\((--[\w-]+)\)$/);
  if (ref && depth < 8) {
    if (!vars.has(ref[1])) return null; // 引用了本组没有的变量（如引用 EP 真源的值）
    return resolveValue(vars.get(ref[1]), vars, depth + 1);
  }
  return /^#[0-9a-fA-F]{3,8}$/.test(v) ? v : null; // 清单只用 hex；rgba/渐变不进对比度口径
}

// —— 语义对清单（写死；变量名引用，值现场解析）——
// 运营端浅组说明：:root 只覆盖品牌族 + --admin-* 壳层族，中性文字/页面底交还 EP 亮色真源
// （App.vue §2.1 注释），所以浅组正文×页底这一对没有本地变量可引，清单如实只列本地可解析的对。
const SUITES = [
  {
    app: "front-merchant/App.vue", label: "商家端 :root 浅组",
    pairs: [
      ["正文 × 页底", "--c-text", "--c-bg"],
      ["正文 × 卡片", "--c-text", "--c-card"],
      ["次级文字 × 页底", "--c-text-2", "--c-bg"],
      ["次级文字 × 卡片", "--c-text-2", "--c-card"],
      ["弱提示 × 页底", "--c-text-3", "--c-bg"],
      ["弱提示 × 卡片", "--c-text-3", "--c-card"],
      ["按钮白字 × 主色实底", "#FFFFFF", "--c-primary"],
      ["警示字 × 警示软底", "--c-warn", "--c-warn-soft"],
      ["危险字 × 危险软底", "--c-danger", "--c-danger-soft"],
    ],
  },
  {
    app: "front-partner/App.vue", label: "主理人端 :root 浅组",
    pairs: [
      ["正文 × 页底", "--c-text", "--c-bg"],
      ["正文 × 卡片", "--c-text", "--c-card"],
      ["次级文字 × 页底", "--c-text-2", "--c-bg"],
      ["次级文字 × 卡片", "--c-text-2", "--c-card"],
      ["弱提示 × 页底", "--c-text-3", "--c-bg"],
      ["弱提示 × 卡片", "--c-text-3", "--c-card"],
      ["按钮白字 × 主色实底", "#FFFFFF", "--c-primary"],
      ["警示字 × 警示软底", "--c-warn", "--c-warn-soft"],
      ["危险字 × 危险软底", "--c-danger", "--c-danger-soft"],
    ],
  },
  {
    app: "front-admin/src/App.vue", label: "运营端 :root 浅组",
    pairs: [
      ["侧栏正文 × 侧栏底", "--admin-side-text", "--admin-side-bg"],
      ["侧栏次级 × 侧栏底", "--admin-side-text-2", "--admin-side-bg"],
      ["按钮白字 × 主色实底", "#FFFFFF", "--el-color-primary"],
      ["警示字 × 警示软底", "--admin-warn-text", "--admin-warn-bg"],
      ["危险字 × 危险软底", "--admin-danger-text", "--admin-danger-bg"],
      ["成功字 × 成功软底", "--admin-ok-text", "--admin-ok-bg"],
    ],
  },
  {
    app: "front-admin/src/App.vue", label: "运营端 html.dark 深组",
    pairs: [
      ["正文 × 页底", "--el-text-color-primary", "--el-bg-color-page"],
      ["正文 × 卡片底", "--el-text-color-primary", "--el-bg-color"],
      ["次级文字 × 页底", "--el-text-color-secondary", "--el-bg-color-page"],
      ["弱提示 × 页底", "--el-text-color-placeholder", "--el-bg-color-page"],
      ["按钮白字 × 主色实底", "#FFFFFF", "--el-color-primary"],
      ["警示字 × 警示软底", "--admin-warn-text", "--admin-warn-bg"],
      ["危险字 × 危险软底", "--admin-danger-text", "--admin-danger-bg"],
      ["成功字 × 成功软底", "--admin-ok-text", "--admin-ok-bg"],
    ],
  },
];

// —— P10-6a 设计取舍（DESIGN_TAKE）豁免清单（契约 docs/十期规划/P10-6-收尾三片与演示数据清理契约.md 片 1 登记）——
// 登记的对为品牌定稿色 / placeholder 语义档的设计取舍：实测低于 AA 时报告标 DESIGN_TAKE 且**不计入 FAIL 数**；
// 若后续迭代使该对实测达标（≥4.5），按普通 PASS 输出、标注自动消失。app+sem 双键定位，
// 避免误伤商家/主理人组的同名「弱提示 × 页底」（那两对必须真达标，不在豁免之列）。
const DESIGN_TAKE = [
  { app: "front-admin/src/App.vue", sem: "按钮白字 × 主色实底", reason: "EP 品牌蓝 #3B82F6 为品牌定稿色不许动（大字 3.0 过，浅/深两档同豁免）" },
  { app: "front-admin/src/App.vue", sem: "弱提示 × 页底", reason: "placeholder 语义档（html.dark 深组；P10-6a 迭代 #85858B 实测达标后不再标注）" },
];

const OUT = "logs/out/contrast-report.txt";
fs.mkdirSync(path.dirname(OUT), { recursive: true });

const rep = [];
rep.push("P10-4 B5 四端语义色对 WCAG 对比度报告");
rep.push(`生成：${new Date().toISOString().replace("T", " ").slice(0, 19)}   判定：AA 正文 ≥ 4.5:1；大字(≥18pt/14pt粗) ≥ 3.0:1`);
rep.push("口径：sRGB 相对亮度（WCAG 2.x 公式，实现见本仓库 tools/gate/contrast-audit.cjs）；值从各端 App.vue 变量定义现场解析。");
rep.push("契约 §B5 呈报型定案 + P10-6a 收口：语义对一律以 AA 4.5 为达标线迭代；DESIGN_TAKE 豁免清单（见脚本内登记）中的对低于 AA 时标注豁免、不计 FAIL。");
rep.push("");
let lowAA = 0, totalPairs = 0, unresolvable = 0, designTake = 0;

for (const suite of SUITES) {
  const vars = parseSelectorVars(suite.app, suite.label.includes("深组") ? "html.dark" : ":root");
  rep.push(`================ ${suite.label}（${suite.app}，${vars.size} 枚变量） ================`);
  for (const [sem, fgName, bgName] of suite.pairs) {
    totalPairs++;
    const fgRaw = fgName.startsWith("#") ? fgName : vars.get(fgName);
    const bgRaw = bgName.startsWith("#") ? bgName : vars.get(bgName);
    const fg = fgName.startsWith("#") ? fgName : resolveValue(fgRaw, vars);
    const bg = bgName.startsWith("#") ? bgName : resolveValue(bgRaw, vars);
    if (!fg || !bg) {
      unresolvable++;
      rep.push(`  [解析失败] ${sem}：${fgName}=${fgRaw || "缺定义"} / ${bgName}=${bgRaw || "缺定义"}（非字面 hex 或引用了本组没有的变量）`);
      continue;
    }
    const r = contrastRatio(fg, bg);
    const aa = r >= 4.5, big = r >= 3.0;
    const take = DESIGN_TAKE.find((t) => t.app === suite.app && t.sem === sem);
    if (!aa && take) designTake++;
    else if (!aa) lowAA++;
    rep.push(
      `  ${sem}  |  ${fgName}=${fg} × ${bgName}=${bg}  |  ${r.toFixed(2)}:1  |  AA(4.5):${aa ? "PASS" : take ? "DESIGN_TAKE" : "FAIL"}  大字(3.0):${big ? "PASS" : "FAIL"}${aa ? "" : take ? `  ←DESIGN_TAKE 豁免（${take.reason}；P10-6a 登记，不算 FAIL）` : "  ←FAIL（未豁免，需继续迭代达标）"}`
    );
  }
  rep.push("");
}

rep.push(`汇总：${totalPairs} 对里 AA 达标 ${totalPairs - lowAA - designTake - unresolvable} 对、DESIGN_TAKE 豁免 ${designTake} 对（登记例外，不算 FAIL）、FAIL ${lowAA} 对（未豁免，需继续迭代）、解析失败 ${unresolvable} 对。`);
rep.push("DESIGN_TAKE 豁免清单登记在 tools/gate/contrast-audit.cjs（P10-6a 契约片 1）：按钮白字×主色蓝=EP 品牌色定稿不许动；深组弱提示×页底=placeholder 语义档。清单外任何对 FAIL 即未达标；清单内对若实测 ≥4.5 则按 PASS 输出、标注自动消失。");
fs.writeFileSync(OUT, rep.join("\n") + "\n");
console.log(`对比度报告已写 ${OUT}：${totalPairs} 对，AA PASS ${totalPairs - lowAA - designTake - unresolvable}，DESIGN_TAKE 豁免 ${designTake}，FAIL ${lowAA}，解析失败 ${unresolvable}`);
process.exitCode = 0; // 报告型，退出码恒 0（契约 §B5：不进 FAILS）
