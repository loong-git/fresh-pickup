<template>
  <view class="page-container">
    <!-- ==================== 1. 窗口切换（7 / 30 / 90 三档点选，默认 7） ====================
             刻意不给「自定义天数」输入框：值域 [1,90] 的裁决权在服务端（契约 D-3，越界 400
             「天数需在 1 到 90 之间」），页面放开自由输入就等于造一次注定失败的往返，
             还会多出「输入了 999 但屏上还是 90」这种两套真值 -->
    <view class="card window-card">
      <view class="window-tabs">
        <view
          class="window-tab"
          v-for="d in daysOptions"
          :key="d"
          :class="{ active: days === d }"
          hover-class="pill-hover"
          @click="switchDays(d)"
        >
          <text>近 {{ d }} 天</text>
        </view>
      </view>
      <!-- 窗口起点一句实话：两个值都只认后端回显（见 computed windowLine 与类注释），页内零 new Date() -->
      <text class="window-line">{{ windowLine }}</text>
    </view>

    <!-- ==================== 2. 加载中（首屏与换窗口后的重拉都走这一态，避免上一窗口的数被读成新窗口的） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载经营读数…</text>
    </view>

    <!-- ==================== 3. 403 引导态（与错误态分开：这是确定性失败，重试必然还是同一枚错误） ====================
             本端点按 D-9 读侧也判停业，故 403 有两种来源（非商家主体 / 商家非 active），
             两种都不是「等一下就能成」，唯一有用的出口是回工作台看账号状态 -->
    <view v-else-if="viewState === 'forbidden'" class="card guide-card">
      <text class="guide-title">暂无权限查看数据看板</text>
      <text class="guide-text">当前账号不是可查看看板的商家主体，或商家已停业，请回工作台确认账号状态</text>
      <text v-if="forbiddenMessage" class="guide-tip">{{ forbiddenMessage }}</text>
      <text class="guide-link" @click="goWorkspace">回工作台</text>
    </view>

    <!-- ==================== 4. 错误态（≠ 空态：空态是后端确认「本窗口没有成交」，错误态是根本没拿到数据） ====================
             两层式照 pickup-plan.vue：主文案是人话，后端 message 原文降为第二行小字 -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="loadStat(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 5. 就绪：四卡 + Top 商品 + 口径说明 ==================== -->
    <template v-else>
      <!-- 四张卡逐键取后端出参（gmvBeforeCoupon / orderCount / itemCount / refundCount），
           页面零加减：把 refundCount 从 GMV 里扣掉、把 itemCount 除成客单价，都会让读数与服务端
           手工对账（G4）打脸，也会让「被取消订单」这一格白摆（契约 D-4 就是要把坏消息一起摆出来） -->
      <view class="card stat-card">
        <view class="stat-grid">
          <view class="stat-item" v-for="c in statCards" :key="c.key">
            <view class="stat-box" :class="{ 'stat-box-warn': c.warn }">
              <text class="stat-value" :class="{ 'stat-value-warn': c.warn }">{{ c.value }}</text>
              <text class="stat-unit">{{ c.unit }}</text>
              <text class="stat-label">{{ c.label }}</text>
            </view>
          </view>
        </view>
      </view>

      <!-- Top 商品：纯 CSS 横向条形（D-12「能看即可」），不引 ECharts、不画坐标系 -->
      <view class="card top-card">
        <view class="card-head">
          <text class="card-title">Top 商品</text>
          <text class="top-note">按成交金额排序，最多 5 行</text>
        </view>
        <view class="top-row" v-for="(r, i) in topRows" :key="rowKey(r, i)">
          <view class="top-head">
            <text class="top-rank">{{ i + 1 }}</text>
            <text class="top-name">{{ r.dishName }}</text>
            <text class="top-qty">× {{ r.quantity }}</text>
            <text class="top-amount">{{ r.amount }}</text>
          </view>
          <!-- 条宽 = 本行金额 ÷ 第一名金额（D-12 原文「占第一名的百分比」），只是相对刻度：
               屏上的金额仍是后端原串，不因为条宽变短而改一个数字 -->
          <view class="top-bar-track">
            <view class="top-bar" :style="{ width: r.width + '%' }"></view>
          </view>
        </view>
        <view v-if="!topRows.length" class="empty-row">
          <text class="empty-text">本窗口还没有成交记录</text>
        </view>
      </view>

      <!-- ==================== 口径说明（D-5 硬要求，逐字三行，不可省、不可润色） ==================== -->
      <view class="card caliber-card">
        <text class="card-title">口径说明</text>
        <text class="caliber-line">我的销售额（商品明细·券前）＝ Σ成交单价×数量，含秒杀价，不含券抵扣</text>
        <text class="caliber-line">平台已收款（订单口径）＝ Σ订单应付，含同一订单里其他商家的明细</text>
        <text class="caliber-line">两个数字天然对不平，差额＝券抵扣＋同单其他商家明细。本期不分摊，分摊随结算（m19+）定义。</text>
        <!-- 后端刻意不下发「平台已收款」那个数（那是 G-06 运营大盘的读数），本页也不做减法：
             这一块的用处就是说清「为什么这里只有一个销售额、以及它和平台口径差在哪」，不是给第二个数 -->
        <text class="caliber-foot">本看板只列商品明细口径，不列平台侧金额</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端数据看板（G-05 / F-19 只读页）：GET /api/merchant/stat?days= 的四卡 + Top 5 商品 + 口径说明卡。
