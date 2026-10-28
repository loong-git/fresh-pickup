<template>
  <view class="login-page">
    <!-- ==================== 1. 顶部品牌区 ==================== -->
    <view class="brand-area">
      <text class="brand-emoji">🏪</text>
      <text class="brand-title">生鲜商家端</text>
      <text class="brand-sub">登录后进入商家工作台</text>
    </view>

    <!-- ==================== 2. 登录态静默复查中（storage 有 merchant_token 时先确认还能不能用，
             避免已登录的商家每次都要重新收一次验证码） ==================== -->
    <view v-if="checking" class="card checking-card">
      <text class="checking-text">正在校验登录状态…</text>
    </view>

    <!-- ==================== 3. 「访问受限」引导态（me 回 403）
             D1/M1：未开通 / 已停业 / 被封禁（F-122 封禁也回这同一枚 403），登录页无从分辨是哪一种，
             故标题只说到「访问受限」、说明只列举可能，不把某一种原因当成事实报给用户。
             不跳转、不当登录失效：账号本身是登录成功的，踢回登录页或跳工作台都是错的（§3.2 三分支的第二支） ==================== -->
    <view v-else-if="forbidden" class="card guide-card">
      <text class="guide-title">访问受限</text>
      <!-- 号码行内 v-if：老状态 storage 无 merchant_login_phone 时 forbiddenPhone 为空，句子退成「当前账号可能没有商家权限…」仍通顺 -->
      <text class="guide-text">
        当前账号<text v-if="forbiddenPhone" class="guide-strong"> {{ forbiddenPhone }} </text>可能没有商家权限（未开通 / 已停业 / 被限制），工作台不对其开放
      </text>
      <!-- 后端 403 的 message 原样透出一行（前端不改写、不猜原因），排查时能一眼看出是拦截器还是 Service 判空 -->
      <text v-if="forbiddenMessage" class="guide-tip">{{ forbiddenMessage }}</text>
      <button class="btn-main" @click="switchAccount"><text>换个账号</text></button>
      <!-- token 还在（403 不清态）：刚被开通的商家点重新检查只重发一次 me，不必走换账号清态重收验证码 -->
      <text class="guide-link" @click="restoreSession">我已开通，重新检查</text>
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
        <text class="form-hint form-hint-loose">未注册手机号验证通过后将自动注册（注册后仍需平台开通商家资质才能进工作台）</text>
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
// 商家端登录页：改造自 front/pages/login/login.vue（双 tab 验证码/密码 + 多多红胶囊表单手感保留）。
// C 端专属逻辑全部删除：邀请码横幅与 inviteCode 透传、头像入 store、设置密码卡区（/api/auth/set-password
// 不在本期商家端接口清单内，不引）、redirect 回跳（G-02 起本端已有四页 login·workspace·dishes·dish-edit，但登录成功的唯一去处仍是 reLaunch 工作台、编辑页只由列表页带 ?id= 进入，没有「登录后回来源页」要还原，故仍无回跳语义）。
// 本页的核心改造是「登录成功 ≠ 进工作台」：拿到 token 后必须先 GET /api/merchant/me 判角色，
// 200/403/401 三分支各走各的（§3.2），而 403 与 401 的可区分性由 api 层 handleEnvelope 保证。
import { sendCode, login, loginPassword, getMerchantMe, retrySecondsOf, cooldownPillText, resendPillText } from '@/api/index.js'
import '@/styles/common.css'

