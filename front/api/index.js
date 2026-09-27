// ==================== API 请求封装 ====================
import { BASE_URL } from './config.js'

// 分层超时（T-M1-06 / 裁定#6）：查询类 10s，提交/支付类 20s（POST /api/orders、POST 评价等提交类接口）
const TIMEOUT_QUERY = 10000
const TIMEOUT_SUBMIT = 20000

// ==================== 登录态辅助（M2-T-04） ====================

// 从本地存储读取登录态（store setUser/setClearUser 与 storage 同步，二者一致）；
// 读写全程 try/catch 守卫，存储异常按未登录处理，不影响游客模式
function getStoredUser() {
  try {
    const cached = uni.getStorageSync('user')
    const value = typeof cached === 'string' ? JSON.parse(cached) : cached
    return (value && typeof value === 'object') ? value : null
  } catch (e) {
    return null
  }
}

// 401 统一回调注册器：main.js 启动时注册 → 触发时同步清 Vuex 登录态
// （api 层不反向 import store，避免 main.js ↔ api 循环引用）
let unauthorizedHandler = null
export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = typeof fn === 'function' ? fn : null
}

// 401 统一处理：清本地登录态 + 通知 store + 结构化 reject（仅 reject 不重试，无死循环）
function rejectUnauthorized(reject) {
  try {
    uni.removeStorageSync('user')
  } catch (e) {
    // 存储异常静默
  }
  if (unauthorizedHandler) {
    try {
      unauthorizedHandler()
    } catch (e) {
      // 回调异常不影响 reject
    }
  }
  reject({ code: 401, message: '请先登录' })
}

/**
 * 统一请求封装：
 * - 有 token 时自动携带 Authorization: Bearer <token>（游客模式无 token 不带该头）
 * - res.statusCode===200 且 res.data.code===200 → resolve(res.data)（完整 R 信封 {code,data,message}）
 * - 401（无/伪造/过期 token）→ 清登录态 + reject({code:401,message:'请先登录'})，登录请求本身不带 token 不受影响
 * - 业务失败（code!==200）/ HTTP 异常状态码 → reject({code, message})，调用方须 toast message
 * - 网络异常/超时 → reject({code:-1, message:'网络异常'})，调用方须 toast（全站无静默分支）
 */
const request = (options) => {
  // 提交类判定：POST 一律按提交类给 20s，其余查询 10s
  const isSubmit = (options.method || 'GET').toUpperCase() === 'POST'
  // 登录态注入：本地存储有 token 才带 Authorization 头
  const stored = getStoredUser()
  const token = (stored && typeof stored.token === 'string' && stored.token) ? stored.token : ''
  const header = { 'Content-Type': 'application/json' }
  if (token) header.Authorization = 'Bearer ' + token
  return new Promise((resolve, reject) => {
    uni.request({
      url: BASE_URL + options.url,
      method: options.method || 'GET',
      data: options.data || {},
      timeout: isSubmit ? TIMEOUT_SUBMIT : TIMEOUT_QUERY,
      header,
      success: (res) => {
        const body = (res.data && typeof res.data === 'object') ? res.data : null
        // 401（HTTP 状态码或信封 code 命中）：统一清登录态并 reject，调用方无需重复处理
        if (res.statusCode === 401 || (body && body.code === 401)) {
          rejectUnauthorized(reject)
          return
        }
        if (res.statusCode === 200 && body && body.code === 200) {
          resolve(body)
        } else {
          // 业务校验失败(400)/资源不存在(404)/系统异常(500)/HTTP 状态码异常：
          // 统一结构化 reject，message 优先透出后端人话，调用方 toast 且保留现场可重试
          reject({
            code: body && body.code != null ? body.code : res.statusCode,
            message: (body && body.message) || '服务开小差了，请稍后重试'
          })
        }
      },
      fail: () => {
        // 网络不可达 / 域名解析失败 / 分层超时（H5 下超时走 fail）
        reject({ code: -1, message: '网络异常' })
      }
    })
  })
}

// ==================== 菜品字段增强（M4-T-03 逐字段迁移：真实字段直用，前端合成逻辑删除） ====================
// M4 统一契约：dish 表新增真实列 seckill(TINYINT)/seckill_price/sold_count/limit_buy/tags/good_rate，
// 后端接口响应直接携带 seckill/seckillPrice/soldCount/limitBuy/tags/goodRate，前端逐字段改用真实值；
// 仅保留两类展示层派生（非后端契约字段）：
// - soldText：由 soldCount 计算的"已卖x件/x.x万件"文案
// - cutText/rushText：由真实价格差/销量派生的氛围文案

