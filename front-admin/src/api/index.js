// ==================== API 请求封装（运营端唯一网络出口） ====================
// 分层纪律（F-20.11 请求层纪律 / 照 front-partner/api/index.js 的 FD-B 形态）：
// 本层是本工程唯一的原生请求发起点，页面零裸 fetch、零自拼 BASE_URL；
// 信封判定只在 handleEnvelope 这一处（将来若开放写侧端点也必须复用同一个函数，
// 两条通道的 401/403 语义才可能一致）。
//
// 【必须用 http://localhost:5177 打开页面】
// 后端 CorsConfig 的 allowedOrigins 只登记了 localhost 形态（不含 127.0.0.1）。
// 若用 http://localhost:5177 打开页面，Origin 与登记项逐字相符 → 放行；
// 若改用 http://127.0.0.1:5177 打开，Origin 变成 127.0.0.1 → 被 CORS 全拦，
// 浏览器控制台报 CORS、页面收到的是 fetch reject → 会被渲染成「网络异常」这个**假故障**
// （后端其实活得好好的）。这不是后端问题，是打开页面的方式问题。
export const BASE_URL = 'http://localhost:3001'

// 分层超时只两档（本仓语义里的第三档「上传 30s」在本工程不存在：F-20.11 三页全是只读 + 登录，
// 没有任何上传通道，不为「将来可能」留无人调用的常量）：
// 查询类 10s —— 大盘、审计分页这类只读请求；
// 提交类 20s —— 登录（后端验密是 PBKDF2 120k 轮，本机实测约 370ms/次，加锁定读写仍远在 10s 内，
//              但它不是只读查询，按提交档给余量）。
const TIMEOUT_QUERY = 10000
const TIMEOUT_SUBMIT = 20000

// 登录态 storage 键：**本仓唯一真源就是这个常量**，其余任何地方（路由守卫、退出登录、401 清态）
// 一律通过 getStoredToken/clearStoredToken/logout 走，不再出现第二处裸字符串 'admin_token'。
export const TOKEN_STORAGE_KEY = 'admin_token'

// 兜底文案：信封 message 缺失时才用，与后端码表同源（R.java:24 MSG_UNAUTHORIZED / R.java:30 MSG_FORBIDDEN）
const MSG_UNAUTHORIZED = '请先登录'
const MSG_FORBIDDEN = '无权限访问'
const MSG_NETWORK = '网络异常'
const MSG_TIMEOUT = '请求超时，请稍后重试'

// ==================== 登录态读写（localStorage 唯一出口，全程守卫） ====================

export function getStoredToken() {
  try {
    const cached = localStorage.getItem(TOKEN_STORAGE_KEY)
    return typeof cached === 'string' ? cached : ''
  } catch (e) {
    // 隐私模式 / 存储配额异常一律按未登录处理（读写守卫，与三端同纪律）
    return ''
  }
}

export function setStoredToken(token) {
  try {
    localStorage.setItem(TOKEN_STORAGE_KEY, token || '')
  } catch (e) {
    // 落盘失败只影响「刷新后要不要重新登录」，本次会话的内存态照常可用
  }
}

export function clearStoredToken() {
  try {
    localStorage.removeItem(TOKEN_STORAGE_KEY)
  } catch (e) {
    // 存储异常静默：清态的下一步就是回登录页，那里本就不读 token
  }
}

// ==================== 401 跳登录的注册器（照三端 main.js ↔ api 同构，api 不反向 import router） ====================
// api 层若直接 import router，就会形成 api → router → views → api 的静态循环；
// 三端既有做法是由上层注册回调、api 层只负责触发。本工程的注册处是 src/router/index.js。
let unauthorizedHandler = null
export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = typeof fn === 'function' ? fn : null
}

function goToLogin() {
  if (unauthorizedHandler) {
    try {
      unauthorizedHandler()
    } catch (e) {
      // 跳转回调异常不影响 reject 语义（调用方仍能按 code 分流渲染）
    }
  }
}

// ==================== 退出登录 ====================
// 本期后端没有登出端点（JWT 无状态，F-20 只做只读面），故「退出」= 清本地 token + 回登录页。
export function logout() {
  clearStoredToken()
  goToLogin()
}

