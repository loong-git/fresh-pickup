<template>
  <view class="page-container">
    <!-- ==================== 1. 合规提示（逐字两句，位置在页顶：先说清「这不是报名表」再看数） ====================
             本期后端零报名口（D-6）：出参只是「平台把我家哪些菜设成了秒杀 / 哪些菜进了免费领池」的事实快照。
             这句话摆在页顶而不是页尾，是因为商家最自然的读法是「看到活动 = 我能参加」，
             少了这一句就会拿着这一屏去问客服「怎么报名」 -->
    <view class="card notice-card">
      <text class="notice-lead">参与位由平台设置，商家端只读</text>
      <text class="notice-sub">报名与退出随二期商家报名流开放</text>
    </view>

    <!-- ==================== 2. 加载中（首屏与 onShow 静默重拉共用；已有内容时不整页换态） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载营销参与态…</text>
    </view>

    <!-- ==================== 3. 403 引导态（与错误态分开：确定性失败，重试必然还是同一枚错误） ====================
             D-9 让本端点读侧也判停业，故 403 有两种来源（非商家主体 / 商家非 active），
             两者唯一有用的出口都是回工作台看账号状态 -->
    <view v-else-if="viewState === 'forbidden'" class="card guide-card">
      <text class="guide-title">暂无权限查看营销参与态</text>
      <text class="guide-text">当前账号不是可查看参与态的商家主体，或商家已停业，请回工作台确认账号状态</text>
      <text v-if="forbiddenMessage" class="guide-tip">{{ forbiddenMessage }}</text>
      <text class="guide-link" @click="goWorkspace">回工作台</text>
    </view>

    <!-- ==================== 4. 错误态（≠ 空态：空态是后端确认「一个参与位都没有」，错误态是根本没拿到数据） ====================
             两层式照 pickup-plan.vue：主文案是人话，后端 message 原文降为第二行小字 -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="loadMarketing(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 5. 就绪：两块参与态（本仓商家现均 0 件，空态就是本期真实形态，绝不造数） ==================== -->
    <template v-else>
      <!-- ---------- 5.1 秒杀参与态 ---------- -->
      <view class="card block-card">
        <view class="card-head">
          <text class="card-title">秒杀参与态</text>
          <text class="count-tag">已参与 {{ countText(seckillCount) }} 件</text>
        </view>
        <!-- count 是真总数、items 上限 20（后端 LIMIT 20，不是分页）：两者不等时必须如实说「只展示前 20 件」，
             否则商家会把屏上 20 行当成全部参与量 -->
        <text v-if="seckillTruncated" class="truncate-line">共 {{ seckillCount }} 件，列表展示前 20 件</text>

        <view class="mk-item" v-for="(it, i) in seckillItems" :key="rowKey('d', it.dishId, i)">
          <text class="mk-name">{{ nameOf(it.name) }}</text>
          <view class="mk-line">
            <text class="mk-kv">原价 <text class="mk-v">{{ moneyText(it.price) }}</text></text>
            <text class="mk-kv">秒杀价 <text class="mk-v mk-v-price">{{ moneyText(it.seckillPrice) }}</text></text>
            <text class="mk-kv">限购 <text class="mk-v">{{ intText(it.limitBuy) }}</text> 件</text>
          </view>
          <view class="mk-line">
            <text class="mk-kv">上架状态 <text class="mk-v">{{ saleText(it.onSale) }}</text></text>
            <!-- D-7：dish.sold_count 是历史累计（由支付/退款链路累加），与看板窗口无关；
                 标签里就把「历史累计」写出来，缺这几个字商家会把它读成近 7 天卖了多少 -->
            <text class="mk-kv">历史累计销量 <text class="mk-v">{{ intText(it.soldCountTotal) }}</text> 件（非本窗口）</text>
          </view>
        </view>

        <!-- 空态文案写实（D-11）：这一句是「平台没给我设过秒杀」的事实，不是系统没数据 -->
        <view v-if="!seckillItems.length" class="empty-row">
          <text class="empty-text">本店当前没有平台设置的秒杀商品</text>
        </view>
      </view>

      <!-- ---------- 5.2 免费领参与态 ---------- -->
      <view class="card block-card">
        <view class="card-head">
          <text class="card-title">免费领参与态</text>
          <text class="count-tag">已参与 {{ countText(freeClaimCount) }} 件</text>
        </view>
        <text v-if="freeClaimTruncated" class="truncate-line">共 {{ freeClaimCount }} 件，列表展示前 20 件</text>

        <view class="mk-item" v-for="(it, i) in freeClaimItems" :key="rowKey('a', it.activityId, i)">
          <text class="mk-name">{{ nameOf(it.name) }}</text>
          <view class="mk-line">
            <!-- 活动状态未知值原样透出（不写死 online/offline 二分支）：脏值要能被当场看见，
                 前端替后端猜一个中文说法就等于把数据问题藏成"看着挺正常" -->
            <text class="mk-kv">活动状态 <text class="mk-v">{{ statusText(it.status) }}</text></text>
            <text class="mk-kv">今日额度 <text class="mk-v">{{ intText(it.dailyQuota) }}</text> 份</text>
            <text class="mk-kv">今日已领 <text class="mk-v">{{ intText(it.dailyClaimed) }}</text> 份</text>
          </view>
          <view class="mk-line">
            <text class="mk-kv">满额门槛 <text class="mk-v mk-v-price">{{ moneyText(it.threshold) }}</text></text>
            <text class="mk-kv">候选池 <text class="mk-v">{{ poolText(it.inPool) }}</text></text>
          </view>
        </view>

        <!-- 同上：免费领没有商家报名表，参与位就是「自家菜被平台放进了 free_activity」，0 条就是没进池 -->
        <view v-if="!freeClaimItems.length" class="empty-row">
          <text class="empty-text">本店商品未进入免费领池</text>
        </view>
      </view>

      <!-- 刷新时刻一行（与工作台「资料刷新于」同用途）：让商家确认 onShow 的重拉真的发生过 -->
      <text v-if="refreshLine" class="refresh-line">{{ refreshLine }}</text>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端营销参与态（G-05 / F-19 只读页）：GET /api/merchant/marketing 的两块参与快照。
