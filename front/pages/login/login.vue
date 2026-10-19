<template>
  <view class="login-page">
    <!-- ==================== 1. 顶部品牌区 ==================== -->
    <view class="brand-area">
      <text class="brand-emoji">🛒</text>
      <text class="brand-title">生鲜配送</text>
      <text class="brand-sub">下单前请先登录</text>
    </view>

    <!-- ==================== 0. 邀请横幅（F-01/F-05：链接带 invite 参数进入时展示；仅验证码登录提交时随请求携带） ==================== -->
    <view class="invite-banner" v-if="inviteCode">
      <text class="invite-banner-icon">🎁</text>
      <view class="invite-banner-texts">
        <text class="invite-banner-title">好友邀请你注册，去领券中心领新人见面礼</text>
        <text class="invite-banner-sub">已携带邀请码 {{ inviteCode }}，注册后到「领券中心」领取</text>
      </view>
    </view>

    <!-- ==================== 2. tab 切换（红色下划线激活态） ==================== -->
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

    <!-- ==================== 3. 验证码登录表单 ==================== -->
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
      <button class="btn-main" :class="{ 'btn-main-disabled': smsLogging }" :disabled="smsLogging" @click="loginBySms"><text>{{ smsLogging ? '登录中…' : '登录' }}</text></button>
      <text class="form-hint form-hint-loose">未注册手机号验证通过后将自动注册</text>
    </view>

    <!-- ==================== 4. 密码登录表单 ==================== -->
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
      <button class="btn-main" :class="{ 'btn-main-disabled': pwdLogging }" :disabled="pwdLogging" @click="loginWithPassword"><text>{{ pwdLogging ? '登录中…' : '登录' }}</text></button>
      <text class="form-hint form-hint-loose">还没有密码？先用验证码登录，之后到「我的 → 账号安全」设置</text>
      <text class="form-hint">忘记密码？登录后到「我的 → 账号安全」重新设置</text>
    </view>
  </view>
</template>

<script>
// 独立登录页（多多买菜风格）：验证码登录 / 密码登录双 tab。
// 登录成功写入登录态严格对照首页购物车弹层 loginAndPay 的 setUser commit 字段；
// 跳转遵循 FRONT_CONTRACT：带 redirect 参数（encodeURIComponent 后的页面路径）时解码回跳，否则 reLaunch 回首页。
import { sendCode, login, loginByPassword } from '@/api/index.js'

