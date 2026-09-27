// ==================== 截单时间工具（T-M3-04，首页/详情页共用） ====================
// 口径：每日 23:00 截单；服务端按当前时间裁决提货日期（<23:00 → 明天自提，>=23:00 → 后天自提）。
// 首页与详情页各自保留 setInterval 定时器，tick 内统一调用本文件计算，
// 保证两页倒计时目标、归零判定与自提日期文案完全一致。

// 截单时刻（本地时区 23:00:00.000）
export const CUTOFF_HOUR = 23
// 跨过截单瞬间的归零展示宽限（毫秒）：期间页面显示"已切明日场次"置灰态并冻结数字，
// 避免倒计时从 00:00:00.1 直接闪跳成 23:59:59.9（下一帧目标已切明天）
export const ZERO_GRACE_MS = 2000
// "截单前 1 小时"红条窗口起点：22:00:00.000
const SOON_WINDOW_MS = 3600000
const DAY_MS = 86400000

function pad2(n) { return String(n).padStart(2, '0') }

// 今天 23:00:00.000 的本地时间戳
function todayCutoffMs(now) {
  const d = new Date(now)
  d.setHours(CUTOFF_HOUR, 0, 0, 0)
  return d.getTime()
}

/**
 * 截单信息（每次调用基于给定时间即时计算，纯函数无副作用）
 * @param {Date|number} [now] - 基准时间，默认当前时间
 * @returns {{
 *   isAfterCutoff: boolean,    // 是否已过今日 23:00（含归零宽限窗内）
 *   justPassedCutoff: boolean, // 是否处于刚跨过截单的归零宽限窗（页面应显示"已切明日场次"置灰态）
 *   countdownTarget: number,   // 倒计时目标：下一个 23:00 的本地时间戳（毫秒）
 *   diffMs: number,            // 距目标剩余毫秒（下限 0）
 *   cutoffSoon: boolean,       // 距今日截单不足 1 小时（22:00 ≤ now < 23:00，首页顶部红条）
 *   pickupDateText: string,    // 提货日期词：'明天' | '后天'（与服务端下单裁决同口径）
 *   pickupDateFull: string     // 提货日期完整文案：如 '明天（9月28日）' / '后天（9月29日）'
 * }}
 */
export function getCutoffInfo(now = new Date()) {
  const t = now instanceof Date ? now.getTime() : Number(now)
  const cur = Number.isFinite(t) ? t : Date.now()
  const cutoff = todayCutoffMs(cur)
  const isAfterCutoff = cur >= cutoff
  // 已过今日截单：倒计时目标切到明天 23:00（+1 天）
  const target = isAfterCutoff ? cutoff + DAY_MS : cutoff
  // 提货日期：未过 23:00 → 明天；已过 → 后天（与服务端裁决口径一致）
  const pickupTs = cur + (isAfterCutoff ? 2 : 1) * DAY_MS
  const pd = new Date(pickupTs)
  const word = isAfterCutoff ? '后天' : '明天'
  return {
    isAfterCutoff,
    justPassedCutoff: isAfterCutoff && (cur - cutoff) < ZERO_GRACE_MS,
    countdownTarget: target,
    diffMs: Math.max(0, target - cur),
    cutoffSoon: cur >= cutoff - SOON_WINDOW_MS && cur < cutoff,
    pickupDateText: word,
    pickupDateFull: `${word}（${pd.getMonth() + 1}月${pd.getDate()}日）`
  }
}

/**
 * 剩余毫秒 → 倒计时展示格式（HH:MM:SS + 十分位）
 * @param {number} diffMs - 剩余毫秒
 * @returns {{ text: string, tenth: string }} 如 { text: '01:23:45', tenth: '6' }
 */
export function formatCountdown(diffMs) {
  const diff = Math.max(0, Number(diffMs) || 0)
  const h = Math.floor(diff / 3600000)
  const m = Math.floor((diff % 3600000) / 60000)
  const s = Math.floor((diff % 60000) / 1000)
  return {
    text: `${pad2(h)}:${pad2(m)}:${pad2(s)}`,
    tenth: String(Math.floor((diff % 1000) / 100))
  }
}

// 自提点时段文案中可被动态替换的静态日期词开头
const STATIC_DATE_WORD_RE = /^(明天|后天|次日|大后天)/