// tags 兼容归一：后端 tags 列可能返回数组或逗号分隔字符串，统一归一为 string[]
function normalizeDishTags(tags) {
  if (Array.isArray(tags)) return tags.filter(t => typeof t === 'string' && t)
  if (typeof tags === 'string' && tags.trim()) {
    return tags.split(/[,，、|]/).map(s => s.trim()).filter(Boolean)
  }
  return []
}

// 销量 -> 文案：>=10000 展示"x.x万件"量级，其余展示原数；无销量（0/脏数据）返回空串由模板隐藏
function soldCountToText(n) {
  const c = Number(n)
  if (!Number.isFinite(c) || c <= 0) return ''
  return c >= 10000 ? `已卖${(c / 10000).toFixed(1)}万件` : `已卖${c}件`
}

/**
 * 菜品字段增强（M4 迁移后）：后端已有字段直接使用，缺省安全兜底，不再合成假数据
 * seckill:boolean（后端 TINYINT 0/1 或 boolean 归一）
 * seckillPrice:number|null / limitBuy:number|null / soldCount:number / tags:string[] / goodRate:string
 * cutText:string（秒杀时由 price-seckillPrice 派生"直降x元"）/ soldText:string（由 soldCount 计算）
 * rushText:string（由 soldCount 阈值派生"正在疯抢/即将抢光"）
 * @param {object} d - 后端原始菜品对象
 */
export function decorateDish(d) {
  // 非法入参原样返回，避免崩溃
  if (!d || typeof d !== 'object') return d
  const price = Number(d.price) || 0
  // M4-T-03：后端真实字段逐项直用（seckill TINYINT 0/1 归一为 boolean）
  const seckill = d.seckill === true || Number(d.seckill) === 1
  const rawSeckillPrice = Number(d.seckillPrice)
  const seckillPrice = d.seckillPrice != null && Number.isFinite(rawSeckillPrice) ? rawSeckillPrice : null
  const rawLimit = Number(d.limitBuy)
  const limitBuy = d.limitBuy != null && Number.isFinite(rawLimit) && rawLimit > 0 ? Math.floor(rawLimit) : null
  const soldCount = Number(d.soldCount) || 0
  const soldText = soldCountToText(soldCount)
  const tags = normalizeDishTags(d.tags)
  const goodRate = typeof d.goodRate === 'string' ? d.goodRate.trim() : ''
  // 展示层派生文案（由真实价格/销量计算，非后端契约字段）
  const cutText = seckill && seckillPrice != null && price > seckillPrice ? `直降${(price - seckillPrice).toFixed(2)}元` : ''
  const rushText = soldCount >= 10000 ? '正在疯抢' : '即将抢光'
  return {
    ...d,
    seckill,
    seckillPrice,
    cutText,
    limitBuy,
    soldCount,
    soldText,
    rushText,
    tags,
    goodRate
  }
}

// 增强列表接口返回体：兼容 { code, data:[...] } 包装与直接返回数组两种形态
function decorateDishListResponse(res) {
  if (Array.isArray(res)) return res.map(decorateDish)
  if (res && Array.isArray(res.data)) {
    return { ...res, data: res.data.map(decorateDish) }
  }
  return res
}

// 增强详情接口返回体：data 为单个菜品对象时增强之（404 等无 data 场景原样返回）
function decorateDishDetailResponse(res) {
  if (res && res.data && typeof res.data === 'object' && !Array.isArray(res.data)) {
    return { ...res, data: decorateDish(res.data) }
  }
  return res
}

// ==================== 菜品接口 ====================

/**
 * 获取菜品列表（返回结果中的每个菜品已经过 decorateDish 前端增强）
 * @param {string} category - 分类: 'meat' | 'vegetable'
 */
export function getDishes(category) {
  return request({
    url: '/api/dishes',
    method: 'GET',
    data: { category }
  }).then(decorateDishListResponse)
}

/**
 * 获取菜品详情（返回结果中的菜品已经过 decorateDish 前端增强）
 * @param {number} id - 菜品ID
 */
export function getDishDetail(id) {
  return request({
    url: `/api/dishes/${id}`,
    method: 'GET'
  }).then(decorateDishDetailResponse)
}

// ==================== 订单接口 ====================

