<template>
  <view class="page-container">
    <!-- ==================== 0. 记账态说明（F-120 §2 页头逐字，恒显不依赖 paidChannelAbsent 做显隐） ====================
             照运营端 Settlements.vue 的「记账态恒显条」同一理由：接口键缺失时这行说明也得在——
             它说的是本页所有金额的口径，不是某一行的状态 -->
    <view class="card caliber-card">
      <text class="card-title">结算账单</text>
      <text class="caliber-line">记账态：打款通道未接入，金额为平台核算口径；有疑问联系平台运营</text>
      <text class="caliber-line">对账 ✓＝账面金额与已结算明细实时对得上；✗＝对不上（请勿自行改单，直接联系平台）</text>
      <text class="caliber-foot">销售额＝已支付·非缺货·已设供货价的明细按供货价核算；退款冲销计入发生期账单</text>
    </view>

    <!-- ==================== 1. 加载中（首屏与回页重拉都走这一态，避免旧列表闪现） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载结算账单…</text>
    </view>

    <!-- ==================== 2. 403 引导态（与错误态分开：确定性失败，重试必然还是同一枚错误） ==================== -->
    <view v-else-if="viewState === 'forbidden'" class="card guide-card">
      <text class="guide-title">暂无权限查看结算账单</text>
      <text class="guide-text">当前账号不是可查看的商家主体，或商家已停业，请回工作台确认账号状态</text>
      <text v-if="forbiddenMessage" class="guide-tip">{{ forbiddenMessage }}</text>
      <text class="guide-link" @click="goWorkspace">回工作台</text>
    </view>

    <!-- ==================== 3. 错误态（两层式照母版 dashboard.vue：人话主文案 + 后端原文小字） ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText && errorCode !== 404" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="loadSettlements(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryPillText }}</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 4. 就绪：账单列表（点行展开明细） / 空态 ==================== -->
    <template v-else>
      <view v-if="settlements.length > 0" class="card list-card">
        <view
          v-for="s in settlements"
          :key="s.id"
          class="settle-item"
          :class="{ 'settle-item-open': expandedId === s.id }"
          @click="toggleExpand(s)"
        >
          <!-- 行 1：账期 + 状态 tag（status==='accounted' 显「记账态」，其余原样转述——
               08 册 status 停在 accounted，paid 留轨道 B，页面不预译未知值） -->
          <view class="settle-head">
            <text class="settle-period">{{ periodText(s) }}</text>
            <text class="settle-tag">{{ statusText(s) }}</text>
          </view>
          <text class="settle-no">账单号 {{ s.settleNo || '--' }}</text>
          <!-- 金额 2×2 网格：逐键取后端出参，页内零加减（母版 D-4 纪律）；
               退款冲销是坏消息，与好消息同权重摆出来 -->
          <view class="amount-grid">
            <view class="amount-box">
              <text class="amount-value">{{ fmtAmount(s.gmvAmount) }}</text>
              <text class="amount-label">销售额</text>
            </view>
            <view class="amount-box">
              <text class="amount-value amount-value-refund">{{ fmtAmount(s.refundAmount) }}</text>
              <text class="amount-label">退款冲销</text>
            </view>
            <view class="amount-box amount-box-main">
              <text class="amount-value">{{ fmtAmount(s.netAmount) }}</text>
              <text class="amount-label">应结净额</text>
            </view>
            <view class="amount-box">
              <text class="amount-value">{{ fmtAmount(s.commission) }}</text>
              <text class="amount-label">主理人佣金</text>
            </view>
          </view>
          <!-- 行 3：订单数 + 对账标志（reconcileOk 是服务端实时结论，页面只转述） -->
          <view class="settle-foot">
            <text class="settle-orders">{{ textOf(s.orderCount) }} 单</text>
            <text :class="s.reconcileOk ? 'reconcile-ok' : 'reconcile-bad'">
              {{ s.reconcileOk ? '✓ 对账一致' : '✗ 对账异常' }}
            </text>
          </view>

          <!-- 展开区：明细懒加载（首次展开拉一次，收起不丢缓存；失败给行内重试，不打断列表） -->
          <view v-if="expandedId === s.id" class="detail-zone" @click.stop>
            <view v-if="itemsLoadingById[s.id]" class="detail-state">
              <text class="detail-state-text">正在加载明细…</text>
            </view>
            <view v-else-if="itemFailedById[s.id]" class="detail-state" @click.stop="loadItems(s)">
              <text class="detail-state-text detail-state-fail">明细加载失败，点击重试</text>
            </view>
            <template v-else>
              <view v-for="it in (itemsBySettle[s.id] || [])" :key="it.itemId" class="detail-row">
                <view class="detail-line1">
                  <text class="detail-name">{{ it.dishName || '未命名商品' }}</text>
                  <text class="detail-subtotal">{{ fmtAmount(it.subtotal) }}</text>
                </view>
                <text class="detail-line2">
                  供货价 {{ fmtSupply(it.supplyPrice) }} × {{ textOf(it.qty) }}
                  · 订单 {{ it.orderId || '--' }}
                  · {{ payStatusText(it.payStatus) }} · {{ fulfillText(it.fulfillStatus) }}
                </text>
                <!-- 孤儿行警示（orphan=true：orders 行已删、payStatus 无从取）——照运营端口径明示不静默 -->
                <text v-if="it.orphan" class="detail-orphan">⚠ 订单已不存在，本行不计入对账口径，请联系平台运营</text>
              </view>
              <!-- 空态判据（P4-4 §3-3）：只有「这张账单成功返回过、返回的确实是空数组」才许说「没有可展示的明细」。
                   改前判的是 !(itemsBySettle[s.id] || []).length，把「还没有缓存」当成了「明细为空」——
                   而请求没发出去/被丢弃/失败后不写缓存这几种情况都没有缓存，界面就会对商家宣称一句
                   他无从核对的「本账单没有可展示的明细」，这正是最容易被当成事实的那句假话 -->
              <view v-if="itemsBySettle[s.id] && itemsBySettle[s.id].length === 0" class="detail-state">
                <text class="detail-state-text">本账单没有可展示的明细</text>
              </view>
            </template>
            <text class="detail-hint">点账单行可收起</text>
          </view>
          <text v-else class="expand-hint">点击查看明细 ›</text>
        </view>
      </view>

      <!-- 空态：一期账单由平台跑批生成，商家侧没有「主动结算」入口，空 = 还没有到账期 -->
      <view v-else class="card empty-card">
        <view class="empty-row">
          <text class="empty-text">还没有结算账单；账期由平台按期生成后展示在这里</text>
        </view>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端结算账单只读页（F-120 §2）：GET /api/merchant/settlements 十三键账单列表 +
