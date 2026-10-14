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
 *   而另 5 页（Dishes/Orders/Reviews/StoreApplies/AuditLogs）的 CODE_HINTS[429] 与 WRITE_CODE_HINTS[429]
 *   共 9 处拼接点**句子自带结尾句号**，补任何标点都会在那 9 处拼出「。。」「。；」双标点；
 *   空格对「有句号的前句」和「没句号的前句」同时成立，且不与后端文案的标点形状发生关系。
 *   反向禁例（同一条理由）：也不许在页面里判「前一句末字符是不是标点」再决定补不补——
 *   那会把前端呈现拴在后端文案上，R.java 改一次标点、六页就变一次形。
 * 与下面 waitDurationText 的分工（别混）：本函数转述的是**后端整句文案里的秒数**，不换算单位、不取整；
 * waitDurationText 只管登录页锁定倒计时的显示写法。两者互不复用。
 * @param {unknown} data reject 载荷里的 e.data（可为 null / undefined / 非对象）
 * @returns {string} 有如实读数时是「 服务端约 N 秒后可再试。」（含前导分隔空格），否则是 ''
 */
// X-6 的前导分隔：单枚空格、非标点，理由见上；要改成别的分隔符必须连同那 9 处自带句号的拼接点一起看。
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
 *   <60 秒 → 「N 秒」，与换算前逐字同形（本页 <60 的屏上文案一字不变）；
 *   ≥60 秒 → 「约 M 分钟」，M = Math.ceil(seconds / 60)。
 * 取整方向选**向上**（ceil）而不是向下，理由：这是「还要等多久才可以再试」，
 *   floor 会把 90 秒说成「约 1 分钟」，运营按 1 分钟去重试只会再吃一次拒（低估等待＝误导）；
 *   向上最多让读数比真值大一格，且「约」字已如实标明这是近似值；方向也与本仓另两端的
 *   retrySecondsOf（front-merchant / front-partner 的 api 层，同为「剩余秒数」换算）的 Math.ceil 一致。
 *   三个钉死的读数：60 →「约 1 分钟」、90 →「约 2 分钟」、600 →「约 10 分钟」（后者正是
 *   AdminAuthServiceImpl:112 的 LOGIN_FAIL_LOCK_TTL = 10 分钟刚上锁时的剩余秒数）。
 * 非有限数 / ≤0 → 回 ''：调用方（Login.vue 的 locked 闸门）此时本就不显示这一行，
 *   不拿它造一句「约 0 分钟后再试」。
 * ⚠ 红线（本条契约的原话是「归因文案不许动」）：本函数只碰倒计时那一行的**时长单位**，
 *   绝不碰同屏那句后端原文（「用户名或密码错误」）。三种失败在服务端本就是同一句（D-5 防枚举），
 *   页面不许把它改写成「已连续输错 5 次」这类暴露锁定条件的说法。
 * @param {unknown} seconds 后端给的剩余秒数（可为字符串数字 / null / 非数）
 * @returns {string} 「N 秒」/「大约 M 分钟」/ ''
 */
export function waitDurationText(seconds) {
  const n = Number(seconds)
  if (!Number.isFinite(n) || n <= 0) return ''
  if (n < 60) return n + ' 秒'
  return '大约 ' + Math.ceil(n / 60) + ' 分钟'
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
 * 运营端订单列表（核销台数据源）：GET /api/admin/v2/orders
 * 行内键（AdminV2ServiceImpl:280-292 逐键重拼的白名单，AdminV2Controller 的 javadoc 同一份）：
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
      category: f.category
    }),
    method: 'GET'
  })
}

/**
 * 运营端评价队列（评价审核页数据源）：GET /api/admin/v2/reviews
 * 行内键：id,dishId,rating,content,reviewer,auditStatus,createTime。
 * ⚠ **没有 dishName**（AdminServiceImpl 侧不 JOIN dish，ReviewMapper 的注释点名「菜品名由同批的 dishes 列表
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
 * 订单核销/取消：PUT /api/admin/orders/{id}/status，body {status}
 * @param {string} id 订单 id（**服务端回的原样字符串**，19 位雪花，绝不 Number()）
 * @param {string} status 'completed'（核销）| 'cancelled'（取消）；其余值服务端 400
 *        「status 仅允许 completed（核销）或 cancelled（取消）」；查无此单 404「订单不存在」；
 *        非 pending_pickup 的单 400「当前状态不可变更（仅待自提订单可核销/取消）」
 * @returns {Promise<{code:number,message:string,data:null}>} 成功 message「订单状态已更新」
 */
export function updateAdminOrderStatus(id, status) {
  return request({
    url: '/api/admin/orders/' + encodeURIComponent(String(id)) + '/status',
    method: 'PUT',
    data: { status }
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
