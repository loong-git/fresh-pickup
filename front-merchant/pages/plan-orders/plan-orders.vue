<template>
  <view class="page-container">
    <!-- ==================== 1. 筛选区（状态 tab + 自提日期） ====================
             两个条件都只作为 query 交 GET /api/merchant/order-items，本页一律不做前端过滤：
             列表是分页的，对「已加载的那几页」再过滤出来的结果既不是全集也总数不对（C 端订单页 :8 自陈的
             前端过滤在它自己的全量拉取下成立，这里照抄就是错的） -->
    <view class="card filter-card">
      <view class="order-tabs">
        <view
          class="order-tab"
          v-for="t in tabs"
          :key="t.key"
          :class="{ active: status === t.key }"
          @click="switchTab(t.key)"
        >
          <text>{{ t.label }}</text>
        </view>
      </view>
      <!-- 日期只选不算：本端点 date 为空 = 不限自提日期（后端无默认值），页内不 new Date() 猜一个（深链/刷新进来同理保持缺省） -->
      <picker class="date-picker" mode="date" :value="date" @change="onDateChange">
        <view class="picker-row">
          <text class="picker-label">自提日期</text>
          <text class="picker-value">{{ dateLabel }}</text>
          <text class="picker-arrow">›</text>
        </view>
      </picker>
      <!-- 清除日期是可逆操作入口，同样收成胶囊 + 按下态（与备货单页的日期条按钮同一档） -->
      <view v-if="date" class="date-clear" hover-class="pill-hover" @click="clearDate">清除日期</view>
    </view>

    <!-- ==================== 2. 加载中（首屏与筛选切换后的重拉都走这一态，避免旧条件的行被读成新条件的） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载供货明细…</text>
    </view>

    <!-- ==================== 3. 错误态（≠ 空态：空态是服务端确认「一条都没有」，错误态是根本没拿到数据） ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="fetchList(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 4. 空态 ==================== -->
    <view v-else-if="viewState === 'empty'" class="card empty-card">
      <view class="empty-row">
        <text class="empty-text">当前条件下没有供货明细</text>
      </view>
      <text v-if="hasFilter" class="guide-link" @click="resetFilters">清除筛选条件</text>
    </view>

    <!-- ==================== 5. 就绪：分页明细列表 ==================== -->
    <template v-else>
      <view class="card list-card">
        <view class="order-item" v-for="(it, i) in list" :key="rowKey(it, i)">
          <!-- 每枚圆点与它后面那段文本绑成同一个不换行单元（.item-unit）：
               圆点当独立 flex 项时会随换行被甩到下一行行首，看着像排版坏了 -->
          <view class="item-line">
            <text class="item-day">{{ dayText(it.pickupDate) }}</text>
            <view class="item-unit">
              <text class="item-dot">·</text>
              <text class="item-store">{{ storeNameOf(it) }}</text>
            </view>
            <view class="item-unit">
              <text class="item-dot">·</text>
              <text class="item-dish">{{ dishNameOf(it) }}</text>
            </view>
            <text class="item-qty">× {{ countOf(it.quantity) }}</text>
            <!-- 这一行显示行小计（单价 × 数量）并明写「小计」：后端的 price 列语义是单价，
                 而 ×2 与金额并排天然被读成小计，备货/对账要的正是「这一行值多少钱」；
                 单价与小计两处同显只会让商家再猜一次哪个才是钱 -->
            <view class="item-unit">
              <text class="item-price">¥{{ fmtPrice(lineMoney(it)) }}</text>
              <text class="item-money-tag">小计</text>
            </view>
          </view>
          <view class="item-line item-sub-line">
            <text class="item-sub">单号 {{ tailOf(it.orderId) }}</text>
            <view class="item-unit">
              <text class="item-dot">·</text>
              <text class="item-sub">{{ timeText(it.createTime) }}</text>
            </view>
            <text class="item-tag item-tag-status">{{ statusText(it.status) }}</text>
            <!-- 未支付角标条件留全（payStatus===0 且非 cancelled）：后端已排除 cancelled，
                 但这里不依赖对方行为——少写半个条件就是一处「上游改了才会出错」的隐性耦合 -->
            <text v-if="showUnpaid(it)" class="item-tag item-tag-unpaid">未支付</text>
          </view>
        </view>
      </view>

      <!-- 加载更多：触底自动 + 按钮兜底（范式照 front/pages/order/order.vue:90-96）；
           hasMore 用后端回显值，为 false 时整块换成「共 N 条」（N 取 shownTotal，见 computed 注释） -->
      <view class="load-more">
        <button v-if="hasMore" class="btn-more" :class="{ 'btn-more-cooling': retryLock > 0 }"
          :disabled="loadingMore || retryLock > 0" @click="loadMore">
          <text>{{ retryLock > 0 ? retryLock + 's后可重试' : (loadingMore ? '加载中…' : '加载更多') }}</text>
        </button>
        <text v-else class="no-more">共 {{ shownTotal }} 条</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端供货明细（G-03 / F-17 只读页）：GET /api/merchant/order-items 分页列出逐条订单行。
