// envelope-diff.cjs —— F-42b 的活体复验：比较改前/改后两个响应快照，把差异分成两类。
// 为什么要它：改后端信封等于改前端读到的字节，而"看起来一样"不算证据。
// 两类差异的处理方式完全不同：
//   ENVELOPE —— 顶层键集合的变化（total 消失、message 补上、404 支 data 从无到有）：这是本批**有意**做的
//   DATA     —— data 子树内部的不同：本批**必须**为零。若非零，要么是并发期间的业务数据自然变动（需人工判），
//               要么是改动真的动了 data 构造（必须回退）。
// 用法：node tools/gate/envelope-diff.cjs <改前目录> <改后目录>     文件名按 pre/post 同名配对
// 说明：F-48 把本工具搬进 tools/gate/（tracked），但**快照产物仍留在 logs/out/**（忽略区，正确）。
//       下面两个默认值是相对**当前工作目录**的，所以必须在仓库根执行本命令；改成 __dirname 反推会
//       让「在哪个目录跑」不再影响结果——那是另一回事，别顺手改。
const fs = require("fs");
const path = require("path");

const PRE = process.argv[2] || "logs/out";
const POST = process.argv[3] || "logs/out";
const PRE_PREFIX = "f42_base_";
const POST_PREFIX = "f42_post_";

function readJson(p) {
  try { return JSON.parse(fs.readFileSync(p, "utf8")); } catch (e) { return null; }
}
// 递归找出一棵对象/数组里所有"叶子路径"，用于把差异定位到具体字段而不是整块 dump
function leaves(v, prefix, out) {
  if (v === null || v === undefined) { out[prefix] = "null"; return out; }
  if (Array.isArray(v)) {
    if (!v.length) { out[prefix + "[]"] = "empty-array"; return out; }
    v.forEach((x, i) => leaves(x, prefix + "[" + i + "]", out));
    return out;
  }
  if (typeof v === "object") {
    Object.keys(v).forEach((k) => leaves(v[k], prefix + "." + k, out));
    return out;
  }
  out[prefix] = String(v);
  return out;
}

const preFiles = fs.readdirSync(PRE).filter((f) => f.startsWith(PRE_PREFIX) && f.endsWith(".json"));
if (!preFiles.length) { console.log("没找到改前快照（前缀 " + PRE_PREFIX + " in " + PRE + "）"); process.exit(2); }

let envelopeChanges = 0, dataBreaks = 0, missing = 0;
for (const pf of preFiles) {
  const name = pf.slice(PRE_PREFIX.length);
  const postPath = path.join(POST, POST_PREFIX + name);
  const pre = readJson(path.join(PRE, pf));
  const post = readJson(postPath);
  console.log("\n=== " + name + " ===");
  if (!pre) { console.log("  SKIP 改前不可解析"); continue; }
  if (!post) { console.log("  MISSING 改后快照不存在：" + postPath); missing++; continue; }

  const kb = (o) => Object.keys(o).sort().join(",");
  const preKeys = kb(pre), postKeys = kb(post);
  if (preKeys !== postKeys) {
    envelopeChanges++;
    console.log("  ENVELOPE 顶层键 " + preKeys + "  ->  " + postKeys);
  } else {
    console.log("  ENVELOPE 顶层键不变：" + postKeys);
  }

  // data 子树比对：这是本批的硬红线
  const a = leaves(pre.data === undefined ? {} : pre.data, "data", {});
  const b = leaves(post.data === undefined ? {} : post.data, "data", {});
  const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
  const diffs = [];
  let addedNulls = 0;
  for (const k of keys) {
    if (a[k] === b[k]) continue;
    // 「原本没有 data 键 / 没有某叶子，现在补成 null」是**信封层**的修复（本仓 404 支要求显式 data:null、
    // 不许缺键），不是 data 内容被改动。混在 DATA 破口里会在真跑时误报，逼我回退一个正确的改动。
    if (a[k] === undefined && b[k] === "null") { addedNulls++; continue; }
    if (b[k] === undefined && a[k] === "null") { addedNulls++; continue; }
    diffs.push("      " + k + " : " + JSON.stringify(a[k]) + "  ->  " + JSON.stringify(b[k]));
  }
  if (addedNulls) console.log("  ENVELOPE 补齐了 " + addedNulls + " 个显式 null 叶子（缺键 -> null，属本批有意的信封修复）");
  if (preKeys.includes("total") && Object.keys(pre).includes("total") && !Object.keys(post).includes("total")) {
    console.log("  ENVELOPE 顶层 total 已去掉（改前=" + pre.total + "，C 端无消费，见派单）");
  }
  if (diffs.length) {
    console.log("  DATA 差异 " + diffs.length + " 条：");
    diffs.slice(0, 12).forEach((d) => console.log(d));
    if (diffs.length > 12) console.log("      …其余 " + (diffs.length - 12) + " 条");
    dataBreaks++;
  } else {
    console.log("  DATA 逐字段一致 ✅（叶子数 " + Object.keys(b).length + "）");
  }
  if (pre.code !== post.code) { console.log("  CODE 差异！" + pre.code + " -> " + post.code); dataBreaks++; }
  if (JSON.stringify(pre.message) !== JSON.stringify(post.message)) {
    console.log("  MESSAGE 变化：" + JSON.stringify(pre.message) + "  ->  " + JSON.stringify(post.message));
  }
}
console.log("\n合计：信封层变化 " + envelopeChanges + " 处（预期如此）｜data/code 破口 " + dataBreaks + " 处（必须为 0）｜缺改后快照 " + missing + " 个");
process.exit(dataBreaks === 0 && missing === 0 ? 0 : 1);