// 三条页面纪律：① 只渲染出参，页内零判断零合成（count 与 items 各说各的，不相乘不相减）；
// ② 归属由服务端 @RequestAttribute 解析，本页不发任何 merchantId，也没有任何写通道（D-6 本期无报名口）；
// ③ 四态单值互斥 loading | ready | forbidden | error，不用多个布尔叠加（照 workspace.vue 的 viewState）。
// 401 一律由请求层清态回登录页，本页不判 401。文案红线（RE）：不出现任何资金含义的读数词——
// mock 支付无资金流，这里的「门槛/秒杀价」都是平台设置的售卖参数，不是钱款往来。
import { getMerchantMarketing } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/marketing/marketing'

// 上架状态说法与后端列注释同源（init.sql:78 dish.on_sale「1上架/0下架」），出参是数字 0/1 不是布尔
const ON_SALE_TEXT = { 1: '在售', 0: '已下架' }

// 免费领活动状态（init.sql:252 free_activity.status「online进行/offline下线」）；
// 值域外/空值一律原样透出（见 methods.statusText），不替后端猜中文名
const FREE_STATUS_TEXT = { online: '进行中', offline: '已下线' }

// 429 冷却秒数：本端点限流档是 USER 30 次 / 60 秒（契约 D-10），故冷却取窗口长度 60。
// 为什么不能像主理人端那样按后端 data.retryAfterSeconds 起锁：商家端请求层（RA 红线一字不动）
// 的 reject 只带 {code,message} 不带 data，页面拿不到后端剩余秒数，本地按窗口取值是唯一不猜的路。
const RETRY_COOLING_SECONDS = 60

// FE-10 同页 429 只留一句说法（错误卡主文案一处实现；冷却中的重试按钮只显示剩余秒数，不重说一遍句式）
function coolingText(seconds) {
  const n = Number(seconds)
  return '操作过于频繁，' + (Number.isFinite(n) && n > 0 ? '请 ' + n + 's 后再试' : '请稍后再试')
}