// 三条页面纪律：① 状态/日期只交后端过滤，页内零过滤；② page/size 以回显值为准（后端「夹而不拒」）；
// ③ 手机号只显示 maskedPhone 语义的脱敏值，本页干脆不渲染联系电话（备货用不上，少一处明文面）。
// 归属由服务端 @RequestAttribute 解析，页面不发任何 merchantId；401 由 api 层清态回登录页，本页不处理跳转。
import { getMerchantOrderItems, retrySecondsOf } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/plan-orders/plan-orders'

// 状态中文说法与 C 端同源（front/pages/order/order.vue:128 STATUS_MAP 同三个键），两端口径不各写一份
const STATUS_TEXT = { pending_pickup: '待自提', completed: '已完成', cancelled: '已取消' }

// 429 冷却秒数的回落档：本端点（GET /api/merchant/order-items）限流窗口是 60 秒
// （MerchantOrderController:68 USER 30 次 / 60 秒），故回落取 60。
// 起锁值优先转述后端 429 data 里的 retryAfterSeconds（F-22 下发、F-23 起请求层 reject 带 data），
// 取不到才回落到本常量。回落值取整个窗口，因为它是「后端不给数时唯一确定的保守上限」；
// 回速（30 次/分实测只有 2 秒）是桶回充一格的间隔，不代表重试一定不挨 429 —— 两个数不是一回事，别混用。
// 本页触底自动加载与「加载更多」都归这枚闸门管，故回落偏保守（锁长一点）比锁短更稳。
const RETRY_COOLING_SECONDS = 60

