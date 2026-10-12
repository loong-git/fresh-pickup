// ==================== API 请求封装（主理人端唯一请求层） ====================
// 骨架由 front-merchant/api/index.js 拷入（F-18.15 C-1 / C-4），改造点只有三类，且刻意保持最小：
// ① 登录态：token 读写走本端独立 storage 键 partner_token、登录身份走 partner_login_phone
//    （C-4：两端 token 不能串台——三端同域调试时 localStorage 按 origin 隔离并不绝对，
//    5173/5174 各自独立，但一旦走同源部署或换端口顺延，键名不同才是可靠的隔离）；
// ② 401 与 403 严格分流：handleEnvelope / rejectUnauthorized / goLoginPage / request 四个函数
//    与商家端**逐字同语义**（401 清态跳登录、403 只 reject 把 message 透给页面、绝不踢登录页）。
//    这是「请求层收敛、不复制分叉债」的正面执行：07 册 G-04 风险行点名禁止绕统一 api 层，
//    三端各留一份语义不同的请求层就是那笔债的起点，故本轮不在此处分叉；
// ③ 分层纪律：本层是 front-partner 内唯一的原生请求发起点，接口一律以具名导出收敛，页面零自拼 BASE_URL。
// 商家端专属封装（6 个 dish 端点 / uploadMerchantImage / getMerchantMe / updateMerchantProfile /
// 备货单两条 / 商品图 URL 归一）按 §3 全部删除，未交付的通道不留死代码。
import { BASE_URL } from './config.js'

// 分层超时（照用户端与商家端纪律，三档语义一字不改）：查询类 10s；提交类 20s；上传 30s
// TIMEOUT_UPLOAD 至今没有调用方，如实登记（FE-4）：上传通道一期不做——门店照片是只读展示（D-18 无上传端点，
// 见 store.vue 的 photo-card），入驻页的凭证照片同样「没有上传口」（apply.vue:564），文件服务随 G-05 才开放。
// 保留它而不是删掉再抄回来：这是 §3「三个超时档逐字保留」的要求；G-05 落地时走 uni.uploadFile + 同一个 handleEnvelope。
const TIMEOUT_QUERY = 10000
const TIMEOUT_SUBMIT = 20000
const TIMEOUT_UPLOAD = 30000

// 登录态 storage 键：main.js 的 setToken/clearToken 与本层共用这一个常量（唯一实现处，防两处漂移）
export const TOKEN_STORAGE_KEY = 'partner_token'

// 登录页路由（401 兜底跳转目标，须与 pages.json 首页一致）
const LOGIN_PAGE = 'pages/login/login'

// 兜底文案与后端码表同源（R.java:23 MSG_UNAUTHORIZED / R.java:30 MSG_FORBIDDEN），信封 message 缺失时才用
// MSG_UNAUTHORIZED 同时是 401 跳登录页前那一声 toast 的文案：同一串只在这一处定义，
// 页面与请求层不会各写一份「请先登录」而漂移
const MSG_UNAUTHORIZED = '请先登录'
const MSG_FORBIDDEN = '无权限访问'

// ==================== 登录态读写（storage 唯一出口） ====================

export function getStoredToken() {
  try {
    const cached = uni.getStorageSync(TOKEN_STORAGE_KEY)
    return typeof cached === 'string' ? cached : ''
  } catch (e) {
    // 存储异常按未登录处理（读写全程守卫，与用户端/商家端同纪律）
    return ''
  }
}

export function setStoredToken(token) {
  try {
    uni.setStorageSync(TOKEN_STORAGE_KEY, token || '')
  } catch (e) {
    // 落盘失败不影响本次会话（Vuex 内存态仍在）
  }
}

export function clearStoredToken() {
  try {
    uni.removeStorageSync(TOKEN_STORAGE_KEY)
  } catch (e) {
    // 存储异常静默
  }
}

// 登录身份 storage 键：只存手机号（非敏感凭证，仅用于 403 引导文案指代账号），
// 与 partner_token 同写同清（main.js 的 setLoginPhone/clearToken 是唯一调用方，页面不各自读写）
export const LOGIN_PHONE_STORAGE_KEY = 'partner_login_phone'