/**
 * 自提时间动态文案（T-M3-04，首页/详情页共用）：
 * 自提点 timeText（如 '明天16:00自提'）中的静态日期词按截单口径推导的动态日期词替换
 * （今天 23:00 前下单 → 明天，之后 → 后天），保留其时段部分；
 * timeText 缺失时回退纯推导完整文案
 * @param {string} [timeText] - 自提点时段文案（pickupPoint.timeText）
 * @param {object} info - getCutoffInfo() 返回值（含 pickupDateText/pickupDateFull）
 * @returns {string} 如 '明天16:00自提'（23:00 后自动变 '后天16:00自提'）
 */
export function formatPickupTimeText(timeText, info) {
  const word = (info && info.pickupDateText) || '明天'
  const t = typeof timeText === 'string' ? timeText.trim() : ''
  if (!t) return (info && info.pickupDateFull || word) + ' 16:00前门店自提'
  if (STATIC_DATE_WORD_RE.test(t)) return t.replace(STATIC_DATE_WORD_RE, word)
  return word + t
}

// ==================== M4-T-03 秒杀场次时间辅助（首页/详情页共用） ====================
// 倒计时基准改为 GET /api/seckill/current 的 serverTime + end（服务端时钟）：
// 拉取时用 serverTime 与本机时钟求差锚定场次剩余毫秒，之后 tick 走单调时钟递减——
// 用户改本机时钟不影响倒计时；end 过点（切场）由页面重新拉取接口

/**
 * 单调时钟毫秒：优先 performance.now（不受本机改时钟影响），不可用（部分小程序环境）回退 Date.now
 * @returns {number}
 */
export function monotonicNow() {
  try {
    if (typeof performance !== 'undefined' && typeof performance.now === 'function') {
      return performance.now()
    }
  } catch (e) {
    // 环境不支持时回退
  }
  return Date.now()
}

/**
 * 服务端时间解析（兼容三种常见形态，失败返回 null）：
 * - 时间戳数字/纯数字字符串：>=1e12 按毫秒、>=1e9 按秒（10 位秒级 epoch）
 * - ISO 字符串（含 T）与 'yyyy-MM-dd HH:mm:ss'（iOS/Safari 不识别空格分隔，规范为 T 再 parse）
 * @param {number|string} [v] - serverTime 原始值
 * @returns {number|null} 毫秒时间戳
 */
export function parseServerTimeMs(v) {
  if (v == null) return null
  if (typeof v === 'number' && Number.isFinite(v)) {
    return v >= 1e12 ? v : (v >= 1e9 ? v * 1000 : null)
  }
  if (typeof v === 'string') {
    const t = v.trim()
    if (/^\d+$/.test(t)) return parseServerTimeMs(Number(t))
    const iso = t.includes(' ') && !t.includes('T') ? t.replace(' ', 'T') : t
    const ms = Date.parse(iso)
    return Number.isFinite(ms) ? ms : null
  }
  return null
}

// 'HH:mm[:ss]' -> {h,mi,s}，非法返回 null
function parseHm(s) {
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(String(s || '').trim())
  return m ? { h: Number(m[1]), mi: Number(m[2]), s: Number(m[3]) || 0 } : null
}

/**
 * 场次结束时刻（毫秒）：end 'HH:mm[:ss]' 锚定到 serverNow 的服务端日期上；
 * 结束时刻已过点时（日场/跨零点场次同口径）顺延到明天，保证始终 >= serverNowMs
 * @param {string} [start] - 场次开始 'HH:mm'
 * @param {string} end - 场次结束 'HH:mm'
 * @param {number} serverNowMs - 服务端当前毫秒（parseServerTimeMs 的结果）
 * @returns {number|null} 结束时刻毫秒（始终 >= serverNowMs 或为 null）
 */
export function sessionEndMs(start, end, serverNowMs) {
  const e = parseHm(end)
  if (!e || !Number.isFinite(serverNowMs)) return null
  const d = new Date(serverNowMs)
  d.setHours(e.h, e.mi, e.s, 0)
  let endMs = d.getTime()
  if (endMs <= serverNowMs) { endMs += DAY_MS }
  return endMs
}