export default {
  data() {
    return {
      // tab 的 key 直接就是后端 status 参数值（多一层映射就多一处漂移面）
      tabs: [
        { key: 'all', label: '全部' },
        { key: 'pending_pickup', label: '待自提' },
        { key: 'completed', label: '已完成' }
      ],
      status: 'all',
      // '' = 不带 date = 不限自提日期（本端点无默认值），页内不自行推算日期
      date: '',
      list: [],
      // 生效分页值（一律取后端回显，回显缺失才退回本次请求值）
      page: 1,
      size: 20,
      total: 0,
      hasMore: false,
      // 四态互斥：loading | error | empty | ready
      viewState: 'loading',
      errorText: '',
      errorCode: 0,
      // 仅驱动「加载更多」按钮的加载中文案：整页四态不受它影响（翻页失败不该把已加载的列表换成错误卡）
      loadingMore: false,
      // 429 冷却剩余秒数（>0 时重试按钮与触底自动加载同时熄 fire）与驱动它的定时器：
      // 与 dishes.vue 同口径，否则每次点/每滚一次都在给这个限流桶续命
      retryLock: 0,
      retryLockTimer: null
    }
  },
  computed: {
    dateLabel() {
      // 后端 MerchantOrderController:58 明写本端点「date 为空 = 不限自提日期（无默认值）」：
      // 这里写成「默认」会让商家把翻出来的历史单当成今天要备的货
      return this.date || '不限日期'
    },
    hasFilter() {
      return !!(this.date || this.status !== 'all')
    },
    // 「共 N 条」给 max(后端 total, 已加载行数)：翻页期间有单被取消会让后端 total 收缩，
    // 屏上已经渲染的行又不会凭空消失，两者不能互相打脸（计数比可见行数小 = 数据被吞的错觉）
    shownTotal() {
      const n = Number(this.total)
      const loaded = Array.isArray(this.list) ? this.list.length : 0
      return Math.max(Number.isFinite(n) && n >= 0 ? n : 0, loaded)
    },
    // F-23-fix/FF-1：429 这行不带时长，秒数只在闸门那一处（本页模板的 retryLock 倒计时行）出现一次
    errorLeadText() {
      if (this.errorCode === 429) return '点得太快了，稍后再刷新'
      if (this.errorCode === 403) return '暂无权限查看供货明细，请回工作台确认账号状态'
      // 列表端点的 404 只有「后端还没更新/路径不通」这一种解释，服务起来后重试就能成，
      // 故不照 dishes.vue（那里 404 = 按 id 取单行取不到）把 404 归入「重试无望」
      if (this.errorCode === 404) return '供货明细接口暂不可用，可能是后端服务还没更新，请稍后重试'
      return '供货明细加载失败，请检查网络后重试'
    },
    // 重试有没有可能成功：只有 403（角色闸门，重试必然还是同一枚错误）判死并给「回工作台」出口；
    // 401 走不到这一态（请求层已回登录页），404/429/网络异常都属于「等一下或修好就能成」
    errorRetryable() {
      return this.errorCode !== 403
    }
  },
  onLoad(query) {
    // 深链带 date 时不只验形状，还验它确有其日（'2026-02-30' 过得了 ^\d{4}-\d{2}-\d{2}$ 却不是日子）：
    // 脏值一律按「未指定」丢掉、绝不带进请求，否则后端 400 落在错误卡上会被「请检查网络」指错方向，
    // 而按空条件筛出一片空列表、页面上却还显示着那串脏日期，看着像「搜索坏了」
    const raw = String((query && query.date) || '')
    this.date = this.isValidDateStr(raw) ? raw : ''
  },
  onShow() {
    // 每次进入回到第一页重拉（备货量与状态在别的页可能已变），带 in-flight 守卫防连点
    // 重第一页成功后必须一并回顶（标记由 fetchList 消费）：翻到第 3 页再切标签页回来只剩 20 行，
    // 滚动条悬在空处就像数据被吞了（同 dishes.vue:246-250 的回顶纪律）
    this._resetScrollTop = true
    this.fetchList(true)
  },
  // 触底自动加载下一页（与「加载更多」按钮同一入口）
  onReachBottom() {
    this.loadMore()
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
    // 继续每秒写一次已死组件的状态（照 dishes.vue 的 onUnload 收尾）
    this.stopRetryLock()
  },
  methods: {
    // ==================== 数据加载 ====================
    // @param {boolean} reset true = 按当前条件重拉第一页（切 tab / 改日期 / onShow / 重试），false = 追加下一页
    // @param {boolean} [resetView] 条件变了要立刻退回加载态让旧行让位（否则会被读成新条件的结果）；
    //                              onShow 的静默重拉不传，已有列表时保持可见，避免每次回页闪一次白卡
    async fetchList(reset, resetView) {
      // 在途守卫：切 tab / 改日期 / 加载更多三者共用。在途期间的任何新触发都记下来排队，
      // 收尾后按最新条件重发，保证「最终渲染的条件 = 最后一次生效的条件」
      if (this._loadingList) {
        this._reloadQueued = true
        if (reset) this._queuedReset = true
        if (resetView) this._queuedView = true
        return
      }
      this._loadingList = true
      const condKey = this.condKey()
      const targetPage = reset ? 1 : this.page + 1
      if (!reset) this.loadingMore = true
      else if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getMerchantOrderItems({
          date: this.date,
          status: this.status,
          page: targetPage,
          size: this.size
        })
        // 回包晚于条件变更（在途期间切了 tab / 改了日期）：整批丢弃，重发 —— 把旧条件的行拼进新条件的列表最要命
        if (this.condKey() !== condKey) {
          this._reloadQueued = true
          this._queuedReset = true
          this._queuedView = true
          return
        }
        const data = (res && res.data) || {}
        const rows = Array.isArray(data.list) ? data.list : []
        // 分页三值一律以后端回显为准（size 被夹到 [1,50]、page<1 归一 1，都是夹而不拒）：
        // 用请求值推进页码的话，下一页就会跳过被夹掉的那一段或重复取同一页
        const echoPage = Number(data.page)
        const echoSize = Number(data.size)
        // 追加走 concat 生成新数组（见 appendRows），重置则整批换成第一页
        const merged = reset ? rows : this.appendRows(rows)
        this.list = merged
        const echoTotal = Number(data.total)
        this.page = echoPage >= 1 ? echoPage : targetPage
        this.size = echoSize >= 1 ? echoSize : this.size
        this.total = Number.isFinite(echoTotal) && echoTotal >= 0 ? echoTotal : merged.length
        // hasMore 只认真假值；后端没给时才按「已加载条数 < total」兜底，缺字段不等于「没有更多」
        this.hasMore = data.hasMore === true || data.hasMore === false
          ? data.hasMore
          : this.list.length < this.total
        this.errorText = ''
        this.errorCode = 0
        this.viewState = this.list.length ? 'ready' : 'empty'
        // 重拉第一页成功后回顶（只在 onShow 置的标记下执行一次）：屏上行数从几百行缩到一页，
        // 不回顶就会停在一片空白上，被读成「数据没了」
        if (reset && this._resetScrollTop) {
          this._resetScrollTop = false
          // #ifdef H5
          document.body.scrollTop = 0
          // #endif
        }
      } catch (e) {
        if (e && e.code === 401) {
          // 跳转与清态都由请求层做完（含「请先登录」toast），这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        if (this.condKey() !== condKey) {
          // 条件在途期间已变更：这次失败属于旧条件，不外抛给现在的列表，按新条件重发
          this._reloadQueued = true
          this._queuedReset = true
          this._queuedView = true
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = Number((e && e.code) || 0)
        // 429：给秒级冷却闸门（错误卡的重试按钮与触底自动加载都归它管），冷却期内点不动 ——
        // 否则用户每点一次、商家每滚一次都在续这个限流桶，会把该商家后面 1 分钟的全部分额度花光
        if (Number(e && e.code) === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        // 翻页失败（已有列表在手）不动整页态：保留现场只 toast，商家可再点一次「加载更多」；
        // 首屏/重拉失败才整页换错误卡（照 front/pages/order/order.vue:215-223 的分流）
        if (reset || !this.list.length) {
          this.viewState = 'error'
        } else {
          uni.showToast({ title: this.errorText || '加载更多失败，请重试', icon: 'none' })
        }
      } finally {
        this.loadingMore = false
        this._loadingList = false
        if (this._reloadQueued) {
          // 排队标记只在被显式置 true 时才升级成「重第一页 / 退回加载态」：
          // 连点两次「加载更多」留下的应是追加请求，把它当重拉第一页会把已加载的行清掉
          const asReset = this._queuedReset === true
          const asView = this._queuedView === true
          this._reloadQueued = false
          this._queuedReset = false
          this._queuedView = false
          this.fetchList(asReset, asView)
        }
      }
    },
    // 当前生效条件（tab + date）的指纹：回包时比对，防止旧条件的响应落到新条件的列表上
    condKey() {
      return this.date + '|' + this.status
    },
    // 日期串是否为「确有其日」：先验 ^\d{4}-\d{2}-\d{2}$ 形状，再按本地分量构造后回读年月日比对
    // （2026-02-30 会被 Date 顺延成 2026-03-02，回读不等即判脏）。
    // 刻意不走 new Date('YYYY-MM-DD')：那串按 UTC 解析，本地回读会少一天，与本页 timeText
    // 「不让时区平移一次」的纪律同源
    isValidDateStr(s) {
      const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(s || ''))
      if (!m) return false
      const y = Number(m[1])
      const mo = Number(m[2])
      const d = Number(m[3])
      if (mo < 1 || mo > 12 || d < 1 || d > 31) return false
      const dt = new Date(y, mo - 1, d)
      return dt.getFullYear() === y && dt.getMonth() === mo - 1 && dt.getDate() === d
    },
    // 追加成新数组而不是 push：跨页边界时后端可能把同一条 orderItemId 在相邻两页各给一次（新单挤进上一页边界），
    // 去重后 concat 出一个新引用，Vue 侧一次替换，既不会重复渲染也不依赖就地变更的侦测时机
    appendRows(rows) {
      const seen = {}
      this.list.forEach((r) => {
        const k = this.rowKey(r, 0)
        if (k.charAt(0) === 'i') seen[k] = true
      })
      const fresh = rows.filter((r) => {
        const k = this.rowKey(r, 0)
        return !(k.charAt(0) === 'i' && seen[k])
      })
      return this.list.concat(fresh)
    },
    // ==================== 筛选交互 ====================
    loadMore() {
      // 429 冷却闸门同样要拦住自动触底：只拦错误卡上的按钮的话，限流后往下滚会一路续桶，
      // 把该商家其它读接口（备货单、商品列表）一起打死
      if (this.retryLock > 0) return
      if (this._loadingList || !this.hasMore) return
      this.fetchList(false)
    },
    switchTab(key) {
      if (this.status === key) return
      this.status = key
      this.fetchList(true, true)
    },
    onDateChange(e) {
      const v = String((e && e.detail && e.detail.value) || '')
      // 与 onLoad 的深链校验同一把尺子（形状 + 确有其日），免得两处各验一半、口径漂移
      this.date = this.isValidDateStr(v) ? v : ''
      this.fetchList(true, true)
    },
    clearDate() {
      if (!this.date) return
      this.date = ''
      this.fetchList(true, true)
    },
    resetFilters() {
      this.status = 'all'
      this.date = ''
      this.fetchList(true, true)
    },
    // ==================== 渲染辅助 ====================
    rowKey(r, i) {
      const id = r && r.orderItemId
      return (id === null || id === undefined || id === '') ? 'x' + i : 'i' + id
    },
    storeNameOf(it) {
      // storeId 为 NULL 的行后端已命名「未指定自提点」，这里再兜一层：拿到 null/空串不能渲染成空白
      return (it && (it.storeName || '未指定自提点')) || '未指定自提点'
    },
    dishNameOf(it) {
      return (it && it.dishName) || '未命名商品'
    },
    dayText(v) {
      const s = String(v == null ? '' : v).trim()
      return /^\d{4}-\d{2}-\d{2}$/.test(s) ? s : '日期未回显'
    },
    // orderId 是 21 位长串（表主键是 varchar），整串显示会把整行撑破：只留后 6 位并前置省略号表明这是截断值
    tailOf(v) {
      const s = String(v == null ? '' : v).trim()
      if (!s) return '--'
      return s.length <= 6 ? s : '…' + s.slice(-6)
    },
    // 下单时间：带 '-' 的后端字符串原样透出（只把 ISO 的 'T' 换空格、砍到分钟）。
    // 不走 new Date() 再格式化：那会把无时区的 LocalDateTime 按本地时区平移一次，屏上值就不再是库里那一位
    timeText(v) {
      const s = String(v == null ? '' : v).trim()
      if (!s) return '--'
      if (s.indexOf('-') > -1) return s.replace('T', ' ').slice(0, 16)
      const n = Number(s)
      if (!Number.isFinite(n)) return s
      const d = new Date(n)
      if (Number.isNaN(d.getTime())) return s
      const p = (x) => (x < 10 ? '0' : '') + x
      return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes())
    },
    statusText(v) {
      // 字典里没有的状态值原样透出，不替后端猜中文名
      return STATUS_TEXT[v] || (v || '--')
    },
    showUnpaid(it) {
      return !!(it && it.payStatus != null && Number(it.payStatus) === 0 && it.status !== 'cancelled')
    },
    countOf(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n : 0
    },
    // 价格两位小数（与 dishes.vue / C 端 index.vue 同口径，同一份数据两端显示不一致会被当成数据问题）
    fmtPrice(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n.toFixed(2) : '0.00'
    },
    // 行小计 = 单价 × 数量：先按「分」取整再还原，避免 0.1×3 这类浮点毛刺显示成 0.30000000000000004
    // （任一侧不是有限数就当 0，与 countOf/fmtPrice 的兜底口径一致）
    lineMoney(it) {
      const price = Number(it && it.price)
      const qty = Number(it && it.quantity)
      const p = Number.isFinite(price) ? price : 0
      const q = Number.isFinite(qty) ? qty : 0
      return Math.round(p * q * 100) / 100
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
    // ==================== 跳转 ====================
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 两道守卫缺一不可：
    // ① 栈顶守卫：监听挂在 document 上，navigateTo/reLaunch 都不保证触发本页 onUnload，
    //    栈里多挂一份 handler 就会一次滚两倍。
    // ② 弹层放行：日期 picker（.uni-picker-container）与 modal/toast 打开时滚轮归弹层，
    //    这里不能 preventDefault 去滚背景，否则「弹层里滚不动、页面却在滚」；
    //    textarea/input 同列表内——表单控件上的滚轮归控件，被页面抢走就会连带影响长按选字/选中。
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
      // H5 端原生滚动被上面 preventDefault 吃掉了，uni 的 onReachBottom 不可能触发（小程序端不受影响）。
      // 触底翻页在这里补上：判定用 body 自己的几何，阈值 50px；闸门全在 loadMore 里
      // （retryLock / _loadingList / hasMore），所以这里无条件调用是安全的。
      if (document.body.scrollTop + window.innerHeight >= document.body.scrollHeight - 50) this.loadMore()
    }
    // #endif
  }
}
</script>