// 三条页面纪律：① 四个数逐键取后端出参，页内零加减（D-4）；
// ② windowStart / days 只显示后端回显值，页内不 new Date() 推算任何日期（D-3，与后端 CURDATE() 同式才算一份真值）；
// ③ 四态单值互斥 loading | ready | forbidden | error，不用多个布尔叠加（照 workspace.vue 的 viewState）。
// 归属由服务端 @RequestAttribute 解析，本页不发任何 merchantId；401 一律由请求层清态回登录页，本页不判 401。
// 文案红线（RE）：本页不出现任何资金含义的读数词——mock 支付无资金流，销售额只按「商品明细·券前」口径出。
import { getMerchantStat } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/dashboard/dashboard'

// 窗口三档（契约 D-3 值域 [1,90] 里挑三个常用档；缺省 7 与后端 @RequestParam(defaultValue="7") 同值）
const DAYS_OPTIONS = [7, 30, 90]

// 429 冷却秒数：本端点限流档是 USER 30 次 / 60 秒（契约 D-10），故冷却取窗口长度 60。
// 为什么不能像主理人端那样按后端 data.retryAfterSeconds 起锁：商家端请求层（RA 红线一字不动）
// 的 reject 只带 {code,message} 不带 data，页面拿不到后端剩余秒数，本地按窗口取值是唯一不猜的路。
const RETRY_COOLING_SECONDS = 60

// FE-10 同页 429 只留一句说法（错误卡主文案与冷却中的重试都走这一处实现，不分两句）
function coolingText(seconds) {
  const n = Number(seconds)
  return '操作过于频繁，' + (Number.isFinite(n) && n > 0 ? '请 ' + n + 's 后再试' : '请稍后再试')
}

