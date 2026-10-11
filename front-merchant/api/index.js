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

// 登录身份 storage 键：只存手机号（非敏感凭证，仅用于 403 引导文案指代账号），
// 与 merchant_token 同写同清（main.js 的 setLoginPhone/clearToken 是唯一调用方，页面不各自读写）
export const LOGIN_PHONE_STORAGE_KEY = 'merchant_login_phone'

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
// （api 层不反向 import store，避免 main.js ↔ api 循环引用，与用户端同构）
let unauthorizedHandler = null
export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = typeof fn === 'function' ? fn : null
}

// ==================== 文件路径 -> 可展示 URL（拼 BASE_URL 的责任收敛在这里，页面层零自拼） ====================
// 两个出口按「值的寻址语义」分工，不是两份重叠实现：merchantFileUrl 内部复用 resolveFileUrl 兜其余分支。
// - resolveFileUrl(u)：后端语义相对路径（/uploads/<名> 这类由后端 ResourceHandler 直读的值，
//   以及资质照片等展示件）唯一拼接处；非 '/' 开头（绝对 URL）原样返回。
// - merchantFileUrl(u)：商品图预览专用（G-02 起任何页面展示 dish.image 一律走它，见下）。

/**
 * 相对路径 -> 后端可直读 URL：以 '/' 开头的拼 BASE_URL，其余（已是绝对 http(s) URL 等）原样返回；
 * 空值/非字符串回空串。
 * 注意：本函数不解 C 端静态根语义的 /static/images/uploads/<名>——那个路径后端读不到（dev 下会 404），
 * 展示商品图请用 merchantFileUrl()。
 */
export function resolveFileUrl(u) {
  if (typeof u !== 'string' || !u) return ''
  return u.charAt(0) === '/' ? BASE_URL + u : u
}

// 入库值前缀（C 端静态根语义，F-13/G-02 决策①方案 A）与后端映射前缀（CorsConfig 双映射的另一条）
const UPLOADS_STATIC_PREFIX = '/static/images/uploads/'
const UPLOADS_BACKEND_PREFIX = '/uploads/'

/**
 * 商家端商品图预览：把入库的 /static/images/uploads/<名> 归一化为 BASE_URL + '/uploads/<名>'。
 * 为什么要换前缀：商家端跑独立 origin（dev 5174），C 端静态根路径不在它的静态目录下，
 * 原样给 <image :src> 必然加载不出；而 CorsConfig 对同一物理目录注册了这两条映射，
 * 换前缀不换文件，两个 origin 都能显示（prod 同源部署时 BASE_URL 为空则自然退化成相对路径）。
 * 分支：
 * - 空值/非字符串 -> ''（模板据此走 emoji 兜底，不产出 'undefined' 这种坏 src）
 * - /static/images/uploads/<名> 与 /uploads/<名> -> BASE_URL + '/uploads/<名>'（两种写法归一到同一条后端映射）
 * - 其余以 '/' 开头（存量种子图 /static/images/dish-N.svg 等）-> 交 resolveFileUrl 拼后端，
 *   取不到就随 @error 落 emoji 兜底，与 C 端口径一致
 * - 绝对 http(s) URL / 非 '/' 开头（chooseImage 的本地临时路径等）-> 原样返回
 * 本函数只管「显示」：写进 dish.image 的必须是上传出参的 path 键（/static/images/uploads/<名>），
 * 存 url 键或本函数返回值会让 C 端显示不出来——那正是决策①要消灭的静默假成功。
 */
