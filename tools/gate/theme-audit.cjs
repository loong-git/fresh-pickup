#!/usr/bin/env node
// P10-4 B4｜主题静态断言审计器（契约 docs/十期规划/P10-4-四端视觉对齐与质量防线契约.md §B4）
// 两件事：
//   1. 双组完整性：商家/主理人端 App.vue 的 :root/:root.dark、运营端 App.vue 的 :root/html.dark——
//      浅组每枚 --变量在深组必须有同名定义。缺一枚 = 一侧加了另一侧没跟，切到深色就渲染出
//      未定义变量的浏览器回退值（P9 双主题「两组值整体生效」纪律的机器判据）。
//      C 端 front/ 单主题、无深组，契约 §B4 明文登记豁免——本审计器不把 front/App.vue 放进对比对象。
//   2. hex 残留：三移动端（front/、front-merchant/、front-partner/）+ 运营端（front-admin/src/）
//      全部 .vue 的 <style> 段内 hex 色值，超白名单即 FAIL（P9 系列「页面 scoped 色值全走 var(--c-*)」
//      纪律的机器判据，防止字面色值回潮）。
//   3. 过时禁值（P11-17 D1/D2/D4 新）：仅 front/ 面全文本扫描（.vue 全文不再只 style 段 + .js/.css/.scss/
//      .json）的四枚过时色 #E02020/#FF6600/#0F7B4A/#0B3D26（大小写不敏感整 token）——三端不扫（七期绿
//      #0F7B4A 是商家/主理人现役主题品牌色，D2 明文边界）。命中即 FAIL 并入退出码（D4），豁免五档见
//      下方「过时禁值扫描」段逐条注释。
// 输出风格照 pointer-audit.cjs：中文汇总行 + 分类 + 逐条「kind  file:line  值」；有 FAIL 时 exitCode=1。
// f28-gate.sh §13 读退出码与本文件汇总行（第一行），本文件汇总措辞改动必须同步那边。
const fs = require("fs");
const path = require("path");
// 注释判据与 pointer-audit / f27 §2 共用同一 helper（本仓纪律：判据不养两套真相，见 pointer-audit.cjs 头注）：
//   insideClosedMask 判跨行块注释的续行（fail-closed：未闭合块那段算非注释 → 进扫描面，方向是更严）；
//   行内 /*…*/ 的剥离在下面 codeOf 里做（学 helper 的 codePart，但要额外处理跨行块的首/尾行，见函数注）。
const bc = require("./probes/block-comment-lines.cjs");

const RE_HEX = /#[0-9a-fA-F]{3,8}\b/g;
// 特殊档（契约 §B4 白名单原话）：「主色底白字 #FFFFFF 与成功实底近黑字 #0A0A0B」——两主题同值、
// 不随主题换色，任何主题档都允许内联（P9 各片契约把它们登记为唯一允许留在页面里的两个字面值）。
// #FFF 是 #FFFFFF 的 3 位缩写，同色同档；大小写变体一并收。
const SPECIAL = new Set(["#FFFFFF", "#ffffff", "#FFF", "#fff", "#0A0A0B", "#0a0a0b"]);
// front/ 显式例外色值清单（P10-6 片3 定稿，契约 docs/十期规划/P10-6-收尾三片与演示数据清理契约.md：
// 「例外保留：#FF6600（评分星标准橙）、#07C160（微信支付绿）、免费领金族 #FFD84D/#FFE14D/#FFEDBE/
// #FFC55C/#FFF7F0/#E8F8EF」）。整 token 精确匹配（大小写归一）：不含 3 位缩写、不含 #FFB800/#FF8533
// 等近邻变体——变体属漏网，照 FAIL 回填 var。#FFFFFF/#0A0A0B 不在本清单，走 SPECIAL 特殊档
// （P10-4 §B4：主色底白字/成功实底近黑字两主题同值、任何主题档允许内联，四端同一口径）。
const FRONT_ALLOWED = new Set([
  "#FF6600", "#07C160",
  "#FFD84D", "#FFE14D", "#FFEDBE", "#FFC55C", "#FFF7F0", "#E8F8EF",
]);

