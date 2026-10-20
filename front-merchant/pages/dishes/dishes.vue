<template>
  <view class="page-container">
    <!-- ==================== 1. 筛选区（关键字 + 三 tab + 类目） ====================
             三个条件都作为 query 交列表接口，本页不做二次过滤：归属集合与筛选口径由服务端唯一裁决
             （tab/category/keyword 的宽松语义见 F-13.6 边界 14，前端不复制一份判定逻辑） -->
    <view class="card filter-card">
      <!-- 商家生命周期状态只读透出（与 workspace.vue:45「商家当前状态」那条只读行同款、同走 api 层 merchantStatusText）：
           本页的「新增」是写路径，停业/清退商家点了必然吃 403，故状态行必须出现在写入口旁边而不是只在工作台 -->
      <text v-if="merchantStatus && merchantStatus !== 'active'" class="status-tip">商家当前状态：{{ statusText }}</text>
      <view class="search-row">
        <input
          class="search-input"
          v-model="keyword"
          placeholder="搜索商品名称"
          placeholder-style="color:#BBBBBB;"
          confirm-type="search"
          @confirm="applyFilters"
        />
        <text class="search-go" @click="applyFilters">搜索</text>
        <!-- 清空按钮看「输入中」与「已生效」两个来源：只判 keyword 时，切 tab 已把未点搜索的关键字带进查询，
             框里有字但按钮不见了，用户无从得知列表正被这个关键字筛着（FEA-1 两套真值的可见面） -->
        <text v-if="keyword || appliedKeyword" class="search-clear" @click="clearKeyword">清空</text>
        <!-- 新增入口做成筛选卡行内按钮（取代原常驻 fab）：fixed fab 在 500×416 视口下与列表第 0/1 行重叠，
             elementFromPoint 实测重叠区命中的是 fab，滚动中点行尾 › 箭头会跳进空白新增页，
             而「新增即上架」（DB on_sale 默认 1）会当场造出真垃圾 SKU —— 常驻悬浮按钮在这个页面上是误点源。
             置灰但保留点击：不加原生 disabled（那会连「toast 指路」一起吞掉），改在 goCreate 内判定并告知原因 -->
        <button class="btn-sub" :class="{ 'btn-sub-disabled': writeBlocked }" @click="goCreate"><text>新增</text></button>
      </view>
      <!-- 三 tab 母版照 front/pages/my-coupons/my-coupons.vue:19-29（只读参照，键值直接对齐后端 tab 参数） -->
      <view class="dish-tabs">
        <view
          class="dish-tab"
          v-for="t in tabs"
          :key="t.key"
          :class="{ active: tab === t.key }"
          @click="switchTab(t.key)"
        >
          <text>{{ t.label }}</text>
        </view>
      </view>
      <!-- 类目下拉：11 类只选不建（F-13.4），取值域来自字典 dish_category 组，页内不硬编码一份枚举 -->
      <picker class="cate-picker" mode="selector" :range="categoryLabels" :value="categoryIndex" @change="onCategoryChange">
        <view class="picker-row">
          <text class="picker-label">类目</text>
          <text class="picker-value">{{ categoryLabels[categoryIndex] }}</text>
          <text class="picker-arrow">›</text>
        </view>
      </picker>
      <!-- 类目可选项的来源不对劲时（退化到内置分类 / 读取失败）追加一行说明并可点重试：
           列表本身不受影响，故不切整页错误态，但也不能静默（商家会以为分类就这么几个） -->
      <text v-if="categoriesHint" class="cate-fail" @click="retryCategories">{{ categoriesHint }}</text>
    </view>

    <!-- ==================== 2. 加载中（首屏与筛选切换后的重拉都走这一态，避免旧列表闪现成「筛选结果」） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载商品…</text>
    </view>

    <!-- ==================== 3. 错误态（≠ 空态：空态是服务端确认「一条都没有」，错误态是根本没拿到数据） ====================
             两层式照 workspace.vue:6-12 的 .guide-tip 口径：主文案是人话（商家看得懂的下一步），
             后端原文降为第二行小字（排查用，不顶在人话前面）——api 层 handleEnvelope 保证 message 恒非空，
             此前 errorText 为空才回退的写法让那句人话成了死代码，屏幕只剩「服务开小差了」这类码表串。
             出口按「重试有没有可能成功」分流：403（角色/写闸门）与 404（行不存在）是确定性失败，
             不给「点击重试」，给「回工作台」；429 给人话并附秒级冷却，其余才直连重试 -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="loadDishes(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryPillText }}</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 4. 就绪：列表 / 空态 ==================== -->
    <template v-else>
      <view v-if="dishes.length > 0" class="card list-card">
        <view class="dish-item" v-for="d in dishes" :key="d.id" @click="goEdit(d)">
          <!-- 图：merchantFileUrl 归一化后展示，取不到（回空串）或加载失败才回退 emoji。
               emoji 这一路刻意无兜底值、与 C 端 index.vue 的 {{ dish.emoji }} 同一口径：
               空 emoji 就留空色块，商家端不替商家挑一颗白菜——预览比客人多一个符号，
               商家会以为「客人也看得见这颗白菜」，实际入库的是空值 -->
          <view class="dish-img" :style="{ backgroundColor: d.bgColor || '#F5F5F5' }">
            <image
              v-if="previewUrl(d.image) && !imgFail[d.id]"
              class="dish-photo"
              :src="previewUrl(d.image)"
              mode="aspectFill"
              @error="onImgFail(d.id)"
            />
            <text v-else class="dish-emoji">{{ d.emoji }}</text>
          </view>
          <view class="dish-body">
            <view class="dish-title-row">
              <text class="dish-name">{{ d.name }}</text>
              <text class="dish-badge" :class="isOnSale(d) ? 'badge-on' : 'badge-off'">{{ isOnSale(d) ? '在售' : '已下架' }}</text>
            </view>
            <text class="dish-meta">{{ categoryText(d.category) }} · 库存 {{ d.stock }} · 限购 {{ d.limitBuy }}{{ d.unit }}</text>
            <view class="dish-price-row">
              <text class="price-symbol">¥</text>
              <text class="price-num">{{ fmtPrice(d.price) }}</text>
              <text class="price-unit">/{{ d.unit }}</text>
            </view>
          </view>
          <text class="cell-arrow">›</text>
        </view>
      </view>

      <!-- 空态分两种：带筛选条件的空（引导清筛）与一条都没有的空（引导新增）——
           后者若也只说「没有商品」，商家会以为搜索坏了，不会想到自己还没上新 -->
      <view v-else class="card empty-card">
        <view class="empty-row">
          <text class="empty-text">{{ hasFilter ? '没有符合条件的商品，换个筛选条件试试' : '还没有商品，先上新第一条吧' }}</text>
        </view>
        <text v-if="hasFilter" class="guide-link" @click="resetFilters">清空筛选条件</text>
        <!-- 无筛选时的新增入口（与筛选卡行内那颗同一判定）：非 active 商家置灰、点击由 goCreate toast 指路，
             原生 disabled 不加——加了就没有「为什么点不动」的告知了 -->
        <button v-else class="btn-main" :class="{ 'btn-disabled': writeBlocked }" @click="goCreate"><text>新增商品</text></button>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端商品列表页（G-02 §F-13.4）：三 tab + 类目 + 关键字，全部条件交 GET /api/merchant/dishes，
