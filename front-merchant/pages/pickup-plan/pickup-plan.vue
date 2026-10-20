<template>
  <view class="page-container">
    <!-- ==================== 1. 日期条 ====================
             显示值只取后端回显的 effectiveDate：date 不传时后端按「明天」兜底并回显生效日期，
             页面若自己 new Date() 算一个来显示，就等于把同一条口径在两端各存一份，迟早漂移（静默改口径） -->
    <view class="card date-card">
      <!-- 字段名标签：只有一个裸日期摆在这里会被读成「今天 11 号的单不见了」，
           措辞与 plan-orders 的 .picker-label 逐字一致（同一枚日期在两端不能两种叫法） -->
      <view class="date-field-row">
        <text class="date-field-label">自提日期</text>
      </view>
      <view class="date-row">
        <view class="date-step" hover-class="pill-hover" @click="stepDay(-1)">前一天</view>
        <text class="date-current">{{ effectiveDate || '--' }}</text>
        <view class="date-step" hover-class="pill-hover" @click="stepDay(1)">后一天</view>
      </view>
      <view class="date-sub-row">
        <!-- 「看明细」是两页唯一贯通入口：裸 text 的命中区只有字形那一小块（约 60×25px）且无按下态，
             点在字外就没反应，相邻的「前一天/后一天」反倒更像按钮；这里统一收成胶囊 + hover-class -->
        <view class="date-quick" hover-class="pill-hover" @click="goToday">今天</view>
        <view class="plan-entry" hover-class="pill-hover" @click="goOrders">看明细 ›</view>
      </view>
    </view>

    <!-- ==================== 2. 加载中（首屏与切日期后的重拉都走这一态，避免旧日期的单子被读成新日期的） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载备货单…</text>
    </view>

    <!-- ==================== 3. 错误态（≠ 空态：空态是服务端确认「该日一单都没有」，错误态是根本没拿到数据） ====================
             两层式照 dishes.vue：主文案是人话，后端 message 原文降为第二行小字（api 层 handleEnvelope 保证 reject 带 message） -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="loadPlan(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryPillText }}</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 4. 空态（服务端 200 且 groups 为空） ==================== -->
    <view v-else-if="viewState === 'empty'" class="card empty-card">
      <view class="empty-row">
        <text class="empty-text">该日无待备货</text>
      </view>
      <!-- 两个方向的出口都给：只留「看后一天」等于假定商家想往后翻，
           而周末/节假日前一天恰恰要往前找备货量 -->
      <view class="empty-actions">
        <text class="guide-link" @click="stepDay(-1)">看前一天</text>
        <text class="guide-link" @click="stepDay(1)">看后一天</text>
      </view>
    </view>

    <!-- ==================== 5. 就绪：按自提点分组聚合 ==================== -->
    <template v-else>
      <view
        class="card group-card"
        v-for="(g, gi) in groups"
        :key="(g.storeId === null || g.storeId === undefined ? 'n' : 's') + g.storeId + '-' + gi"
      >
        <view class="group-head">
          <text class="group-store">{{ storeNameOf(g) }}</text>
          <text class="group-count">{{ countOf(g.orderCount) }} 单</text>
        </view>
        <view class="plan-line" v-for="(it, ii) in itemsOf(g)" :key="(it.dishId == null ? 'x' : it.dishId) + '-' + ii">
          <text class="plan-name">{{ it.dishName || '未命名商品' }}</text>
          <text class="plan-qty">× {{ countOf(it.quantity) }}</text>
        </view>
        <view v-if="!itemsOf(g).length" class="plan-line">
          <text class="plan-empty-line">该自提点暂无条目</text>
        </view>
      </view>

      <!-- 导出文本：复制 + 只读文本视图两条路并存。
           非 secure context / 浏览器拒绝剪贴板权限时 setClipboardData 会走 fail/reject，
           这一路必须如实说「复制失败」并把文本展开让商家长按手抄，绝不能照旧 toast「已复制」（静默假成功） -->
      <view class="card export-card">
        <button class="btn-main" @click="copyPlanText"><text>导出文本</text></button>
        <view class="export-toggle" @click="toggleExportText">
          <text class="export-toggle-text">{{ showExportText ? '收起文本' : '展开文本' }}</text>
        </view>
        <text v-if="copyHint" class="copy-hint" :class="{ 'copy-hint-ok': copyOk }">{{ copyHint }}</text>
        <textarea
          v-if="showExportText"
          class="export-textarea"
          :value="planText"
          readonly
        />
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端备货单（G-03 / F-17 只读页）：GET /api/merchant/pickup-plan 按自提点聚合待备货量。
// 三条页面纪律：① 生效日期一律用后端回显值渲染（页内不自行推算显示值）；
// ② 归属由服务端解析，本页不发任何 merchantId；③ 四态单值互斥，不用多个布尔标志叠加（照 dishes.vue 的 viewState）。
// 401 由 api 层统一清态回登录页，本页不再处理跳转；403/404 是确定性失败，给「回工作台」而非重试。
import { getMerchantPickupPlan, retrySecondsOf, coolingFullText, coolingLeadText, cooldownPillText } from '@/api/index.js'
import '@/styles/common.css'

