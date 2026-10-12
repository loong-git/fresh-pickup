<template>
  <view class="page-container">
    <!-- ==================== 1. 加载中（首屏占位，避免整页空白） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载门店资料…</text>
    </view>

    <!-- ==================== 2. 加载失败（≠ 空态：这一态是请求没成功，等一会儿或查网络就能成）
             401 不会走到这里（请求层 rejectUnauthorized 已清态并回登录页）；
             403/404 落空态而不是错误态（见 script 的 load），因为「重试永远失败」；
             429 起 retryLock 秒级冷却，冷却期内只显秒数（口径同 Step 6 的 verify/workspace）。 ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="!retryLock" class="error-retry" @click="load(true)">点击重试</text>
        <text v-else class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 3. 空态：名下读不到可维护的门店（403 归属集合为空 / 404 行已不在）
             这不是「加载失败」，给的去向是申请入驻与回工作台，不是重试；
             仍留一条「重新检查」——平台刚把门店绑到本手机号时点它只重发一次请求，不必重收验证码 ==================== -->
    <view v-else-if="viewState === 'empty'" class="card guide-card">
      <text class="guide-title">还没有可维护的门店资料</text>
      <text class="guide-text">当前账号名下读不到门店明细：可能是入驻申请还没通过审核，或门店绑定已变更</text>
      <text v-if="emptyMessage" class="guide-tip">{{ emptyMessage }}</text>
      <button class="btn-main" @click="goApply"><text>去提交入驻申请</text></button>
      <text v-if="!retryLock" class="guide-link" @click="load(true)">已开通，重新检查</text>
      <text v-else class="guide-link guide-link-cooling">{{ retryLock }}s后可重新检查</text>
      <text class="guide-link guide-link-plain" @click="goWorkspace">回工作台</text>
    </view>

    <!-- ==================== 4. 就绪：合规提示 → 只读身份区 → 七项维护表单 → 保存 ==================== -->
    <template v-else>
      <!-- RE 红线的前端自证（F-18.19 §2 点名必须有这一句）：本页改的 openTime/closeTime 只是展示字段，
           截单时刻由服务端 23:00 裁决（utils/time.js 对齐链不动），改这里不会改变任何下单语义 -->
      <view class="card notice-card">
        <text class="notice-text">营业时间仅作展示，不改变 23:00 全局截单规则（截单由服务端裁决，本页不参与）</text>
      </view>

      <!-- 只读区（D-7：name/address/lng/lat/status 一律不可改，后端 @JsonIgnoreProperties 会把它们丢掉，
           前端给出口就等于「用户以为改了」的静默假成功，故这里整块灰显并且没有输入控件） -->
      <view class="card ro-card">
        <text class="block-title">门店身份（只读）</text>
        <view class="ro-row">
          <text class="ro-label">店名</text>
          <text class="ro-value">{{ textOr(store.name, '未返回') }}</text>
        </view>
        <view class="ro-row">
          <text class="ro-label">地址</text>
          <text class="ro-value">{{ textOr(store.address, '未返回') }}</text>
        </view>
        <view class="ro-row">
          <text class="ro-label">坐标</text>
          <text class="ro-value">{{ coordText }}</text>
        </view>
        <view class="ro-row">
          <text class="ro-label">平台状态</text>
          <text class="ro-value">{{ statusText }}</text>
        </view>
        <text class="ro-note">店名、地址与坐标由平台维护；如需变更请提交新的入驻申请或联系平台</text>
      </view>

      <!-- ==================== 4.1 可维护表单（D-7 白名单七项里本页给输入的六项） ==================== -->
      <view class="card form-card">
        <text class="block-title">门店信息维护</text>

        <!-- 营业时间两栏用 picker mode="time"（先例 plan-orders.vue:20 的 mode="date"）：
             H5 编译成 uni 的时间弹层、小程序端是原生 picker，两端都有值形状 HH:MM，
             因此不存在「手打出一个 8:0 或 25:99」的中间态；提交前仍按后端同一条正则再预检一次（零成本） -->
        <picker class="row-picker" mode="time" :value="form.openTime || '08:00'" @change="onOpenTimeChange">
          <view class="field-row st-field-openTime" :class="{ 'field-error': errorKey === 'openTime' }">
            <text class="field-label">开始营业</text>
            <text class="field-picker" :class="{ 'is-empty': !form.openTime }">{{ form.openTime || '未设置，点选时间' }}</text>
            <text class="cell-arrow">›</text>
          </view>
        </picker>
        <picker class="row-picker" mode="time" :value="form.closeTime || '20:00'" @change="onCloseTimeChange">
          <view class="field-row st-field-closeTime" :class="{ 'field-error': errorKey === 'closeTime' }">
            <text class="field-label">结束营业</text>
            <text class="field-picker" :class="{ 'is-empty': !form.closeTime }">{{ form.closeTime || '未设置，点选时间' }}</text>
            <text class="cell-arrow">›</text>
          </view>
        </picker>
        <text class="field-hint">仅展示给消费者看，不参与 23:00 截单；一旦点选就会写入（本页不提供「清空该列」，见下方保存说明）</text>

        <view class="field-row st-field-dailyCapacity" :class="{ 'field-error': errorKey === 'dailyCapacity' }">
          <text class="field-label">日接单量</text>
          <input
            class="field-input"
            type="number"
            maxlength="5"
            :value="form.dailyCapacity"
            placeholder="留空表示不修改"
            placeholder-style="color:#BBBBBB;"
            @input="onCapacityInput"
          />
          <text class="field-suffix">单/日</text>
        </view>
        <!-- 诚实文案（F-18.19 §2 点名）：一期没有「清空回不限容量」的入参形态（dailyCapacity 只有
             null=不改这一支，后端没有 blank 分支，见 PartnerStoreServiceImpl.java:163-169），
             所以这里绝不说「留空即取消限制」——那是把人骗进一次无效的保存 -->
        <text class="field-hint">填了才改、留空表示不修改；要取消容量限制一期请联系平台（S4b-F2 已登记该缺口）</text>

        <view class="field-row st-field-accepting">
          <text class="field-label">接单开关</text>
          <text class="switch-text">{{ acceptingLabel }}</text>
          <switch class="accepting-switch" :checked="form.accepting === 1" color="#E02020" @change="onAcceptingChange" />
        </view>
        <text class="field-hint">关闭＝临时满容/休市，与上面「平台状态」（open/closed，由平台维护）是两件正交的事</text>

        <view class="field-row st-field-contactPhone" :class="{ 'field-error': errorKey === 'contactPhone' }">
          <text class="field-label">联系电话</text>
          <input
            class="field-input"
            type="number"
            maxlength="11"
            :value="form.contactPhone"
            :placeholder="phonePlaceholder"
            placeholder-style="color:#BBBBBB;"
            @input="onPhoneInput"
          />
        </view>
        <text class="field-hint">{{ phoneHint }}</text>

        <view class="field-row field-row-col st-field-service" :class="{ 'field-error': errorKey === 'service' }">
          <text class="field-label">服务说明</text>
          <textarea
            class="field-textarea"
            :value="form.service"
            maxlength="50"
            placeholder="如 支持冷冻/冷藏自提，50 字以内"
            placeholder-style="color:#BBBBBB;"
            @input="onServiceInput"
          />
          <text class="field-count">{{ form.service.length }}/50</text>
        </view>
        <text class="field-hint">这一栏清空并保存即删除已填的服务说明（后端空串=清空该列，与「不填」不同）</text>
      </view>

      <!-- ==================== 4.2 门店照片：只读展示，不给输入口（D-18 一期无上传端点） ==================== -->
      <view class="card photo-card">
        <text class="block-title">门店照片（只读）</text>
        <view v-if="photoList.length" class="photo-list">
          <view v-for="(p, i) in photoList" :key="'ph' + i" class="photo-item">
            <image v-if="!photoFailed[i]" class="photo-img" :src="p.url" mode="aspectFill" @error="onPhotoError(i)" />
            <text v-else class="photo-fail">第 {{ i + 1 }} 张加载失败</text>
          </view>
        </view>
        <text v-else class="field-hint">暂无门店照片</text>
        <text class="field-hint">本页不提供照片增删：入库形态受平台上传件前缀白名单约束，拍照上传随 G-05 文件服务端点开放</text>
      </view>

      <!-- ==================== 4.3 保存 ==================== -->
      <view class="card save-card">
        <!-- 刻意不吃 HTML disabled（母版 dish-edit.vue:138 的实测结论）：真 disabled 会吞掉 click，
             冷却中点上来什么都不发生等于零反馈。这里视觉禁用 + 可点，save() 首行给 toast -->
        <button
          class="btn-main"
          :class="{ 'btn-disabled': saving || retryLock > 0 }"
          @click="save"
        ><text>{{ saveText }}</text></button>
        <!-- 提交前的「这次到底会发什么」摊开给人看：增量 diff 的预览与提交体共用同一个 computed，
             两处不是各算一遍，因此屏上列出的就是真正下发的键 -->
        <text v-if="changedKeys.length" class="field-hint">将提交 {{ changedKeys.length }} 项改动：{{ changedKeys.join('、') }}</text>
        <text v-else class="field-hint">没有改动时点保存不发请求（后端是白名单增量更新，多传一个空串会真清空那一列）</text>
        <!-- 常驻反馈行：成功/无改动/400/403/404/429 都落在这一行，不只 toast（母版 verify.vue:40-42 的理由：
             toast 一闪而过就等于没反馈）。toast 同批发出、文案同一处生成 -->
        <text v-if="feedback" class="store-feedback" :class="'store-feedback-' + feedback.tone">{{ feedbackText }}</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 门店信息维护页（F-18.19 §2）：D-7 七项白名单里「本页给输入口」的六项 + photos 只读展示。
