// ==================== API 请求封装（商家端唯一请求层） ====================
// 骨架照用户端 front/api/index.js 里 request 封装的整段（uni.request + 信封分流）拷入后改造，三点差异：
// ① 登录态：token 读写走本端独立 storage 键 merchant_token（用户端存 'user' 对象，键名与形态都不同，
//    两端同域调试互不串态）；
// ② 401 与 403 严格分流（本文件最要紧的一处口径，§3.2 登录三分支判定完全依赖它）；
// ③ 分层纪律：本层是 front-merchant 内唯一的原生请求发起点，接口一律以具名导出收敛。
//    用户端 front/pages/search/search.vue 的 dishRequest 与 front/pages/index/index.vue 的 dishRequest 那种页内直发请求的分叉不继承（§3.1 偿还分叉债）。
import { BASE_URL } from './config.js'

// 分层超时（照用户端纪律）：查询类 10s；提交类 20s；上传另设 30s（multipart 体积大且用户端无此通道）
const TIMEOUT_QUERY = 10000
const TIMEOUT_SUBMIT = 20000
const TIMEOUT_UPLOAD = 30000

// 登录态 storage 键：main.js 的 setToken/clearToken 与本层共用这一个常量（唯一实现处，防两处漂移）
export const TOKEN_STORAGE_KEY = 'merchant_token'

// 登录页路由（401 兜底跳转目标，须与 pages.json 首页一致）
const LOGIN_PAGE = 'pages/login/login'

// 兜底文案与后端码表同源（R.java:24 MSG_UNAUTHORIZED / R.java:30 MSG_FORBIDDEN），信封 message 缺失时才用
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
 * 注意：本函数对 C 端静态根语义的 /static/images/uploads/<名> 只拼 BASE_URL、不改前缀——那一路后端读得到
 * （CorsConfig:70-71 为同一物理目录注册了这条映射，且拦截器只挂 /api/**，匿名可读），但展示商品图请用 merchantFileUrl()。
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
 * - /static/images/<名>（非 uploads 子目录，即平台 28 个种子商品 dish-N.svg）-> 直接 ''，不发请求。
 *   为什么不能交 resolveFileUrl 拼后端：后端 ResourceHandler 只映射 /uploads/** 与 /static/images/uploads/**
 *   （CorsConfig），种子图是 C 端静态目录里的文件、后端根本没有这一份，拼出来的 URL 每次都必 404；
 *   C 端显示这些 svg 是「同 origin 命中自己的静态目录」，商家端跑独立 origin 是「跨 origin 打后端」，
 *   两者寻址语义本就不同，此前注释写成「与 C 端口径一致」不实。回 '' 让模板走 emoji，省掉 28 个注定失败的请求。
 * - 绝对 http(s) URL / 非 '/' 开头（chooseImage 的本地临时路径等）-> 原样返回
 * 本函数只管「显示」：写进 dish.image 的必须是上传出参的 path 键（/static/images/uploads/<名>），
 * 存 url 键或本函数返回值会让 C 端显示不出来——那正是决策①要消灭的静默假成功。
 */
const DISH_STATIC_ROOT_PREFIX = '/static/images/'