export function getStoredLoginPhone() {
  try {
    const cached = uni.getStorageSync(LOGIN_PHONE_STORAGE_KEY)
    return typeof cached === 'string' ? cached : ''
  } catch (e) {
    return ''
  }
}

export function setStoredLoginPhone(phone) {
  try {
    uni.setStorageSync(LOGIN_PHONE_STORAGE_KEY, phone || '')
  } catch (e) {
    // 落盘失败只影响 403 文案能否带出账号，不影响本次会话
  }
}

export function clearStoredLoginPhone() {
  try {
    uni.removeStorageSync(LOGIN_PHONE_STORAGE_KEY)
  } catch (e) {
    // 存储异常静默
  }
}

// 401 统一回调注册器：main.js 启动时注册 → 触发时同步清 Vuex 登录态
// （api 层不反向 import store，避免 main.js ↔ api 循环引用，与用户端/商家端同构）
let unauthorizedHandler = null
export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = typeof fn === 'function' ? fn : null
}

// ==================== 文件路径 -> 可展示 URL（拼 BASE_URL 的责任收敛在这里，页面层零自拼） ====================
/**
 * 相对路径 -> 后端可直读 URL：以 '/' 开头的拼 BASE_URL，其余（已是绝对 http(s) URL 等）原样返回；
 * 空值/非字符串回空串。
 * 主理人端唯一保留的 URL 出口（现状读数，FE-4）：当前唯一调用方是 store.vue 的 photoList
 * ——门店照片 photos 的**只读展示**（入库值形如 /static/images/uploads/<名>），页面不得自拼 BASE_URL。
 * 凭证图 proofPhotos 不走这里：它既没有上传端点（D-18 一期不做，见 apply.vue:564 与 TIMEOUT_UPLOAD 的注释），
 * 也不在 GET /api/store-applies/mine 的出参里（最小暴露，见下方 getMyStoreApply），所以本层现在没有它的 URL 出口。
 * 商家端那个 merchantFileUrl() 不拷过来：它处理的是「C 端静态根前缀 <-> 后端 /uploads 映射前缀」的
 * 商品图换前缀问题，主理人端只有门店照片这一处图片需求且实测直读可显示，先落地就是死代码
 * （G-05 文件服务开放后若确实需要换前缀，届时按当时的后端映射实测再决定，不照抄结论）。
 */
export function resolveFileUrl(u) {
  if (typeof u !== 'string' || !u) return ''
  return u.charAt(0) === '/' ? BASE_URL + u : u
}

// 401 兜底跳转：C 端有游客态、401 只清态不跳转；主理人端与商家端一样没有游客态，
// 未登录停在任何页面都是死路，故 401 一律收敛回登录页（页面无需各写一份）。
// 已在登录页时不跳：同页 reLaunch 在 H5 会整页刷新，打断正在输入的表单与刚渲染的引导态。
function goLoginPage() {
  let pages = []
  try {
    pages = getCurrentPages() || []
  } catch (e) {
    pages = []
  }
  // 启动早期页面栈尚未建立：pages.json 首页就是登录页，无需跳转
  if (!pages.length) return
  const current = (pages[pages.length - 1] && pages[pages.length - 1].route) || ''
  if (current === LOGIN_PAGE) return
  // 跳转前先告知原因：reLaunch 会销毁当前页（长表单内容随之全丢），不出一声的话用户只觉得
  // 「页面莫名其妙跳回登录了」。文案复用 MSG_UNAUTHORIZED，不新写第二串
  uni.showToast({ title: MSG_UNAUTHORIZED, icon: 'none' })
  uni.reLaunch({ url: '/' + LOGIN_PAGE })
}

// 401 统一处理：清 partner_token + 通知 store + 回登录页 + 结构化 reject（仅 reject 不重试，无死循环）
function rejectUnauthorized(reject) {
  clearStoredToken()
  if (unauthorizedHandler) {
    try {
      unauthorizedHandler()
    } catch (e) {
      // 回调异常不影响 reject
    }
  }
  goLoginPage()
  reject({ code: 401, message: MSG_UNAUTHORIZED })
}

