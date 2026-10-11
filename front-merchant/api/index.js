// ==================== API 请求封装（商家端唯一请求层） ====================
// 骨架照用户端 front/api/index.js:54-93 拷入后改造，三点差异：
// ① 登录态：token 读写走本端独立 storage 键 merchant_token（用户端存 'user' 对象，键名与形态都不同，
//    两端同域调试互不串态）；
// ② 401 与 403 严格分流（本文件最要紧的一处口径，§3.2 登录三分支判定完全依赖它）；
// ③ 分层纪律：本层是 front-merchant 内唯一的原生请求发起点，接口一律以具名导出收敛。
//    用户端 search.vue:472-490、index.vue:731-733 那种页内直发请求的分叉不继承（§3.1 偿还分叉债）。
import { BASE_URL } from './config.js'

// 分层超时（照用户端纪律）：查询类 10s；提交类 20s；上传另设 30s（multipart 体积大且用户端无此通道）
const TIMEOUT_QUERY = 10000
const TIMEOUT_SUBMIT = 20000
const TIMEOUT_UPLOAD = 30000

// 登录态 storage 键：main.js 的 setToken/clearToken 与本层共用这一个常量（唯一实现处，防两处漂移）
export const TOKEN_STORAGE_KEY = 'merchant_token'

// 登录页路由（401 兜底跳转目标，须与 pages.json 首页一致）
const LOGIN_PAGE = 'pages/login/login'

// 兜底文案与后端码表同源（R.java:23 MSG_UNAUTHORIZED / R.java:30 MSG_FORBIDDEN），信封 message 缺失时才用
const MSG_UNAUTHORIZED = '请先登录'
const MSG_FORBIDDEN = '无权限访问'

// ==================== 登录态读写（storage 唯一出口） ====================

export function getStoredToken() {
  try {
    const cached = uni.getStorageSync(TOKEN_STORAGE_KEY)
    return typeof cached === 'string' ? cached : ''
  } catch (e) {
    // 存储异常按未登录处理（读写全程守卫，与用户端同纪律）
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

// 401 统一回调注册器：main.js 启动时注册 → 触发时同步清 Vuex 登录态
// （api 层不反向 import store，避免 main.js ↔ api 循环引用，与用户端同构）
let unauthorizedHandler = null
export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = typeof fn === 'function' ? fn : null
}

// 401 兜底跳转：与用户端的差异点——C 端有游客态，401 只清态不跳转；
// 商家端没有游客态，未登录停在任何页面都是死路，故 401 一律收敛回登录页（页面无需各写一份）。
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
  uni.reLaunch({ url: '/' + LOGIN_PAGE })
}

// 401 统一处理：清 merchant_token + 通知 store + 回登录页 + 结构化 reject（仅 reject 不重试，无死循环）
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
 * 统一信封分流：request 与 uploadFile 共用同一个判定函数（两条通道的 401/403 语义必须一致）
 * 后端 HTTP 状态码恒 200、业务码只写在 body.code（ResponseUtil.java:23-27 拦截器直写信封 +
 * GlobalExceptionHandler 全部 return R），故 statusCode 只在信封不可解析时兜底。
 * - code 200 且信封是对象 → resolve(完整信封 {code,data,message})
 *   （HTTP 200 但 body 不是 JSON 对象的情况——代理层回 HTML 错误页等——一律按业务失败 reject，
 *   绝不 resolve(null)，否则页面会拿到一个「成功但没有商家」的假象）
 * - code 401（无/伪造/过期 token）→ rejectUnauthorized：清 token 并回登录页，message 固定「请先登录」
 * - code 403（已登录但 role!=merchant，或 merchantId 为 NULL）→ 只结构化 reject，
 *   并把后端 message 原样透出给页面渲染「暂无商家权限」引导态；
 *   绝不在此踢回登录页——那是权限问题而非登录失效，当 401 处理会让用户陷在登录死循环里
 * - 其余（400/404/500/网络异常）→ reject({code,message})，调用方须 toast，全站无静默分支
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
    reject({ code: 403, message: message || MSG_FORBIDDEN })
    return
  }
  if (res && res.statusCode === 200 && body && code === 200) {
    resolve(body)
    return
  }
  reject({
    code: code || -1,
    message: message || '服务开小差了，请稍后重试'
  })
}

/**
 * 统一请求封装：有 token 才带 Authorization: Bearer <token>；其余判定全在 handleEnvelope
 */