export function merchantFileUrl(u) {
  if (typeof u !== 'string' || !u) return ''
  let name = null
  if (u.indexOf(UPLOADS_STATIC_PREFIX) === 0) name = u.slice(UPLOADS_STATIC_PREFIX.length)
  else if (u.indexOf(UPLOADS_BACKEND_PREFIX) === 0) name = u.slice(UPLOADS_BACKEND_PREFIX.length)
  if (name) return BASE_URL + UPLOADS_BACKEND_PREFIX + name
  // C 端静态根专属资源（种子 svg 等）：商家端 origin 下取不到，后端也没有这一份，直接回空串走 emoji 兜底
  if (u.indexOf(DISH_STATIC_ROOT_PREFIX) === 0) return ''
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
  // 跳转前先告知原因：reLaunch 会销毁当前页（长表单内容随之全丢），不出一声的话用户只觉得
  // 「页面莫名其妙跳回登录了」。文案复用 MSG_UNAUTHORIZED，不新写第二串（redirect 回跳本期不做）
  uni.showToast({ title: MSG_UNAUTHORIZED, icon: 'none' })
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
 * 后端 HTTP 状态码恒 200、业务码只写在 body.code（ResponseUtil.java 的 writeJson 拦截器直写信封 +
 * GlobalExceptionHandler 全部 return R），故 statusCode 只在信封不可解析时兜底。
 * - code 200 且信封是对象 → resolve(完整信封 {code,data,message})
 *   （HTTP 200 但 body 不是 JSON 对象的情况——代理层回 HTML 错误页等——一律按业务失败 reject，
 *   绝不 resolve(null)，否则页面会拿到一个「成功但没有商家」的假象）
 * - code 401（无/伪造/过期 token）→ rejectUnauthorized：清 token 并回登录页，message 固定「请先登录」
 * - code 403（已登录但 role!=merchant，或 merchantId 为 NULL）→ 只结构化 reject，
 *   并把后端 message 原样透出给页面渲染「暂无商家权限」引导态；
 *   绝不在此踢回登录页——那是权限问题而非登录失效，当 401 处理会让用户陷在登录死循环里
 * - 其余（400/404/429/500/网络异常）→ reject({code,message,data})，调用方须 toast，业务失败面无静默分支（仅快照补拉 syncMerchantSnapshot 两处刻意静默，dishes 页与 dish-edit 页各自注释自陈「失败静默」）；
 *   data 是后端信封的原样透传（信封里没有 data 或不可解析时为 null），
 *   429 的 {retryAfterSeconds} 与 F-22 下发的其余载荷都从这里出，页面按 retrySecondsOf 取用
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
 * 429 冷却闸门的秒数取值（唯一实现处，页面 import 使用，不在各页复制私有版本）：
 * 优先转述后端 data.retryAfterSeconds（F-22 起 RateLimitInterceptor 在 429 的 data 里下发）——
 * 该值是有限正数才 Math.ceil；拿不到 data（网络异常/信封不可解析）或值不合法时回落到调用方给的 fallbackSeconds。
 * 回落走的是**数值形态** Number(fallbackSeconds) 而不是入参本身（X-13，与 front-partner/api/index.js
 * 的 retrySecondsOf 末行 `return fb` 等形）：末行原先 `return fallbackSeconds` 把调用方的原样吐回去，
 * 第二参一旦是 '60' 这种字符串，本函数就返回字符串——三个返回点的形态不再一致，而消费侧
 * 全按数字用它：`retryLock--` 自减、`v-if="!retryLock"` 真假判定、`{{ retryLock }}s` 渲染都要过隐式转换。
 * 今天九个调用点传的都是数字常量 60，`f` 与 `fallbackSeconds` 同值，改动对它们是逐 case 等价。
 * fallbackSeconds 的口径＝该端点 @RateLimit 的 windowSeconds（整个窗口），它是后端不给时的保守上限，
 * 不是回速（30 次/分 档的回速实测只有 2 秒）——两个数不是一回事，页内常量别混用。
 *
 * 第二参是必传项，且必须是有限正数（A-3 的根治）：改前这一支直接 `return fallbackSeconds`，漏传就回
 * undefined → 页面 `this.retryLock = undefined` → 所有 `retryLock > 0` 判定恒假 → **闸门敞开不拦**；
 * 更阴的是模板 `!retryLock` 此时为真，冷却整行被藏掉，用户看不到任何异常、控制台也没有声音。
 * 现在非有限正数一律回硬保守值 RETRY_GATE_MIN_SECONDS 并 console.warn 留痕——闸门宁可多关一会儿，
 * 也不能因为一行拷贝就把「等待」这件事整个取消。九个调用点（dashboard/dishes/marketing/pickup-plan/
 * login 各一处、plan-orders 读侧与缺货写侧各一处、dish-edit 保存与三把写锁共用一处）都显式传了数字常量 60，改动对它们是逐字等价的。
 *
 * data.retryAfterSeconds 全仓有四个产出点、两种语义，本函数只取数值不认语义，四处都能喂进来但含义不同：
 * - backEnd/src/main/java/com/fresh/interceptor/RateLimitInterceptor 的 retryAfterSeconds 换算（retryAfterSecondsOf 静态纯函数，具名后不挂行号）—— 桶的回速
 *   （NanosToWaitForRefill 向上取整、下限 1，实测个位秒），绝大多数 429 出自这里，也是 fallbackSeconds 要对标的档位；
 * - backEnd/src/main/java/com/fresh/service/impl/AdminAuthServiceImpl.java:276 —— **401**（不是 429）
 *   运营登录防枚举锁定的剩余 TTL，上限 600 秒（LOGIN_FAIL_LOCK_TTL=10 分钟）；
 * - backEnd/src/main/java/com/fresh/service/impl/AuthServiceImpl.java 的 assertCodeNotLocked —— 429（F-65 验证码失败锁），
 *   同一手机号连错 5 次的剩余锁定期，同为 600 秒封顶；
 * - backEnd/src/main/java/com/fresh/service/impl/PartnerOrderServiceImpl.java 的核销锁 429 分支（D-14）—— 核销枚举锁的剩余 TTL，同为 600 秒封顶。
 * 为什么不能与 front-admin/src/api/index.js 的 retryAfterText 互换（两者只是长得像）：
 * retryAfterText 是纯文案转述，函数内零本地推算、拿不到就回空串闭嘴，服务的是「服务端约 N 秒后可再试」
 * 那一句；本函数服务的是闸门，必须永远交出一个能起闸的正数。两个方向拿错都会出事——
 * 拿 retryAfterText 替闸门：空串让 `retryLock > 0` 恒假，等于上面那条敞闸；
 * 拿本函数替文案：后端没给读数时把猜出来的 60 当事实念给用户，撞上 admin 那条 401 时更会把
 * 600 秒的锁定剩余时长当成「保守上限」报出去。所以两边各留各的回落纪律，不许合并。
 */
// 第二参不合法时的硬保守值：全仓 @RateLimit 的 windowSeconds 最长就是 60 秒这一档（50 处挂载点里 49 处为 60，
// 见 MerchantDishController / OrderController / DictController 等），另有 1 处 10 秒——
// ReviewController.java 的 addReview（limit = 1, windowSeconds = 10），没有比 60 更长的档。
// 兜到 60 ＝按全仓最长档起闸，是最保守的那个值（原注「实测只有 60 一档」事实错，F-28.1/X-11 订正；结论不变）
const RETRY_GATE_MIN_SECONDS = 60

export function retrySecondsOf(err, fallbackSeconds) {
  const data = (err && err.data) || null
  const n = Number(data && data.retryAfterSeconds)
  if (Number.isFinite(n) && n > 0) return Math.ceil(n)
  const f = Number(fallbackSeconds)
  if (!Number.isFinite(f) || f <= 0) {
    console.warn('[api] retrySecondsOf: 调用方漏传了该页档位的窗口长度（fallbackSeconds=' + String(fallbackSeconds) + '），已按保守值 ' + RETRY_GATE_MIN_SECONDS + ' 起闸')
    return RETRY_GATE_MIN_SECONDS
  }
  // X-13：回已换算的 f，不是入参 fallbackSeconds 本身——与 front-partner/api/index.js 的 `return fb` 等形，
  // 三个返回点从此恒为数字（调用方传 '60' 时不再把字符串喂给闸门与模板）
  return f
}

/**
 * ==================== F-28.5 路 B：429/冷却文案族的唯一生成处 ====================
 * 改前商家端同一个 429 有五种说法（「点得太快了，稍后再刷新」／「操作过于频繁，请 Ns 后再试」／
 * 「X请求太频繁，Ns后可重试」／「X请求太频繁，稍后再点重试（Ns）」括号制／「X请求太频繁，请稍后再试」），
 * 都不骗人，但验收方读不出这是同一条规则。这里收成一个句式、三种形态：
 *   FULL  coolingFullText(label, seconds) → 「<label>请求太频繁，<时长>后可重试」  归因 + 带数
 *   LEAD  coolingLeadText(label)          → 「<label>请求太频繁，稍后再点重试」  归因 + 不带数
 *   PILL  cooldownPillText(seconds)       → 「<时长>后可重试」 / resendPillText → 「<N>s后重发」
 *         <时长> 由 formatCoolingSeconds 出：<=60 是紧凑 `Ns`、>=61 是中文「N 分 MM 秒」（60 归紧凑档，理由见该函数注释）；
 *         resendPillText 刻意不接这套换算（见其函数注释），恒走 `<N>s`。
 *         只用在按钮/胶囊这类宽度受限、且**上一行就是同屏归因句**的位置（错误卡的 lead 已经点名是哪把锁）
 * 三种形态的分工正是 X-5 定稿判据的落地方式：同一把锁一屏只有一处带数（LEAD 与 PILL 各顶半边），
 * 而多把独立锁同屏时（dishes 页的商品列表锁 + 类目字典锁、dish-edit 页的保存/上传/库存/上下架四把写锁）
 * 每一处都从 label 参数点名自己报的是哪把——不许再出现无归因的「太频繁」。
 * 母版沿用 dishes.vue 的 retryCoolingBlocked 与 pickup-plan/plan-orders/dashboard 三页同形的那条 toast，
 * 逐字不动（改前它们已经是这个句式了），本页族里其余四种说法全部并进来。
 *
 * 单位：分两套制式，由 formatCoolingSeconds 单点分叉（改这一处即可整体调整时长读法）：
 * ①<=60 秒走紧凑 `Ns`——沿用全端唯一单位字面量 COOLING_UNIT，与改前逐字节一致（X-8「单位统一到 s」的定稿，
 *   toast 与按钮里的时长要短，`5s后可重试` 比 `5 秒后可重试` 少两个字符宽，不撑破 .error-retry 胶囊）；
 * ②>=61 秒换算成中文「N 分 MM 秒」，秒位继续走（600→「10 分 00 秒」、601→「10 分 01 秒」）。
 *   分档线刻意含 60（F-38.3）：回落档位的值就是 60，让它留在紧凑档，模板短标签与 pill 才是同一个读法。
 *   这一支刻意不再用 ceil 到分钟的粗化——运营端 waitDurationText 在本片之前就是那种会把 600→541 冻成同一个读数「大约 10 分钟」（实测 ceil(541/60)=ceil(600/60)=10），
 *   正是本条任务线在打的「静止读数冒充剩余时间」——运营端那一处已由 F-98e 改成同形的分秒制，三端这一档一致了。
 * 括号制「（Ns）」是第三种形态，本批消灭：它把时长放在句尾括号里，读不出和「后可重试」是同一条规则。
 *
 * 数值来源纪律（本文件红线，与 retrySecondsOf 同一条）：这些函数**只做字符串生成**，
 * seconds 一律由各页把 retrySecondsOf(err, <该页回落档>) 转述出来的那一枚实时剩余秒数喂进来，
 * 页内不许新增任何写死的时长字面量；非有限正数（含 0）一律退回 LEAD 那种不带数的整句——
 * 这正是 X-3 点名的病的反面：把「请 60s 后再试」存进常驻句子、冷却走完后按钮早已解禁却还挂着数。
 */
// 时长单位（全端唯一一处字面量；改这一处即可整体换单位）
const COOLING_UNIT = 's'
// 句式骨架（label 是「锁名」，函数负责补上「请求」二字，页面只传业务名词）
const COOLING_SUFFIX = '后可重试'
const COOLING_RESEND_SUFFIX = '后重发'

function coolingSkeleton(label) {
  return (label || '') + '请求太频繁，'
}

/**
 * 时长格式化（<=60 与 >=61 两套制式的唯一分叉口，判有效性在前、取整在后）：
 *   <=60 → 紧凑 `Ns`（沿用全端唯一单位字面量 COOLING_UNIT，与改前逐字节一致）
 *   >=61 → 中文「N 分 MM 秒」，秒位继续走（600→「10 分 00 秒」、601→「10 分 01 秒」）
 * 为什么这里绝不用 ceil 到分钟：运营端 waitDurationText 在 F-98e 之前正是把 60→1 分、600→10 分粗化到整档，
 * 会让 600→541 这 60 秒里读数一动不动（那一段全显示「大约 10 分钟」）——那正是本条任务线一直在打的「静止读数冒充剩余时间」。
 * 先判 Number.isFinite 与 >0 再 Math.ceil：顺序反了会把 0.4 抬成「1s」而不是降级成不带数。
 * 返回空串代表「这一处不该出数」，由调用方（coolingFullText / cooldownPillText）的无效降级分支处理。
 *
 * 【分档线为什么刻意落在「60 不算分钟」】（F-38.3：上一版把线画在 <60，依据的那句前提是错的）：
 * 会落到模板短标签里的读数并非恒 <60s——后端 429 不带 data.retryAfterSeconds 时，
 * retrySecondsOf 回落到调用方带来的该页档位，而登录页那把就是 LOGIN_LOCK_SECONDS = 60（主理人端六页的
 * RETRY_FALLBACK_SECONDS 同为 60）。线画在 <60 时，同一个 60 在同一屏印成两副面孔：模板裸插「60s后可重试」，
 * pill 函数「1 分 00 秒后可重试」——用户读出两个形状、实际是同一把锁的同一秒。把 60 归进紧凑档后，
 * 回落那一格在两处逐字同形，换算区只剩 >=61。
 * 代价如实登记：倒计时从 61「1 分 01 秒」走到 60 会换一次形态（变成「60s」）。
 * 换的只是**制式**不是读数——数值始终单调递减，不会停也不会跳回去；
 * 相比之下「同一个数在同屏有两种读法」更难读，那才是本条任务线一直在打的病。
 */
function formatCoolingSeconds(seconds) {
  const n = Number(seconds)
  if (!Number.isFinite(n) || n <= 0) return ''
  const c = Math.ceil(n)
  if (c <= 60) return c + COOLING_UNIT        // COOLING_UNIT='s' 是全端唯一单位字面量，<=60 支仍走它，别删
  return Math.floor(c / 60) + ' 分 ' + String(c % 60).padStart(2, '0') + ' 秒'
}

/**
 * LEAD：归因 + 不带数。用在「同屏已有一处带数」的那一半（错误卡主文案、被拦 toast 的无真值态、
 * 类目提示行的回落态——回落值是给闸门用的窗口长度，不是能念给用户的等待时长，一个数都不出）。
 */
export function coolingLeadText(label) {
  return coolingSkeleton(label) + '稍后再点重试'
}

/**
 * FULL：归因 + 带数，本屏这一把锁的那一处数就由它出。seconds 拿不到真值（0/NaN/漏传）时自动降级成
 * coolingLeadText，调用方不必自己写三元——这样「不给数就不许念数」这条判据只有一处实现。
 * 带数那一支的时长读法（<=60 → Ns、>=61 → N 分 MM 秒）由 formatCoolingSeconds 单点决定，本函数不自己拼单位。
 */
export function coolingFullText(label, seconds) {
  const n = Number(seconds)
  if (!Number.isFinite(n) || n <= 0) return coolingLeadText(label)
  return coolingSkeleton(label) + formatCoolingSeconds(n) + COOLING_SUFFIX
}

/**
 * PILL：短式带数，只用于按钮/胶囊。归因由所在控件自己给（错误卡上一行的 lead、
 * 登录页的「登录」按钮、发码按钮的「获取验证码」），所以这一支刻意不带 label。
 * 无时长时回空串（调用点的模板分支本来就是「有锁才渲染这一支」，回空串比回一句谎话安全）。
 * 时长读法同 coolingFullText，由 formatCoolingSeconds 单点决定（<=60 → Ns、>=61 → N 分 MM 秒）。
 */
export function cooldownPillText(seconds) {
  const n = Number(seconds)
  if (!Number.isFinite(n) || n <= 0) return ''
  return formatCoolingSeconds(n) + COOLING_SUFFIX
}

/**
 * PILL 的「重发」变体：验证码那把锁报的是「什么时候能再要一条码」，动词必须是重发不是重试。
 * 它刻意不接 formatCoolingSeconds 那套时长换算——这一支报的是「1 分钟 1 码」的业务节奏，
 * 不是后端 retryAfterSeconds 的限流等待剩余；接了会把 60 印成「1 分 00 秒后重发」，把业务规则说成倒计时。
 * （与 cooldownPillText 的分工：那一支管限流冷却、走分秒换算；这一支管发码节奏、恒紧凑 Ns。）
 */
export function resendPillText(seconds) {
  const n = Number(seconds)
  if (!Number.isFinite(n) || n <= 0) return ''
  return n + COOLING_UNIT + COOLING_RESEND_SUFFIX
}

/**
 * 统一请求封装：有 token 才带 Authorization: Bearer <token>；其余判定全在 handleEnvelope
 *
 * options.noBody === true 是「这个端点不收请求体」的显式声明（F-69 的缺货登记是第一个用户）：
 * 这一支把 data 交 undefined 而不是默认的 {}。差别不是洁癖——`{}` 会被序列化成一枚两字节非空的
 * '{}' 请求体，于是「无 body」这条契约面（后端类注释原文：收 @RequestBody 只会多一个可被伪造的
 * 入参面）在网络上根本断不成真。不传该键时行为逐字不变，其余调用方零影响。
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
      data: options.noBody === true ? undefined : (options.data || {}),
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
 * 与 MerchantController#upload 的 @RequestParam("file") 对齐；字段名不匹配后端回 400「请选择文件」）
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
 * 11 类只选不建（F-13.4）：词条 definition 形如 'code:meat'，code 即 dish.category 列取值（init.sql 的 dish.category 列注释）。
 * 分组名只能是 dish_category——后端 GROUP_ID 只登记了四个键（DictServiceImpl.java:36-39），
 * 写成 'category' 会 400「字典分组不存在」（契约 F-13.4 那句 group=category 是错值，已列契约疑点回报）。
 * 模块级缓存的理由：/api/dict 限流是 IP 维度 10 次/分（DictController.java:28 的
 * `@RateLimit(keyType = IP, limit = 10, windowSeconds = 60)`），而列表页与编辑页各要一份下拉，
 * 每次进入都打一次的话来回切几页就吃 429、下拉静默变空；只缓存成功结果，失败不写缓存以便下次重试
 * （缓存策略同用户端 front/api/dict.js，本端只需内存一级）。
 * 拉不到时（reject 或成功但空列表）回内置 11 类并标 degraded：category 是后端 @NotBlank 必填，
 * 下拉一旦为空新增就永远提交不出去（C 端靠同一份兜底免疫，见 front/api/dict.js:23-34 的 FALLBACK）；
 * 回退结果刻意不进缓存——上游恢复后下次进入自动回真源。
 * degradedReason（契约字段名，编辑页消费侧同名，不可改名）只取两个字面值，用于区分「谁把字典弄坏的」：
 * - 'rate-limited'：reject 且 code 429——是本页自己打太频繁撞的限流桶，不是上游故障，
 *   文案须引导「稍后再点重试」，否则会误导商家去查上游、并在用户对着错误文案反复点时把 IP 桶续满
 * - 'unreachable'：其余失败（网络异常/5xx/403）与「200 但空列表」
 * degradedRetryAfterSeconds（同为契约字段名，列表页与编辑页消费侧同名，不可改名）：
 * 429 降级支恒带这一键，值是后端 data.retryAfterSeconds 的原样转述（RateLimitInterceptor 的 429 data 组装处，具名后不挂行号，
 * 桶的回速而非窗口）；后端没给读数就是 null，刻意不填默认值——编一个数出去下游就再也分不出
 * 「后端说的」与「我们猜的」。'unreachable' 两支里「200 但空列表」不带这一键（undefined），
 * 消费侧一律按 Number.isFinite && > 0 判定后才用它起闸门 / 印秒数。
 * @returns {Promise<{code:number,data:Array<{word:string,definition:string,sortOrder:number}>,degraded?:boolean,degradedReason?:'rate-limited'|'unreachable',degradedRetryAfterSeconds?:number|null}>}
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
      // 200 但空列表：上游没坏，只是这一组没词条 —— 归 unreachable（重试也还是空，不是限流）
      if (!list.length) return { code: 200, data: DISH_CATEGORY_FALLBACK, degraded: true, degradedReason: 'unreachable' }
      categoryDictCache = list
      return res
    },
    (e) => {
      // 429 与真·不可达分开标：前者是我们自己点快了，后者才是上游有问题
      const reason = (e && Number(e.code) === 429) ? 'rate-limited' : 'unreachable'
      // F-24-B/FB-1 通道：这一路 reject→resolve 原本会把 429 的 e.data 整个丢掉，
      // 下游页面（dishes / dish-edit）手里只剩常量可写，于是各自把窗口长度 60 当成了等待时长。
      // 这里只多带一个键，把后端真值原样转述出去（源头：RateLimitInterceptor 的 429 data 组装处（具名后不挂行号）的
      // data.retryAfterSeconds，即桶的回速而非窗口）。
      // 取不到就是 null，刻意不给默认值：编一个数出去下游就无法再区分「后端说的」与「我们猜的」，
      // 回落口径由各页面自己带注释声明（retrySecondsOf 那条有回落的通道只服务闸门，不服务文案）。
      const raw = e && e.data && e.data.retryAfterSeconds
      const retryAfterSeconds = Number.isFinite(Number(raw)) && Number(raw) > 0 ? Number(raw) : null
      return { code: 200, data: DISH_CATEGORY_FALLBACK, degraded: true, degradedReason: reason, degradedRetryAfterSeconds: retryAfterSeconds }
    }
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
 * 显式不转发 stock：后端 MerchantDishServiceImpl#update 见 dto.getStock() 非 null 就 400
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
 * 用 1/0 不用 boolean：dish.on_sale 是 TINYINT、实体列是 Integer onSale（见 init.sql 的 dish.on_sale 列），
 * 与后端 MerchantDishOnSaleDTO 及 AdminDishUpdateDTO 同一套口径，免得布尔↔tinyint 两边各猜
 */
export function setMerchantDishOnSale(id, onSale) {
  return request({
    url: '/api/merchant/dishes/' + encodeURIComponent(id) + '/on-sale',
    method: 'PUT',
    data: { onSale: Number(onSale) }
  })
}

// ==================== 备货单（G-03 / F-17，只读两张列表） ====================
// 两条都是「后端已夹好/兜好默认值」的读接口：空值不下发（让服务端默认生效），返回原信封。

/**
 * 备货单聚合：GET /api/merchant/pickup-plan?date=&storeId=（两个参数都可省）
 * date 缺省时后端按「明天」兜底并把生效日期回显在 data.date，页面必须渲染回显值而不是请求值；
 * 归属由服务端 @RequestAttribute 解析，本层不拼任何归属入参。
 * @param {{date?:string, storeId?:string|number}} [query] date 形如 YYYY-MM-DD
 * @returns {Promise<{code,data:{date:string,groups:Array<{storeId:number,storeName:string,orderCount:number,items:Array<{dishId:number,dishName:string,quantity:number}>}>},message:string}>}
 */
export function getMerchantPickupPlan(query) {
  const src = query && typeof query === 'object' ? query : {}
  const data = {}
  if (src.date) data.date = src.date
  // storeId=0 是合法值（后端按 id 过滤，0 命中不了任何店），只有空串/null/undefined 才当「不带这一筛选」
  if (src.storeId !== '' && src.storeId !== null && src.storeId !== undefined) data.storeId = src.storeId
  return request({ url: '/api/merchant/pickup-plan', method: 'GET', data })
}

/**
 * 供货明细分页：GET /api/merchant/order-items?date=&status=&page=&size=
 * status 值域 pending_pickup | completed | all（all = 全部非取消；cancelled 后端一律排除，未知值后端兜底 all），
 * 过滤口径只在服务端一份，页面不得对已加载数据做前端过滤（那样分页语义直接错）。
 * size 后端夹到 [1,50]、page<1 归一 1，两条都是「夹而不拒」并在 data 里回显生效值 —— 翻页要用回显值。
 * 归属由服务端 @RequestAttribute 解析，本层不拼任何归属入参。
 * @param {{date?:string, status?:string, page?:number, size?:number}} [query]
 * @returns {Promise<{code,data:{list:object[],page:number,size:number,total:number,hasMore:boolean},message:string}>}
 */
export function getMerchantOrderItems(query) {
  const src = query && typeof query === 'object' ? query : {}
  const data = {}
  if (src.date) data.date = src.date
  if (src.status) data.status = src.status
  if (src.page) data.page = Number(src.page)
  if (src.size) data.size = Number(src.size)
  return request({ url: '/api/merchant/order-items', method: 'GET', data })
}

// ==================== 缺货登记（F-69） ====================
// 单列一节：本条定性口径与上方那组只读接口不同（200 含「幂等命中」、404 含「不是你的行」），并进去会被读错。

/**
 * 登记缺货：PUT /api/merchant/order-items/{orderItemId}/shortage，**无请求体**
 * （端点不收 @RequestBody，fulfill_status 取值由服务端钉死，故走 noBody 支；超时落写侧档 TIMEOUT_SUBMIT）。
 * 归属由服务端 WHERE 判定：他商家行与不存在的行同为 404「订单不存在」，不给枚举者存在信号。
 * 限流 @RateLimit(10/60s) 的桶身份含方法名 → 与读侧 order-items 那枚桶互不相干，
 * 页面侧因此必须各起一把冷却锁；429 的 data.retryAfterSeconds 原样透出，折算与起闸归页面。
 * 出参 {code:200, data:null, message:'已登记缺货'}，首次与幂等命中同为 200、信封不区分。
 * ⚠ 改的是 order_item.fulfill_status 旁路标记，与订单三态 orders.status 正交，不得据它改订单状态的说法。
 * @param {string|number} orderItemId - 明细行主键（GET order-items 出参的 orderItemId 原值）
 * @returns {Promise<{code:number,data:null,message:string}>}
 */
export function registerShortage(orderItemId) {
  return request({
    url: '/api/merchant/order-items/' + encodeURIComponent(orderItemId) + '/shortage',
    method: 'PUT',
    noBody: true
  })
}

// ==================== 数据看板与营销参与态（G-05 / F-19，两张只读卡） ====================
// 与上方备货单两条同一套纪律：空值不下发（让服务端默认生效）、本层不拼任何归属入参、
// 返回原信封。区别只在「口径全在后端」：窗口天数与金额位数都在 SQL / Service 定形
// （MerchantOrderServiceImpl.moneyText() 出两位小数字符串），本层既不加减也不补零
// ——把口径搬到展示层，G-03 已因此返工过一次。

/**
 * 数据看板：GET /api/merchant/stat?days=（days 可省）
 * days 值域 [1,90]、缺省 7（契约 D-3）：越界后端 400「天数需在 1 到 90 之间」，
 * 本层不夹值也不替页面猜天数（缺省交给 @RequestParam(defaultValue="7")，两处各设默认迟早漂移）；
 * 窗口起点 windowStart 由 SQL 侧同式取回并回显，页面只显示回显值、不自行推算日期（D-3/D-4）。
 * 归属由服务端 @RequestAttribute 解析，本层不拼任何归属入参。
 * 四卡取值逐键用（gmvBeforeCoupon/orderCount/itemCount/refundCount），前端零加减；
 * top[].amount 与 gmvBeforeCoupon 是两位小数字符串（"61.60"），条宽只用它做比例、不参与展示。
 * @param {{days?:number}} [query]
 * @returns {Promise<{code,data:{days:number,windowStart:string,gmvBeforeCoupon:string,orderCount:number,itemCount:number,refundCount:number,top:Array<{dishId:number,dishName:string,quantity:number,amount:string}>},message:string}>}
 */
export function getMerchantStat(query) {
  const src = query && typeof query === 'object' ? query : {}
  const data = {}
  // 0/空串/null/undefined 都不下发：本端点缺省就是 7，硬塞 0 只会换回一条 400
  if (src.days) data.days = Number(src.days)
  return request({ url: '/api/merchant/stat', method: 'GET', data })
}

/**
 * 营销参与态：GET /api/merchant/marketing（无参数，契约 D-6）
 * 出参 {seckill:{count,items:[{dishId,name,price,seckillPrice,limitBuy,onSale,soldCountTotal}]},
 *       freeClaim:{count,items:[{activityId,dishId,name,activityStatus,dailyQuota,dailyClaimed,threshold}]}}。
 * count 是真总数、items 上限 20（不是分页，别在这里凑 page/size）；
 * 本期零参与位是真实形态（merchant 2 名下秒杀与免费领实测各 0 件），空数组不是降级也不是错误。
 * dish.seckill / free_activity 均由平台侧设置，本层没有任何写通道（D-6「本期不出报名口」）。
 * 归属由服务端 @RequestAttribute 解析，本层不拼任何归属入参。
 * @returns {Promise<{code,data:{seckill:{count:number,items:object[]},freeClaim:{count:number,items:object[]}},message:string}>}
 */
export function getMerchantMarketing() {
  return request({ url: '/api/merchant/marketing', method: 'GET' })
}

// ==================== 结算账单（F-120，只读） ====================

/**
 * 结算单列表：GET /api/merchant/settlements?pageNum=&pageSize=（P5-2 分页对齐，对象传参；id 升序——
 * F-120 §1 刻意差异，与运营端 F-125 的 DESC 相反，页面别按 id 顺序自行脑补新旧）
 * 出参 data 是分页对象（商家侧无筛选维度，无 filters 键，这是与 F-125 出参的明确差异）：
 * {list,total,pageNum,pageSize}；list 每行十三键商家自见子集：{id,settleNo,periodStart,periodEnd,
 * gmvAmount,refundAmount,netAmount,orderCount,commission,status,createdAt,reconcileOk,paidChannelAbsent}——
 * 运营管理字段（merchantId/merchantName/gross/refund/net/retain/itemCount）后端不下发。
 * reconcileOk 是服务端实时对账标志（✓ 账面金额/订单数与挂锚明细实时对得上；✗ 对不上=数据异常口径，
 * 页面只转述不推算）；paidChannelAbsent 恒 true（打款通道未接，记账态明示——说明横幅恒显，不依赖它做显隐，
 * 照运营端 Settlements.vue 同一口径）。归属由服务端 @RequestAttribute 解析，本层不拼任何归属入参
 * （merchantId 不收也不发，页码越界/页大小出 [1,100] 后端 400 兜底，缺省 1/20）；
 * 商家对账单无任何写通道（settlement 只增不改，F-118 §3 红线照守）。
 * @param {{pageNum?:number, pageSize?:number}} [params] 分页参数（两键都可省，省即后端缺省 1/20）
 * @returns {Promise<{code,data:{list:Array<object>,total:number,pageNum:number,pageSize:number}}>}
 */
export function getMerchantSettlements(params) {
  const src = params && typeof params === 'object' ? params : {}
  const data = {}
  if (src.pageNum !== undefined && src.pageNum !== null) data.pageNum = src.pageNum
  if (src.pageSize !== undefined && src.pageSize !== null) data.pageSize = src.pageSize
  return request({ url: '/api/merchant/settlements', method: 'GET', data })
}

/**
 * 结算单挂锚明细：GET /api/merchant/settlements/{id}/items（F-120 §2，点行展开时懒加载）
 * 行键照后端：{itemId,dishName,supplyPrice,qty,subtotal,orderId,payStatus,orphan,fulfillStatus}；
 * orphan=true 的行其 orders 行已被删，payStatus 显 null，页面标「订单已不存在」警示（后端不静默丢行）。
 * 他人账单与不存在同为 404「账单不存在」（不泄露他人账单存在性）。
 * @param {number|string} id 结算单 id（列表出参行的 id，非账单号）
 * @returns {Promise<{code,data:Array<object>}>}
 */
export function getMerchantSettlementItems(id) {
  return request({ url: '/api/merchant/settlements/' + encodeURIComponent(id) + '/items', method: 'GET' })
}
