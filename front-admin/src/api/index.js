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
 * 429 限流剩余秒数的**转述**（F-22 FE-429-1，收 L-16 / L-20-1）。
 * 后端（BE-429-2 起）在 429 信封的 data 里下发 `retryAfterSeconds`（整数秒、下限 1），
 * 本函数只做一件事：把那一枚数原样念成一句话。
 * ⚠ 纪律（照 views/Login.vue:43 的同一口径——「那是既有载荷的转述，不是推断」）：
 *   函数内**零本地推算**——不兜默认秒数（不许给「猜一个 60」留任何余地）、不按 windowSeconds 估、
 *   不向上取整、不 clamp。
 *   载荷缺失 / 非对象 / 不是 >0 的有限数 → 回空串，页面就退回原有的「请稍候再翻页」文案。
 * 键名逐字 `retryAfterSeconds`，与 AdminAuthServiceImpl:276、Login.vue:125、PartnerOrderServiceImpl:106 同拼写。
 * X-6（F-28.2 片 4）：返回值**自带前导分隔**（一枚空格的 RETRY_AFTER_TEXT_LEAD），拼接点不必再各自补标点。
 *   病因：后端 429 的整句文案（R.java:26「操作过于频繁，请稍后再试」）**结尾没有句号**，而 Overview.vue 的
 *   load() 把「后端原文 + 本函数返回值」直连成一行，无分隔时就拼成
 *   「操作过于频繁，请稍后再试服务端约 2 秒后可再试。」——两词粘连的病句。
 *   分隔符为什么是空格而不是「。，；」这类标点：本函数只拿得到 data、看不见前一句，
 *   而另 6 页（Dishes/Orders/Reviews/StoreApplies/AuditLogs/Arrivals）的 CODE_HINTS[429] 与 WRITE_CODE_HINTS[429]
 *   共 11 处提示**句子自带结尾句号**，补任何标点都会在那 11 处拼出「。。」「。；」双标点；
 *   空格对「有句号的前句」和「没句号的前句」同时成立，且不与后端文案的标点形状发生关系。
 *   反向禁例（同一条理由）：也不许在页面里判「前一句末字符是不是标点」再决定补不补——
 *   那会把前端呈现拴在后端文案上；且 2026-10-22 穷尽列举后只有 Overview.vue 一处直连后端 429 原文，余 6 页拼本页句号句。
 * 与下面 waitDurationText 的分工（别混）：本函数转述的是**后端整句文案里的秒数**，不换算单位、不取整；
 * waitDurationText 只管登录页锁定倒计时的显示写法。两者互不复用。
 * @param {unknown} data reject 载荷里的 e.data（可为 null / undefined / 非对象）
 * @returns {string} 有如实读数时是「 服务端约 N 秒后可再试。」（含前导分隔空格），否则是 ''
 */
// X-6 的前导分隔：单枚空格、非标点，理由见上；要改成别的分隔符必须连同那 11 条自带句号的提示一起看。
const RETRY_AFTER_TEXT_LEAD = ' '

export function retryAfterText(data) {
  if (!data || typeof data !== 'object') return ''
  const n = Number(data.retryAfterSeconds)
  if (!Number.isFinite(n) || n <= 0) return ''
  return RETRY_AFTER_TEXT_LEAD + '服务端约 ' + n + ' 秒后可再试。'
}

/**
 * 等待时长的**显示单位换算**（X-9 / F-28.2 片 4）：只改读数怎么写，不改读数本身。
 * 入参仍是后端如实给的秒数（Login.vue 的 lockRemaining，源头是 401/429 信封 data.retryAfterSeconds），
 * 本函数不产生任何新数值、不兜默认时长：
 *   ≤60 秒 → 「N 秒」，与换算前逐字同形（本页 ≤60 的屏上文案一字不变）；
 *   >60 秒 → 「M 分 SS 秒」，M = floor(c/60)、SS = padStart(c%60,'0')（600 →「10 分 00 秒」、541 →「9 分 01 秒」）。
 * 为什么 ≥60 不再粗化成「大约 M 分钟」：调用方那一屏是个**逐秒自减的倒计时**（Login.vue 每 1s 做
 *   lockRemaining -= 1），粗化到分钟会让 600→541 这整整 60 秒里屏上读数一动不动——一把正在跑的锁
 *   印成一个静止的数字。本仓另外两端早已用分秒制避开它（front-merchant / front-partner 的 api 层
 *   formatCoolingSeconds），其中 front-merchant 那两处还点名写了「别照抄运营端那种 ceil 到分钟」
 *   （partner 侧没有这句点名，只有同形的实现），本页是同族漏网的第三端。
 * 分档线落在「60 归秒制」（60 →「60 秒」而不是「1 分 00 秒」）：本仓另外两端的 formatCoolingSeconds
 *   都是 `c <= 60` 这一条线（F-38.3 定案），这里取同值只为三端对同一个 60 给同一个读法。注意 F-38.3
 *   原本的病（同一个 60 在同一屏印成两副面孔）在运营端**不存在**——本页只有这一处换算函数，
 *   所以那条前提不搬过来当依据，只搬它定下的线；运营端能观测到 60 的场合是限流窗口剩整分钟那一刻。
 * 单位形态与另两端刻意不同：它们服务 pill 短标签（≤60 用紧凑 60s），本页服务整句「请 <时长> 后再试」，
 *   所以两支都用中文「N 秒」，不引 COOLING_UNIT。
 * 取整方向仍是**向上**（ceil），但要说清它当前不产生差异：本页 lockRemaining 从后端下发的整数秒起、
 *   每 1s 自减 1，入参恒为整数，ceil 与 floor 同值；留着 ceil 是防御——入参一旦带小数，宁可把等待
 *   说大一格也不说小（低估会让人按点重试再吃一次拒）。别把这句当"向上取整修掉了某个真实读数"。
 * 非有限数 / ≤0 → 回 ''：调用方（Login.vue 的 locked 闸门）此时本就不显示这一行，
 *   不拿它造一句「0 分 00 秒后再试」。
 * ⚠ 红线（本条契约的原话是「归因文案不许动」）：本函数只碰倒计时那一行的**时长单位**，
 *   绝不碰同屏那句后端原文（「用户名或密码错误」）。三种失败在服务端本就是同一句（D-5 防枚举），
 *   页面不许把它改写成「已连续输错 5 次」这类暴露锁定条件的说法。
 * @param {unknown} seconds 后端给的剩余秒数（可为字符串数字 / null / 非数）
 * @returns {string} 「N 秒」（≤60）/「M 分 SS 秒」（>60）/ ''
 */
export function waitDurationText(seconds) {
  const n = Number(seconds)
  if (!Number.isFinite(n) || n <= 0) return ''
  const c = Math.ceil(n)
  if (c <= 60) return c + ' 秒'
  return Math.floor(c / 60) + ' 分 ' + String(c % 60).padStart(2, '0') + ' 秒'
}

/**
 * 统一请求封装：只有 admin_token 存在时才带 Authorization: Bearer <token>；
 * 登录请求传 loginRequest:true → **强制不带 Authorization**（F-20.11 写死，防未来回退：
 * 后端 BE-20-1 后带了也不会 403，但「匿名端点不该出现凭证」这条纪律不依赖后端行为）。
 * F-63 新增 anonymous:true → 同样强制不带 Authorization，但**不改变 401 语义**（loginRequest 会把 401
 * 当业务失败不清态不跳转，anonymous 不需要那条例外）。为什么门店列表必须走这一档：
 * AuthInterceptor 的双向 typ 隔离铁律会把带运营 JWT（typ=admin）的、路径不在 /api/admin 前缀下的请求
 * 直接 403（该拦截段在 preHandle 的 requiresLogin 判定之前，按「typ=admin 反隔离」具名即可，不挂行号指针）；
 * GET /api/stores 恰好就在 admin 前缀之外——带上我们必然的 Bearer 反而是假故障。
 * 超时用 AbortController（fetch 没有 timeout 参数，只有 signal 能把「分层超时」这一档如实实现）。
 */
