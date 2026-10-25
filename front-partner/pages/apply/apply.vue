<template>
  <view class="page-container">
    <!-- ==================== 1. 加载中（先读状态再决定渲染什么，见 script 头注释「状态优先」） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在读取你的申请进度…</text>
    </view>

    <!-- ==================== 2. 加载失败（请求没成功，等一会儿或查网络就能成；429 起秒级冷却） ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText" class="guide-tip">{{ errorText }}</text>
        <text v-if="!retryLock" class="error-retry" @click="loadMine(true)">点击重试</text>
        <text v-else class="error-retry error-retry-cooling">{{ retryLock }}s后可重试</text>
        <text class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 3. 空态：本期唯一一支 = 申请已通过（已开通，没有可提交的东西）
             这不是错误态，也不给重试：状态是服务端事实，重试还是同一句「已开通」。
             给的去向是工作台（门店资料在 store 页改）与「再看一次」 ==================== -->
    <view v-else-if="viewState === 'empty'" class="card guide-card">
      <text class="guide-title">已开通</text>
      <text class="guide-text">入驻申请已通过审核，这家门店已经上线，无需重复提交；营业时间、接单开关等资料去「门店信息」页维护</text>
      <text v-if="approvedName" class="guide-strong">门店名称：{{ approvedName }}</text>
      <button class="btn-main" @click="goWorkspace"><text>去工作台</text></button>
      <text v-if="!retryLock" class="guide-link" @click="loadMine(true)">状态有变，重新检查</text>
      <text v-else class="guide-link guide-link-cooling">{{ retryLock }}s后可重新检查</text>
    </view>

    <!-- ==================== 4. 就绪：状态卡（pending/rejected/无记录）+ 申请表单 + 提交 ==================== -->
    <template v-else>
      <!-- 状态卡：pending/rejected 都先出这一张，让人先看清「现在处在哪一步」再动手 -->
      <view v-if="applyStatus !== 'none'" class="card status-card" :class="'status-card-' + statusTone">
        <text class="status-title">{{ statusLabel }}</text>
        <text v-if="submittedName" class="status-line">拟用门店名称：{{ submittedName }}</text>
        <text v-if="submittedAddress" class="status-line">拟用门店地址：{{ submittedAddress }}</text>
        <text v-if="createdAtText" class="status-line">提交时间：{{ createdAtText }}</text>
        <text v-if="auditRemark" class="status-remark">平台意见：{{ auditRemark }}</text>
        <text v-if="historyText" class="status-line">{{ historyText }}</text>
        <text class="status-note">坐标与其余资料不在本接口的出参里（GET /api/store-applies/mine 是最小暴露，StoreApplyController.java:51-52），这里只列能确认的部分</text>
        <text class="status-note">材料补充随 G-05 文件服务开放</text>
      </view>

      <!-- pending：表单不给编辑口（改=新申请，与后端 D-21 一致），只把「为什么不能改」说清 -->
      <view v-if="applyStatus === 'pending'" class="card locked-card">
        <text class="locked-text">审核期间不能修改，也不能重复提交（后端会回「已有申请在审核中，请等待结果」）。</text>
        <text class="locked-text">信息填错了？等平台驳回后在本页改后重提，或直接联系平台。</text>
      </view>

      <!-- 新建 / 驳回后重提：可编辑表单 -->
      <view v-else class="card form-card">
        <text class="block-title">入驻申请</text>
        <text v-if="applyStatus === 'rejected'" class="form-lead">修改后重新提交会生成一条新的申请行（旧记录保留在历史里）。</text>
        <text v-if="applyStatus === 'rejected'" class="form-lead">上次提交的坐标与联系方式接口不下发，这一版请重新填一遍。</text>

        <view class="field-row ap-field-proposedName" :class="{ 'field-error': errorKey === 'proposedName' }">
          <text class="field-label">门店名称</text>
          <input
            class="field-input"
            maxlength="100"
            :value="form.proposedName"
            placeholder="必填，100 字以内"
            placeholder-style="color:#BBBBBB;"
            @input="onNameInput"
          />
        </view>
        <view class="field-row field-row-col ap-field-proposedAddress" :class="{ 'field-error': errorKey === 'proposedAddress' }">
          <text class="field-label">门店地址</text>
          <textarea
            class="field-textarea"
            maxlength="255"
            :value="form.proposedAddress"
            placeholder="必填，写清门牌号，255 字以内"
            placeholder-style="color:#BBBBBB;"
            @input="onAddressInput"
          />
        </view>

        <!-- 坐标（D-24：一期不搬地图块）。两个手填数字框常驻可用，定位按钮只是「帮忙填一遍」的加速器：
             拿不到定位只提示、不清已填的值、更不把页面拖进错误态 ==================== -->
        <view class="coord-head">
          <text class="field-label coord-label">坐标</text>
          <view class="pill" :class="{ 'pill-disabled': locating }" hover-class="pill-hover" @click="locatePosition">
            <text>{{ locating ? '定位中…' : '用当前定位填一下' }}</text>
          </view>
        </view>
        <view class="field-row ap-field-lng" :class="{ 'field-error': errorKey === 'lng' }">
          <text class="field-label">经度</text>
          <input
            class="field-input"
            type="digit"
            maxlength="12"
            :value="form.lng"
            placeholder="可手填，如 121.473700"
            placeholder-style="color:#BBBBBB;"
            @input="onLngInput"
          />
        </view>
        <view class="field-row ap-field-lat" :class="{ 'field-error': errorKey === 'lat' }">
          <text class="field-label">纬度</text>
          <input
            class="field-input"
            type="digit"
            maxlength="12"
            :value="form.lat"
            placeholder="可手填，如 31.230400"
            placeholder-style="color:#BBBBBB;"
            @input="onLatInput"
          />
        </view>
        <text class="field-hint">{{ coordHint }}</text>

        <view class="field-row ap-field-service" :class="{ 'field-error': errorKey === 'service' }">
          <text class="field-label">服务说明</text>
          <input
            class="field-input"
            maxlength="50"
            :value="form.service"
            placeholder="选填，50 字以内，如 支持冷冻自提"
            placeholder-style="color:#BBBBBB;"
            @input="onServiceInput"
          />
        </view>
        <view class="field-row ap-field-contactName" :class="{ 'field-error': errorKey === 'contactName' }">
          <text class="field-label">联系人</text>
          <input
            class="field-input"
            maxlength="50"
            :value="form.contactName"
            placeholder="选填，50 字以内"
            placeholder-style="color:#BBBBBB;"
            @input="onContactNameInput"
          />
        </view>
        <view class="field-row ap-field-contactPhone" :class="{ 'field-error': errorKey === 'contactPhone' }">
          <text class="field-label">联系电话</text>
          <input
            class="field-input"
            type="number"
            maxlength="11"
            :value="form.contactPhone"
            placeholder="选填，11 位大陆手机号"
            placeholder-style="color:#BBBBBB;"
            @input="onPhoneInput"
          />
        </view>
        <text class="field-hint">凭证照片一期不放表单：没有上传端点（D-18），材料补充随 G-05 文件服务开放</text>
      </view>

      <!-- ==================== 提交 ==================== -->
      <view class="card submit-card">
        <!-- 刻意不吃 HTML disabled（母版 dish-edit.vue 的 save-card 注释给的实测结论：真 disabled 吞掉 click，
             点了什么都不发生等于零反馈）：视觉禁用 + 可点，submit() 首行按分支给一句实话 -->
        <button
          class="btn-main"
          :class="{ 'btn-disabled': !canSubmit }"
          @click="submit"
        ><text>{{ submitText }}</text></button>
        <!-- 常驻反馈行：成功/400/429/其余失败都落在这一行，不只 toast（front-partner/pages/verify/verify.vue 的 verify-feedback 常驻行同理由，F-25/P-4 重测）；
             400 一支显示后端原文——它已按字段给文案，前端不改写 -->
        <text v-if="feedback" class="apply-feedback" :class="'apply-feedback-' + feedback.tone">{{ feedbackText }}</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 入驻申请页（F-18.19 §3）：状态优先，先读 GET /api/store-applies/mine 再决定渲染什么。