// F-23-fix/FF-5：登录按钮冷却的回落秒数。口径按 api 层 retrySecondsOf(err, fallbackSeconds) 的约定——
// 回落值取该端点 @RateLimit 的 windowSeconds（POST /api/auth/login 与 /login-password 都是
// IP 维度 5 次 / 60 秒，AuthController.java:45/:53），它是「后端不给数时唯一确定的保守上限」而非回速。
// 本行不复制页内私有实现：front-merchant/api/index.js 导出的 retrySecondsOf 是本端唯一实现处（同文件 :215 明文
// 「页面 import 使用，不在各页复制私有版本」，dashboard/dishes/marketing/pickup-plan/plan-orders 五页同此），
// 故商家端不存在（也不新增）partners 侧那种私有副本一致性债。
const LOGIN_LOCK_SECONDS = 60

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
      // FE-5 锚点化：发码闸门的墙上时钟到期锚点（ms）——闸门真值改存这里，smsCountdown 降级为
      // 「每秒由锚点现算出来的读数」，判据名一个都不改（发码按钮判定、smsResendPillText 仍读这枚派生值）。
      // 改前是 setInterval 里 -- 自减：H5 后台标签页会把 interval 压到远慢于 1 秒，回到前台时屏上的数
      // 比真实剩余少。形状照 front-partner/pages/verify/verify.vue 已验收版（FE-5 契约的形状唯一真源），
      // 本页不另起第三套闸门抽象；锚点秒数仍只来自调用方——成功支本端 1 分钟 1 码的节奏（缺省 60）、
      // 429 支 api 层 retrySecondsOf 转述的后端真值（下方 D2/M6 纪律一字未动），页内零推算时长。
      // stop*/onUnload 成对归零：残留值会让下一实例读到上一实例的数字。
      smsCountdownUntil: 0,
      // 启动本轮倒计时的手机号：后端发码限流按号码维度（AuthServiceImpl.java:101-116），
      // 输入框换号时据此判定是否该收掉上一个号码的冷却
      smsCooldownPhone: '',
      smsLogging: false,
      // 登录按钮 60s 冷却（429 是 IP 维度限流，AuthController.java:45/:53，两个 tab 的登录按钮共用这一计时）
      loginLock: 0,
      loginLockTimer: null,
      // FE-5 锚点化：上面这枚闸门的墙上时钟到期锚点（ms）——闸门真值改存这里，loginLock 降级为
      // 「每秒由锚点现算出来的读数」，判据名一个都不改（两支登录按钮判定、loginLockPillText 仍读这枚派生值）。
      // 改前是 setInterval 里 -- 自减：H5 后台标签页会把 interval 压到远慢于 1 秒，回到前台时屏上的数
      // 比真实剩余少。形状照 front-partner/pages/verify/verify.vue 已验收版（FE-5 契约的形状唯一真源），
      // 本页不另起第三套闸门抽象；锚点秒数仍只由 api 层 retrySecondsOf 转述的后端秒数换算，页内零推算时长。
      // stop*/onUnload 成对归零：残留值会让下一实例读到上一实例的数字。
      loginLockUntil: 0,
      // 密码登录表单
      pwdPhone: '',
      pwdPassword: '',
      pwdLogging: false,
      // 登录态复查中（onLoad 时 storage 有 token 才为 true，期间表单区整块隐藏）
      checking: false,
      // 「访问受限」引导态（me 回 403）：message 为后端原文透出不改写
      forbidden: false,
      forbiddenPhone: '',
      forbiddenMessage: ''
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
  // 本页有两把互不相干的锁同屏（发码倒计时跟手机号/IP 的发码限流，登录冷却跟登录接口的 IP 限流），
  // 两枚数各自写在**自己那一枚按钮**上，控件本身就是归因（X-5「能看出报的是哪把」），
  // 所以这里用 api 层的短式而不铺「X请求太频繁」整句——验证码按钮置灰时文案若是「后可重试」会说错事，
  // 它报的是「什么时候能再要一条码」，故走 resendPillText（动词「后重发」）；登录那枚走 cooldownPillText。
  // 时长数值：smsCountdown 成功支仍是 1 分钟 1 码的业务节奏（60），429 支自 D2/M6 起改由
  // retrySecondsOf 转述后端真值（发码锁到 600 就报 600）；loginLock 仍是 retrySecondsOf 转述的后端真值
  computed: {
    smsResendPillText() {
      return resendPillText(this.smsCountdown)
    },
    loginLockPillText() {
      return cooldownPillText(this.loginLock)
    }
  },
  onLoad() {
    // 商家端无游客态：本地已有 token 时先静默复查（能进工作台就直接进，403 就渲染引导态），
    // 否则用户每次冷启都要重新收验证码
    if (this.$store.state.token) this.restoreSession()
  },
  onShow() {
    // FE-5：回前台先按墙上时钟重算一次派生读数，再让下面各处闸门判它（verify.vue 的 onShow 首行口径）。
    // 隐藏标签页里 setInterval 被节流（可低到 1 次/分），锚点一直是准的，但屏上的数会停在最后一次
    // tick 算出的旧值——不重算就把「已经到期的锁」继续显示成「还在冷却」。本页 onShow 不发请求，
    // 重算只服务两枚按钮的判定与屏上读数
    this.loginLock = this.loginLockRemaining()
    this.smsCountdown = this.smsCountdownRemaining()
  },
  onUnload() {
    // 验证码倒计时定时器清理（照用户端登录页同款处理）
    this.stopSmsCountdown()
    this.stopLoginLock()
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
    // 发送验证码：POST /api/auth/send-code，发码成功后 60s 倒计时防重（dev 后端固定验证码 123456）；
    // 429 时改按后端 data.retryAfterSeconds 起表（D2/M6，见下方 catch）
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
        // 429（IP 或手机号维度限流，后端提示「1 分钟后再试」类原文已 toast 透出）：前端同步进入倒计时，
        // 否则按钮立刻可再点，用户只会连续撞限流；非 429 分支维持原行为（不启动倒计时）
        // D2/M6：原样无参调 startSmsCountdown() 会把后端的 600 秒锁定印成 60 秒，用户 60 秒后又去撞锁；
        // 秒数改由 retrySecondsOf 转述 429 data.retryAfterSeconds，取不到才回落本端发码节奏 60
        if (e && e.code === 429) {
          this.startSmsCountdown(retrySecondsOf(e, 60))
          this.smsCooldownPhone = phone
        }
      } finally {
        this.codeSending = false
      }
    },
    // 发码倒计时：期间「获取验证码」置灰防重。
    // D2/M6：seconds 由调用方按场景给——发码成功走本端 1 分钟 1 码的节奏（缺省 60），
    // 429 走 retrySecondsOf 转述的后端真值（发码锁 600 就倒 600），非正数/漏传一律回落 60
    startSmsCountdown(seconds) {
      this.stopSmsCountdown()
      // FE-5 锚点化：闸门真值写墙上时钟锚点，smsCountdown 只是立刻取一次的派生读数
      // （形状照 verify.vue 的 startRetryLock；成功支缺省 60 与 429 支 retrySecondsOf 的来源纪律原样保留）
      this.smsCountdownUntil = Date.now() + (Number(seconds) > 0 ? Number(seconds) : 60) * 1000
      this.smsCountdown = this.smsCountdownRemaining()
      this.smsTimer = setInterval(() => {
        // tick 只换算派生值，不再有 -- 自减：后台标签页把 interval 压慢时，回来那一次 tick
        // 直接从墙上时钟重算，读数与真实剩余一致（改前是「少了几十秒」的那一类偏差）
        this.smsCountdown = this.smsCountdownRemaining()
        if (this.smsCountdown <= 0) this.stopSmsCountdown()
      }, 1000)
    },
    // 发码锚点 → 剩余秒数：只从墙上时钟现算，到期回 0（0 同时是发码按钮与胶囊文案隐掉冷却态的判据）。
    // 屏上的数、按钮的判定、smsResendPillText 用的是同一个来源，不存在「两处数互相矛盾」
    smsCountdownRemaining() {
      if (!(this.smsCountdownUntil > 0)) return 0
      return Math.max(0, Math.ceil((this.smsCountdownUntil - Date.now()) / 1000))
    },
    stopSmsCountdown() {
      if (this.smsTimer) { clearInterval(this.smsTimer); this.smsTimer = null }
      // FE-5：锚点与读数成对归零（残值会让下一实例的闸门/读数从上一实例那里读出来）
      this.smsCountdownUntil = 0
      this.smsCountdown = 0
      this.smsCooldownPhone = ''
    },
    // 登录按钮冷却：与验证码倒计时分表计时（一个跟手机号/IP 的发码限流走，一个跟登录接口的 IP 限流走）
    // F-23-fix/FF-5：秒数不再裸写 60，改由 retrySecondsOf(err, LOGIN_LOCK_SECONDS) 转述后端
    // 429 data.retryAfterSeconds，取不到才回落 60；闸门判定 loginLock > 0 未动
    startLoginLock(err) {
      this.stopLoginLock()
      // FE-5 锚点化：闸门真值写墙上时钟锚点（形状照 verify.vue 的 startRetryLock），loginLock 只是
      // 立刻取一次的派生读数；秒数唯一出处仍是 api 层 retrySecondsOf（上面 F-23-fix/FF-5 那条纪律一字未动），页内零推算时长
      this.loginLockUntil = Date.now() + retrySecondsOf(err, LOGIN_LOCK_SECONDS) * 1000
      this.loginLock = this.loginLockRemaining()
      this.loginLockTimer = setInterval(() => {
        // tick 只换算派生值，不再有 -- 自减：后台标签页把 interval 压慢时，回来那一次 tick
        // 直接从墙上时钟重算，读数与真实剩余一致（改前是「少了几十秒」的那一类偏差）
        this.loginLock = this.loginLockRemaining()
        if (this.loginLock <= 0) this.stopLoginLock()
      }, 1000)
    },
    // 登录锚点 → 剩余秒数：只从墙上时钟现算，到期回 0（0 同时是两支登录按钮与胶囊文案隐掉冷却态的判据）。
    // 屏上的数、按钮的判定、loginLockPillText 用的是同一个来源，不存在「两处数互相矛盾」
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
    // 验证码登录：POST /api/auth/login 只负责拿 token，角色判定交 afterToken 里的 me（三分支见那处注释）
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
        // D3/M20：复位必须压在 finally——放在 await afterToken(...) 之前会在「token 已到手、me 还在飞」那段
        // 把按钮提前放开，同帧再点就会发出第二次 login；afterToken 成功跳转的语义不变（reLaunch 后本页即卸载）
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
        // D3/M20：与验证码通道同款——复位压在 finally，afterToken 在飞期间按钮保持禁用，双击只发一次请求
        this.pwdLogging = false
      }
    },
    // ==================== 拿到 token 后的角色判定（§3.2 三分支唯一落点） ====================
    // token 必须先落 store（请求层从 storage 读它注入 Bearer 头），再拉 me：
    // - 200 且 role='merchant' → 存 me 快照 → reLaunch 工作台
    // - 403（可能受限：未开通 / 停业 / 被封禁）→ 渲染引导态并透出后端 message，停在登录页，不跳工作台也不清登录态
    // - 401（token 失效）→ 请求层已清 merchant_token，本页停留让用户重新登录
    async afterToken(token, phone) {
      this.$store.commit('setToken', token)
      // 登录身份（手机号）与 token 同点持久化：冷启动 403 时引导文案要有可指代的账号（P1-3）
      this.$store.commit('setLoginPhone', phone || '')
      try {
        const res = await getMerchantMe()
        const me = (res && res.data) || {}
        // 后端 me 出参的 role 是拦截器裁决后回的常量 'merchant'（MerchantServiceImpl#getMe 里的 me.put("role")），
        // 这里再判一次纯属防契约漂移的兜底：不是 merchant 就绝不放行进工作台
        if (me.role !== 'merchant') {
          this.showForbidden(phone, '账号已登录，但未绑定商家主体')
          return
        }
        this.$store.commit('setMerchant', me)
        uni.showToast({ title: '登录成功', icon: 'success' })
        uni.reLaunch({ url: '/pages/workspace/workspace' })
      } catch (e) {
        if (e && e.code === 403) {
          this.showForbidden(phone, e.message)
        } else if (e && e.code === 401) {
          // 登录刚拿到的 token 立刻被判 401（后端拒绝/时间异常）：清态并留在本页重新登录
          this.$store.commit('clearToken')
          this.showToast('登录状态已失效，请重新登录')
        } else {
          // 网络异常/500 等非权限类失败：不清 token（用户可停在表单重试，不必重收验证码）
          this.showToast((e && e.message) || '网络异常')
        }
      }
    },
    // 403 引导态渲染（token 保留：这是「账号能登、但可能没有商家权限」，不是登录失效）
    showForbidden(phone, message) {
      this.checking = false
      this.forbidden = true
      this.forbiddenPhone = phone || ''
      this.forbiddenMessage = (typeof message === 'string' ? message : '').trim()
    },
    // 换个账号：清登录态 + 复位表单（403 保留的 token 在这里才清，避免下一个账号串上一账号的态）
    switchAccount() {
      this.$store.commit('clearToken')
      // 发码倒计时按手机号维度计时（AuthServiceImpl.java:101-116），换号本可立即发码，不能拿上一个号码的计时拦新号；
      // loginLock 是 IP 维度限流、与账号无关，故保留不清（清了也只是让用户再撞一次 429）
      this.stopSmsCountdown()
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
    async restoreSession() {
      this.checking = true
      try {
        const res = await getMerchantMe()
        const me = (res && res.data) || {}
        if (me.role === 'merchant') {
          this.$store.commit('setMerchant', me)
          uni.reLaunch({ url: '/pages/workspace/workspace' })
        } else {
          // 冷启动复查走到这里没有表单输入可用，账号一律取持久化的登录身份（老状态无此键时为空，文案自适配）
          this.showForbidden(this.$store.state.loginPhone, '账号已登录，但未绑定商家主体')
        }
      } catch (e) {
        if (e && e.code === 403) {
          this.showForbidden(this.$store.state.loginPhone, e.message)
        }
        // 401 由请求层清态（本页就是登录页，请求层不会再跳）；其余失败按 toast 处理，
        // 两种情况都照常收起 checking 让用户看到表单，不卡死在「校验中」
        else if (e && e.code !== 401) {
          this.showToast((e && e.message) || '网络异常')
        }
      } finally {
        this.checking = false
      }
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 页面级滚轮兜底：手动驱动 body 滚动（照用户端登录页同款方案）；本页无弹层，无需弹层放行守卫
    onPageWheel(e) {
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* 多多红单色板：白底登录页 + 主红 #E02020（表单手感照用户端登录页，红值与 common.css 同源） */
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
