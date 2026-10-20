<template>
  <view class="page-container">
    <!-- ==================== 1. 日期条（首屏默认明日，见 tomorrowStr 的理由） ====================
             显示值优先取后端回显的 data.date（arrivals 端点确实回显，PartnerStoreServiceImpl 里 data.put("date", ...)）；
             步进基准用本次请求带出去的 queryDate（照 pickup-plan.vue 的 stepDay 定稿）——慢网下连点时回显
             还是上一轮的日期，以它为准会把第 2、3 次点击算成同一天的 +1，点击被吞掉。 ==================== -->
    <view class="card date-card">
      <view class="date-field-row">
        <!-- 这一枚是「到货日期」（arrival_batch.batch_date），与核销台那枚「自提日期」是两件事，
             叫法必须分开：写成同一个词会让主理人以为批次没到 = 今天没人来提货 -->
        <text class="date-field-label">到货日期</text>
      </view>
      <view class="date-row">
        <view class="date-step" hover-class="pill-hover" @click="stepDay(-1)">前一天</view>
        <text class="date-current">{{ effectiveDate || '--' }}</text>
        <view class="date-step" hover-class="pill-hover" @click="stepDay(1)">后一天</view>
      </view>
      <view class="date-sub-row">
        <view class="date-quick" hover-class="pill-hover" @click="goToday">今天</view>
        <text class="date-note">{{ storeText }}</text>
      </view>
    </view>

    <!-- ==================== 2. 加载中（首屏与切日期后的重拉都走这一态，避免上一日期的批次被读成本日期的） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载到货批次…</text>
    </view>

    <!-- ==================== 3. 错误态（≠ 空态：空态是服务端确认「该日一批都没有」，错误态是根本没拿到数据） ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="loadBatches(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 4. 空态 ====================
             原文案「批次由平台生成，见 G-05 运营端」是双重指错，已删：G-05 实为「商家端营销参与态 + 数据看板」
             （07 册 §G-05，F-19 已交付），运营端是 G-06，而**两期都没有批次生成页**——全系统对 arrival_batch
             只有「读 + 确认」两个接口，零写入端点。不能把主理人指向一个不存在的页面。
             仍然不说「加载失败」也不说「没货」：前者是骗，后者越界（本页不知道货）。 ==================== -->
    <view v-else-if="viewState === 'empty'" class="card empty-card">
      <view class="empty-row">
        <text class="empty-text">{{ effectiveDate || '该日' }} 无到货批次记录</text>
        <text class="empty-sub">批次由平台侧配货，当前版本未开放平台端录入；如应有货未到，请联系平台运营</text>
      </view>
      <view class="empty-actions">
        <text class="guide-link" @click="stepDay(-1)">看前一天</text>
        <text class="guide-link" @click="stepDay(1)">看后一天</text>
      </view>
    </view>

    <!-- ==================== 5. 就绪：批次卡片 ==================== -->
    <template v-else>
      <view class="card batch-card" v-for="(b, bi) in batches" :key="batchKey(b, bi)">
        <view class="batch-head">
          <text class="batch-title">{{ batchTitle(b) }}</text>
          <text class="item-tag" :class="'batch-tag-' + statusTone(b)">{{ statusText(b) }}</text>
        </view>

        <text v-if="confirmTimeText(b) !== ''" class="batch-row">确认时间：{{ confirmTimeText(b) }}</text>
        <text v-else-if="isConfirmed(b)" class="batch-row">确认时间：未回显</text>
        <!-- remark 是后端列（arrival_batch.remark，一期由平台/运营写入）：没这个键或空值都不出一行，
             渲染成「备注：null」比不渲染更糟 -->
        <text v-if="remarkText(b) !== ''" class="batch-row">备注：{{ remarkText(b) }}</text>

        <view class="batch-items">
          <view class="item-line" v-for="(it, ii) in itemsOf(b)" :key="itemKey(b, it, ii)">
            <text class="item-dish">{{ itemName(it) }}</text>
            <text class="item-qty">应收 {{ countOf(it.expectedQty) }}</text>
            <text class="item-qty">实收 {{ actualText(it) }}</text>
            <!-- 短少原因：契约 §3 列了 shortReason，但后端明细 SQL 一条都不出这个键
                 （ArrivalItemMapper#selectItemsByBatchIds 只 SELECT 六列），所以「有键才渲染」，不替后端编一个 -->
            <text v-if="shortReasonText(it) !== ''" class="item-short">短少：{{ shortReasonText(it) }}</text>
          </view>
          <view v-if="!itemsOf(b).length" class="item-line">
            <text class="item-empty">该批次暂无条目</text>
          </view>
        </view>

        <!-- 确认到货：仅 status ∈ {planned, arriving} 可点（与后端四条件 UPDATE 的 IN 白名单同集合，
             ArrivalBatchMapper:46-49）。confirmed 置灰并显确认时间；short 也置灰——后端对它给 400
             「该批次已登记短少，不可直接确认」（PartnerStoreServiceImpl.java:328-331），按钮留着就是诱骗报错 -->
        <button
          class="btn-main batch-confirm"
          :class="{ 'btn-disabled': !canConfirm(b) || confirmingId !== null || writeRetryLock > 0 }"
          :disabled="!canConfirm(b) || confirmingId !== null || writeRetryLock > 0"
          @click="confirmBatch(b)"
        ><text>{{ confirmLabel(b) }}</text></button>
        <!-- F-28.5 路 A（X-5）：写侧闸门在本屏唯一的带数处是那张被点批次的常驻反馈行（feedbackText 读实时 writeRetryLock），
             这一行与按钮字都不带数——改前「操作过于频繁，Ns 后可再确认」+ 按钮「Ns后可确认」+ 反馈行三处同报一个数 -->
        <text v-if="writeRetryLock > 0 && canConfirm(b)" class="form-hint">操作过于频繁，请稍后再试</text>
        <text v-else-if="!canConfirm(b) && !isConfirmed(b)" class="form-hint">{{ disabledReason(b) }}</text>

        <!-- 常驻反馈（每个批次一行，四支口径同核销台）：写在被点的那张卡里，
             多批次时不会把上一张卡的结论挪到这一张上 -->
        <text v-if="feedbackOf(b)" class="batch-feedback" :class="'batch-feedback-' + feedbackOf(b).tone">{{ feedbackText(b) }}</text>
      </view>

      <!-- FE-12：确认成功但「只重拉这一条」没成时给一行降级提示——页面不动四态、那张卡的反馈也不撤销，
           只如实说明屏上这一条可能还是旧值（原来是全静默，看不出来） -->
      <text v-if="batchRefreshFailed" class="form-hint">最新批次没补拉到，屏上状态可能偏旧；点「今天」或步进日期可重查</text>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 主理人到货批次（F-18.17 §3）：GET /api/partner/arrivals?date= 按日读批次 + 确认到货。
