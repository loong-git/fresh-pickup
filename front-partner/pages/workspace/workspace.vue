<template>
  <view class="page-container">
    <!-- ==================== 1. 加载中（首屏无数据时的占位，避免空白页） ==================== -->
    <view v-if="viewState === 'loading'" class="card checking-card">
      <text class="checking-text">正在加载门店资料…</text>
    </view>

    <!-- ==================== 2. 加载失败（区别于「名下无店」的 empty：这一态是请求没成功，重试有可能成）
             出口按「重试有没有可能成功」给：403/401 不会走到这里（各自另有去向，见 script 注释），
             剩下的都是「等一会儿或查网络就能成」，故给重试；429 起秒级冷却，冷却期内只显秒数 ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="!retryLock" class="error-retry" @click="load(true)">点击重试</text>
        <text v-else class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text class="guide-link" @click="switchAccount">换个账号</text>
      </view>
    </view>

    <!-- ==================== 3. 空态：名下还没有可用的自提点
             两个来源都落这里（见 script）：① GET /api/partner/stores 回 403——token 有效但
             store.owner_user_id 归属集合为空（AuthInterceptor:108-118，R.CODE_FORBIDDEN）；
             ② 200 但数组为空（后端走不到，纯防契约漂移的兜底闸，绝不因此放行渲染工作台内容）。
             这不是错误态：重试永远失败（店不会自己长出来），故给的两条去向是「申请入驻」与「换个账号」，
             另留一条「重新检查」——平台刚把门店绑定到这个手机号时，点它只重发一次请求，不必重收验证码。 ==================== -->
    <view v-else-if="viewState === 'empty'" class="card guide-card">
      <text class="guide-title">名下还没有可用的自提点</text>
      <text class="guide-text">当前账号已登录，但没有一家自提点绑定到它，工作台没有可操作的数据</text>
      <text v-if="emptyMessage" class="guide-tip">{{ emptyMessage }}</text>
      <button class="btn-main" @click="goStoreApply"><text>去申请入驻</text></button>
      <text v-if="!retryLock" class="guide-link" @click="load(true)">我已绑定，重新检查</text>
      <text v-else class="guide-link guide-link-cooling">{{ retryLock }}s后可重新检查</text>
      <text class="guide-link guide-link-plain" @click="switchAccount">换个账号</text>
    </view>

    <!-- ==================== 4. 就绪：身份卡 + 登出/换账号出口 + 入口列表 ==================== -->
    <template v-else>
      <view class="card store-card">
        <view class="store-head">
          <text class="store-name">{{ storeName }}</text>
          <!-- 多店主理人：本页取列表第一条展示，并把「共 N 家店」如实标出来。
               一期不做门店切换控件，但把「本页正在展示的这家」的 id 透传给门店信息页（见 onEntryTap），
               否则 store 页会退回后端缺省「名下 id 最小的一家」（PartnerStoreServiceImpl.java:360-369），
               多店时两页显示的很可能不是同一家。 ==================== -->
          <text v-if="multiStoreText" class="count-tag">{{ multiStoreText }}</text>
        </view>
        <view class="store-rows">
          <text class="store-row">地址：{{ storeAddress }}</text>
          <!-- 营业时间/接单开关/日接单量来自 GET /api/partner/store 明细（六新列，F-18.12 第 4 条），
               列表接口不下发这些（D-12 最小暴露）；明细没回来就如实写「未返回」，不猜一个值 -->
          <text class="store-row">营业时间：{{ openTimeText }}</text>
          <text class="store-row">接单开关：{{ acceptingText }}</text>
          <text class="store-row">日接单量：{{ capacityText }}</text>
          <!-- 门店生命周期只读透出（值域 open/closed，见 init.sql store 种子与 Store.java:17 注）：
               非正常态才出一行提示，脏值原样展示、不替后端猜中文说法；本页不做任何跳转拦截 -->
          <text v-if="statusTip" class="status-tip">{{ statusTip }}</text>
        </view>
        <!-- 每次 onShow 重拉的成功时刻（门店资料改了、刚被绑定新店后回页即可见，验收时用它辨认刷新确实发生） -->
        <text v-if="refreshText" class="refresh-tip">资料刷新于 {{ refreshText }}</text>
        <!-- 会话内已有列表快照时，重拉失败降级为卡底这一行，不把整页换成错误卡（母版 front-merchant/pages/workspace/workspace.vue:49 同款） -->
        <text v-if="refreshFailed && !retryLock" class="refresh-fail" @click="load(true)">资料刷新失败，点击重试</text>
        <text v-else-if="refreshFailed" class="refresh-fail refresh-fail-cooling">资料刷新失败，{{ retryLock }}s后可重试</text>
      </view>

      <!-- 正常态唯一登出/换账号出口：走 confirmSwitchAccount 带二次确认（误触代价=重新登录并重收验证码） -->
      <view class="card cell-card">
        <view class="cell" @click="confirmSwitchAccount">
          <text class="cell-logout">换个账号 / 退出登录</text>
        </view>
      </view>

      <!-- 入口逐个替换：四行都已在 ENTRY_ROUTES 在册（F-18.19 交付 store / apply 两页），
           统一挂 › 箭头并保持可点。可点与否的唯一真源仍是 ENTRY_ROUTES（模块级常量表），
           模板的置灰判定（entryRoute）与点击分支（onEntryTap）两处消费它，页面不持第二个开关字段。 ==================== -->
      <view class="card cell-card">
        <view
          class="cell"
          :class="{ 'cell-disabled': !entryRoute(entry) }"
          v-for="entry in entries"
          :key="entry.key"
          @click="onEntryTap(entry)"
        >
          <text class="cell-icon">{{ entry.icon }}</text>
          <text class="cell-title">{{ entry.title }}</text>
          <text class="cell-sub">{{ entry.hint }}</text>
          <!-- 在册行给箭头；表里没登记路由的行（当前没有）不画箭头，点了给一句实话，不留静默死行 -->
          <text v-if="entryRoute(entry)" class="cell-arrow">›</text>
        </view>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 主理人工作台（F-18.15 §4）：拷自 front-merchant/pages/workspace/workspace.vue 并按本轮数据源改造。
