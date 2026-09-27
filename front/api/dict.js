// ==================== 字典系统对接模块（T-M2-07 改走自家后端代理） ====================
// 枚举数据（商品分类/优惠券/金刚区/服务保障）统一经后端 GET /api/dict?group= 代理拉取：
// 分组名→categoryId 映射与上游鉴权 Key（dict.api-key）均由后端持有，前端不再暴露；
// 注意：自带独立 uni.request 封装，不 import api/index.js，避免循环依赖
import { BASE_URL } from './config.js'

// 分组代码 -> 中文名（用于未知分组校验与日志）
export const GROUP_NAME = {
  dish_category: '商品分类',
  coupon_template: '优惠券配置',
  kingkong_entry: '金刚区入口',
  service_tags: '服务保障标签'
}

// 请求超时（毫秒）
const REQUEST_TIMEOUT = 3000
// sessionStorage 缓存有效期：10 分钟
const CACHE_TTL = 10 * 60 * 1000

// ==================== 内置默认数据（接口失败/超时/解析异常时的兜底，结构与接口返回一致） ====================
const FALLBACK = {
  // 商品分类
  dish_category: [
    { word: '肉蛋水产', definition: 'code:meat', sortOrder: 110 },
    { word: '新鲜蔬菜', definition: 'code:vegetable', sortOrder: 100 },
    { word: '速食', definition: 'code:fastfood', sortOrder: 90 },
    { word: '酒水', definition: 'code:wine', sortOrder: 80 },
    { word: '水果', definition: 'code:fruit', sortOrder: 70 },
    { word: '生鲜', definition: 'code:fresh', sortOrder: 60 },
    { word: '速冻', definition: 'code:frozen', sortOrder: 50 },
    { word: '零食', definition: 'code:snack', sortOrder: 40 },
    { word: '乳饮', definition: 'code:dairy', sortOrder: 30 },
    { word: '粮油', definition: 'code:grain', sortOrder: 20 },
    { word: '百货', definition: 'code:general', sortOrder: 10 }
  ],
  // 优惠券配置（definition 为 JSON 字符串，用 parseCoupon 解析）
  coupon_template: [
    { word: '满19减4', definition: '{"threshold":19,"amount":4,"state":"available"}', sortOrder: 30 },
    { word: '满30减5', definition: '{"threshold":30,"amount":5,"state":"locked1"}', sortOrder: 20 },
    { word: '满50减21', definition: '{"threshold":50,"amount":21,"state":"locked2"}', sortOrder: 10 }
  ],
  // 金刚区入口
  kingkong_entry: [
    { word: '超级秒杀', definition: 'code:seckill', sortOrder: 50 },
    { word: '免费领商品', definition: 'code:free', sortOrder: 40 },
    { word: '领券中心', definition: 'code:coupon_center', sortOrder: 30 },
    { word: '邀请赚现金', definition: 'code:invite', sortOrder: 20 },
    { word: '其他', definition: 'code:more', sortOrder: 10 }
  ],
  // 服务保障标签
  service_tags: [
    { word: '坏了包退', definition: 'code:refund_bad', sortOrder: 40 },
    { word: '晚到必赔', definition: 'code:late_pay', sortOrder: 30 },
    { word: '极速退款', definition: 'code:fast_refund', sortOrder: 20 },
    { word: '缺货退款', definition: 'code:out_of_stock_refund', sortOrder: 10 }
  ]
}

// ==================== 内部工具 ====================

// sessionStorage 安全读写（H5 可用；小程序等无 sessionStorage 环境自动降级为纯内存缓存）
const sessionCache = {
  get(key) {
    try {
      if (typeof sessionStorage === 'undefined') return null
      const raw = sessionStorage.getItem(key)
      if (!raw) return null
      const parsed = JSON.parse(raw)
      // 结构不对或已过期（超过 10 分钟）视为未命中并清理
      if (!parsed || typeof parsed.t !== 'number' || Date.now() - parsed.t > CACHE_TTL) {
        sessionStorage.removeItem(key)
        return null
      }
      return Array.isArray(parsed.data) ? parsed.data : null
    } catch (e) {
      return null
    }
  },
  set(key, data) {
    try {
      if (typeof sessionStorage === 'undefined') return
      sessionStorage.setItem(key, JSON.stringify({ t: Date.now(), data }))
    } catch (e) {
      // 存储失败（隐私模式/超额）静默忽略，内存缓存仍在
    }
  }
}

