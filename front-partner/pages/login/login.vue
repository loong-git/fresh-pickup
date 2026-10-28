<template>
  <view class="login-page">
    <!-- ==================== 1. 顶部品牌区 ==================== -->
    <view class="brand-area">
      <text class="brand-emoji">🧺</text>
      <text class="brand-title">生鲜主理人端</text>
      <text class="brand-sub">登录后进入主理人工作台</text>
    </view>

    <!-- ==================== 2. 登录态静默复查中（storage 有 partner_token 时先确认还能不能用，
             避免已绑店主理人每次冷启都要重新收一次验证码） ==================== -->
    <view v-if="checking" class="card checking-card">
      <text class="checking-text">正在校验登录状态…</text>
    </view>

    <!-- ==================== 3. 「访问受限」引导态（stores 回 403；D1 口径）
             403 只说明「这个账号此刻进不了工作台」，前端不断言具体原因：归属集合为空、账号未开通、
             门店已停业、账号被限制（F-122 封禁也回同一枚 403——AuthInterceptor 只回 MSG_FORBIDDEN，
             只有登录接口才开始区分封禁）都落这一态，故文案只列举可能，把判定交回用户与平台。
             主理人身份不占 user.role，而是 store.owner_user_id 的归属集合（AuthInterceptor.java 的 ATTR_PARTNER_STORE_IDS）。
             账号本身是登录成功的，故不清登录态、不跳工作台，给两条明确去向：
             ①「去申请入驻」（pages/apply/apply 已注册于 pages.json:34，真 navigateTo 过去）
             ②「重新检查」（平台刚给这个账号绑店/恢复状态后自救，不用重收验证码）——它带 429 秒级冷却 ==================== -->
    <view v-else-if="forbidden" class="card guide-card">
      <text class="guide-title">访问受限</text>
      <text class="guide-text">
        当前账号<text v-if="forbiddenPhone" class="guide-strong"> {{ forbiddenPhone }} </text>可能没有门店归属或账号受限（未开通 / 已停业 / 被限制），请核对账号状态或联系平台
      </text>
      <!-- 老状态 storage 无 partner_login_phone 时 forbiddenPhone 为空：这一行不能出现「该手机号」空指代，故按有无号码分两个表述 -->
      <text v-if="forbiddenPhone" class="guide-text">可以先提交入驻申请，也可以让平台把已有自提点绑定到该手机号，之后重新检查即可进入工作台</text>
      <text v-else class="guide-text">可以先提交入驻申请，也可以让平台把已有自提点绑定到该账号，之后重新检查即可进入工作台</text>
      <!-- 后端 403 的 message 原样透出一行（前端不改写、不猜原因），排查时能一眼看出是拦截器还是别的来源 -->
      <text v-if="forbiddenMessage" class="guide-tip">{{ forbiddenMessage }}</text>
      <button class="btn-main" @click="goStoreApply"><text>去申请入驻</text></button>
      <!-- 重试入口的唯一冷却处：一次「重新检查」就打一次 GET /api/partner/stores（USER 30 次/分），
           连点只会续限流桶，故 429 后给秒级冷却（照 dishes.vue 的 retryPillText 冷却态模板） -->
      <text v-if="!retryLock" class="guide-link" @click="restoreSession">我已开通，重新检查</text>
      <text v-else class="guide-link guide-link-cooling">{{ retryLock }}s后可重新检查</text>
      <text class="guide-link guide-link-plain" @click="switchAccount">换个账号</text>
    </view>

    <!-- ==================== 4. 登录表单（验证码 / 密码双通道） ==================== -->
    <template v-else>
      <view class="tabs">
        <view class="tab-item" @click="switchTab('sms')">
          <text class="tab-text" :class="{ 'tab-text-active': activeTab === 'sms' }">验证码登录</text>
          <view class="tab-underline" v-if="activeTab === 'sms'"></view>
        </view>
        <view class="tab-item" @click="switchTab('password')">
          <text class="tab-text" :class="{ 'tab-text-active': activeTab === 'password' }">密码登录</text>
          <view class="tab-underline" v-if="activeTab === 'password'"></view>
        </view>
      </view>

      <!-- 4.1 验证码登录 -->
      <view class="login-form" v-if="activeTab === 'sms'">
        <view class="input-capsule">
          <input
            class="capsule-input"
            v-model="smsPhone"
            type="number"
            maxlength="11"
            placeholder="请输入手机号码"
            placeholder-style="color:#BBBBBB;"
          />
        </view>
        <view class="input-capsule">
          <input
            class="capsule-input"
            v-model="smsCode"
            type="number"
            maxlength="6"
            placeholder="请输入验证码"
            placeholder-style="color:#BBBBBB;"
          />
          <button
            class="btn-send-code"
            :class="{ 'btn-send-disabled': codeSending || smsCountdown > 0 }"
            :disabled="codeSending || smsCountdown > 0"
            @click="sendSmsCode"
          ><text>{{ codeSending ? '发送中…' : (smsCountdown > 0 ? smsResendPillText : '获取验证码') }}</text></button>
        </view>
        <text class="form-hint">开发环境验证码固定为 123456</text>
        <button class="btn-main" :class="{ 'btn-disabled': smsLogging || loginLock > 0 }" :disabled="smsLogging || loginLock > 0" @click="loginBySms"><text>{{ smsLogging ? '登录中…' : (loginLock > 0 ? loginLockPillText : '登录') }}</text></button>
        <text class="form-hint form-hint-loose">未注册手机号验证通过后将自动注册（注册后可直接提交入驻申请；已由平台绑定自提点的账号才能进工作台）</text>
      </view>

      <!-- 4.2 密码登录 -->
      <view class="login-form" v-else>
        <view class="input-capsule">
          <input
            class="capsule-input"
            v-model="pwdPhone"
            type="number"
            maxlength="11"
            placeholder="请输入手机号码"
            placeholder-style="color:#BBBBBB;"
          />
        </view>
        <view class="input-capsule">
          <input
            class="capsule-input"
            v-model="pwdPassword"
            :password="true"
            maxlength="20"
            placeholder="请输入密码（6-20位）"
            placeholder-style="color:#BBBBBB;"
          />
        </view>
        <button class="btn-main" :class="{ 'btn-disabled': pwdLogging || loginLock > 0 }" :disabled="pwdLogging || loginLock > 0" @click="loginWithPassword"><text>{{ pwdLogging ? '登录中…' : (loginLock > 0 ? loginLockPillText : '登录') }}</text></button>
        <text class="form-hint form-hint-loose">未设置过密码的账号请用验证码登录</text>
      </view>
    </template>
  </view>