// 与母版的三点实质差异：
// ① 数据源：母版只有一次商家端那个 me 端点；本页是「列表 + 明细」两次——
//    GET /api/partner/stores（F-18.8 第 4 条，给 id/name/address/status/service）与
//    GET /api/partner/store（F-18.12 第 4 条，白名单 Map，含 openTime/closeTime/dailyCapacity/
//    accepting/contactPhone/photos 六新列）。列表定「能不能进」、明细填「这家店什么样」。
// ② 状态位：母版是 loading|ready|forbidden|error，本页按 §4 收敛为 loading|error|empty|ready 四态互斥
//    （单值 viewState，不叠布尔）。母版的 forbidden 在这里就是 empty——名下无店是「没有数据」而不是
//    「权限报错」，重试永远失败，所以它给的不是重试按钮而是申请入驻/换账号两条去向。
// ③ 入口：可点与否由 ENTRY_ROUTES 唯一裁决（F-18.17 放开核销台/到货批次两行，F-18.19 放开门店信息与
//    入驻申请两行——四行全部在册，未交付占位分支随之退役）。
// 401 不在本页处理（请求层 rejectUnauthorized 已清 partner_token 并 reLaunch 回登录页）；
// 403 也不当登录失效（handleEnvelope 只 reject 不跳），本页把它归到 empty 态渲染引导内容。
// 渲染不依赖 store：本页自持一份服务端最新值（母版同款纪律）。
import { getPartnerStores, getPartnerStore, retrySecondsOf } from '@/api/index.js'
import '@/styles/common.css'