// 点行展开 GET /api/merchant/settlements/{id}/items 明细。三条页面纪律照母版 dashboard.vue：
// ① 金额/计数逐键取后端出参，页内零加减（页内算「净额＝销售额−退款」就是在页里发明第二套结算口径）；
// ② reconcileOk / paidChannelAbsent / status 只转述不推算；
// ③ 四态单值互斥 loading | ready | forbidden | error，不用多布尔叠加。
// 归属由服务端 @RequestAttribute 解析，本页不发任何 merchantId；401 由请求层清态回登录页。
// 展开明细是懒加载：列表本身不带明细（运营端同款交互），expandedId 单值 + itemsBySettle 缓存，
// 收起再展开不重发请求（明细是落库快照不是实时读数，缓存不构成陈旧读数）。
import { getMerchantSettlements, getMerchantSettlementItems, retrySecondsOf, coolingFullText, coolingLeadText, cooldownPillText } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（母版 H5 滚轮守卫用；本页列表可能很长，与 dashboard 同款接管）
const PAGE_ROUTE = 'pages/settlements/settlements'

// 429 冷却秒数的回落档：两端点限流都是 USER 30 次/分（MerchantController settlements 两个 GET，照 /me 读档），
// 窗口 60 秒；回落取整个窗口是「后端不给数时唯一能确定的安全上限」——口径同母版 dashboard.vue 的同名常量
const RETRY_COOLING_SECONDS = 60