/**
 * 统一信封分流：本函数是本层唯一的信封判定处，将来若开放上传通道（G-05 文件服务，一期不做）
 * 也必须复用同一个函数，两条通道的 401/403 语义才可能一致（现状：上传通道无调用方，见文件头 TIMEOUT_UPLOAD）
 * 后端 HTTP 状态码恒 200、业务码只写在 body.code（ResponseUtil.java:23-27 拦截器直写信封 +
 * GlobalExceptionHandler 全部 return R），故 statusCode 只在信封不可解析时兜底。
 * - code 200 且信封是对象 → resolve(完整信封 {code,data,message})
 *   （HTTP 200 但 body 不是 JSON 对象的情况——代理层回 HTML 错误页等——一律按业务失败 reject，
 *   绝不 resolve(null)，否则页面会拿到一个「成功但没有门店」的假象）
 * - code 401（无/伪造/过期 token）→ rejectUnauthorized：清 token 并回登录页，message 固定「请先登录」
 * - code 403 → 只结构化 reject，并把后端 message 原样透出给页面渲染引导态；
 *   主理人端的 403 来源是 AuthInterceptor:108-118「token 有效但 store.owner_user_id 名下门店集合为空」，
 *   即「登录成功、还没绑定自提点」，这是登录成功后的业务事实，绝不当登录失效处理——
 *   按 401 踢回登录页会让用户陷在登录死循环里（本轮登录页的 403 分支就靠这一条分流）
 * - 其余（400/404/429/500/网络异常）→ reject({code,message,data})，调用方须 toast，全站无静默分支
 *   （data 原样透传后端信封 body.data：当前唯一使用方=429 锁的 {retryAfterSeconds}，见 PartnerOrderServiceImpl 的 BE-5；
 *     网络异常与信封不可解析时为 null）
 */
function handleEnvelope(res, resolve, reject) {
  const body = (res && res.data && typeof res.data === 'object') ? res.data : null
  const code = (body && body.code != null) ? Number(body.code) : (res ? Number(res.statusCode) : 0)
  const message = (body && typeof body.message === 'string' && body.message) ? body.message : ''
  if (code === 401) {
    rejectUnauthorized(reject)
    return
  }
  if (code === 403) {
    reject({ code: 403, message: message || MSG_FORBIDDEN, data: (body && body.data !== undefined) ? body.data : null })
    return
  }
  if (res && res.statusCode === 200 && body && code === 200) {
    resolve(body)
    return
  }
  reject({
    code: code || -1,
    message: message || '服务开小差了，请稍后重试',
    data: (body && body.data !== undefined) ? body.data : null
  })
}

/**
 * 统一请求封装：有 token 才带 Authorization: Bearer <token>；其余判定全在 handleEnvelope
 */
const request = (options) => {
  const method = (options.method || 'GET').toUpperCase()
  // 非 GET 一律按提交类给 20s（主理人端写侧有 POST 核销/到货确认/入驻申请与 PUT 门店维护）
  const isSubmit = method !== 'GET'
  const header = { 'Content-Type': 'application/json' }
  const token = getStoredToken()
  if (token) header.Authorization = 'Bearer ' + token
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE_URL + options.url,
      method,
      data: options.data || {},
      timeout: isSubmit ? TIMEOUT_SUBMIT : TIMEOUT_QUERY,
      header,
      success: (res) => handleEnvelope(res, resolve, reject),
      fail: () => {
        // 网络不可达 / 域名解析失败 / 分层超时（H5 下超时走 fail）
        reject({ code: -1, message: '网络异常' })
      }
    })
  })
}

// ==================== 登录链路（与用户端/商家端同一套账号体系，不建第二套） ====================

/**
 * 发送短信验证码（匿名可调）：POST /api/auth/send-code
 * dev 环境后端固定验证码 123456（配置项 auth.dev-code），与用户端同一份后端实现
 * @param {string} phone - 1[3-9] 开头 11 位手机号
 */
export function sendCode(phone) {
  return request({
    url: '/api/auth/send-code',
    method: 'POST',
    data: { phone }
  })
}