// 本页只做渲染不做过滤；归属由服务端 attribute 解析，页面不发任何 merchantId（R2/R3）。
// 数据加载纪律照 workspace.vue：onShow 每次重拉（编辑页改完回页即反映最新值），带 in-flight 守卫防连点。
// 401 由 api 层统一清态回登录页（并 toast 告知原因），本页不再处理跳转；403 透 message 不清 token（handleEnvelope 语义），
// 本页对 403/404 这类确定性失败给「回工作台」出口而非重试。
import { getMerchantDishes, getDishCategories, getMerchantMe, merchantFileUrl, merchantStatusText, retrySecondsOf, coolingFullText, coolingLeadText, cooldownPillText } from '@/api/index.js'
import '@/styles/common.css'

// 429 冷却秒数的回落档：本端点（GET /api/merchant/dishes）限流窗口是 60 秒
// （MerchantDishController:43 USER 30 次 / 60 秒），故回落取 60。
// 起锁值优先转述后端 429 data 里的 retryAfterSeconds（F-22 下发、F-23 起请求层 reject 带 data），
// 取不到才回落到本常量。回落值取整个窗口，因为它是「后端不给数时唯一确定的保守上限」；
// 回速（30 次/分实测只有 2 秒）是桶回充一格的间隔，不代表重试一定不挨 429 —— 两个数不是一回事，别混用。
const RETRY_COOLING_SECONDS = 60

// 类目字典 429 闸门的回落档（F-24-B/FB-2 起，只在拿不到后端 retryAfterSeconds 时才生效）：
// 60 ＝ 字典档 @RateLimit 的 windowSeconds（DictController.java:28 是 IP 10 次 / 60 秒），
// 同样是「后端不给数时的保守上限」而不是回速——同一档配 Refill.greedy，实测回速只有 6 秒。
// 刻意不与上面那枚合并：两个端点限流档位不同（30 次/分 vs 10 次/分），共用一个常量必有一边口径错。
const CATEGORY_RETRY_FALLBACK_SECONDS = 60