// 支付态映射（照运营端 Settlements.vue 的三态词：1 已支付 / 2 已退款 / 其余未支付）；
// 孤儿行 payStatus 为 null，落在「未支付」会被误读成「顾客没付钱」——故 null 单独给「--」不猜
const PAY_STATUS_TEXT = { 1: '已支付', 2: '已退款' }

export default {
  data() {
    return {
      settlements: [],
      // 展开态单值：同一时刻只展开一张账单（再点已展开的行收起）
      expandedId: null,
      itemsBySettle: {},   // 结算单 id -> 明细行数组（懒加载缓存）
      // 加载态与失败态都按 id 存（P4-4 §3-2）：原先各只有一枚单值 id，而展开态允许多张账单同时在飞
      // （A 在飞时点开 B，两张都该有自己的态）。单值表达不了「多张各自在飞」，后起的那张会拿不到态
      itemsLoadingById: {}, // 结算单 id -> true（正在拉明细）
      itemFailedById: {},   // 结算单 id -> true（明细拉取失败，行内重试；false/缺键 = 无失败）
      // 四态互斥：loading | ready | forbidden | error
      viewState: 'loading',
      errorText: '',
      errorCode: 0,
      forbiddenMessage: '',
      // 429 冷却剩余秒数与定时器（口径照母版 dashboard.vue：限流态下的重试必须有秒级闸门）
      retryLock: 0,
      retryLockTimer: null,
      // FE-5（契约 docs/四期前端交互规划/FE-5-移动端倒计时锚点化契约）：上面这把闸门的墙上时钟锚点（ms）——
      // 闸门真值改存这里，retryLock 降级为「每秒由锚点现算出来的读数」，判据名一个都不改
      // （模板重试入口、retryPillText、onShow 闸门仍读这枚派生值）。
      // 改前是 setInterval 里 -- 自减：H5 后台标签页会把 interval 压到远慢于 1 秒（可低到 1 次/分），
      // 回到前台时屏上的数比真实剩余少。形状照 front-partner/pages/verify/verify.vue 的已验收版
      // （startRetryLock/retryLockRemaining/stopRetryLock 那一族），本页不另起第三套闸门抽象；
      // 锚点仍只由 api 层 retrySecondsOf 转述的后端秒数换算，页内零推算时长。
      // stop* 与之成对归零：残留值会让下一实例读到上一实例的数字。
      retryLockUntil: 0
    }
  },
  computed: {
    // 错误卡主文案四路分流（照 dashboard.vue 的 errorLeadText，归因「结算账单」）
    errorLeadText() {
      if (this.errorCode === 429) return coolingLeadText('结算账单')
      // 404（商家主体不存在等确定性失败）主文案直出后端 message，重试无望
      if (this.errorCode === 404) return this.errorText
      if (this.errorCode === 500) return '结算账单加载失败，服务端处理出错，稍后重试'
      if (this.errorCode <= 0) return '结算账单加载失败，请检查网络后重试'
      return '结算账单加载失败，请稍后重试'
    },
    errorRetryable() {
      return this.errorCode !== 403 && this.errorCode !== 404
    },
    retryPillText() {
      return cooldownPillText(this.retryLock)
    }
  },
  onShow() {
    // FE-5：回前台先按墙上时钟锚点重算一次派生读数，再让下面那道 429 闸门判它。
    // 隐藏标签页里 setInterval 被节流（可低到 1 次/分），锚点一直是准的，但屏上的数会停在最后一次
    // tick 算出的旧值——不重算就把「已经到期的锁」继续显示成「还在冷却」，也就会把本可以发的
    // onShow 请求无谓地挡掉（形状照 front-partner/pages/verify/verify.vue:330-335 的已验收版）
    this.retryLock = this.retryLockRemaining()
    // 429 冷却闸门（判据逐字照母版 dashboard.vue 的 onShow）：限流态下回页静默重拉是在给桶续命
    if (this.retryLock > 0) return
    this.loadSettlements()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底（照母版 dashboard.vue 的 onReady/onUnload 配对）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    this._unloaded = true
    // #ifdef H5
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
    this.stopRetryLock()
  },
  methods: {
    // ==================== 数据加载 ====================
    async loadSettlements(resetView) {
      if (this._unloaded) return
      if (this._loadingList) {
        this._reloadQueued = true
        return
      }
      this._loadingList = true
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getMerchantSettlements()
        this.settlements = (res && Array.isArray(res.data)) ? res.data : []
        this.errorText = ''
        this.errorCode = 0
        this.forbiddenMessage = ''
        this.stopRetryLock()
        this.viewState = 'ready'
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 401) {
          // 跳转与清态都由请求层做完，这里只停在加载态（照母版纪律不闪错误卡）
          this.viewState = 'loading'
          return
        }
        if (code === 403) {
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
          this.errorText = ''
          this.errorCode = 403
          this.stopRetryLock()
          this.viewState = 'forbidden'
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = code
        if (code === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loadingList = false
        if (this._reloadQueued) {
          if (this._unloaded) return
          this._reloadQueued = false
          this.loadSettlements(true)
        }
      }
    },
    // ==================== 展开/收起与明细懒加载 ====================
    toggleExpand(s) {
      const id = s && s.id
      if (id === null || id === undefined) return
      if (this.expandedId === id) {
        // 收起：缓存保留（明细是落库快照，重开不重发）
        this.expandedId = null
        return
      }
      this.expandedId = id
      if (!this.itemsBySettle[id]) this.loadItems(s)
    },
    async loadItems(s) {
      const id = s && s.id
      if (id === null || id === undefined) return
      // 单飞按 id 计（P4-4 §3-1）：改前是一枚全局 _loadingItems，A 的明细在飞时点开 B，
      // B 的请求被静默丢弃——B 既不进缓存也拿不到失败态，屏上只剩一个由「没缓存」冒充出来的空态，
      // 而且连重试入口都没有。非响应式实例属性即可（形状同上面的 _loadingList）
      if (!this._loadingItemsById) this._loadingItemsById = {}
      if (this._loadingItemsById[id]) return
      this._loadingItemsById[id] = true
      this.itemsLoadingById = { ...this.itemsLoadingById, [id]: true }
      // 重试前只清这一张的失败态：整体清掉会把另一张已失败账单的行内提示一起抹平
      this.itemFailedById = { ...this.itemFailedById, [id]: false }
      try {
        const res = await getMerchantSettlementItems(id)
        // 回包晚于收起/换行展开：整批丢弃（展开态已不指向它，塞进缓存会渲染到看不见的地方）
        if (this.expandedId !== id) return
        this.itemsBySettle = { ...this.itemsBySettle, [id]: (res && Array.isArray(res.data)) ? res.data : [] }
      } catch (e) {
        // 只在仍展开着这张账单时给行内失败提示；401 已由请求层处理，不在这里二次跳转
        if (this.expandedId === id && Number((e && e.code) || 0) !== 401) {
          this.itemFailedById = { ...this.itemFailedById, [id]: true }
        }
      } finally {
        // 只摘自己那一枚：把整枚闸门清空会放行同一张账单的重复请求，也会别处的在飞态跟着失真
        this._loadingItemsById[id] = false
        const loading = { ...this.itemsLoadingById }
        delete loading[id]
        this.itemsLoadingById = loading
      }
    },
    // ==================== 渲染辅助 ====================
    // 账期行（服务端定形串原样拼接，页内不 new Date() 换算——照母版 D-3 纪律）
    periodText(s) {
      if (!s || (!s.periodStart && !s.periodEnd)) return '账期 --'
      return '账期 ' + (s.periodStart || '--') + ' ~ ' + (s.periodEnd || '--')
    },
    statusText(s) {
      // status==='accounted' 显「记账态」照运营端 Settlements.vue 同一词；未知值原样转述不预译
      if (!s || !s.status) return '--'
      return s.status === 'accounted' ? '记账态' : s.status
    },
    // 金额两位小数（展示定形；后端 BigDecimal 出 JSON number，toFixed 不做任何算术只补形）；
    // 缺失显 '--' 不兜 0：把「后端没回」和「真是 0」抹成同一个读数是替商家编数（照母版 textOf 纪律）
    fmtAmount(v) {
      if (v === null || v === undefined || v === '') return '--'
      const n = Number(v)
      return Number.isFinite(n) ? n.toFixed(2) : '--'
    },
    // 供货价（明细行）：未设显「--」——settle 挂锚明细必设供货价，出现「--」即数据异常的诚实呈现
    fmtSupply(v) {
      if (v === null || v === undefined || v === '') return '--'
      const n = Number(v)
      return Number.isFinite(n) ? '¥' + n.toFixed(2) : '--'
    },
    textOf(v) {
      if (v === null || v === undefined || v === '') return '--'
      return String(v)
    },
    payStatusText(v) {
      // null（孤儿行）显「--」不落「未支付」：没付钱与查不到支付态是两回事
      if (v === null || v === undefined || v === '') return '--'
      return PAY_STATUS_TEXT[v] || '未支付'
    },
    fulfillText(v) {
      // 照运营端口径：shortage 缺货 / 其余正常（聚合基数本就排除 shortage，这里只兜脏值）
      return v === 'shortage' ? '缺货' : '正常'
    },
    // ==================== 跳转 ====================
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    // ==================== 429 冷却（照母版 dashboard.vue 同款秒级禁用范式） ====================
    // 只在 code===429 起（调用点在 loadSettlements 的 catch 里判过码）：入参是错误对象，
    // 秒数一律由 api 层 retrySecondsOf 取值（F-25/P-3 纪律：回落只能有 RETRY_COOLING_SECONDS 一个出处，页内零推算时长）
    startRetryLock(err) {
      if (this._unloaded) return
      this.stopRetryLock()
      // FE-5：闸门真值改存墙上时钟锚点，秒数只由它派生（形状照 front-partner/pages/verify/verify.vue 的已验收版：
      // 「锚点 = Date.now() + retrySecondsOf(...) * 1000」+ 立刻取一次派生值）。
      this.retryLockUntil = Date.now() + retrySecondsOf(err, RETRY_COOLING_SECONDS) * 1000
      this.retryLock = this.retryLockRemaining()
      this.retryLockTimer = setInterval(() => {
        // tick 只换算派生值，不再有 -- 自减：后台标签页把 interval 压慢时，回来那一次 tick
        // 直接从墙上时钟重算，读数与真实剩余一致（改前是「少了几十秒」的那一类偏差）
        this.retryLock = this.retryLockRemaining()
        if (this.retryLock <= 0) this.stopRetryLock()
      }, 1000)
    },
    // 锚点 → 剩余秒数：只从墙上时钟现算，到期回 0（0 同时是模板重试入口与 onShow 闸门隐掉冷却态的判据）。
    // 屏上的数、重试入口的显隐用的是同一个来源，不存在「两处数互相矛盾」
    retryLockRemaining() {
      if (!(this.retryLockUntil > 0)) return 0
      return Math.max(0, Math.ceil((this.retryLockUntil - Date.now()) / 1000))
    },
    stopRetryLock() {
      if (this.retryLockTimer) { clearInterval(this.retryLockTimer); this.retryLockTimer = null }
      // FE-5：锚点与读数成对归零（残值会让下一实例的闸门/读数从上一实例那里读出来）
      this.retryLockUntil = 0
      this.retryLock = 0
    },
    // ==================== H5 页面滚轮兜底（照母版 dashboard.vue 的 onPageWheel 两道守卫） ====================
    // #ifdef H5
    onPageWheel(e) {
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

/* ==================== 0. 记账态说明卡 ==================== */
.caliber-card { margin-top: 24rpx; display: flex; flex-direction: column; gap: 12rpx; }
.caliber-line { font-size: 24rpx; color: #666666; line-height: 1.7; }
.caliber-foot { font-size: 20rpx; color: #BBBBBB; line-height: 1.6; }

/* ==================== 1. 加载中 ==================== */
.loading-card { margin-top: 24rpx; display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }

/* ==================== 2/3. 引导态与错误态（基类取自 common.css，本页只补差异件） ==================== */
.guide-card { margin-top: 56rpx; }
.guide-card .guide-link { margin-top: 8rpx; }
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; cursor: not-allowed; }

/* ==================== 4. 账单列表 ==================== */
.list-card { margin-top: 24rpx; padding: 0; overflow: hidden; }
.settle-item { padding: 24rpx; border-bottom: 1rpx solid #F5F5F5; }
.settle-item:last-child { border-bottom: none; }
.settle-item-open { background-color: #FFFBF8; }
.settle-head { display: flex; align-items: center; gap: 12rpx; }
.settle-period { flex: 1; min-width: 0; font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.4; }
.settle-tag {
  flex-shrink: 0; font-size: 20rpx; font-weight: 600; line-height: 1.4;
  color: #FFFFFF; background-color: #E02020; border-radius: 8rpx; padding: 4rpx 12rpx;
}
.settle-no { display: block; margin-top: 8rpx; font-size: 22rpx; color: #999999; line-height: 1.5; font-variant-numeric: tabular-nums; }

/* 金额 2×2 网格（flex 而不是 grid：小程序端 grid 支持面窄，照 dashboard 的 stat-grid 同款手势） */
.amount-grid { display: flex; flex-wrap: wrap; margin-top: 16rpx; }
.amount-box {
  width: 50%; box-sizing: border-box; padding: 12rpx 8rpx;
  display: flex; flex-direction: column; gap: 4rpx;
  background-color: #FAFAFA; border-radius: 12rpx; margin-bottom: 8rpx;
}
/* 应结净额是商家最关心的那格：浅红底与 dashboard 的 stat-box-warn 同一族（#FFF7F0），不引新色 */
.amount-box-main { background-color: #FFF7F0; }
.amount-value {
  font-size: 32rpx; font-weight: 700; color: #333333; line-height: 1.3;
  font-variant-numeric: tabular-nums;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%;
}
.amount-value-refund { color: #E02020; }
.amount-label { font-size: 20rpx; color: #999999; line-height: 1.4; }

.settle-foot { display: flex; align-items: center; margin-top: 8rpx; }
.settle-orders { flex: 1; min-width: 0; font-size: 22rpx; color: #666666; line-height: 1.5; }
.reconcile-ok { flex-shrink: 0; font-size: 22rpx; color: #07A854; line-height: 1.5; font-variant-numeric: tabular-nums; }
/* 对账异常：与主红同族但读法不同（这不是按钮色），照 dishes 的驳回徽标思路用醒目红字承载 */
.reconcile-bad { flex-shrink: 0; font-size: 22rpx; font-weight: 600; color: #E02020; line-height: 1.5; }

.expand-hint { display: block; margin-top: 8rpx; font-size: 20rpx; color: #CCCCCC; line-height: 1.5; }

/* ==================== 展开明细区 ==================== */
.detail-zone { margin-top: 16rpx; border-top: 1rpx solid #F0F0F0; padding-top: 8rpx; }
.detail-state { padding: 16rpx 0; }
.detail-state-text { font-size: 22rpx; color: #999999; line-height: 1.6; }
.detail-state-fail { color: #E02020; }
.detail-row { padding: 16rpx 0; border-bottom: 1rpx solid #F7F7F7; }
.detail-row:last-of-type { border-bottom: none; }
.detail-line1 { display: flex; align-items: baseline; gap: 12rpx; }
.detail-name {
  flex: 1; min-width: 0;
  font-size: 26rpx; font-weight: 600; color: #333333; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.detail-subtotal { flex-shrink: 0; font-size: 26rpx; font-weight: 700; color: #333333; font-variant-numeric: tabular-nums; }
.detail-line2 { display: block; margin-top: 6rpx; font-size: 22rpx; color: #999999; line-height: 1.6; font-variant-numeric: tabular-nums; }
.detail-orphan { display: block; margin-top: 6rpx; font-size: 22rpx; color: #E02020; line-height: 1.5; }
.detail-hint { display: block; padding: 12rpx 0 4rpx; font-size: 20rpx; color: #CCCCCC; line-height: 1.5; }

/* ==================== 空态卡 ==================== */
.empty-card { margin-top: 24rpx; display: flex; flex-direction: column; align-items: center; }

.page-bottom-space { height: 60rpx; }
</style>
