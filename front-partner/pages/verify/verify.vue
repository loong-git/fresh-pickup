<template>
  <view class="page-container">
    <!-- ==================== 1. 到货警示条（可关，F-18.17 §2 + FE-2 逐店聚合） ====================
             数据源：GET /api/partner/stores 拿名下门店集合 → 逐店 GET /api/partner/arrivals?date=今天&storeId=该店
             （FE-2：只发一次不带 storeId 的请求 = 服务端 Collections.min 挑一家，而待取清单覆盖名下全部店，
               别家店没确认就是漏报，所以这里显式逐店查后聚合；D-9「当日批次未确认」的唯一依据不变）
             显示条件：聚合结果里存在「今日无批次 或 有 status !== 'confirmed' 批次」的门店
             文案：名下多店 → 「N 家店今日批次未确认，货可能未到」；只有一家 → 保持原句
             请求失败：整条不显示——提示本身一旦「报错还亮着」就会把「没查到」说成「货没到」，
             那就是误导源；故这次失败静默（不打 console、不留 spinner），也绝不并入页面四态
             （清单那两次请求才是本页四态的主人）。 ==================== -->
    <view v-if="alertVisible" class="card alert-card">
      <text class="alert-text">{{ alertText }}</text>
      <view class="alert-row">
        <view class="alert-link" hover-class="pill-hover" @click="goArrival">去看批次 ›</view>
        <view class="alert-close" hover-class="pill-hover" @click="dismissAlert">关闭</view>
      </view>
    </view>

    <!-- ==================== 2. 输码区 ====================
             过滤与截断放在 @input（type=number 在部分安卓键盘上仍会漏进非数字，maxlength 只管长度不管字符）；
             提交期间的单飞锁 verifying 由 finally 复位（照 dishes.vue cancelingId 同款防重姿势）：
             核销是写操作，重复提交会撞 D-14 枚举锁，第二次点击必须发不出去。 ==================== -->
    <view class="card verify-card">
      <text class="block-title">取货码核销</text>
      <input
        class="code-input"
        type="number"
        maxlength="6"
        :value="code"
        placeholder="请输入取货码（6 位数字）"
        placeholder-style="color:#BBBBBB;"
        @input="onCodeInput"
      />
      <button
        class="btn-main"
        :class="{ 'btn-disabled': !canSubmit }"
        :disabled="!canSubmit"
        @click="submitVerify"
      ><text>{{ verifying ? '核销中…' : '核销' }}</text></button>
      <!-- F-28.5 路 A（X-5）：写侧闸门在本屏唯一的带数处是下方常驻反馈行（feedbackText 读实时 writeRetryLock），
           冷却分支只给不带数的状态词——改前「冷却中，Ns 后可再提交」与反馈行同屏各报一次同一个数 -->
      <text v-if="code.length !== 6" class="form-hint">请输入 6 位取货码</text>
      <text v-else-if="writeRetryLock > 0" class="form-hint">提交冷却中</text>
      <!-- 常驻反馈行：四支（成功/幂等/404/400/429）都落在这一行，不只 toast——
           核销现场主理人正低头翻货，toast 一闪而过就等于没反馈。toast 同批发出，文案同一处生成 -->
      <text v-if="feedback" class="verify-feedback" :class="'verify-feedback-' + feedback.tone">{{ feedbackText }}</text>
    </view>

    <!-- ==================== 3. 待取清单的日期条 ====================
             步进基准优先本次请求带出去的 queryDate（照 pickup-plan.vue:239-250 定稿）：
             慢网下连点时后端回显还是上一轮的日期，以它为准会把第 2、3 次点击算成同一天的 +1（点被吞掉） -->
    <view class="card date-card">
      <view class="date-field-row">
        <!-- 叫法与商家端两页逐字一致（同一枚日期在三端不能三种叫法）；本页语义确实是自提日期：
             请求参数就是 pickupDate（PartnerController.java:76） -->
        <text class="date-field-label">自提日期</text>
      </view>
      <view class="date-row">
        <view class="date-step" hover-class="pill-hover" @click="stepDay(-1)">前一天</view>
        <text class="date-current">{{ effectiveDate || '--' }}</text>
        <view class="date-step" hover-class="pill-hover" @click="stepDay(1)">后一天</view>
      </view>
      <view class="date-sub-row">
        <view class="date-quick" hover-class="pill-hover" @click="goToday">今天</view>
        <text class="date-note">清单固定只看「待自提」，已完成/已取消不在这一页</text>
      </view>
    </view>

    <!-- ==================== 4. 加载中（首屏与切日期后的重拉都走这一态，避免上一日期的行被读成本日期的） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载待取清单…</text>
    </view>

    <!-- ==================== 5. 错误态（≠ 空态：空态是服务端确认「该日一条都没有」，错误态是根本没拿到数据） ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="fetchList(true, true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 6. 空态 ==================== -->
    <view v-else-if="viewState === 'empty'" class="card empty-card">
      <view class="empty-row">
        <text class="empty-text">{{ effectiveDate || '该日' }} 没有待自提的订单</text>
      </view>
      <view class="empty-actions">
        <text class="guide-link" @click="stepDay(-1)">看前一天</text>
        <text class="guide-link" @click="stepDay(1)">看后一天</text>
      </view>
    </view>

    <!-- ==================== 7. 就绪：待取清单（分页 + rowKey 去重） ==================== -->
    <template v-else>
      <view class="card list-card">
        <view class="order-item" v-for="(it, i) in list" :key="rowKey(it, i)">
          <view class="item-line">
            <text class="item-dish">{{ dishNameOf(it) }}</text>
            <text class="item-qty">× {{ countOf(it.quantity) }}</text>
          </view>
          <view class="item-line item-sub-line">
            <text class="item-phone">{{ phoneOf(it) }}</text>
            <view class="item-unit">
              <text class="item-dot">·</text>
              <text class="item-store">{{ storeNameOf(it) }}</text>
            </view>
            <view class="item-unit">
              <text class="item-dot">·</text>
              <text class="item-sub">{{ timeText(it.createTime) }}</text>
            </view>
            <!-- 未支付角标：条件留全（payStatus===0 且非 cancelled），后端清单不过滤 pay_status
                 （PartnerOrderMapper:51 只排除 cancelled），所以这类行确实可能出现在这一页 -->
            <text v-if="showUnpaid(it)" class="item-tag item-tag-unpaid">未支付</text>
          </view>
          <text v-if="showUnpaid(it)" class="item-unpaid-note">未支付的取货码不可核销（提交会被拒 400）</text>
        </view>
      </view>

      <!-- 加载更多：触底自动 + 按钮兜底（照 plan-orders.vue:96-102）；终态「共 N 条」用 max(total, 已加载行数) -->
      <view class="load-more">
        <button v-if="hasMore" class="btn-more" :class="{ 'btn-more-cooling': retryLock > 0 }"
          :disabled="loadingMore || retryLock > 0" @click="loadMore">
          <text>{{ retryLock > 0 ? loadMorePillText : (loadingMore ? '加载中…' : '加载更多') }}</text>
        </button>
        <text v-else class="no-more">共 {{ shownTotal }} 条</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 主理人核销台（F-18.17 §2）：输 6 位取货码核销 + 今日待取清单。