</template>

<script>
// 主理人端登录页（F-18.15 §4）：拷自 front-merchant/pages/login/login.vue 并只改「身份判定」这一处实质差异。
// 与商家端的差异清单（其余表单手感/倒计时/错误文案分流一律照母版）：
// ① 母版调 getMerchantMe()（两处调用），本页调 getPartnerStores()，且两处收敛进同一个
//    resolveIdentity()（§4 明令「判定函数与文案分流写在同一个 resolveIdentity() 里，别散到 onLoad/onLogin 两处」）；
// ② 403 的含义不同：商家端 403 = 角色不是 merchant 或账号被限制（F-122 封禁同回 403，只能等平台代开通）；
//    主理人端 403 = token 有效但进不了工作台——store.owner_user_id 归属集合为空（AuthInterceptor 的 /api/partner/ 分支）
//    与账号本身受限（未开通 / 已停业 / 被限制）回的是同一枚 MSG_FORBIDDEN，故文案只列举可能、不断言原因（D1）；
//    其中「无门店归属」这一支是用户自己能走通的路——所以引导态给「去申请入驻」主按钮，而不是只有一句「等平台开通」；
// ③ 429 多了 retryLock 冷却（「重新检查」是反复打 /api/partner/stores 的入口，USER 30 次/分）。
// C 端专属逻辑一律不引进来：邀请码横幅与 inviteCode 透传、头像入 store、设置密码卡区、syncPickupFromServer
// 之类的 C 端启动链（主理人端无「默认自提点」概念，C-7 明令）。
// redirect 回跳：母版本身就没实现（front-merchant/pages/login/login.vue:112 已注明「登录成功的唯一去处仍是 reLaunch 工作台」），
// 本页保持同款——契约 §4 把 redirect 列为「照母版」的一项，母版没有即不补，见本轮回报的契约疑点。
import { sendCode, login, loginPassword, getPartnerStores, retrySecondsOf, coolingText, cooldownPillText, resendPillText } from '@/api/index.js'
import '@/styles/common.css'

// 登录成功后的唯一去处（工作台）与入驻申请页路由。
// pages/apply/apply 已由 F-18.19 交付并在 pages.json 注册（第 34 行那条），所以这里是真 navigateTo，
// 跳过去就是申请表单页；写法与 workspace.vue 的同名方法 goStoreApply() 一致。
const WORKSPACE_PAGE = '/pages/workspace/workspace'
const APPLY_PAGE = '/pages/apply/apply'

// F-23/FP-3：429 冷却时长不再硬编码 60——后端 429 信封的 data.retryAfterSeconds 优先，取不到才回落本地 60。
// 回落值口径：它是本页所撞限流档（USER 维度 @RateLimit）的整窗长度，是「后端不给 data 时的保守上限」，
// 不是回速（回速远小于窗口），两个数不是一回事，别混成一个值。
const RETRY_FALLBACK_SECONDS = 60