// P11-17（D1）过时禁值清单（仅 front/ 面全文本扫描——三端不扫，七期绿品牌是商家/主理人现役主题）：
// 四值全部是 C 端红主题收口后不该再出现的过时色。豁免五档（星位/公章 .seal- 块/uni.scss dead 标记/
// App.vue --c-ok 定义行/注释行）见下方「过时禁值扫描」段逐条注释。
const STALE_HEX = [
  { hex: "#E02020", why: "旧深红（P11-15 已统一 #E1251B）" },
  { hex: "#FF6600", why: "旧橙（P11-16 已回轨 var；评分星位豁免）" },
  { hex: "#0F7B4A", why: "旧主色绿（P11-13 已翻红；App.vue --c-ok 定义位豁免）" },
  { hex: "#0B3D26", why: "八期深绿（P11-13 已消）" },
];
// 整 token 大小写不敏感：hex 串后接 \b 词边界断言——#E020201 这类 8 位 hex 的前缀形态不误命中
//（末位 0 与后续数字 1 同为词字符、无边界），后跟 ;/}/空白/引号/行尾才成立。
const STALE_RES = STALE_HEX.map((x) => ({ hex: x.hex, why: x.why, re: new RegExp(x.hex + "\\b", "i") }));

// 双组完整性对比对象（浅组选择器 → 深组选择器）。C 端 front/App.vue 单主题，契约登记豁免。
const PAIRS = [
  { file: "front-merchant/App.vue", light: ":root", dark: ":root.dark" },
  { file: "front-partner/App.vue", light: ":root", dark: ":root.dark" },
  { file: "front-admin/src/App.vue", light: ":root", dark: "html.dark" },
];

// hex 扫描面（契约 §B4：三移动端 + 运营端全部 .vue）
const ROOTS = ["front", "front-merchant", "front-partner", "front-admin/src"];
const SKIP_DIR = new Set(["node_modules", "unpackage", "dist", ".git", "static"]);
// 四端 App.vue——「变量定义块」白名单只对它们生效（契约：白名单是变量定义块本身）
const APP_VUES = new Set(["front/App.vue", "front-merchant/App.vue", "front-partner/App.vue", "front-admin/src/App.vue"]);

// exts 缺省 [".vue"]＝既有 hex 残留扫描面（一字不变）；P11-17 过时禁值扫描传五扩展名复用同一遍历
//（skip 目录与点开头目录——含 front/.backup-*——两口径天然同 rules，D2「全部跟踪文本文件」的
// 等效面：.backup-*/unpackage/static/dist 均不在 git 跟踪里）。
function walk(dir, acc, exts) {
  const extList = exts || [".vue"];
  let ents;
  try { ents = fs.readdirSync(dir, { withFileTypes: true }); } catch (e) { return acc; }
  for (const e of ents) {
    if (e.name.startsWith(".") || SKIP_DIR.has(e.name)) continue;
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, acc, extList);
    else if (extList.some((x) => e.name.endsWith(x))) acc.push(p);
  }
  return acc;
}

const norm = (p) => p.replace(/\\/g, "/");