export default {
  data() {
    return {
      // 两块出参原样收下（count 是真总数、items 是后端 LIMIT 20 的明细），页内不做任何加工
      seckillCount: null,
      seckillItems: [],
      freeClaimCount: null,
      freeClaimItems: [],
      // 四态互斥：loading | ready | forbidden | error
      viewState: 'loading',
      errorText: '',
      errorCode: 0,
      // 403 引导态透出后端原文
      forbiddenMessage: '',
      // 本次成功时刻（ms），仅驱动「读数刷新于」一行
      refreshAt: 0,
      // 429 冷却剩余秒数（>0 时重试按钮点不动）与驱动它的定时器：与 pickup-plan.vue 同口径，
      // 限流态下的「重试」必须有秒级闸门，否则每次点都在给这个桶续命
      retryLock: 0,
      retryLockTimer: null
    }
  },
  computed: {
    // 「共 N 件，列表展示前 20 件」：count 是真总数、items 是后端 LIMIT 20 的展示条（不是分页），
    // 两者不等就说明明细被裁过 —— 判据用「不等」而不是「大于」：出参自相矛盾（count < items.length）
    // 同样得让商家当场看见，藏着等客服来问就成数据问题了
    seckillTruncated() {
      return this.differsFromShown(this.seckillCount, this.seckillItems)
    },
    freeClaimTruncated() {
      return this.differsFromShown(this.freeClaimCount, this.freeClaimItems)
    },
    // 「读数刷新于 HH:mm」：这一行存在的意义是让商家（和验收）确认 onShow 的那次静默重拉真发生过
    refreshLine() {
      return this.refreshAt ? '读数刷新于 ' + this.formatClock(this.refreshAt) : ''
    },
    errorLeadText() {
      if (this.errorCode === 429) return coolingText(this.retryLock)
      if (this.errorCode === 404) return '营销参与态接口暂不可用，可能是后端服务还没更新，请稍后重试'
      return '营销参与态加载失败，请检查网络后重试'
    },
    // 重试有没有可能成功：403 已单独走 forbidden 态，到这里都是「等一下或修好就能成」
    errorRetryable() {
      return this.errorCode !== 403
    }
  },
  onShow() {
    // 每次进入静默重拉（平台侧置位改了要回页即可见），带 in-flight 守卫防连点；
    // 已是 ready 时不切加载态，避免回页闪一次白卡把上一轮的数盖掉
    this.loadMarketing()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    // #ifdef H5
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
    // 冷却计时器随页销毁一起清：setInterval 挂在 window 上，不摘就会在页面卸载后
    // 继续每秒写一次已死组件的状态（与 startRetryLock 成对，照 pickup-plan.vue:169-179）
    this.stopRetryLock()
  },
  methods: {
    // ==================== 数据加载 ====================
    // @param {boolean} [forceView] 错误卡重试时强制退回加载态（首屏失败后卡片是空的，不必静默）
    async loadMarketing(forceView) {
      // 并发守卫：onShow 重拉 + 错误卡重试 + 「点击重试」连点可叠加，慢失败不能盖掉快成功
      if (this._loadingMarketing) {
        this._reloadQueued = true
        if (forceView) this._queuedView = true
        return
      }
      this._loadingMarketing = true
      if (forceView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getMerchantMarketing()
        const data = (res && res.data) || {}
        const seckill = (data.seckill && typeof data.seckill === 'object') ? data.seckill : {}
        const freeClaim = (data.freeClaim && typeof data.freeClaim === 'object') ? data.freeClaim : {}
        // count 原样收（null 就是「后端没回这一键」，显示层出 '--'，不兜 0 —— 兜 0 会把「没回」说成「一个都没参与」）
        this.seckillCount = seckill.count
        this.seckillItems = Array.isArray(seckill.items) ? seckill.items : []
        this.freeClaimCount = freeClaim.count
        this.freeClaimItems = Array.isArray(freeClaim.items) ? freeClaim.items : []
        this.errorText = ''
        this.errorCode = 0
        this.forbiddenMessage = ''
        this.refreshAt = Date.now()
        // 两块都空也仍是 ready：本仓 merchant 2 名下秒杀与免费领实测各 0 件（G8），
        // 空态是服务端确认过的事实，不是「没拿到数据」，绝不能把这一屏换成错误卡
        this.viewState = 'ready'
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 403) {
          // D-9 读侧停业闸门 / 非商家主体：确定性失败，重试无望，给「回工作台」而不是重试按钮
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
          this.errorText = ''
          this.errorCode = 403
          this.stopRetryLock()
          this.viewState = 'forbidden'
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = code
        // 只有 429 起冷却锁（母版 pickup-plan.vue:227-228）：其余失败不锁，避免把网络抖动也锁成 60 秒
        if (code === 429) this.startRetryLock()
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loadingMarketing = false
        if (this._reloadQueued) {
          const asView = this._queuedView === true
          this._reloadQueued = false
          this._queuedView = false
          this.loadMarketing(asView)
        }
      }
    },
    // ==================== 渲染辅助 ====================
    // 件数：count 缺失出 '--'（见 loadMarketing 注释）；有值原样透出，页面不与 items.length 取大取小
    countText(count) {
      if (count === null || count === undefined || count === '') return '--'
      return String(count)
    },
    // 「总数与展示条不等」的判据（两处共用，别在 computed 里各写一遍）：
    // count 不是有限数（后端没回这一键）时不宣称被截断 —— 那时屏上挂的是 '--'，
    // 再说一句「共 -- 件，列表展示前 20 件」就是把同一个缺失值解释了两遍
    differsFromShown(count, items) {
      if (count === null || count === undefined || count === '') return false
      const n = Number(count)
      if (!Number.isFinite(n)) return false
      return n !== (Array.isArray(items) ? items.length : 0)
    },
    intText(v) {
      if (v === null || v === undefined || v === '') return '--'
      return String(v)
    },
    // 金额：后端已 setScale(2) 定形成串（"6.60"），这里原样透出、绝不 toFixed/补零
    // （把口径搬到展示层，G-03 已因此返工过一次）。可空列出 null（如 dish.seckill_price 真 NULL）时
    // 显示「未设置」而不是 0.00 —— 那是「平台只打了标记没填价」的事实形态，兜 0 会要商家按 0 元理解
    moneyText(v) {
      if (v === null || v === undefined || v === '') return '未设置'
      return String(v)
    },
    nameOf(v) {
      // 与备货单/明细页同词：菜名缺失不能渲染成空白行
      return (v === null || v === undefined || v === '') ? '未命名商品' : String(v)
    },
    saleText(v) {
      // 值域外（脏值/缺字段）原样透出，不替后端猜「在售」
      const n = Number(v)
      if (Number.isFinite(n) && ON_SALE_TEXT[n] !== undefined) return ON_SALE_TEXT[n]
      return (v === null || v === undefined || v === '') ? '--' : String(v)
    },
    statusText(v) {
      // 未知值兜底成原样字符串（不写死二分支）：见 FREE_STATUS_TEXT 的注释
      const s = (v === null || v === undefined) ? '' : String(v)
      if (!s) return '--'
      return FREE_STATUS_TEXT[s] || s
    },
    poolText(v) {
      // inPool 是 EXISTS() 出来的 0/1（后端按本仓 flag 惯例出数字不出布尔）
      const n = Number(v)
      if (n === 1) return '在候选池'
      if (n === 0) return '不在候选池'
      return '--'
    },
    // 行 key：dishId / activityId 理论上有值；缺失（脏值）时退化到行号，保证 :key 不重复
    rowKey(prefix, id, i) {
      return (id === null || id === undefined || id === '') ? 'x' + prefix + i : prefix + id
    },
    formatClock(ms) {
      const d = new Date(ms)
      if (!Number.isFinite(d.getTime())) return ''
      const p = (n) => (n < 10 ? '0' : '') + n
      // 只取到分钟：秒级跳动会让商家以为「页面在自己刷」，而这一行要证明的是 onShow 那一次重拉
      return p(d.getHours()) + ':' + p(d.getMinutes())
    },
    // ==================== 跳转 ====================
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    // ==================== 429 冷却（照 pickup-plan.vue:328-339，秒级禁用范式） ====================
    startRetryLock() {
      this.stopRetryLock()
      this.retryLock = RETRY_COOLING_SECONDS
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
    // 两道守卫缺一不可（照 pickup-plan.vue:350-367）：
    // ① 栈顶守卫：监听挂在 document 上，而 navigateTo 不触发上一页 onUnload —— 进别的页后本页 handler
    //    仍挂着，两份 handler 同时驱动 body.scrollTop 会让滚轮一次滚两倍。
    // ② 弹层放行：modal/toast 打开时滚轮该归弹层，不能 preventDefault 去滚背景。
    //    本页没有 textarea/input，故不带表单控件那一段放行清单。
    onPageWheel(e) {
      // getCurrentPages 在卸载竞态下可能拿不到栈，拿不到就不接管滚动——绝不能在这里 preventDefault 把滚轮吞掉
      let pages = []
      try {
        pages = getCurrentPages() || []
      } catch (err) {
        return
      }
      if (!pages.length || (pages[pages.length - 1] && pages[pages.length - 1].route) !== PAGE_ROUTE) return
      const t = e.target
      const el = t && t.nodeType === 1 ? t : (t && t.parentElement)
      if (el && typeof el.closest === 'function'
        && el.closest('.uni-actionsheet, .uni-modal, .uni-toast, .uni-sample-toast, .uni-picker-container')) {
        return
      }
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* 多多红单色板：灰底 + 白卡（.card/.card-head/.card-title/.error-row/.empty-row/.guide-card/.guide-link 取自 common.css） */

/* ==================== 1. 合规提示（页顶常驻，不随滚动消失的那一句） ==================== */
.notice-card {
  margin-top: 24rpx; padding: 20rpx 24rpx;
  background-color: #FFF7F0; border: 1rpx solid #FFE3E0;
}
.notice-lead { font-size: 28rpx; font-weight: 700; color: #E02020; line-height: 1.5; }
.notice-sub { display: block; margin-top: 8rpx; font-size: 22rpx; color: #666666; line-height: 1.5; }

/* ==================== 2. 加载中 ==================== */
.loading-card { display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }

/* ==================== 3/4. 引导态与错误态（.guide-card/.guide-title/.guide-text/.error-row/.error-retry 取自 common.css） ==================== */
.guide-card { margin-top: 56rpx; }
.guide-card .guide-link { margin-top: 8rpx; }
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
/* 429 冷却中的重试：同一枚按钮的形状，置灰 + 不可点（点击已在模板里按 retryLock 分流） */
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; cursor: not-allowed; }

/* ==================== 5. 参与态两块 ==================== */
.block-card { padding: 20rpx 24rpx; }
.count-tag {
  flex-shrink: 0; font-size: 22rpx; font-weight: 600; color: #E02020;
  background-color: #FFF7F0; border-radius: 8rpx; padding: 4rpx 14rpx; line-height: 1.4;
}
.truncate-line { display: block; font-size: 22rpx; color: #999999; line-height: 1.6; }
.mk-item { padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5; }
.mk-item:last-of-type { border-bottom: none; }
.mk-name {
  display: block;
  font-size: 30rpx; font-weight: 700; color: #333333; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.mk-line { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8rpx 20rpx; margin-top: 10rpx; }
.mk-kv { font-size: 24rpx; color: #999999; line-height: 1.5; }
.mk-v {
  font-size: 26rpx; font-weight: 600; color: #333333; line-height: 1.5;
  font-variant-numeric: tabular-nums;
}
.mk-v-price { color: #E02020; }

/* ==================== 6. 刷新时刻行（与工作台「资料刷新于」同用途） ==================== */
.refresh-line {
  display: block; margin: 20rpx 24rpx 0;
  font-size: 20rpx; color: #CCCCCC; line-height: 1.4; text-align: right;
}

.page-bottom-space { height: 60rpx; }
</style>
