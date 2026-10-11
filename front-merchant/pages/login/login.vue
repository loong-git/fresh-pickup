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

    <!-- ==================== 3. 「暂无商家权限」引导态（me 回 403）
             不跳转、不当登录失效：账号本身是登录成功的，只是角色不是商家，
             踢回登录页或跳工作台都是错的（§3.2 三分支的第二支） ==================== -->
    <view v-else-if="forbidden" class="card guide-card">
      <text class="guide-title">暂无商家权限</text>
      <text class="guide-text">
        账号<text v-if="forbiddenPhone" class="guide-strong"> {{ forbiddenPhone }} </text>已登录，但不是商家账号
      </text>
      <text class="guide-text">商家资质由平台统一开通，需平台代开通商家后用该手机号重新登录</text>
      <!-- 后端 403 的 message 原样透出一行（前端不改写、不猜原因），排查时能一眼看出是拦截器还是 Service 判空 -->
      <text v-if="forbiddenMessage" class="guide-tip">{{ forbiddenMessage }}</text>
      <button class="btn-main" @click="switchAccount"><text>换个账号</text></button>
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
          ><text>{{ codeSending ? '发送中…' : (smsCountdown > 0 ? smsCountdown + 's后重发' : '获取验证码') }}</text></button>
        </view>
        <text class="form-hint">开发环境验证码固定为 123456</text>
        <button class="btn-main" :class="{ 'btn-disabled': smsLogging }" :disabled="smsLogging" @click="loginBySms"><text>{{ smsLogging ? '登录中…' : '登录' }}</text></button>
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
        <button class="btn-main" :class="{ 'btn-disabled': pwdLogging }" :disabled="pwdLogging" @click="loginWithPassword"><text>{{ pwdLogging ? '登录中…' : '登录' }}</text></button>
        <text class="form-hint form-hint-loose">未设置过密码的账号请用验证码登录</text>
      </view>
    </template>
  </view>
</template>

<script>
// 商家端登录页：改造自 front/pages/login/login.vue（双 tab 验证码/密码 + 多多红胶囊表单手感保留）。
// C 端专属逻辑全部删除：邀请码横幅与 inviteCode 透传、头像入 store、设置密码卡区（/api/auth/set-password
// 不在本期商家端接口清单内，不引）、redirect 回跳（本端只两页，登录后唯一去处是工作台且用 reLaunch，无回跳语义）。
// 本页的核心改造是「登录成功 ≠ 进工作台」：拿到 token 后必须先 GET /api/merchant/me 判角色，
// 200/403/401 三分支各走各的（§3.2），而 403 与 401 的可区分性由 api 层 handleEnvelope 保证。
import { sendCode, login, loginPassword, getMerchantMe } from '@/api/index.js'
import '@/styles/common.css'

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
      smsLogging: false,
      // 密码登录表单
      pwdPhone: '',
      pwdPassword: '',
      pwdLogging: false,
      // 登录态复查中（onLoad 时 storage 有 token 才为 true，期间表单区整块隐藏）
      checking: false,
      // 「暂无商家权限」引导态（me 回 403）：message 为后端原文透出不改写
      forbidden: false,
      forbiddenPhone: '',
      forbiddenMessage: ''
    }
  },
  onLoad() {
    // 商家端无游客态：本地已有 token 时先静默复查（能进工作台就直接进，403 就渲染引导态），
    // 否则用户每次冷启都要重新收验证码
    if (this.$store.state.token) this.restoreSession()
  },
  onUnload() {
    // 验证码倒计时定时器清理（照用户端登录页同款处理）
    this.stopSmsCountdown()
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
        this.startSmsCountdown()
      } catch (e) {
        this.showToast((e && e.message) || '网络异常')
      } finally {
        this.codeSending = false
      }
    },
    // 60s 倒计时：期间「获取验证码」置灰防重
    startSmsCountdown() {
      this.stopSmsCountdown()
      this.smsCountdown = 60
      this.smsTimer = setInterval(() => {
        this.smsCountdown--
        if (this.smsCountdown <= 0) this.stopSmsCountdown()
      }, 1000)
    },
    stopSmsCountdown() {
      if (this.smsTimer) { clearInterval(this.smsTimer); this.smsTimer = null }
      this.smsCountdown = 0
    },
    // 验证码登录：POST /api/auth/login 只负责拿 token，角色判定交 afterToken 里的 me（三分支见那处注释）
    async loginBySms() {
      if (this.smsLogging) return
      const phone = (this.smsPhone || '').trim()
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      const code = (this.smsCode || '').trim()
      if (!code) { this.showToast('请输入验证码'); return }
      this.smsLogging = true
      try {
        const res = await login(phone, code)
        const data = (res && res.data) || {}
        this.smsLogging = false
        if (!data.token) { this.showToast('登录异常，请重试'); return }
        await this.afterToken(data.token, data.user && data.user.phone ? data.user.phone : phone)
      } catch (e) {
        this.smsLogging = false
        this.showToast((e && e.message) || '网络异常')
      }
    },
    // 密码登录：POST /api/auth/login-password（未设密码时后端 code:400 且 message 人话透出，直接 toast）
    async loginWithPassword() {
      if (this.pwdLogging) return
      const phone = (this.pwdPhone || '').trim()
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      const password = this.pwdPassword || ''
      if (password.length < 6 || password.length > 20) { this.showToast('请输入6-20位密码'); return }
      this.pwdLogging = true
      try {
        const res = await loginPassword(phone, password)
        const data = (res && res.data) || {}
        this.pwdLogging = false
        if (!data.token) { this.showToast('登录异常，请重试'); return }
        await this.afterToken(data.token, data.user && data.user.phone ? data.user.phone : phone)
      } catch (e) {
        this.pwdLogging = false
        this.showToast((e && e.message) || '网络异常')
      }
    },
    // ==================== 拿到 token 后的角色判定（§3.2 三分支唯一落点） ====================
    // token 必须先落 store（请求层从 storage 读它注入 Bearer 头），再拉 me：
    // - 200 且 role='merchant' → 存 me 快照 → reLaunch 工作台
    // - 403（已登录非商家 / 商家无归属）→ 渲染引导态并透出后端 message，停在登录页，不跳工作台也不清登录态
    // - 401（token 失效）→ 请求层已清 merchant_token，本页停留让用户重新登录
    async afterToken(token, phone) {
      this.$store.commit('setToken', token)
      try {
        const res = await getMerchantMe()
        const me = (res && res.data) || {}
        // 后端 me 出参的 role 是拦截器裁决后回的常量 'merchant'（MerchantServiceImpl.java:60），
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
    // 403 引导态渲染（token 保留：这是「账号能登但没商家权限」，不是登录失效）
    showForbidden(phone, message) {
      this.checking = false
      this.forbidden = true
      this.forbiddenPhone = phone || ''
      this.forbiddenMessage = (typeof message === 'string' ? message : '').trim()
    },
    // 换个账号：清登录态 + 复位表单（403 保留的 token 在这里才清，避免下一个账号串上一账号的态）
    switchAccount() {
      this.$store.commit('clearToken')
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
          this.showForbidden('', '账号已登录，但未绑定商家主体')
        }
      } catch (e) {
        if (e && e.code === 403) {
          this.showForbidden('', e.message)
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
