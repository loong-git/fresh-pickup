<template>
  <view class="page-container">
    <!-- ==================== 1. 加载中（首屏占位，避免整页空白） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载佣金钱包…</text>
    </view>

    <!-- ==================== 2. 加载失败（请求没成功，重试有可能成）
             401 不走这里（请求层 rejectUnauthorized 已清态并回登录页）；403 也到不了这里
             （/api/partner/ 闸门是「登录 + 名下有店」，能进工作台就能进本页，口径同 store.vue 的注释）；
             429 起秒级冷却，冷却期内只显秒数 ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="!retryLock" class="error-retry" @click="load(true)">点击重试</text>
        <text v-else class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 3. 就绪：余额卡（含记账态标注）→ 流水列表 → 加载更多 ==================== -->
    <template v-else>
      <!-- 余额卡：记账态恒显标注（F-119 §3「标注『记账态：提现通道未接入』」）——
           佣金余额只被跑批记账增减，本页零任何写入口（无提现/无调额，轨道 B） -->
      <view class="card balance-card">
        <text class="balance-label">佣金余额（元）</text>
        <text class="balance-value">{{ balanceText }}</text>
        <view class="balance-tag-row">
          <text class="balance-tag">记账态：提现通道未接入</text>
        </view>
        <text class="balance-note">佣金由平台结算跑批按订单实付 3% 自动记账（已支付订单入账、退款订单冲回），无需申请</text>
      </view>

      <!-- 流水列表：类型/单号/金额±/余额快照/费率/时间（F-119 §3 逐字六项）。
           空列表不是错误态：从无佣金记录时后端就回空集 + 余额 0（200），如实提示 -->
      <view class="card flow-card">
        <text class="block-title">佣金流水</text>
        <view v-if="!flows.length" class="flow-empty">
          <text class="empty-text">暂无佣金流水</text>
          <text class="flow-empty-sub">有已支付的含供价订单并生成账期后，这里会出现入账记录</text>
        </view>
        <view v-else class="flow-list">
          <view class="flow-item" v-for="(f, i) in flows" :key="flowKey(f, i)">
            <view class="flow-head">
              <text class="flow-type" :class="'flow-type-' + toneOf(f)">{{ typeText(f) }}</text>
              <text class="flow-amount" :class="'flow-amount-' + toneOf(f)">{{ amountText(f) }}</text>
            </view>
            <text class="flow-row">单号：{{ f.orderId }}</text>
            <text class="flow-row">余额快照：{{ moneyText(f.balanceAfter) }} 元</text>
            <text class="flow-row">费率：{{ rateText(f) }}</text>
            <text class="flow-row flow-time">{{ timeText(f) }}</text>
          </view>
        </view>
        <!-- 加载更多：hasMore 是后端现算（offset + 本页行数 < total），本页不做本地估算 -->
        <view v-if="flows.length && hasMore" class="load-more" hover-class="pill-hover" @click="loadMore">
          <text class="load-more-text">{{ loadingMore ? '加载中…' : '加载更多' }}</text>
        </view>
        <text v-if="flows.length && !hasMore" class="flow-end">已显示全部 {{ total }} 条</text>
        <!-- 追加失败降级提示：列表照常展示（已有快照），只如实说最新一页没拉到 -->
        <text v-if="loadMoreFailed" class="flow-refresh-fail">下一页加载失败，请重试</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 我的佣金（F-119 §3）：GET /api/partner/wallet 余额 + 流水分页，只读展示类。
// 页面纪律与到货批次/门店信息同一套：
// ① 状态位单值互斥 loading|error|ready（本页无 empty 态——后端对无钱包主理人回余额 0 + 空流水，
//    「从没记过佣金」是正常态不是错误态，空列表提示放在流水卡内）；
// ② 冷却锁只在 code===429 起（retrySecondsOf 转述后端 data.retryAfterSeconds，取不到回落本地 60）；
// ③ onUnload 成对清定时器；④ 请求全走 @/api/index.js（页内零原生请求）；
// ⑤ 只渲染后端白名单键，缺键就如实不渲染，绝不为了好看前端编数据。
// 分页：追加式加载（loadMore 拼在 flows 尾部），hasMore/total 都取后端回显值；
// 重进页面（onShow）整体重拉，回到第一页——余额是活跃数值，回到本页就该看最新值。
import { getPartnerWallet, retrySecondsOf, coolingText } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/commission/commission'

// biz_type 两值（后端 PartnerWalletFlow 常量收口，前端只做展示映射，未知值原样透出不猜中文名）
const BIZ_GRANT = 'commission_grant'
const BIZ_REVERSAL = 'commission_reversal'
const TYPE_TEXT = { commission_grant: '佣金入账', commission_reversal: '佣金冲回' }

