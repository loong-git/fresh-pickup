<template>
  <view class="page-container">
    <!-- ==================== 1. 登录密码 ==================== -->
    <!-- 不设「已设/未设」状态位：无任何只读接口暴露 hasPassword（仅在登录响应里），
         且后端 set-password 对既有 password_hash 幂等覆盖，"首次设置"与"重置"本就是同一个动作 -->
    <view class="card">
      <view class="card-head"><text class="card-title">登录密码</text></view>
      <text class="card-desc">设置后下次可用密码登录，无需再等验证码</text>
      <view class="input-capsule">
        <input
          class="capsule-input"
          v-model="newPassword"
          :password="true"
          maxlength="20"
          placeholder="请输入新密码（6-20位）"
          placeholder-style="color:#BBBBBB;"
        />
      </view>
      <view class="input-capsule">
        <input
          class="capsule-input"
          v-model="confirmPassword"
          :password="true"
          maxlength="20"
          placeholder="请再次输入新密码"
          placeholder-style="color:#BBBBBB;"
        />
      </view>
      <button class="btn-main" :class="{ 'btn-main-disabled': settingPwd }" :disabled="settingPwd" @click="confirmSetPassword"><text>{{ settingPwd ? '设置中…' : '保存新密码' }}</text></button>
    </view>

    <!-- ==================== 2. 绑定手机号（F-08 两步流自 mine.vue 整体搬入，语义不变） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">绑定手机号</text></view>
      <view class="phone-row">
        <text class="phone-now">{{ maskPhone(user.phone) }}</text>
        <text class="cell-action" @click="togglePhoneChange">{{ phoneChangeOpen ? '收起' : '更换手机号' }}</text>
      </view>
      <!-- 更换手机号卡区（两步流：①验证当前手机号 ②绑定新手机号，中途收起即复位不产生半态） -->
      <view v-if="phoneChangeOpen" class="phone-change">
        <!-- 步骤①：展示当前手机号脱敏 + 验证码（本地暂存，真实校验在步骤②提交时由后端一次带三参数校验） -->
        <template v-if="phoneStep === 1">
          <text class="pc-title">更换手机号</text>
          <text class="pc-desc">当前手机号 {{ maskPhone(user.phone) }}，为确认是本人操作，请输入该手机号收到的验证码</text>
          <view class="pc-capsule">
            <input
              class="pc-input"
              v-model="pcOldPhoneCode"
              type="number"
              maxlength="6"
              placeholder="请输入验证码"
              placeholder-style="color:#BBBBBB;"
            />
            <button
              class="pc-send"
              :class="{ 'pc-send-disabled': pcCodeSending || pcCountdown1 > 0 }"
              :disabled="pcCodeSending || pcCountdown1 > 0"
              @click="sendOldPhoneCode"
            ><text>{{ pcCodeSending ? '发送中…' : (pcCountdown1 > 0 ? pcCountdown1 + 's后重发' : '获取验证码') }}</text></button>
          </view>
          <text v-if="isDevEnv" class="pc-hint">开发环境验证码固定为 123456</text>
          <button class="pc-main" @click="goPhoneStep2"><text>下一步</text></button>
        </template>
        <!-- 步骤②：新手机号 + 验证码 →「确认更换」一次带三参数调 change-phone -->
        <template v-else>
          <text class="pc-title">绑定新手机号</text>
          <text class="pc-desc">新手机号须未注册过本平台账号，换绑成功后请用新手机号登录</text>
          <view class="pc-capsule">
            <input
              class="pc-input"
              v-model="pcNewPhone"
              type="number"
              maxlength="11"
              placeholder="请输入新手机号"
              placeholder-style="color:#BBBBBB;"
            />
          </view>
          <view class="pc-capsule">
            <input
              class="pc-input"
              v-model="pcNewPhoneCode"
              type="number"
              maxlength="6"
              placeholder="请输入验证码"
              placeholder-style="color:#BBBBBB;"
            />
            <button
              class="pc-send"
              :class="{ 'pc-send-disabled': pcSend2Disabled }"
              :disabled="pcSend2Disabled"
              @click="sendNewPhoneCode"
            ><text>{{ pcCodeSending ? '发送中…' : (pcSend2Disabled ? pcCountdown2 + 's后重发' : '获取验证码') }}</text></button>
          </view>
          <!-- U1（轮2 找茬）：步骤②对称开发提示——同 isDevEnv 条件，生产构建不展示 -->
          <text v-if="isDevEnv" class="pc-hint">开发环境验证码固定为 123456</text>
          <button class="pc-main" :class="{ 'pc-main-disabled': pcSubmitting }" :disabled="pcSubmitting" @click="confirmChangePhone"><text>{{ pcSubmitting ? '更换中…' : '确认更换' }}</text></button>
          <text class="pc-back" @click="backToStep1">返回上一步</text>
        </template>
      </view>
    </view>
    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 账号安全页（F-67）：登录密码设置 + 绑定手机号更换，两张卡，白底 + 主红 #E02020。
