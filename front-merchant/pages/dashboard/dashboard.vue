<template>
  <view class="page-container">
    <!-- ==================== 1. 窗口切换（7 / 30 / 90 三档点选，默认 7） ====================
             刻意不给「自定义天数」输入框：值域 [1,90] 的裁决权在服务端（契约 D-3，越界 400
             「天数需在 1 到 90 之间」），页面放开自由输入就等于造一次注定失败的往返，
             还会多出「输入了 999 但屏上还是 90」这种两套真值 -->
    <view class="card window-card">
      <!-- 三档 pill 的字段名标签（U-13：两套母版都有这一层，缺了它 pill 就只是三个不知道在切什么的按钮）
           复用本页既有 .window-line 类，不新增样式 -->
      <text class="window-line">统计窗口（点选切换下方四卡与 Top 商品）</text>
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
      <!-- 窗口起点一句实话：两个值都只认后端回显（见 computed windowLine 与类注释），页内不推算任何统计日期 -->
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
             两层式照 pickup-plan.vue：主文案是人话，后端 message 原文降为第二行小字。
             404 例外：主文案就是后端 message 原文（S-7/U-14：404 是「商家不存在」这类确定性失败，
             说成「稍后重试」是把确定失败伪装成可恢复），此时第二行不再重复印同一句 -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText && errorCode !== 404" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="loadStat(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryPillText }}</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 5. 就绪：四卡 + Top 商品 + 口径说明 ==================== -->
    <template v-else>
      <!-- 四张卡逐键取后端出参（gmvBeforeCoupon / orderCount / itemCount / refundCount），
           页面零加减：把 refundCount 从 GMV 里扣掉、把 itemCount 除成客单价，都会让读数与服务端
           手工对账（G4）打脸，也会让「取消单」这一格白摆（契约 D-4 就是要把坏消息一起摆出来） -->
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
          <text class="top-note">按成交金额排序，按商品聚合，最多 5 行；其余商品未列出，故合计可小于上方销售额</text>
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

      <!-- ==================== 口径说明（D-5 硬要求，逐字三行不可省、不可润色；第四/五行按 F-19.8 S-4/S-5 逐字补） ==================== -->
      <view class="card caliber-card">
        <text class="card-title">口径说明</text>
        <text class="caliber-line">我的销售额（商品明细·券前）＝ Σ成交单价×数量，含秒杀价，不含券抵扣</text>
        <text class="caliber-line">平台已收款（订单口径）＝ Σ订单应付，含同一订单里其他商家的明细</text>
        <text class="caliber-line">两个数字天然对不平，差额＝券抵扣＋同单其他商家明细。本期不分摊，分摊随结算（m19+）定义。</text>
        <!-- 第四行（S-4/U-1 逐字）：refundCount 的谓词只有 status='cancelled'，库里 38 笔取消单 37 笔从未支付
             （merchant 2 近 7 天 19/19 全是未付款自动关单）。这一格与「订单数」同权重并排，缺这一句就会被读成
             「83% 跑单」；分母差由文字承担，SQL 谓词不动（动了就推翻 F-19.5 G4 已留证的 refund=19） -->
        <text class="caliber-line">取消单＝窗口内取消的订单，含未付款自动关单，与上方订单数不是同一分母</text>
        <!-- 第五行（S-5 逐字）：免费领 0 元单 pay_status=1、price=0，进 orderCount/itemCount 而不进 GMV，
             极端情形是「订单数 2、商品件数 2、销售额 0.00」——上面三行解释不了这个组合 -->
        <text class="caliber-line">免费领 0 元单不进本看板：它没有成交金额</text>
        <!-- 后端刻意不下发「平台已收款」那个数（那是 G-06 运营大盘的读数），本页也不做减法：
             这一块的用处就是说清「为什么这里只有一个销售额、以及它和平台口径差在哪」，不是给第二个数 -->
        <text class="caliber-foot">本看板只列商品明细口径，不列平台侧金额</text>
      </view>

      <!-- 读数拉取时刻一行（U-9/U-10）：本页 onShow 是静默重拉，没有这一行商家无法判断屏上数字是哪一次拉的；
           措辞用「拉取」不用「刷新」，也精度到秒（同分钟内两次进页只到分钟会一字不差）。
           红线：这一行只是"这一次请求成功的时刻"，不参与任何统计日期的推算（窗口起点仍只认后端回显） -->
      <text v-if="readTimeLine" class="refresh-line">{{ readTimeLine }}</text>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端数据看板（G-05 / F-19 只读页）：GET /api/merchant/stat?days= 的四卡 + Top 5 商品 + 口径说明卡。