/**
 * 手机号 + 验证码登录（新手机号后端自动静默注册）：POST /api/auth/login
 * 主理人身份不占 user.role（D-1/A3.1：主理人靠 store.owner_user_id 归属判定），
 * 故本函数只负责拿 token，「能不能进工作台」一律交随后的 getPartnerStores()（F-18.15 §4 三分支）
 * @returns {Promise<{code,data:{token,user:{id,phone},isNew?:boolean,hasPassword?:boolean}}>}
 */
export function login(phone, code) {
  return request({
    url: '/api/auth/login',
    method: 'POST',
    data: { phone, code }
  })
}

/**
 * 手机号 + 密码登录（匿名可调）：POST /api/auth/login-password
 * 与用户端/商家端同一通道；未设过密码时后端 code:400 且 message 含「请先使用验证码登录」，调用方直接 toast 透出
 * @returns {Promise<{code,data:{token,user:{id,phone},hasPassword:boolean}}>}
 */
export function loginPassword(phone, password) {
  return request({
    url: '/api/auth/login-password',
    method: 'POST',
    data: { phone, password }
  })
}

// ==================== 主理人接口（F-18.8 第 4 条 + F-18.12 第 1/2/4/5/6 条） ====================
// 归属纪律（RF 红线）：/api/partner/** 的归属一律由服务端从 token 解析（AuthInterceptor:108-118 注入
// ATTR_PARTNER_STORE_IDS），本层不拼任何归属入参；`storeId` 只是 D-22 的「名下多店选一家」选择器，
// 服务端校验它 ∈ 集合、不在即 404，它既不是归属也不是可改列。
// 每个函数只拼参数、不写业务分支；判定与文案分流全在页面层按 reject 的 code 走。

/**
 * 名下门店列表：GET /api/partner/stores（F-18.8 第 4 条，读 30/分）
 * 出参每行 {id,name,address,status,service}——刻意不含 owner_user_id/contact_phone/photos/
 * daily_capacity/accepting（D-12 最小暴露）；那六列要走 getPartnerStore() 明细才给（F-18.12 第 4 条）。
 * 登录页的身份判定就用它：resolve=200 且数组非空可进工作台；
 * reject 403 = token 有效但名下无店（不是登录失效）；reject 401 由请求层收敛回登录页。
 * @returns {Promise<{code,data:Array<{id:number,name:string,address:string,status:string,service:string}>,message:string}>}
 */
export function getPartnerStores() {
  return request({
    url: '/api/partner/stores',
    method: 'GET'
  })
}

/**
 * 核销清单：GET /api/partner/orders?pickupDate=&status=&page=&size=（F-18.8 第 4 条，读 30/分）
 * ⚠ 日期参数的真名是 pickupDate（PartnerController.java:76 @RequestParam("pickupDate")），
 *   契约 F-18.15 §3 写的 `date` 是那条端点的查询参数名笔误（arrivals 才叫 date）。
 *   本函数按「调用方传 date、下发 pickupDate」收敛，两种键名都收（src.pickupDate 优先），
 *   Step 6 核销台不必再记这个差异。已列为本轮契约疑点。
 * status 白名单 all|pending_pickup|completed，非法值后端 400（与商家侧备货单「未知值兜底 all」口径不同，
 * 见 PartnerController.java:70-71）；cancelled 三个取值下一律排除。page/size 夹而不拒并在 data 回显生效值。
 * @param {{date?:string, pickupDate?:string, status?:string, page?:number, size?:number}} [query] date 形如 YYYY-MM-DD
 * @returns {Promise<{code,data:object,message:string}>}
 */
export function getPartnerOrders(query) {
  const src = query && typeof query === 'object' ? query : {}
  const data = {}
  const pickupDate = src.pickupDate || src.date
  if (pickupDate) data.pickupDate = pickupDate
  if (src.status) data.status = src.status
  if (src.page) data.page = Number(src.page)
  if (src.size) data.size = Number(src.size)
  return request({ url: '/api/partner/orders', method: 'GET', data })
}

/**
 * 核销：POST /api/partner/orders/verify，body {pickupCode}（F-18.8 第 4 条，写 10/分）
 * 码形状 6 位数字；定性码由 Service 给：200 核销成功／200 幂等成功、400 码形状或状态不允许、
 * 404 查无此码或不属于名下门店、429 命中 D-14 防枚举锁（5 次/10 分钟，锁定期内连正确码也拒）。
 * 429 不是「点太快」的普通限流，页面须给冷却态而不是立刻可再试（Step 6 交付时按 reject 的 code 分流）。
 * @param {string} pickupCode
 * @returns {Promise<{code,data:object,message:string}>}
 */