/**
 * 提交订单（POST 提交类，20s 超时）
 * body 契约 OrderCreateDTO（M4 契约扩展，均为可选字段）：
 * { clientRequestId(必填,≤64), phone(必填,1[3-9]\d{9}), items:[{dishId(必填), quantity(1~限购)}],
 *   couponId?:number - 用户优惠券 id（服务端校验归属/available/满减门槛后抵扣重算，不满足 400"未满足满减条件"）,
 *   storeId?:number  - 自提门店 id（orders 落 store_id + store_name/store_address 快照列） }
 * 金额由服务端按 dish 现价重算；同 clientRequestId 重复提交幂等返回首次订单
 * @param {object} orderData - OrderCreateDTO
 */
export function submitOrder(orderData) {
  return request({
    url: '/api/orders',
    method: 'POST',
    data: orderData
  })
}

/**
 * 分页查询当前登录用户订单（M2 归属升级：后端按 token 解析的 userId 过滤，phone 查询参数废弃）
 * @param {number} pageNum - 页码（从 1 起）
 * @param {number} pageSize - 每页条数（默认 10）
 * @returns {Promise<{code,data:{list:Array,total:number}}>}
 */
export function getOrders(pageNum = 1, pageSize = 10) {
  return request({
    url: '/api/orders',
    method: 'GET',
    data: { pageNum, pageSize }
  })
}

/**
 * 取消订单（仅 pending_pickup 可取消，取消回补库存；其他状态后端 code:400）
 * @param {string} id - 订单号（雪花字符串）
 */
export function cancelOrder(id) {
  return request({
    url: `/api/orders/${id}/cancel`,
    method: 'POST'
  })
}

// ==================== 登录/支付接口（M2 统一契约） ====================

/**
 * 发送短信验证码（匿名可调）：dev 环境后端固定验证码 123456（配置项 auth.dev-code，预留云短信）
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
 * 手机号+验证码登录（新手机号后端自动静默注册）；验证码错后端 code:400"验证码错误"
 * F-01：inviteCode 可选透传（LoginDTO 可选字段）——仅验证码登录创建新账号时后端绑定邀请关系并发新人券；
 * 老用户/密码登录携带邀请码一律被后端忽略（密码登录函数不带该参数）
 * @param {string} phone - 1[3-9] 开头 11 位手机号
 * @param {string} code - 短信验证码
 * @param {string} [inviteCode] - 邀请码（可选，来自 login 页 options.invite 横幅携带）
 * @returns {Promise<{code,data:{token,user:{id,phone},isNew?:boolean,hasPassword?:boolean}}>}
 */
export function login(phone, code, inviteCode) {
  const data = { phone, code }
  if (inviteCode) data.inviteCode = inviteCode
  return request({
    url: '/api/auth/login',
    method: 'POST',
    data
  })
}

/**
 * 手机号+密码登录（匿名可调）：密码错误后端 code:400 message 人话透出；
 * 用户未设置过密码后端 code:400 且 message 含「请先使用验证码登录」，调用方直接 toast
 * @returns {Promise<{code,data:{token,user:{id,phone},hasPassword:boolean}}>}
 */
export function loginByPassword(phone, password) {
  return request({
    url: '/api/auth/login-password',
    method: 'POST',
    data: { phone, password }
  })
}

/**
 * 设置登录密码（需登录，token 由 request 层自动携带 Authorization 头）：
 * 后端校验 6-20 位；已设过密码可重复调用覆盖（幂等语义由后端保证）
 * @param {string} password - 6-20 位密码
 */
export function setPassword(password) {
  return request({
    url: '/api/auth/set-password',
    method: 'POST',
    data: { password }
  })
}

/**
 * 模拟支付（需登录+本人订单）：pay_status=1、pay_time=now；
 * 幂等——已支付订单重复调用直接返回成功，不重复入账
 * @param {string} orderId - 订单号（雪花字符串）
 */
export function mockPay(orderId) {
  return request({
    url: `/api/pay/${orderId}/mock-pay`,
    method: 'POST'
  })
}