// 429 冷却秒数的回落档：本端点（GET /api/merchant/pickup-plan）限流窗口是 60 秒
// （MerchantOrderController:53 USER 30 次 / 60 秒），故回落取 60。
// 起锁值优先转述后端 429 data 里的 retryAfterSeconds（F-22 下发、F-23 起请求层 reject 带 data），
// 取不到才回落到本常量。回落值取整个窗口，因为它是「后端不给数时唯一确定的保守上限」；
// 回速（30 次/分实测只有 2 秒）是桶回充一格的间隔，不代表重试一定不挨 429 —— 两个数不是一回事，别混用。
const RETRY_COOLING_SECONDS = 60

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/pickup-plan/pickup-plan'

export default {
  data() {
    return {
      // 请求要下发的日期（'' = 不带 date，由后端默认「明天」兜底）：只进请求，不参与显示
      queryDate: '',
      // 后端回显的生效日期（YYYY-MM-DD）：日期条唯一显示源
      effectiveDate: '',
      groups: [],
      // 四态互斥：loading | error | empty | ready
      viewState: 'loading',
      errorText: '',
      errorCode: 0,
      // 只读文本视图是否展开（折叠是默认，复制失败时代码自动展开）
      showExportText: false,
      // 复制结果的常驻提示（成功/失败都落这一行：toast 一闪而过，长按手抄这句得留在屏上）
      copyHint: '',
      // copyHint 是当前这条复制「成功」时写的（仅驱动颜色：成功不该穿失败那身红）
      copyOk: false,
      // 429 冷却剩余秒数（>0 时重试按钮不可点）与驱动它的定时器：与 dishes.vue 同口径，
      // 限流态下的「重试」必须有秒级闸门，否则每次点都在给这个桶续命
      retryLock: 0,
      retryLockTimer: null
    }
  },
  computed: {
    // 纯文本导出内容：`生效日期 · 店名` 换行后每行两条空格 + `商品名 × 数量`
    planText() {
      const day = this.effectiveDate
      return this.groups
        .map((g) => {
          const head = day ? day + ' · ' + this.storeNameOf(g) : this.storeNameOf(g)
          const lines = this.itemsOf(g).map((it) => '  ' + (it.dishName || '未命名商品') + ' × ' + this.countOf(it.quantity))
          return lines.length ? [head].concat(lines).join('\n') : head
        })
        .join('\n')
    },
    // F-23-fix/FF-1：429 这行不带时长，秒数只在闸门那一处（本页模板的 retryLock 倒计时行）出现一次
    // X-2（四路分流，母版 dashboard.vue 的 errorLeadText）：改前的最后一行是无条件兜底，500/400 都被说成
    // 「请检查网络后重试」，与同屏第二行透出的后端 500 原文（R.java「服务开小差了，请稍后重试」）
    // 因果方向相反；判据：只有网络层失败才说检查网络，500 说服务端处理出错，其余不猜成因
    // F-28.5 路 B：429 一支并入 api 层冷却文案族（coolingLeadText），点名「备货单」这把锁
    errorLeadText() {
      if (this.errorCode === 429) return coolingLeadText('备货单')
      if (this.errorCode === 403) return '暂无权限查看备货单，请回工作台确认账号状态'
      // 列表端点的 404 只有「后端还没更新/路径不通」这一种解释（本页不按 id 取单行），
      // 服务起来后重试就能成，故不照 dishes.vue 那样把 404 归入「重试无望」
      if (this.errorCode === 404) return '备货单接口暂不可用，可能是后端服务还没更新，请稍后重试'
      // 500：服务端处理出错，与「网络不通」是方向相反的两种因果，不能再共用一句「请检查网络」
      if (this.errorCode === 500) return '备货单加载失败，服务端处理出错，稍后重试'
      // 只有网络层失败（api 层 request 的 fail 支回 code:-1 / message「网络异常」，见 front-merchant/api/index.js 其中网络支的 reject({ code: -1, message: '网络异常' })）
      // 才说「请检查网络后重试」
      if (this.errorCode <= 0) return '备货单加载失败，请检查网络后重试'
      // 其余服务端业务码（400 一类）：不猜成因，报「加载失败」＋第二行透出后端 message 原文
      return '备货单加载失败，请稍后重试'
    },
    // 重试有没有可能成功：只有 403（角色闸门，重试必然还是同一枚错误）判死并给「回工作台」出口；
    // 401 走不到这一态（请求层已回登录页），404/429/网络异常都属于「等一下或修好就能成」
    errorRetryable() {
      return this.errorCode !== 403
    },
    // 冷却中的时长短式：句式生成在 api 层 cooldownPillText，归因由同卡 errorLeadText 那一行给出（X-5）
    retryPillText() {
      return cooldownPillText(this.retryLock)
    }
  },
  onShow() {
    // X-4：429 冷却期内这一路静默跳过（判据逐字照 marketing.vue / dashboard.vue 与本页同批的
    // plan-orders.vue onShow；回页不是用户的点击，弹「太频繁」反而莫名，且错误卡在手时倒计时行本来就在屏上）。
    // 改前这一路是全页唯一没被闸门覆盖的自动重拉口：日期步进与错误卡重试都拦了，唯独每次回到本页
    // 都会无条件 loadPlan，限流窗口内反复进出就是在给这个桶续命、并把倒计时覆写回满值。
    if (this.retryLock > 0) return
    // 每次进入重拉（明细页/别的页返回时也保证看到的是当前真实待备货量），带 in-flight 守卫防连点
    this.loadPlan()
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
    // 继续每秒写一次已死组件的状态
    this.stopRetryLock()
  },
  methods: {
    // ==================== 数据加载 ====================
    // @param {boolean} [resetView] 日期变了要立刻退回加载态，让旧日期的组让位（否则会被读成新日期的结果）；
    //                              onShow 的静默重拉不传，已有组卡片时保持可见，避免每次回页闪一次白卡
    async loadPlan(resetView) {
      // 并发守卫：onShow 重拉 + 日期步进连点 + 错误卡重试可叠加，慢失败不能盖掉快成功
      if (this._loadingPlan) {
        // 在途期间日期又步进了一格：不能吞掉（否则日期条已变、卡片还是上一天的），排队等收尾后按最新条件重拉
        this._reloadQueued = true
        return
      }
      this._loadingPlan = true
      const reqDate = this.queryDate
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getMerchantPickupPlan({ date: reqDate })
        const data = (res && res.data) || {}
        // 回包晚于步进（在途期间用户又点了一格）：整批丢弃，按最新日期重发，
        // 绝不用上一天的组渲染当前日期——那是「日期写着 12 号、内容是 11 号」的两套真值
        if (this.queryDate !== reqDate) {
          this._reloadQueued = true
          return
        }
        // 生效日期取后端回显；后端没回显时才退回本次请求值，仍是「一份真值」而非页内推算
        this.effectiveDate = String(data.date || reqDate || '')
        const groups = Array.isArray(data.groups) ? data.groups : []
        this.groups = groups
        this.errorText = ''
        this.errorCode = 0
        this.copyHint = ''
        this.copyOk = false
        // 空态判定：一条组都没有（有组但组内空条目仍算 ready，卡片自会显示「暂无条目」）
        this.viewState = groups.length ? 'ready' : 'empty'
      } catch (e) {
        if (e && e.code === 401) {
          // 跳转与清态都由请求层做完（含「请先登录」toast），这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        if (this.queryDate !== reqDate) {
          this._reloadQueued = true
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = Number((e && e.code) || 0)
        // 429：给秒级冷却闸门，冷却期内按钮点不动 —— 否则用户每点一次都在续这个限流桶，
        // 来回几次就把后面 1 分钟的全部分额度花光（照 dishes.vue 的 loadDishes 错误分支）
        if (Number(e && e.code) === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loadingPlan = false
        if (this._reloadQueued) {
          this._reloadQueued = false
          this.loadPlan(true)
        }
      }
    },
    // ==================== 日期步进 ====================
    // X-4：闸门要覆盖全部出口（改前只拦住错误卡的「点击重试」，日期步进与 onShow 全旁路）。
    // 纪律三条：①不新增第二个闸门状态，读的就是既有 retryLock（数值只来自后端 data.retryAfterSeconds，
    // 经 startRetryLock→retrySecondsOf）；②调用点放在 queryDate 被改动之前，拦下时页面上那枚日期不动；
    // ③不静默，给带秒数的反馈。返回 true = 本次已拦下，调用方直接 return。
    retryCoolingBlocked() {
      if (this.retryLock <= 0) return false
      uni.showToast({ title: coolingFullText('备货单', this.retryLock), icon: 'none' })
      return true
    },
    // 步进基准优先取本次请求带出去的 queryDate（它是「页面上那枚日期想要的值」，改一步即生效），
    // 回显值 effectiveDate 只在成功时更新：慢网下连点时它还是上一轮的日期，
    // 以它为基准会把第 2、3 次点击算成同一天的 +1，三次点击只前进一格（点被吞掉）；
    // 两者都没有（首屏就失败、queryDate 仍为空）才退到本地今天——这个值只进请求，
    // 屏幕上的日期仍然只认回显
    stepDay(delta) {
      if (this.retryCoolingBlocked()) return
      const base = this.queryDate || this.effectiveDate || this.todayStr()
      const d = this.parseDate(base) || this.newToday()
      d.setDate(d.getDate() + delta)
      this.queryDate = this.fmtDate(d)
      this.loadPlan(true)
    },
    goToday() {
      if (this.retryCoolingBlocked()) return
      this.queryDate = this.todayStr()
      this.loadPlan(true)
    },
    todayStr() {
      return this.fmtDate(this.newToday())
    },
    newToday() {
      const n = new Date()
      return new Date(n.getFullYear(), n.getMonth(), n.getDate())
    },
    parseDate(s) {
      const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s || ''))
      if (!m) return null
      const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]))
      return Number.isNaN(d.getTime()) ? null : d
    },
    fmtDate(d) {
      return d.getFullYear() + '-' + this.pad2(d.getMonth() + 1) + '-' + this.pad2(d.getDate())
    },
    pad2(n) {
      return (n < 10 ? '0' : '') + n
    },
    // ==================== 导出文本 ====================
    // showToast: false —— uni-h5 的 setClipboardData 默认会自己弹一枚「复制成功」，
    // 那等于在页面还没知道真实结果前先替页面宣布成功；关掉它，文案由本页按 success/fail 分支决定
    async copyPlanText() {
      const text = this.planText
      if (!text) {
        uni.showToast({ title: '当前没有可导出的备货内容', icon: 'none' })
        return
      }
      try {
        await uni.setClipboardData({ data: text, showToast: false })
        // 成功也要落到常驻行位（不只 toast）：非可信上下文下 setClipboardData 可能既不 reject
        // 又没真写进去，只剩一次性 toast 就是「静默假成功」，商家得能在屏上回头核对这句
        this.copyHint = '已复制到剪贴板，可粘贴核对'
        this.copyOk = true
        uni.showToast({ title: '已复制', icon: 'none' })
      } catch (e) {
        // 失败就说失败并把只读文本展开（非 secure context 下剪贴板会走到这一路）
        this.copyHint = '复制失败，请长按文本框手动复制'
        this.copyOk = false
        this.showExportText = true
        uni.showToast({ title: this.copyHint, icon: 'none', duration: 2000 })
      }
    },
    toggleExportText() {
      this.showExportText = !this.showExportText
      if (this.showExportText) {
        this.copyHint = ''
        this.copyOk = false
      }
    },
    // ==================== 渲染辅助 ====================
    storeNameOf(g) {
      // storeId 为 NULL 的组后端已命名「未指定自提点」，这里再兜一层：拿到 null/空串不能渲染成空白
      return (g && (g.storeName || '未指定自提点')) || '未指定自提点'
    },
    itemsOf(g) {
      return (g && Array.isArray(g.items)) ? g.items : []
    },
    countOf(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n : 0
    },
    // ==================== 跳转 ====================
    goOrders() {
      // 传给明细页的必须是「本页当前正在查的那枚日期」，故与 stepDay 同优先级取基准：
      // 在途未回显时 effectiveDate 还是上一轮的日期，照它传会把明细页带到错的日期上
      // 两者都没有（首屏就没成功）才传空串 = 明细页按它的口径走「不限日期」，页内不猜日期
      uni.navigateTo({ url: '/pages/plan-orders/plan-orders?date=' + encodeURIComponent(this.queryDate || this.effectiveDate || '') })
    },
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    // ==================== 429 冷却（照 dishes.vue 的「429 冷却」一节，同 login.vue 的 loginLock 秒级禁用范式） ====================
    startRetryLock(err) {
      this.stopRetryLock()
      this.retryLock = retrySecondsOf(err, RETRY_COOLING_SECONDS)
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
    // 两道守卫缺一不可：
    // ① 栈顶守卫：监听挂在 document 上，而 navigateTo 不触发上一页 onUnload —— 进明细页后本页 handler
    //    仍挂着，两份 handler 同时驱动 body.scrollTop 会让滚轮一次滚两倍。
    // ② 弹层放行：弹层（含本页可能出现的 picker/modal/toast）打开时滚轮该归弹层，不能 preventDefault 去滚背景；
    //    textarea/input 同列表内——「复制失败→长按只读文本框手抄」是指定的兜底通道，
    //    滚轮压在它上面时若被页面 preventDefault 抢走，选中/滚动都受影响，等于兜底通道自己先不可用。
    //    末尾的 uni-textarea/uni-input 是 H5 自定义元素「壳」：closest('textarea') 匹配不到 <uni-textarea>
    //    （标签名是 UNI-TEXTAREA），指针落在只读文本框的边框/内边距区时滚轮仍会被页面抢走，故壳也要放行。
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
        && el.closest('.uni-actionsheet, .uni-modal, .uni-toast, .uni-sample-toast, .uni-picker-container, textarea, input, uni-textarea, uni-input')) {
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
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.empty-row/.btn-main/.guide-link 取自 common.css） */

/* ==================== 1. 日期条 ==================== */
.date-card { margin-top: 24rpx; padding: 20rpx 24rpx; }
/* 字段名标签：与 plan-orders 的 .picker-label 同一档（26rpx/#999999），日期条不再是个裸日期 */
.date-field-row { display: flex; margin-bottom: 12rpx; }
.date-field-label { font-size: 26rpx; color: #999999; line-height: 1.4; }
.date-row { display: flex; align-items: center; gap: 16rpx; }
/* 胶囊按钮：view + 12rpx 24rpx 内边距（原 8rpx 上下偏薄），四枚入口同一形状 */
.date-step {
  flex-shrink: 0; text-align: center;
  font-size: 26rpx; font-weight: 600; color: #E02020;
  background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 999rpx;
  padding: 12rpx 24rpx; line-height: 1.4;
}
/* 按下反馈（hover-class 目标，date-step / date-quick / plan-entry 共用）：
   移动端没有 :hover，靠 uni-app 的 hover-class 才有这一态 */
.pill-hover { color: #B01818; background-color: #FFF7F0; border-color: #B01818; }
.date-current {
  flex: 1; min-width: 0; text-align: center;
  font-size: 32rpx; font-weight: 700; color: #333333; line-height: 1.4;
  font-variant-numeric: tabular-nums;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.date-sub-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; margin-top: 16rpx; }
/* 「今天」「看明细」与上一排步进钮同形：同一文件里已有一套胶囊设计语言，不另起炉灶 */
.date-quick {
  flex-shrink: 0; text-align: center;
  font-size: 26rpx; color: #666666; line-height: 1.4;
  background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 999rpx;
  padding: 12rpx 24rpx;
}
.plan-entry {
  flex-shrink: 0; text-align: center;
  font-size: 26rpx; font-weight: 600; color: #E02020; line-height: 1.4;
  background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 999rpx;
  padding: 12rpx 24rpx;
}

/* ==================== 2. 加载中 ==================== */
.loading-card { display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }

/* ==================== 3. 错误态（两层式：人话主文案 + 后端原文小字） ==================== */
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
/* 429 冷却中的重试：同一枚按钮的形状，置灰 + 不可点（点击已在模板里按 retryLock 分流） */
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; }

/* ==================== 4. 空态 ==================== */
.empty-card { display: flex; flex-direction: column; align-items: center; }
.empty-actions { display: flex; justify-content: center; gap: 16rpx; }

/* ==================== 5. 组卡片 ==================== */
.group-card { padding: 20rpx 24rpx; }
.group-head {
  display: flex; align-items: center; gap: 16rpx;
  padding-bottom: 16rpx; border-bottom: 1rpx solid #F5F5F5;
}
.group-store {
  flex: 1; min-width: 0;
  font-size: 30rpx; font-weight: 700; color: #333333; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.group-count {
  flex-shrink: 0; font-size: 22rpx; font-weight: 600; color: #E02020;
  background-color: #FFF7F0; border-radius: 8rpx; padding: 4rpx 14rpx; line-height: 1.4;
}
.plan-line { display: flex; align-items: baseline; gap: 16rpx; padding: 16rpx 0; }
.plan-line + .plan-line { border-top: 1rpx solid #F7F7F7; }
.plan-name {
  flex: 1; min-width: 0;
  font-size: 28rpx; color: #333333; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.plan-qty {
  flex-shrink: 0; font-size: 28rpx; font-weight: 700; color: #333333; line-height: 1.5;
  text-align: right; font-variant-numeric: tabular-nums;
}
.plan-empty-line { font-size: 24rpx; color: #BBBBBB; line-height: 1.5; }

/* ==================== 6. 导出文本 ==================== */
.export-card { display: flex; flex-direction: column; align-items: center; gap: 12rpx; }
.export-toggle { padding: 4rpx 8rpx; }
.export-toggle-text { font-size: 24rpx; font-weight: 600; color: #E02020; }
.copy-hint {
  align-self: stretch; font-size: 22rpx; color: #E02020; line-height: 1.5; text-align: center;
}
/* 复制成功的常驻复核行：与失败提示同一行位，但不穿失败那身红 */
.copy-hint-ok { color: #666666; }
.export-textarea {
  width: 100%; min-height: 260rpx; box-sizing: border-box;
  background-color: #F5F5F5; border-radius: 12rpx; padding: 16rpx;
  font-size: 24rpx; color: #333333; line-height: 1.6;
}

.page-bottom-space { height: 60rpx; }
</style>