// 429 冷却回落秒数（与读侧 @RateLimit 的 60s 窗口同档，口径同 workspace/store）
const RETRY_FALLBACK_SECONDS = 60

export default {
  data() {
    return {
      // 页面状态单值互斥：loading | error | ready
      viewState: 'loading',
      // GET /api/partner/wallet 的快照（余额 + 分页元信息）
      balance: 0,
      total: 0,
      page: 1,
      size: 20,
      hasMore: false,
      flows: [],
      // error 态文案（后端 message 原文）与错误码（决定主文案是「点太快」还是「加载失败」）
      errorText: '',
      errorCode: 0,
      // 加载更多单飞锁与降级提示（追加失败不清已有列表，只给一行如实提示）
      loadingMore: false,
      loadMoreFailed: false,
      // 429 冷却剩余秒数与定时器（onUnload 成对清；口径同 workspace.vue）
      retryLock: 0,
      retryLockTimer: null
    }
  },
  computed: {
    // 余额两位小数（后端 DECIMAL(10,2)，负值如实显示——冲回余额不足时后端照实记账）
    balanceText() {
      return this.moneyText(this.balance)
    },
    errorLeadText() {
      // 429 这行不带时长，秒数只在闸门那一处（模板 retryLock 倒计时行）出现一次（FF-1 口径）
      if (this.errorCode === 429) return coolingText()
      // 500 是服务端承认自己处理出错，主文案与「查网络」那支分开说（X-2b 口径）
      if (this.errorCode === 500) return '佣金钱包加载失败，服务端处理出错，稍后重试'
      // 只有网络层失败才许出现「网络」字样（api 层 fail 支回 code:-1）
      if (this.errorCode <= 0) return '佣金钱包加载失败，请检查网络后重试'
      return '佣金钱包加载失败，请稍后重试'
    }
  },
  onShow() {
    // 每次进入整体重拉（回到第一页）：佣金余额是跑批写入的活跃数值，回页就该看最新值
    this.load(true)
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底（workspace/store 同款）：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    this.stopRetryLock()
    // #ifdef H5
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
  },
  methods: {
    // ==================== 数据加载 ====================
    async load(resetView) {
      if (this._loading) return
      if (this.retryLock > 0) return
      this._loading = true
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getPartnerWallet({ page: 1, size: this.size })
        const data = (res && res.data && typeof res.data === 'object') ? res.data : {}
        this.applyData(data)
        this.viewState = 'ready'
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 401) {
          // 跳登录页与清态都由请求层做完，这里只停在加载态
          this.viewState = 'loading'
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = code
        if (code === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loading = false
      }
    },
    // 加载更多：只追加 flows 尾部，不整页换态；失败保留已有列表并给一行降级提示
    async loadMore() {
      if (this.loadingMore || !this.hasMore) return
      this.loadingMore = true
      this.loadMoreFailed = false
      try {
        const nextPage = this.page + 1
        const res = await getPartnerWallet({ page: nextPage, size: this.size })
        const data = (res && res.data && typeof res.data === 'object') ? res.data : {}
        // 追加页必须确是下一页（后端回显 page 防乱序），不对就当失败处理，不硬拼
        if (Number(data.page) !== nextPage) {
          this.loadMoreFailed = true
          return
        }
        const more = Array.isArray(data.flows) ? data.flows : []
        this.flows = this.flows.concat(more)
        this.page = nextPage
        this.total = Number(data.total) || this.total
        this.hasMore = data.hasMore === true
        this.balance = this.numOr(data.balance, this.balance)
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 401) return
        this.loadMoreFailed = true
      } finally {
        this.loadingMore = false
      }
    },
    applyData(data) {
      this.balance = this.numOr(data.balance, 0)
      this.total = Number(data.total) || 0
      this.page = Number(data.page) || 1
      this.size = Number(data.size) || 20
      this.hasMore = data.hasMore === true
      this.flows = Array.isArray(data.flows) ? data.flows : []
      this.loadMoreFailed = false
    },
    // ==================== 展示映射（未知值原样透出，不替后端猜中文） ====================
    typeText(f) {
      const t = f && TYPE_TEXT[f.bizType]
      return t || (f && f.bizType) || '未知类型'
    },
    // 入账红（本端色板主红=正向强调，与「余额快照」一起构成对账可读的成对信息）；冲回灰
    toneOf(f) {
      return f && f.bizType === BIZ_GRANT ? 'grant' : 'reversal'
    },
    amountText(f) {
      const v = this.moneyText(f && f.amount)
      if (!f || f.amount === undefined || f.amount === null) return '—'
      return (f.bizType === BIZ_REVERSAL ? '' : '+') + v
    },
    rateText(f) {
      // 费率快照 DECIMAL(5,4)（0.0300）：有值按原样展示四位小数（与流水行快照逐字一致），缺键如实「未返回」
      if (!f || f.rateSnapshot === undefined || f.rateSnapshot === null) return '未返回'
      return String(f.rateSnapshot)
    },
    moneyText(v) {
      const n = Number(v)
      if (!Number.isFinite(n)) return '—'
      return n.toFixed(2)
    },
    timeText(f) {
      const raw = f && f.createdAt
      if (!raw) return ''
      const d = new Date(String(raw).replace(' ', 'T'))
      if (!Number.isFinite(d.getTime())) return String(raw)
      const p = (x) => String(x).padStart(2, '0')
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
    },
    flowKey(f, i) {
      return (f && f.id !== undefined && f.id !== null ? 'f' + f.id : 'i' + i)
    },
    numOr(v, fallback) {
      const n = Number(v)
      return Number.isFinite(n) ? n : fallback
    },
    // ==================== 跳转与 429 冷却（workspace 同款） ====================
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    startRetryLock(err) {
      this.stopRetryLock()
      this.retryLock = retrySecondsOf(err, RETRY_FALLBACK_SECONDS)
      this.retryLockTimer = setInterval(() => {
        this.retryLock--
        if (this.retryLock <= 0) this.stopRetryLock()
      }, 1000)
    },
    stopRetryLock() {
      if (this.retryLockTimer) { clearInterval(this.retryLockTimer); this.retryLockTimer = null }
      this.retryLock = 0
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    onPageWheel(e) {
      let pages = []
      try {
        pages = getCurrentPages() || []
      } catch (err) {
        return
      }
      if (!pages.length || (pages[pages.length - 1] && pages[pages.length - 1].route) !== PAGE_ROUTE) return
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* 多多红单色板：灰底页面 + 白卡（.card/.error-row/.btn-main 等公共类在 styles/common.css，
   本页只补间距与专属字，不动主题变量） */

/* ==================== 1/2. 单卡承载的三态 ==================== */
.loading-card { margin-top: 56rpx; display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.error-retry-cooling { color: #CCCCCC; }

/* ==================== 3. 余额卡 ==================== */
.balance-card {
  margin-top: 24rpx;
  display: flex; flex-direction: column; align-items: center; gap: 12rpx;
  padding: 40rpx 24rpx;
}
.balance-label { font-size: 24rpx; color: #999999; }
.balance-value { font-size: 64rpx; font-weight: 700; color: #E02020; line-height: 1.2; }
.balance-tag-row { display: flex; }
.balance-tag {
  font-size: 22rpx; color: #B88230; background-color: #FDF6EC;
  border: 1rpx solid #FAECD8; border-radius: 8rpx; padding: 6rpx 16rpx; line-height: 1.5;
}
.balance-note { font-size: 22rpx; color: #BBBBBB; line-height: 1.5; text-align: center; }

/* ==================== 4. 流水卡 ==================== */
.flow-card { margin-top: 24rpx; display: flex; flex-direction: column; gap: 16rpx; }
.block-title { font-size: 30rpx; font-weight: 700; color: #333333; line-height: 1.4; }
.flow-empty { display: flex; flex-direction: column; align-items: center; gap: 8rpx; padding: 32rpx 0; }
.flow-empty-sub { font-size: 22rpx; color: #BBBBBB; line-height: 1.5; text-align: center; }
.flow-list { display: flex; flex-direction: column; }
.flow-item {
  display: flex; flex-direction: column; gap: 6rpx;
  padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5;
}
.flow-item:last-child { border-bottom: none; }
.flow-head { display: flex; align-items: center; justify-content: space-between; }
.flow-type {
  font-size: 22rpx; border-radius: 8rpx; padding: 4rpx 12rpx; line-height: 1.5;
  color: #666666; background-color: #F5F5F5;
}
.flow-type-grant { color: #E02020; background-color: #FFF1F0; }
.flow-amount { font-size: 32rpx; font-weight: 700; color: #333333; }
.flow-amount-grant { color: #E02020; }
.flow-amount-reversal { color: #999999; }
.flow-row { font-size: 24rpx; color: #666666; line-height: 1.6; word-break: break-all; }
.flow-time { color: #BBBBBB; }
.load-more {
  margin-top: 8rpx; padding: 16rpx 0;
  display: flex; justify-content: center;
  border: 1rpx solid #EEEEEE; border-radius: 12rpx;
}
/* 按压反馈（arrival.vue 的 .pill-hover 同款，scoped 内自带一份） */
.pill-hover { opacity: .82; }
.load-more-text { font-size: 26rpx; color: #666666; }
.flow-end { font-size: 22rpx; color: #CCCCCC; text-align: center; padding: 8rpx 0; }
.flow-refresh-fail { font-size: 22rpx; color: #E02020; opacity: .8; text-align: center; }

.page-bottom-space { height: 60rpx; }
</style>