// 三条页面纪律（全部沿用已验收的母版，不新造第二套）：
// ① 四态单值互斥 loading|error|empty|ready，只由「待取清单」那一次请求决定；
// ② 冷却锁只在 code===429 起（同商家端母版 plan-orders.vue 的 fetchList 与 dishes.vue 的 loadDishes 里那两枚 429 起锁判据，F-25/P-4 按商家端实况重测），其余失败重试是有意义的；
// ③ 所有请求走 @/api/index.js，页内零原生请求、零自拼 BASE_URL；归属由服务端从 token 解析（RF），
//    本页不发任何 storeId 之外的归属入参。
// 401 不在本页处理（请求层 rejectUnauthorized 已清 partner_token 并回登录页）；
// 403 = token 有效但名下无店，是确定性失败，给「回工作台」而不是重试。
// 渲染不依赖 store：本页自持一份服务端最新值（workspace.vue 同款纪律）。
import { getPartnerOrders, verifyPartnerOrder, getPartnerArrivals, getPartnerStores, retrySecondsOf, coolingText, cooldownPillText } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/verify/verify'

// 清单固定「待自提」：后端 status 白名单 all|pending_pickup|completed（非法值 400，PartnerController.java:70-71）。
// 已完成不进这一页——核销台要看的就只有一件事：还有谁没提走。
const LIST_STATUS = 'pending_pickup'

// 每页条数：后端 size 夹到 [1,50]、缺省 20（PartnerOrderServiceImpl#DEFAULT_PAGE_SIZE/#MAX_PAGE_SIZE），这里就按缺省给
const PAGE_SIZE = 20

// 批次「已确认」值（PartnerStoreServiceImpl#BATCH_CONFIRMED 同值）：警示条的唯一判据。
// 刻意不写 status !== 'confirmed' 之外的组合判断——后端多一个态（如 short）时它天然落进「未确认」
const BATCH_CONFIRMED = 'confirmed'

// FE-5：429 冷却时长不再硬编码 60——后端 429 信封的 data.retryAfterSeconds 优先（BE-5：D-14 枚举锁
// 把自己的剩余 TTL 如实回传，见 PartnerOrderServiceImpl.java:105 与 :210-215），取不到才回落本地 60
// （与写侧 @RateLimit 的 60s 窗口同档）。600s 的锁只冷却 60s 就是「锁未解而人反复撞墙」（F-18.20 E 行）。
const RETRY_FALLBACK_SECONDS = 60

// FE-10 + F-28.5 路 A：同页 429 只留一句说法（清单加载与提交两支共用），且本页不再私藏实现——
// coolingText 唯一实现上提在 api 层（与 retrySecondsOf 同层、同先例），本页句子全部由它出：
// 带数支只读实时闸门，无参支就是 X-3 起锁点那句不带数的整句

// 从 reject 出来的错误对象取 retryAfterSeconds：retrySecondsOf 已收敛到 api 层（F-25 路 1），
// front-partner 内唯一实现是 front-partner/api/index.js 的 retrySecondsOf，本页档位由上面的
// RETRY_FALLBACK_SECONDS 作为第二参传进去。
// 载荷来源：请求层 handleEnvelope 的 reject 带 data（front-partner/api/index.js:183-186 早已透传），
// 429 锁的 {retryAfterSeconds} 就是从这里进页面；网络异常/信封不可解析时 data 为 null，走本地回落 60。