// 四条口径写在这里以免看代码时猜：
// ① 状态分流（与后端 D-21/D-20 一致）：pending → 状态卡 + 禁用提交并显示已提交内容；
//    rejected → 状态卡显 auditRemark 并允许改后重提（会产生新申请行）；approved → 「已开通」+ 去工作台；
//    无记录 → 空白表单。四态单值互斥 loading|error|empty|ready（empty 唯一来源是 approved 这个服务端事实），
//    ready 内部再按 applyStatus 这个「服务端事实枚举」分可编辑/只读两支——它不是第二个布尔开关，
//    与 workspace 的「页面四态互斥」data 注释 / verify.vue:141 的「不叠布尔」纪律不冲突（后者是 ① 那条「四态单值互斥 loading|error|empty|ready」的页面纪律；两处读数按 F-25/P-4 重测）；
// ② 坐标：库里 store_apply.lng/lat 存的是 GCJ-02 真源（见 init.sql 的 store_apply.lng/lat 列注释），而 uni.getLocation 在 H5 端
//    对 type:'gcj02' 并不做本地转换，而是把转换委托给地图 SDK（引证见 locatePosition 的注释）。
//    本工程按 D-24 一期不配高德，那条路必然 reject，所以这里取 type:'wgs84'（原生返回、零 SDK 依赖）
//    再用 utils/gcj02.js 本地转 GCJ-02；两个手填数字框常驻可用，定位失败只提示、不清已填值；
// ③ 提交前本地必填校验指到具体字段（缺项描红 + 滚过去），语义裁决权仍在服务端，400 原样显示后端原文；
// ④ 冷却锁只在 code===429 起，onUnload 成对清定时器，所有请求走 @/api/index.js（页内零原生请求）。
// 本页挂 /api/store-applies 而非 /api/partner/**（D-20：申请阶段名下无店，partner 闸门会把自己锁死），
// 所以 role=user 也能进，403 在这里不是设计内分支（请求层 403 不跳登录页的语义照旧）。
import { getMyStoreApply, submitStoreApply, retrySecondsOf, coolingText } from '@/api/index.js'
import { wgs84ToGcj02 } from '@/utils/gcj02.js'
import '@/styles/common.css'

// 本页路由（滚轮栈顶守卫用，须与 pages.json 的 path 逐字一致）
const PAGE_ROUTE = 'pages/apply/apply'