// 四条口径写在这里以免看代码时猜：
// ① 保存体只带改动过的键（buildChangedBody 一处实现，预览与提交共用）：后端 PUT /api/partner/store 是
//    逐字段 `if (非 null) set` 的白名单增量更新（PartnerStoreServiceImpl.java:148-218），
//    「没传=不改」「传空串=清空该列」是两种不同指令，全量下发会把没看的列写成空——那是数据损失，不是显示瑕疵；
// ② 六项之外一律不发：name/address/lng/lat/status 在 PartnerStoreUpdateDTO 的
//    @JsonIgnoreProperties(ignoreUnknown = true) 上就被丢掉（不报错也不生效），前端留输入口就是静默假成功；
// ③ contactPhone 的回包是 PhoneMask.mask 的脱敏值（RD 红线，PartnerStoreServiceImpl.java:410），
//    明文根本到不了本页，所以这一栏的初值恒为空串、留空恒等于「不改」，本页不给「清空电话」这条路（见 phoneHint）；
// ④ 四态单值互斥 loading|error|empty|ready（照 workspace.vue:136 / verify.vue:179 的纪律，不叠布尔），
//    冷却锁只在 code===429 起，onUnload 成对清定时器，所有请求走 @/api/index.js（页内零原生请求）。
// 一期不做地图选点（D-24）：本页连坐标都不让改（D-7），搬高德地图块过来只能看不能存，纯增回归面。
import { getPartnerStore, updatePartnerStore, resolveFileUrl } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/store/store'