// 页面纪律与核销台同一套（母版 front-merchant/pages/pickup-plan/pickup-plan.vue）：
// ① 四态单值互斥 loading|error|empty|ready；② 冷却锁只在 code===429 起；③ onUnload 成对清定时器；
// ④ 请求全走 @/api/index.js（本页零原生请求）；⑤ 只渲染后端白名单键，缺键就如实不渲染，
//    绝不为了「页面好看」去前端 JOIN 别的接口补数据。
// 本表 uk_store_date 保证「一店一日至多一批」，故这一页没有分页（RB 的手写分页在核销台那一页用）。
// storeId 选择器本页不传（FE-3：一期不提供选店，本页没有选店入口）：留空即由服务端按
// 「名下 id 最小的一家」解析（PartnerStoreServiceImpl.java:368-371 Collections.min），
// 页内那一行「门店编号 N」就是回包的 data.storeId——显示哪家、确认哪家，永远是那一家。
// 核销台的到货警示条是另一条通道：它逐店**显式带 storeId** 查后聚合（FE-2），管的是「名下几家没确认」，
// 与本页「只看一家」各说各的事，不构成同一屏上的矛盾结论。
// ?date= 是可选入参（FE-6：警示条「去看批次」会带日期过来，因为批次多在明日生成）。
import { getPartnerArrivals, confirmArrival, retrySecondsOf, coolingText } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/arrival/arrival'

// 可点「确认到货」的状态集合：与后端条件 UPDATE 的 IN 白名单逐字相同（ArrivalBatchMapper:46-49）
const CONFIRMABLE_STATUS = ['planned', 'arriving']
const BATCH_CONFIRMED = 'confirmed'
const BATCH_SHORT = 'short'

// 批次状态中文说法：字典里没有的值原样透出，不替后端猜中文名（同 plan-orders.vue 的 STATUS_TEXT 那条纪律）
const STATUS_TEXT = { planned: '计划到货', arriving: '到货中', confirmed: '已确认', short: '已登记短少' }
const STATUS_TONE = { planned: 'info', arriving: 'warn', confirmed: 'ok', short: 'err' }

// 入参日期形状：与后端 DATE_PATTERN 同一口径（PartnerOrderServiceImpl.java:56），只用来洗深链带进来的 ?date=
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/

// FE-5：429 冷却时长不再硬编码 60——后端 429 信封的 data.retryAfterSeconds 优先（BE-5），
// 取不到才回落本地 60（与读侧 @RateLimit 的 60s 窗口同档）。与核销台同一口径，两处不各写一套算法。
const RETRY_FALLBACK_SECONDS = 60