/**
 * 查询我的邀请信息（登录，F-01）：GET /api/invite/me
 * → R{code,data:{inviteCode,inviteUrl,invitedCount,completedCount,
 *    records:[{phoneMasked,statusText,createdAt,rewardedAt}]}}
 * - inviteCode 8 位去混淆字符集，首次调用后端惰性生成，重复调用幂等
 * - inviteUrl 为后端拼的相对路径（#/pages/login/login?invite=<code>），
 *   完整链接由前端补 origin（invite 页按契约 location.origin + '/#/pages/login/login?invite=' + code 自拼）
 * - records 按 created_at 倒序上限 50；phoneMasked 已脱敏（138****1235）
 * - statusText：待完成首单 | 已完成（status 0=注册未完成首单 1=已完成首单）
 * 401 由 request 层统一清登录态并 reject
 */
export function getInviteInfo() {
  return request({
    url: '/api/invite/me',
    method: 'GET'
  })
}

/**
 * 查询我的邀请现金信息（登录，F-04）：GET /api/invite/cash
 * → R{code,data:{balance,frozenTotal,totalEarned,canWithdraw,withdrawMin,
 *    flows:[{type,status,amount,balanceAfter,remark,createdAt}]}}
 * - balance 可提现余额 / frozenTotal 冻结中（type=1 且 status=0 求和）/ totalEarned 累计已入账
 * - canWithdraw: balance ≥ withdrawMin(20) 可提现全部余额；提现口径「满 20 提全部余额」
 * - flows 按 createdAt 倒序上限 50：type 1=邀请奖励（status 0=冻结 1=已入账）2=提现（支出）
 * - 演示环境提现为模拟到账（明示文案由 cash 页展示），401 由 request 层统一清登录态并 reject
 */
export function getCashInfo() {
  return request({
    url: '/api/invite/cash',
    method: 'GET'
  })
}

/**
 * 提现邀请现金（登录，POST 提交类 20s 超时，后端限流 1 次/分）：POST /api/invite/cash/withdraw
 * 定稿口径「满 20 可提现全部余额」：成功后 balance 置 0 并产生 type=2 流水（amount=全部余额）
 * 失败语义：balance<20 后端 code:400 且 message 为差额文案（「还差 X 元可提现」），调用方须 toast；
 * 限流/并发双提后端 code:400 人话透出，401 由 request 层统一清登录态并 reject
 * → R{code,data:{balance:0, ...}}
 */
export function withdrawCash() {
  return request({
    url: '/api/invite/cash/withdraw',
    method: 'POST'
  })
}

// ==================== 门店/秒杀场次/优惠券接口（M4 统一契约） ====================

/**
 * 获取门店列表（匿名可调）：GET /api/stores → R{code,data:[{id,name,address,service,status}]}
 * status：'open' 营业 | 'closed' 停业（停业门店前端置灰不可选）
 * 失败时由调用方回退本地兜底门店数据
 */
export function getStores() {
  return request({
    url: '/api/stores',
    method: 'GET'
  })
}

/**
 * 获取当前秒杀场次（匿名可调）：GET /api/seckill/current
 * → R{code,data:{serverTime, start:'HH:mm', end:'HH:mm', dishes:[{id,name,price,seckillPrice,soldCount,limitBuy,image,...}]}}
 * serverTime 为服务端时钟（前端倒计时基准，改本机时钟不影响）；end 为本场结束时刻，
 * end 过点（切场）后前端重新拉取本接口
 */
export function getSeckillCurrent() {
  return request({
    url: '/api/seckill/current',
    method: 'GET'
  })
}

/**
 * 查询当前登录用户优惠券（登录）：GET /api/coupons
 * → R{code,data:[{id,userId,couponKey,threshold,amount,status:'available'|'used',orderId,createdAt}]}
 * 注：M4 契约草案为 /api/coupons/my，后端实现定为 /api/coupons（claim 同级路径自查后对齐）；
 * 后端返回含 used 券，调用方按 status==='available' 过滤可用
 */
export function getMyCoupons() {
  return request({
    url: '/api/coupons',
    method: 'GET'
  })
}

/**
 * 领取优惠券（登录）：POST /api/coupons/claim body{couponKey}
 * 固定模板：coupon_1 满19减4 / coupon_2 满30减5 / coupon_3 满50减21，发券到 user_coupon；
 * 同 key 同人仅 1 张可用（重复领取后端 400，message 透出）
 * @param {string} couponKey - 券模板标识（coupon_1/coupon_2/coupon_3）
 */
export function claimCoupon(couponKey) {
  return request({
    url: '/api/coupons/claim',
    method: 'POST',
    data: { couponKey }
  })
}

// ==================== 免费领商品接口（M5：每日 0 元限量领 1 份） ====================