// 后端列宽（StoreApplyCreateDTO.java:38-64 的 @Size 同口径）：本地拦一句是为了不让人白填，
// 语义裁决权在服务端，前端不复制 @Pattern 那套（手机号这里只拦「填了就得是 11 位」）
const NAME_MAX = 100
const ADDRESS_MAX = 255
const SERVICE_MAX = 50
const CONTACT_NAME_MAX = 50
const PHONE_RE = /^1[3-9]\d{9}$/

// 中国粗框（与服务端 @DecimalMin/@DecimalMax 同口径，StoreApplyCreateDTO.java:47-53）
const LNG_MIN = 73
const LNG_MAX = 135
const LAT_MIN = 3
const LAT_MAX = 54

// 落库列是 DECIMAL(10,6)（init.sql 的 store_apply.lng/lat 两列；store 那两列是 store.lng/lat）：手填输入只留 6 位小数，第 7 位起 MySQL 会自己四舍五入，
// 屏上多显一位就是「显示的与入库的不是同一个数」
const COORD_DECIMALS = 6

// 申请状态三值（init.sql store_apply.status 值域 pending/approved/rejected）
const STATUS_PENDING = 'pending'
const STATUS_REJECTED = 'rejected'
const STATUS_APPROVED = 'approved'

// F-23/FP-3：429 冷却时长不再硬编码 60——后端 429 信封的 data.retryAfterSeconds 优先，
// 取不到才回落本地 60。回落值口径：它是写侧 @RateLimit（POST /api/store-applies 10 次/分）整窗长度，
// 是「后端不给 data 时的保守上限」，不是回速（回速实测约 6 秒），两者不是一回事。
const RETRY_FALLBACK_SECONDS = 60

// retrySecondsOf 已收敛到 api 层（F-25 路 1）：front-partner 内唯一实现是 front-partner/api/index.js 的 retrySecondsOf，
// 页内不再留私有副本；本页档位由上面的 RETRY_FALLBACK_SECONDS 作为第二参传进去。
// 载荷来源：请求层 handleEnvelope 的 reject 带 data（front-partner/api/index.js:183-186 早已透传），
// 429 锁的 {retryAfterSeconds} 就是从这里进页面；网络异常/信封不可解析时 data 为 null，走上面的本地回落。

