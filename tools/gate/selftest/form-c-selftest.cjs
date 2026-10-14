// 形态 C 的连字符修复自验：直接复用 symbol-audit 里那条 RE_NAMED（从源码读，不手抄，
// 手抄一份就等于在测我抄的那份而不是出货的那份——这条教训本批已经踩过一次）。
const fs = require('fs')
const path = require('path')
const src = fs.readFileSync(path.join(__dirname, '..', 'symbol-audit.cjs'), 'utf8')
const m = src.match(/^const RE_NAMED = (.+);$/m)
if (!m) { console.log('EXTRACT_FAIL: 没从 symbol-audit.cjs 里取到 RE_NAMED'); process.exit(2) }
// eslint-disable-next-line no-eval
const RE = eval(m[1])
console.log('取到的正则: ' + m[1])

const cases = [
  // [说明, 注释行, 期望抽到的符号名]
  ['纯标识符', '    // 判据逐字照 marketing.vue / dashboard.vue 的 onShow，', 'onShow'],
  ['CSS 连字符族（真名）', '// 母版 verify.vue 的 verify-feedback 那一族同一档', 'verify-feedback'],
  ['CSS 连字符族（ghost，必须整体抓到才能报红）', '// 母版 verify.vue 的 verify-feedback-ghost 那一族同一档', 'verify-feedback-ghost'],
  ['驼峰方法名', '// 两道守卫缺一不可（照 pickup-plan.vue 的 onPageWheel）：', 'onPageWheel'],
  ['太短的残词不该被抓', '// 见 api/index.js 的 a', null],
]
let fail = 0
for (const [label, line, want] of cases) {
  RE.lastIndex = 0
  const hit = RE.exec(line)
  const got = hit ? hit[2] : null
  const ok = got === want
  if (!ok) fail++
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}: 抽到=${JSON.stringify(got)} 期望=${JSON.stringify(want)}`)
}

// 再验「验存在」那一步：把抽到的名字拿去目标文件里查，ghost 必须查不到
// F-48 搬迁修法：本文件从 logs/scratch59/ 搬进 tools/gate/selftest/，相对 symbol-audit.cjs 的那句
// （'..', 'symbol-audit.cjs'）**层级恰好不变**、一字未改就跑通了 5/5；但仓库根这句多了一层
// （selftest → gate → tools → 根），单层 '..','..' 会把根算成 tools/ 而在读 verify.vue 时 ENOENT。
// 这里只改「仓库根怎么算」，第 5 行那条**从出货源码里抽 RE_NAMED**的姿势保持不动——手抄正则就等于在测我抄的那份。
const ROOT = path.join(__dirname, '..', '..', '..')
const target = path.join(ROOT, 'front-partner', 'pages', 'verify', 'verify.vue')
const text = fs.readFileSync(target, 'utf8')
for (const name of ['verify-feedback', 'verify-feedback-ghost', 'verify']) {
  const present = new RegExp('\\b' + name + '\\b').test(text)
  console.log(`  目标文件里 ${name} 存在? ${present}`)
}
console.log(`\n失败 ${fail} 条`)
process.exit(fail === 0 ? 0 : 1)