// 入口 key -> 路由表（先例：front-merchant/pages/workspace/workspace.vue:99。模块级 const 表，
// 模板的置灰判定（entryRoute）与点击分支（onEntryTap）两处消费它，页面不持第二个开关字段）。
// F-18.17 §1.4 放开 verify / arrivals 两行，F-18.19 §1 放开 store / apply 两行：
// 四条路径与 pages.json 的注册逐字一致（表里写一条没注册的路由，H5 下就是白屏死链）。
const ENTRY_ROUTES = {
  verify: '/pages/verify/verify',
  arrivals: '/pages/arrival/arrival',
  store: '/pages/store/store',
  apply: '/pages/apply/apply'
}

// 门店生命周期状态正常值（init.sql store.status：种子 3 家 open + 1 家 closed，Store.java:17 注）：
// 只有它不等于该值时才出一行提示，脏值原样展示不替后端猜中文
const STORE_STATUS_OPEN = 'open'

// F-23.2（补 F-23 漏网的第三处同 pattern）：429 冷却时长不再硬编码 60——后端 429 信封的
// data.retryAfterSeconds 优先，取不到才回落本地 60。
// 回落值口径：它是本页所撞限流档（GET /api/partner/stores 与 GET /api/partner/store 都是 USER 维度
// @RateLimit 30 次/分）的整窗长度，是「后端不给 data 时的保守上限」，不是回速（回速实测 2~12 秒，
// 远小于窗口），两个数不是一回事，别混成一个值。
const RETRY_FALLBACK_SECONDS = 60

// retrySecondsOf 已收敛到 api 层（F-25 路 1）：front-partner 内唯一实现是 front-partner/api/index.js:214-224，
// 本页这份私有的一参版是第 6 份、也是最后一份：F-23.2 起锁时加进来，F-25 路 1 又随 apply/arrival/login/store/verify 那 5 份一起删除（净 diff 里一加一删相消，别据此断言「本页从来没有私有版」）。
// （商家端更早就收敛了：front-merchant/api/index.js 导出同名两参版 retrySecondsOf——两端形态现在一致。
//   这里刻意不写商家端那一行的行号：F-25 路 2 正在改那个文件，任何指向它内部的 file:line 都会被同批加行打断。）
// 载荷来源：请求层 handleEnvelope 的 reject 带 data（front-partner/api/index.js:183-186 早已透传），
// 网络异常/信封不可解析时 data 为 null，走上面的本地回落。