export default {
  data() {
    return {
      // 本次请求要下发的窗口天数（唯一控制值；生效后一律用后端回显 days 校正，见 loadStat）
      days: 7,
      daysOptions: DAYS_OPTIONS,
      // 后端回显的生效窗口（0 = 还没回显）：只驱动「统计窗口」那一句，不参与任何计算
      echoDays: 0,
      // 后端回显的窗口起点（YYYY-MM-DD 串，SQL 侧同式取回）
      windowStart: '',
      // 四卡原值（逐键对齐后端出参名，不做前端改名换算）
      gmvBeforeCoupon: '',
      orderCount: null,
      itemCount: null,
      refundCount: null,
      top: [],
      // 四态互斥：loading | ready | forbidden | error
      viewState: 'loading',
      errorText: '',
      errorCode: 0,
      // 403 引导态透出后端原文
      forbiddenMessage: '',
      // 429 冷却剩余秒数（>0 时重试按钮点不动）与驱动它的定时器：与 pickup-plan.vue 同口径，
      // 限流态下的「重试」必须有秒级闸门，否则每次点都在给这个桶续命
      retryLock: 0,
      retryLockTimer: null
    }
  },
  computed: {
    // 「统计窗口：2026-10-06 起 7 天」——两个值都只认后端回显：
    // 窗口起点是 SQL 里 DATE_SUB(CURDATE(), INTERVAL (days-1) DAY) 算的，页内若用 new Date() 再推一次，
    // 就等于把同一条口径在两端各存一份（应用服务器时区与库时区可能差一天，屏上值从此不可信）。
    // 后端没回显时如实说「未回显」，绝不拿本地日期顶一个上去。
    windowLine() {
      if (!this.windowStart) return '统计窗口：后端未回显起始日'
      const d = this.echoDays > 0 ? this.echoDays : this.days
      return '统计窗口：' + this.windowStart + ' 起 ' + d + ' 天'
    },
    // 四张卡：label 是契约 D-4 的四叫法，value 逐键取后端出参（金额是后端定形的两位小数串，原样显示）
    statCards() {
      return [
        { key: 'gmv', label: '我的销售额（券前）', unit: '元', value: this.textOf(this.gmvBeforeCoupon), warn: false },
        { key: 'order', label: '订单数', unit: '单', value: this.textOf(this.orderCount), warn: false },
        { key: 'item', label: '商品件数', unit: '件', value: this.textOf(this.itemCount), warn: false },
        // 坏消息与好消息同权重摆出来（D-4 ④）：只报成交不报取消的看板是在替商家美化数据
        { key: 'refund', label: '被取消订单', unit: '单', value: this.textOf(this.refundCount), warn: true }
      ]
    },
    // Top 5 行（含条宽）：条宽是唯一允许的除法，且它除的是「占第一名的比例」，不是金额本身
    topRows() {
      const list = Array.isArray(this.top) ? this.top.slice(0, 5) : []
      const base = this.numOf(list.length ? list[0].amount : null)
      return list.map((r, i) => {
        const v = this.numOf(r && r.amount)
        // 夹进 [0,100]：并列第一给满格，脏值/第一名金额为 0 时给 0 宽，绝不出现负宽或溢出轨道
        const ratio = base > 0 ? (v / base) * 100 : 0
        return {
          dishId: r && r.dishId,
          // dishName 是 order_item 的下单快照（后端没 JOIN dish），缺失时兜底写法与备货单页同词
          dishName: (r && r.dishName) || '未命名商品',
          quantity: this.textOf(r && r.quantity),
          amount: this.textOf(r && r.amount),
          width: Math.max(0, Math.min(100, Math.round(ratio)))
        }
      })
    },
    errorLeadText() {
      if (this.errorCode === 429) return coolingText(this.retryLock)
      if (this.errorCode === 404) return '看板接口暂不可用，可能是后端服务还没更新，请稍后重试'
      return '经营读数加载失败，请检查网络后重试'
    },
    // 重试有没有可能成功：403 已单独走 forbidden 态，到这里都是「等一下或修好就能成」
    errorRetryable() {
      return this.errorCode !== 403
    }
  },
  onShow() {
    // 每次进入静默重拉（别的页返回时也保证看到的是当前窗口的真实读数），带 in-flight 守卫防连点；
    // 已是 ready 时不切加载态，避免回页闪一次白卡把上一轮的数盖掉
    this.loadStat()
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
    // @param {boolean} [resetView] 换窗口要立刻退回加载态，让上一窗口的四卡让位（否则会被读成新窗口的数）；
    //                              onShow 的静默重拉不传
    async loadStat(resetView) {
      // 并发守卫：onShow 重拉 + 窗口三档连点 + 错误卡重试可叠加，慢失败不能盖掉快成功
      if (this._loadingStat) {
        // 在途期间窗口又换了一档：不能吞掉（否则屏上选中 30 天、卡片还是 7 天的），排队等收尾后按最新条件重拉
        this._reloadQueued = true
        return
      }
      this._loadingStat = true
      const reqDays = this.days
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getMerchantStat({ days: reqDays })
        const data = (res && res.data) || {}
        // 回包晚于换档（在途期间又点了一档）：整批丢弃，按最新窗口重发，
        // 绝不用上一窗口的数渲染当前选中的档——那是「档写着 30 天、内容是 7 天」的两套真值
        if (this.days !== reqDays) {
          this._reloadQueued = true
          return
        }
        // 生效窗口取后端回显（回显是合法值时才校正选中档）：服务端「夹而不拒」的话，
        // 页面坚持自己那份请求值就会与屏上那句「起 N 天」打脸
        const echoDays = Number(data.days)
        if (Number.isFinite(echoDays) && echoDays >= 1) {
          this.echoDays = echoDays
          this.days = echoDays
        }
        this.windowStart = String(data.windowStart || '')
        // 四个数逐键原样收下：金额串已由后端 setScale(2) 定形，页面不再 toFixed（那是把口径搬到展示层）
        this.gmvBeforeCoupon = data.gmvBeforeCoupon
        this.orderCount = data.orderCount
        this.itemCount = data.itemCount
        this.refundCount = data.refundCount
        this.top = Array.isArray(data.top) ? data.top : []
        this.errorText = ''
        this.errorCode = 0
        this.forbiddenMessage = ''
        // 空 top 不改整页态：四张卡 + 「本窗口还没有成交记录」就是零单商家的真实读数（G5 恒回 0/[]，不是错误）
        this.viewState = 'ready'
      } catch (e) {
        if (this.days !== reqDays) {
          // 窗口在途期间已变更：这次失败属于旧窗口，不外抛给现在的卡片，按新窗口重发
          this._reloadQueued = true
          return
        }
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
        this._loadingStat = false
        if (this._reloadQueued) {
          this._reloadQueued = false
          this.loadStat(true)
        }
      }
    },
    // ==================== 窗口切换 ====================
    switchDays(d) {
      const next = Number(d)
      if (!Number.isFinite(next) || next === this.days) return
      this.days = next
      this.loadStat(true)
    },
    // ==================== 渲染辅助 ====================
    // 出参缺失时如实显示 '--'，不兜 0：G5 保证后端零单回 0 / "0.00"，页面再兜一层就是把
    // 「后端没回」和「真是 0」抹成同一个读数，那是替商家编数。有值就原样透出（金额串已由后端定形两位小数）
    textOf(v) {
      if (v === null || v === undefined || v === '') return '--'
      return String(v)
    },
    numOf(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n : 0
    },
    // 行 key：dishId 是聚合行的分组键，理论上有值；缺失（脏值）时退化到行号，保证 :key 不重复
    rowKey(r, i) {
      const id = r && r.dishId
      return (id === null || id === undefined || id === '') ? 'x' + i : 'd' + id
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
    // ② 弹层放行：modal/toast（回工作台的确认、限流提示）打开时滚轮该归弹层，不能 preventDefault 去滚背景。
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

/* ==================== 1. 窗口切换 ==================== */
.window-card { margin-top: 24rpx; padding: 20rpx 24rpx; }
.window-tabs { display: flex; gap: 12rpx; }
.window-tab {
  flex: 1; text-align: center;
  font-size: 26rpx; color: #666666;
  background-color: #F5F5F5; border-radius: 24rpx;
  padding: 12rpx 0; border: 1rpx solid #EEEEEE;
}
.window-tab.active { color: #FFFFFF; font-weight: 700; background-color: #E02020; border-color: #E02020; }
/* 按下反馈（hover-class 目标）：移动端没有 :hover，靠 uni-app 的 hover-class 才有这一态 */
.pill-hover { color: #B01818; background-color: #FFF7F0; border-color: #B01818; }
/* 窗口起点那句实话：整行可换行，长日期串不许被截断（这是商家判断「到底统计的哪几天」的唯一线索） */
.window-line {
  display: block; margin-top: 16rpx;
  font-size: 24rpx; color: #666666; line-height: 1.6;
  font-variant-numeric: tabular-nums;
}

/* ==================== 2. 加载中 ==================== */
.loading-card { display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }

/* ==================== 3/4. 引导态与错误态（.guide-card/.guide-title/.guide-text/.error-row/.error-retry/.guide-link 取自 common.css） ==================== */
.guide-card { margin-top: 56rpx; }
.guide-card .guide-link { margin-top: 8rpx; }
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
/* 429 冷却中的重试：同一枚按钮的形状，置灰 + 不可点（点击已在模板里按 retryLock 分流） */
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; cursor: not-allowed; }

/* ==================== 5. 四卡（2×2 网格，flex 而不是 grid：小程序端 grid 支持面窄） ==================== */
.stat-card { margin-top: 24rpx; padding: 8rpx; }
.stat-grid { display: flex; flex-wrap: wrap; }
.stat-item { width: 50%; box-sizing: border-box; padding: 8rpx; }
.stat-box {
  display: flex; flex-direction: column; align-items: center; gap: 4rpx;
  background-color: #FAFAFA; border-radius: 12rpx; padding: 20rpx 12rpx;
}
.stat-box-warn { background-color: #FFF7F0; }
.stat-value {
  font-size: 36rpx; font-weight: 700; color: #333333; line-height: 1.3;
  font-variant-numeric: tabular-nums;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%;
}
.stat-value-warn { color: #E02020; }
.stat-unit { font-size: 20rpx; color: #999999; line-height: 1.4; }
.stat-label { font-size: 22rpx; color: #666666; line-height: 1.4; text-align: center; }

/* ==================== 6. Top 商品（纯 CSS 条形，零图表库） ==================== */
.top-card { padding: 20rpx 24rpx; }
.top-card .card-head { margin-bottom: 12rpx; }
.top-note { font-size: 20rpx; color: #BBBBBB; line-height: 1.4; }
.top-row { padding: 16rpx 0; }
.top-row + .top-row { border-top: 1rpx solid #F7F7F7; }
.top-head { display: flex; align-items: baseline; gap: 12rpx; }
.top-rank {
  flex-shrink: 0; width: 32rpx; height: 32rpx; line-height: 32rpx; text-align: center;
  font-size: 20rpx; font-weight: 700; color: #E02020;
  background-color: #FFF7F0; border-radius: 8rpx;
}
.top-name {
  flex: 1; min-width: 0;
  font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.top-qty { flex-shrink: 0; font-size: 24rpx; color: #666666; line-height: 1.5; font-variant-numeric: tabular-nums; }
.top-amount { flex-shrink: 0; font-size: 28rpx; font-weight: 700; color: #E02020; line-height: 1.5; font-variant-numeric: tabular-nums; }
.top-bar-track { margin-top: 10rpx; height: 12rpx; background-color: #F5F5F5; border-radius: 999rpx; overflow: hidden; }
.top-bar { height: 12rpx; min-width: 4rpx; background-color: #E02020; border-radius: 999rpx; }

/* ==================== 7. 口径说明（D-5 逐字三行，行序即原文序） ==================== */
.caliber-card { display: flex; flex-direction: column; gap: 12rpx; }
.caliber-line { font-size: 24rpx; color: #666666; line-height: 1.7; }
.caliber-foot { font-size: 20rpx; color: #BBBBBB; line-height: 1.6; }

.page-bottom-space { height: 60rpx; }
</style>