// 换绑两步流与卡 1 的密码校验规则均自既有页整体搬入（mine.vue F-08 / login.vue），行为语义保持一致；
// 本页用原生返回栏（pages.json 不设 navigationStyle:custom）——F-66 实测登录页那种 custom 无返回栏
// 叠加 uni-app H5 这段 hash 导航不新增 history 条目会把人困在页面上，这一页必须能退出去。
import { mapState } from 'vuex'
import { setPassword, changePhone, sendCode } from '@/api/index.js'

// U1（轮2 找茬）：开发环境判断（口径同 front/api/config.js BASE_URL 与 invite/cash.vue IS_PROD）——
// 「开发环境验证码固定为 123456」提示仅非生产构建展示，生产包不向用户暴露固定码提示
const IS_DEV = process.env.NODE_ENV !== 'production'

export default {
  data() {
    return {
      // ==================== 登录密码卡 ====================
      newPassword: '',
      confirmPassword: '',
      settingPwd: false, // 「保存新密码」提交中防重
      // ==================== F-08 更换手机号卡区 ====================
      phoneChangeOpen: false, // 卡区展开态（内联展开/收起，不跳页）
      phoneStep: 1, // 两步流：1=验证当前手机号 2=绑定新手机号
      pcOldPhoneCode: '', // 步骤①当前手机号验证码（本地暂存，步骤②提交时随三参数一并发送）
      pcNewPhone: '', // 步骤②新手机号
      pcNewPhoneCode: '', // 步骤②新手机号验证码
      pcCodeSending: false, // 验证码发送中（两步按钮共用重入互斥，同一时刻只会点其中一个）
      pcSubmitting: false, // 「确认更换」提交中防重
      pcCountdown1: 0, // 步骤①当前手机号 60s 倒计时
      pcTimer1: null,
      pcCountdown2: 0, // 步骤②新手机号 60s 倒计时（两步各持独立倒计时，互不串扰）
      pcTimer2: null,
      pcOldCodeSent: false, // U6（轮2 找茬）：步骤①验证码已成功发送（后端 200）标记——goPhoneStep2 未发码拦截
      pcCountdownPhone: '' // U9（轮2 找茬）：倒计时绑定的目标手机号（startPcCountdown 记录；步骤②换号立即允许给新号发码）
    }
  },
  computed: {
    ...mapState(['user']),
    // 是否已登录（token 鉴权）：401 时 request 层清登录态，本页随 store 降级
    hasToken() { return !!(this.user && this.user.token) },
    // U1：开发环境提示显隐（生产构建隐藏「验证码固定为 123456」提示）
    isDevEnv() { return IS_DEV },
    // U9：步骤②发送按钮禁用口径与 sendNewPhoneCode 守卫同源——仅「同一新号倒计时中」禁用，
    // 换输入新号立即恢复可点并回「获取验证码」文案（旧口径 pcCountdown2>0 一刀切会把
    // 换号后的发码入口锁死 60s，守卫放开而按钮仍禁用等于没修）
    pcSend2Disabled() {
      return this.pcCodeSending || (this.pcCountdown2 > 0 && this.pcCountdownPhone === (this.pcNewPhone || '').trim())
    }
  },
  watch: {
    // 登录态丢失（token 失效 401 时 request 层统一清 store）：复位更换手机号卡区半态，
    // 防止重新登录后带着旧输入/进行中的倒计时复活（契约：中途放弃不产生半态）
    hasToken(v) {
      if (!v) {
        this.phoneChangeOpen = false
        this.resetPhoneChange()
      }
    }
  },
  onLoad() {
    // 登录态守卫：本页两张卡都要 token（set-password / change-phone 均需登录）。
    // 带 redirect 回跳依赖 login.vue 那条「已登录且带 redirect 就回跳」——F-67 搬走登录页设密码卡后它仍在
    if (!this.hasToken) {
      uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/security/security') })
    }
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照我的页同款方案）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    this.stopPcCountdown(1)
    this.stopPcCountdown(2)
    // #ifdef H5
    // 页面级滚轮兜底监听清理（与上方 onReady 的注册配对）
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
  },
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // ==================== 登录密码卡 ====================
    // 设置密码：POST /api/auth/set-password（需登录，token 由 request 层自动携带）；前端先行 6-20 位校验与两次一致校验
    // 成功停留本页不跳走（这一页就是账号安全本身，无处可"返回"）
    async confirmSetPassword() {
      if (this.settingPwd) return
      const pwd = this.newPassword || ''
      if (pwd.length < 6 || pwd.length > 20) { this.showToast('请输入6-20位密码'); return }
      if (pwd !== (this.confirmPassword || '')) { this.showToast('两次输入的密码不一致'); return }
      this.settingPwd = true
      try {
        await setPassword(pwd)
        uni.showToast({ title: '设置成功，下次可用密码登录', icon: 'none' })
      } catch (e) {
        this.showToast((e && e.message) || '网络异常')
      } finally {
        this.settingPwd = false
      }
    },
    // ==================== 通用 ====================
    // 手机号脱敏兜底展示（口径照订单页 order.vue 同款正则）：138****1234
    maskPhone(p) {
      const s = String(p || '')
      return /^1[3-9]\d{9}$/.test(s) ? s.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2') : s
    },
    // ==================== F-08 更换手机号卡区 ====================
    // 「更换手机号/收起」小按钮：切换内联卡区（不跳页）。
    // 收起即复位两步表单与双倒计时（契约：中途放弃不产生半态）
    togglePhoneChange() {
      this.phoneChangeOpen = !this.phoneChangeOpen
      if (!this.phoneChangeOpen) this.resetPhoneChange()
    },
    // 复位：步数回 1、清输入、停双倒计时（不动 phoneChangeOpen，由调用方决定显隐）
    resetPhoneChange() {
      this.phoneStep = 1
      this.pcOldPhoneCode = ''
      this.pcNewPhone = ''
      this.pcNewPhoneCode = ''
      this.pcOldCodeSent = false
      this.stopPcCountdown(1)
      this.stopPcCountdown(2)
    },
    // 60s 倒计时（参照 login.vue sendSmsCode 的 smsCountdown 模式，两步各持独立倒计时互不串扰）；
    // U9：记录倒计时目标手机号——步骤②发码守卫「倒计时中且同号」才拦截，换号立即允许给新号发码
    startPcCountdown(n, phone) {
      this.stopPcCountdown(n)
      this.pcCountdownPhone = phone || ''
      if (n === 1) {
        this.pcCountdown1 = 60
        this.pcTimer1 = setInterval(() => {
          this.pcCountdown1--
          if (this.pcCountdown1 <= 0) this.stopPcCountdown(1)
        }, 1000)
      } else {
        this.pcCountdown2 = 60
        this.pcTimer2 = setInterval(() => {
          this.pcCountdown2--
          if (this.pcCountdown2 <= 0) this.stopPcCountdown(2)
        }, 1000)
      }
    },
    stopPcCountdown(n) {
      if (n === 1) {
        if (this.pcTimer1) { clearInterval(this.pcTimer1); this.pcTimer1 = null }
        this.pcCountdown1 = 0
      } else {
        if (this.pcTimer2) { clearInterval(this.pcTimer2); this.pcTimer2 = null }
        this.pcCountdown2 = 0
      }
    },
    // 步骤①发送当前手机号验证码：先发后校验（本地只判手机号在库且格式合法；验证码对错由步骤②提交时后端校验）
    async sendOldPhoneCode() {
      if (this.pcCodeSending || this.pcCountdown1 > 0) return
      const phone = (this.user && this.user.phone) || ''
      if (!/^1[3-9]\d{9}$/.test(phone)) {
        uni.showToast({ title: '当前手机号异常，请重新登录', icon: 'none', duration: 2000 })
        return
      }
      this.pcCodeSending = true
      try {
        await sendCode(phone)
        uni.showToast({ title: '验证码已发送', icon: 'none' })
        // U6：request 层仅在后端 HTTP 200 + code 200 时 resolve——走到这里即发码成功，
        // 置标记放行 goPhoneStep2（失败路径保持 false）；倒计时绑定当前号（U9）
        this.pcOldCodeSent = true
        this.startPcCountdown(1, phone)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '网络异常', icon: 'none', duration: 2000 })
      } finally {
        this.pcCodeSending = false
      }
    },
    // 步骤①「下一步」：仅本地暂存 pcOldPhoneCode 并切步，不调接口——
    // change-phone 是单接口三参数契约，旧号验证码的真实校验在步骤②确认更换时由后端一次完成
    goPhoneStep2() {
      // U6（轮2 找茬）：未成功发码（后端 200）不得进步骤②——防跳过发码直接臆测验证码提交
      if (!this.pcOldCodeSent) {
        uni.showToast({ title: '请先获取验证码', icon: 'none', duration: 1500 })
        return
      }
      const code = (this.pcOldPhoneCode || '').trim()
      if (!code) {
        uni.showToast({ title: '请输入验证码', icon: 'none', duration: 1500 })
        return
      }
      this.pcOldPhoneCode = code
      this.phoneStep = 2
    },
    // 步骤②「返回上一步」：仅切回步骤①，已输旧号验证码与步骤①倒计时保留（可原地重发/改输）
    backToStep1() {
      this.phoneStep = 1
    },
    // 步骤②发送新手机号验证码：本地先校验新号格式（未注册校验由后端在确认更换时把关，不在发码链路做）
    async sendNewPhoneCode() {
      const phone = (this.pcNewPhone || '').trim()
      // U9（轮2 找茬）：倒计时绑定目标号——仅「同一新号倒计时中」才拦截；换输入新号
      // 立即允许给新号发码（旧口径 pcCountdown2>0 一刀切会把换号后的发码锁死 60s）
      if (this.pcCodeSending) return
      if (this.pcCountdown2 > 0 && this.pcCountdownPhone === phone) return
      if (!/^1[3-9]\d{9}$/.test(phone)) {
        uni.showToast({ title: '请输入正确的手机号', icon: 'none', duration: 1500 })
        return
      }
      this.pcCodeSending = true
      try {
        await sendCode(phone)
        uni.showToast({ title: '验证码已发送', icon: 'none' })
        this.startPcCountdown(2, phone)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '网络异常', icon: 'none', duration: 2000 })
      } finally {
        this.pcCodeSending = false
      }
    },
    // 步骤②「确认更换」：一次带三参数调 POST /api/user/change-phone（单接口契约）。
    // 成功：store 合并 phone（R8 找茬教训：setUser 是 {...DEFAULT_USER,...payload} 全量替换语义，
    // 只传 {phone} 会把 token/id/头像清成游客态，必须 {...this.user} 展开——与头像链路同口径）→
    // toast → 卡区收起复位（不回写 store 的话用户换完号回到「我的」页看到的还是旧号）。
    // 失败：toast 后端 message 直出（400 该手机号已注册/验证码错误等）并保留现场可重试；
    // 401（登录态过期）已被 request 层清登录态，本页随 store 降级、卡区由 watch 复位，无需再 toast
    async confirmChangePhone() {
      if (this.pcSubmitting) return
      const oldPhoneCode = (this.pcOldPhoneCode || '').trim()
      const newPhone = (this.pcNewPhone || '').trim()
      const newPhoneCode = (this.pcNewPhoneCode || '').trim()
      if (!oldPhoneCode) {
        uni.showToast({ title: '请输入当前手机号验证码', icon: 'none', duration: 1500 })
        return
      }
      if (!/^1[3-9]\d{9}$/.test(newPhone)) {
        uni.showToast({ title: '请输入正确的手机号', icon: 'none', duration: 1500 })
        return
      }
      if (!newPhoneCode) {
        uni.showToast({ title: '请输入新手机号验证码', icon: 'none', duration: 1500 })
        return
      }
      this.pcSubmitting = true
      try {
        const res = await changePhone(oldPhoneCode, newPhone, newPhoneCode)
        // 后端出参 R{code,data:{phone:新号原始值}}，原始值入 store，脱敏展示由本页 maskPhone 负责
        const newPhoneRaw = (res && res.data && res.data.phone) || newPhone
        this.$store.commit('setUser', { ...this.user, phone: newPhoneRaw })
        this.phoneChangeOpen = false
        this.resetPhoneChange()
        uni.showToast({ title: '已更换为 ' + this.maskPhone(newPhoneRaw) + '，下次请用新手机号登录', icon: 'none', duration: 2500 })
      } catch (e) {
        if (!e || e.code !== 401) {
          uni.showToast({ title: (e && e.message) || '网络异常', icon: 'none', duration: 2000 })
        }
      } finally {
        this.pcSubmitting = false
      }
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 页面级滚轮兜底：手动驱动 body 滚动（照我的页同款方案）。弹层（showToast/showModal）打开时
    // 命中 uni 弹层节点直接 return 放行，不再无差别 preventDefault 驱动背景滚动
    onPageWheel(e) {
      const t = e.target
      const el = t && t.nodeType === 1 ? t : (t && t.parentElement)
      if (el && typeof el.closest === 'function'
        && el.closest('.uni-actionsheet, .uni-modal, .uni-toast, .uni-sample-toast')) {
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
/* ==================== 多多买菜工具风 — 账号安全页（白底 + 主红 #E02020） ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; box-sizing: border-box; }

/* ==================== 白卡通用（照我的页） ==================== */
.card {
  background-color: #FFFFFF; border-radius: 16rpx;
  margin: 20rpx 24rpx 0;
  padding: 24rpx;
}
.card-head { margin-bottom: 8rpx; }
.card-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.card-desc {
  display: block;
  margin-bottom: 24rpx;
  font-size: 22rpx; color: #999999; line-height: 1.5;
}

/* ==================== 1. 登录密码（输入胶囊/主按钮照登录页同款） ==================== */
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
/* uni-button 默认圆角与 line-height 必须显式覆盖（本项目前科坑） */
.btn-main {
  width: 100%; height: 88rpx; line-height: 88rpx;
  margin-top: 16rpx; padding: 0;
  background-color: #E02020; color: #FFFFFF;
  font-size: 32rpx; font-weight: 700;
  border: none; border-radius: 999rpx;
}
.btn-main.btn-main-disabled { background-color: #F5B5B0; color: rgba(255, 255, 255, 0.9); }

/* ==================== 2. 绑定手机号（当前号一行 + 行尾胶囊按钮） ==================== */
.phone-row { display: flex; align-items: center; justify-content: space-between; padding: 8rpx 0 4rpx; }
.phone-now { font-size: 30rpx; font-weight: 600; color: #333333; }
.cell-action {
  flex-shrink: 0;
  font-size: 24rpx; font-weight: 600; color: #E02020;
  padding: 6rpx 20rpx; line-height: 1.4;
  border: 1rpx solid #E02020; border-radius: 24rpx;
}

/* ==================== 2.1 F-08 更换手机号卡区（自 mine.vue 整体搬入，样式与观感不变） ==================== */
.phone-change {
  padding: 24rpx 0 4rpx;
  border-top: 1rpx solid #F5F5F5;
  margin-top: 16rpx;
}
.pc-title { display: block; font-size: 28rpx; font-weight: 700; color: #333333; }
.pc-desc {
  display: block;
  margin: 8rpx 0 24rpx;
  font-size: 22rpx; color: #999999; line-height: 1.5;
}
.pc-capsule {
  display: flex; align-items: center;
  height: 84rpx; margin-bottom: 20rpx;
  background-color: #F5F5F5; border-radius: 999rpx;
  padding: 0 10rpx 0 32rpx; box-sizing: border-box;
}
.pc-input { flex: 1; min-width: 0; height: 84rpx; line-height: 84rpx; font-size: 28rpx; color: #333333; }
.pc-send {
  flex-shrink: 0; margin: 0; padding: 0 24rpx;
  height: 64rpx; line-height: 64rpx;
  background-color: #E02020; color: #FFFFFF;
  font-size: 22rpx; font-weight: 600;
  border: none; border-radius: 999rpx;
}
/* uni-button 默认伪元素边框必须显式覆盖（本项目前科坑） */
.pc-send::after { border: none; }
.pc-send.pc-send-disabled { background-color: #CCCCCC; color: rgba(255, 255, 255, 0.9); }
.pc-hint {
  display: block;
  font-size: 22rpx; color: #999999; line-height: 1.5;
}
.pc-main {
  display: block;
  width: 100%; height: 80rpx; line-height: 80rpx;
  margin-top: 24rpx; padding: 0;
  background-color: #E02020; color: #FFFFFF;
  font-size: 30rpx; font-weight: 700;
  border: none; border-radius: 999rpx; text-align: center;
}
.pc-main::after { border: none; }
.pc-main.pc-main-disabled { background-color: #F5B5B0; color: rgba(255, 255, 255, 0.9); }
.pc-back {
  display: block; margin-top: 20rpx;
  font-size: 24rpx; color: #E02020; text-align: center;
}

.page-bottom-space { height: env(safe-area-inset-bottom); }
</style>