// 营业时间形状：与后端 PartnerStoreUpdateDTO.java:38/:42 的 @Pattern 同一条正则（去掉 ^$ 那一支——
// 本页 picker 只产出 HH:MM，不存在「主动清空时间列」这条路，预检只是把脏中间态拦在提交前）
const TIME_RE = /^([01]?\d|2[0-3]):[0-5]\d$/

// 联系电话形状（StoreApplyServiceImpl.java:42 / PartnerStoreServiceImpl.java:61 同一条规则）
const PHONE_RE = /^1[3-9]\d{9}$/

// 日接单量取值域（PartnerStoreUpdateDTO.java:46-47 的 @Min/@Max 同口径）
const CAPACITY_MIN = 1
const CAPACITY_MAX = 99999

// 服务说明列宽（store.service VARCHAR(50)，与后端 @Size(max=50) 同口径）
const SERVICE_MAX = 50

// 门店平台状态的正常值（init.sql store.status 值域 open/closed）：其余值原样透出，不替后端猜中文
const STORE_STATUS_OPEN = 'open'

// 后端 400 文案 → 表单字段（GlobalExceptionHandler 的 @Pattern/@Size 文案点名了字段，
// 这里据此把描红落到那一栏；映射不上就只出原文，不猜一栏）
const MESSAGE_FIELD_RULES = [
  ['营业开始时间', 'openTime'],
  ['营业结束时间', 'closeTime'],
  ['日接单容量', 'dailyCapacity'],
  ['accepting', 'accepting'],
  ['联系电话', 'contactPhone'],
  ['服务说明', 'service'],
  ['门店照片', 'photos']
]