// 三条页面纪律：① 四个数逐键取后端出参，页内零加减（D-4）；
// ② windowStart / days 只显示后端回显值，页内不 new Date() 推算任何统计日期（D-3，与后端 CURDATE() 同式才算一份真值）；
//    唯一例外是 formatClock 把「读数拉取时刻」的 ms 印成 HH:mm:ss（母版 workspace.vue:266-271 同款），它不参与任何计算；
// ③ 四态单值互斥 loading | ready | forbidden | error，不用多个布尔叠加（照 workspace.vue 的 viewState）。
// 归属由服务端 @RequestAttribute 解析，本页不发任何 merchantId；401 一律由请求层清态回登录页，本页不判 401。
// 文案红线（RE）：本页不出现任何资金含义的读数词——mock 支付无资金流，销售额只按「商品明细·券前」口径出。
import { getMerchantStat, retrySecondsOf, coolingFullText, coolingLeadText, cooldownPillText } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/dashboard/dashboard'

// 窗口三档（契约 D-3 值域 [1,90] 里挑三个常用档；缺省 7 与后端 @RequestParam(defaultValue="7") 同值）
const DAYS_OPTIONS = [7, 30, 90]

// 429 冷却秒数的回落档：本端点限流窗口是 60 秒（MerchantOrderController:90 USER 30 次 / 60 秒），故回落取 60。
// 现在的取值口径是「优先转述后端真值，取不到才回落到本常量」：F-22 起 RateLimitInterceptor 在 429 的 data 里
// 下发 retryAfterSeconds，F-23 起本层 reject 也带 data，页面用 retrySecondsOf(err, RETRY_COOLING_SECONDS) 起锁。
// 回落值为什么选整个窗口而不是回速：窗口 60 是「后端不给数时唯一能确定的安全上限」，
// 回速（30 次/分实测约 2 秒）只说明桶多久回充一格，不代表现在重试一定不挨 429 —— 两个数不是一回事。
// 【历史记录，勿删】F-23 之前请求层有条「RA 红线一字不动」的约定，商家端 reject 只带 {code,message} 不带 data，
// 页面拿不到后端剩余秒数，那时「本地按窗口取值」是唯一不猜的路；F-23 打通 data 通道后窗口值退居兜底档，
// 当时的约束确实成立过，故留文不删。
const RETRY_COOLING_SECONDS = 60