/**
 * 统一信封分流（本层唯一的信封判定处）。
 * 后端 HTTP 状态码恒 200、业务码只写在 body.code（ResponseUtil 直写信封 + GlobalExceptionHandler 全部 return R），
 * 所以 statusCode 只在信封不可解析时兜底。
 * @param {{statusCode:number, data:*}} res 归一后的响应（fetch 的 status + JSON.parse 结果）
 * @param {boolean} loginRequest 是否登录请求：**登录接口的 401 例外**——不清态、不跳转，
 *        原样把后端 message（三种失败本就是同一句「用户名或密码错误」，D-5 防枚举）交页面渲染，
 *        并把 data.retryAfterSeconds 透出去给登录页起锁定倒计时。
 */
function handleEnvelope(res, resolve, reject, loginRequest) {
  const body = (res && res.data && typeof res.data === 'object') ? res.data : null
  const code = (body && body.code != null) ? Number(body.code) : (res ? Number(res.statusCode) : 0)
  const message = (body && typeof body.message === 'string' && body.message) ? body.message : ''
  const data = (body && body.data !== undefined) ? body.data : null

  if (code === 401) {
    if (loginRequest) {
      // 登录失败的 401 是一次**业务失败**，不是「本地态脏了」：
      // 清 token / 跳登录页在这里都会把用户正在填的表单冲掉，且此时本来就在登录页。
      reject({ code: 401, message: message || MSG_UNAUTHORIZED, data })
      return
    }
    // 其余端点的 401 = token 缺失/伪造/过期（v2 面对静态密钥与 C 端 token 也回同一枚 401，F-20.10 实测）
    // → 清态 + 跳登录 + reject（只 reject 不重试，无死循环）
    clearStoredToken()
    goToLogin()
    reject({ code: 401, message: message || MSG_UNAUTHORIZED })
    return
  }
  if (code === 403) {
    // 403 与 401 严格分流：403 是「登录态有效但没权限」，绝不当登录失效处理，
    // 只结构化 reject，后端 message 原样透出给页面渲染引导态（三端同一语义，不另立判例）
    reject({ code: 403, message: message || MSG_FORBIDDEN, data })
    return
  }
  if (res && res.statusCode === 200 && body && code === 200) {
    resolve(body)
    return
  }
  // 其余（400 / 404 / 429 / 500 / 网络异常 / JSON 不可解析）一律 reject **带 data**：
  // retryAfterSeconds 这类结构化载荷必须能到页面（429 的限流/锁定剩余秒数就靠它，照 FD-B）
  reject({
    code: code || -1,
    message: message || '服务开小差了，请稍后重试',
    data
  })
}

/**
 * 统一请求封装：只有 admin_token 存在时才带 Authorization: Bearer <token>；
 * 登录请求传 loginRequest:true → **强制不带 Authorization**（F-20.11 写死，防未来回退：
 * 后端 BE-20-1 后带了也不会 403，但「匿名端点不该出现凭证」这条纪律不依赖后端行为）。
 * 超时用 AbortController（fetch 没有 timeout 参数，只有 signal 能把「分层超时」这一档如实实现）。
 */
function request(options) {
  const method = (options.method || 'GET').toUpperCase()
  const loginRequest = options.loginRequest === true
  const isSubmit = method !== 'GET'
  // 超时档位：登录 20s（提交档）；其余 GET 查询 10s
  const timeoutMs = isSubmit ? TIMEOUT_SUBMIT : TIMEOUT_QUERY

  const headers = { 'Content-Type': 'application/json' }
  if (!loginRequest) {
    const token = getStoredToken()
    if (token) headers.Authorization = 'Bearer ' + token
  } else {
    // 显式声明：登录请求绝不带 Authorization（不留「以后顺手给 request 加默认头」的余地）
    delete headers.Authorization
  }

  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)

  return new Promise((resolve, reject) => {
    fetch(BASE_URL + options.url, {
      method,
      headers,
      body: options.data !== undefined && method !== 'GET' ? JSON.stringify(options.data) : undefined,
      signal: controller.signal
    })
      .then((res) => res.json().then(
        (json) => ({ statusCode: res.status, data: json }),
        () => ({ statusCode: res.status, data: null })
      ))
      .then((normalized) => {
        clearTimeout(timer)
        handleEnvelope(normalized, resolve, reject, loginRequest)
      })
      .catch((err) => {
        clearTimeout(timer)
        // 网络层失败 / 主动 abort 一律 reject：绝不 resolve(null)——
        // 那会把「没连通」渲染成「没有数据」，是运营看到的最坏的一种假象
        const aborted = err && err.name === 'AbortError'
        reject({ code: -1, message: aborted ? MSG_TIMEOUT : MSG_NETWORK, data: null })
      })
  })
}