export default {
  data() {
    return {
      // 页面四态互斥：loading | error | empty | ready
      viewState: 'loading',
      // D-22 选择器：进页时 ?storeId= 有值就指定一家，没有则由后端取名下 id 最小的一家，
      // 读到明细后用回包的 id 回填（此后 PUT 与 GET 落在同一家店，不会出现「读 A 存 B」）
      storeId: '',
      // GET /api/partner/store 的白名单 Map 快照（只读区与表单初值的唯一来源）
      store: null,
      // 表单值全部按字符串存（input 绑定态），提交时再转数字——中途态不能污染成 NaN
      // accepting 例外：它是 0/1 整数，与后端 DTO 同口径（switch 的 checked 由它派生）
      form: {
        openTime: '',
        closeTime: '',
        dailyCapacity: '',
        contactPhone: '',
        service: '',
        accepting: 1
      },
      // 增量 diff 的基线：与 form 同形状，applyDetail 每次成功后整体重设（见 changedBody 的注释）
      baseline: null,
      // 校验/后端 400 命中要落到「哪一栏」（母版 dish-edit.vue:194 errorKey 同款），供描红 + 滚动定位
      errorKey: '',
      // 照片加载失败位（按 index 记，失败只把那张换成一句话，不整块隐藏）
      photoFailed: {},
      // error 态文案（后端 message 原文）与错误码（决定主文案是「点太快」还是「加载失败」）
      errorText: '',
      errorCode: 0,
      // empty 态透出后端原文（403 的 R.MSG_FORBIDDEN / 404 的「门店不存在」）
      emptyMessage: '',
      // 保存单飞锁（写操作专用，finally 复位；失败也不能留在 true）
      saving: false,
      // 常驻反馈：{tone:'ok'|'neutral'|'err'|'cooling', text:string}；null = 本次会话还没点过保存
      feedback: null,
      // 429 冷却剩余秒数与驱动它的定时器（onUnload 成对清；口径同 verify.vue:186-187）
      retryLock: 0,
      retryLockTimer: null
    }
  },
  computed: {
    // 只读区的坐标行：后端给的是 DECIMAL(10,6) 的 GCJ-02 真源（init.sql:401-402），
    // 任一为空就整行如实写「未返回」，不拿单边值拼出「121.4-,」这种半截话
    coordText() {
      const d = this.store || {}
      if (d.lng === undefined || d.lat === undefined || d.lng === null || d.lat === null) return '未返回'
      return d.lng + ', ' + d.lat + '（GCJ-02，平台维护）'
    },
    statusText() {
      const s = this.store ? this.store.status : null
      if (s === undefined || s === null || s === '') return '未返回'
      if (s === STORE_STATUS_OPEN) return 'open（正常营业）'
      return String(s)
    },
    acceptingLabel() {
      return this.form.accepting === 1 ? '接单中' : '已暂停接单（临时满容/休市）'
    },
    // 电话栏占位：把当前脱敏值显出来，用户才知道「这栏已经有一位数了，我不填就是不动它」
    phonePlaceholder() {
      const masked = this.store && this.store.contactPhone
      return masked ? '留空表示不修改（当前 ' + masked + '）' : '留空表示不修改'
    },
    phoneHint() {
      return '出于脱敏口径，接口只回脱敏号，本页拿不到明文，因此无法回填也不能删号：留空恒等于「不修改」，'
        + '要改就填完整的 11 位新号；要删号请联系平台'
    },
    // 照片列表：入库值（/static/images/uploads/<名>）经 api 层 resolveFileUrl 归一，页面零自拼 BASE_URL
    photoList() {
      const raw = this.store && this.store.photos
      if (!Array.isArray(raw)) return []
      return raw.filter((p) => typeof p === 'string' && p).map((p) => ({ raw: p, url: resolveFileUrl(p) }))
    },
    // 本次提交体（增量 diff 的唯一实现处，见文件头注释 ①）：预览行与 save() 都读它，两处同源。
    // 基线是响应式的 baseline（不是实例上的私有字段）：这个 computed 的结果要进模板，
    // 基线若不参与依赖收集，就会出现「屏上列出的改动」与「真正下发的键」不同源的那一种漂移
    changedBody() {
      const base = this.baseline || this.form
      const cur = this.form
      const body = {}
      // ①/② openTime / closeTime：picker 只会产出 HH:MM，故这一支不存在空串（=不会误清空列）
      if (cur.openTime !== base.openTime) body.openTime = cur.openTime
      if (cur.closeTime !== base.closeTime) body.closeTime = cur.closeTime
      // ③ dailyCapacity：留空 = 不改（后端没有「清空回不限」这一支，见 PartnerStoreServiceImpl.java:163-169）
      const capacity = String(cur.dailyCapacity || '').trim()
      if (capacity !== '' && capacity !== String(base.dailyCapacity || '')) body.dailyCapacity = Number(capacity)
      // ④ accepting：只在真的翻过时下发（1/0 整数，不是 boolean）
      if (Number(cur.accepting) !== Number(base.accepting)) body.accepting = Number(cur.accepting)
      // ⑤ contactPhone：只有「填了非空且与基线不同」才下发。空串照发会真清列，而本页基线恒为 ''
      //    （明文拿不到），所以这一支刻意不给清空语义
      const phone = String(cur.contactPhone || '').trim()
      if (phone !== '' && phone !== String(base.contactPhone || '')) body.contactPhone = phone
      // ⑥ service：改了才发；清空是用户主动删文字，按后端契约（空串=清空该列）如实发 ''
      const service = String(cur.service || '').trim()
      if (service !== String(base.service || '')) body.service = service
      // ⑦ photos 不在这里：本页只读展示（D-18 无上传端点），一个键都不会下发
      return body
    },
    changedKeys() {
      return Object.keys(this.changedBody)
    },
    // 常驻反馈文案：cooling 一支的秒数读实时 retryLock（存死的字符串会在冷却结束后仍写着「请 60s 后再试」）
    feedbackText() {
      if (!this.feedback) return ''
      if (this.feedback.tone === 'cooling') {
        return this.retryLock > 0 ? '操作过于频繁，请 ' + this.retryLock + 's 后再试' : this.feedback.text
      }
      return this.feedback.text
    },
    saveText() {
      if (this.saving) return '保存中…'
      if (this.retryLock > 0) return '保存冷却中'
      return this.changedKeys.length ? '保存 ' + this.changedKeys.length + ' 项改动' : '保存修改'
    },
    errorLeadText() {
      if (this.errorCode === 429) return '点得太快了，请 60 秒后再刷新'
      return '门店资料加载失败，请检查网络后重试'
    }
  },
  watch: {
    // 描红只是「上一次拦在哪一栏」的记号：用户一开始改表单就撤掉，别让改好的栏还挂着红框
    form: {
      deep: true,
      handler() {
        this.errorKey = ''
      }
    }
  },
  onLoad(options) {
    this.storeId = options && options.storeId ? String(options.storeId) : ''
    this.load(true)
  },
  // 刻意不在 onShow 重拉：本页是编辑态，回页重拉会把用户刚改还没存的值整体覆盖（母版 dish-edit 同样只在 onLoad 拉）；
  // 需要看最新值时走错误/空态那两条显式的「重新检查」出口
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底（workspace.vue:226-231 同款）：index.html 锁 html,body overflow:hidden 后
    // PC 滚轮默认滚动失效，本页是长表单，不接管就够不到保存按钮
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    // 定时器清理：429 冷却这枚 setInterval 必须成对摘掉（页面销毁后仍在跑的话，
    // 回到本页会显示一个不属于本页的秒数，且每秒写一次已死组件的状态）
    this.stopRetryLock()
    // #ifdef H5
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
  },
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    textOr(v, fallback) {
      if (v === undefined || v === null || v === '') return fallback
      return String(v)
    },
    // ==================== 数据加载：GET /api/partner/store ====================
    async load(resetView) {
      // 并发守卫：重试/重新检查都可能撞上，同一时刻只放行一个请求序列
      if (this._loading) return
      // 冷却期内不重发（GET 与 PUT 都是同一账号的额度，撞在闸门上只会白烧一次）
      if (this.retryLock > 0) return
      this._loading = true
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getPartnerStore(this.storeId)
        const detail = (res && res.data && typeof res.data === 'object') ? res.data : null
        if (!detail) {
          // 200 但 data 不是对象：契约漂移，宁可按「读不到」落空态，绝不渲染一张空表单让人以为能保存
          this.showEmpty('门店资料为空，请确认这家门店是否仍在你的名下')
          return
        }
        this.applyDetail(detail)
        this.viewState = 'ready'
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 401) {
          // 跳登录页与清态都由请求层做完，这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        if (code === 403 || code === 404) {
          // 403=归属集合为空（AuthInterceptor:108-118）、404=门店不存在：都是确定性失败，
          // 重试永远失败，给申请入驻的去向而不是重试（workspace.vue 把 403 归 empty 同口径）
          this.showEmpty((e && e.message) || '')
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = code
        // 只有 429 才起冷却锁（verify.vue:443 同判据）：其余失败重试是有意义的，不该被秒数拦住
        if (code === 429) this.startRetryLock()
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loading = false
      }
    },
    // 明细灌进视图：只读区直接用回包，表单六项归一成「可比较的字符串/整数」形态，
    // 并在此把同一形态存一份进 baseline —— 增量 diff 的基线只能来自这里（FEB-10 的教训：
    // 没有基线，什么都没改点保存也会发一次 PUT，白烧写额度还多落一条 old==new 的 ops_log）
    applyDetail(detail) {
      this.store = detail
      this.storeId = (detail.id === undefined || detail.id === null) ? this.storeId : String(detail.id)
      const capacity = detail.dailyCapacity
      this.form = {
        openTime: this.textOr(detail.openTime, ''),
        closeTime: this.textOr(detail.closeTime, ''),
        // null（库里未设置）与 undefined（明细没回这一列）都归一成空串 = 「不修改」，
        // 二者在请求上是同一个结果，页内不必再分两支（分了就多一处会漂移的判断）
        dailyCapacity: (capacity === undefined || capacity === null) ? '' : String(capacity),
        // 明文拿不到（RD 脱敏），初值恒空 = 不改；见文件头注释 ③
        contactPhone: '',
        service: this.textOr(detail.service, ''),
        // m18 的 accepting 是 TINYINT NOT NULL DEFAULT 1：缺值按列默认显示，且快照同源，
        // 于是「没碰开关」永远不进提交体（不会把展示兜底值当成用户改动写回）
        accepting: (detail.accepting === 0 || detail.accepting === '0' || detail.accepting === false) ? 0 : 1
      }
      this.baseline = Object.assign({}, this.form)
      this.photoFailed = {}
      this.errorKey = ''
    },
    showEmpty(message) {
      this.store = null
      this.emptyMessage = (typeof message === 'string' ? message : '').trim()
      this.viewState = 'empty'
    },
    // ==================== 表单交互 ====================
    onOpenTimeChange(e) {
      this.form.openTime = this.pickTime(e, this.form.openTime, '开始营业')
    },
    onCloseTimeChange(e) {
      this.form.closeTime = this.pickTime(e, this.form.closeTime, '结束营业')
    },
    // picker 回的值理论上已是 HH:MM，但两端实现不完全一致（个别端会回 '8:0'），归一一次再入表单，
    // 与提交前的 TIME_RE 预检同一口径；拿不到合法值就保持这一栏的原值（keep）不动——
    // 宁可不改，也不写一个后端必拒的形状，更不把另一栏的值串过来
    pickTime(e, keep, label) {
      const raw = String((e && e.detail && e.detail.value) || '').trim()
      const m = /^(\d{1,2}):(\d{2})$/.exec(raw)
      const h = m ? Number(m[1]) : NaN
      const min = m ? Number(m[2]) : NaN
      if (!m || !(h >= 0 && h <= 23) || !(min >= 0 && min <= 59)) {
        this.showToast(label + '时间读取异常，请重新点选')
        return keep
      }
      return (h < 10 ? '0' : '') + h + ':' + min
    },
    // 数字栏一律在 @input 里过滤（type=number 在部分安卓键盘上仍会漏进非数字，maxlength 只管长度不管字符）
    onCapacityInput(e) {
      const raw = String((e && e.detail && e.detail.value) || '')
      this.form.dailyCapacity = raw.replace(/\D/g, '').slice(0, 5)
    },
    onPhoneInput(e) {
      const raw = String((e && e.detail && e.detail.value) || '')
      this.form.contactPhone = raw.replace(/\D/g, '').slice(0, 11)
    },
    onServiceInput(e) {
      this.form.service = String((e && e.detail && e.detail.value) || '').slice(0, SERVICE_MAX)
    },
    // switch 的 @change 给的是 boolean（e.detail.value，H5 与小程序两端同形），这里统一转成后端要的 0/1 整数；
    // 拿不到 detail 就退回原值，绝不替用户「猜一个新状态」（猜错就是把没动的列写进库）
    onAcceptingChange(e) {
      const detail = e && e.detail
      if (!detail || typeof detail.value !== 'boolean') {
        this.showToast('接单开关读取异常，请再点一次')
        return
      }
      this.form.accepting = detail.value ? 1 : 0
    },
    onPhotoError(i) {
      this.photoFailed[i] = true
    },
    // ==================== 校验（本地只拦一眼能判的形状，语义裁决权在服务端） ====================
    validate() {
      const fail = (msg, key) => ({ msg, key })
      if (this.form.openTime && !TIME_RE.test(this.form.openTime)) return fail('开始营业时间格式应为 HH:MM', 'openTime')
      if (this.form.closeTime && !TIME_RE.test(this.form.closeTime)) return fail('结束营业时间格式应为 HH:MM', 'closeTime')
      const capacity = String(this.form.dailyCapacity || '').trim()
      if (capacity !== '') {
        if (!/^\d+$/.test(capacity)) return fail('日接单量请填 1~99999 的整数', 'dailyCapacity')
        const n = Number(capacity)
        if (n < CAPACITY_MIN || n > CAPACITY_MAX) return fail('日接单容量需在 1~99999 之间', 'dailyCapacity')
      }
      const phone = String(this.form.contactPhone || '').trim()
      if (phone !== '' && !PHONE_RE.test(phone)) return fail('联系电话需为 11 位大陆手机号', 'contactPhone')
      if (this.form.service.length > SERVICE_MAX) return fail('服务说明不能超过 50 字', 'service')
      return null
    },
    // ==================== 保存（PUT /api/partner/store，只带改动过的键） ====================
    // 闸门写在方法首行：小程序端 disabled 按钮的 click 仍可能触发，只靠模板拦不住（verify.vue:271-273 同姿势）
    async save() {
      if (this.retryLock > 0) {
        this.showToast('操作过于频繁，请 ' + this.retryLock + 's 后再试')
        return
      }
      if (this.saving) return
      const ret = this.validate()
      if (ret) {
        this.showToast(ret.msg)
        this.focusField(ret.key)
        return
      }
      this.errorKey = ''
      const body = this.changedBody
      const keys = Object.keys(body)
      // 无改动一个请求都不发（母版 dish-edit.vue:756-763 的 FEB-10 结论）：白名单增量更新收到空 body 时
      // 后端虽也走「无变更」分支回当前视图（PartnerStoreServiceImpl.java:220-225），但那是一次白烧的写额度
      if (!keys.length) {
        this.setFeedback('neutral', '没有需要保存的修改')
        return
      }
      this.saving = true
      try {
        // storeId 作为 D-22 选择器交给 api 层下发查询参数（updatePartnerStore 内部转成 ?storeId=），
        // 它不在可改白名单里，也不会进请求体
        const res = await updatePartnerStore(Object.assign({ storeId: this.storeId }, body))
        const data = (res && res.data && typeof res.data === 'object') ? res.data : null
        // 成功用回包整体刷新视图（回包是落库后的权威值，不是「旧值 + 本次入参」的拼装，
        // PartnerStoreServiceImpl.java:236-241 专门回查过一次），并用新值重设 diff 基线
        if (data) this.applyDetail(data)
        this.setFeedback('ok', '已保存')
      } catch (e) {
        this.handleSaveError(e)
      } finally {
        this.saving = false
      }
    },
    // 失败按 400/403/404/429 分支给常驻反馈（F-18.19 §2）：输入值一律保留，不重拉、不整页换态
    handleSaveError(e) {
      const code = Number((e && e.code) || 0)
      const message = (e && e.message) || ''
      if (code === 401) {
        // 请求层已清态并回登录页；这里不写反馈——写任何一支都会把「登录失效」说成「保存有问题」
        return
      }
      if (code === 429) {
        this.setFeedback('cooling', '操作过于频繁，请稍后再试')
        this.startRetryLock()
        return
      }
      if (code === 400) {
        // 后端文案已按字段给（@Pattern/@Size/@Min 的 message），原样透出并顺手把描红落到那一栏
        this.setFeedback('err', message || '请求参数有误，请检查后重试')
        const key = this.fieldKeyOfMessage(message)
        if (key) this.focusField(key)
        return
      }
      if (code === 403) {
        this.setFeedback('err', message || '无权限访问，这家门店可能已不属于当前账号')
        return
      }
      if (code === 404) {
        this.setFeedback('err', message || '门店不存在，请回工作台确认账号状态')
        return
      }
      this.setFeedback('err', message || '保存失败，请检查网络后重试')
    },
    fieldKeyOfMessage(message) {
      const msg = String(message || '')
      if (!msg) return ''
      for (let i = 0; i < MESSAGE_FIELD_RULES.length; i++) {
        const rule = MESSAGE_FIELD_RULES[i]
        if (msg.indexOf(rule[0]) > -1) return rule[1]
      }
      return ''
    },
    // 反馈落一处（常驻行 + 同文 toast）：两条通道同一串，防止「toast 说成功、行说失败」的分裂
    setFeedback(tone, text) {
      this.feedback = { tone, text }
      this.showToast(text)
    },
    // ==================== 校验反馈定位 ====================
    focusField(key) {
      if (!key) return
      this.errorKey = key
      this.scrollToField('.st-field-' + key)
    },
    scrollToField(sel) {
      // #ifdef H5
      // index.html 锁死 html,body overflow:hidden，本页滚动容器是 document.body，
      // uni.pageScrollTo 滚的是 window，这里会静默无效——照 dish-edit.vue:695-722 用 scrollIntoView
      try {
        const el = typeof document !== 'undefined' && typeof document.querySelector === 'function'
          ? document.querySelector(sel)
          : null
        if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      } catch (e) {
        // 定位失败只是少了个滚动动画，toast 已经给出去了，不能因此把反馈升级成报错
      }
      // #endif
      // #ifndef H5
      try {
        const q = uni.createSelectorQuery().in(this)
        q.select(sel).boundingClientRect()
        q.selectViewport().scrollOffset()
        q.exec((res) => {
          if (!res || !res[0]) return
          const cur = (res[1] && res[1].scrollTop) || 0
          uni.pageScrollTo({ scrollTop: Math.max(0, cur + res[0].top - 16), duration: 300 })
        })
      } catch (e) {
        // 同上
      }
      // #endif
    },
    // ==================== 跳转 ====================
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    goApply() {
      uni.navigateTo({ url: '/pages/apply/apply' })
    },
    // ==================== 429 冷却（照 verify.vue:561-574 同款） ====================
    startRetryLock() {
      this.stopRetryLock()
      // 本地闸门 60s，与读写两侧 @RateLimit 的 60s 窗口同档；冷却结束再点仍可能吃一次 429，
      // 那一支会把常驻反馈行重新写上，反馈不会说谎
      this.retryLock = 60
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
    // 两道守卫缺一不可（照 verify.vue:580-606）：
    // ① 栈顶守卫：监听挂在 document 上，navigateTo 不触发上一页 onUnload，栈里多挂一份就会一次滚两倍
    // ② 表单控件与弹层放行：picker/input/textarea 上的滚轮归控件，不能 preventDefault 抢走
    //    （本页正文全是 input/textarea/picker，缺这条守卫就等于表单不可用）
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
        && el.closest('.uni-actionsheet, .uni-modal, .uni-toast, .uni-sample-toast, .uni-picker-container, textarea, input, uni-textarea, uni-input, uni-switch')) {
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
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.guide-card/.btn-main/.cell-arrow 取自 common.css，
   本页只补间距与专属字，不动主题变量、不加新公共类） */

/* ==================== 1/2/3. 单卡承载的三态 ==================== */
.loading-card { margin-top: 56rpx; display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }
.guide-card .btn-main { margin-top: 16rpx; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; }
.guide-link-cooling { color: #BBBBBB; }
.guide-link-plain { color: #999999; }

/* ==================== 4. 合规提示条（RE 红线自证，浅红底压在整页最前） ==================== */
.notice-card {
  margin-top: 24rpx;
  background-color: #FFF7F0;
  border: 1rpx solid #FFE3E0;
}
.notice-text { font-size: 24rpx; font-weight: 600; color: #E02020; line-height: 1.6; }

/* ==================== 4.0 只读身份区：整块灰显，明确「这里没有输入口」 ==================== */
.ro-card { display: flex; flex-direction: column; gap: 12rpx; }
.block-title { font-size: 30rpx; font-weight: 700; color: #333333; line-height: 1.4; }
.ro-row { display: flex; align-items: baseline; gap: 16rpx; }
.ro-label { flex-shrink: 0; width: 140rpx; font-size: 26rpx; color: #999999; line-height: 1.6; }
.ro-value {
  flex: 1; min-width: 0;
  font-size: 26rpx; color: #999999; line-height: 1.6;
  word-break: break-all;
}
.ro-note { font-size: 22rpx; color: #BBBBBB; line-height: 1.5; }

/* ==================== 4.1 表单行（形状取自母版 dish-edit.vue:845-884，类名换 st- 前缀避免跨页误命中） ==================== */
.form-card { margin-top: 24rpx; padding: 8rpx 24rpx 24rpx; }
.field-row {
  display: flex; align-items: center; gap: 16rpx;
  padding: 22rpx 0; border-bottom: 1rpx solid #F5F5F5;
}
.field-row-col { flex-direction: column; align-items: flex-start; gap: 12rpx; }
.field-label { width: 140rpx; flex-shrink: 0; font-size: 28rpx; color: #333333; line-height: 1.4; }
.field-row-col .field-label { width: auto; font-weight: 600; }
.field-input {
  flex: 1; min-width: 0;
  height: 68rpx; line-height: 68rpx;
  background-color: #F5F5F5; border-radius: 12rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333333;
}
.field-suffix { flex-shrink: 0; font-size: 24rpx; color: #999999; }
.field-picker {
  flex: 1; min-width: 0;
  font-size: 28rpx; color: #333333; line-height: 1.4;
  text-align: right;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 未设置时的显示值改灰：与「已有一个值」区分开，避免把占位话术读成真实时间 */
.field-picker.is-empty { color: #BBBBBB; }
.row-picker { display: block; width: 100%; }
.field-textarea {
  width: 100%; height: 120rpx; box-sizing: border-box;
  background-color: #F5F5F5; border-radius: 12rpx;
  padding: 16rpx 20rpx; font-size: 26rpx; color: #333333; line-height: 1.6;
}
.field-count { font-size: 20rpx; color: #BBBBBB; line-height: 1.4; align-self: flex-end; }
.field-hint { font-size: 22rpx; color: #BBBBBB; line-height: 1.5; }
.switch-text { flex: 1; min-width: 0; font-size: 26rpx; color: #666666; line-height: 1.4; }
.accepting-switch { flex-shrink: 0; transform: scale(.9); }
/* 校验命中栏的描红（母版 dish-edit.vue:880-884 同款）：一句居中 toast 不足以说明「是哪栏拦的」 */
.field-error {
  box-shadow: inset 0 0 0 2rpx #E02020;
  border-radius: 12rpx;
  background-color: #FFF7F0;
}

/* ==================== 4.2 照片只读区 ==================== */
.photo-card { display: flex; flex-direction: column; gap: 12rpx; }
.photo-list { display: flex; flex-wrap: wrap; gap: 16rpx; }
.photo-item { width: 160rpx; height: 160rpx; }
.photo-img { width: 160rpx; height: 160rpx; border-radius: 12rpx; }
.photo-fail {
  display: flex; align-items: center; justify-content: center;
  width: 160rpx; height: 160rpx; box-sizing: border-box;
  font-size: 20rpx; color: #BBBBBB; line-height: 1.4; text-align: center;
  background-color: #F5F5F5; border-radius: 12rpx;
}

/* ==================== 4.3 保存卡 ==================== */
.save-card { margin-top: 24rpx; display: flex; flex-direction: column; gap: 12rpx; }
/* 常驻反馈四支：成功=主红底白字，无改动=中性灰（不是失败就不该穿红），失败=主红，冷却=橙红 */
.store-feedback { font-size: 28rpx; font-weight: 600; line-height: 1.5; padding: 12rpx 16rpx; border-radius: 12rpx; }
.store-feedback-ok { color: #FFFFFF; background-color: #E02020; }
.store-feedback-neutral { color: #666666; background-color: #F5F5F5; }
.store-feedback-err { color: #E02020; background-color: #FFF1F0; }
.store-feedback-cooling { color: #E02020; background-color: #FFF7F0; }

.page-bottom-space { height: 60rpx; }
</style>