export function verifyPartnerOrder(pickupCode) {
  return request({
    url: '/api/partner/orders/verify',
    method: 'POST',
    data: { pickupCode }
  })
}

/**
 * 门店明细：GET /api/partner/store?storeId=（F-18.12 第 4 条，读 30/分，storeId 可省=D-22 选择器）
 * 出参白名单 Map：id/name/address/service/status/lng/lat + 六个新列
 * openTime/closeTime/dailyCapacity/accepting/contactPhone/photos（PartnerStoreServiceImpl.java:399-411）。
 * contactPhone 只出 PhoneMask.mask 结果（RD 脱敏，前端不再做二次处理也不猜原号）。
 * 缺省取名下 id 最小的一家——多店场景要指定就给 storeId。
 * @param {string|number} [storeId]
 * @returns {Promise<{code,data:object,message:string}>}
 */
export function getPartnerStore(storeId) {
  const data = {}
  // storeId=0 是合法值（后端按 id 匹配，命中不了就 404），只有空串/null/undefined 才当「不带这一选择器」
  if (storeId !== '' && storeId !== null && storeId !== undefined) data.storeId = storeId
  return request({ url: '/api/partner/store', method: 'GET', data })
}

// 门店维护可改七项（F-18.12 第 5 条 / D-7）：与 PartnerStoreUpdateDTO 字段严格一致。
// name/address/lng/lat/status 一律不在名单内——后端会「忽略不生效、不报错」，前端留口子就等于
// 让用户以为改了（比 400 更坏的静默）。storeId 也刻意不在这里：它是选择器不是可改列，见下。
const PARTNER_STORE_EDITABLE_FIELDS = ['openTime', 'closeTime', 'dailyCapacity', 'accepting', 'contactPhone', 'service', 'photos']

/**
 * 门店维护：PUT /api/partner/store?storeId=（F-18.12 第 5 条，写 10/分，部分更新语义）
 * storeId 走查询参数下发：后端 D-22 的优先级是「?storeId= 优先，其次 body 里的 storeId」
 * （PartnerStoreServiceImpl.java:119-121），只留一处传法可避免「两处给了不同店」的二义性写入。
 * body 只发白名单七项，其余键不下发；accepting 用 1/0 不用 boolean（列为 TINYINT，与后端 DTO 同口径）。
 * @param {{storeId?:string|number, openTime?:string, closeTime?:string, dailyCapacity?:number,
 *          accepting?:number, contactPhone?:string, service?:string, photos?:string[]}} fields
 * @returns {Promise<{code,data:object,message:string}>}
 */
export function updatePartnerStore(fields) {
  const src = fields && typeof fields === 'object' ? fields : {}
  const data = {}
  PARTNER_STORE_EDITABLE_FIELDS.forEach((key) => {
    // null/undefined 不下发（部分更新语义里那叫「本次不动这一列」）；空串照发——那是用户主动清空的意图
    if (src[key] !== undefined && src[key] !== null) data[key] = src[key]
  })
  const query = {}
  if (src.storeId !== '' && src.storeId !== null && src.storeId !== undefined) query.storeId = src.storeId
  return request({
    url: '/api/partner/store' + (query.storeId !== undefined ? '?storeId=' + encodeURIComponent(query.storeId) : ''),
    method: 'PUT',
    data
  })
}