// 行级「代码部分」：把注释剥掉再扫 hex，避免把 P9 系契约要求写进注释的登记值（「深值=原 #4A3B14 逐字保留、
// 登记契约 §2.2.1」这类文档性引用）当成残留报红——那些注释本身是各批契约的登记载体，删掉它们才是破坏。
// 三种形态（insideOpen = 该行开头时已在一个已闭合的 /* 块内，来自 bc.scan 的 inside 语义）：
//   跨行块中间行（无 */）→ 整行是注释，代码部分为空；
//   跨行块结束行（有 */）→ 只保留 */ 之后的尾巴（后面还可能跟真代码）；
//   同行自闭合 /*…*/ 与 // 之后 → 剥掉（helper codePart 同款口径；CSS 无 //，URL 的 // 会被切，
//   但剥离只发生在「判豁免」一侧，剥粗只会少豁免不会多放行——与 block-comment-lines.cjs:183-185 同一取舍）。
function codeOf(raw, insideOpen) {
  let s = String(raw);
  if (insideOpen) {
    // 该行开头已在块内：取块结束符（--> 或 */，谁先出现用谁）之后的尾巴，其后可能还跟真代码
    const atH = s.indexOf("-->"), atC = s.indexOf("*/");
    const at = atH === -1 ? atC : atC === -1 ? atH : Math.min(atH, atC);
    if (at === -1) return "";
    s = s.slice(at + (s.startsWith("-->", at) ? 3 : 2));
  }
  s = s.replace(/\/\*[\s\S]*?\*\//g, "");       // 同行自闭合 C 注释
  s = s.replace(/<!--[\s\S]*?-->/g, "");        // 同行自闭合模板注释（uni-app 条件编译 <!-- #ifdef H5 --> 也在这层）
  const cut = s.indexOf("/*");
  if (cut >= 0) s = s.slice(0, cut);            // 本行开了跨行 C 块：/* 之后全是注释
  const cutH = s.indexOf("<!--");
  if (cutH >= 0) s = s.slice(0, cutH);          // 本行开了跨行模板注释（实测：front/pages/pickup/pickup.vue 尾注里有 <style> 字样，
  //                                             不剥它会把注释内容当 <style> 段开头，UNCLOSED_STYLE 假红 + 判据面被截断）
  const i = s.indexOf("//");
  if (i >= 0) s = s.slice(0, i);
  return s;
}

// 解析 App.vue 里指定选择器块内的全部 --变量名（带首次定义行号）。
function varsInSelector(lines, mask, selector) {
  const map = new Map();
  const selRe = selector === ":root.dark" ? /^:root\.dark\s*\{/ : selector === ":root" ? /^:root\s*\{/ : /^html\.dark\s*\{/;
  let cur = false, depth = 0;
  for (let i = 0; i < lines.length; i++) {
    const code = codeOf(lines[i], mask[i]);
    if (cur) {
      for (const ch of code) {
        if (ch === "{") depth++;
        else if (ch === "}") { depth--; if (depth <= 0) { cur = false; depth = 0; break; } }
      }
      const m = code.match(/^\s*(--[\w-]+)\s*:/);
      if (m && !map.has(m[1])) map.set(m[1], i + 1);
      continue;
    }
    if (selRe.test(code.trim())) { cur = true; depth = 1; }
  }
  return map;
}

// —— 1) 双组完整性 ——
const findings = [];
let pairsChecked = 0, lightVars = 0;
for (const pair of PAIRS) {
  if (!fs.existsSync(pair.file)) { findings.push({ kind: "APP_MISSING", file: pair.file, line: 0, val: `${pair.light}/${pair.dark} 双组完整性无法检查（文件不存在）` }); continue; }
  const lines = fs.readFileSync(pair.file, "utf8").split(/\r?\n/);
  const mask = bc.insideClosedMask(lines, pair.file);
  const light = varsInSelector(lines, mask, pair.light);
  const dark = varsInSelector(lines, mask, pair.dark);
  pairsChecked++;
  lightVars += light.size;
  for (const [name, line] of light) {
    if (!dark.has(name)) findings.push({ kind: "MISSING_DARK", file: pair.file, line, val: `${name}（${pair.light} 有、${pair.dark} 无同名定义——切深色会拿浏览器回退值）` });
  }
}

// —— 2) hex 残留扫描（只扫 <style> 段）——
const files = [];
for (const r of ROOTS) walk(r, files);
let styleFiles = 0, hexTotal = 0, whitelisted = 0;
for (const f of files) {
  const lines = fs.readFileSync(f, "utf8").split(/\r?\n/);
  const mask = bc.insideClosedMask(lines, f);
  const isApp = APP_VUES.has(norm(f));
  let inStyle = false, inVarBlock = false, depth = 0;
  let inSealBlock = false, sealDepth = 0;
  lines.forEach((raw, idx) => {
    // <style> 段开闭判定同样过注释剥离：模板注释里的 "<style>" 字样不是段开头（pickup.vue 实测踩过）
    const rawCode = codeOf(raw, mask[idx]);
    if (/<style[\s>]/.test(rawCode)) { inStyle = true; inVarBlock = false; depth = 0; return; }
    if (/<\/style>/.test(rawCode)) { inStyle = false; return; }
    if (!inStyle) return;
    const code = rawCode;
    if (!code.trim()) return; // 纯注释行 / 空行
    hexTotal += (code.match(RE_HEX) || []).length;
    // App.vue 变量定义块白名单（契约：白名单=变量定义块本身）。:root/:root.dark/html.dark 之外，
    // page 兜底块也按变量定义块豁免——它是 #ifndef H5 里的同名浅组逐字副本（小程序端变量真源，
    // App.vue 头注明说「给 page 根声明同一套浅色组」），与 :root 同属「变量定义块」的语义范围。
    if (isApp) {
      if (inVarBlock) {
        for (const ch of code) {
          if (ch === "{") depth++;
          else if (ch === "}") { depth--; if (depth <= 0) { inVarBlock = false; depth = 0; break; } }
        }
        return; // 变量定义块内整体豁免
      }
      if (/^(:root(\.dark)?|html\.dark|page)\s*\{/.test(code.trim())) { inVarBlock = true; depth = 1; return; }
    }
    // 契约白名单：C 端 --c-warn-bright 定义块（C7 的促销价签橙渐变端点，单组变量）。
    // C7 未落地时本行不命中任何东西（前瞻白名单，落地即生效，不是为绿而设——它只放行「定义行」，
    // 用 --c-warn-bright 之外 hex 的渐变行照旧报）。
    if (/^\s*--c-warn-bright\s*:/.test(code)) { whitelisted++; return; }
    // 契约白名单：placeholder-style 模板属性行（uni-app 输入框占位色内联属性，B5 治理改 var()）。
    // 本审计器只扫 <style> 段，template 里的 placeholder-style 天然不在扫描面；这里兜的是
    // 万一哪天 style 段出现同类行，不因扫描面收窄而假红。
    if (/placeholder-style/.test(code)) { whitelisted++; return; }
    // 契约白名单「JS 业务字面量」：HEX_COLOR_RE、默认背景色等都在 script 段，只扫 style 段天然覆盖——无需代码。
    // front/ 豁免口径沿革：P10-4 §B4 执行后曾按「非旧红即豁免」宽口径放行 C 端灰阶/第三方功能色
    // （2026-11-06 登记，彼时 C7 旧红已收口、灰阶语义化未做）；P10-6 片3 灰阶映射完成后该宽口径
    // 废止——它连漏网灰阶与新违规一起掩住。收窄后的 front/ 显式例外清单仅四档，见下方
    // 「P10-6 片3 收窄」分支注释（.seal- 块／「P10-4 登记」锚行／FRONT_ALLOWED 色值／注释行）。
    // 拟物公章豁免档：qualification.vue 营业执照模拟图上的红色公章（「公章是红的」=业务事实）。
    // 块级判据：进入 .seal- 开头的规则块后整块豁免直到闭合——选择器行（无 hex）也必须先走块
    // 追踪，所以这段必须在 hexesHere 提前 return 之前（P10-4 执行后登记，主会话定性；
    // 块上方注释带「拟物公章红」登记锚 144/227 行）。
    if (/^front[/\\]/.test(f)) {
      if (inSealBlock) {
        for (const ch of code) {
          if (ch === "{") sealDepth++;
          else if (ch === "}") { sealDepth--; if (sealDepth <= 0) { inSealBlock = false; sealDepth = 0; break; } }
        }
        whitelisted += (code.match(RE_HEX) || []).length;
        return;
      }
      if (/\.seal-[^{]*\{/.test(code)) { inSealBlock = true; sealDepth = 1; whitelisted += (code.match(RE_HEX) || []).length; return; }
    }
    const hexesHere = code.match(RE_HEX);
    if (!hexesHere) return;
    // P10-6 片3 收窄（契约 docs/十期规划/P10-6-收尾三片与演示数据清理契约.md）：front/ 显式例外清单
    // 仅四档，其余命中照旧 FAIL——
    //   ① .seal- 拟物公章块：上方块级判据已整体豁免（选择器行+块内行，公章红=业务事实）；
    //   ② 含「P10-4 登记」锚的行：各批契约的登记载体（锚写在注释里，codeOf 已剥离，故按原始行 raw 判）；
    //   ③ 色值 ∈ FRONT_ALLOWED（评分星橙/微信绿/免费领金族，第三方与活动资产）：混行按枚数放行例外、
    //      违规枚数照报（SPECIAL 特殊档在分支外四端统一生效，#FFFFFF/#0A0A0B 不因收窄变红）；
    //   ④ 注释行：codeOf 剥空后已在上方「if (!code.trim()) return」提前离场。
    // 旧红族（E02020/FF4A3A/F5222D/CF1322/E65C4C 系）不在清单内——回潮防线不松，照 FAIL。
    let hexes = hexesHere;
    if (/^front[/\\]/.test(f)) {
      if (/P10-4 登记/.test(raw)) { whitelisted += hexesHere.length; return; }
      const allowed = hexesHere.filter((h) => FRONT_ALLOWED.has(h.toUpperCase()));
      whitelisted += allowed.length;
      hexes = hexesHere.filter((h) => !FRONT_ALLOWED.has(h.toUpperCase()));
      if (!hexes.length) return;
    }
    const over = hexes.filter((h) => !SPECIAL.has(h));
    if (!over.length) { whitelisted += hexes.length; return; } // 特殊档（主色底白字/成功实底近黑字）
    findings.push({ kind: "HEX_RESIDUE", file: f, line: idx + 1, val: over.join(",") });
  });
  if (inStyle) findings.push({ kind: "UNCLOSED_STYLE", file: f, line: lines.length, val: "<style> 段未闭合（判据面被截断，先修文件）" });
}

// —— 3) 过时禁值扫描（P11-17 D1/D2/D4：仅 front/ 面全文本——.vue 全文不再只 style 段 + .js/.css/.scss/.json；
// 三端不扫：七期绿 #0F7B4A 是商家/主理人现役主题品牌色，D2 明文边界）。front/ 面单独走一遍 walk 收集
//（exts 五扩展名），既有 style 段扫描（上面第 2 段）的文件面/逐条输出/读数一字不动。
const STALE_EXTS = [".vue", ".js", ".css", ".scss", ".json"];
const staleFiles = walk("front", [], STALE_EXTS);
let staleLines = 0;
for (const f of staleFiles) {
  const nf = norm(f);
  // 豁免②（契约 D3）：front/uni.scss 整文件——P11-16 dead 标记（文件头「P11-16 审计标记」，实测全仓
  // 零消费，色值已过时勿引用），整文件豁免不逐行判。
  if (nf === "front/uni.scss") continue;
  const lines = fs.readFileSync(f, "utf8").split(/\r?\n/);
  const mask = bc.insideClosedMask(lines, f); // .json 不在 helper 的 BLOCK_CAPABLE → noMask（JSON 无注释，正确）
  // 豁免③（契约 D3 例外档①）：qualification.vue 拟物公章 .seal- 块——**块级**判据（与上方 style 段
  // 扫描的 inSealBlock 完全同构，按行内容匹配不按行号，指针安全）。不能按「行含 .seal-」字面判：
  // 公章 hex 落在 .seal-top/.seal-top-star 块**内部行**（实测 :151/:158 两行本体不含 .seal-），
  // 按行字面判现状树就出 2 误报。
  let inSealBlock = false, sealDepth = 0;
  lines.forEach((raw, idx) => {
    // 豁免⑤（契约 D3）：注释行——复用 codeOf 剥注释口径（.js/.css/.scss 走块注释 mask + 行注释剥离，
    // 与既有 style 段扫描同一真相；.json 无注释天然全代码）。剥空即离场，注释里的历史值登记引用不误报。
    const code = codeOf(raw, mask[idx]);
    if (!code.trim()) return;
    // 豁免④（契约 D3，任务简化式）：App.vue 里命中 #0F7B4A 的行若以 --c-ok 开头即豁免——合法定义位
    //（:root 变量定义块内，P11-13 主色翻红后 ok 轨保留绿）。App.vue 其余位置命中照报。
    if (nf === "front/App.vue" && /^--c-ok\s*:/.test(code.trim())) return;
    // 豁免①（契约 D3 原文）：review.vue 评分星位——「含 .star.active 或 .star-picker .star.active 的行」，
    // 后者包含前者子串，一个 includes 判据精确覆盖两处登记位（:402/:435）。不放宽到裸 .star
    //（.review-stars/.star-picker 等非星色行不豁免，方向更严）。
    if (nf === "front/pages/review/review.vue" && code.includes(".star.active")) return;
    // 豁免③块追踪（见上）：选择器行先入账开块，块内行（含 hex 行）整体豁免直到闭合。
    if (nf === "front/pages/qualification/qualification.vue") {
      if (inSealBlock) {
        for (const ch of code) {
          if (ch === "{") sealDepth++;
          else if (ch === "}") { sealDepth--; if (sealDepth <= 0) { inSealBlock = false; sealDepth = 0; break; } }
        }
        return;
      }
      if (/\.seal-[^{]*\{/.test(code)) { inSealBlock = true; sealDepth = 1; return; }
    }
    const hits = STALE_RES.filter((s) => s.re.test(code));
    if (!hits.length) return;
    staleLines++; // 摘要读数按「行」计（D4「命中 N 行」）；一行多枚过时值时逐枚出条便于定位
    for (const h of hits) {
      findings.push({ kind: "STALE_HEX_HIT", file: f, line: idx + 1, val: `${h.hex}（${h.why}）` });
    }
  });
}

// —— 输出（照 pointer-audit.cjs 风格）——
const byKind = {};
for (const x of findings) byKind[x.kind] = (byKind[x.kind] || 0) + 1;
const hexResidue = byKind.HEX_RESIDUE || 0;
const missingDark = byKind.MISSING_DARK || 0;
const failCount = findings.length;
// 汇总行（第一行，f28 §13 展示用；措辞改动同步 f28-gate.sh）。P11-17（D4）追加「过时禁值命中 N 行」段，
// 保持单行形态（f28 §13 say "  $(head -1 …)" 吃首行）；FAIL 计数含 STALE_HEX_HIT（退出码合并，D4）。
console.log(`主题审计：双组完整性 ${pairsChecked} 组/${lightVars} 枚浅组变量，缺失 ${missingDark} 枚；hex 残留 ${hexResidue} 行（扫描 ${files.length} 个 .vue，命中 style 段字面 hex ${hexTotal} 处、白名单放行 ${whitelisted} 处）；过时禁值命中 ${staleLines} 行（front/ 全文本面 E02020/FF6600/0F7B4A/0B3D26，豁免：星位/公章/dead 标记/App.vue 变量块/注释），FAIL ${failCount} 条`);
console.log("分类:", JSON.stringify(byKind, null, 0));
console.log("口径备注：front/ 显式例外清单四档（.seal- 块／「P10-4 登记」锚行／FRONT_ALLOWED 八色值／注释行）之外，front/ 命中即真违规照 FAIL（P10-6 片3 收窄，含旧红回潮防线），四端已无「预期红」档。");
console.log("");
for (const x of findings.sort((p, q) => p.kind.localeCompare(q.kind) || norm(p.file).localeCompare(norm(q.file)) || p.line - q.line)) {
  // 路径打印统一正斜杠：f28 §13 按前缀过滤 C 端命中（grep -v '  front/'），
  // 避免在 shell 判据里写反斜杠字符类（ugrep 对 [/\\] 解析与 GNU grep 不一致，实测踩过）
  console.log(`  ${x.kind}  ${norm(x.file)}:${x.line}  ${x.val}`);
}
process.exitCode = failCount > 0 ? 1 : 0;