const request = (options) => {
  const method = (options.method || 'GET').toUpperCase()
  // 非 GET 一律按提交类给 20s（用户端只判 POST；商家端多一个 PUT /api/merchant/profile）
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

// ==================== 登录链路（与用户端同一套账号体系，不建第二套） ====================

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
 * 商家端不带用户端的 inviteCode 参数（邀请现金是 C 端玩法，商家登录无该语义）；
 * 本函数只负责拿 token，角色判定一律交随后的 getMerchantMe()（§3.2 三分支）
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
 * 复用用户端同一通道（front/api/index.js 里导出名为 loginByPassword，本端按契约命名 loginPassword）；
 * 未设过密码时后端 code:400 且 message 含「请先使用验证码登录」，调用方直接 toast 透出
 * @returns {Promise<{code,data:{token,user:{id,phone},hasPassword:boolean}}>}
 */
export function loginPassword(phone, password) {
  return request({
    url: '/api/auth/login-password',
    method: 'POST',
    data: { phone, password }
  })
}

// ==================== 商家资料接口 ====================

/**
 * 商家资料 + 资质状态：GET /api/merchant/me
 * 出参（MerchantServiceImpl.getMe）：{role:'merchant', merchantId, name, contactPhone 已脱敏,
 * contactName, status, profile:{auditStatus, businessLicense, legalPerson, address}}
 * 三分支判定就看这里：
 * - resolve（code 200 且 role='merchant'）→ 登录成功可进工作台
 * - reject code 403（拦截器查库 role!=merchant，或 role=merchant 但 merchant_id 为 NULL）→ 引导态
 * - reject code 401（token 失效）→ 请求层已清 token 并回登录页，页面不再处理跳转
 * 注：非商家用户拿不到任何商家数据，角色裁决权全在服务端拦截器，前端不预判 role
 */
export function getMerchantMe() {
  return request({
    url: '/api/merchant/me',
    method: 'GET'
  })
}

// 资料更新白名单：与后端 MerchantProfileUpdateDTO 四个字段严格一致。
// 主体名 name / 结算账户 settlement_account 一期锁定平台维护（03 §8.2），前端不留口子；
// 归属 merchant_id 由后端 @RequestAttribute 服务端解析，入参绝不带任何归属字段（R2/R3）。
const PROFILE_EDITABLE_FIELDS = ['contactName', 'address', 'businessLicense', 'legalPerson']

/**
 * 更新商家可编辑资料：PUT /api/merchant/profile（部分更新语义，null 字段后端跳过不改）
 * @param {object} fields - {contactName?, address?, businessLicense?, legalPerson?}
 * 全空请求后端回 code:400「无更新字段」，本层不做本地拦截（口径交服务端唯一裁决）
 */
export function updateMerchantProfile(fields) {
  const data = {}
  const src = fields && typeof fields === 'object' ? fields : {}
  PROFILE_EDITABLE_FIELDS.forEach((key) => {
    if (src[key] != null) data[key] = src[key]
  })
  return request({
    url: '/api/merchant/profile',
    method: 'PUT',
    data
  })
}

/**
 * 商家图片上传：POST /api/merchant/uploads（multipart，字段名固定 file，
 * 与 MerchantController.java:67 @RequestParam("file") 对齐；字段名不匹配后端回 400「请选择文件」）
 * 走 uni.uploadFile 而非 request（multipart 边界由运行时生成，手拼 Content-Type 会坏）；
 * Bearer 头需在此手填——request 层的 header 注入到不了这条通道。
 * 出参 {code:200,data:{url:'/uploads/<随机名>'}}，url 为相对路径，展示时自行拼 BASE_URL 前缀。
 * 后端 R5 校验链（后缀白名单 + 魔数 + ≤5MB + 随机文件名）在服务端完成，前端只透传 400 文案
 * @param {string} filePath - uni.chooseImage 等拿到的本地临时路径
 */
export function uploadMerchantImage(filePath) {
  return new Promise((resolve, reject) => {
    const header = {}
    const token = getStoredToken()
    if (token) header.Authorization = 'Bearer ' + token
    uni.uploadFile({
      url: BASE_URL + '/api/merchant/uploads',
      filePath,
      name: 'file',
      header,
      timeout: TIMEOUT_UPLOAD,
      success: (res) => {
        // uploadFile 不回对象而回字符串：先解 JSON 再交同一个 handleEnvelope，
        // 保证 401/403 语义与 request 通道严格一致（否则上传的 403 会被当成登录失效踢回登录页）
        let parsed = res.data
        if (typeof parsed === 'string') {
          try {
            parsed = JSON.parse(parsed)
          } catch (e) {
            parsed = null
          }
        }
        handleEnvelope({ statusCode: res.statusCode, data: parsed }, resolve, reject)
      },
      fail: () => {
        reject({ code: -1, message: '网络异常' })
      }
    })
  })
}