// FE-10 + F-28.5 路 A：同页 429 只留一句说法（清单加载与确认提交两支共用），本页不留私有实现——
// coolingText 唯一实现上提在 api 层（与 retrySecondsOf 同层、同先例），
// 带数支只读实时闸门，无参支就是 X-3 起锁点那句不带数的整句

// retrySecondsOf 已收敛到 api 层（F-25 路 1）：front-partner 内唯一实现是 front-partner/api/index.js 的 retrySecondsOf，
// 本页档位由上面的 RETRY_FALLBACK_SECONDS 作为第二参传进去。
// 载荷来源：请求层 handleEnvelope 的 reject 带 data（front-partner/api/index.js:183-186 早已透传），
// 429 锁的 {retryAfterSeconds} 就是从这里进页面；网络异常/信封不可解析时 data 为 null，走本地回落 60。

export default {
  data() {
    return {
      // 请求要下发的日期（本页永远给值，不吃后端「缺省=明日」那条默认，见类注释）
      queryDate: '',
      // 显示用日期：一律以 data.date 回显为准，回显缺失才退回本次请求值（一份真值）
      effectiveDate: '',
      // 本次回包的 storeId（后端 D-22 解析出来的那一家）：只用于「你看的是哪家」这一行字
      resolvedStoreId: null,
      batches: [],
      // 四态互斥：loading | error | empty | ready
      viewState: 'loading',
      errorText: '',
      errorCode: 0,
      // 单飞锁：正在确认的批次 id（null = 空闲）。finally 复位，失败也不留在非 null
      confirmingId: null,
      // 每个批次的常驻反馈：{ [批次id字符串]: {tone,text} }；四支（成功/幂等/404/400/429）都落这里
      feedbackMap: {},
      // FE-12：「只重拉这一条」失败的降级提示位（true = 屏上状态可能偏旧，页尾给一行实话）
      batchRefreshFailed: false,
      // 429 冷却剩余秒数与驱动它的定时器（onUnload 成对清）
      // X-1 拆锁（母版 dishes.vue catRetryLock）：retryLock = 读侧闸门（loadBatches 撞 arrivals 读桶，
      // 本页刻意不给读入口装闸门，见 loadBatches 注释），writeRetryLock = 写侧闸门（confirmBatch 撞确认写桶）。
      // 共用一枚时：读侧一次非 429 失败的 stopRetryLock() 会把写侧闸门清成 0——这正是本页的实病。
      retryLock: 0,
      retryLockTimer: null,
      writeRetryLock: 0,
      writeRetryLockTimer: null
    }
  },
  computed: {
    errorLeadText() {
      // FE-10 + F-28.5 路 A（X-5）：429 这一行不带数——同屏闸门行「{{ retryLock }}s后可重试」是读侧唯一带数处，
      // 主句走 coolingText 无参整句（与确认提交那一支同句式；姿势照 apply/workspace 的 FF-1）
      if (this.errorCode === 429) return coolingText()
      if (this.errorCode === 403) return '暂无权限查看到货批次，请回工作台确认账号状态'
      if (this.errorCode === 404) return '到货批次接口暂不可用，可能是后端服务还没更新，请稍后重试'
      // X-2b（四路分流，母版 front-merchant/pages/dashboard/dashboard.vue 的同名 computed）：500 是服务端
      // 承认自己处理出错，同屏第二行透出的正是它那句原文，第一行却叫主理人去查网络——因果方向相反
      if (this.errorCode === 500) return '到货批次加载失败，服务端处理出错，稍后重试'
      // 只有网络层失败才许出现「网络」字样：front-partner/api/index.js 的 request fail 支回 code:-1 /
      // message「网络异常」，本页 errorCode 因此是 -1；没带码的失败归 0，两支同一条出路
      if (this.errorCode <= 0) return '到货批次加载失败，请检查网络后重试'
      // 其余服务端业务码（400 一类）：不猜成因，报「加载失败」＋第二行透出后端 message 原文
      return '到货批次加载失败，请稍后重试'
    },
    // 重试有没有可能成功：只有 403（归属集合为空）判死并给「回工作台」出口；401 走不到这一态
    errorRetryable() {
      return this.errorCode !== 403
    },
    // 门店这一行：后端 data.storeId 有值就报 id（多店主理人靠它对上工作台那张「共 N 家店」），
    // 没值就整行不出——本页不认门店名（明细接口不在本轮范围内，不为了这一行字多发一次请求）
    storeText() {
      return this.resolvedStoreId === null || this.resolvedStoreId === undefined
        ? ''
        : '本页只显门店 ' + this.resolvedStoreId + ' 的批次，一期不提供选店'
    }
  },
  onLoad(options) {
    // FE-6：核销台的「去看批次」会把目标日期带进来（?date=YYYY-MM-DD，批次多在明日生成）。
    // 形状不符的一律忽略、由 onShow 回落到「今天」——深链参数不洗形状就入请求，
    // 后端 DATE_PATTERN 会直接 400（同 store.vue 对 ?storeId= 的姿势）。
    const d = options && options.date ? String(options.date) : ''
    if (DATE_RE.test(d)) this.queryDate = d
  },
  onShow() {
    // 每次进入重拉（确认动作在别的页/别的设备做过也要第一时间反映）。
    // 不传 resetView：日期没变就不该把已有卡片整页换成加载态白卡（静默重拉成功后直接覆盖，
    // 姿势照 pickup-plan.vue 的 onShow）
    if (!this.queryDate) this.queryDate = this.tomorrowStr()
    // X-4b：回页重拉要过读侧闸门。onShow 属「页面自己发的请求」而不是用户的点击，所以它没有旁路资格——
    // 批次页与核销台来回切几次就是在往 arrivals 读桶（PartnerController#arrivals，USER 30/60）连续续发。
    // 闸门只加在**这一处调用点**，loadBatches 本体保持无闸门（那条豁免是刻意的：用户主动步进/回到今天
    // 必须立发，否则日期条已变、卡片还是上一天，而 ready 态没有重试入口，那一下就永久丢了）。
    // 被拦时静默、不给 toast（dishes/marketing/dashboard 三页的 onShow 母版同形；冷却中的秒数本来就写在屏上：
    // 错误卡那一行与「加载更多/重试」那两处）。
    // 刻意不复制商家端 dishes 页的「刚从新增页返回」例外：本页没有写页可跳（确认就在页内做，
    // 成功后已经 patchBatch 就地变色 + reloadOneBatch 补拉），唯一的外跳是 403 时 reLaunch 回工作台，
    // 那条路上没有任何「回页必须立刻重拉」的新写入。
    if (this.retryLock > 0) return
    this.loadBatches()
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
    // 滚轮监听与 onReady 的注册配对
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
    // 定时器清理：X-1 拆锁后本页有两枚 setInterval（读侧 retryLockTimer / 写侧 writeRetryLockTimer），都必须成对清掉
    this.stopRetryLock()
    this.stopWriteRetryLock()
  },
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // ==================== 批次加载 ====================
    // @param {boolean} [resetView] 日期变了要立刻退回加载态让上一日期的卡让位；onShow 的静默重拉不传
    async loadBatches(resetView) {
      // 并发守卫：onShow 重拉 + 日期步进连点 + 错误卡重试可叠加，慢失败不能盖掉快成功
      if (this._loadingBatches) {
        this._reloadQueued = true
        return
      }
      // 刻意不在这里 gate retryLock（姿势照 plan-orders.vue 的 fetchList：闸门只装在
      // 「自动翻页」和「错误卡重试按钮」两处，用户主动改条件必须立刻发请求）：
      // 冷却期里挡掉步进 = 日期条已变、卡片还是上一天，而且 ready 态没有任何重试入口，那点就永久丢了
      this._loadingBatches = true
      const reqDate = this.queryDate
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getPartnerArrivals({ date: reqDate })
        // 回包晚于步进：整批丢弃、按最新日期重发——绝不用上一日期的批次渲染当前日期
        if (this.queryDate !== reqDate) {
          this._reloadQueued = true
          return
        }
        const data = (res && res.data) || {}
        const list = Array.isArray(data.list) ? data.list : []
        this.batches = list
        this.resolvedStoreId = data.storeId === undefined ? null : data.storeId
        this.effectiveDate = String(data.date || reqDate || '')
        this.errorText = ''
        this.errorCode = 0
        this.viewState = list.length ? 'ready' : 'empty'
      } catch (e) {
        if (e && e.code === 401) {
          // 请求层已清态回登录页，这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        if (this.queryDate !== reqDate) {
          this._reloadQueued = true
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = Number((e && e.code) || 0)
        if (Number(e && e.code) === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loadingBatches = false
        if (this._reloadQueued) {
          this._reloadQueued = false
          this.loadBatches(true)
        }
      }
    },
    // ==================== 确认到货（四支常驻反馈，口径同核销台 §2） ====================
    async confirmBatch(b) {
      const id = b && b.id
      if (id === null || id === undefined || id === '') return
      // 冷却闸门写在方法级第一行：模板的 disabled 在小程序端不保证拦住 click（X-1：读写拆锁后闸门只看写侧 writeRetryLock）
      if (this.writeRetryLock > 0) return
      // 单飞锁：同一批次连点只放行一次；其余批次此刻也共用这一枚锁（一次只做一个写动作，
      // 避免两个确认互相把对方的读数刷掉——本表 uk_store_date 下本来就一日一批，这里只是把规矩立住）
      if (this.confirmingId !== null) return
      if (!this.canConfirm(b)) return
      this.confirmingId = id
      const key = String(id)
      try {
        const res = await confirmArrival(id)
        const data = (res && res.data) || {}
        if (data.idempotent === true) {
          // 幂等不是失败：这一批此前已确认，且不重复写 ops_log（PartnerStoreServiceImpl#confirmArrival 的 BATCH_CONFIRMED 幂等分支）
          this.setFeedback(key, 'neutral', '该批次此前已确认，未重复记录')
        } else {
          this.setFeedback(key, 'ok', '到货已确认：' + this.batchTitle(b))
          // 先把确认响应里的权威字段就地更新（status/confirmed/confirmTime），
          // 卡片不会等到重拉回来才变色
          this.patchBatch(id, data)
        }
        // 「成功后只重拉该批次」（§3）：重拉一次当日清单、只把这一条换进去，
        // 不动 viewState、不清其它批次、不回顶——整页重拉会把主理人刚翻到的位置甩掉
        await this.reloadOneBatch(id)
      } catch (e) {
        const code = Number((e && e.code) || 0)
        const message = (e && e.message) || ''
        if (code === 401) return
        if (code === 429) {
          // X-3：起锁点存不带数字的整句（照 apply.vue 的 handleSubmitError 里 429 那一支），冷却秒数只由 feedbackText 读实时 writeRetryLock 那一处出，
          // 免得归零后常驻行永久停在「请 Ns 后再试」（改前存 coolingText(seconds) 把数整句存死了）。
          // X-1：起写侧闸门 writeRetryLock——确认写桶的冷却与 loadBatches 读闸门互不覆盖。秒数仍只来自 retrySecondsOf。
          // FE-10 + F-28.5 路 A：不带数整句不再页内写死，与 coolingText 无参支同一出处（api 层一份）
          this.setFeedback(key, 'cooling', coolingText())
          this.startWriteRetryLock(e)
          return
        }
        if (code === 404) {
          // 他店批次与不存在的批次在后端就是同一个 404 同文案（不泄漏行存在性），
          // 前端跟着拆两支就等于把这枚预言机还回去（PartnerStoreServiceImpl#confirmArrival 两条 BATCH_NOT_FOUND_MSG 出口）
          this.setFeedback(key, 'err', '批次不存在或不属于你的门店')
          return
        }
        if (code === 400) {
          this.setFeedback(key, 'err', message || '请求参数有误，请检查后重试')
          return
        }
        if (code === 403) {
          this.setFeedback(key, 'err', message || '无权限访问')
          return
        }
        this.setFeedback(key, 'err', message || '确认失败，请检查网络后重试')
      } finally {
        // 单飞锁一律复位：留在非 null 会让整页按钮永远点不动
        this.confirmingId = null
      }
    },
    // 就地更新（只用响应里确实有的键）：status/confirmTime 是后端 confirmResult 的白名单键
    patchBatch(id, data) {
      const idx = this.batches.findIndex((b) => String(b && b.id) === String(id))
      if (idx < 0) return
      const next = Object.assign({}, this.batches[idx])
      if (data.status !== undefined) next.status = data.status
      if (data.confirmed !== undefined) next.confirmed = data.confirmed
      if (data.confirmTime !== undefined) next.confirmTime = data.confirmTime
      this.batches.splice(idx, 1, next)
    },
    // 只重拉这一条批次：请求本身仍按 date 拿当日清单（后端没有「按批次 id 取单条」的端点，
    // 已在契约疑点登记），但落到页面上只替换被确认的那一条，其余批次与滚动位置都不动
    async reloadOneBatch(id) {
      if (this.retryLock > 0) return
      this.batchRefreshFailed = false
      try {
        const res = await getPartnerArrivals({ date: this.queryDate })
        const data = (res && res.data) || {}
        const list = Array.isArray(data.list) ? data.list : []
        const fresh = list.filter((b) => String(b && b.id) === String(id))[0]
        if (!fresh) return
        const idx = this.batches.findIndex((b) => String(b && b.id) === String(id))
        if (idx < 0) return
        this.batches.splice(idx, 1, fresh)
      } catch (e) {
        // FE-12：不再纯静默。确认动作本身已经成功（上面那张卡的反馈行是准的），所以这次补拉失败
        // 绝不能把页面拖进 error 态、也不能撤销刚写下的反馈；但「屏上这条可能还是旧值」必须说一句——
        // 姿势照 store 页的常驻提示（状态位 + 一行 hint，不弹 toast 抢掉成功那句的声道）
        this.batchRefreshFailed = true
      }
    },
    // ==================== 反馈读写 ====================
    feedbackOf(b) {
      const id = b && b.id
      if (id === null || id === undefined || id === '') return null
      return this.feedbackMap[String(id)] || null
    },
    // cooling 一支的秒数读实时写侧闸门 writeRetryLock：存死的字符串会在冷却结束后仍写着「请 60s 后再试」；
    // 起锁点（confirmBatch 429 支）存不带数字的整句（X-3，照 apply.vue 的 handleSubmitError 里 429 那一支）
    feedbackText(b) {
      const fb = this.feedbackOf(b)
      if (!fb) return ''
      if (fb.tone === 'cooling') {
        return this.writeRetryLock > 0 ? coolingText(this.writeRetryLock) : fb.text
      }
      return fb.text
    },
    // 整体替换（新对象引用）：Vue2 下就地加键不会触发响应，这一处不省这个麻烦
    setFeedback(key, tone, text) {
      this.feedbackMap = Object.assign({}, this.feedbackMap, { [key]: { tone, text } })
      this.showToast(text)
    },
    // ==================== 日期步进（基准优先 queryDate，照 pickup-plan.vue 的 stepDay） ====================
    stepDay(delta) {
      const base = this.queryDate || this.effectiveDate || this.todayStr()
      const d = this.parseDate(base) || this.newToday()
      d.setDate(d.getDate() + delta)
      this.queryDate = this.fmtDate(d)
      this.loadBatches(true)
    },
    goToday() {
      this.queryDate = this.todayStr()
      this.loadBatches(true)
    },
    todayStr() {
      return this.fmtDate(this.newToday())
    },
    // 首屏默认落点=明日。批次是「明天要到的货」，默认今天会让这一页在绝大多数时间里
    // 显示成「当日无到货批次」，而那不是异常、只是看错了日期。页内保留「今天」快捷按钮可回退。
    tomorrowStr() {
      const d = this.newToday()
      d.setDate(d.getDate() + 1)
      return this.fmtDate(d)
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
    // ==================== 渲染辅助（只渲染白名单键：id/storeId/batchDate/status/confirmTime/remark/items，
    //                items 内 id/batchId/dishId/dishName/expectedQty/actualQty）
    //                白名单就是 PartnerStoreServiceImpl#arrivals 里逐键 put 的那一段，键名本行已抄全，
    //                所以刻意不挂行号：F-60 在后端加了几行注释，带行号的写法当场被顶偏（pointer-audit 实测报过）。
    // ====================
    batchKey(b, bi) {
      const id = b && b.id
      return (id === null || id === undefined || id === '') ? 'x' + bi : 'b' + id
    },
    itemKey(b, it, ii) {
      const id = it && it.id
      return (id === null || id === undefined || id === '') ? 'x' + ii : 'i' + id
    },
    // 卡标题：批次日期 + 批次号（一店一日一批，光写「批次」两个字的卡并排时区分不出来）
    batchTitle(b) {
      const date = this.batchDateText(b)
      const id = b && b.id
      const tail = (id === null || id === undefined || id === '') ? '' : ' · 批次 #' + id
      return (date || '到货批次') + tail
    },
    batchDateText(b) {
      const s = String((b && b.batchDate) || '').trim()
      return /^\d{4}-\d{2}-\d{2}$/.test(s) ? s : ''
    },
    statusText(b) {
      const s = (b && b.status) || ''
      return STATUS_TEXT[s] || (s || '状态未回显')
    },
    statusTone(b) {
      return STATUS_TONE[(b && b.status) || ''] || 'info'
    },
    isConfirmed(b) {
      // 判据只认 status 原值（后端同时给了 confirmed 布尔位，但两个都是同一份 status 派生，
      // 以 status 为唯一真源可避免「confirmed=true 而 status 还是 arriving」这类漂移各自渲染一次）
      return (b && b.status) === BATCH_CONFIRMED
    },
    canConfirm(b) {
      return CONFIRMABLE_STATUS.indexOf((b && b.status) || '') > -1
    },
    confirmLabel(b) {
      // 冷却中的可确认批次：给不带数的状态词（F-28.5 路 A/X-5，秒数只在该批次反馈行那一处出；
      // 「置灰但不说为什么置灰 = 用户以为页面坏了」由状态词承担，句式照 apply「提交冷却中」/store「保存冷却中」）
      if (this.writeRetryLock > 0 && this.canConfirm(b)) return '确认冷却中'
      if (this.confirmingId !== null && String(this.confirmingId) === String((b && b.id))) return '确认中…'
      if (this.isConfirmed(b)) return '已确认'
      if (!this.canConfirm(b)) return '不可确认'
      return '确认到货'
    },
    // 不可点的原因（只在「非已确认」时出一行，已确认那一支上面已有确认时间这一行）
    disabledReason(b) {
      const s = (b && b.status) || ''
      if (s === BATCH_SHORT) return '短少批次需平台处理，不在本页确认'
      if (!s) return '状态未回显，暂不可确认'
      return '当前状态「' + s + '」不可确认'
    },
    confirmTimeText(b) {
      const s = String((b && b.confirmTime) == null ? '' : (b && b.confirmTime)).trim()
      if (!s || s === 'null') return ''
      // 带 '-' 的原样透出（只把 ISO 的 'T' 换空格、砍到秒）：不走 new Date() 再格式化，
      // 那会把无时区的 LocalDateTime 按本地时区平移一次，屏上值就不再是库里那一位
      if (s.indexOf('-') > -1) return s.replace('T', ' ').slice(0, 19)
      const n = Number(s)
      if (!Number.isFinite(n)) return s
      const d = new Date(n)
      if (Number.isNaN(d.getTime())) return s
      const p = (x) => (x < 10 ? '0' : '') + x
      return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
    },
    remarkText(b) {
      const s = (b && b.remark) == null ? '' : String(b.remark).trim()
      return s
    },
    itemsOf(b) {
      return (b && Array.isArray(b.items)) ? b.items : []
    },
    // 明细行商品名：后端 LEFT JOIN dish 带出 dishName（ArrivalItemMapper#selectItemsByBatchIds），
    // 但 dish 被平台删掉时该列为 NULL —— 此时按契约渲染「商品#<dishId>」，
    // 绝不在前端另发一次商品列表请求去补名（那是把一次 JOIN 换成一次越权查询 + 一笔分叉债）
    itemName(it) {
      const name = (it && it.dishName) == null ? '' : String(it.dishName).trim()
      if (name) return name
      const dishId = it && it.dishId
      if (dishId === null || dishId === undefined || dishId === '') return '商品#未回显'
      return '商品#' + dishId
    },
    countOf(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n : 0
    },
    // 实收：一期确认动作不写 actual_qty（D-19，PartnerStoreServiceImpl 类注释），所以 NULL 是常态。
    // 显示「—」而不是 0：把「没登记」渲染成「收到 0 件」是会让人去追一笔不存在的短少
    actualText(it) {
      const v = it && it.actualQty
      if (v === null || v === undefined || v === '') return '—'
      const n = Number(v)
      return Number.isFinite(n) ? String(n) : '—'
    },
    shortReasonText(it) {
      const s = (it && it.shortReason) == null ? '' : String(it.shortReason).trim()
      return s
    },
    // ==================== 429 冷却（照 plan-orders.vue 同款；F-25/P-3 起入参是错误对象） ====================
    // 只在 code===429 起（页面纪律 ②）：调用点在 catch 里判过码
    // X-1 拆锁：startRetryLock/stopRetryLock 只管读侧闸门 retryLock（loadBatches 撞 arrivals 读桶）；
    // 写侧闸门另有 startWriteRetryLock/stopWriteRetryLock 一对（confirmBatch），两把锁互不覆盖。
    startRetryLock(err) {
      this.stopRetryLock()
      // F-25/P-3：入参由「调用方算好的秒数」改为 catch 到的错误对象，秒数一律由 api 层 retrySecondsOf 取值
      // （与 apply/login/workspace 那一族同形，消除同名方法两种语义）。原先这里那道
      // `Number(seconds) > 0 ? seconds : RETRY_FALLBACK_SECONDS` 二次兜底已删——回落只能有一个出处，
      // 两处各算一遍正是「拷一行就静默失效」的源头。闸门值与改前逐 case 等值（见 logs/scratch26 探针）。
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
    // X-1：写侧闸门专用一对——confirmBatch 撞确认写桶的 429 只动这两枚变量，
    // 读侧任何路径（含 loadBatches 非 429 失败的 stopRetryLock）都清不掉它。秒数仍只来自 retrySecondsOf。
    startWriteRetryLock(err) {
      this.stopWriteRetryLock()
      this.writeRetryLock = retrySecondsOf(err, RETRY_FALLBACK_SECONDS)
      this.writeRetryLockTimer = setInterval(() => {
        this.writeRetryLock--
        if (this.writeRetryLock <= 0) this.stopWriteRetryLock()
      }, 1000)
    },
    stopWriteRetryLock() {
      if (this.writeRetryLockTimer) { clearInterval(this.writeRetryLockTimer); this.writeRetryLockTimer = null }
      this.writeRetryLock = 0
    },
    // ==================== 跳转 ====================
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 本页无分页，所以只接管滚动、不判底（判底翻页那一段是 plan-orders 的清单页才需要的）
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
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.empty-row/.guide-link/.btn-main 取自 common.css，
   本页只补间距与专属的字，不动主题变量、不改公共类语义） */

/* ==================== 1. 日期条 ==================== */
.date-card { display: flex; flex-direction: column; gap: 12rpx; }
.date-field-row { display: flex; align-items: center; }
.date-field-label { font-size: 26rpx; color: #999999; line-height: 1.5; }
.date-row { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.date-step {
  flex-shrink: 0; font-size: 24rpx; font-weight: 600; color: #E02020; line-height: 1.4;
  background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 999rpx; padding: 12rpx 24rpx;
}
.date-current {
  flex: 1; min-width: 0; text-align: center;
  font-size: 30rpx; font-weight: 700; color: #333333; line-height: 1.4;
  font-variant-numeric: tabular-nums;
}
.date-sub-row { display: flex; align-items: center; gap: 12rpx; }
.date-quick {
  flex-shrink: 0; font-size: 24rpx; font-weight: 600; color: #333333; line-height: 1.4;
  background-color: #F5F5F5; border: 1rpx solid #EEEEEE; border-radius: 999rpx; padding: 12rpx 24rpx;
}
.date-note { flex: 1; min-width: 0; font-size: 20rpx; color: #BBBBBB; line-height: 1.4; }
.pill-hover { opacity: .82; }

/* ==================== 2/3/4. 三态卡 ==================== */
.loading-card { display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; }
.empty-card { display: flex; flex-direction: column; align-items: center; }
.empty-actions { display: flex; gap: 24rpx; }

/* ==================== 5. 批次卡 ==================== */
.batch-card { margin-top: 24rpx; display: flex; flex-direction: column; gap: 12rpx; }
.batch-head { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.batch-title {
  flex: 1; min-width: 0;
  font-size: 28rpx; font-weight: 700; color: #333333; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.batch-row { font-size: 24rpx; color: #666666; line-height: 1.5; }
.batch-items { display: flex; flex-direction: column; gap: 8rpx; padding-top: 8rpx; border-top: 1rpx solid #F5F5F5; }
.item-line { display: flex; flex-wrap: wrap; align-items: baseline; gap: 12rpx; }
.item-dish {
  flex: 1 1 auto; min-width: 0;
  font-size: 26rpx; color: #333333; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.item-qty { flex-shrink: 0; font-size: 24rpx; color: #666666; line-height: 1.5; font-variant-numeric: tabular-nums; }
.item-empty { font-size: 24rpx; color: #CCCCCC; line-height: 1.5; }
.item-short { flex-basis: 100%; font-size: 22rpx; color: #E02020; opacity: .8; line-height: 1.4; }
.item-tag {
  flex-shrink: 0; font-size: 20rpx; font-weight: 600; line-height: 1.4;
  border-radius: 8rpx; padding: 4rpx 12rpx;
}
.batch-tag-info { color: #666666; background-color: #F5F5F5; }
.batch-tag-warn { color: #E02020; background-color: #FFF7F0; }
.batch-tag-ok { color: #FFFFFF; background-color: #E02020; }
.batch-tag-err { color: #FFFFFF; background-color: #B01818; }

/* ==================== 6. 确认按钮与反馈行 ==================== */
.batch-confirm { margin-top: 8rpx; }
.form-hint { font-size: 22rpx; color: #999999; line-height: 1.5; }
.batch-feedback { font-size: 26rpx; font-weight: 600; line-height: 1.5; padding: 12rpx 16rpx; border-radius: 12rpx; }
.batch-feedback-ok { color: #FFFFFF; background-color: #E02020; }
.batch-feedback-neutral { color: #666666; background-color: #F5F5F5; }
.batch-feedback-err { color: #E02020; background-color: #FFF1F0; }
.batch-feedback-cooling { color: #E02020; background-color: #FFF7F0; }

/* 空态改两行式：主句只说事实（哪一天没有批次记录），副句说「这事该找谁」。
   common.css 的 .empty-row 是横向居中，两个 text 并排会把长句挤成竖条，这里按本页需要改纵向。 */
.empty-row { flex-direction: column; align-items: center; gap: 12rpx; }
.empty-sub { font-size: 22rpx; color: #BBBBBB; text-align: center; padding: 0 30rpx; line-height: 34rpx; }

.page-bottom-space { height: 60rpx; }
</style>