// retrySecondsOf 已收敛到 api 层（F-25 路 1）：front-partner 内唯一实现是 front-partner/api/index.js 的 retrySecondsOf，
// 页内私有副本已删；本页档位由上面的 RETRY_FALLBACK_SECONDS 作为第二参传进去。
// 载荷来源：请求层 handleEnvelope 的 reject 带 data（front-partner/api/index.js:183-186 早已透传），
// 网络异常/信封不可解析时 data 为 null，走上面的本地回落。

export default {
  data() {
    return {
      activeTab: 'sms', // 当前 tab：'sms' 验证码登录 | 'password' 密码登录
      // 验证码登录表单
      smsPhone: '',
      smsCode: '',
      codeSending: false,
      smsCountdown: 0,
      smsTimer: null,
      // 启动本轮倒计时的手机号：后端发码限流按号码维度（AuthServiceImpl.java:106-113），
      // 输入框换号时据此判定是否该收掉上一个号码的冷却
      smsCooldownPhone: '',
      smsLogging: false,
      // 登录按钮 60s 冷却（429 是 IP 维度限流，AuthController.java:35/:45，两个 tab 的登录按钮共用这一计时）
      loginLock: 0,
      loginLockTimer: null,
      // FE-5（docs/四期前端交互规划/FE-5-移动端倒计时锚点化契约）：loginLockUntil 是上面这把闸门的
      // 墙上时钟锚点（ms）——闸门真值改存这里，loginLock 降级为「每秒由锚点现算出来的读数」，判据名一个
      // 都不改（模板禁用态与 loginLockPillText 仍读派生值）。改前是 setInterval 里 -- 自减：H5 后台标签页
      // 会把 interval 压到远慢于 1 秒，回到前台时屏上的数比真实剩余少。形状照 front-partner/pages/verify/verify.vue
      // 的 retryLockUntil + retryLockRemaining（P4-5 S2 已验收的那份设计），本页不另起第三套闸门抽象；
      // 锚点仍只由 api 层 retrySecondsOf 转述的后端秒数换算，页内零推算时长；stop*/onUnload 成对归零：
      // 残留值会让下一实例读到上一实例的数字。
      loginLockUntil: 0,
      // 密码登录表单
      pwdPhone: '',
      pwdPassword: '',
      pwdLogging: false,
      // 登录态复查中（onLoad 时 storage 有 token 才为 true，期间表单区整块隐藏）
      checking: false,
      // 「访问受限」引导态（stores 回 403，原因只列举不断言）：message 为后端原文透出不改写
      forbidden: false,
      forbiddenPhone: '',
      forbiddenMessage: '',
      // 「重新检查」的 429 冷却剩余秒数（GET /api/partner/stores 是 USER 30 次/分，RateLimit 默认 KeyType.USER）
      // 与 loginLock 分表计时：一个跟登录接口的 IP 维度限流走（与账号无关），一个跟身份判定的用户维度限流走
      // （换账号就换桶）——合并成一个会把两件事的冷却互相污染。落点与写法照 dishes.vue:187-188（data）/448（429 才起锁）/521（startRetryLock）。
      // F-23-fix/FF-6 + F-25/P-4：这串指针被商家端连续加行打断过两次（170-171/378/425 → 177-178/392/439），
      // 现值按 front-merchant/pages/dishes/dishes.vue 当前实况逐条重测：retryLock 在 data 的 187-188、
      // errorCode===429 才起锁在 448、startRetryLock 方法在 521。
      retryLock: 0,
      retryLockTimer: null,
      // FE-5（docs/四期前端交互规划/FE-5-移动端倒计时锚点化契约）：retryLockUntil 是上面这把闸门的
      // 墙上时钟锚点（ms）——口径与 loginLockUntil 同款：闸门真值改存这里，retryLock 降级为「每秒由锚点
      // 现算出来的读数」，判据名一个都不改（引导态闸门行「Ns后可重新检查」与 restoreSession 的早退判定
      // 仍读派生值）。改前是 setInterval 里 -- 自减：H5 后台标签页会把 interval 压到远慢于 1 秒，回到前台时
      // 屏上的数比真实剩余少。形状照 front-partner/pages/verify/verify.vue 的 retryLockUntil +
      // retryLockRemaining（P4-5 S2 已验收的那份设计），本页不另起第三套闸门抽象；锚点仍只由 api 层
      // retrySecondsOf 转述的后端秒数换算，页内零推算时长；两把锁的锚点各自独立（上面「与 loginLock
      // 分表计时」的边界不变），stop*/onUnload/switchAccount 成对归零：残留值会让下一实例读到上一实例的数字。
      retryLockUntil: 0
    }
  },
  watch: {
    // 后端发码限流按手机号维度，换号本可立即发码，不该继承上一个号码的冷却：
    // 一旦当前输入与启动倒计时的号码不一致，收掉倒计时让按钮回到可用态
    smsPhone(val) {
      if (this.smsCooldownPhone && (val || '').trim() !== this.smsCooldownPhone) {
        this.stopSmsCountdown()
      }
    }
  },
  onLoad() {
    // 主理人端无游客态：本地已有 token 时先静默复查（能进工作台就直接进，403 就渲染引导态），
    // 否则用户每次冷启都要重新收验证码
    if (this.$store.state.token) this.restoreSession()
  },
  onShow() {
    // FE-5（docs/四期前端交互规划/FE-5-移动端倒计时锚点化契约）：回前台先按墙上时钟锚点重算两把锁的
    // 派生读数，再让后面任何闸门判它（口径照 verify.vue:330-335 那段 onShow 首行重算）。隐藏标签页里
    // setInterval 被节流（可低到 1 次/分），锚点一直是准的，但屏上的数会停在最后一次 tick 算出的旧值——
    // 不重算就把「已经到期的锁」继续显示成「还在冷却」，也就会把本可以点的登录/重新检查无谓地挡住。
    // 这里刻意只做重算：本页 onShow 不发请求、无其他闸门判定，避免和 onLoad 的 restoreSession 重复。
    this.loginLock = this.loginLockRemaining()
    this.retryLock = this.retryLockRemaining()
  },
  onUnload() {
    // 三个计时器全部清理：验证码倒计时、登录按钮冷却、身份复查冷却
    // （H5 下 setInterval 挂在页面实例上，页面销毁不清就会带着上一页的秒数继续跑）
    this.stopSmsCountdown()
    this.stopLoginLock()
    this.stopRetryLock()
    // #ifdef H5
    // 页面级滚轮兜底监听清理（与下方 onReady 的注册配对）
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // tab 切换：仅切表单展示，不清已输入内容（验证码倒计时跨 tab 持续）
    switchTab(tab) {
      this.activeTab = tab === 'password' ? 'password' : 'sms'
    },
    // ==================== 验证码登录 ====================
    // 发送验证码：POST /api/auth/send-code，60s 倒计时防重（dev 后端固定验证码 123456）
    async sendSmsCode() {
      if (this.codeSending || this.smsCountdown > 0) return
      const phone = (this.smsPhone || '').trim()
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      this.codeSending = true
      try {
        await sendCode(phone)
        uni.showToast({ title: '验证码已发送', icon: 'none' })
        // 记号码要在 startSmsCountdown 之后：该函数首行会 stopSmsCountdown 清掉残留计时，先记会被抹掉
        this.startSmsCountdown()
        this.smsCooldownPhone = phone
      } catch (e) {
        this.showToast((e && e.message) || '网络异常')
        // 429（IP 或手机号维度限流，后端提示原文已 toast 透出）：前端同步进入倒计时，
        // 否则按钮立刻可再点，用户只会连续撞限流；非 429 分支维持原行为（不启动倒计时）。
        // D2（M6）：秒数不再写死——后端 600s 锁的信封带 data.retryAfterSeconds，转述它让屏上倒计时显示真实等待；
        // 取不到真值（60s 频控/日上限不带 data）才回落 60，与成功支同一个缺省。
        if (e && e.code === 429) {
          this.startSmsCountdown(retrySecondsOf(e, 60))
          this.smsCooldownPhone = phone
        }
      } finally {
        this.codeSending = false
      }
    },
    // 发码倒计时：期间「获取验证码」置灰防重。
    // D2（M6）：支持入参 seconds——429 分支传后端真值（缺省 60），屏上读数走 resendPillText（「Ns后重发」）。
    startSmsCountdown(seconds) {
      this.stopSmsCountdown()
      this.smsCountdown = (Number(seconds) > 0 ? Number(seconds) : 60)
      this.smsTimer = setInterval(() => {
        this.smsCountdown--
        if (this.smsCountdown <= 0) this.stopSmsCountdown()
      }, 1000)
    },
    stopSmsCountdown() {
      if (this.smsTimer) { clearInterval(this.smsTimer); this.smsTimer = null }
      this.smsCountdown = 0
      this.smsCooldownPhone = ''
    },
    // 登录按钮冷却：与验证码倒计时分表计时（一个跟手机号/IP 的发码限流走，一个跟登录接口的 IP 限流走）
    // F-23-fix/FF-5：秒数不再裸写 60，改由 retrySecondsOf(err, RETRY_FALLBACK_SECONDS) 转述后端 data.retryAfterSeconds
    // （POST /api/auth/login 与 /login-password 同档 @RateLimit(IP, limit=5, windowSeconds=60)，
    // AuthController.java:45/:53），取不到真值才回落页内 RETRY_FALLBACK_SECONDS=60。
    // 入参 err 由两个 429 分支透传，闸门判定仍是 loginLock > 0（未动）。
    startLoginLock(err) {
      this.stopLoginLock()
      // FE-5：闸门真值改存墙上时钟锚点 loginLockUntil，秒数只由它派生（形状照 verify.vue 的
      // 「retryLockUntil = Date.now() + retrySecondsOf(...) * 1000」+ 立刻取一次派生值）。
      // 秒数唯一出处仍是 api 层 retrySecondsOf（上面 F-23-fix/FF-5 那条纪律一字未动），页内零推算时长。
      this.loginLockUntil = Date.now() + retrySecondsOf(err, RETRY_FALLBACK_SECONDS) * 1000
      this.loginLock = this.loginLockRemaining()
      this.loginLockTimer = setInterval(() => {
        // tick 只换算派生值，不再有 -- 自减：后台标签页把 interval 压慢时，回来那一次 tick
        // 直接从墙上时钟重算，读数与真实剩余一致（改前是「少了几十秒」的那一类偏差）
        this.loginLock = this.loginLockRemaining()
        if (this.loginLock <= 0) this.stopLoginLock()
      }, 1000)
    },
    // 登录闸门锚点 → 剩余秒数：只从墙上时钟现算，到期回 0（0 同时是模板禁用态与两个 429 分支
    // 之外所有闸门隐掉冷却态的判据）。屏上的数、按钮的判定、loginLockPillText 用的是同一个来源，
    // 不存在「两处数互相矛盾」
    loginLockRemaining() {
      if (!(this.loginLockUntil > 0)) return 0
      return Math.max(0, Math.ceil((this.loginLockUntil - Date.now()) / 1000))
    },
    stopLoginLock() {
      if (this.loginLockTimer) { clearInterval(this.loginLockTimer); this.loginLockTimer = null }
      // FE-5：锚点与读数成对归零（残值会让下一实例的闸门/读数从上一实例那里读出来）
      this.loginLockUntil = 0
      this.loginLock = 0
    },
    // 「重新检查」的 429 冷却（照 login.vue 母版 loginLock 的秒级禁用范式 + dishes.vue 的 startRetryLock 命名）
    // F-23/FP-1：入参改为 catch 到的错误对象，起锁秒数由 retrySecondsOf 转述后端真值，取不到才回落本地 60
    startRetryLock(err) {
      this.stopRetryLock()
      // FE-5：与 loginLock 同一形状——闸门真值改存墙上时钟锚点 retryLockUntil，retryLock 只是派生读数，
      // 两把锁各挂各的桶（上面「与 loginLock 分表计时」的边界一字未动：这里只改「数是怎么算出来的」，
      // 不改「谁起谁停谁归零」）。秒数唯一出处仍是 api 层 retrySecondsOf（上面 F-23/FP-1 那条纪律一字未动），
      // 页内零推算时长。
      this.retryLockUntil = Date.now() + retrySecondsOf(err, RETRY_FALLBACK_SECONDS) * 1000
      this.retryLock = this.retryLockRemaining()
      this.retryLockTimer = setInterval(() => {
        // tick 只换算派生值，不再有 -- 自减：后台标签页把 interval 压慢时，回来那一次 tick
        // 直接从墙上时钟重算，读数与真实剩余一致（改前是「少了几十秒」的那一类偏差）
        this.retryLock = this.retryLockRemaining()
        if (this.retryLock <= 0) this.stopRetryLock()
      }, 1000)
    },
    // 「重新检查」闸门锚点 → 剩余秒数：只从墙上时钟现算，到期回 0（0 同时是引导态闸门行与
    // restoreSession 早退判定隐掉冷却态的判据）。屏上的数与两处判定用同一个来源，不存在「两处数互相矛盾」
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
    // 验证码登录：POST /api/auth/login 只负责拿 token，身份判定交 afterToken → resolveIdentity
    async loginBySms() {
      if (this.smsLogging || this.loginLock > 0) return
      const phone = (this.smsPhone || '').trim()
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      const code = (this.smsCode || '').trim()
      if (!code) { this.showToast('请输入验证码'); return }
      this.smsLogging = true
      try {
        const res = await login(phone, code)
        const data = (res && res.data) || {}
        if (!data.token) { this.showToast('登录异常，请重试'); return }
        await this.afterToken(data.token, data.user && data.user.phone ? data.user.phone : phone)
      } catch (e) {
        this.showToast((e && e.message) || '网络异常')
        // 429：后端 IP 维度 5 次/分（AuthController.java:45），toast 透出的即是「操作过于频繁，请稍后再试」人话原文，
        // 前端同步锁定登录按钮（F-23-fix/FF-5：秒数转述后端真值，e 必须透传给 startLoginLock），
        // 避免连点继续撞限流窗；非 429 分支行为不变
        if (e && e.code === 429) this.startLoginLock(e)
      } finally {
        // M20（D3）：复位必须在 afterToken 之后——afterToken 里还有一次 GET /api/partner/stores，
        // 在飞期间按钮若已解禁，同帧双击会再发一次登录请求。放 finally 覆盖成功/失败/异常三条路。
        this.smsLogging = false
      }
    },
    // 密码登录：POST /api/auth/login-password（未设密码时后端 code:400 且 message 人话透出，直接 toast）
    async loginWithPassword() {
      if (this.pwdLogging || this.loginLock > 0) return
      const phone = (this.pwdPhone || '').trim()
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      const password = this.pwdPassword || ''
      if (password.length < 6 || password.length > 20) { this.showToast('请输入6-20位密码'); return }
      this.pwdLogging = true
      try {
        const res = await loginPassword(phone, password)
        const data = (res && res.data) || {}
        if (!data.token) { this.showToast('登录异常，请重试'); return }
        await this.afterToken(data.token, data.user && data.user.phone ? data.user.phone : phone)
      } catch (e) {
        this.showToast((e && e.message) || '网络异常')
        // 429 同验证码通道：IP 维度限流不分登录方式，锁定的是两个 tab 共用的登录按钮
        // F-23-fix/FF-5：这里也要把 e 透传下去，否则 startLoginLock 收到 undefined、只能回落 60
        if (e && e.code === 429) this.startLoginLock(e)
      } finally {
        // M20（D3）：同验证码通道，afterToken 在飞期间保持禁用
        this.pwdLogging = false
      }
    },
    // ==================== 拿到 token 后落 store，再交唯一判定处 ====================
    // token 必须先落 store（请求层从 storage 读它注入 Bearer 头），再做身份判定。
    async afterToken(token, phone) {
      this.$store.commit('setToken', token)
      // 登录身份（手机号）与 token 同点持久化：冷启动 403 时引导文案要有可指代的账号（母版 P1-3 同款）
      this.$store.commit('setLoginPhone', phone || '')
      await this.resolveIdentity(phone)
    },
    // ==================== 身份判定与文案分流的唯一落点（§4：两处调用、一处判定） ====================
    // 调用方只有两个：afterToken（登录成功那一刻）与 restoreSession（冷启复查 / 引导态「重新检查」）。
    // GET /api/partner/stores 的四条分支（三分支 + 限流）：
    // - 200 且数组非空 → 有可用门店 → reLaunch 工作台（多店也进同一个工作台，切换归 Step 6/7）
    // - 200 但数组为空 → 后端集合为空时直接回 403（走不到这里），仍按「受限」渲染引导态、绝不放行：
    //   这是防契约漂移的兜底闸，与母版 front-merchant/pages/workspace/workspace.vue 的 loadMe 那道闸（管的是「已有 token 却拿到没有角色的空响应，也不当 ready 放行」）同口径（F-25/P-4 重测）
    // - 403（归属集合为空或账号受限，两种原因同一枚码）→ 渲染引导态 + 透出后端原文，停在登录页，不清登录态也不跳工作台
    // - 401（token 失效）→ 请求层 rejectUnauthorized 已清 partner_token 并回登录页，本页清态停留让用户重登
    // - 429（USER 30 次/分撞限流）→ 人话文案 + retryLock 秒级冷却，不当成「受限」误判
    // - 其余（网络异常/400/500）→ toast 透出后端原文，不清 token（用户可停在表单重试，不必重收验证码）
    async resolveIdentity(phone) {
      try {
        const res = await getPartnerStores()
        const list = (res && Array.isArray(res.data)) ? res.data : []
        if (!list.length) {
          // 兜底闸的文案也只说数据事实（响应里没有可用门店），不替账号状态下结论（D1）
          this.showForbidden(phone, '账号已登录，但未返回可用的自提点')
          return
        }
        uni.showToast({ title: '登录成功', icon: 'success' })
        uni.reLaunch({ url: WORKSPACE_PAGE })
      } catch (e) {
        const code = Number((e && e.code) || 0)
        if (code === 403) {
          this.showForbidden(phone, e.message)
          return
        }
        if (code === 401) {
          // 刚拿到的 token 立刻被判 401（后端拒绝/时间异常）：请求层已清态，这里同步清 store 并留在本页重新登录
          this.$store.commit('clearToken')
          this.showToast('登录状态已失效，请重新登录')
          return
        }
        if (code === 429) {
          // F-23-fix/FF-2：起锁先于 toast——这一顺序本批一步未动（startRetryLock 仍先把后端真值写进 this.retryLock）。
          // F-28.5 路 A（X-5 + X-8）：toast 改用 coolingText 无参整句。改前这里「点得太快了，请 N 秒后再刷新」是全端
          // 唯一一支中文「秒」制、也是同屏两处各报一次同一把读侧锁的源头（快照值与引导态闸门行的实时值，即 X-10 判过
          // 的那个「两个读数」）——现在本页读侧带数处只剩引导态闸门行「Ns后可重新检查」一处，toast 只说事不说数。
          // （商家端同类 toast 的数早被 FF-1 摘掉，本页这次跟齐同一纪律；归因与闸门判定逻辑零改动。）
          this.startRetryLock(e)
          this.showToast(coolingText())
          return
        }
        this.showToast((e && e.message) || '网络异常')
      }
    },
    // 403 引导态渲染（token 保留：这是「账号能登但当前受限」，不是登录失效）
    showForbidden(phone, message) {
      this.checking = false
      this.forbidden = true
      this.forbiddenPhone = phone || ''
      this.forbiddenMessage = (typeof message === 'string' ? message : '').trim()
    },
    // 「去申请入驻」：pages/apply/apply 已在 pages.json:34 注册（F-18.19 交付），这里是真跳转。
    // 引导态其余分支（保留 token、换账号）一律不动——本页 403 的语义是「登录成功、但工作台还不对其开放」，
    // 无门店归属那一支的下一步正是这张表单，跳过去比 toast 诚实；账号受限支同样可先提交/查看申请。
    goStoreApply() {
      uni.navigateTo({ url: APPLY_PAGE })
    },
    // 换个账号：清登录态 + 复位表单（403 保留的 token 在这里才清，避免下一个账号串上一账号的态）
    switchAccount() {
      this.$store.commit('clearToken')
      // 发码倒计时按手机号维度计时（AuthServiceImpl.java:106-113），换号本可立即发码，不能拿上一个号码的计时拦新号；
      // retryLock 是 stores 接口的 USER 维度限流（RateLimit KeyType.USER 默认），换账号即换桶，同样收起；
      // loginLock 是 IP 维度限流、与账号无关，故保留不清（清了也只是让用户再撞一次 429）
      this.stopSmsCountdown()
      this.stopRetryLock()
      this.forbidden = false
      this.forbiddenPhone = ''
      this.forbiddenMessage = ''
      this.smsPhone = ''
      this.smsCode = ''
      this.pwdPhone = ''
      this.pwdPassword = ''
      this.switchTab('sms')
    },
    // ==================== 本地 token 复查（已登录用户免重复登录） ====================
    // 判定本体不在这里写：只负责「置复查中 → 交 resolveIdentity → 收起复查中」，
    // 分支结论（进工作台 / 渲染引导态 / 清态 / 冷却）全部由 resolveIdentity 一家给。
    async restoreSession() {
      if (this.retryLock > 0) return // 冷却期内的兜底守卫：模板已置灰，这里防脚本级连点
      this.checking = true
      try {
        await this.resolveIdentity(this.$store.state.loginPhone)
      } finally {
        // 403 时 resolveIdentity 已在 showForbidden 里收起 checking；其余分支（工作台已在 reLaunch 路上、
        // 401/429/网络异常停在表单）在这里统一收起，不卡死在「校验中」
        this.checking = false
      }
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 页面级滚轮兜底：手动驱动 body 滚动（照母版/用户端登录页同款方案，一字不改）；本页无弹层，无需弹层放行守卫
    onPageWheel(e) {
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  },
  // 两枚按钮上的时长短式：句式生成在 api 层的 cooldownPillText / resendPillText，本轮把页内三处裸拼
  // （发码按钮的「Ns后重发」+ 两个 tab 登录按钮的「Ns后可重试」）收了进来。
  // 为什么是 computed 而不是让模板直接调 import 进来的函数：uni-app H5（Vue3）的模板表达式只在组件
  // 实例作用域求值，模块级 import 的名字在 <template> 里读不到——商家端登录页母版就是这套 computed 形状。
  // 为什么这个块放在 methods 之后（母版把它放在 watch 之后）：workspace 的 429 冷却段头注按方法名点名了本页
  // methods 里的 startRetryLock（实测它只点名、不带行号，谈不上「漂」，但插到 methods 之前会让那次点名在本页里跨块来回找）；Vue 不关心 options 键的先后顺序。
  // 数值一律不动：smsCountdown 仍是「1 分钟 1 码」的业务节奏，走 resendPillText（动词是重发不是重试）；
  // loginLock 仍是 retrySecondsOf 转述的后端真值。两把锁各写在自己那一枚按钮上，控件本身就是归因。
  // 读数变化只在越过 60 那一档：loginLock 回落到 60 时仍出「60s后可重试」（F-38.3 把分档线含 60，为的是与同屏模板短标签逐字同形）；
  // <60 时两个函数的输出与改前的裸拼逐字相同，发码按钮仍是「60s后重发」。
  computed: {
    smsResendPillText() {
      return resendPillText(this.smsCountdown)
    },
    loginLockPillText() {
      return cooldownPillText(this.loginLock)
    }
  }
}
</script>

<style scoped>
/* 多多红单色板：白底登录页 + 主红 #E02020（表单手感照母版，红值与 common.css 同源，不改主题变量） */
.login-page {
  min-height: 100vh;
  background-color: #FFFFFF;
  box-sizing: border-box;
  padding: 0 56rpx 60rpx;
}

/* ==================== 1. 顶部品牌区（有原生导航栏，留白比用户端登录页收一档） ==================== */
.brand-area {
  display: flex; flex-direction: column; align-items: center;
  padding-top: 88rpx;
}
.brand-emoji { font-size: 104rpx; line-height: 1; }
.brand-title { margin-top: 24rpx; font-size: 44rpx; font-weight: 700; color: #333333; line-height: 1; }
.brand-sub { margin-top: 18rpx; font-size: 26rpx; color: #999999; line-height: 1; }

/* ==================== 2. 复查中（.card 来自 common.css，此处只加本页间距） ==================== */
.checking-card { margin-top: 56rpx; display: flex; justify-content: center; }
.checking-text { font-size: 26rpx; color: #999999; line-height: 1.6; }

/* ==================== 3. 引导态卡（.guide-card/.guide-title/.guide-text/.btn-main 均来自 common.css） ==================== */
.guide-card { margin-top: 56rpx; }
/* 后端 403 原文透出的一行：小字灰红，与本页自撰文案区分开（避免用户以为是前端编的原因） */
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }
.guide-card .btn-main { margin-top: 16rpx; }
/* 429 冷却中的「重新检查」：同形状置灰不可点（点击已在模板里按 retryLock 分流） */
.guide-link-cooling { color: #CCCCCC; }
/* 换个账号是次级出口：不与「重新检查」抢视觉权重 */
.guide-link-plain { color: #999999; }

/* ==================== 4. tab 切换（红色下划线激活态） ==================== */
.tabs {
  display: flex; justify-content: center; gap: 96rpx;
  margin-top: 64rpx;
}
.tab-item {
  position: relative;
  display: flex; flex-direction: column; align-items: center;
  min-width: 160rpx; padding-bottom: 18rpx;
}
.tab-text { font-size: 30rpx; color: #666666; line-height: 1; }
.tab-text-active { color: #E02020; font-weight: 700; }
.tab-underline {
  position: absolute; bottom: 0; left: 50%; transform: translateX(-50%);
  width: 48rpx; height: 6rpx; border-radius: 6rpx;
  background-color: #E02020;
}

/* ==================== 5. 登录表单（圆角胶囊输入框） ==================== */
.login-form { margin-top: 48rpx; }
.input-capsule {
  display: flex; align-items: center;
  height: 92rpx; margin-bottom: 24rpx;
  background-color: #F5F5F5; border-radius: 999rpx;
  padding: 0 12rpx 0 36rpx; box-sizing: border-box;
}
.capsule-input {
  flex: 1; min-width: 0;
  height: 92rpx; line-height: 92rpx;
  font-size: 30rpx; color: #333333;
}
.btn-send-code {
  flex-shrink: 0; margin: 0; padding: 0 28rpx;
  height: 68rpx; line-height: 68rpx;
  background-color: #E02020; color: #FFFFFF;
  font-size: 24rpx; font-weight: 600;
  border: none; border-radius: 999rpx;
}
.btn-send-code.btn-send-disabled { background-color: #CCCCCC; color: rgba(255, 255, 255, 0.9); }
/* 本页主按钮补外边距（.btn-main 基类在 common.css，两页共用不带间距） */
.login-form .btn-main { margin-top: 40rpx; }
.form-hint {
  display: block;
  margin: 0 24rpx;
  font-size: 22rpx; color: #999999; line-height: 1.5;
}
/* 提示位于按钮下方时与上方拉开间距 */
.form-hint-loose { margin-top: 24rpx; }
</style>