/**
 * 到货批次清单：GET /api/partner/arrivals?date=&storeId=（F-18.12 第 6 条，读 30/分）
 * 出参 {storeId,date,list}，list 每行带 confirmed 布尔位（供「当日批次未确认」警示条，D-9）。
 * 参数名真值（F-18.17 §1.2 已核）：本端点是 `date`（PartnerController.java:131 @RequestParam date），
 * 与 orders 的 `pickupDate`（:76）**不同名**，两条各拼各的，页面不必记这个差异、更不许互换。
 * date 缺省不下发 → 服务端默认「明日」（PartnerStoreServiceImpl.java:252-254），本页两页一律显式带 date，
 * 免得警示条（按今天查）与批次页（按后端缺省查）落在两个不同的日上。
 * storeId 是 D-22 的「名下多店选一家」选择器（PartnerController.java:130 @RequestParam Long storeId）：
 * 空值 = 取名下 id 最小的一家（PartnerStoreServiceImpl.java:368-371 Collections.min）；集合外的 id → 404。
 * 它既不是归属也不是可改列（RF），归属仍由服务端解析。
 * 两个调用方的现状读数（FE-2/FE-3，别再当成「遗留项待收口」）：
 * ① 核销台的到货警示条**逐店显式传 storeId**——待取清单覆盖名下全部店，只查最小 id 那一家会漏报别家；
 * ② 批次页刻意不传（一期不提供选店，页面只显示服务端解析出的那一家，并在卡头如实写明门店编号）。
 * 页面一律走本函数，不许另发原生请求。
 * @param {{date?:string, storeId?:string|number}} [params] date 形如 YYYY-MM-DD，空值不下发
 * @returns {Promise<{code,data:{storeId:number,date:string,list:Array<object>},message:string}>}
 */
export function getPartnerArrivals(params) {
  const src = params && typeof params === 'object' ? params : {}
  const data = {}
  if (src.date) data.date = src.date
  // storeId=0 是合法值（后端按 id 匹配，命中不了就 404），只有空串/null/undefined 才当「不带这一选择器」
  if (src.storeId !== '' && src.storeId !== null && src.storeId !== undefined) data.storeId = src.storeId
  return request({ url: '/api/partner/arrivals', method: 'GET', data })
}

/**
 * 到货确认：POST /api/partner/arrivals/{id}/confirm（F-18.12 第 6 条，写 10/分）
 * 四条件 UPDATE（id + 归属集合 + status∈{planned,arriving}）；已 confirmed → 幂等成功且不重复写 ops_log；
 * 他店/不存在 → 404 同文案（不泄漏行存在性）。归属仍由服务端解析，路径里的 {id} 只是目标行。
 * @param {string|number} id 批次 id
 */
export function confirmArrival(id) {
  return request({
    url: '/api/partner/arrivals/' + encodeURIComponent(id) + '/confirm',
    method: 'POST'
  })
}

// ==================== 入驻申请（F-18.12 第 1/2 条，挂 /api/store-applies 而非 /api/partner/**） ====================
// D-20：申请阶段名下还没有店，而 /api/partner/ 的闸门是「登录 + 归属集合非空」，会把入口自己锁死；
// 故这两条只要登录、role=user 也能用（AuthInterceptor:237-238 的登录段）。
// 这也正是登录页 403 分支存在的理由：名下无店的账号能拿到 token、能调这两条，只是进不了工作台。

/**
 * 提交入驻申请：POST /api/store-applies（F-18.12 第 1 条，写 10/分）
 * 字段与 StoreApplyCreateDTO 对齐：proposedName/proposedAddress 必填，lng/lat 必填（@NotNull），
 * service/contactName/contactPhone/photos 选填（photos 最多 6 张、前缀白名单 /static/images/uploads/）。
 * 本函数只透传 body 做键名归一，形状校验交服务端唯一裁决（不在前端复制一份 @Pattern）。
 * @param {object} body
 * @returns {Promise<{code,data:object,message:string}>}
 */
export function submitStoreApply(body) {
  return request({
    url: '/api/store-applies',
    method: 'POST',
    data: body && typeof body === 'object' ? body : {}
  })
}

/**
 * 我的最新一条入驻申请：GET /api/store-applies/mine（F-18.12 第 2 条，读 30/分）
 * 出参 {id,proposedName,proposedAddress,status,auditRemark,createdAt,auditedAt} + historyCount；
 * 不下发 contactPhone/proofPhotos（最小暴露）。无 token 401、有 token 200（role=user 也可，D-20 核心断言）。
 * @returns {Promise<{code,data:object,message:string}>}
 */
export function getMyStoreApply() {
  return request({
    url: '/api/store-applies/mine',
    method: 'GET'
  })
}