export default {
  data() {
    return {
      // 三 tab：key 直接就是后端 tab 参数值，不做一层映射翻译（多一层命名就多一处漂移面）
      tabs: [
        { key: 'all', label: '全部' },
        { key: 'on_sale', label: '在售' },
        { key: 'off_sale', label: '已下架' }
      ],
      tab: 'all',
      // keyword = 输入框实时值，appliedKeyword = 已提交查询的值：
      // 只绑一个的话每次按键都会打一次列表接口（读限流 30 次/分，边打字边查很快就 429）
      keyword: '',
      appliedKeyword: '',
      categoryIndex: 0, // picker 下标；0 固定是「全部类目」，真实分类从 1 开始
      categories: [],   // 字典 dish_category 映射后的 [{code,name}]
      // 字典拉取失败原文（后端 message）：非空即在下拉行渲染一行可点重试的提示，不静默降级
      categoriesError: '',
      // true = 当前下拉是 api 层的内置 11 类（列表照常可选，但得让商家知道来源）
      categoriesDegraded: false,
      // 降级来源（api 层契约字段 degradedReason，只有 'rate-limited' / 'unreachable' 两个值）：
      // 把「429 是我们自己点快了」与「上游真的坏了」分开说，否则限流会被伪装成上游故障，
      // 商家照着「暂不可用」反复点重试，反而把 /api/dict 的 IP 10 次/分桶续满
      categoriesDegradedReason: '',
      // 类目重试冷却剩余秒数（>0 时点提示行不发请求）：限流态下的「重试」必须有秒级闸门，同 login.vue 的 loginLock 范式
      catRetryLock: 0,
      catRetryLockTimer: null,
      // 本枚闸门起点是否真的来自后端 data.retryAfterSeconds（>0 = 是，并记下那一个数）：
      // categoriesHint 只在它为真时才把秒数印进括号——闸门本身（catRetryLock）两种来源都要起，
      // 但「等待时长」这个读数只能转述后端给的，不能把本地窗口长度冒充成它（F-24.0 裁定 B-1a）
      catRetryBackendSeconds: 0,
      dishes: [],
      // 三态互斥（同 workspace.vue 的 viewState 纪律，避免多面布尔标志打架留脏态）：loading | error | ready
      viewState: 'loading',
      errorText: '',
      // 最近一次列表失败的错误码（0 = 无）：决定错误卡给「重试」还是给「回工作台」，以及是否附 429 冷却倒计时。
      // 只把 message 透出而不留 code，页面就无法区分「重试可能成功」与「重试永远失败」
      errorCode: 0,
      // 列表重试冷却剩余秒数（429 用）：限流窗口是 1 分钟，窗口内再点只会续桶并把 USER 30 次/分打满
      retryLock: 0,
      retryLockTimer: null,
      // 图片加载失败标记（按 dish.id）：与 C 端 index.vue 的 imgFail / onImgFail 同款——失败才回退 emoji，
      // 成功过的一次性标记在每次重拉后清掉，否则商家换了好图仍显示 emoji
      imgFail: {}
    }
  },
  computed: {
    // picker 的 range：首项恒为「全部类目」，其后接字典 11 类的中文名
    categoryLabels() {
      return ['全部类目'].concat(this.categories.map((c) => c.name))
    },
    // 当前生效的类目 code（'' 表示不带该筛选）
    category() {
      if (this.categoryIndex <= 0) return ''
      const c = this.categories[this.categoryIndex - 1]
      return c ? c.code : ''
    },
    // 是否带着任一筛选条件（决定空态文案是「清筛引导」还是「新增引导」）
    hasFilter() {
      return !!(this.appliedKeyword || this.category || this.tab !== 'all')
    },
    // 商家生命周期状态（store 的 me 快照，与 workspace.vue 同一份真值）：本页唯一的写入口「新增」要看它
    merchantStatus() {
      return (this.$store.state.merchant && this.$store.state.merchant.status) || ''
    },
    // 状态中文说法走 api 层 merchantStatusText（工作台状态行共用同一个函数，两页不得两种口径）
    statusText() {
      return merchantStatusText(this.merchantStatus)
    },
    // 写闸门预判（F-13 决策④：非 active 商家的写请求一律 403）：新增点了就是 403，
    // 故这里置灰并在点击时指路；快照只是预判，真判归服务端。
    // 判定口径与编辑页一致：store 里有 me 且 status 不是 active 即锁；无快照（null）时不预判成锁，
    // 免得把「还没拉过 me」误报成「商家被停业」
    writeBlocked() {
      const m = this.$store.state.merchant
      return !!(m && m.status !== 'active')
    },
    // 错误卡主文案（人话，第二行才透出后端原文）：按码分派。
    // X-2（四路分流，母版本页 dashboard.vue 的 errorLeadText）：改前最后一行是无条件兜底，
    // 把 500/400 也一并说成「请检查网络后重试」——而同屏第二行透出的后端原文（R.java 的 500
    // 「服务开小差了，请稍后重试」）正是服务端承认自己处理出错，两行因果方向相反：
    // 第一行让商家去查 Wi-Fi，第二行是服务端认错。判据：只有网络层失败才许说「检查网络」，
    // 500 说「服务端处理出错」，其余服务端业务码不猜成因
    // F-23-fix/FF-1：429 这行不带时长——同屏的 retryLock 倒计时行（模板 :70）已转述后端真值，
    // 这里再报一个数就是两处互相矛盾的谎报（截图 f23-c-cooldown.png 的 60 vs 1）。
    // F-28.5 路 B：句式并入 api 层冷却文案族（coolingLeadText），归因点名「商品列表」这把锁——
    // 本页屏上还有类目字典那把独立锁，「点得太快了，稍后再刷新」那种无归因说法读不出是哪一把
    errorLeadText() {
      if (this.errorCode === 429) return coolingLeadText('商品列表')
      if (this.errorCode === 403) return '暂无权限查看商品，请回工作台确认账号状态'
      if (this.errorCode === 404) return '没有查到商品数据，请回工作台重新进入'
      // 500：服务端处理出错，与「网络不通」是方向相反的两种因果，不能再共用一句「请检查网络」
      if (this.errorCode === 500) return '商品加载失败，服务端处理出错，稍后重试'
      // 只有网络层失败（api 层 request 的 fail 支回 code:-1 / message「网络异常」，见 front-merchant/api/index.js 其中网络支的 reject({ code: -1, message: '网络异常' })）
      // 才说「请检查网络后重试」
      if (this.errorCode <= 0) return '商品加载失败，请检查网络后重试'
      // 其余服务端业务码（400 一类）：不猜成因，报「加载失败」＋第二行透出后端 message 原文
      return '商品加载失败，请稍后重试'
    },
    // 重试有没有可能成功：403（角色/写闸门）与 404（行不存在）是确定性失败，重试只是白打一次请求；
    // 429 属于「等一会儿就能成」，故仍给重试但带秒级冷却。401 不会走到这一态（请求层已回登录页）
    errorRetryable() {
      return this.errorCode !== 403 && this.errorCode !== 404
    },
    // 冷却中的「点击重试」位：短式带数（.error-retry 是胶囊，宽度受限），归因由同卡上一行的
    // errorLeadText 给出——本屏这一把锁的数只在这一处出现（X-5），句式生成在 api 层 cooldownPillText
    retryPillText() {
      return cooldownPillText(this.retryLock)
    },
    // 下拉提示行：退化到内置分类按来源分两句（限流 vs 上游不可达），读取失败一句，
    // 正常字典态返回空串不出这一行
    // F-24-B/FB-2：时长只在「闸门起点确实转述自后端」时才出现。此前这里无条件印
    // catRetryLock，而它的起点是写死的 60 —— 那是 @RateLimit 的窗口长度，不是等待时长，
    // 印出来就是冒充（裁定 B-1a）。回落态只留文案，与运营端 5 页「无 data 就不出数字」同口径。
    // F-28.5 路 B：出数那一支从括号制「（Ns）」改成 api 层的 FULL 形（「类目请求太频繁，Ns后可重试」），
    // 与本页商品列表那把锁的 toast 同一句式——两把独立锁同屏时，各报各的数、各点各的名（X-5）；
    // 判据（catRetryLock > 0 && catRetryBackendSeconds > 0）与闸门本身一个字没动
    categoriesHint() {
      if (this.categoriesDegraded) {
        if (this.categoriesDegradedReason !== 'rate-limited') return '类目字典暂不可用，当前为内置分类，点击重试'
        return this.catRetryLock > 0 && this.catRetryBackendSeconds > 0
          ? coolingFullText('类目', this.catRetryLock)
          : coolingLeadText('类目')
      }
      return this.categoriesError ? '类目加载失败：' + this.categoriesError + '，点击重试' : ''
    }
  },
  onLoad() {
    this.loadCategories()
  },
  onShow() {
    // 从编辑页新增返回：把筛选退回「无筛选」再拉（FEA-7）。
    // 后端 orderByAsc(id) 新行在最末，若退回时仍带「已下架」tab 或关键字，新行既看不见、
    // 空态还会说「还没有商品」，诱导商家再建一条同名 SKU。
    // 标记挂在 this 上：navigateTo 不销毁本页实例，所以它能活到返回（onUnload 才销毁）
    let returnedFromCreate = false
    if (this._resetFiltersOnReturn) {
      this._resetFiltersOnReturn = false
      returnedFromCreate = true
      this.tab = 'all'
      this.categoryIndex = 0
      this.keyword = ''
      this.appliedKeyword = ''
      // #ifdef H5
      // 新增的可见性判据是「新行在屏上」：上一筛选态可能已滚到列表深处，退回后再滚到最末行等于没滚，
      // 与清筛回到第 1 行的语义也不符，故回顶
      document.body.scrollTop = 0
      // #endif
    }
    // 每次进入重拉（编辑页保存/上下架/改库存后回页即可见），首屏也由这里发起。
    // X-4：429 冷却期内这一路静默跳过（判据逐字照 marketing.vue / dashboard.vue 的 onShow，
    // 本仓两处母版都不给 toast——回页不是用户的点击，弹「太频繁」反而莫名；错误卡在手时倒计时行本来就在屏上）。
    // 唯一例外是从新增页返回：新 SKU 上屏是用户必要的可见性，且上面已把筛选清成无筛选，
    // 跳过就会被读成「加了商品却显示还没有商品」
    if (returnedFromCreate || this.retryLock <= 0) this.loadDishes()
    this.syncMerchantSnapshot()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动
    // （照 workspace.vue 现有实现逐字搬，本页同样有长列表需要滚）
    // 注意：document 是全局的，监听在栈顶判定（onPageWheel 首行）里兜住——见那处注释，
    // 本页 navigateTo 进编辑页时自己不会被 onUnload，光靠注册/清理配对会双份驱动滚动
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  // 滚轮监听仅 H5 注册（上方 onReady #ifdef 内），非 H5 端 _pageWheelHandler 恒为 undefined，清理分支天然跳过
  onUnload() {
    // #ifdef H5
    // 页面级滚轮兜底监听清理（与 onReady 注册配对）
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
    // 两个限流冷却计时器随页销毁一起清：setInterval 挂在 window 上，不摘就会在页面卸载后
    // 继续每秒写一次已死组件的状态
    this.stopRetryLock()
    this.stopCatRetryLock()
  },
  methods: {
    // ==================== 数据加载 ====================
    // 类目字典：失败只让下拉退化（列表本身不受影响），故不切换整页 viewState，给一行可点的重试提示
    async loadCategories() {
      try {
        const res = await getDishCategories()
        const list = (res && Array.isArray(res.data)) ? res.data : []
        // definition 形如 'code:meat'，code 即 dish.category 的取值（与 C 端 index.vue codeOf 同一口径）
        this.categories = list
          .map((d) => ({ code: String((d && d.definition) || '').replace(/^code:/, '').trim(), name: (d && d.word) || '' }))
          .filter((c) => c.code)
        // degraded = 这一批是 api 层的内置兜底，下拉照常可选但须让商家看见来源；
        // degradedReason 是 api 层契约字段（'rate-limited' | 'unreachable'），决定下面那句提示怎么说
        this.categoriesDegraded = !!(res && res.degraded)
        this.categoriesDegradedReason = (res && res.degraded && res.degradedReason) || ''
        // 兜底态下的 429 不进 categoriesError 分支（api 层已把 reject 转成 resolve 了，这里按 reason 起冷却）
        // degradedRetryAfterSeconds 是 api 层 F-24-B/FB-1 补的那一键：没有 data 时为 null，不是猜出来的数
        if (this.categoriesDegradedReason === 'rate-limited') this.startCatRetryLock(res && res.degradedRetryAfterSeconds)
        else this.stopCatRetryLock()
        this.categoriesError = ''
      } catch (e) {
        this.categoriesDegraded = false
        this.categoriesDegradedReason = ''
        this.categoriesError = (e && e.message) || '未知错误'
      }
    },
    // 类目提示行的点击入口：限流冷却期内直接拦掉，不拿注定 429 的请求去打那个 IP 10 次/分桶
    // （降级结果 api 层刻意不进缓存，所以每次点击都会真的重发一次 —— 闸门只能设在页面侧）
    retryCategories() {
      if (this.catRetryLock > 0) {
        // 这一声刻意不带数（X-5）：类目这把锁的数已经在 categoriesHint 那一行报了，
        // 一闪而过的 toast 再报一个数就是同屏两处；句式仍是 api 层的 LEAD 形，与那一行的回落态同串
        uni.showToast({ title: coolingLeadText('类目'), icon: 'none' })
        return
      }
      this.loadCategories()
    },
    // F-24-B/FB-2（原 F-23-fix/FF-3 的登记项，本批收口）：闸门秒数改为转述 api 层透出的
    // degradedRetryAfterSeconds，源头是 RateLimitInterceptor.java:79 的 data.retryAfterSeconds
    //（取的是 Refill.greedy 下的 NanosToWaitForRefill，不是窗口）。
    // 此前这里裸写 60 是「第 6 枚锁」：触发源读的是 getDishCategories() 的降级 resolve
    //（degradedReason==='rate-limited'），reject 已被 api 层转成 resolve，页面手里没有带 data 的
    // 错误对象 —— 现在 FB-1 把那一键随降级出参一起透出，页面只负责转述。
    startCatRetryLock(backendRetryAfterSeconds) {
      this.stopCatRetryLock()
      const n = Number(backendRetryAfterSeconds)
      const fromBackend = Number.isFinite(n) && n > 0
      // 判有效性在前、取整在后（与 api/index.js 的 retrySecondsOf、dish-edit.vue 的 categoryBackendSeconds 同形）：
      // 后端 RateLimitInterceptor#retryAfterSeconds 返回 long、实测恒为整数秒，这层 Math.ceil 不改变今天任何
      // 用户可见读数——它只是把三份实现的取整口径对齐，防未来某天下发小数秒时本页读到小数。属防御性统一，不是修 bug。
      const secs = fromBackend ? Math.ceil(n) : 0
      // 回落值 60 ＝保守上限＝窗口长度（DictController.java:28 字典档是 IP 10 次/60 秒），
      // 不是回速：同一档配 Refill.greedy，实测回速 6 秒 —— 回落时这一闸门会多白拦 54 秒，
      // 但拿不到后端真值时宁可保守，也不能报一个假的短数让商家连点续桶
      this.catRetryLock = fromBackend ? secs : CATEGORY_RETRY_FALLBACK_SECONDS
      // 只有这一枚起点来自后端，categoriesHint 才允许把它渲染成「（Ns）」的等待时长
      this.catRetryBackendSeconds = secs
      this.catRetryLockTimer = setInterval(() => {
        this.catRetryLock--
        if (this.catRetryLock <= 0) this.stopCatRetryLock()
      }, 1000)
    },
    stopCatRetryLock() {
      if (this.catRetryLockTimer) { clearInterval(this.catRetryLockTimer); this.catRetryLockTimer = null }
      this.catRetryLock = 0
      this.catRetryBackendSeconds = 0
    },
    // store 的 me 快照缺失或停在非 active 时补拉一次（编辑页与本页的写闸门都读这份快照）：
    // 平台恢复营业后不必先绕一次工作台才解除假锁，本页的状态行与「新增」置灰也据此翻转。
    // 失败静默——快照只是预判，真被停业的写请求照样吃 403，这一路拉不到不该打扰列表页
    async syncMerchantSnapshot() {
      const cached = this.$store.state.merchant
      if (cached && cached.status === 'active') return
      if (this._syncingMe) return
      this._syncingMe = true
      try {
        const res = await getMerchantMe()
        const me = (res && res.data) || null
        if (me && me.role === 'merchant') this.$store.commit('setMerchant', me)
      } catch (e) {
        // 拉不到就维持原快照，本页无用户可见副作用
      } finally {
        this._syncingMe = false
      }
    },
    // GET /api/merchant/dishes：200 → ready（含空数组）；401 由 api 层回登录页；
    // 其余（403/404/429/400/500/网络异常）→ error 态，并按 code 分流出口：
    // 403/404 确定性失败只给「回工作台」，429 给带 60s 冷却的重试，别的给直连重试
    // @param {boolean} [resetView] 筛选条件变了要立刻退回加载态，让旧列表让位（否则会被读成新条件的结果）；
    //                              onShow 的静默重拉不传，已有列表时保持可见，避免每次回页闪一次白卡
    async loadDishes(resetView) {
      // 并发守卫：onShow 重拉 + tab 连点 + 错误卡重试可叠加，慢失败不能盖掉快成功
      if (this._loadingList) {
        // 在途期间筛选又变了：不能吞掉这一次（否则 tab 已切、列表还是上一批），排队等当前请求收尾后按最新条件重拉
        this._reloadQueued = true
        return
      }
      this._loadingList = true
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getMerchantDishes({ tab: this.tab, category: this.category, keyword: this.appliedKeyword })
        this.dishes = (res && Array.isArray(res.data)) ? res.data : []
        // 每次成功都清失败标记：图换成功了还能继续显示图，不必刷新页面才恢复
        this.imgFail = {}
        this.errorText = ''
        this.errorCode = 0
        // 成功即解除冷却：限流窗口过去后「点击重试」重新可用，不必干等满 60s
        this.stopRetryLock()
        this.viewState = 'ready'
      } catch (e) {
        if (e && e.code === 401) {
          // 跳转与清态都由请求层做完（含「请先登录」toast），这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = Number((e && e.code) || 0)
        // 429（USER 30 次/分）：重试按钮给秒级冷却，冷却期内点不动 —— 否则用户每点一次都在续这个限流桶，
        // 列表↔编辑来回几次就把后面 1 分钟的全部分额度花光
        if (this.errorCode === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loadingList = false
        if (this._reloadQueued) {
          this._reloadQueued = false
          this.loadDishes(true)
        }
      }
    },
    // ==================== 筛选交互 ====================
    // 一套真值纪律：keyword 是输入框实时值、appliedKeyword 是「即将生效的查询值」，
    // 任何会重新拉列表的入口（搜索/清空/切 tab/换类目）都必须先把 keyword 提交进 appliedKeyword 再发请求。
    // 否则会出现「框里显示着关键字、请求却按无关键字发」——同一屏上两套筛选状态互相矛盾（FEA-1）
    commitKeyword() {
      this.appliedKeyword = (this.keyword || '').trim()
    },
    // ==================== 429 冷却闸门覆盖全部出口（X-4）====================
    // 改前闸门只拦住错误卡上的那颗「点击重试」（模板 :69 的 !retryLock），筛选区与 onShow 全部旁路：
    // 屏上写着「2s后可重试」，商家在上方点一下「在售」tab 就立刻实发一次、再吃 429、倒计时被覆写回满值，
    // 他看到的是「这 2 秒永远数不完」。F-23 把闸门值从 60 秒降到 1~2 秒后，原先靠 60 秒兜住旁路的余量没了，
    // 所以现在必须让这张表覆盖所有出口。
    // 纪律：①不新增第二个闸门状态，判据读的就是既有 retryLock（它由 startRetryLock 从后端
    //   data.retryAfterSeconds 换算而来，页内无数值来源）；②调用点必须放在**任何筛选值被改动之前**，
    //   拦下时 tab/类目/关键字一格没动，屏上选中的条件与列表才是同一份真值；③不静默，给带秒数的反馈。
    // 返回 true = 本次触发已被拦下，调用方直接 return。
    retryCoolingBlocked() {
      if (this.retryLock <= 0) return false
      // 文案点名「商品列表」这枚锁：本页屏上同时存在类目字典的**另一把独立闸门**（catRetryLock，
      // 其提示行 categoriesHint 与 retryCategories 的 toast 说的都是「类目请求太频繁」），不带归因的「太频繁」会让商家以为换类目也被锁着
      // （X-5 定稿判据：每处文案必须能看出它在报哪把锁）
      uni.showToast({ title: coolingFullText('商品列表', this.retryLock), icon: 'none' })
      return true
    },
    applyFilters() {
      if (this.retryCoolingBlocked()) return
      this.commitKeyword()
      this.loadDishes(true)
    },
    clearKeyword() {
      if (this.retryCoolingBlocked()) return
      this.keyword = ''
      this.appliedKeyword = ''
      this.loadDishes(true)
    },
    switchTab(key) {
      if (this.tab === key) return
      // 同一枚 tab 的重复点击在上面就早退了（它不改变任何条件、本来也不发请求），闸门只判真的要换态的点击
      if (this.retryCoolingBlocked()) return
      this.commitKeyword()
      this.tab = key
      this.loadDishes(true)
    },
    onCategoryChange(e) {
      if (this.retryCoolingBlocked()) return
      this.commitKeyword()
      const idx = Number(e && e.detail && e.detail.value)
      this.categoryIndex = Number.isFinite(idx) ? idx : 0
      this.loadDishes(true)
    },
    resetFilters() {
      // 这里拦下不吞操作：清筛后屏上会显示「无筛选」而列表仍是上一批（两套真值），
      // 而商家等的只是 1~2 秒；闸门过去后同一颗按钮照常可用，`.picker-value`/`tab` 的显示值都绑本页
      // state，未被改动即保持原样，不会出现「下拉框显示新类目、列表是旧条件」的错位
      if (this.retryCoolingBlocked()) return
      this.tab = 'all'
      this.categoryIndex = 0
      this.keyword = ''
      this.appliedKeyword = ''
      this.loadDishes(true)
    },
    // ==================== 429 冷却（照 login.vue 的 startLoginLock 那套秒级禁用范式） ====================
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
    // ==================== 渲染辅助 ====================
    // 商品图预览：入库值是 C 端静态根语义的 /static/images/uploads/<名>，商家端 origin 下取不到，
    // 归一化统一在 api 层 merchantFileUrl() 做（§3.1 唯一出口，页内不自拼 BASE_URL）
    previewUrl(u) {
      return merchantFileUrl(u)
    },
    onImgFail(id) {
      // 整体替换而不是 this.imgFail[id]=true：Vue3 下新增键能触发响应，但沿用 C 端 front/pages/index/index.vue 的 onImgFail 写法，
      // 两端同一姿势便于对照
      this.imgFail = { ...this.imgFail, [id]: true }
    },
    isOnSale(d) {
      return Number(d && d.onSale) === 1
    },
    categoryText(code) {
      const hit = this.categories.filter((c) => c.code === code)[0]
      // 字典里查不到的 code（脏值/历史分类）原样展示，不替后端猜中文名
      return hit ? hit.name : (code || '未分类')
    },
    // 价格两位小数（与 C 端 front/pages/index/index.vue 的 fmtPrice 同口径，同一份数据两端显示不一致会被当成数据问题）
    fmtPrice(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n.toFixed(2) : '0.00'
    },
    // ==================== 跳转 ====================
    goEdit(d) {
      uni.navigateTo({ url: '/pages/dish-edit/dish-edit?id=' + d.id })
    },
    // 回工作台：403/404 这类「重试不可能成功」的错误态的唯一出口（本页只能从工作台进来，
    // 回那里由工作台去判定角色/状态，比在本页挂一个永远吃同一枚错误的重试按钮有用）
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    goCreate() {
      // 写闸门预判：非 active 商家点新增必然在编辑页撞 403（长表单填完才报错最坑），这里当场拦住并指路
      if (this.writeBlocked) {
        uni.showToast({ title: '商家当前' + (this.statusText || '非正常营业') + '，暂不能新增商品', icon: 'none' })
        return
      }
      // 新增返回后要退回无筛选态（见 onShow）：新行按 orderByAsc(id) 落在最末，
      // 带着「已下架」tab 或关键字回去的话看不见新行、空态还会说「还没有商品」，诱导重复建同名 SKU
      this._resetFiltersOnReturn = true
      uni.navigateTo({ url: '/pages/dish-edit/dish-edit' })
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 滚轮接管的两道守卫（缺一条就是 bug，见各自注释）：
    // ① 栈顶守卫（FEA-5）：监听注册在 document 上，而 navigateTo 不触发上一页 onUnload ——
    //    进编辑页后本页 handler 仍挂着，两个 handler 同时驱动 body.scrollTop。实测编辑页单次
    //    deltaY=100 滚了 +223（对照组深链直达只有 +100），就是这一份叠加。
    // ② 弹层放行（FEA-6）：弹层打开时滚轮该归弹层，不该 preventDefault 后去滚背景。
    //    选择器集合在母版 front/pages/mine/mine.vue 的 onPageWheel 基础上，额外加本工程才有的
    //    .uni-picker-container —— 本页的类目下拉是 picker，它打开时背景被滚走等于「弹层里滚不动、页面却在滚」
    onPageWheel(e) {
      // getCurrentPages 在卸载竞态下可能拿不到栈（api 层 goLoginPage 同样做了守卫），拿不到就不接管滚动，
      // 绝不能在这里 preventDefault 把滚轮吞掉——那是「页面还活着但滚不动了」的成因
      let pages = []
      try {
        pages = getCurrentPages() || []
      } catch (err) {
        return
      }
      if (!pages.length || (pages[pages.length - 1] && pages[pages.length - 1].route) !== 'pages/dishes/dishes') return
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
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.empty-row/.btn-main/.guide-link/.cell-arrow 取自 common.css） */

/* ==================== 1. 筛选卡 ==================== */
.filter-card { margin-top: 24rpx; padding: 20rpx 24rpx; }
/* 商家状态行（照 workspace.vue 的 .status-tip 同一档字号/颜色：同一状态在两端必须是同一副样子） */
.status-tip { display: block; margin-bottom: 16rpx; font-size: 22rpx; color: #E02020; opacity: .8; line-height: 1.5; }
.search-row { display: flex; align-items: center; gap: 16rpx; }
.search-input {
  flex: 1; min-width: 0; height: 72rpx; line-height: 72rpx;
  background-color: #F5F5F5; border-radius: 999rpx;
  padding: 0 24rpx; font-size: 26rpx; color: #333333;
}
.search-go { flex-shrink: 0; font-size: 26rpx; font-weight: 600; color: #E02020; padding: 8rpx 4rpx; }
.search-clear { flex-shrink: 0; font-size: 24rpx; color: #BBBBBB; padding: 8rpx 4rpx; }
/* 行内新增按钮（取代常驻 fab）：灰度/禁用态照 dish-edit.vue 的 .btn-sub/.btn-sub-disabled 同一套写法，
   两处「新增/次级动作」的观感不能各长一个样；.btn-sub-disabled 只做视觉，
   拦截与告知在 goCreate 里（原生 disabled 会把点击整个吞掉，商家就看不到「为什么点不动」） */
.btn-sub {
  flex-shrink: 0; margin: 0; padding: 0 28rpx;
  height: 64rpx; line-height: 64rpx;
  background-color: #FFFFFF; color: #E02020;
  font-size: 26rpx; font-weight: 600;
  border: 1rpx solid #E02020; border-radius: 999rpx;
}
.btn-sub.btn-sub-disabled { color: #F5B5B0; border-color: #F5B5B0; background-color: #FFFFFF; }

/* 三 tab（照用户端 my-coupons.vue:288 .coupon-tabs 手感，激活态用商家端主红） */
.dish-tabs { display: flex; gap: 12rpx; margin-top: 20rpx; }
.dish-tab {
  flex: 1; text-align: center;
  font-size: 26rpx; color: #666666;
  background-color: #F5F5F5; border-radius: 24rpx;
  padding: 12rpx 0; border: 1rpx solid #EEEEEE;
}
.dish-tab.active { color: #FFFFFF; font-weight: 700; background-color: #E02020; border-color: #E02020; }

/* 类目下拉行（picker 是原生组件，整行可点，右侧 › 复用 common.css .cell-arrow） */
/* picker 显式占满整行：H5 下它编译成自定义元素，不声明块级的话整行点击区只剩文字那么宽 */
.cate-picker { display: block; width: 100%; }
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
/* 字典拉取失败的降级行（本页筛选退化不影响列表，故只提示不给整页错误态，可点重试） */
.cate-fail { display: block; margin-top: 12rpx; font-size: 22rpx; color: #E02020; line-height: 1.5; }

/* ==================== 2. 加载中 ==================== */
.loading-card { margin-top: 24rpx; display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }

/* ==================== 3. 错误态（两层式：人话主文案 + 后端原文小字） ==================== */
/* .error-row/.error-text/.error-retry 基类在 common.css；本页只补两个差异件 */
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
/* 后端原文降为第二行：照 workspace.vue 的 .guide-tip 同一档（小字、半透明红），排查时看得见但不顶替人话 */
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
/* 429 冷却中的重试：同一枚按钮的形状，置灰 + 不可点（点击已在模板里按 retryLock 分流） */
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; }

/* ==================== 4. 商品行 ==================== */
.list-card { margin-top: 24rpx; padding: 0; overflow: hidden; }
.dish-item { display: flex; align-items: center; gap: 16rpx; padding: 24rpx; border-bottom: 1rpx solid #F5F5F5; }
.dish-item:last-child { border-bottom: none; }
.dish-img {
  width: 140rpx; height: 140rpx; flex-shrink: 0;
  border-radius: 12rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.dish-photo { width: 140rpx; height: 140rpx; }
.dish-emoji { font-size: 64rpx; line-height: 1; }
.dish-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.dish-title-row { display: flex; align-items: center; gap: 12rpx; }
.dish-name {
  flex: 1; min-width: 0;
  font-size: 30rpx; font-weight: 600; color: #333333; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.dish-badge {
  flex-shrink: 0; font-size: 20rpx; font-weight: 600; line-height: 1.4;
  border-radius: 8rpx; padding: 4rpx 12rpx;
}
.badge-on { color: #FFFFFF; background-color: #E02020; }
.badge-off { color: #999999; background-color: #F5F5F5; }
.dish-meta { font-size: 22rpx; color: #999999; line-height: 1.5; }
.dish-price-row { display: flex; align-items: baseline; }
.price-symbol { font-size: 22rpx; font-weight: 700; color: #E02020; }
.price-num { font-size: 34rpx; font-weight: 700; color: #E02020; line-height: 1; }
.price-unit { font-size: 22rpx; color: #999999; margin-left: 4rpx; }

/* ==================== 4. 空态卡 ==================== */
.empty-card { margin-top: 24rpx; display: flex; flex-direction: column; align-items: center; }
.empty-card .btn-main { margin-top: 8rpx; }

/* 底部安全区占位（common.css 的 .page-bottom-space 只有 safe-area 高度，本页列表末尾再留一段呼吸位，
   让最后一行的 › 箭头不被手势条压住）——原先这里是给常驻 fab 让位，fab 已删（见筛选卡行内新增按钮） */
.page-bottom-space { height: 60rpx; }
</style>