// 自家后端字典代理 GET 请求（匿名可读，无鉴权头）+ 3s 手动超时兜底（uni.request 的 timeout 部分平台不生效）
function dictRequest(group) {
  return new Promise((resolve, reject) => {
    let settled = false
    const timer = setTimeout(() => {
      if (settled) return
      settled = true
      reject(new Error('字典请求超时(3s): ' + group))
    }, REQUEST_TIMEOUT)
    uni.request({
      url: BASE_URL + '/api/dict',
      method: 'GET',
      data: { group },
      timeout: REQUEST_TIMEOUT,
      success: (res) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        if (res.statusCode === 200) {
          resolve(res.data)
        } else {
          reject(new Error('字典接口状态码异常: ' + res.statusCode))
        }
      },
      fail: (err) => {
        if (settled) return
        settled = true
        clearTimeout(timer)
        reject(new Error('字典请求失败: ' + ((err && err.errMsg) || '未知错误')))
      }
    })
  })
}

// 统一词条结构 { word, definition, sortOrder } 并按 sortOrder 降序排序
function normalizeList(raw) {
  return (Array.isArray(raw) ? raw : [])
    .map((it) => ({
      word: it && it.word != null ? String(it.word) : '',
      definition: it && it.definition != null ? String(it.definition) : '',
      sortOrder: Number(it && it.sortOrder) || 0
    }))
    .sort((a, b) => b.sortOrder - a.sortOrder)
}

// 模块级内存缓存（本次运行期内复用，命中则完全不发请求）
const memoryCache = new Map()

/**
 * 获取字典分组词条列表
 * 优先级：内存缓存 > sessionStorage（10 分钟 TTL）> 远程接口 > 内置 FALLBACK
 * @param {string} code - 分组代码: 'dish_category' | 'coupon_template' | 'kingkong_entry' | 'service_tags'
 * @returns {Promise<Array<{word:string, definition:string, sortOrder:number}>>} 按 sortOrder 降序
 */
export async function getDictGroup(code) {
  // 未知分组直接回退（当前未知分组无默认数据则返回空数组）
  if (!GROUP_NAME[code]) {
    console.warn('[dict] 未知分组代码: ' + code + '，回退内置默认数据')
    return normalizeList(FALLBACK[code] || [])
  }
  // 1) 内存缓存
  if (memoryCache.has(code)) return memoryCache.get(code)
  // 2) sessionStorage 缓存
  const cached = sessionCache.get('dict_' + code)
  if (cached) {
    memoryCache.set(code, cached)
    return cached
  }
  // 3) 远程拉取（自家后端代理）；失败/超时/结构异常回退 FALLBACK（回退结果不写缓存，便于下次自动重试恢复）
  let list
  try {
    const res = await dictRequest(code)
    // 自家后端统一信封 R{code,data,message}：code===200 且 data 为词条数组（后端已按分组代理上游）
    const raw = res && res.code === 200 && Array.isArray(res.data) ? res.data : null
    if (!raw) throw new Error('字典接口返回结构异常')
    list = normalizeList(raw)
    if (!list.length) throw new Error('字典词条为空')
    // 仅成功结果写两级缓存
    memoryCache.set(code, list)
    sessionCache.set('dict_' + code, list)
  } catch (e) {
    console.warn('[dict] 拉取「' + GROUP_NAME[code] + '」失败，回退内置默认数据:', (e && e.message) || e)
    list = normalizeList(FALLBACK[code] || [])
  }
  return list
}

/**
 * 安全解析优惠券 definition JSON 字符串
 * @param {string} definition - 形如 '{"threshold":19,"amount":4,"state":"available"}'
 * @returns {object|null} 解析失败/入参非法返回 null
 */
export function parseCoupon(definition) {
  if (!definition || typeof definition !== 'string') return null
  try {
    const obj = JSON.parse(definition)
    return obj && typeof obj === 'object' && !Array.isArray(obj) ? obj : null
  } catch (e) {
    return null
  }
}

export default { GROUP_NAME, getDictGroup, parseCoupon }