export function merchantFileUrl(u) {
  if (typeof u !== 'string' || !u) return ''
  let name = null
  if (u.indexOf(UPLOADS_STATIC_PREFIX) === 0) name = u.slice(UPLOADS_STATIC_PREFIX.length)
  else if (u.indexOf(UPLOADS_BACKEND_PREFIX) === 0) name = u.slice(UPLOADS_BACKEND_PREFIX.length)
  if (name) return BASE_URL + UPLOADS_BACKEND_PREFIX + name
  return resolveFileUrl(u)
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

// 商家生命周期三态文案（列值域见 m15-migration.sql:22 active/suspended/terminated）：
// 真值只在这一处——工作台的状态行与商品页写闸门红条共用它，否则同一状态在两个页面两种口径，
// 红条裸露 suspended 这种后端枚举时商家会当成系统报错
const MERCHANT_STATUS_TEXT = {
  active: '正常营业',
  suspended: '停业整顿',
  terminated: '已清退'
}

export function merchantStatusText(status) {
  const s = status || ''
  // 未知值/空值原样返回：状态是后端事实，前端不替它猜一个中文说法（脏值要能被当场看见）
  return MERCHANT_STATUS_TEXT[s] || s
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
 * 与 MerchantController.java:74 @RequestParam("file") 对齐；字段名不匹配后端回 400「请选择文件」）
 * 走 uni.uploadFile 而非 request（multipart 边界由运行时生成，手拼 Content-Type 会坏）；
 * Bearer 头需在此手填——request 层的 header 注入到不了这条通道。
 * 出参 {code:200,data:{path:'/static/images/uploads/<随机名>',url:'/uploads/<随机名>'}}（F-13/G-02 决策①方案 A）：
 * **入库用 path**（与 C 端现状 dish.image 同构，C 端 :src 零改动即可显示），url 只是后端映射地址；
 * 展示一律走本文件 merchantFileUrl()（§3.1 唯一出口，禁止页内自拼 BASE_URL），把 url 存进库里
 * 会得到「上传成功、库里有值、C 端永远显示 emoji 兜底」的静默假成功。
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

// ==================== 商品接口（F-13/G-02 Step 4，契约 F-13.2 六端点） ====================
// 归属纪律：六个端点的 merchant_id 全由后端 @RequestAttribute 解析（R2/R3），本层不拼任何归属入参——
// DTO 里根本没有 merchantId/id 字段，前端就算硬塞也进不去；路径里的 {id} 只是目标行，
// 是不是当前商家的行由服务端 WHERE 判定（他商家与不存在同为 404「商品不存在」）。
// 写闸门：非 active 商家的写请求一律 403「无权限访问」（F-13 决策④），handleEnvelope 只透出 message 不清 token，
// 页面据此渲染红条并禁用保存，不当登录失效处理。

/**
 * 商品分类字典：GET /api/dict?group=dish_category（匿名可读，DictController.java:29-31）
 * 11 类只选不建（F-13.4）：词条 definition 形如 'code:meat'，code 即 dish.category 列取值（init.sql:67 列注释）。
 * 分组名只能是 dish_category——后端 GROUP_ID 只登记了四个键（DictServiceImpl.java:36-39），
 * 写成 'category' 会 400「字典分组不存在」（契约 F-13.4 那句 group=category 是错值，已列契约疑点回报）。
 * 模块级缓存的理由：/api/dict 限流是 IP 维度 10 次/分（DictController.java:29），而列表页与编辑页各要一份下拉，
 * 每次进入都打一次的话来回切几页就吃 429、下拉静默变空；只缓存成功结果，失败不写缓存以便下次重试
 * （缓存策略同用户端 front/api/dict.js，本端只需内存一级）。
 * 拉不到时（reject 或成功但空列表）回内置 11 类并标 degraded：category 是后端 @NotBlank 必填，
 * 下拉一旦为空新增就永远提交不出去（C 端靠同一份兜底免疫，见 front/api/dict.js:23-34 的 FALLBACK）；
 * 回退结果刻意不进缓存——上游恢复后下次进入自动回真源。
 * @returns {Promise<{code:number,data:Array<{word:string,definition:string,sortOrder:number}>,degraded?:boolean}>}
 */
const DISH_CATEGORY_FALLBACK = [
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
]

let categoryDictCache = null
export function getDishCategories() {
  if (categoryDictCache) {
    return Promise.resolve({ code: 200, data: categoryDictCache, message: '分类字典命中缓存' })
  }
  return request({ url: '/api/dict', method: 'GET', data: { group: 'dish_category' } }).then(
    (res) => {
      const list = res && Array.isArray(res.data) ? res.data : []
      if (!list.length) return { code: 200, data: DISH_CATEGORY_FALLBACK, degraded: true }
      categoryDictCache = list
      return res
    },
    () => ({ code: 200, data: DISH_CATEGORY_FALLBACK, degraded: true })
  )
}

// 新增/编辑共用的字段白名单（与 MerchantDishCreateDTO / MerchantDishUpdateDTO 逐字段对齐）。
// R5'：seckill / seckillPrice / soldCount / goodRate 四列与 id / merchantId 一律不出现在请求里——
// 秒杀列二期开放、sold_count 由支付链路累加、good_rate 由评价聚合回写、归属与主键服务端解析。
// DISH_UPDATE_FIELDS 刻意不含 stock，理由见 updateMerchantDish。
const DISH_UPDATE_FIELDS = ['name', 'price', 'image', 'description', 'category', 'unit', 'emoji', 'bgColor', 'tags', 'limitBuy']
// 新增比编辑多一个 stock：仅用于给新行定初值，不构成「变更已有商品库存」的路径（R7'）
const DISH_CREATE_FIELDS = DISH_UPDATE_FIELDS.concat(['stock'])

// 白名单挑字段：null/undefined 不下发（部分更新语义里那叫「本次不动这一列」），
// 空串 '' 照发——那是用户主动清空描述/图片的意图，后端 @Size 允许，会被真的写进去
function pickDishFields(src, fields) {
  const o = src && typeof src === 'object' ? src : {}
  const data = {}
  fields.forEach((key) => {
    if (o[key] !== undefined && o[key] !== null) data[key] = o[key]
  })
  return data
}

/**
 * 商品列表：GET /api/merchant/dishes?tab=&category=&keyword=（三个筛选都可省，缺省即全部）
 * 未知 tab 值后端按 all 兜底、keyword 只做名称 LIKE、category 不做枚举硬校验（F-13.6 边界 14，三条宽松口径）
 * @param {{tab?:string, category?:string, keyword?:string}} [query]
 * @returns {Promise<{code,data:object[]}>} 一期不分页（F-13.6 边界 1，现 28 SKU 量级）
 */
export function getMerchantDishes(query) {
  const src = query && typeof query === 'object' ? query : {}
  const data = {}
  if (src.tab) data.tab = src.tab
  if (src.category) data.category = src.category
  if (src.keyword) data.keyword = src.keyword
  return request({ url: '/api/merchant/dishes', method: 'GET', data })
}

/**
 * 商品详情：GET /api/merchant/dishes/{id}（编辑态回填用；他商家或不存在同为 404「商品不存在」）
 * @returns {Promise<{code,data:object}>} data 是完整 dish 行
 */
export function getMerchantDish(id) {
  return request({ url: '/api/merchant/dishes/' + encodeURIComponent(id), method: 'GET' })
}

/**
 * 新增商品：POST /api/merchant/dishes
 * 必填 name/price/category/unit/stock/limitBuy（后端 @NotBlank/@NotNull），选填 image/description/emoji/bgColor/tags；
 * tags 最多 5 个、每个 ≤12 字（@Size 在服务端裁）。新建行 on_sale 走 DB 默认 1 = 商家新增即上架（F-13.6 边界 13）。
 * @returns {Promise<{code,data:object,message:string}>} data 为落库后回查的完整新行（含 id）
 */
export function createMerchantDish(form) {
  return request({
    url: '/api/merchant/dishes',
    method: 'POST',
    data: pickDishFields(form, DISH_CREATE_FIELDS)
  })
}

/**
 * 编辑商品：PUT /api/merchant/dishes/{id}（部分更新，全部选填；一个字段都没改时后端 400「无更新字段…」，
 * 本层不做本地拦截，口径交服务端唯一裁决——与 updateMerchantProfile 同款）
 * 显式不转发 stock：后端 MerchantDishServiceImpl.java:132-136 见 stock 非 null 就 400
 * 「库存不能直接填最终值，请用库存调整接口按增减数量修改」（R7'：一期精确 set 库存归 Admin，商家端只开增量）。
 * 后来人若把 stock 加回 DISH_UPDATE_FIELDS，编辑保存就会撞上这条 400——改库存请走 adjustMerchantDishStock()。
 * @param {string|number} id
 * @param {object} fields - DISH_UPDATE_FIELDS 内的任意子集
 */
export function updateMerchantDish(id, fields) {
  return request({
    url: '/api/merchant/dishes/' + encodeURIComponent(id),
    method: 'PUT',
    data: pickDishFields(fields, DISH_UPDATE_FIELDS)
  })
}

/**
 * 库存增量调整：PUT /api/merchant/dishes/{id}/stock，body {delta}（正补负减，|delta| ≤ 1000，减成负数 400「库存不足，不能减成负数」）
 * @returns {Promise<{code,data:number,message:string}>} data 是落库后的真实库存——并发下它不等于
 * 「进入页面时看到的库存 + delta」，页面必须以本返回值回显，别自己加（后端 stock = stock + delta 是条件 UPDATE，非读改写）
 */
export function adjustMerchantDishStock(id, delta) {
  return request({
    url: '/api/merchant/dishes/' + encodeURIComponent(id) + '/stock',
    method: 'PUT',
    data: { delta: Number(delta) }
  })
}

/**
 * 上下架：PUT /api/merchant/dishes/{id}/on-sale，body {onSale}
 * 用 1/0 不用 boolean：dish.on_sale 是 TINYINT、实体列是 Integer onSale（init.sql:70），
 * 与后端 MerchantDishOnSaleDTO 及 AdminDishUpdateDTO 同一套口径，免得布尔↔tinyint 两边各猜
 */
export function setMerchantDishOnSale(id, onSale) {
  return request({
    url: '/api/merchant/dishes/' + encodeURIComponent(id) + '/on-sale',
    method: 'PUT',
    data: { onSale: Number(onSale) }
  })
}