/**
 * 获取今日免费商品（匿名可调）：GET /api/free/current（URL 不变，data 扩展 M8 契约）
 * → R{code,data:{activityStatus, remaining, dish(默认免费商品=候选池第一个), threshold,
 *    todayPaid(登录态当日实付累计,非登录 0), remainingToThreshold(登录态 max(0,threshold-todayPaid)),
 *    pool:[{dishId,name,image,unit,price,emoji,bgColor}](候选池全量),
 *    claimed(登录态今日已领 boolean,非登录 false), claimedOrder(登录态已领时 0 元单摘要或 null),
 *    quota, claimedCount, status(旧口径保留)}}
 * - status/activityStatus 非 'online' 表示活动未开放
 * - claimedToday（旧口径已领标记）：登录时后端下发，匿名/未领缺失，调用方按假值兜底
 */
export function freeCurrent() {
  return request({
    url: '/api/free/current',
    method: 'GET'
  })
}

/**
 * 领取今日免费商品（登录，POST 提交类 20s 超时）：POST /api/free/claim
 * M8 契约升级：请求体 { dishId }（必填，须在当日免费商品候选池 pool 内，否则后端 400「无效的免费商品」；
 * 当日实付未满 threshold 时后端 400，message 形如「再挑选30.01元，今天带走免费商品」）。
 * 手机号/地址等归属字段仍一律服务端按 token 落库，不信任前端其他字段
 * → R{code,data:{...0元订单}}（totalPrice=0、已自动支付，同下单/收银台订单结构）
 * 失败语义：重复领取/未达门槛/无效商品/已抢完/活动未开放/库存不足均 code:400 且 message 人话透出，调用方须 toast；
 * 401 由 request 层统一清登录态并 reject
 * @param {number|string} dishId - 用户在候选池中选中的免费商品 id（free.vue localStorage 'freeSelectedDish' 同源）
 */
export function freeClaim(dishId) {
  return request({
    url: '/api/free/claim',
    method: 'POST',
    data: { dishId }
  })
}

/**
 * 查询当前登录用户领取记录（登录，token 由 request 层自动携带，分页参数同 getOrders）：
 * GET /api/free/my-claims → R{code,data:{list:[{id,dishId,dishName,orderId,claimDate,status,...}],total}}
 * status 为关联订单状态（pending_pickup/completed/cancelled，口径同订单页）
 * @param {number} pageNum - 页码（从 1 起）
 * @param {number} pageSize - 每页条数（默认 10）
 */
export function freeMyClaims(pageNum = 1, pageSize = 10) {
  return request({
    url: '/api/free/my-claims',
    method: 'GET',
    data: { pageNum, pageSize }
  })
}

// ==================== 评价接口 ====================

/**
 * 获取菜品评价列表
 * @param {number} dishId - 菜品ID
 */
export function getReviews(dishId) {
  return request({
    url: `/api/dishes/${dishId}/reviews`,
    method: 'GET'
  })
}

/**
 * 提交评价
 * @param {number} dishId - 菜品ID
 * @param {object} reviewData - 评价数据 { rating, content, reviewer }
 */
export function submitReview(dishId, reviewData) {
  return request({
    url: `/api/dishes/${dishId}/reviews`,
    method: 'POST',
    data: reviewData
  })
}

/**
 * 查询当前登录用户的全部评价（登录，token 由 request 层自动携带，写法对照 getMyCoupons）：
 * GET /api/reviews/mine → R{code,data:[{id,dishId,dishName,content,auditStatus,createdAt,...}]}
 * - dishName 由服务端按 dish_id 填充；按评价时间倒序
 * - auditStatus：null 正常（已过审/历史正常评价）/ 'pending' 命中软词待人工复核
 *   （驳回为后端物理删除，本列表不会出现；待审评价本人可见）
 * - createdAt 为评价时间（契约键），与实体风格键 createTime 同值，按需取用
 */
export function getMyReviews() {
  return request({
    url: '/api/reviews/mine',
    method: 'GET'
  })
}

export default {
  decorateDish,
  getDishes,
  getDishDetail,
  submitOrder,
  getOrders,
  cancelOrder,
  sendCode,
  login,
  loginByPassword,
  setPassword,
  mockPay,
  getInviteInfo,
  getCashInfo,
  withdrawCash,
  getStores,
  getSeckillCurrent,
  getMyCoupons,
  claimCoupon,
  freeCurrent,
  freeClaim,
  freeMyClaims,
  getReviews,
  submitReview,
  getMyReviews
}