export default {
  data() {
    return {
      // 页面四态互斥（避免多面布尔标志互相打架留脏态）：loading | error | empty | ready
      viewState: 'loading',
      // GET /api/partner/stores 的列表快照（多店时展示第一条并标数量；本页唯一「能不能渲染工作台」的依据）
      stores: [],
      // GET /api/partner/store 的明细快照（六新列只在它里面）
      store: null,
      // error 态文案（后端 message 原文，无则兜底话术）与错误码（决定主文案是「点太快」还是「加载失败」）
      errorText: '',
      errorCode: 0,
      // empty 态透出后端原文（403 的 R.MSG_FORBIDDEN 是「无权限访问」，人话主文案由本页给，原文降为第二行小字）
      emptyMessage: '',
      // 本次成功刷新时刻（ms），仅驱动「资料刷新于」一行
      refreshAt: 0,
      // 已有列表快照时重拉失败（含明细那一次失败）的降级标记：卡片照常展示，只在卡底追加一行重试提示
      refreshFailed: false,
      // 429 冷却剩余秒数（两条 GET 同档 USER 30 次/分、但各是一枚独立桶——桶身份取「控制器类名 + 方法名」，
      // 见 RateLimitInterceptor#bucketKey；页内共用这一张冷却表是刻意的保守，不是说它们花同一份额度；
      // 起锁秒数不再硬编码：转述后端 429 信封的 data.retryAfterSeconds，取不到才回落 RETRY_FALLBACK_SECONDS；
      // 落点与写法照 dishes.vue:187-188（data）/ :417（errorCode===429 才起锁）/ :464（startRetryLock）。
      // F-25/P-4：原指针 177-178/385/432 已被商家端加行打断，上面三个是按 dishes.vue 现况逐条重测的读数）
      retryLock: 0,
      retryLockTimer: null,
      // 四入口（静态列表放 data，与母版 entries 同款）：可点与否由 ENTRY_ROUTES[entry.key] 决定，
      // 四行全部在册（F-18.19 补齐 store / apply），hint 里不再挂「某 Step 交付」的占位话术
      entries: [
        { key: 'verify', icon: '🧾', title: '核销台', hint: '按取货码核销' },
        { key: 'arrivals', icon: '📦', title: '到货批次', hint: '批次清单与确认' },
        { key: 'store', icon: '🏪', title: '门店信息', hint: '营业时间/接单开关' },
        { key: 'apply', icon: '📝', title: '我的入驻申请', hint: '审核进度与驳回原因' }
      ]
    }
  },
  computed: {
    // 当前展示的门店（多店取列表第一条，D-22 选择器的缺省口径与后端 resolveStoreId 一致）
    currentStore() {
      return this.stores.length ? this.stores[0] : null
    },
    storeName() {
      const d = this.store && this.store.name
      const s = this.currentStore && this.currentStore.name
      return d || s || '未命名自提点'
    },
    storeAddress() {
      const d = this.store && this.store.address
      const s = this.currentStore && this.currentStore.address
      return d || s || '未填写'
    },
    multiStoreText() {
      return this.stores.length > 1 ? '共 ' + this.stores.length + ' 家店' : ''
    },
    // 营业时间：后端是两列 HH:MM 字符串（openTime/closeTime，可 NULL）。
    // 任一为空就整行如实写「未设置」——不拿单边值拼出 '08:00-' 这种半截话
    openTimeText() {
      const d = this.store || {}
      if (!d.openTime || !d.closeTime) return d.openTime === undefined ? '未返回' : '未设置'
      return d.openTime + '-' + d.closeTime
    },
    // accepting：TINYINT 0/1（不是 boolean，与后端 DTO 同口径）。未知值/缺值不猜
    acceptingText() {
      const d = this.store || {}
      if (d.accepting === undefined) return '未返回'
      if (d.accepting === 1 || d.accepting === '1') return '已开启（可接单）'
      if (d.accepting === 0 || d.accepting === '0') return '已关闭（暂不接单）'
      return '未设置'
    },
    capacityText() {
      const d = this.store || {}
      // undefined = 明细没回来（不猜）；null = 后端明确回「未设置」
      if (d.dailyCapacity === undefined) return '未返回'
      if (d.dailyCapacity === null) return '未设置'
      return d.dailyCapacity + ' 单/日'
    },
    // 门店生命周期提示行：open 不出行；其余（含 closed 与脏值）原样透出，不替后端翻译
    statusTip() {
      const fromDetail = this.store && this.store.status
      const fromList = this.currentStore && this.currentStore.status
      const s = fromDetail || fromList || ''
      if (!s || s === STORE_STATUS_OPEN) return ''
      return '自提点当前状态：' + s
    },
    refreshText() {
      return this.refreshAt ? this.formatClock(this.refreshAt) : ''
    },
    // 错误态主文案（两层式照母版与 dishes.vue:228-233 的 errorLeadText 口径：主文案是人话，后端原文降为第二行小字）
    // F-23-fix/FF-1：429 这行不带时长，秒数只在闸门那一处（本页模板的 retryLock 倒计时行）出现一次；
    // FF-6 + F-25/P-4：这条指针历史 209-214 → 216-221 → 218-223（每次都只隔着一两次加行），
    // 现值按 dishes.vue 实况重测＝228-233（computed errorLeadText 本体 228、四支 return 229-232、收尾 233）
    errorLeadText() {
      if (this.errorCode === 429) return '点得太快了，稍后再刷新'
      // X-2b（四路分流，母版 front-merchant/pages/dashboard/dashboard.vue 的同名 computed）：500 是服务端
      // 承认自己处理出错，同屏第二行透出的正是那句原文，第一行却叫主理人去查网络——因果方向相反
      if (this.errorCode === 500) return '门店资料加载失败，服务端处理出错，稍后重试'
      // 只有网络层失败才许出现「网络」字样：front-partner/api/index.js 的 request fail 支回 code:-1 /
      // message「网络异常」，本页 errorCode 因此是 -1；没带码的失败归 0，两支同一条出路
      if (this.errorCode <= 0) return '门店资料加载失败，请检查网络后重试'
      // 其余服务端业务码（400 一类）：不猜成因，报「加载失败」＋第二行透出后端 message 原文。
      // 403 走不到这里——它被 load() 归进 empty 态（名下归属集合为空，重试永远失败）
      return '门店资料加载失败，请稍后重试'
    }
  },
  onShow() {
    // 每次进入重拉（对齐母版 front-merchant/pages/workspace/workspace.vue:163 的 onShow 惯例，F-25/P-4 重测）：
    // 换绑门店、改了营业时间都能第一时间反映
    this.load()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  // 滚轮监听仅 H5 注册（上方 onReady #ifdef 内），非 H5 端 _pageWheelHandler 恒为 undefined，清理分支天然跳过
  onUnload() {
    // 定时器清理：429 冷却计时（页面销毁后仍在跑的话，回到本页会显示一个不属于本页的秒数）
    this.stopRetryLock()
    // #ifdef H5
    // 页面级滚轮兜底监听清理（与 onReady 注册配对）
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
  },
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // ==================== 数据加载：先列表、后明细 ====================
    // resetView=true 时（点重试 / 重新检查）先把视图切回 loading，让「这次真的重发了请求」可见；
    // 静默的 onShow 重拉保持当前视图，成功再覆盖，失败只追加一行降级提示（不把整页换成错误卡）。
    async load(resetView) {
      // 并发守卫：重试/重新检查/刷新区/每次 onShow 都可能撞上，同一时刻只放行一个请求序列，
      // 慢失败不能盖掉快成功（母版 front-merchant/pages/workspace/workspace.vue:191-193 同款，F-25/P-4 重测）
      if (this._loading) return
      // 冷却期内不重发：本页所有重试入口共用 retryLock 这一张表
      if (this.retryLock > 0) return
      this._loading = true
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getPartnerStores()
        const list = (res && Array.isArray(res.data)) ? res.data : []
        if (!list.length) {
          // 200 但空数组：后端归属集合为空时直接回 403，走不到这里；留这一道闸是为了
          // 「已有 token 却拿到空数据」绝不当成正常态放行（母版 front-merchant/pages/workspace/workspace.vue:201-202
          // 的「不放水」同口径，F-25/P-4 重测）
          this.showEmpty('名下没有可用的自提点')
          return
        }
        this.stores = list
        this.emptyMessage = ''
        this.refreshAt = Date.now()
        this.viewState = 'ready'
        // 明细失败不改四态：列表已经足够证明「这家店存在」，六新列读不到就在卡里如实写「未返回」
        await this.loadDetail(list[0].id)
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 401) {
          // 跳登录页与清态都由请求层做完（主理人端无游客态），这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        if (code === 403) {
          // 归属集合为空（AuthInterceptor:115-118）：不是登录失效、也不是「加载失败」，落 empty 给去向
          this.showEmpty((e && e.message) || '')
          return
        }
        // 会话内已有列表快照：保持 ready 展示，只降级卡底一行重试提示（偶发网络抖动不该把已看到的数据整页换掉）
        if (this.stores.length) {
          this.refreshFailed = true
          if (code === 429) this.startRetryLock(e)
          this.viewState = 'ready'
          return
        }
        // 首屏无快照才是整页错误态
        this.errorText = (e && e.message) || ''
        this.errorCode = code
        // 只有 429 才起冷却锁（照 dishes.vue:417 的判定：其余失败重试是有意义的，不该被秒数拦住）
        // 把 catch 到的错误对象交给 startRetryLock：冷却秒数由它转述后端 429 信封的真值，本页不再猜 60
        if (code === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loading = false
      }
    },
    // 明细：GET /api/partner/store?storeId=（storeId 传列表第一条，与 D-22 缺省口径一致）
    // 失败一律在本函数内消化（四态由列表那一次决定；明细只影响卡片里几行字与一行降级提示）
    async loadDetail(storeId) {
      try {
        const res = await getPartnerStore(storeId)
        const detail = (res && res.data && typeof res.data === 'object') ? res.data : null
        this.store = detail
        this.refreshFailed = false
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 401) return // 请求层已接管（回登录页），这里不追加任何提示
        this.refreshFailed = true
        if (code === 429) this.startRetryLock(e)
      }
    },
    // empty 态渲染：清掉可能残留的列表/明细快照，避免「已换账号/已解绑」还渲着上一家门店的名字
    showEmpty(message) {
      this.stores = []
      this.store = null
      this.refreshFailed = false
      this.emptyMessage = (typeof message === 'string' ? message : '').trim()
      this.viewState = 'empty'
    },
    // ==================== 交互 ====================
    // 入口可点与否的唯一判定处（模板置灰与点击分支共用，避免两处真值打架）
    entryRoute(entry) {
      return (entry && ENTRY_ROUTES[entry.key]) || ''
    },
    // 入口点击：全部在册（ENTRY_ROUTES 四行），走 navigateTo 过去。
    // store 这一行额外把「本页正在展示的这家」的 id 作为 D-22 选择器透传过去：多店时不带的话，
    // 门店信息页会退回后端缺省（名下 id 最小的一家），于是工作台显示 A 店、点进去编辑的是 B 店
    onEntryTap(entry) {
      let url = this.entryRoute(entry)
      if (!url) {
        // 防御分支，当前不可达（FE-13 裁定）：entries 与 ENTRY_ROUTES 同文件维护，四行现已全部在册
        // （verify/arrivals/store/apply），这一支走不到——留着是因为「在册表漏一行」属于编码疏漏而不是用户状态，
        // 真漏了也要给一句实话，不留「点了没反应」的死行（母版纪律）。不删代码、不再挂占位话术。
        uni.showToast({ title: '该功能暂未开放', icon: 'none', duration: 1500 })
        return
      }
      if (entry && entry.key === 'store' && this.currentStore && this.currentStore.id !== undefined) {
        url += '?storeId=' + encodeURIComponent(this.currentStore.id)
      }
      uni.navigateTo({ url })
    },
    // 「去申请入驻」：pages/apply/apply 已在 pages.json 注册（F-18.19），这里给真实跳转。
    // 申请页只要求登录（D-20 挂 /api/store-applies），所以从 empty 态进来必然可用——
    // 名下无店的账号正是这一页唯一的目标用户
    goStoreApply() {
      uni.navigateTo({ url: '/pages/apply/apply' })
    },
    // 登出/换账号的执行体：清 partner_token 后回登录页换账号
    switchAccount() {
      this.$store.commit('clearToken')
      uni.reLaunch({ url: '/pages/login/login' })
    },
    // 正常态「换个账号 / 退出登录」入口的二次确认：误触代价=重新登录并重收一次验证码，故只在这一路包一层 modal；
    // empty/error 态那两个出口是用户主动求助路径、本就进不去，无误触代价，不加确认（否则自救路径被拖啰嗦）
    confirmSwitchAccount() {
      uni.showModal({
        title: '换个账号',
        content: '将退出当前登录的主理人账号，需重新用验证码登录。确定换账号？',
        confirmText: '退出',
        confirmColor: '#E02020',
        success: (res) => {
          if (!res.confirm) return
          this.switchAccount()
        }
      })
    },
    // ==================== 429 冷却（照 dishes.vue:464 startRetryLock 的写法 + 同端母版 login.vue:283） ====================
    // F-23.2：入参改为 catch 到的错误对象，起锁秒数由 retrySecondsOf 转述后端 429 信封的
    // data.retryAfterSeconds，取不到才回落本地窗口长度（RETRY_FALLBACK_SECONDS，F-25 路 1 起该函数在 api 层）。
    // 闸门语义一行没削弱：retryLock 仍是「冷却期内不重发」的唯一依据
    // （load() 首行的 retryLock > 0 早退 + 模板三处 !retryLock 判定均未动）。
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
    // 时间戳 -> HH:mm:ss（脏值返回空串，模板随之隐藏这一行）
    formatClock(ms) {
      const d = new Date(ms)
      if (!Number.isFinite(d.getTime())) return ''
      const p = (n) => String(n).padStart(2, '0')
      return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    onPageWheel(e) {
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* 多多红单色板：灰底页面 + 白卡（.card/.cell-card/.guide-card/.btn-main/.error-row 等公共类在 styles/common.css，
   本页只补间距与本页专属的字，不动主题变量） */

/* ==================== 1/2/3. 单卡承载的三态（本页自加间距，公共类不带 margin） ==================== */
.guide-card { margin-top: 56rpx; }
.checking-card { margin-top: 56rpx; display: flex; justify-content: center; }
.checking-text { font-size: 26rpx; color: #999999; line-height: 1.6; }
.guide-card .btn-main { margin-top: 16rpx; }
/* 后端原文透出行：小字主红，与本页自撰文案区分，避免用户以为原因是前端编的 */
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
.error-lead { font-size: 26rpx; color: #666666; line-height: 1.6; text-align: center; }
/* 429 冷却中的重试：同一枚按钮的形状，置灰 + 不可点（点击已在模板里按 retryLock 分流） */
.error-retry-cooling { color: #CCCCCC; }
.guide-link-cooling { color: #CCCCCC; }
.guide-link-plain { color: #999999; }

/* ==================== 4. 身份卡 ==================== */
.store-card { margin-top: 24rpx; }
.store-head { display: flex; align-items: baseline; gap: 12rpx; }
/* 门店名：后端锁定平台维护（本轮不可改），只读展示，长名两行截断 */
.store-name {
  flex: 1; min-width: 0;
  font-size: 34rpx; font-weight: 700; color: #333333; line-height: 1.4;
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2;
  overflow: hidden;
}
/* 「共 N 家店」角标：中性灰底，不与状态类徽标抢色（它不是状态，是数量事实） */
.count-tag {
  flex-shrink: 0;
  font-size: 22rpx; color: #666666; background-color: #F5F5F5;
  border-radius: 8rpx; padding: 4rpx 12rpx; line-height: 1.4;
}
.store-rows { display: flex; flex-direction: column; gap: 8rpx; margin-top: 20rpx; }
.store-row { font-size: 24rpx; color: #666666; line-height: 1.6; }
.refresh-tip { display: block; margin-top: 20rpx; font-size: 20rpx; color: #CCCCCC; line-height: 1.4; }
/* 非 open 生命周期的提示行：主红小字（色板内既有值），只读展示不拦截 */
.status-tip { font-size: 22rpx; color: #E02020; opacity: .8; line-height: 1.5; }
/* 有快照时重拉失败的降级重试行：与刷新时间行同位呼应，可点 */
.refresh-fail { display: block; margin-top: 8rpx; font-size: 22rpx; color: #E02020; line-height: 1.5; }
.refresh-fail-cooling { color: #CCCCCC; }

/* ==================== 4.5 登出/换账号行（.cell 体系取自 common.css，仅本页补居中红色字） ==================== */
.cell-logout { flex: 1; text-align: center; font-size: 28rpx; font-weight: 600; color: #E02020; }

/* ==================== 5. 四入口列表（.cell/.cell-disabled 均取自 common.css；
   .tag-building 占位角标已随 F-18.19 交付 store/apply 退役，本页不再引用它） ==================== */
.cell-card { margin-top: 24rpx; }
</style>