export default {
  data() {
    return {
      // 输码区：@input 已过滤成纯数字且最多 6 位，长度判定因此可直接采信
      code: '',
      // 提交单飞锁（写操作专用；finally 复位，失败也不能把它留在 true）
      verifying: false,
      // 常驻反馈：{tone:'ok'|'neutral'|'err'|'cooling', text:string}；null = 本次会话还没提交过
      feedback: null,
      // ===== 待取清单 =====
      // 请求要下发的日期（页内永远给值：本页按日核销，不带 date 就等于不限日期）
      queryDate: '',
      // 显示用日期：优先后端回显，回显缺失才退回本次请求值（一份真值，不是页内推算）
      effectiveDate: '',
      list: [],
      // 分页三值一律以后端回显为准（回显缺失才退回请求值）
      page: 1,
      size: PAGE_SIZE,
      total: 0,
      hasMore: false,
      // 四态互斥：loading | error | empty | ready
      viewState: 'loading',
      errorText: '',
      errorCode: 0,
      // 仅驱动「加载更多」按钮的加载中文案：翻页失败不该把已加载的清单换成错误卡
      loadingMore: false,
      // 429 冷却剩余秒数与驱动它的定时器（onUnload 成对清）
      // X-1 拆锁（母版 dishes.vue 的 catRetryLock）：retryLock = 读侧闸门（fetchList/loadMore/loadAlert，
      // 撞的是 orders 读桶 USER 档），writeRetryLock = 写侧闸门（submitVerify 撞 D-14 防枚举锁 / 写桶）。
      // 两把锁是两个独立 @RateLimit 桶，必须各计各的：共用一枚时读侧一次非 429 失败的 stopRetryLock()
      // 会把写侧 600 秒防枚举闸门清成 0（本页 fetchList 无闸门内判据，onShow/步进都能撞进去）。
      retryLock: 0,
      retryLockTimer: null,
      writeRetryLock: 0,
      writeRetryLockTimer: null,
      // ===== 到货警示条 =====
      // null = 还没查/这次没查成（不显示）；true = 今日确有未确认批次（显示）；false = 今日已确认（不显示）
      alertUnconfirmed: null,
      // FE-2 聚合读数：名下几家店、其中几家「今日无批次或有未确认批次」；两个数都要在条上说话
      alertStoreTotal: 0,
      alertPendingCount: 0,
      // FE-6：「去看批次」这一跳要落到哪个日期——名下确有未确认批次就用那一批的 batchDate，
      // 今日整日无批次（批次由平台在明日生成）就用明日，避免跳进批次页的今日空态死胡同
      alertTargetDate: '',
      // 关闭只在本页实例内生效：每次回页都重弹同一条，等于逼主理人再关一次
      alertDismissed: false
    }
  },
  computed: {
    // 警示条可见性：只有「查成功且确实未确认」才亮；失败（null）与已确认（false）都不亮
    alertVisible() {
      return this.alertUnconfirmed === true && !this.alertDismissed
    },
    // FE-2 文案：名下多店时把「几家没确认」写在条上（只有一家时不必数一遍，保持原句）
    alertText() {
      const n = Number(this.alertPendingCount)
      const total = Number(this.alertStoreTotal)
      if (total > 1 && n > 0) return n + ' 家店今日批次未确认，货可能未到'
      return '今日批次未确认，货可能未到'
    },
    canSubmit() {
      return this.code.length === 6 && !this.verifying && this.writeRetryLock === 0
    },
    // 常驻反馈文案：cooling 一支的秒数读实时写侧闸门 writeRetryLock（存死的字符串会在冷却结束后仍写着「请 60s 后再试」），
    // 句式与清单加载那一支同源（coolingText 在 api 层一份，F-28.5 路 A）；起锁点存的是不带数字的整句（X-3，照 apply.vue 的 cooling 支）
    feedbackText() {
      if (!this.feedback) return ''
      if (this.feedback.tone === 'cooling') {
        return this.writeRetryLock > 0 ? coolingText(this.writeRetryLock) : this.feedback.text
      }
      return this.feedback.text
    },
    // 「共 N 条」给 max(后端 total, 已加载行数)：翻页期间有单被核掉会让 total 收缩，
    // 屏上已渲染的行又不会凭空消失（口径照 plan-orders.vue 的 shownTotal）
    shownTotal() {
      const n = Number(this.total)
      const loaded = Array.isArray(this.list) ? this.list.length : 0
      return Math.max(Number.isFinite(n) && n >= 0 ? n : 0, loaded)
    },
    errorLeadText() {
      // FE-10 + F-28.5 路 A（X-5）：429 这一行不带时长——同屏闸门行「{{ retryLock }}s后可重试」是本屏读侧唯一带数处，
      // 主句走 coolingText 的无参整句（与提交那支同一句式、同一实现，只是不带数；apply/workspace 的 FF-1 早就是这个姿势）
      if (this.errorCode === 429) return coolingText()
      if (this.errorCode === 403) return '暂无权限查看待取清单，请回工作台确认账号状态'
      if (this.errorCode === 404) return '核销清单接口暂不可用，可能是后端服务还没更新，请稍后重试'
      // X-2b（四路分流，母版 front-merchant/pages/dashboard/dashboard.vue 的同名 computed）：500 是服务端
      // 承认自己处理出错——同屏第二行透出的小字正是它那句原文（「服务开小差了，请稍后重试」），
      // 第一行却叫主理人去查 Wi-Fi，两行因果方向相反。500 说「服务端处理出错」，不再借网络那句
      if (this.errorCode === 500) return '待取清单加载失败，服务端处理出错，稍后重试'
      // 只有网络层失败才许出现「网络」字样：front-partner/api/index.js 的 request fail 支回 code:-1 /
      // message「网络异常」，页面侧 errorCode 因此是 -1；没带码的失败归 0，两支同一条出路
      if (this.errorCode <= 0) return '待取清单加载失败，请检查网络后重试'
      // 其余服务端业务码（400 一类）：不猜成因，报「加载失败」＋第二行透出后端 message 原文
      return '待取清单加载失败，请稍后重试'
    },
    // 重试有没有可能成功：只有 403（归属集合为空，重试必然还是同一枚错误）判死并给「回工作台」出口
    errorRetryable() {
      return this.errorCode !== 403
    },
    // 「加载更多」按钮冷却中的时长短式：句式生成在 api 层 cooldownPillText（本轮把这里那一处
    // 页内秒数裸拼收进函数，让「页内裸拼 = 0」这条判据能一刀切，不必逐处论证档位）。
    // 模板调不到模块级 import——uni-app H5 的 Vue3 模板表达式只在组件实例作用域求值，
    // 所以这里挂一层纯转发 computed，与商家端 login.vue/dashboard.vue 的 cooldownPillText 用法同形。
    // 输出与改前逐字相同：这把读侧闸门有两支——真值支撞 USER 30 次 / 60 秒 + Refill.greedy（回速 ≤2），回落支正好 60（本页 RETRY_FALLBACK_SECONDS），两支都不越过含 60 的分档线。
    loadMorePillText() {
      return cooldownPillText(this.retryLock)
    }
  },
  onShow() {
    // 首屏默认今天（本页是「今天来提货的人」这一件事，不像备货单那样让后端缺省顶上来）
    if (!this.queryDate) this.queryDate = this.todayStr()
    // X-4b：回页重拉要过读侧闸门。裁定口径同商家端本轮定稿——onShow 是「页面重新显示时自己发的请求」，
    // 不是用户的点击，所以它没有旁路闸门的资格：主理人在核销台/批次页之间来回几次，
    // 每次都在往 orders 读桶（PartnerController#orders，USER 30/60）里续一发，额度就是这么花光的。
    // 被拦时刻意不给 toast（母版姿势一致：dishes/marketing/dashboard 三页的 onShow 都静默跳过——
    // 回页弹一句「太频繁」没人知道为什么，而冷却中的倒计时本来就写在屏上：错误卡那一行与「加载更多」按钮）。
    // 刻意不加商家端那种「刚从写页返回」的强制刷新例外：本页唯一的写（核销）就在页内做，
    // 成功后已经就地静默重拉第 1 页（submitVerify 里那次 fetchList(true)），不经过 onShow；
    // 页内唯一的外跳 goArrival 落到只读的批次页，返回时没有任何「必须立刻显形」的新写入。
    // loadAlert 首句自持同一枚 retryLock 判定，这里早退与它自己的判定同结果，不算新增覆盖。
    if (this.retryLock > 0) return
    // 回页重拉要回顶：翻到第 3 页再切回来只剩 20 行，滚动条悬在空处就像数据被吞了
    this._resetScrollTop = true
    this.fetchList(true)
    this.loadAlert()
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
    // 滚轮监听与 onReady 的注册配对
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
    // 定时器清理：X-1 拆锁后本页有两枚 setInterval（读侧 retryLockTimer / 写侧 writeRetryLockTimer），都必须成对清掉
    // （挂在 window 上不摘就会在页面销毁后继续每秒写一次已死组件的状态）
    this.stopRetryLock()
    this.stopWriteRetryLock()
  },
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // ==================== 输码 ====================
    // 过滤非数字 + 截 6 位（后端判据是 ^[0-9]{6}$，见 PartnerOrderServiceImpl 的 PICKUP_CODE_PATTERN）：
    // 让脏字符进不了 data，按钮的 disabled 判定与提交前的长度校验才能共用同一个值
    onCodeInput(e) {
      const raw = String((e && e.detail && e.detail.value) || '')
      this.code = raw.replace(/\D/g, '').slice(0, 6)
    },
    // ==================== 核销提交（四支常驻反馈） ====================
    // 单飞锁 verifying 在 finally 复位；冷却闸门写在方法级第一行——小程序端 disabled 按钮的
    // click 仍可能触发，只靠模板拦不住（闸门姿势照 plan-orders.vue 的 loadMore 首行）
    async submitVerify() {
      if (this.writeRetryLock > 0) return
      if (this.verifying) return
      const pickupCode = this.code
      if (pickupCode.length !== 6) return
      this.verifying = true
      try {
        const res = await verifyPartnerOrder(pickupCode)
        const data = (res && res.data) || {}
        // 幂等成功（D-6/G4：该码此前已核销）不是失败，也不重复计数——绝不能报成「核销成功」，
        // 那会让主理人以为又放行了一件货
        if (data.idempotent === true) {
          this.setFeedback('neutral', '该码此前已核销，未重复计数')
        } else {
          this.setFeedback('ok', '核销成功：' + this.orderSummary(data))
          this.code = ''
        }
        // 静默重拉第 1 页（不传 resetView：清单留在屏上，被核掉的那一行自然消失）。
        // 幂等那一支也重拉：既然这单在别处已被核掉，屏上就不该还挂着它。
        // 不 await：清单刷新与核销结果是两件事，等待只会把「核销中…」拉长
        this.fetchList(true)
      } catch (e) {
        const code = Number((e && e.code) || 0)
        const message = (e && e.message) || ''
        if (code === 401) {
          // 请求层已清态并回登录页；这里不写反馈——写任何一支都会把「登录失效」说成「码有问题」
          return
        }
        if (code === 429) {
          // D-14 防枚举锁（5 次/10 分钟）或写侧限流（10 次/分）命中：这一支就是枚举锁的可见反馈。
          // FE-5：冷却秒数按后端 data.retryAfterSeconds 起（锁 10 分钟时本地就该冷却到锁解），
          // FE-10：文案与清单加载那一支同一句。
          // 冷却期内输入框仍可用，但提交不发请求（上面那道闸门）
          // X-3：起锁点存不带数字的整句（照 apply.vue 的 handleSubmitError 里 429 那一支），冷却秒数只由 feedbackText 读实时 writeRetryLock 那一处出，
          // 免得归零后常驻行永久停在「请 Ns 后再试」（改前这里存 coolingText(seconds)，把数整句存死了）。
          // X-1：起写侧闸门 writeRetryLock——这是 D-14 防枚举锁的前端闸门，读侧冷却（retryLock）再动也碰不到它。
          // FE-10 + F-28.5 路 A：不带数整句不再页内写死，与 coolingText 无参支同一出处（api 层一份）
          this.setFeedback('cooling', coolingText())
          this.startWriteRetryLock(e)
          return
        }
        if (code === 404) {
          // 刻意不分「无此码 / 属于别家门店」两支：后端两条判定收敛成同一个 404 同文案
          // （PartnerOrderServiceImpl#verify 的 code_not_found / code_not_in_owned_stores 两条 failAs，不泄漏「码本身是对的」），前端跟着分两支就是
          // 替攻击者把 404 拆成可区分的预言机
          this.setFeedback('err', '码不存在或不属于你的门店')
          return
        }
        if (code === 400) {
          // 后端原文（码形状不符/已取消/未支付共用「请求参数有误，请检查后重试」，:77/:206/:231/:234）
          this.setFeedback('err', message || '请求参数有误，请检查后重试')
          return
        }
        if (code === 403) {
          this.setFeedback('err', message || '无权限访问')
          return
        }
        this.setFeedback('err', message || '核销失败，请检查网络后重试')
      } finally {
        this.verifying = false
      }
    },
    // 成功行的摘要：核销出参只有 {orderId(后6位),status,idempotent}，**没有 dishName**，
    // 所以只能从在手的清单行按单号尾号回查。
    // FE-7：命中不唯一就只报单号——0 条（清单翻页后不含该单）与 >1 条（同尾号的两行 / 一单多商品）
    // 都不许猜菜名，那会把话说到别人的货上；「等 N 项」这种拼装同样是猜。后端补 dishName 记在 S6-F2，
    // 到那一天这里只删回查、不改句式。
    orderSummary(data) {
      const tail = String((data && data.orderId) || '')
      if (!tail) return '本单已核销'
      const rows = Array.isArray(this.list) ? this.list.filter((r) => String(r.orderId || '') === tail) : []
      if (rows.length !== 1) return '单号 …' + tail
      return this.dishNameOf(rows[0]) + ' × ' + this.countOf(rows[0].quantity)
    },
    // 反馈落一处（常驻行 + 同文 toast）：两条通道同一串，防止「toast 说成功、行说失败」的分裂
    setFeedback(tone, text) {
      this.feedback = { tone, text }
      this.showToast(text)
    },
    // ==================== 到货警示条（静默通道：失败不显示、不动四态、不起冷却） ====================
    // FE-2：名下多店时逐店查后聚合。原来只发一次不带 storeId 的请求，服务端按 Collections.min 挑一家
    // （PartnerStoreServiceImpl.java:368-371），而待取清单覆盖名下全部门店 → 别家店没确认就是漏报。
    // 现在先 GET /api/partner/stores 取名下集合，再逐店**显式带 storeId** 查今日批次，聚合成「几家没确认」。
    // 纪律不变：这一条仍是静默通道——任一次请求没成就整条不亮（宁可少报，也不把「没查到」说成「货没到」），
    // 不并入页面四态、不起 retryLock（Promise.all 一起 reject 就整体落 catch）。
    async loadAlert() {
      // 冷却期内不重发（F-28.3 注释订正：这几条与待取清单同账号但不同桶——桶身份取「控制器类名 + 方法名」，见
      // RateLimitInterceptor#bucketKey，#stores/#arrivals 各有各的配额；借 retryLock 只是页内保守纪律「整页在读冷却里不补发任何读」）；
      // 保持上一次的成功读数，不因为「这次没查」就把已经确认过的批次说成未确认
      if (this.retryLock > 0) return
      const today = this.todayStr()
      try {
        const storesRes = await getPartnerStores()
        const ids = this.storeIdsOf(storesRes)
        if (!ids.length) {
          // 名下读不到门店（403 那一支走不到这里，请求层已 reject）：无从判断，整条不亮
          this.alertUnconfirmed = null
          this.alertStoreTotal = 0
          this.alertPendingCount = 0
          return
        }
        const results = await Promise.all(ids.map((id) => getPartnerArrivals({ date: today, storeId: id })))
        let pending = 0
        let firstPendingDate = ''
        results.forEach((res) => {
          const data = (res && res.data) || {}
          const batches = Array.isArray(data.list) ? data.list : []
          // 空 list = 今日根本没有批次（批次由平台生成，见 G-05 运营端）：按契约同样算「货可能未到」
          const unconfirmedRows = batches.filter((b) => (b && b.status) !== BATCH_CONFIRMED)
          if (!batches.length || unconfirmedRows.length) {
            pending++
            // 有真批次就用它的 batchDate 作为跳转目标；今日整日无批次才落到明日（FE-6）
            if (!firstPendingDate && unconfirmedRows.length) {
              const d = String(unconfirmedRows[0].batchDate || '')
              if (/^\d{4}-\d{2}-\d{2}$/.test(d)) firstPendingDate = d
            }
          }
        })
        this.alertStoreTotal = ids.length
        this.alertPendingCount = pending
        this.alertUnconfirmed = pending > 0
        this.alertTargetDate = firstPendingDate || this.stepDayStr(1, today)
      } catch (e) {
        // 静默：提示条不是本页的主数据源，它挂了就把整页拖进错误态，用户会以为「清单也读不到」
        this.alertUnconfirmed = null
      }
    },
    // 名下门店 id：只认回包里确实是数字/数字串的 id，其余（缺键、null、非数组）一律当「没读到」
    storeIdsOf(res) {
      const rows = res && res.data
      if (!Array.isArray(rows)) return []
      const ids = []
      rows.forEach((r) => {
        const id = r && r.id
        if (id !== null && id !== undefined && id !== '' && !Number.isNaN(Number(id))) ids.push(id)
      })
      return ids
    },
    // 相对今天偏移 delta 天的 YYYY-MM-DD（FE-6 的「明日」）：复用本页既有的日期工具，不另起一套
    stepDayStr(delta, base) {
      const d = this.parseDate(base || this.todayStr()) || this.newToday()
      d.setDate(d.getDate() + delta)
      return this.fmtDate(d)
    },
    dismissAlert() {
      this.alertDismissed = true
    },
    // FE-6：这一跳必须带批次日期，否则是死胡同——警示条按「今天」查，而批次由平台在「明日」生成
    // （后端 arrivals 的 date 缺省也是明日，PartnerStoreServiceImpl.java:251-253），
    // 不带日期过去就是批次页的今日空态。目标日：名下确有未确认批次 → 那一批的 batchDate；整日无批次 → 明日。
    // 批次页的空态文案不另加「点『后一天』查看」那句（两处只说一次，避免同一件事两套话）。
    goArrival() {
      const date = this.alertTargetDate || this.todayStr()
      uni.navigateTo({ url: '/pages/arrival/arrival?date=' + encodeURIComponent(date) })
    },
    // ==================== 待取清单加载（分页手写：本工程无分页插件，RB 红线口径） ====================
    // @param {boolean} reset true = 按当前日期重拉第一页；false = 追加下一页
    // @param {boolean} [resetView] 改了日期要立刻退回加载态（否则上一日期的行会被读成本日期的）；
    //                              onShow 的静默重拉不传，已有清单时保持可见，避免每次回页闪一次白卡
    async fetchList(reset, resetView) {
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
        const res = await getPartnerOrders({
          pickupDate: this.queryDate,
          status: LIST_STATUS,
          page: targetPage,
          size: this.size
        })
        // 回包晚于条件变更（在途期间又步进了一格日期）：整批丢弃、按最新条件重发。
        // 把上一日期的行拼进本日期是最要命的一类脏显示
        if (this.condKey() !== condKey) {
          this._reloadQueued = true
          this._queuedReset = true
          this._queuedView = true
          return
        }
        const data = (res && res.data) || {}
        const rows = Array.isArray(data.list) ? data.list : []
        // 分页三值一律以后端回显为准（size 被夹到 [1,50]、page<1 归一 1，都是夹而不拒）：
        // 用请求值推进页码就会跳过被夹掉的那一段或重复取同一页
        const echoPage = Number(data.page)
        const echoSize = Number(data.size)
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
        // 日期显示值：orders 端点不回显 date（出参只有 list/page/size/total/hasMore，
        // PartnerOrderServiceImpl#orders 的 data.put 五键，不含 date），所以这里回落到本次请求值——仍是「一份真值」
        this.effectiveDate = String(data.date || this.queryDate || '')
        this.viewState = this.list.length ? 'ready' : 'empty'
        if (reset && this._resetScrollTop) {
          this._resetScrollTop = false
          // #ifdef H5
          document.body.scrollTop = 0
          // #endif
        }
      } catch (e) {
        if (e && e.code === 401) {
          // 跳转与清态都由请求层做完，这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        if (this.condKey() !== condKey) {
          this._reloadQueued = true
          this._queuedReset = true
          this._queuedView = true
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = Number((e && e.code) || 0)
        // 只有 429 才起冷却锁：其余失败重试是有意义的，不该被秒数拦住
        if (Number(e && e.code) === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        // 翻页失败（清单在手）不动整页态：保留现场只 toast；首屏/重拉失败才整页换错误卡
        if (reset || !this.list.length) {
          this.viewState = 'error'
        } else {
          // 429 那一支给统一句式（FE-10）：翻页撞限流与提交撞限流听起来必须是同一把锁；
          // F-28.5 路 A（X-5）：toast 不带数——同屏「加载更多」按钮的闸门标签（Ns后可重试）才是读侧带数处
          this.showToast(this.errorCode === 429 ? coolingText() : (this.errorText || '加载更多失败，请重试'))
        }
      } finally {
        this.loadingMore = false
        this._loadingList = false
        if (this._reloadQueued) {
          const asReset = this._queuedReset === true
          const asView = this._queuedView === true
          this._reloadQueued = false
          this._queuedReset = false
          this._queuedView = false
          this.fetchList(asReset, asView)
        }
      }
    },
    // 当前生效条件指纹（date + status）：回包时比对，防止旧条件的响应落到新条件的清单上
    condKey() {
      return this.queryDate + '|' + LIST_STATUS
    },
    // 追加成新数组而不是 push：跨页边界时后端可能把同一行在相邻两页各给一次（新单挤进上一页边界），
    // 去重后 concat 出新引用，Vue 侧一次替换
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
    // ==================== 翻页 / 日期步进 ====================
    loadMore() {
      // 429 冷却闸门同样要拦住自动触底：只拦错误卡上的按钮的话，限流后往下滚会一路续桶，
      // 把该主理人其它读接口（批次清单、门店资料）一起打死
      if (this.retryLock > 0) return
      if (this._loadingList || !this.hasMore) return
      this.fetchList(false)
    },
    stepDay(delta) {
      const base = this.queryDate || this.effectiveDate || this.todayStr()
      const d = this.parseDate(base) || this.newToday()
      d.setDate(d.getDate() + delta)
      this.queryDate = this.fmtDate(d)
      // 步进日期只重拉清单，不动输码区与反馈行：核销现场是「手里正拿着一个码」，
      // 翻一下日期就把已输的码清掉等于逼主理人重输；上一条反馈也是刚那次提交的事实，不随翻页失效
      this.fetchList(true, true)
    },
    goToday() {
      this.queryDate = this.todayStr()
      this.fetchList(true, true)
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
    // ==================== 渲染辅助（只渲染后端白名单键，见 PartnerOrderServiceImpl#orders 的 line.put 块） ====================
    // 行指纹：清单出参没有 orderItemId（只有截断后的 orderId 后 6 位），所以按「同一单 + 同一商品快照」
    // 组指纹去重。同单同名的两条完全相同的行会被并成一条——已登记为契约缺口，不在前端补 JOIN 去猜行 id
    rowKey(r, i) {
      const orderId = r && r.orderId
      if (orderId === null || orderId === undefined || orderId === '') return 'x' + i
      return 'i' + orderId + '|' + String((r && r.dishName) || '') + '|' + String(r && r.quantity)
    },
    dishNameOf(it) {
      return (it && it.dishName) || '未命名商品'
    },
    storeNameOf(it) {
      return (it && (it.storeName || '未指定自提点')) || '未指定自提点'
    },
    phoneOf(it) {
      // 只出脱敏值 maskedPhone（RD）：清单出参根本没有未脱敏手机号这一列，前端也不做二次加工
      return (it && it.maskedPhone) || '电话未回显'
    },
    // 下单时间：带 '-' 的后端字符串原样透出（把 ISO 的 'T' 换空格、砍到分钟）。
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
    countOf(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n : 0
    },
    showUnpaid(it) {
      return !!(it && it.payStatus != null && Number(it.payStatus) === 0 && it.status !== 'cancelled')
    },
    // ==================== 429 冷却（照 plan-orders.vue 的 startRetryLock / stopRetryLock 那一对同款；F-25/P-3 起入参是错误对象） ====================
    // 只在 code===429 起（页面纪律 ②）：调用点全部在 catch 里判过码，这里不重复判、也不接受别的来源
    // X-1 拆锁：startRetryLock/stopRetryLock 只管读侧闸门 retryLock（fetchList 撞 orders 读桶）；
    // 写侧闸门另有 startWriteRetryLock/stopWriteRetryLock 一对，两把锁互不覆盖。
    startRetryLock(err) {
      this.stopRetryLock()
      // F-25/P-3：入参由「调用方算好的秒数」改为 catch 到的错误对象，秒数一律由 api 层 retrySecondsOf 取值
      // （与 apply/login/workspace 那一族同形）。原先方法内那道 `Number(seconds) > 0 ? … : RETRY_FALLBACK_SECONDS`
      // 二次兜底已删——回落只能有一个出处。
      // 秒数 = 后端 data.retryAfterSeconds（读桶 429 的剩余 TTL）优先，取不到回落本地 60。
      // 回落 60 与读侧 @RateLimit 的 60s 窗口同档；冷却结束再拉仍可能吃一次 429，
      // 那一支会把这一行重新写上，反馈不会说谎
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
    // X-1：写侧闸门专用一对——submitVerify 撞 D-14 防枚举锁（可达 600s）只动这两枚变量，
    // 读侧任何失败（含非 429 的 stopRetryLock）都无法把这里的长闸门清掉。秒数仍只来自 retrySecondsOf。
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
    // 两道守卫缺一不可（照 plan-orders.vue 的 onPageWheel 与其上方的守卫说明注释）：
    // ① 栈顶守卫：监听挂在 document 上，navigateTo/reLaunch 都不保证触发本页 onUnload，
    //    栈里多挂一份 handler 就会一次滚两倍。
    // ② 弹层与表单控件放行：picker/modal/toast 以及取货码 input 上的滚轮归控件，不能 preventDefault 抢走。
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
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.empty-row/.guide-link/.btn-main/.cell-arrow 取自 common.css，
   本页只补间距与本页专属的字，不动主题变量、不加新公共类） */

/* ==================== 1. 到货警示条 ==================== */
.alert-card {
  margin-top: 24rpx;
  background-color: #FFF1F0;
  border: 1rpx solid #E02020;
  display: flex; flex-direction: column; gap: 12rpx;
}
.alert-text { font-size: 28rpx; font-weight: 700; color: #E02020; line-height: 1.5; }
.alert-row { display: flex; align-items: center; gap: 16rpx; }
.alert-link {
  font-size: 24rpx; font-weight: 600; color: #FFFFFF; line-height: 1.4;
  background-color: #E02020; border-radius: 999rpx; padding: 10rpx 24rpx;
}
.alert-close {
  font-size: 24rpx; color: #E02020; line-height: 1.4;
  border: 1rpx solid #E02020; border-radius: 999rpx; padding: 10rpx 24rpx;
}
.pill-hover { opacity: .82; }

/* ==================== 2. 输码卡 ==================== */
.verify-card { margin-top: 24rpx; display: flex; flex-direction: column; gap: 16rpx; }
.block-title { font-size: 30rpx; font-weight: 700; color: #333333; line-height: 1.4; }
.code-input {
  height: 88rpx; padding: 0 24rpx;
  font-size: 36rpx; font-weight: 700; color: #333333; letter-spacing: 6rpx;
  background-color: #F7F7F7; border: 1rpx solid #EEEEEE; border-radius: 12rpx;
}
.form-hint { font-size: 22rpx; color: #999999; line-height: 1.5; }
/* 常驻反馈四支：成功=主红加粗，幂等=中性灰（不是失败就不该穿红），失败=主红，冷却=橙红 */
.verify-feedback { font-size: 28rpx; font-weight: 600; line-height: 1.5; padding: 12rpx 16rpx; border-radius: 12rpx; }
.verify-feedback-ok { color: #FFFFFF; background-color: #E02020; }
.verify-feedback-neutral { color: #666666; background-color: #F5F5F5; }
.verify-feedback-err { color: #E02020; background-color: #FFF1F0; }
.verify-feedback-cooling { color: #E02020; background-color: #FFF7F0; }

/* ==================== 3. 日期条（取值口径与 pickup-plan.vue 样式小节的「1. 日期条」同档） ==================== */
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

/* ==================== 4/5/6. 三态卡 ==================== */
.loading-card { display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; }
.empty-card { display: flex; flex-direction: column; align-items: center; }
.empty-actions { display: flex; gap: 24rpx; }

/* ==================== 7. 清单行 ==================== */
.list-card { margin-top: 24rpx; padding: 0 24rpx; overflow: hidden; }
.order-item { padding: 24rpx 0; border-bottom: 1rpx solid #F5F5F5; }
.order-item:first-child { padding-top: 20rpx; }
.order-item:last-child { border-bottom: none; }
.item-line { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8rpx; }
.item-sub-line { margin-top: 8rpx; }
.item-unit { display: flex; align-items: center; min-width: 0; gap: 8rpx; }
.item-dish {
  flex: 1 1 auto; min-width: 0;
  font-size: 30rpx; font-weight: 600; color: #333333; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.item-qty { flex-shrink: 0; font-size: 26rpx; color: #333333; line-height: 1.5; font-variant-numeric: tabular-nums; }
.item-phone { flex-shrink: 0; font-size: 26rpx; font-weight: 600; color: #666666; line-height: 1.5; font-variant-numeric: tabular-nums; }
.item-dot { flex-shrink: 0; font-size: 24rpx; color: #DDDDDD; line-height: 1.5; }
.item-store {
  flex: 0 1 auto; min-width: 0; max-width: 240rpx;
  font-size: 24rpx; color: #666666; line-height: 1.5;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
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
.item-tag-unpaid { color: #FFFFFF; background-color: #E02020; }
.item-unpaid-note { display: block; margin-top: 8rpx; font-size: 20rpx; color: #E02020; opacity: .8; line-height: 1.4; }

/* ==================== 8. 加载更多 ==================== */
.load-more { display: flex; flex-direction: column; align-items: center; margin-top: 24rpx; }
.btn-more {
  margin: 0; padding: 0 48rpx;
  height: 72rpx; line-height: 72rpx;
  background-color: #FFFFFF; color: #E02020;
  font-size: 26rpx; font-weight: 600;
  border: 1rpx solid #E02020; border-radius: 999rpx;
}
.btn-more[disabled] { color: #F5B5B0; background-color: #FFFFFF; border-color: #F5B5B0; }
.btn-more.btn-more-cooling { color: #BBBBBB; border-color: #EEEEEE; cursor: not-allowed; }
.no-more { font-size: 24rpx; color: #999999; line-height: 1.6; }

.page-bottom-space { height: 60rpx; }
</style>