export default {
  data() {
    return {
      // 页面四态互斥：loading | error | empty（已开通）| ready（表单/状态卡）
      viewState: 'loading',
      // 服务端事实枚举：none（没申请过）| pending | rejected —— 决定 ready 里表单是否可编辑
      applyStatus: 'none',
      // GET /api/store-applies/mine 的快照（白名单七键 + historyCount，不含电话与凭证）
      apply: null,
      // 表单值全部按字符串存（input 绑定态），提交时再转数字——中途态不能污染成 NaN
      form: {
        proposedName: '',
        proposedAddress: '',
        lng: '',
        lat: '',
        service: '',
        contactName: '',
        contactPhone: ''
      },
      // 校验/后端 400 命中落到哪一栏（母版 dish-edit.vue 的 errorKey 同款），供描红 + 滚动定位
      errorKey: '',
      // 提交单飞锁（写操作，finally 复位）与定位请求的进行中标记
      submitting: false,
      locating: false,
      // 常驻反馈：{tone:'ok'|'neutral'|'err'|'cooling', text:string}；null = 本次会话还没提交过
      feedback: null,
      // error 态文案与错误码（决定主文案是「点太快」还是「读取失败」）
      errorText: '',
      errorCode: 0,
      // 429 冷却剩余秒数与驱动它的定时器（onUnload 成对清；口径同 verify.vue 的拆锁，F-25/P-4 重测）
      // X-1 拆锁（母版 dishes.vue catRetryLock）：retryLock = 读侧闸门（loadMine/refreshMineQuiet 撞 mine 读桶），
      // writeRetryLock = 写侧闸门（submit 撞 POST /store-applies 写桶）。两把独立 @RateLimit 桶各计各的。
      retryLock: 0,
      retryLockTimer: null,
      writeRetryLock: 0,
      writeRetryLockTimer: null
    }
  },
  computed: {
    canSubmit() {
      return this.applyStatus !== STATUS_PENDING && !this.submitting && this.writeRetryLock === 0
    },
    submitText() {
      if (this.submitting) return '提交中…'
      if (this.applyStatus === STATUS_PENDING) return '审核中，暂不能提交'
      if (this.writeRetryLock > 0) return '提交冷却中'
      return this.applyStatus === STATUS_REJECTED ? '重新提交（会生成新申请）' : '提交入驻申请'
    },
    statusLabel() {
      if (this.applyStatus === STATUS_PENDING) return '审核中'
      if (this.applyStatus === STATUS_REJECTED) return '已驳回'
      return ''
    },
    // 状态卡配色：pending=中性灰（还没结果，不该穿红），rejected=主红（这是需要行动的事实）
    statusTone() {
      if (this.applyStatus === STATUS_PENDING) return 'neutral'
      if (this.applyStatus === STATUS_REJECTED) return 'err'
      return 'neutral'
    },
    submittedName() {
      return this.textOr(this.apply && this.apply.proposedName, '')
    },
    submittedAddress() {
      return this.textOr(this.apply && this.apply.proposedAddress, '')
    },
    auditRemark() {
      return this.textOr(this.apply && this.apply.auditRemark, '')
    },
    approvedName() {
      return this.textOr(this.apply && this.apply.proposedName, '')
    },
    // 申请时间：带 '-' 的后端字符串原样透出（把 ISO 的 T 换空格、砍到分钟）。
    // 不走 new Date() 再格式化：那会把无时区的 LocalDateTime 按本地时区平移一次，屏上值就不再是库里那一位
    // （同一支渲染逻辑见 verify.vue 的 timeText，F-25/P-4 重测）
    createdAtText() {
      const s = this.textOr(this.apply && this.apply.createdAt, '')
      if (!s) return ''
      if (s.indexOf('-') > -1) return s.replace('T', ' ').slice(0, 16)
      return s
    },
    historyText() {
      const n = Number(this.apply && this.apply.historyCount)
      if (!Number.isFinite(n) || n <= 0) return ''
      return '历史申请 ' + n + ' 条'
    },
    coordHint() {
      return '可手填，坐标需与地址一致；点「用当前定位填一下」会自动取浏览器位置并转成 GCJ-02（与库里同一坐标系）。'
        + '拿不到定位时手动填也可以，精度到 6 位小数'
    },
    // 常驻反馈文案：cooling 一支的秒数读实时写侧闸门 writeRetryLock（存死的字符串会在冷却结束后仍写着「请 60s 后再试」）
    // F-28.5 路 A：带数整句不再页内拼，交 api 层唯一的 coolingText（本页 cooling 支原是不 import 收敛的三份私有实现之外的第四份手写拼接，一并收进来）
    feedbackText() {
      if (!this.feedback) return ''
      if (this.feedback.tone === 'cooling') {
        return this.writeRetryLock > 0 ? coolingText(this.writeRetryLock) : this.feedback.text
      }
      return this.feedback.text
    },
    // F-23-fix/FF-1：429 这行不带时长，秒数只在闸门那一处（本页模板的 retryLock 倒计时行）出现一次
    // F-28.5 路 A（X-8）：句式收进 coolingText 无参整句——「点得太快了」这一支第三种说法并入全端统一族
    errorLeadText() {
      if (this.errorCode === 429) return coolingText()
      // X-2b（四路分流，母版 front-merchant/pages/dashboard/dashboard.vue 的同名 computed）：500 是服务端
      // 承认自己处理出错，同屏第二行透出的正是那句原文，第一行却叫申请人去查网络——因果方向相反
      if (this.errorCode === 500) return '申请进度读取失败，服务端处理出错，稍后重试'
      // 只有网络层失败才许出现「网络」字样：front-partner/api/index.js 的 request fail 支回 code:-1 /
      // message「网络异常」，本页 errorCode 因此是 -1；没带码的失败归 0，两支同一条出路
      if (this.errorCode <= 0) return '申请进度读取失败，请检查网络后重试'
      // 其余服务端业务码（400 一类）：不猜成因，报「加载失败」＋第二行透出后端 message 原文
      return '申请进度读取失败，请稍后重试'
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
  onLoad() {
    // 状态优先（F-18.19 §3）：先读 mine，再决定渲染状态卡还是空白表单
    this.loadMine(true)
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底（front-partner/pages/workspace/workspace.vue 的 onPageWheel 同款，F-25/P-4 重测）：index.html 锁 html,body overflow:hidden 后
    // PC 滚轮默认滚动失效，本页是长表单，不接管就够不到提交按钮
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    // 定时器清理：X-1 拆锁后有两枚 setInterval（读侧 retryLockTimer / 写侧 writeRetryLockTimer），必须都成对摘掉（挂在 document 上不摘就会在页面销毁后
    // 继续每秒写一次已死组件的状态）
    this.stopRetryLock()
    this.stopWriteRetryLock()
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
    // ==================== 状态读取：GET /api/store-applies/mine（四态的主人） ====================
    async loadMine(resetView) {
      if (this._loadingMine) return
      if (this.retryLock > 0) return
      this._loadingMine = true
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getMyStoreApply()
        const data = (res && res.data && typeof res.data === 'object') ? res.data : {}
        this.applyMine(data)
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 401) {
          // 跳登录页与清态都由请求层做完，这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = code
        // 只有 429 才起冷却锁（同 verify.vue 的 fetchList 那一支，真身是 if (Number(e && e.code) === 429) this.startRetryLock(e)；F-25/P-4 重测）：其余失败重试是有意义的，不该被秒数拦住
        if (code === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loadingMine = false
      }
    },
    // 把 mine 出参落成视图状态：状态卡数据 + 「无记录 / 驳回后重提」时的表单预填
    applyMine(data) {
      this.apply = data
      const status = this.textOr(data.status, '')
      if (status === STATUS_APPROVED) {
        // 已开通：没有可提交的东西，落 empty 态给「去工作台」，不给重试
        this.applyStatus = 'none'
        this.viewState = 'empty'
        return
      }
      this.errorText = ''
      this.errorCode = 0
      this.viewState = 'ready'
      if (status === STATUS_PENDING) {
        this.applyStatus = STATUS_PENDING
        return
      }
      if (status === STATUS_REJECTED) {
        this.applyStatus = STATUS_REJECTED
        // 驳回后改后重提：能回填的只有出参里那两栏（坐标与联系方式是最小暴露之外的字段，接口不下发），
        // 其余留空由用户重填——hint 已在模板里如实说明，不假装「已恢复上次填写」
        this.form.proposedName = this.textOr(data.proposedName, '')
        this.form.proposedAddress = this.textOr(data.proposedAddress, '')
        return
      }
      // status 为空 = 从未申请过（StoreApplyServiceImpl#mine 各键回 null、historyCount=0）→ 空白表单
      this.applyStatus = 'none'
    },
    // 提交成功后的静默纠偏：只为把 createdAt/historyCount 这类本地拿不到的值对齐服务端。
    // 这是一条静默通道（同 verify.vue 的 loadAlert 的纪律，F-25/P-4 重测）：失败不动四态、不清已有反馈、
    // 不起 toast——「已提交」这件事已经由 POST 的回包定死，这一趟没跑成不该把它说成失败
    async refreshMineQuiet() {
      if (this.retryLock > 0) return
      try {
        const res = await getMyStoreApply()
        const data = (res && res.data && typeof res.data === 'object') ? res.data : null
        if (data) {
          const status = this.textOr(data.status, '')
          // 只接受「仍然是本次提交的那条 pending」，其余状态一律以服务端为准覆盖视图
          if (status === STATUS_PENDING || status === STATUS_APPROVED || status === STATUS_REJECTED) {
            this.apply = data
            if (status === STATUS_APPROVED) this.viewState = 'empty'
            else this.applyStatus = status
          }
        }
      } catch (e) {
        if (Number(e && e.code) === 429) this.startRetryLock(e)
      }
    },
    // ==================== 表单输入（数字栏一律在 @input 里过滤：type=number 在部分安卓键盘上仍会漏进非数字） ====================
    onNameInput(e) {
      this.form.proposedName = String((e && e.detail && e.detail.value) || '').slice(0, NAME_MAX)
    },
    onAddressInput(e) {
      this.form.proposedAddress = String((e && e.detail && e.detail.value) || '').slice(0, ADDRESS_MAX)
    },
    onServiceInput(e) {
      this.form.service = String((e && e.detail && e.detail.value) || '').slice(0, SERVICE_MAX)
    },
    onContactNameInput(e) {
      this.form.contactName = String((e && e.detail && e.detail.value) || '').slice(0, CONTACT_NAME_MAX)
    },
    onPhoneInput(e) {
      const raw = String((e && e.detail && e.detail.value) || '')
      this.form.contactPhone = raw.replace(/\D/g, '').slice(0, 11)
    },
    // 经纬度：只留数字与一个小数点（负号在这两个区间里用不上——中国粗框全是正值，
    // 让它进来只会造出一个后端必拒的 -121，拦在输入层比拦在校验层省事）
    onLngInput(e) {
      this.form.lng = this.filterCoord(e, this.form.lng)
    },
    onLatInput(e) {
      this.form.lat = this.filterCoord(e, this.form.lat)
    },
    filterCoord(e, keep) {
      const raw = String((e && e.detail && e.detail.value) || '')
      const cleaned = raw.replace(/[^\d.]/g, '')
      const firstDot = cleaned.indexOf('.')
      if (firstDot === -1) return cleaned.slice(0, 12)
      // 只认第一个小数点，小数位截到 6 位（DECIMAL(10,6) 的实际有效精度）
      const intPart = cleaned.slice(0, firstDot).slice(0, 5)
      const decPart = cleaned.slice(firstDot + 1).replace(/\./g, '').slice(0, COORD_DECIMALS)
      return intPart + '.' + decPart
    },
    // ==================== 定位（只帮忙，不添乱） ====================
    // 关于 uni.getLocation 在 H5 的真实行为（已读实现，非推测）：
    //   E:/HBuilderX/plugins/uniapp-cli-vite/node_modules/@dcloudio/uni-h5/dist/uni-h5.es.js
    //   :21402-21415 先取 navigator.geolocation（浏览器给的是 WGS-84），
    //   :21501-21512 成功值一律过 translateCoordinateSystem(type, coords, skip) 再 resolve；
    //   :18106 type 是 'wgs84' 时短路原样返回；
    //   :18109-18160 type 是 'gcj02' 时把转换委托给「地图 SDK」——qq 走 JSONP、AMap 走 window.AMap.convertFrom；
    //   :18162-18166 没有配置任何地图 provider 时直接 Promise.reject('translate coordinate system faild, …')，
    //   :21513-21515 那个 reject 就是 uni.getLocation 的失败回调。
    //   本端 manifest.json 的 h5 只有 title、没有 maps.key（dev 产物 /manifest-json-js 里 aMapKey/qqMapKey
    //   均为 undefined），且 D-24 一期不配高德 → 传 type:'gcj02' 会「拿到定位也报失败」。
    // 结论：这里按 type:'wgs84' 拿原生值，再用 utils/gcj02.js 的 wgs84ToGcj02 本地转 GCJ-02
    // （DB 侧存 GCJ-02 真源，只有用户定位这一侧需要转换，HANDOFF §8 的不对称口径；见 utils/gcj02.js 头注释）。
    locatePosition() {
      if (this.locating) return
      this.locating = true
      uni.getLocation({
        type: 'wgs84',
        success: (res) => {
          const lng = Number(res && res.longitude)
          const lat = Number(res && res.latitude)
          if (!Number.isFinite(lng) || !Number.isFinite(lat)) {
            // 只提示，已填的值一个都不动
            this.showToast('定位结果异常，请手动填写坐标')
            return
          }
          const gcj = wgs84ToGcj02(lng, lat)
          const gLng = Number(gcj && gcj.lng)
          const gLat = Number(gcj && gcj.lat)
          if (!Number.isFinite(gLng) || !Number.isFinite(gLat)) {
            this.showToast('坐标转换异常，请手动填写坐标')
            return
          }
          // 归一到 6 位小数（列宽 DECIMAL(10,6)）：与 MySQL 写入该列时的四舍五入同一档，
          // 于是屏上显示的就是将要入库的那一位，不会「显示 7 位、库里 6 位」
          this.form.lng = this.trimCoord(gLng)
          this.form.lat = this.trimCoord(gLat)
          this.showToast('已按当前定位填好坐标，请核对后提交')
        },
        fail: (err) => {
          // 硬约束：定位失败/被拒只提示，不清已有输入值、不写 feedback、不改 viewState。
          // 「拒绝授权」是用户的正当选择，把它做成页面错误态就是在惩罚用户
          const msg = String((err && (err.errMsg || err.message)) || '')
          this.showToast('没拿到定位，可手动填写坐标' + (msg ? '（' + this.failReason(msg) + '）' : ''))
        },
        complete: () => {
          this.locating = false
        }
      })
    },
    // 把 uni 的 errMsg 翻成人话（只取常见三支，其余原样短截，不替它编原因）
    failReason(msg) {
      if (msg.indexOf('auth') > -1 && msg.indexOf('deny') > -1) return '定位权限被拒绝'
      if (msg.indexOf('PERMISSION') > -1) return '定位权限被拒绝'
      if (msg.indexOf('TIME') > -1 || msg.indexOf('timeout') > -1) return '定位超时'
      if (msg.indexOf('provider') > -1) return '浏览器未提供位置'
      return '未获取到位置'
    },
    trimCoord(n) {
      return String(Number(n.toFixed(COORD_DECIMALS)))
    },
    // ==================== 校验：缺项要指到具体字段（F-18.19 §3） ====================
    validate() {
      const fail = (msg, key) => ({ msg, key })
      const name = (this.form.proposedName || '').trim()
      if (!name) return fail('请填写门店名称', 'proposedName')
      if (name.length > NAME_MAX) return fail('门店名称不能超过 100 字', 'proposedName')
      const address = (this.form.proposedAddress || '').trim()
      if (!address) return fail('请填写门店地址', 'proposedAddress')
      if (address.length > ADDRESS_MAX) return fail('门店地址不能超过 255 字', 'proposedAddress')
      const lng = (this.form.lng || '').trim()
      const lat = (this.form.lat || '').trim()
      if (!lng) return fail('请填写门店经度（可用定位填，也可手填）', 'lng')
      if (!lat) return fail('请填写门店纬度（可用定位填，也可手填）', 'lat')
      if (!/^\d+(\.\d+)?$/.test(lng)) return fail('经度请填数字，如 121.473700', 'lng')
      if (!/^\d+(\.\d+)?$/.test(lat)) return fail('纬度请填数字，如 31.230400', 'lat')
      const nLng = Number(lng)
      const nLat = Number(lat)
      if (!(nLng >= LNG_MIN && nLng <= LNG_MAX)) return fail('门店经度需在中国范围（73~135）', 'lng')
      if (!(nLat >= LAT_MIN && nLat <= LAT_MAX)) return fail('门店纬度需在中国范围（3~54）', 'lat')
      if ((this.form.service || '').trim().length > SERVICE_MAX) return fail('服务说明不能超过 50 字', 'service')
      if ((this.form.contactName || '').trim().length > CONTACT_NAME_MAX) return fail('联系人姓名不能超过 50 字', 'contactName')
      const phone = (this.form.contactPhone || '').trim()
      if (phone && !PHONE_RE.test(phone)) return fail('联系电话需为 11 位大陆手机号', 'contactPhone')
      return null
    },
    // 提交体：只带 DTO 声明的键（StoreApplyCreateDTO 没有 userId 这一说，归属唯一来源是 token，RF 红线）；
    // 选填项空串一律不下发——Service 侧 trimToNull 后同样是 NULL，发空串只是多一个键
    buildBody() {
      const body = {
        proposedName: (this.form.proposedName || '').trim(),
        proposedAddress: (this.form.proposedAddress || '').trim(),
        lng: Number(this.form.lng),
        lat: Number(this.form.lat)
      }
      const service = (this.form.service || '').trim()
      if (service) body.service = service
      const contactName = (this.form.contactName || '').trim()
      if (contactName) body.contactName = contactName
      const phone = (this.form.contactPhone || '').trim()
      if (phone) body.contactPhone = phone
      // proofPhotos 刻意不在这里：一期没有上传端点（D-18），给了就是「填了也进不去库」的假入口
      return body
    },
    // ==================== 提交 ====================
    // 闸门写在方法首行：小程序端 disabled 按钮的 click 仍可能触发，只靠模板拦不住（verify.vue 的 submitVerify 首行同姿势，F-25/P-4 重测）
    async submit() {
      // X-1：提交闸门读写侧 writeRetryLock（POST 写桶），与读侧 mine 刷新的 retryLock 互不干扰
      if (this.writeRetryLock > 0) {
        // F-28.5 路 A（X-5）：被闸门拦下的 toast 不带数——同屏常驻反馈行（feedbackText 读实时 writeRetryLock）是写侧唯一带数处
        this.showToast(coolingText())
        return
      }
      if (this.applyStatus === STATUS_PENDING) {
        // 审核中点提交：给一句实话，不发请求（后端 D-21 会回 400「已有申请在审核中，请等待结果」，
        // 本地先拦是为了不白烧一次 10 次/分的写额度）
        this.showToast('已有申请在审核中，请等待结果')
        return
      }
      if (this.submitting) return
      const ret = this.validate()
      if (ret) {
        this.showToast(ret.msg)
        this.focusField(ret.key)
        return
      }
      this.errorKey = ''
      this.submitting = true
      try {
        const res = await submitStoreApply(this.buildBody())
        const data = (res && res.data) || {}
        // 成功回状态卡：先用手里的数据把 pending 状态立起来（不等第二趟网络往返），
        // 再静默重拉 mine 把 createdAt/historyCount 对齐服务端
        this.applyStatus = STATUS_PENDING
        this.apply = Object.assign({}, this.apply || {}, {
          id: (data.id === undefined ? null : data.id),
          status: STATUS_PENDING,
          proposedName: this.form.proposedName,
          proposedAddress: this.form.proposedAddress,
          auditRemark: null
        })
        this.setFeedback('ok', (res && res.message) || '申请已提交，审核结果会在「我的申请」里显示')
        this.refreshMineQuiet()
      } catch (e) {
        this.handleSubmitError(e)
      } finally {
        this.submitting = false
      }
    },
    // 失败按 400/429/其余分支给常驻反馈（400 显示后端原文，它已按字段给文案）
    handleSubmitError(e) {
      const code = Number((e && e.code) || 0)
      const message = (e && e.message) || ''
      if (code === 401) {
        // 请求层已清态并回登录页；这里不写反馈——写任何一支都会把「登录失效」说成「申请有问题」
        return
      }
      if (code === 429) {
        // 本页 X-3 无病：起锁点本就存不带数字的整句（此处为四页母版），秒数只在 feedbackText 读实时 writeRetryLock 那一处出
        // X-1：起写侧闸门 writeRetryLock——POST 写桶的冷却与 mine 读闸门互不覆盖
        // F-28.5 路 A：母版那句整句现在的唯一字面出处在 api 层的 coolingText 无参支，本页改调它（字符串逐字不变）
        this.setFeedback('cooling', coolingText())
        this.startWriteRetryLock(e)
        return
      }
      if (code === 400) {
        this.setFeedback('err', message || '请求参数有误，请检查后重试')
        const key = this.fieldKeyOfMessage(message)
        if (key) this.focusField(key)
        return
      }
      if (code === 403) {
        this.setFeedback('err', message || '无权限访问，请回工作台确认账号状态')
        return
      }
      this.setFeedback('err', message || '提交失败，请检查网络后重试')
    },
    // 后端 @Size/@NotBlank/@DecimalMin 的 message 都点名了字段（「门店名称不能超过 100 字」这类），
    // 据此把描红落到那一栏；映射不上就只出原文，不猜一栏
    fieldKeyOfMessage(message) {
      const msg = String(message || '')
      if (!msg) return ''
      const rules = [
        ['门店名称', 'proposedName'],
        ['拟用门店名称', 'proposedName'],
        ['门店地址', 'proposedAddress'],
        ['经度', 'lng'],
        ['纬度', 'lat'],
        ['服务说明', 'service'],
        ['联系人姓名', 'contactName'],
        ['联系电话', 'contactPhone'],
        ['凭证照片', 'proofPhotos']
      ]
      for (let i = 0; i < rules.length; i++) {
        if (msg.indexOf(rules[i][0]) > -1) return rules[i][1]
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
      this.scrollToField('.ap-field-' + key)
    },
    scrollToField(sel) {
      // #ifdef H5
      // index.html 锁死 html,body overflow:hidden，本页滚动容器是 document.body，
      // uni.pageScrollTo 滚的是 window，这里会静默无效——照 dish-edit.vue 的 scrollToField 用 scrollIntoView
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
    // 栈里能退就退（本页从工作台 navigateTo 进来），深链直达时栈长度只有 1，
    // navigateBack 在 H5 等于 history.go(-1) 会落到站外——照 dish-edit.vue 的 goBackToList 判栈姿势
    goWorkspace() {
      let pages = []
      try {
        pages = getCurrentPages() || []
      } catch (e) {
        pages = []
      }
      if (pages.length > 1) {
        uni.navigateBack()
        return
      }
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    // ==================== 429 冷却（定时器与闸门同端母版同款；F-25/P-3 起入参是错误对象，起锁秒数由 retrySecondsOf 取值） ====================
    // X-1 拆锁：startRetryLock/stopRetryLock 只管读侧闸门 retryLock（loadMine/refreshMineQuiet 撞 mine 读桶）；
    // 写侧闸门另有 startWriteRetryLock/stopWriteRetryLock 一对，两把锁互不覆盖。
    startRetryLock(err) {
      this.stopRetryLock()
      // F-23/FP-1：闸门秒数优先转述后端 429 信封的 data.retryAfterSeconds，取不到才回落本地 60
      // （原先硬锁 60 是把 10 次/分、回速约 6 秒的桶高估了 10 倍）；
      // 冷却结束再点仍可能吃一次 429，那一支会把常驻反馈行重新写上，反馈不会说谎
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
    // X-1：写侧闸门专用一对——handleSubmitError 撞 POST 写桶的 429 只动这两枚变量，
    // 读侧任何路径（含 loadMine 非 429 失败的 stopRetryLock）都清不掉它。秒数仍只来自 retrySecondsOf。
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
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 两道守卫缺一不可（照 verify.vue 的 onPageWheel 与其上方守卫说明注释，F-25/P-4 重测）：
    // ① 栈顶守卫：监听挂在 document 上，navigateTo 不触发上一页 onUnload，栈里多挂一份就会一次滚两倍
    // ② 表单控件与弹层放行：input/textarea 上的滚轮归控件，不能 preventDefault 抢走
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
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.guide-card/.btn-main 取自 common.css，
   本页只补间距与专属字，不动主题变量、不加新公共类） */

/* ==================== 1/2/3. 单卡承载的三态 ==================== */
.loading-card { margin-top: 56rpx; display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }
.guide-card .btn-main { margin-top: 16rpx; }
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
.error-lead { font-size: 28rpx; font-weight: 600; color: #333333; line-height: 1.5; text-align: center; }
.error-retry-cooling { color: #BBBBBB; border-color: #EEEEEE; }
.guide-link-cooling { color: #BBBBBB; }

/* ==================== 4. 状态卡（pending 中性、rejected 主红） ==================== */
.status-card { margin-top: 24rpx; display: flex; flex-direction: column; gap: 10rpx; }
.status-card-neutral { background-color: #F7F7F7; border: 1rpx solid #EEEEEE; }
.status-card-err { background-color: #FFF7F0; border: 1rpx solid #FFE3E0; }
.status-title { font-size: 32rpx; font-weight: 700; color: #333333; line-height: 1.4; }
.status-card-err .status-title { color: #E02020; }
.status-line { font-size: 24rpx; color: #666666; line-height: 1.6; word-break: break-all; }
/* 平台意见（驳回原因）：主红小字，这是本页唯一需要用户据以行动的一句话 */
.status-remark { font-size: 24rpx; font-weight: 600; color: #E02020; line-height: 1.6; word-break: break-all; }
.status-note { font-size: 20rpx; color: #BBBBBB; line-height: 1.5; }

/* 审核中的告知卡：没有输入口，就把「为什么没有」写在最显眼处 */
.locked-card { display: flex; flex-direction: column; gap: 8rpx; background-color: #F7F7F7; }
.locked-text { font-size: 24rpx; color: #666666; line-height: 1.6; }

/* ==================== 4.1 表单行（形状取自母版 dish-edit.vue 的「2. 表单行」样式段，类名换 ap- 前缀） ==================== */
.form-card { margin-top: 24rpx; padding: 8rpx 24rpx 24rpx; display: flex; flex-direction: column; }
.block-title { font-size: 30rpx; font-weight: 700; color: #333333; line-height: 1.4; }
.form-lead { font-size: 22rpx; color: #E02020; line-height: 1.5; }
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
.field-textarea {
  width: 100%; height: 120rpx; box-sizing: border-box;
  background-color: #F5F5F5; border-radius: 12rpx;
  padding: 16rpx 20rpx; font-size: 26rpx; color: #333333; line-height: 1.6;
}
.field-hint { font-size: 22rpx; color: #BBBBBB; line-height: 1.5; }
.field-error {
  box-shadow: inset 0 0 0 2rpx #E02020;
  border-radius: 12rpx;
  background-color: #FFF7F0;
}
/* 坐标行头：定位按钮与「坐标」标签同排，按下态与禁用态都在 pill 自己身上 */
.coord-head { display: flex; align-items: center; gap: 16rpx; padding-top: 22rpx; }
.coord-label { width: auto; font-weight: 600; }
.pill {
  font-size: 24rpx; font-weight: 600; color: #E02020; line-height: 1.4;
  background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 999rpx;
  padding: 10rpx 24rpx;
}
.pill-hover { opacity: .82; }
.pill-disabled { color: #BBBBBB; border-color: #EEEEEE; }

/* ==================== 4.2 提交卡 ==================== */
.submit-card { margin-top: 24rpx; display: flex; flex-direction: column; gap: 12rpx; }
/* 常驻反馈四支：成功=主红底白字，失败=主红底，冷却=橙红（母版 verify.vue 的 verify-feedback 那一族同一档，F-25/P-4 重测） */
.apply-feedback { font-size: 28rpx; font-weight: 600; line-height: 1.5; padding: 12rpx 16rpx; border-radius: 12rpx; }
.apply-feedback-ok { color: #FFFFFF; background-color: #E02020; }
.apply-feedback-neutral { color: #666666; background-color: #F5F5F5; }
.apply-feedback-err { color: #E02020; background-color: #FFF1F0; }
.apply-feedback-cooling { color: #E02020; background-color: #FFF7F0; }

.page-bottom-space { height: 60rpx; }
</style>