function request(options) {
  const method = (options.method || 'GET').toUpperCase()
  const loginRequest = options.loginRequest === true
  const skipAuth = loginRequest || options.anonymous === true
  const isSubmit = method !== 'GET'
  // 超时档位：登录 20s（提交档）；其余 GET 查询 10s
  const timeoutMs = isSubmit ? TIMEOUT_SUBMIT : TIMEOUT_QUERY

  const headers = { 'Content-Type': 'application/json' }
  if (!skipAuth) {
    const token = getStoredToken()
    if (token) headers.Authorization = 'Bearer ' + token
  } else {
    // 显式声明：登录请求与匿名端点绝不带 Authorization（不留「以后顺手给 request 加默认头」的余地）
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

// ==================== 文件路径 -> 可展示 URL（F-107 证照缩略图） ====================
/**
 * 站内相对路径 -> 后端可直读 URL：以 '/' 开头的拼 BASE_URL，其余（已是绝对 http(s) 地址等）原样返回；
 * 空值/非字符串回空串。姿势照 front-partner/api/index.js 的 resolveFileUrl——拼 BASE_URL 的责任
 * 收敛在请求层，页面零自拼。当前唯一调用方是商家入驻审核页的证照缩略图
 * （licenses 里的 url 形如 /static/images/uploads/<名>）。
 */
export function resolveFileUrl(u) {
  if (typeof u !== 'string' || !u) return ''
  return u.charAt(0) === '/' ? BASE_URL + u : u
}

// ==================== 运营登录（匿名端点；本工程不带 Authorization 的请求共两处，另一处是下面的 getStores） ====================

/**
 * 运营口令登录：POST /api/admin/auth/login，body {username, password}
 * 成功 200 → data {token, displayName, adminUserId}（白名单三键，AdminAuthController:29 逐字）。
 * 失败 401 同一句「用户名或密码错误」（不区分用户不存在/口令错/已禁用/锁定中，D-5 防枚举）；
 * 锁定中的 401 额外在 data 里带 {retryAfterSeconds}（AdminAuthServiceImpl:276，BE-5 通道）。
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
 * 出参恰六键（AdminV2ServiceImpl#overview 的 LinkedHashMap 键序即契约）：
 * {orderCount:number, totalAmount:string 两位小数, pendingPickupCount:number, paidAmount:string 两位小数,
 *  notifyRejectCount:number, notifyRejectLastAt:string|null}。
 * ⚠ 空数据日前四键回的是四个 0（0 / "0.00" / 0 / "0.00"），**不是 null**（F-20.10 实测）。
 * ⚠ 后两键是 F-50 加的当日「微信支付回调拒收」读数（与前四键同一个自然日窗口）：
 *   notifyRejectLastAt 是服务端 DATE_FORMAT 定好的 "HH:mm" 串，**当日没有拒收时是显式 null、键一定在**
 *  （F-42b 信封纪律：缺键与显式 null 是两种语义）；页面只展示这一串，不许自己换算时区或减 8 小时。
 * ⚠ 后端**不回显 date**（出参没有第七个键），所以页面的口径文案只能标「服务端 CURDATE()」或用户选定值，
 *   见 Overview.vue 的 scopeText——这一项与 F-20.11「后端回显」的措辞不符，已按实况处理。
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
 * 审计日志分页：GET /api/admin/v2/audit-logs?pageNum=&pageSize=&actor=&action=&targetType=&targetId=
 * 出参 {list, total, pageNum, pageSize, filters}；行内恰七键
 * id/actor/action/targetType/targetId/detail/createdAt（AdminAuditLogMapper 的 SELECT 清单，
 * **没有 ip**——SQL 侧连列都没查，RD 最小暴露，页面也不许造这一列）。
 * createdAt 已由后端 DATE_FORMAT 定形成 "YYYY-MM-DD HH:mm:ss" 字符串，页面直接显示、不二次格式化。
 * 越界脏值一律 400 不夹取（pageSize 值域 [1,100]、pageNum ≥1，F-20.10 追认）→
 * **可选页大小只能给 10/20/50/100**，见 AuditLogs.vue 的 PAGE_SIZES。
 * 空表 / 筛选无命中 → {list:[],total:0} 不 500；pageNum 超界 → {list:[],total:定值}。
 * <p>F-20.16（收 G-06 DoD ④）filters：四个**精确等值**可选筛选 {actor, action, targetType, targetId}，全非必填。
 * 走 withQuery 只拼非空值 → 空串/null 一律不下发（服务端把缺省当「不筛」，绝不发空串）；等值匹配**不区分大小写**
 * （列 collation 决定）。脏形状（含空格/%/超 64 字符）→ 后端 400。出参 filters 是生效筛选的回显，未传的键为 null。
 * @param {number} pageNum 从 1 起
 * @param {number} pageSize ∈ {10,20,50,100}
 * @param {{actor?:string, action?:string, targetType?:string, targetId?:string}} [filters] 筛选对象，缺省即不筛
 */
export function getAdminAuditLogs(pageNum, pageSize, filters) {
  // 页面不自拼 URL：筛选值一律经本层 withQuery 收口（只下发非空值），保持向后兼容的 (pageNum, pageSize) 位置调用
  const f = filters || {}
  return request({
    url: withQuery('/api/admin/v2/audit-logs', {
      pageNum,
      pageSize,
      actor: f.actor,
      action: f.action,
      targetType: f.targetType,
      targetId: f.targetId
    }),
    method: 'GET'
  })
}

// ==================== v2 四条只读列表（F-20.22 Step 5b-2 的四个 P0 页的数据源，5b-1 已交付） =====
// 四条共用同一外层形态：{list, total, pageNum, pageSize, filters}，filters 里**未传的键恒为 null**（D-19）。
// 分页/筛选纪律与 audit-logs 逐字同一套（D-16/D-17）：pageNum≥1、pageSize∈[1,100] 缺省 20、
// 越界与脏值一律 400 **不夹取**、筛选全是「精确等值」（本面零 LIKE、没有关键字搜索参数）。
// 400 在服务端有**两句**原文（AdminV2ServiceImpl.BAD_REQUEST_MSG「请求参数有误，请检查后重试」
// 与 GlobalExceptionHandler:70 对类型绑不进去的「参数格式不正确」），请求层只透 message、不猜是哪一句
// ——判定与并列文案渲染都在页面那两条提示条里（照 FE-G6-6 的修法）。
// 金额是服务端定形的两位小数**字符串**、时间是 DATE_FORMAT 定形的 "YYYY-MM-DD HH:mm:ss" 字符串（D-18），
// 本层不做任何换算/格式化，页面原样显示。phone / contactPhone 已在服务端过 PhoneMask（D-15），
// 本层与页面都不许拼回明文。

/**
 * 运营端订单列表（订单管理页数据源）：GET /api/admin/v2/orders
 * 行内键（AdminV2ServiceImpl#orders 里逐键重拼的白名单，AdminV2Controller 的 javadoc 同一份）：
 * id,totalPrice,**address**,phone,pickupDate,status,createTime,updateTime,storeId,storeName,storeAddress,payStatus,payTime。
 * ⚠ `pickup_code` / `user_id` / `client_request_id` 三键在 SQL 层连列都没 SELECT（D-15 禁下发），
 *   页面不许凭空造这三列。`id` 是 **19 位雪花 id、JSON 里就是字符串**，本层与页面一律原样透传、绝不 Number()。
 * 排序 create_time DESC, id DESC。
 * @param {number} pageNum 从 1 起
 * @param {number} pageSize ∈ {10,20,50,100}
 * @param {{status?:string, storeId?:string|number, date?:string}} [filters]
 *        status ∈ pending_pickup|completed|cancelled（服务端先 toLowerCase 再过白名单，BE-G6-2）；
 *        storeId 等值；date=YYYY-MM-DD 按 create_time 的自然日（缺省=不限，「今日」由服务端决定，前端不推算）
 */
export function getAdminOrders(pageNum, pageSize, filters) {
  const f = filters || {}
  return request({
    url: withQuery('/api/admin/v2/orders', {
      pageNum,
      pageSize,
      status: f.status,
      storeId: f.storeId,
      date: f.date
    }),
    method: 'GET'
  })
}

/**
 * 运营端商品列表（商品管理台数据源，一期**只改不建**——Admin 面本就没有「新建商品」端点，F-20.17 事实 3 附注）：
 * GET /api/admin/v2/dishes
 * 行内键：id,name,price,stock,onSale,soldCount,category,unit,image,goodRate,seckill,seckillPrice,limitBuy,
 * merchantId,createTime,updateTime。onSale/seckill 出 0/1 原值由页面映射文案（D-18）；
 * seckillPrice 非秒杀恒 null；goodRate 是服务端算好的 "94.3%" 串（可为 null），不二次格式化。
 * 排序 id ASC（不是新建在前）。
 * ⚠ 没有关键字搜索参数（D-17 把「商品搜索框」登记为边界，不许顺手加）。
 * @param {{merchantId?:string|number, onSale?:string|number, category?:string}} [filters]
 *        onSale ∈ {0,1}；category 精确等值且**允许中文**（库里确有一行分类是「蔬菜」，BE-G6-1 已将闸门放宽到
 *        「长度 ≤64 且不含控制字符」，所以点分类名不会是 400）
 */
export function getAdminDishes(pageNum, pageSize, filters) {
  const f = filters || {}
  return request({
    url: withQuery('/api/admin/v2/dishes', {
      pageNum,
      pageSize,
      merchantId: f.merchantId,
      onSale: f.onSale,
      category: f.category,
      auditStatus: f.auditStatus
    }),
    method: 'GET'
  })
}

/**
 * 运营端评价队列（评价审核页数据源）：GET /api/admin/v2/reviews
 * 行内键：id,dishId,rating,content,reviewer,auditStatus,createTime。
 * ⚠ **没有 dishName**（AdminV2ServiceImpl 侧不 JOIN dish，ReviewMapper 的注释点名「菜品名由同批的 dishes 列表
 *   在前端对齐」）→ 要显示菜品名只能拿 getAdminDishes 的 id→name 映射，绝不为此改后端。
 * ⚠ `user_id` 禁下发（D-15）。
 * auditStatus 行里是**库里原值**：'pending'=待人工复审、null=展示中；库里不存在「已驳回」这一态
 * （reject 是 deleteById 物理删行），页面文案绝不许写「已驳回列表」。
 * @param {{auditStatus?:string}} [filters] 两枚 UI 伪值：pending → audit_status='pending'、
 *        visible → audit_status IS NULL（D-17 钉死的 SQL 映射）；白名单外 400。filters 回的是伪值本身（D-19）
 */
export function getAdminReviews(pageNum, pageSize, filters) {
  const f = filters || {}
  return request({
    url: withQuery('/api/admin/v2/reviews', {
      pageNum,
      pageSize,
      auditStatus: f.auditStatus
    }),
    method: 'GET'
  })
}

/**
 * 运营端入驻申请列表（入驻审核页数据源）：GET /api/admin/v2/store-applies
 * 行内键：id,proposedName,proposedAddress,lng,lat,service,contactName,contactPhone,status,auditRemark,
 * auditedAt,createdAt。
 * ⚠ `proof_photos`（执照图，§9 悬项未拍板）、`user_id`、生成列 `pending_guard` 三者在 SQL 层就不查询，
 *   页面没有「查看资质材料」这一列可用——这是实况，不是缺陷，也不许从别处凑。
 * contactPhone 过 PhoneMask（可空列：null 原样回 null）。排序 id DESC。
 * @param {{status?:string}} [filters] status ∈ pending|approved|rejected（三态白名单，大小写不敏感，BE-G6-2）
 */
export function getAdminStoreApplies(pageNum, pageSize, filters) {
  const f = filters || {}
  return request({
    url: withQuery('/api/admin/v2/store-applies', {
      pageNum,
      pageSize,
      status: f.status
    }),
    method: 'GET'
  })
}

// ==================== 旧面四个写端点（AdminController，运营 JWT 第二轨，F-20.17 事实 2 已实测整条链路） =====
// 路径里的 id 一律 encodeURIComponent(String(id)) 原样透传：orders.id 是 19 位雪花 id，
// 一旦 Number() 化就丢精度（…4738 → …4000 之类），打过去必然 404「订单不存在」——那是前端造的假故障。
// Authorization 由上面的 request() 在 token 存在时自动挂（本层唯一的挂头处），
// method≠GET → isSubmit → 走 **TIMEOUT_SUBMIT 20s** 提交档（纪律 1）。
// ⚠ 这四个端点带 @RateLimit(IP, 10 次/60s)，**比只读列表的 30 次/分更紧**；
//   写成功与否由页面重拉列表核对，绝不本地改行（纪律 3）。

/**
 * 订单核销/取消：PUT /api/admin/orders/{id}/status，body {status, reason?}
 * @param {string} id 订单 id（**服务端回的原样字符串**，19 位雪花，绝不 Number()）
 * @param {string} status 'completed'（核销）| 'cancelled'（取消）；其余值服务端 400
 *        「status 仅允许 completed（核销）或 cancelled（取消）」；查无此单 404「订单不存在」；
 *        非 pending_pickup 的单 400「当前状态不可变更（仅待自提订单可核销/取消）」
 * @param {string} [reason] 取消理由（F-61）：**status=cancelled 时服务端必填**，空白即 400
 *        「取消订单必须填写原因（会回补库存并置已退款，需可追责）」；>255 字 400「取消原因不能超过 255 字」。
 *        核销不传（不本地化空串下发——服务端「空则 detail 不落这一键」，前端也不该替它决定写什么）
 * @returns {Promise<{code:number,message:string,data:null}>} 成功 message「订单状态已更新」
 */
export function updateAdminOrderStatus(id, status, reason) {
  const body = { status }
  // 空串/null/undefined 一律不下发 reason 键：让「没填」与「填了空白」在后端判据里保持可分
  if (reason !== undefined && reason !== null && String(reason) !== '') body.reason = reason
  return request({
    url: '/api/admin/orders/' + encodeURIComponent(String(id)) + '/status',
    method: 'PUT',
    data: body
  })
}

/**
 * 商品改价/补库存/上下架：PUT /api/admin/dishes/{id}，body {price?, stock?, onSale?}（**部分更新**）
 * 只下发运营实际填过的那几项（服务端语义是「传了才更新」，三项都不传 → 400「无更新字段（可传 price / stock / onSale）」，
 * 该 400 在 setCount==0 处抛、不触库，是零副作用的诚实回答，页面原样渲染即可）。
 * @param {number|string} id 菜品 id（列表里的数字 id）
 * @param {{price?:number, stock?:number, onSale?:number}} patch
 *        price @DecimalMin 0.01「价格必须大于 0」；stock @Min 0「库存不能为负数」且是**直接置值**不是累加；
 *        onSale @Min0 @Max1「onSale 仅允许 0（下架）或 1（上架）」；查无此菜 404「菜品不存在」
 * @returns {Promise<{code:number,message:string,data:null}>} 成功 message「商品已更新」
 */
export function updateAdminDish(id, patch) {
  return request({
    url: '/api/admin/dishes/' + encodeURIComponent(String(id)),
    method: 'PUT',
    data: patch || {}
  })
}

/**
 * 商品内容审核（F-116 先发后审）：PUT /api/admin/dishes/{id}/audit，body {action, reason?}
 * @param {number|string} id 菜品 id
 * @param {{action:'reject'|'restore', reason?:string}} body
 *        reject：reason 必填 trim 后 1~200，置 audit_status='rejected' + 理由（**不改 on_sale**，两列正交）；
 *        restore：置 'ok' 并清理由，body 带 reason 忽略。
 *        action 非法 400「action 仅允许 reject/restore」；查无此菜 404「菜品不存在」；
 *        已是目标态幂等 200（重复驳回/恢复不产生假变化，审计照记）
 * @returns {Promise<{code:number,message:string,data:null}>} 成功 message「商品已审核」
 */
export function auditAdminDish(id, body) {
  return request({
    url: '/api/admin/dishes/' + encodeURIComponent(String(id)) + '/audit',
    method: 'PUT',
    data: body || {}
  })
}

/**
 * 评价审核：PUT /api/admin/reviews/{id}/audit，body {action}
 * @param {number|string} id 评价 id
 * @param {string} action 'approve'（过审：audit_status 置 NULL 恢复展示，可重复执行）
 *        | 'reject'（驳回：**deleteById 物理删行，不可恢复**）；
 *        其余值 400「action 仅允许 approve（过审展示）或 reject（驳回删除）」；查无此评价 404「评价不存在」
 * @returns {Promise<{code:number,message:string,data:null}>} 成功 message「评价已审核」
 */
export function auditAdminReview(id, action) {
  return request({
    url: '/api/admin/reviews/' + encodeURIComponent(String(id)) + '/audit',
    method: 'PUT',
    data: { action }
  })
}

/**
 * 入驻申请审核：PUT /api/admin/store-applies/{id}/audit，body {action, remark?}
 * 服务端读的是 @RequestBody Map<String,String> 的 action / remark 两个键（**无 DTO**，
 * 姿势照 PartnerController#verify），所以本层只发字符串值，remark 为空时**整个键不下发**
 * （不传 = 服务端不 SET audit_remark，避免把空串写进留痕列）。
 * @param {number|string} id 申请 id
 * @param {string} action 'approve'（同事务置态 + INSERT store + 把 store.owner_user_id 绑成申请人，
 *        **不可翻转、不可用 SQL 代办、本页面无法回滚**）| 'reject'（只置 rejected + 留 audit_remark）
 * @param {string} [remark] 审核意见，可空；>255 字服务端 400「审核意见不能超过 255 字」
 *        其余失败：400「action 仅允许 approve（过审并发布门店）或 reject（驳回）」、
 *        400「该申请已审核完成，不可再次审核」、404「申请不存在」
 * @returns {Promise<{code:number,message:string,data:null}>} 成功 message「申请已审核」
 */
export function auditAdminStoreApply(id, action, remark) {
  const body = { action }
  if (remark !== '' && remark !== null && remark !== undefined) body.remark = String(remark)
  return request({
    url: '/api/admin/store-applies/' + encodeURIComponent(String(id)) + '/audit',
    method: 'PUT',
    data: body
  })
}

// ==================== F-63 到货发车（preview 只读 + depart 写 + 公开门店列表） ====================
// 契约真源：docs/上线任务文档.md「F-63｜运营端「发车」」的「接口形状（BE/FE 共用）」一节，
// 后端并行按同一份写——本层字段名一律逐字照抄契约，不做任何改名/补键/兜底推算。
// 参数名有个刻意的不对称：preview 的查询参数是 `date`，发车 body 里是 `batchDate`，
// 照契约各写各的，不许互换（同 front-partner/api/index.js 对 arrivals/orders 参数名不同源的纪律）。

/**
 * 门店下拉数据源（**既有公开端点**，本片不新开后端端点）：GET /api/stores
 * 出参 data 是**数组**（不是 {list,total} 分页信封）：每行键集合恒八键
 * id,name,address,service,status,lng,lat,createdAt（Store.java 类注释 D-23 的门禁断言）；
 * id 是自增小整数（非 19 位雪花），status ∈ open（营业）| closed（停业）。
 * 全站既有约定（StoreController 类注释 + front C 端 pickup 页同姿势）：**停业门店前端置灰不可选**，
 * 页面按此渲染，本层不筛不滤、原样透传。
 * ⚠ 必须 anonymous（不带 Authorization）：带运营 JWT 打非 /api/admin/** 端点会被
 *   AuthInterceptor 的双向 typ 隔离直接 403（见 request() 上方注释），那是链路纪律不是权限问题。
 * 限流：IP 30 次/分（StoreController#list 的 @RateLimit，读档）。
 */
export function getStores() {
  return request({
    url: '/api/stores',
    method: 'GET',
    anonymous: true
  })
}

/**
 * 发车前预览（只读）：GET /api/admin/arrivals/preview?storeId={id}&date={yyyy-MM-dd}
 * 出参恰八键（契约逐字，实测 AdminServiceImpl#arrivalPreview 逐键 put 八次）：
 * {storeId, storeName, date,
 *  existing: null | {id, status, confirmTime, itemCount},
 *  plan: [{dishId, dishName, qty}],
 *  orderCount, dishKinds, departable}
 * departable = existing == null || status ∈ {planned, arriving}；页面只读这一枚布尔做闸门，
 * 不在本地重拼该判定（confirmed/short 的语义由服务端裁决）。
 * existing.confirmTime 为服务端定形串或 null，页面原样显示、不二次格式化。
 * 鉴权同 AdminController 旧面（X-Admin-Key 或运营 JWT）；限流按文档锚点与只读列表同档（IP 30 次/分）。
 * @param {number|string} storeId 门店 id（自增小整数，非雪花）
 * @param {string} date YYYY-MM-DD；空值由 withQuery 挡下不下发（服务端自定缺省，页面不依赖它——本页永远显式给值）
 */
export function getArrivalPreview(storeId, date) {
  return request({
    url: withQuery('/api/admin/arrivals/preview', { storeId, date }),
    method: 'GET'
  })
}

/**
 * 发车（写）：POST /api/admin/arrivals，body {storeId, batchDate, remark?}
 * 成功 200 → data {batchId, status:'arriving', itemCount, dishKinds, orderCount, idempotent}，
 * message「已发车，批次进入到货中」；idempotent=true（该店该日已有 planned/arriving 批次）时
 * message「该店该日批次已在途，已按最新订单重算明细」。
 * 失败文案以服务端原文为准（判据顺序契约钉死：@Valid 形状 → 过去日期 400 → 门店不存在 404 →
 * 已 confirmed/short 400 → 无可发订单 400）；本层不猜各句原文。
 * 限流：Admin 写面 IP 10 次/60。
 * ⚠ 写成功与否由页面重拉 preview 核对，绝不本地改状态（纪律 3）。
 * @param {number|string} storeId 门店 id
 * @param {string} batchDate YYYY-MM-DD（早于今天服务端 400）
 * @param {string} [remark] 备注，可空；空串/null/undefined **整个键不下发**（照 updateAdminOrderStatus 的 reason 姿势）
 */
export function departArrival(storeId, batchDate, remark) {
  const body = { storeId, batchDate }
  if (remark !== undefined && remark !== null && String(remark) !== '') body.remark = String(remark)
  return request({
    url: '/api/admin/arrivals',
    method: 'POST',
    data: body
  })
}

// ==================== F-106 商家自助入驻（运营端：申请列表 + 审核） ====================
// 契约真源：docs/上线任务文档.md 的 F-106（片 A 的审核端点 + 片 B2 补的运营端列表端点）。
// 鉴权与其余 AdminController 端点同一面（checkKeyOrOperator 双轨：X-Admin-Key 或运营 JWT）：
// 浏览器端只走 JWT 这一轨——request() 在 admin_token 存在时自动挂 Authorization，
// 静态密钥是给命令行/脚本用的另一轨，本工程没有存放它的地方，也不凭空造头。
// 列表行键由后端逐键重拼成十四键白名单（F-107 起加 licenses），本层不改名、不补键，
// 也不对已脱敏字段做任何二次加工。

/**
 * 运营端商家入驻申请列表（商家入驻审核页数据源）：GET /api/admin/merchant-applies?status=&pageNum=&pageSize=
 * 出参外层 {list, total, pageNum, pageSize, filters}（与 v2 列表族逐字同形）。
 * 行内恰十四键：id, userId, merchantName, contactName, businessLicense, licenses, legalPerson, address,
 * settlementAccount, status, auditRemark, createdAt, auditedAt, applicantPhone。
 * licenses 是 JSON 数组的**字符串原样**（形如 [{"type":"business_license","url":"/static/images/uploads/a.jpg"}]），
 * 老数据为 null / 空串；本层不 parse，页面按 try/catch + Array.isArray 自行容错解析（坏数据当没有）。
 * applicantPhone 是 user.phone 经 PhoneMask 的脱敏值（形如 138****1234），本层与页面一律原样展示、
 * **不拼回明文、不再截断**；createdAt / auditedAt 由服务端 DATE_FORMAT 定形成 "YYYY-MM-DD HH:mm:ss"，
 * 不二次格式化。排序 pending 优先，其次 created_at DESC, id DESC。
 * status 空 = 全部，非空仅 pending|approved|rejected（服务端归一小写后过白名单，白名单外 400）；
 * pageNum ≥1、1≤pageSize≤100、缺省 1/20，越界与脏值一律 400 不夹取。
 * 限流：Admin 只读面 IP 30 次/分。
 * @param {number} pageNum 从 1 起
 * @param {number} pageSize ∈ {10,20,50,100}
 * @param {{status?:string}} [filters] 空串由 withQuery 挡下不下发（服务端把缺省当「不筛」）
 */
export function listMerchantApplies(pageNum, pageSize, filters) {
  const f = filters || {}
  return request({
    url: withQuery('/api/admin/merchant-applies', {
      status: f.status,
      pageNum,
      pageSize
    }),
    method: 'GET'
  })
}

/**
 * 商家入驻申请审核：PUT /api/admin/merchant-applies/{id}/audit，body {action, remark?}
 * 服务端读的是 @RequestBody Map<String,String> 的 action / remark 两个键（**无 DTO**，姿势照
 * auditAdminStoreApply），所以本层只发字符串值；remark 为空时**整个键不下发**
 * （不传 = 服务端不 SET audit_remark，避免把空串写进留痕列）。
 * @param {number|string} id 申请 id（自增小整数，行里原样透传；encodeURIComponent(String(id)) 只做路径转义，不做数值变换）
 * @param {string} action 'approve'（同一事务：新建 merchant（contact_phone 取申请人 user.phone，不取表单）
 *        + 新建 merchant_profile(approved) + 条件 UPDATE user 绑定新商家 + 置申请 approved + 审计）|
 *        'reject'（只置 rejected + 留 audit_remark，**不删申请行**）
 * @param {string} [remark] 审核意见；reject 时**必填**（空 → 400「驳回时必须填写审核意见」），
 *        >255 字 → 400「审核意见不能超过 255 字」
 * 已审核（approved/rejected）再审是**幂等成功**（message 仍是「申请已审核」，不会重复建商家）；
 * 其余失败：400「action 仅允许 approve（过审并开通商家）或 reject（驳回）」、
 * 400「该用户已绑定商家，请刷新后重试」、400「该申请已审核完成，请刷新后重试」、404「申请不存在」。
 * 限流：Admin 写面 IP 10 次/60。
 * @returns {Promise<{code:number,message:string,data:null}>} 成功 message「申请已审核」
 */
export function auditMerchantApply(id, action, remark) {
  const body = { action }
  if (remark !== '' && remark !== null && remark !== undefined) body.remark = String(remark)
  return request({
    url: '/api/admin/merchant-applies/' + encodeURIComponent(String(id)) + '/audit',
    method: 'PUT',
    data: body
  })
}

/**
 * 平台代开通商家（一期能力，二期补运营台入口）：POST /api/admin/merchants。
 * body {name, phone, contactName?}。后端按 phone 找**已注册**的 user，同事务建 merchant +
 * merchant_profile(approved) 并绑定 user.merchant_id；手机号未注册 / 已绑定商家均回 400，
 * 文案由后端给出，本函数原样透出，不改写。
 */
export function createAdminMerchant(payload) {
  const body = { name: payload.name, phone: payload.phone }
  if (payload.contactName) body.contactName = String(payload.contactName)
  return request({ url: '/api/admin/merchants', method: 'POST', data: body })
}

// ==================== F-108 片 2 字典管理（分组 + 词条 CRUD + 缓存清除） ====================
// 契约真源：docs/上线任务文档.md §0.7 片 2（六个端点全部在 AdminController，双轨鉴权；
// 浏览器端只走运营 JWT 这一轨——request() 在 admin_token 存在时自动挂 Authorization）。
// 写操作成功后由服务端内部自动 evict 该词条所属分组（AdminServiceImpl），本节只保留 §0.4 的手工
// evict 端点；页面也一律以写后重拉分组/词条为准，不做本地改行（与其余写侧页同一纪律）。
// 路径里的 group / code 是 dict_group.code 的 ASCII 串，id 是 dict_item 自增整数；
// 一律经 encodeURIComponent(String(...)) 只做路径转义，不做任何数值变换。

/**
 * 字典分组列表：GET /api/admin/dict/groups
 * 出参 data 是**数组**：[{code,name,enabled,itemCount,sort}]，按 sort ASC, code ASC。
 * enabled ∈ {0,1}（1 启用 / 0 停用；停用后 C 端 GET /api/dict 按空返回且不回退上游）；
 * itemCount 是服务端 GROUP BY 的真实计数，无词条的组为 0。只读档限流 IP 30/60s。
 */
export function getAdminDictGroups() {
  return request({ url: '/api/admin/dict/groups', method: 'GET' })
}

/**
 * 某分组全部词条（不分页）：GET /api/admin/dict/{group}/items
 * 出参 data 是数组：[{id,word,definition,sortOrder}]，顺序 sort_order DESC, id ASC（与 C 端读路径同口径）；
 * definition 可为 null。分组不存在 → 400「字典分组不存在」。只读档限流 IP 30/60s。
 * @param {string} group dict_group.code（如 dish_category）
 */
export function getAdminDictItems(group) {
  return request({
    url: '/api/admin/dict/' + encodeURIComponent(String(group)) + '/items',
    method: 'GET'
  })
}

/**
 * 新增词条：POST /api/admin/dict/{group}/items，body {word, definition?, sortOrder?}
 * word 必填（服务端 trim 后校验非空、最长 100 字符）；definition 可空（最长 500）、
 * sortOrder 可空（服务端缺省落 0）。分组不存在 400；uk_group_word 冲突 400（文案由服务端给）。
 * 成功 message「词条已新增」，且服务端自动 evict 该组缓存。写档限流 IP 10/60s。
 * @param {string} group dict_group.code
 * @param {{word:string, definition?:string, sortOrder?:number|null}} payload
 *        definition 传空串表示「落空串」，传 null/undefined 则整个键不下发（新增缺省即空）；
 *        sortOrder 为 null/undefined 时不发，让服务端按 0 落库。
 * @returns {Promise<{code:number,message:string,data:null}>}
 */
export function createAdminDictItem(group, payload) {
  const body = { word: payload.word }
  if (payload.definition !== undefined && payload.definition !== null) body.definition = payload.definition
  if (payload.sortOrder !== undefined && payload.sortOrder !== null) body.sortOrder = payload.sortOrder
  return request({
    url: '/api/admin/dict/' + encodeURIComponent(String(group)) + '/items',
    method: 'POST',
    data: body
  })
}

/**
 * 部分更新词条：PUT /api/admin/dict/items/{id}，body {word?, definition?, sortOrder?}
 * null/缺键 = 本次不改该列（MyBatis-Plus NOT_NULL 策略）；三列全空 → 400「无更新字段…」；
 * word 传空白串仍 400「词条名称不能为空」；改后撞 uk_group_word → 400。id 不存在 404。
 * 成功 message「词条已更新」，且服务端自动 evict 该词条所属组缓存。写档限流 IP 10/60s。
 * @param {number|string} id dict_item.id（自增整数）
 * @param {{word?:string, definition?:string, sortOrder?:number|null}} patch
 *        definition 传空串 = 显式清空（服务端允许）；传 null/undefined 则不下发 = 不改。
 */
export function updateAdminDictItem(id, patch) {
  const body = {}
  if (patch.word !== undefined) body.word = patch.word
  if (patch.definition !== undefined && patch.definition !== null) body.definition = patch.definition
  if (patch.sortOrder !== undefined && patch.sortOrder !== null) body.sortOrder = patch.sortOrder
  return request({
    url: '/api/admin/dict/items/' + encodeURIComponent(String(id)),
    method: 'PUT',
    data: body
  })
}

/**
 * 删除词条：DELETE /api/admin/dict/items/{id}
 * id 不存在 404；dish_category 组有引用保护——词条 definition 形如 code:meat，取 code: 之后的部分
 * 查 dish.category 引用数，>0 时 400「该分类下还有 N 个商品，删除后它们会变成无主分类」
 * （页面必须原样透出这句）；其余组直接删。
 * 成功 message「词条已删除」，且服务端自动 evict 该组缓存。写档限流 IP 10/60s。
 * @param {number|string} id dict_item.id
 */
export function deleteAdminDictItem(id) {
  return request({
    url: '/api/admin/dict/items/' + encodeURIComponent(String(id)),
    method: 'DELETE'
  })
}

/**
 * 部分更新分组：PUT /api/admin/dict/groups/{code}，body {name?, enabled?}
 * null/缺键 = 不改；name 空白串 400；enabled 仅 0（停用）| 1（启用），其余 400。
 * code 不存在 404（与读/建端点的 400「字典分组不存在」不同，契约钉死）。
 * 成功 message「分组已更新」，且服务端自动 evict(code) 缓存；停用后 C 端 GET /api/dict 按空返回。
 * 写档限流 IP 10/60s。
 * @param {string} code dict_group.code
 * @param {{name?:string, enabled?:number}} patch
 */
export function updateAdminDictGroup(code, patch) {
  const body = {}
  if (patch.name !== undefined && patch.name !== null) body.name = patch.name
  if (patch.enabled !== undefined && patch.enabled !== null) body.enabled = patch.enabled
  return request({
    url: '/api/admin/dict/groups/' + encodeURIComponent(String(code)),
    method: 'PUT',
    data: body
  })
}

/**
 * 手工清除某分组缓存：POST /api/admin/dict/{group}/evict（§0.4 既有端点）。
 * 只清 Caffeine、不强制重拉上游；下一次 GET /api/dict?group= 会重新读本地 dict_item。
 * 组不存在 → 400「字典分组不存在」。成功 message「字典缓存已清除」。写档限流 IP 10/60s。
 * @param {string} group dict_group.code
 */
export function evictAdminDict(group) {
  return request({
    url: '/api/admin/dict/' + encodeURIComponent(String(group)) + '/evict',
    method: 'POST'
  })
}

/**
 * 从上游同步某分组词条：POST /api/admin/dict/{group}/sync（F-108 §0.8，B 语义「只增不删」）。
 * 组不存在 400「字典分组不存在」；组 enabled=0 → 400「分组已停用，不同步」；
 * 上游失败原样 400「字典服务暂时不可用，请稍后重试」（不会静默当成功）。
 * 成功时只把本地没有的词条 INSERT IGNORE 进来，已有行（含运营改过的）一行不动：
 * 上游改名 = 新名插入 + 旧名保留，需要运营手工清理旧名。
 * 出参 data：{upstreamTotal, inserted, skipped}（三个数字都是服务端真值）。
 * 写档限流 IP 10/60s。
 * @param {string} group dict_group.code
 * @returns {Promise<{code:number,message:string,data:{upstreamTotal:number,inserted:number,skipped:number}}>}
 */
export function syncAdminDictGroup(group) {
  return request({
    url: '/api/admin/dict/' + encodeURIComponent(String(group)) + '/sync',
    method: 'POST'
  })
}

// ==================== F-109 免费领活动管理（活动配置 + 候选池，四个端点） ====================
// 契约真源：docs/上线任务文档.md「F-109」§0.2/§0.3。四个端点全在 AdminController 的 Admin 面
// （checkKeyOrOperator 双轨；浏览器端只走运营 JWT 这一轨——request() 在 admin_token 存在时自动挂 Bearer）。
// 读端点限流 IP 30/60s、写端点 IP 10/60s。
// ⚠ dailyClaimed / dailyDate 是领取链路的运行时计数器：更新 DTO 里根本没有这两个字段，
//   本层也刻意不提供任何「传了会生效」的函数签名——活动更新只允许 dishId/dailyQuota/threshold/status。
// 选品用的商品列表走既有的 getAdminDishes（GET /api/admin/v2/dishes，JWT-only 面），本节不另造商品端点。

/**
 * 免费领活动 + 候选池（运营页活动卡数据源）：GET /api/admin/free/activity
 * 出参恰九键：{id,dishId,dishName,dailyQuota,dailyClaimed,dailyDate,threshold,status,
 * pool:[{id,dishId,dishName,onSale}]}。
 * dailyClaimed 是当日已领份数（运行时计数，本层与页面都只读展示、绝不回写）；
 * dailyQuota=0 等效暂停；status ∈ online|offline；
 * pool 行含已下架商品（onSale=0）——管理端要看见池内失效项，页面不许把它静默滤掉。
 * 只读档限流 IP 30/60s。
 */
export function getAdminFreeActivity() {
  return request({
    url: '/api/admin/free/activity',
    method: 'GET'
  })
}

/**
 * 更新免费领活动（部分更新）：PUT /api/admin/free/activity，body {dishId?, dailyQuota?, threshold?, status?}
 * **只允许改这四列**；daily_claimed / daily_date 传了也不会生效（本层不接受这两个键，DTO 也没有对应字段）。
 * dishId 须存在且 on_sale=1，否则 400「免费领商品不存在或已下架」；
 * dailyQuota ≥ 0（0=暂停）、threshold ≥ 0、status ∈ online|offline，越界/非法值由服务端 400。
 * 四列全空 → 400「无更新字段（可传 dishId / dailyQuota / threshold / status）」。
 * 成功 message「免费领活动已更新」。写档限流 IP 10/60s。
 * @param {{dishId?:number, dailyQuota?:number, threshold?:number, status?:string}} patch
 *        未传/null 的键不下发（服务端按「本次不改该列」处理）
 */
export function updateAdminFreeActivity(patch) {
  const p = patch || {}
  const body = {}
  if (p.dishId !== undefined && p.dishId !== null) body.dishId = p.dishId
  if (p.dailyQuota !== undefined && p.dailyQuota !== null) body.dailyQuota = p.dailyQuota
  if (p.threshold !== undefined && p.threshold !== null) body.threshold = p.threshold
  if (p.status !== undefined && p.status !== null) body.status = p.status
  return request({
    url: '/api/admin/free/activity',
    method: 'PUT',
    data: body
  })
}

/**
 * 免费领候选池：GET /api/admin/free/pool
 * 出参 data 是数组：[{id,dishId,dishName,onSale}]，按池行 id 升序（=插入序，第一行即 C 端默认免费商品）。
 * onSale ∈ {0,1}，池里可能留有已下架商品（onSale=0）——管理端要看见这里，不能静默隐藏。
 * 只读档限流 IP 30/60s。
 */
export function getAdminFreePool() {
  return request({
    url: '/api/admin/free/pool',
    method: 'GET'
  })
}

/**
 * 全量替换免费领候选池：PUT /api/admin/free/pool，body {dishIds:[...]}
 * 同事务删旧插新（旧池行一条不留）；空数组 → 400「候选池至少保留一件商品」；
 * 任一商品不存在/已下架 → 400「候选商品不存在或已下架」（整单拒绝，不静默剔除）。
 * 服务端按入参顺序去重插入（=展示顺序）。成功 message「候选池已更新」。写档限流 IP 10/60s。
 * @param {Array<number>} dishIds 至少一件、每件须在售；页面只传勾选的 onSale 商品
 */
export function updateAdminFreePool(dishIds) {
  return request({
    url: '/api/admin/free/pool',
    method: 'PUT',
    data: { dishIds: Array.isArray(dishIds) ? dishIds : [] }
  })
}

// ==================== F-110 搜索别名维护（dish_alias 四个端点） ====================
// 契约真源：docs/上线任务文档.md「F-110」§0.2/§0.3。四个端点全在 AdminController 的 Admin 面
// （checkKeyOrOperator 双轨；浏览器端只走运营 JWT 这一轨——request() 在 admin_token 存在时自动挂 Bearer）。
// 读端点限流 IP 30/60s、写端点 IP 10/60s。
// 语义边界：别名只影响 C 端搜索命中（dish_alias.alias 精确等值 → dish_id），不碰价格/库存/订单；
// 搜索 SQL（DishMapper）本片零改动。dishId 只要求商品存在，**不要求在售**（下架件持有别名是合法状态）。
// 商品下拉走既有的 getAdminDishes（GET /api/admin/v2/dishes，JWT-only 面），本节不另造商品端点。

/**
 * 搜索别名全量列表：GET /api/admin/aliases
 * 出参 data 是数组：[{id,alias,dishId,dishName,onSale}]，按 alias 升序。
 * onSale ∈ {0,1}，下架商品同样列出（onSale=0）——管理端要看见失效项，不许静默滤掉。
 * 只读档限流 IP 30/60s。
 */
export function getAdminAliases() {
  return request({
    url: '/api/admin/aliases',
    method: 'GET'
  })
}

/**
 * 新增搜索别名：POST /api/admin/aliases，body {alias, dishId}
 * alias 服务端 trim 后非空且 ≤50；dishId 必须存在（不要求在售）；
 * uk_alias 冲突 → 400「该别名已存在」。成功 message「别名已新增」。写档限流 IP 10/60s。
 * @param {{alias:string, dishId:number}} payload
 */
export function createAdminAlias(payload) {
  return request({
    url: '/api/admin/aliases',
    method: 'POST',
    data: { alias: payload.alias, dishId: payload.dishId }
  })
}

/**
 * 部分更新搜索别名：PUT /api/admin/aliases/{id}，body {alias?, dishId?}
 * null/缺键 = 本次不改该列；两列全空 400；alias 传空白串 400；id 不存在 404；
 * 改后撞 uk_alias → 400「该别名已存在」。成功 message「别名已更新」。写档限流 IP 10/60s。
 * @param {number|string} id dish_alias.id（自增整数）
 * @param {{alias?:string, dishId?:number}} patch 未传/null 的键不下发
 */
export function updateAdminAlias(id, patch) {
  const p = patch || {}
  const body = {}
  if (p.alias !== undefined && p.alias !== null) body.alias = p.alias
  if (p.dishId !== undefined && p.dishId !== null) body.dishId = p.dishId
  return request({
    url: '/api/admin/aliases/' + encodeURIComponent(String(id)),
    method: 'PUT',
    data: body
  })
}

/**
 * 删除搜索别名：DELETE /api/admin/aliases/{id}
 * id 不存在 404。成功 message「别名已删除」；删除后 C 端用该别名搜索不再命中。
 * 写档限流 IP 10/60s。
 * @param {number|string} id dish_alias.id
 */
export function deleteAdminAlias(id) {
  return request({
    url: '/api/admin/aliases/' + encodeURIComponent(String(id)),
    method: 'DELETE'
  })
}

// ==================== F-112 秒杀商品集管理（两个端点，只写 dish.seckill / seckill_price） ====================
// 契约真源：docs/F-112-秒杀商品集管理契约.md §1。两个端点全在 AdminController 的 Admin 面
// （checkKeyOrOperator 双轨；浏览器端只走运营 JWT 这一轨——request() 在 admin_token 存在时自动挂 Bearer）。
// 读端点限流 IP 30/60s、写端点 IP 10/60s。
// 语义边界：只动两列——进秒杀（seckill=1+设价）/ 调价（seckill=1+新价）/ 移出（seckill=0，
// seckill_price 不回清，历史价保留）；不碰价格/库存/在售列。校验顺序与 400/404 文案以服务端为准。

/**
 * 全量 dish 的秒杀视角列表：GET /api/admin/seckill/dishes
 * 出参 data 是数组：[{id,name,price,onSale,stock,soldCount,seckill,seckillPrice,limitBuy,merchantId}]，
 * 按 id 升序。秒杀中/未进的筛选由页面 tab 做（契约钉死端点不做 filter 参数）；
 * 下架品同样列出（onSale=0，预先配秒杀是合法状态，页面照实显示「已下架」不静默滤掉）。
 * 只读档限流 IP 30/60s。
 */
export function getAdminSeckillDishes() {
  return request({
    url: '/api/admin/seckill/dishes',
    method: 'GET'
  })
}

/**
 * 更新秒杀参与态：PUT /api/admin/seckill/dishes/{id}，body {seckill: 0|1, seckillPrice?: number}
 * 校验顺序即报错优先级（服务端逐条）：dish 不存在 404「商品不存在」；seckill 非 0/1 400；
 * seckill=1 时价必填/正数/最多两位小数 → 400「秒杀价必须为正数（最多两位小数）」；
 * 价 ≥ 库内现价 → 400「秒杀价必须低于原价 X.XX」。seckill=0 时 seckillPrice 不下发也不生效（不回清）。
 * 成功 message「秒杀商品已更新」。写档限流 IP 10/60s。
 * @param {number|string} id dish.id（自增小整数）
 * @param {{seckill:number, seckillPrice?:number}} body seckill=1 时必须带价
 */
export function updateAdminSeckillDish(id, body) {
  const payload = { seckill: body.seckill }
  if (body.seckill === 1 && body.seckillPrice !== undefined && body.seckillPrice !== null) {
    payload.seckillPrice = body.seckillPrice
  }
  return request({
    url: '/api/admin/seckill/dishes/' + encodeURIComponent(String(id)),
    method: 'PUT',
    data: payload
  })
}

// ==================== F-113 券模板管理（三个端点，只写 coupon_template 一张表） ====================
// 契约真源：docs/F-113-券模板表化契约.md §4。三个端点全在 AdminController 的 Admin 面
// （checkKeyOrOperator 双轨；浏览器端只走运营 JWT 这一轨——request() 在 admin_token 存在时自动挂 Bearer）。
// 读端点限流 IP 30/60s、写端点 IP 10/60s（照 F-110 档位）。
// 语义边界：无 DELETE（已发过的模板删除=历史 user_coupon.coupon_key 悬挂，停用即运营语义）；
// coupon_key/issued_count 不可改（PUT body 带了忽略并在响应 message 注明）。
// C 端零改动：claim/center 读同一张表，运营改配置后 C 端下一次请求即生效（无缓存）。

/**
 * 券模板全量列表：GET /api/admin/coupon-templates
 * 出参 data 是数组：[{id,couponKey,title,threshold,amount,validDays,limitPerUser,totalQuota,
 * issuedCount,status}]，按 id 升序；disabled 同样列出。只读档限流 IP 30/60s。
 */
export function getAdminCouponTemplates() {
  return request({
    url: '/api/admin/coupon-templates',
    method: 'GET'
  })
}

/**
 * 上新券模板：POST /api/admin/coupon-templates，body 全量必填字段见 @param。
 * 校验（服务端为准，报错顺序逐条）：key ^[a-z0-9_]{2,50}$ 且不与现存冲突（400「key 已存在」）；
 * title ≤50；threshold/amount >0 两位小数且 amount < threshold（负价单拦截）；
 * validDays 1~365；limitPerUser ≥0；totalQuota 不传=不限（传则 ≥0）；status ∈ {active,disabled}。
 * 成功 message「券模板已新增」。写档限流 IP 10/60s。
 * @param {{couponKey:string, title?:string|null, threshold:number, amount:number, validDays:number,
 *   limitPerUser:number, totalQuota?:number|null, status:string}} body
 */
export function createAdminCouponTemplate(body) {
  return request({
    url: '/api/admin/coupon-templates',
    method: 'POST',
    data: body
  })
}

/**
 * 部分更新券模板：PUT /api/admin/coupon-templates/{id}，body 七列 null/缺键=本次不改。
 * 校验同 POST 但对合并后的新值判（amount<threshold 以合并后门槛为参照）；id 不存在 404。
 * couponKey/issuedCount 传了也不落库，服务端在响应 message 注明已忽略。
 * 成功 message「券模板已更新」。写档限流 IP 10/60s。
 * @param {number|string} id coupon_template.id
 * @param {{title?:string|null, threshold?:number, amount?:number, validDays?:number,
 *   limitPerUser?:number, totalQuota?:number, status?:string}} body 只带要改的列
 */
export function updateAdminCouponTemplate(id, body) {
  return request({
    url: '/api/admin/coupon-templates/' + encodeURIComponent(String(id)),
    method: 'PUT',
    data: body
  })
}

// ==================== F-114 自提点存量管理（两个端点，只写 store 表白名单八列） ====================
// 契约真源：docs/F-114-自提点存量管理契约.md §1。两个端点全在 AdminController 的 Admin 面
// （checkKeyOrOperator 双轨；浏览器端只走运营 JWT 这一轨——request() 在 admin_token 存在时自动挂 Bearer）。
// 读端点限流 IP 30/60s、写端点 IP 10/60s（照 F-110 档位）。
// 语义边界：无新建无删除（门店的创建只经入驻审核 approve，删除无运营语义）；
// owner_user_id/contact_phone/photos/lng/lat 不可改（PUT body 带了忽略并在响应 message 注明）。
// 联动：改 status 后 C 端该店即刻置灰/可选（零缓存）；改 daily_capacity 真实生效到下单拒单（F-111 拦截读列）。

/**
 * 自提点全量列表：GET /api/admin/stores
 * 出参 data 是数组：[{id,name,address,service,status,openTime,closeTime,dailyCapacity,accepting,
 * ownerUserId,createdAt}]，按 id 升序；closed 同样列出。
 * 不含 contact_phone/photos/lng/lat（运营页用不上，少下发）。只读档限流 IP 30/60s。
 */
export function getAdminStores() {
  return request({
    url: '/api/admin/stores',
    method: 'GET'
  })
}

/**
 * 部分更新自提点：PUT /api/admin/stores/{id}，body 白名单八列 null/缺键=本次不改。
 * 校验（服务端为准）：name trim 后 1~100；address trim 后 1~255；service ≤50；
 * status ∈ {open,closed}；openTime/closeTime 匹配 HH:MM 或不传；dailyCapacity ≥0（0=不限容）；
 * accepting ∈ {0,1}；id 不存在 404「门店不存在」。
 * ownerUserId/contactPhone/photos/lng/lat 传了也不落库，服务端在响应 message 注明已忽略。
 * 成功 message「门店已更新」。写档限流 IP 10/60s。
 * @param {number|string} id store.id
 * @param {{name?:string, address?:string, service?:string|null, status?:string,
 *   openTime?:string, closeTime?:string, dailyCapacity?:number, accepting?:0|1}} body 只带要改的列
 */
export function updateAdminStore(id, body) {
  return request({
    url: '/api/admin/stores/' + encodeURIComponent(String(id)),
    method: 'PUT',
    data: body
  })
}

// ==================== F-118 结算跑批 + 结算中心（三个端点，只增不改） ====================
// 契约真源：docs/F-118-结算跑批与结算中心契约.md §1/§2。三个端点全在 AdminController 的 Admin 面
// （checkKeyOrOperator 双轨；浏览器端只走运营 JWT 这一轨——request() 在 admin_token 存在时自动挂 Bearer）。
// 读端点限流 IP 30/60s、写端点 IP 10/60s（照 F-110/F-113 档位）。
// 语义边界（契约 §3 红线）：settlement 只增不改——本节无任何 UPDATE/DELETE 函数；冲销走下一期 refund
// 列承载（06 册 §4.5 定稿）；「打款通道未接」是页面恒显语义（paidChannelAbsent 由接口层带出防漏显）。
// ⚠ 商家下拉数据源：契约 §1 只授权三端点、未给商家清单端点——选项来自列表返回的 merchantId/merchantName
//   去重 + allow-create 手输 merchantId（首次对某商家出账直接输入其 id），本层不为此新造请求。

/**
 * 账期列表：GET /api/admin/settlements?merchantId=
 * 出参 data 是数组，行内键序（F-118 契约 §1.2 逐字十四键，F-119 §3 在 net 后追加 commission 成十五键）：
 * id,settleNo,merchantId,merchantName,periodStart,periodEnd,gross,refund,retain,net,commission,
 * itemCount,status,reconcileOk,paidChannelAbsent。
 * reconcileOk 是实时对账标志（金额+计数双对账，false=数据事故，页面必须标红不得静默）；
 * paidChannelAbsent 恒 true（记账态明示）；merchantId 可空=全量。只读档限流 IP 30/60s。
 * @param {number|string} [merchantId] 空值由 withQuery 挡下不下发（服务端把缺省当「全量」）
 */
export function getAdminSettlements(merchantId) {
  return request({
    url: withQuery('/api/admin/settlements', { merchantId }),
    method: 'GET'
  })
}

/**
 * 生成账期（写）：POST /api/admin/settlements/generate，body {merchantId, periodStart, periodEnd}。
 * 服务端判定（契约 §1.1）：merchant 不存在 404「商家不存在」；start≥end 400；
 * 同期重跑 400「该商家此账期已生成」；无可结算明细 400「该商家本期无可结算明细」（不生成空账单）；
 * 并发跑批落败方 400（整单回滚）。成功 message「账期已生成」，data 为生成单概要
 * {id, settleNo, merchantId, merchantName, periodStart, periodEnd, gross, refund, net, itemCount, orderCount, status}。
 * 聚合口径（已支付·非缺货·已设供货价）由页面二次确认文案说明；写档限流 IP 10/60s。
 * ⚠ 写成功与否由页面重拉列表核对，绝不本地改行（纪律 3）。
 * @param {{merchantId:number, periodStart:string, periodEnd:string}} body 日期 YYYY-MM-DD
 */
export function generateAdminSettlement(body) {
  return request({
    url: '/api/admin/settlements/generate',
    method: 'POST',
    data: body
  })
}

/**
 * 账单明细（只读）：GET /api/admin/settlements/{id}/items——明细弹窗数据源。
 * 出参 data 是数组，行内键：itemId,dishName,supplyPrice,qty,subtotal,orderId,payStatus,fulfillStatus,
 * orphan（2026-10-25 验收裁决：payStatus 经 LEFT JOIN orders 取，orders 行被删的孤儿行不再静默丢行——
 * payStatus=null、orphan=true，页面标「订单已不存在」警示；正常行 orphan=false）。
 * dish_name/supply_price/qty/fulfill_status 全部是 order_item 下单快照（不 JOIN dish）。id 不存在 404。
 * 只读档限流 IP 30/60s。
 * @param {number|string} id settlement.id（自增小整数）
 */
export function getAdminSettlementItems(id) {
  return request({
    url: '/api/admin/settlements/' + encodeURIComponent(String(id)) + '/items',
    method: 'GET'
  })
}

// ==================== F-119 主理人佣金钱包（只读一端点；无任何钱包写端点） ====================
// 契约真源：docs/F-119-主理人佣金记账契约.md §3。写路径只有生成账期跑批（AdminServiceImpl#generateSettlement
// 挂锚后的追加步），人工调额=资损面不设入口；本层只提供只读函数，与本节红线一致。
// 主理人端自查页走 front-partner 的 GET /api/partner/wallet（本人归属），不在本文件（两端身份隔离）。

/**
 * 主理人钱包列表（只读）：GET /api/admin/partner-wallets（F-119 §3，IP 30/60s）
 * 出参 data 是数组，行内键（PartnerWalletService#listWallets 契约逐字）：
 * userId, userPhone（PhoneMask 脱敏；user 行已删的悬挂钱包显 null）, storeNames（名下店名数组，
 * 一主理人可能多店）, balance（记账态余额，提现属轨道 B；冲回余额不足可为负——如实展示）, flowCount。
 * 无任何写操作；与结算中心（Settlements.vue）同一条运营侧只读线。
 */
export function getAdminPartnerWallets() {
  return request({
    url: '/api/admin/partner-wallets',
    method: 'GET'
  })
}

// ==================== F-121 限流观测（只读一端点；无任何写操作） ====================
// 契约真源：docs/F-121-限流观测契约.md §2/§3。数据源是拦截器命中旁路写的 rate_limit_hit（F-121 §2），
// 命中即异常流量信号；本面纯只读，封禁操作见「封禁管理」页。

/**
 * 限流命中聚合（只读）：GET /api/admin/rate-limit/hits?days=（F-121 §2，IP 30/60s）
 * days ∈ {1,7,30} 三档白名单（缺省 7，白名单外 400）。
 * 出参 data：{ days, total, list: [{route, bucketKey, count, lastHitAt}] }——list 按 count 倒序 top 100，
 * total 为该时间档内命中总次数（全量计数，不受 top-100 截断影响）。
 */
export function getAdminRateLimitHits(days) {
  // 页面不自拼 URL：days 一律经本层 withQuery 收口（同 getAdminAuditLogs 的筛选值姿势）
  return request({
    url: withQuery('/api/admin/rate-limit/hits', { days }),
    method: 'GET'
  })
}

// ==================== F-122 封禁体系（用户/商家两个域 × 列表 + 状态变更，共四端点） ====================
// 契约真源：docs/F-122-封禁体系契约.md §3.4。四端点全在 AdminController 的 Admin 面
// （checkKeyOrOperator 双轨；浏览器端只走运营 JWT 这一轨——request() 在 admin_token 存在时自动挂 Bearer）。
// 读端点限流 IP 30/60s、写端点 IP 10/60s。
// 语义边界（契约 §1.2 红线，本层逐条不越界）：写只落 user.status / merchant.status 一列，**零级联**；
// 封禁商家不下架商品、不清秒杀、不移出免费领池；在途订单仍归「订单管理」逐单处理。
// user 封禁后旧 token 立即失效靠后端拦截器实时判库（本层无任何 token 操作）；
// merchant 封禁复用既有 suspended（D-9/BE-5 闸门已覆盖商家端写与履约），terminated 不可经这两个端点变更。
// 列表出参统一 {list,total,pageNum,pageSize,filters}；phone/contactPhone 已在服务端过 PhoneMask，
// 本层与页面都不许拼回明文；createdAt 是服务端定形串，页面原样显示、不做二次格式化。

/**
 * 用户列表（运营端）：GET /api/admin/users?userId=&phone=&status=&pageNum=&pageSize=
 * 行内恰六键 {id,phone,role,merchantId,status,createdAt}，按 id 倒序（契约 §3.4.1）。
 * status 白名单 {active,banned}（服务端 Locale.ROOT 归一小写后判定，空=全部）；
 * userId 非空须 >0、phone 精确等值（trim 后，不做 LIKE）；分页 pageNum≥1、pageSize∈[1,100]、
 * 越界一律 400 不夹取；offset 越界短路回 {list:[]}。
 * 失败按请求层 toast；判定与「下一步」文案由页面呈现。
 * @param {{userId?:number|string, phone?:string, status?:string, pageNum?:number, pageSize?:number}} [params]
 *        空值一律由 withQuery 挡下、不下发（服务端把缺省当「不筛」）
 */
export function getAdminUsers(params) {
  const p = params || {}
  return request({
    url: withQuery('/api/admin/users', {
      userId: p.userId,
      phone: p.phone,
      status: p.status,
      pageNum: p.pageNum,
      pageSize: p.pageSize
    }),
    method: 'GET'
  })
}

/**
 * 用户封禁/解封：PUT /api/admin/users/{id}/status，body {action}（ban/unban，其余 400「action 仅允许 ban/unban」）。
 * 用户不存在 404「用户不存在」；幂等 no-op（已 banned 再 ban / 已 active 再 unban）200 且不写审计，
 * message 分别是「用户已处于封禁状态」「用户已处于正常状态」；实际变更 message「用户已封禁」/「用户已解封」。
 * 只改 status 一列，不级联 merchant/store/settlement/order，也不动其 token（拦截器实时判库失效）。
 * 写档限流 IP 10/60s。成功与否由页面重拉当前 tab 核对，绝不本地改行。
 * @param {number|string} id user.id
 * @param {'ban'|'unban'} action
 */
export function updateAdminUserStatus(id, action) {
  return request({
    url: '/api/admin/users/' + encodeURIComponent(String(id)) + '/status',
    method: 'PUT',
    data: { action }
  })
}

/**
 * 商家列表（运营端）：GET /api/admin/merchants?merchantId=&status=&pageNum=&pageSize=
 * 行内恰六键 {id,name,contactPhone,contactName,status,createdAt}，按 id 升序（契约 §3.4.2）。
 * status 白名单 {active,suspended,terminated}（空=全部）；merchantId 非空须 >0；分页规则同用户列表。
 * 注意与写端点 POST /api/admin/merchants（平台代开通，F-12）同路径不同方法，本函数只发 GET。
 * @param {{merchantId?:number|string, status?:string, pageNum?:number, pageSize?:number}} [params]
 *        空值一律由 withQuery 挡下、不下发
 */
export function getAdminMerchants(params) {
  const p = params || {}
  return request({
    url: withQuery('/api/admin/merchants', {
      merchantId: p.merchantId,
      status: p.status,
      pageNum: p.pageNum,
      pageSize: p.pageSize
    }),
    method: 'GET'
  })
}

/**
 * 商家封禁/解封：PUT /api/admin/merchants/{id}/status，body {action}（ban/unban，其余 400「action 仅允许 ban/unban」）。
 * ban：active→suspended；unban：suspended→active；terminated/脏值 → 400「该商家已清退，不可变更封禁状态」；
 * 商家不存在 404「商家不存在」；幂等 no-op 200 不写审计（message「商家已处于封禁状态」/「商家已处于正常营业状态」）；
 * 实际变更 message「商家已封禁」/「商家已解封」。
 * 只写 merchant.status 一列（零级联）；封禁期间 C 端商品照常展示、仅下单被拒，商家端写与看板/营销接口 403
 * （备货计划/供货明细按既有 D-9 口径仍可读）。
 * 写档限流 IP 10/60s。成功与否由页面重拉当前 tab 核对，绝不本地改行。
 * @param {number|string} id merchant.id
 * @param {'ban'|'unban'} action
 */
export function updateAdminMerchantStatus(id, action) {
  return request({
    url: '/api/admin/merchants/' + encodeURIComponent(String(id)) + '/status',
    method: 'PUT',
    data: { action }
  })
}