export default {
  data() {
    return {
      activeTab: 'sms', // 当前 tab：'sms' 验证码登录 | 'password' 密码登录
      redirect: '', // onLoad 传入的 redirect 原串，跳转时才解码（非法编码兜底回首页）
      inviteCode: '', // F-01：onLoad 传入的邀请码（邀请链接 ?invite=），仅验证码登录提交时透传给后端
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
      pwdLogging: false
    }
  },
  onLoad(options) {
    // redirect 为 encodeURIComponent 后的目标页面路径（如 %2Fpages%2Findex%2Findex），原串保存、跳转时解码
    if (options && typeof options.redirect === 'string' && options.redirect) {
      this.redirect = options.redirect
    }
    // F-01：邀请链接进入时读取邀请码展示横幅；后端契约 8 位去混淆码，trim 后截断 16 位防脏参数（无效码由后端登录时忽略不绑定）
    if (options && typeof options.invite === 'string' && options.invite.trim()) {
      this.inviteCode = options.invite.trim().slice(0, 16)
    }
    // 登录态早已存在、又是被「去登录」带着 redirect 进来的：目标页就是他要回去的地方，
    // 停在登录页只会被读成"刚才那下没生效"→ 直接回跳。
    // 不带 redirect 的情况仍停留：他本来就是来登录的，跳走反而把登录这一步丢了。
    const user = this.$store.state.user
    if (this.redirect && user && user.token) this.goAfterLogin()
  },
  onUnload() {
    // 登录验证码倒计时定时器清理（对照首页 onUnload 对内嵌登录区块的处理）
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
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照搜索页同款方案）
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
    // 60s 倒计时：期间"获取验证码"置灰防重
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
    // 验证码登录：POST /api/auth/login（F-01/F-05 新号注册时随请求透传 inviteCode 绑定邀请关系
    // 并冻结邀请人现金，新人券不再自动发放、由新人在领券中心领取；老用户携带无效邀请码由后端忽略，前端不区分）。
    // 登录成功后一律按 redirect 规则跳转。
    async loginBySms() {
      if (this.smsLogging) return
      const phone = (this.smsPhone || '').trim()
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      const code = (this.smsCode || '').trim()
      if (!code) { this.showToast('请输入验证码'); return }
      this.smsLogging = true
      try {
        const res = await login(phone, code, this.inviteCode || '')
        const data = (res && res.data) || {}
        this.$store.commit('setUser', {
          token: data.token || '',
          id: data.user && data.user.id != null ? data.user.id : null,
          phone: (data.user && data.user.phone) || phone,
          // R8 头像链路补全（轮1 找茬）：后端登录响应已回传 data.user.avatar，此前丢弃导致
          // 登录后「我的」页头像回落默认 SVG，须透传入 store（setUser {...DEFAULT_USER,...payload} 保底）
          avatar: (data.user && data.user.avatar) || null
        })
        this.smsLogging = false
        // 登录成功后一律直接跳转（用户 2026-10-18 定：不要弹去留询问）。
        uni.showToast({ title: '登录成功', icon: 'success' })
        this.goAfterLogin()
      } catch (e) {
        this.smsLogging = false
        this.showToast((e && e.message) || '网络异常')
      }
    },
    // ==================== 密码登录 ====================
    // 密码登录：POST /api/auth/login-password；密码错误/未设密码（"请先使用验证码登录"）由后端 code:400 message 透出
    async loginWithPassword() {
      if (this.pwdLogging) return
      const phone = (this.pwdPhone || '').trim()
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      const password = this.pwdPassword || ''
      if (password.length < 6 || password.length > 20) { this.showToast('请输入6-20位密码'); return }
      this.pwdLogging = true
      try {
        const res = await loginByPassword(phone, password)
        const data = (res && res.data) || {}
        this.$store.commit('setUser', {
          token: data.token || '',
          id: data.user && data.user.id != null ? data.user.id : null,
          phone: (data.user && data.user.phone) || phone,
          // R8 头像链路补全（轮1 找茬）：同验证码登录口径，透传后端已回传的 data.user.avatar
          avatar: (data.user && data.user.avatar) || null
        })
        this.pwdLogging = false
        // 密码登录成功即已设密码：直接按 redirect 规则跳转
        uni.showToast({ title: '登录成功', icon: 'success' })
        this.goAfterLogin()
      } catch (e) {
        this.pwdLogging = false
        this.showToast((e && e.message) || '网络异常')
      }
    },
    // ==================== 登录后跳转（FRONT_CONTRACT） ====================
    // 带 redirect 参数则解码回跳（须为 / 开头的页面路径，防脏参数跳坏），否则 reLaunch 回首页
    goAfterLogin() {
      let target = '/pages/index/index'
      if (this.redirect) {
        try {
          const decoded = decodeURIComponent(this.redirect)
          if (decoded && decoded.charAt(0) === '/') target = decoded
        } catch (e) {
          // redirect 非法编码（半截百分号等）时保持回首页兜底
        }
      }
      uni.reLaunch({ url: target })
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 页面级滚轮兜底：手动驱动 body 滚动（照搜索页同款方案）；本页无弹层，无需弹层放行守卫
    onPageWheel(e) {
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* 多多买菜风格：白底 + 主红 #E02020 + 圆角胶囊输入框 */
.login-page {
  min-height: 100vh;
  background-color: #FFFFFF;
  box-sizing: border-box;
  padding: 0 56rpx 60rpx;
}

/* ==================== 1. 顶部品牌区 ==================== */
.brand-area {
  display: flex; flex-direction: column; align-items: center;
  padding-top: 140rpx;
}
.brand-emoji { font-size: 108rpx; line-height: 1; }
.brand-title { margin-top: 24rpx; font-size: 44rpx; font-weight: 700; color: #333333; line-height: 1; }
.brand-sub { margin-top: 18rpx; font-size: 26rpx; color: #999999; line-height: 1; }

/* ==================== 0. 邀请横幅（橙红渐变卡，缩品牌区留白避免页面过高） ==================== */
.invite-banner {
  display: flex; align-items: center; gap: 20rpx;
  margin-top: -56rpx;
  background: linear-gradient(90deg, #FF6600, #E02020);
  border-radius: 24rpx;
  padding: 24rpx 28rpx;
}
.invite-banner-icon { flex-shrink: 0; font-size: 52rpx; line-height: 1; }
.invite-banner-texts { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.invite-banner-title { font-size: 28rpx; font-weight: 700; color: #FFFFFF; line-height: 1.3; }
.invite-banner-sub { font-size: 22rpx; color: rgba(255, 255, 255, 0.92); line-height: 1.3; }

/* ==================== 2. tab 切换（红色下划线激活态） ==================== */
.tabs {
  display: flex; justify-content: center; gap: 96rpx;
  margin-top: 72rpx;
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

/* ==================== 3/4. 登录表单（圆角胶囊输入框） ==================== */
.login-form { margin-top: 56rpx; }
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
.form-hint {
  display: block;
  margin: 0 24rpx;
  font-size: 22rpx; color: #999999; line-height: 1.5;
}
/* 密码 tab 的提示位于登录按钮下方，与上方拉开间距 */
.form-hint-loose { margin-top: 24rpx; }
/* uni-button 默认圆角与 line-height 必须显式覆盖（本项目前科坑） */
.btn-main {
  width: 100%; height: 88rpx; line-height: 88rpx;
  margin-top: 40rpx; padding: 0;
  background-color: #E02020; color: #FFFFFF;
  font-size: 32rpx; font-weight: 700;
  border: none; border-radius: 999rpx;
}
/* 提交飞行中禁用态：置灰提示"登录中…"，防重复提交（对照首页 .btn-confirm-disabled） */
.btn-main.btn-main-disabled { background-color: #F5B5B0; color: rgba(255, 255, 255, 0.9); }
</style>