// 只给非空值拼查询参数：空串/null/undefined 一律不下发，让服务端走自己的缺省
// （overview 的 date 缺省 → SQL 侧 CURDATE() 决定「今日」，前端不做日期推算）。
function withQuery(url, params) {
  const search = new URLSearchParams()
  Object.keys(params || {}).forEach((key) => {
    const value = params[key]
    if (value !== '' && value !== null && value !== undefined) search.append(key, String(value))
  })
  const qs = search.toString()
  return qs ? url + '?' + qs : url
}

// ==================== 运营登录（匿名端点，唯一不带 Authorization 的请求） ====================

/**
 * 运营口令登录：POST /api/admin/auth/login，body {username, password}
 * 成功 200 → data {token, displayName, adminUserId}（白名单三键，AdminAuthController:29 逐字）。
 * 失败 401 同一句「用户名或密码错误」（不区分用户不存在/口令错/已禁用/锁定中，D-5 防枚举）；
 * 锁定中的 401 额外在 data 里带 {retryAfterSeconds}（AdminAuthServiceImpl:248，BE-5 通道）。
 * 另有 IP 令牌桶 5 次/60s → 429（文案「操作过于频繁，请稍后再试」）。
 * @returns {Promise<{code:number,data:{token:string,displayName:string,adminUserId:number},message:string}>}
 */
export function adminLogin(username, password) {
  return request({
    url: '/api/admin/auth/login',
    method: 'POST',
    data: { username, password },
    loginRequest: true
  })
}

// ==================== v2 只读面（只认运营 JWT：静态密钥在 v2 上也是 401，F-20.10 实测） ====================

/**
 * 只读大盘：GET /api/admin/v2/overview?date=YYYY-MM-DD
 * 出参恰四键（AdminV2ServiceImpl:119-122 的 LinkedHashMap 键序即契约）：
 * {orderCount:number, totalAmount:string 两位小数, pendingPickupCount:number, paidAmount:string 两位小数}。
 * ⚠ 空数据日回的是四个 0（0 / "0.00" / 0 / "0.00"），**不是 null**（F-20.10 实测）。
 * ⚠ 后端**不回显 date**（出参没有第五个键），所以页面的口径文案只能标「服务端 CURDATE()」或用户选定值，
 *   见 Overview.vue 的 captionOf——这一项与 F-20.11「后端回显」的措辞不符，已按实况处理。
 * 脏 date（非 YYYY-MM-DD / 假日期）→ 400；限流 IP 30/60 → 429。
 * @param {string} [date] 空值不下发（服务端按 CURDATE() 聚合今日）
 */
export function getAdminOverview(date) {
  return request({
    url: withQuery('/api/admin/v2/overview', { date }),
    method: 'GET'
  })
}

/**
 * 审计日志分页：GET /api/admin/v2/audit-logs?pageNum=&pageSize=
 * 出参 {list, total, pageNum, pageSize}；行内恰七键
 * id/actor/action/targetType/targetId/detail/createdAt（AdminAuditLogMapper:31-35 的 SELECT 清单，
 * **没有 ip**——SQL 侧连列都没查，RD 最小暴露，页面也不许造这一列）。
 * createdAt 已由后端 DATE_FORMAT 定形成 "YYYY-MM-DD HH:mm:ss" 字符串，页面直接显示、不二次格式化。
 * 越界脏值一律 400 不夹取（pageSize 值域 [1,100]、pageNum ≥1，F-20.10 追认）→
 * **可选页大小只能给 10/20/50/100**，见 AuditLogs.vue 的 PAGE_SIZES。
 * 空表 → {list:[],total:0} 不 500；pageNum 超界 → {list:[],total:定值}。
 * @param {number} pageNum 从 1 起
 * @param {number} pageSize ∈ {10,20,50,100}
 */
export function getAdminAuditLogs(pageNum, pageSize) {
  return request({
    url: withQuery('/api/admin/v2/audit-logs', { pageNum, pageSize }),
    method: 'GET'
  })
}