// FE-10 同页 429 只留一句说法：改前本页私有的 coolingText（「操作过于频繁，请 Ns 后再试」）既无归因、
// 又让错误卡主文案与倒计时胶囊各报一个数（同屏两处带数，违反 X-5）。F-28.5 路 B 把它并入 api 层冷却文案族——
// 主文案走 coolingLeadText（归因「经营读数」、不带数），数只在模板 retryPillText 那一处出，句式仍只有一族

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
      // 本次 stat 成功时刻（ms），仅驱动「本页读数拉取于 HH:mm:ss」一行；
      // 它不是统计日期的一部分（窗口起止一律只认后端 windowStart / days 回显，D-3 红线）
      refreshAt: 0,
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
    // 两张卡的措辞按 F-19.8 逐字改（U-8：同一屏不许出现两个名字——卡片 label 与 D-5 口径行必须是同一个短语，
    //   被省掉的「商品明细」正是把它与「平台已收款」区分开的那个限定词；
    //  S-4/U-1：「被取消订单」会被读成"曾成交又退了"，而库里 38 笔取消单 37 笔从未支付，故如实写「含未付款」）
    statCards() {
      return [
        { key: 'gmv', label: '我的销售额（商品明细·券前）', unit: '元', value: this.textOf(this.gmvBeforeCoupon), warn: false },
        { key: 'order', label: '订单数', unit: '单', value: this.textOf(this.orderCount), warn: false },
        { key: 'item', label: '商品件数', unit: '件', value: this.textOf(this.itemCount), warn: false },
        // 坏消息与好消息同权重摆出来（D-4 ④）：只报成交不报取消的看板是在替商家美化数据
        { key: 'refund', label: '取消单（含未付款）', unit: '单', value: this.textOf(this.refundCount), warn: true }
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
      // F-19.8 S-7/U-14 的四路分流（形状照本仓既有分支，只换判据与措辞）：
      // F-28.5 路 B：429 一支改走 api 层 coolingLeadText（归因＋不带数）——改前这里是带数的
      // 「操作过于频繁，请 Ns 后再试」，与同屏下方那枚「Ns后可重试」胶囊构成同一把锁两处报数（X-5 违例），
      // 且「操作过于频繁」无归因，商家读不出锁的是经营读数还是别的端点
      if (this.errorCode === 429) return coolingLeadText('经营读数')
      // 404（AuthInterceptor:95-99 注入的 merchant_id 查不到行 →「商家不存在」）是确定性失败，
      // 重试永远拿回同一枚错误码，故主文案直出后端 message，不再说「请稍后重试」
      if (this.errorCode === 404) return this.errorText
      // 500：服务端处理出错，与「网络不通」是方向相反的两种因果，不能再共用一句「请检查网络」
      if (this.errorCode === 500) return '经营读数加载失败，服务端处理出错，稍后重试'
      // 只有网络层失败（api 层回 code:-1 / '网络异常'）才说「请检查网络后重试」
      if (this.errorCode <= 0) return '经营读数加载失败，请检查网络后重试'
      // 其余服务端业务码（400 一类）：不猜成因，报「加载失败」＋第二行透出后端 message 原文
      return '经营读数加载失败，请稍后重试'
    },
    // 重试有没有可能成功：403 已单独走 forbidden 态；404 是确定性失败，重试无望（S-7/U-14），
    // 两枚都不给重试按钮，模板那一行的 !errorRetryable 分支会给「回工作台」出口
    errorRetryable() {
      return this.errorCode !== 403 && this.errorCode !== 404
    },
    // 冷却中的时长短式：本屏这一把锁（经营读数）的数只在这一处出现（X-5），
    // 归因由同卡 errorLeadText 给；句式生成在 api 层 cooldownPillText
    retryPillText() {
      return cooldownPillText(this.retryLock)
    },
    // 「本页读数拉取于 HH:mm:ss」（U-9/U-10）：精度到秒 —— 母版 workspace.vue:266-271 那句
    // 「资料刷新于」就是这个精度，理由一致：同一分钟内两次进页若只到分钟，字符串一字不差，
    // 商家（和验收）就无从判断 onShow 的那一次静默重拉到底发生过没有
    readTimeLine() {
      return this.refreshAt ? '本页读数拉取于 ' + this.formatClock(this.refreshAt) : ''
    }
  },
  onShow() {
    // 429 冷却闸门（U-4）：限流态下 onShow 的静默重拉也一样在给桶续命，判据逐字照
    // plan-orders.vue 的 loadMore（闸门就写在它里面，那句注释原文：「只拦错误卡上的按钮的话，限流后往下滚会一路续桶，
    // 把该商家其它读接口一起打死」）——本仓为这条踩过一次，本期不再重犯
    if (this.retryLock > 0) return
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
    // 卸载标记（U-5）：在途请求的回包晚于 onUnload 时，下面三处 _unloaded 守卫拦住
    // 「迟到回包再发一条请求并起一个每秒 setInterval」——页面已销毁，那个定时器没人回收
    this._unloaded = true
    // #ifdef H5
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
    // 冷却计时器随页销毁一起清：setInterval 挂在 window 上，不摘就会在页面卸载后
    // 继续每秒写一次已死组件的状态（与 startRetryLock 成对，姿势照 pickup-plan.vue 的 onUnload 收尾）
    this.stopRetryLock()
  },
  methods: {
    // ==================== 数据加载 ====================
    // @param {boolean} [resetView] 换窗口要立刻退回加载态，让上一窗口的四卡让位（否则会被读成新窗口的数）；
    //                              onShow 的静默重拉不传
    async loadStat(resetView) {
      // 页面已卸载：回包/重拉都不再有任何意义（U-5），渲染目标已经不存在了
      if (this._unloaded) return
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
        // 只在成功回包时记时刻（失败不记，否则「拉取于」会被读成"失败的那一次也算读过"）
        this.refreshAt = Date.now()
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
        // 只有 429 起冷却锁（母版 pickup-plan.vue 的 loadPlan 错误分支）：其余失败不锁，避免把网络抖动也锁成 60 秒
        if (code === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loadingStat = false
        if (this._reloadQueued) {
          // 卸载后不再排队重发：这一条若回 429，还会经 startRetryLock 起一个无人回收的每秒定时器（U-5）
          if (this._unloaded) return
          this._reloadQueued = false
          this.loadStat(true)
        }
      }
    },
    // ==================== 窗口切换 ====================
    switchDays(d) {
      // 429 冷却闸门（U-4 实测：打空 30/分桶后进看板，卡正上方三枚 pill 照点，点「近 90 天」就实发
      // ?days=90 一条、窗口行立刻变、倒计时文案被覆写——闸门被旁路）。
      // 无操作先早退、闸门只判真的要换档的点击（同 dishes/plan-orders 的 switchTab）：点已选中的那一档
      // 本来就不发请求，不该被算成「被闸门拦下」而弹一句「太频繁」（模板 .window-tab.active.pill-hover
      // 那一段依赖「点当前档不发请求」这一律，故同档判断必须留在闸门之前）。
      const next = Number(d)
      if (!Number.isFinite(next) || next === this.days) return
      // X-4：这一路改前判了闸门却**静默 return**——pill 是用户主动点击，与 onShow 的自动重拉不同，
      // 拦而不说就等于「这页点不动」。补一条带秒数的反馈，秒数只读既有 retryLock（数值来自后端
      // data.retryAfterSeconds 经 retrySecondsOf），不新增第二个闸门状态变量。
      if (this.retryLock > 0) {
        uni.showToast({ title: coolingFullText('经营读数', this.retryLock), icon: 'none' })
        return
      }
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
    // 时间戳 -> HH:mm:ss（母版 workspace.vue:266-271 同款写法；脏值返回空串，模板随之隐藏这一行）。
    // 红线：这个时刻只用于「读数拉取于」一行，绝不参与统计窗口/日期的任何推算（D-3：窗口只认后端回显）
    formatClock(ms) {
      const d = new Date(ms)
      if (!Number.isFinite(d.getTime())) return ''
      const p = (n) => String(n).padStart(2, '0')
      return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },
    // ==================== 跳转 ====================
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    // ==================== 429 冷却（照 pickup-plan.vue 的「429 冷却」一节，秒级禁用范式） ====================
    startRetryLock(err) {
      // 已卸载的页面不给它起定时器（U-5）：迟到回包回 429 时若照常 startRetryLock，
      // 那个每秒 setInterval 挂在 window 上、onUnload 的清理已经跑完了，永远不会被回收
      if (this._unloaded) return
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
    // 两道守卫缺一不可（照 pickup-plan.vue 的 onPageWheel）：
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
/* 当前档的按下态（U-13）：.window-tab.active 是 (0,2,0)、.pill-hover 只有 (0,1,0)，
   于是点已选中的那一档毫无视觉反馈，而 switchDays 又对它早退不发请求 —— 看着就像「这页点不动」。
   这里给同一条规则 (0,3,0) 的权重把它压回去，色值全取本色板已有的 #B01818/#FFFFFF，不引新色 */
.window-tab.active.pill-hover { color: #FFFFFF; background-color: #B01818; border-color: #B01818; }
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

/* ==================== 7. 口径说明（D-5 逐字三行 + S-4/S-5 补的两行，行序即原文序） ==================== */
.caliber-card { display: flex; flex-direction: column; gap: 12rpx; }
.caliber-line { font-size: 24rpx; color: #666666; line-height: 1.7; }
.caliber-foot { font-size: 20rpx; color: #BBBBBB; line-height: 1.6; }

/* ==================== 8. 读数拉取时刻行（与营销页 .refresh-line 同一用途，只在本页 scoped 内定义） ==================== */
.refresh-line {
  display: block; margin: 20rpx 24rpx 0;
  font-size: 20rpx; color: #CCCCCC; line-height: 1.4; text-align: right;
}

.page-bottom-space { height: 60rpx; }
</style>