<style scoped>
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.empty-row/.guide-link/.cell-arrow 取自 common.css） */

/* ==================== 1. 筛选卡 ==================== */
.filter-card { margin-top: 24rpx; padding: 20rpx 24rpx; }
/* 三 tab：视觉照 front/pages/order/order.vue:321-328 .status-tab，配色用商家端主红（与 dishes.vue .dish-tab 同一档） */
.order-tabs { display: flex; gap: 12rpx; }
.order-tab {
  flex: 1; text-align: center;
  font-size: 26rpx; color: #666666;
  background-color: #F5F5F5; border-radius: 24rpx;
  padding: 12rpx 0; border: 1rpx solid #EEEEEE;
}
.order-tab.active { color: #FFFFFF; font-weight: 700; background-color: #E02020; border-color: #E02020; }
/* 日期下拉（picker 是原生组件，显式占满整行才有整行点击区，同 dishes.vue .cate-picker） */
.date-picker { display: block; width: 100%; }
.picker-row {
  display: flex; align-items: center; gap: 12rpx;
  margin-top: 20rpx; padding-top: 20rpx;
  border-top: 1rpx solid #F5F5F5;
}
.picker-label { flex-shrink: 0; font-size: 26rpx; color: #999999; }
.picker-value {
  flex: 1; min-width: 0; text-align: right;
  font-size: 26rpx; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.picker-arrow { flex-shrink: 0; font-size: 34rpx; color: #CCCCCC; }
/* 「清除日期」收成胶囊 + 按下态：与备货单页日期条的按钮同一档，内边距不小于 12rpx 24rpx */
.date-clear {
  display: inline-block; margin-top: 12rpx; text-align: center;
  font-size: 24rpx; font-weight: 600; color: #E02020; line-height: 1.4;
  background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 999rpx;
  padding: 12rpx 24rpx;
}
/* 按下反馈（hover-class 目标）：移动端没有 :hover，靠 uni-app 的 hover-class 才有这一态 */
.pill-hover { color: #B01818; background-color: #FFF7F0; border-color: #B01818; }

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

/* ==================== 5. 明细行 ==================== */
.list-card { margin-top: 24rpx; padding: 0 24rpx; overflow: hidden; }
.order-item { padding: 24rpx 0; border-bottom: 1rpx solid #F5F5F5; }
.order-item:first-child { padding-top: 20rpx; }
.order-item:last-child { border-bottom: none; }
/* 长店名/长商品名靠 flex-wrap 换行 + 单元自身 ellipsis，两种溢出都不会撑破卡片 */
.item-line { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8rpx; }
.item-sub-line { margin-top: 8rpx; }
.item-day {
  flex-shrink: 0; font-size: 24rpx; color: #999999; line-height: 1.5;
  font-variant-numeric: tabular-nums;
}
.item-dot { flex-shrink: 0; font-size: 24rpx; color: #DDDDDD; line-height: 1.5; }
/* 圆点 + 其后那段文本 = 一个不换行单元：整组一起折行，绝不会出现行首孤立圆点；
   min-width:0 让单元内的 ellipsis（店名/商品名）仍然生效 */
.item-unit { display: flex; align-items: center; min-width: 0; gap: 8rpx; }
.item-store {
  flex: 0 1 auto; min-width: 0; max-width: 240rpx;
  font-size: 26rpx; color: #666666; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.item-dish {
  flex: 1 1 auto; min-width: 0;
  font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.item-qty {
  flex-shrink: 0; font-size: 26rpx; color: #333333; line-height: 1.5;
  font-variant-numeric: tabular-nums;
}
.item-price {
  flex-shrink: 0; margin-left: 8rpx;
  font-size: 28rpx; font-weight: 700; color: #E02020; line-height: 1.5;
  font-variant-numeric: tabular-nums;
}
/* 「小计」两字的小标签：金额语义的唯一说明，缺了它 ×2 与 ¥6.60 并排就会被读成两种数 */
.item-money-tag { flex-shrink: 0; font-size: 20rpx; color: #999999; line-height: 1.4; }
.item-sub {
  flex: 0 1 auto; min-width: 0;
  font-size: 22rpx; color: #999999; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.item-tag {
  flex-shrink: 0; margin-left: 8rpx;
  font-size: 20rpx; font-weight: 600; line-height: 1.4;
  border-radius: 8rpx; padding: 4rpx 12rpx;
}
.item-tag-status { color: #999999; background-color: #F5F5F5; }
.item-tag-unpaid { color: #FFFFFF; background-color: #E02020; }

/* ==================== 6. 加载更多 ==================== */
.load-more { display: flex; flex-direction: column; align-items: center; margin-top: 24rpx; }
.btn-more {
  margin: 0; padding: 0 48rpx;
  height: 72rpx; line-height: 72rpx;
  background-color: #FFFFFF; color: #E02020;
  font-size: 26rpx; font-weight: 600;
  border: 1rpx solid #E02020; border-radius: 999rpx;
}
.btn-more[disabled] { color: #F5B5B0; background-color: #FFFFFF; border-color: #F5B5B0; }
/* 冷却态（429 后 retryLock > 0）：置灰取值口径同 .error-retry-cooling（color #BBBBBB / border #EEEEEE），
   再加 not-allowed 把「这一下点不动」说给人看。写成 .btn-more.btn-more-cooling 是为了压过
   上一条 .btn-more[disabled]（两者同特异度，靠源码顺序 + 类名叠加取胜），不改原类。 */
.btn-more.btn-more-cooling { color: #BBBBBB; border-color: #EEEEEE; cursor: not-allowed; }
.no-more { font-size: 24rpx; color: #999999; line-height: 1.6; }

.page-bottom-space { height: 60rpx; }
</style>
