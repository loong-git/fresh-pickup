<template>
  <view class="page-container">
    <!-- ==================== 1. 身份卡（navigationStyle custom 自绘：红色头部 + 白卡叠压） ==================== -->
    <view class="mine-header">
      <!-- 返回箭头：有页面栈 navigateBack，深链直达无栈兜底回首页（与搜索页 goBack 同口径） -->
      <view class="mine-back" @click="goBack"><text class="mine-back-arrow">&lt;</text></view>
      <text class="mine-title">我的</text>
      <!-- 身份卡：登录态展示头像+脱敏手机号；游客态点击跳独立登录页（带 redirect 回本页） -->
      <view class="user-card" @click="onUserCardTap">
        <!-- R8 头像：登录态点击弹设置（选图/恢复默认）；游客态与卡整体同跳登录 -->
        <view class="avatar-box" :class="{ 'avatar-editable': hasToken }" @click.stop="onAvatarTap">
          <image class="avatar-img" :src="avatarSrc" mode="aspectFill" />
        </view>
        <view class="user-info">
          <text v-if="hasToken" class="user-phone">{{ maskPhone(user.phone) }}</text>
          <text v-else class="user-phone user-phone-guest">点击登录</text>
          <text class="user-sub">{{ hasToken ? '欢迎回来，生鲜好货天天低价' : '登录后享优惠券/订单/评价服务' }}</text>
        </view>
        <text v-if="!hasToken" class="user-arrow">›</text>
      </view>
    </view>

    <!-- ==================== 2. 优惠券概要入口（F-09：单行 cell-card 与订单行同构；F-10 起三态 tab/券列表承载在「我的优惠券」独立页） ==================== -->
    <view class="card cell-card" @click="goCoupons">
      <view class="cell">
        <text class="cell-icon">🎟️</text>
        <text class="cell-title">我的优惠券</text>
        <text class="cell-sub">{{ couponSummary }}</text>
        <text class="cell-arrow">›</text>
      </view>
    </view>

    <!-- ==================== 3. 我的订单行（整行跳订单页；订单页内部已有四状态 tab，不重复做） ==================== -->
    <view class="card cell-card" @click="goOrder">
      <view class="cell">
        <text class="cell-icon">📦</text>
        <text class="cell-title">我的订单</text>
        <text class="cell-sub">待自提/已完成/已取消</text>
        <text class="cell-arrow">›</text>
      </view>
    </view>

    <!-- ==================== 4. 邀请赚现金行（F-05：整行跳邀请赚现金页，邀请码/复制/记录都在现金页内） ==================== -->
    <view class="card cell-card" @click="goInviteCash">
      <view class="cell">
        <text class="cell-icon">💰</text>
        <text class="cell-title">邀请赚现金</text>
        <text class="cell-sub">邀新人注册得现金奖励</text>
        <text class="cell-arrow">›</text>
      </view>
    </view>

    <!-- ==================== 5. 我的评价概要入口（F-11：评论列表整块迁出为独立页，与券概要行同构；概要行只报数，点击进「我的评价」页） ==================== -->
    <view class="card cell-card" @click="goReviews">
      <view class="cell">
        <text class="cell-icon">📝</text>
        <text class="cell-title">我的评价</text>
        <text class="cell-sub">{{ reviewSummary }}</text>
        <text class="cell-arrow">›</text>
      </view>
    </view>

    <!-- ==================== 6. 账号安全行 + 更换手机号内联卡区（F-08） ==================== -->
    <!-- cell 整行保留 goSecurity 跳登录页（设置/重置密码）旧行为；卡区展开/收起挂在行尾「更换」小按钮上（@click.stop 隔离，不跳页） -->
    <view class="card cell-card">
      <view class="cell" @click="goSecurity">
        <text class="cell-icon">🔐</text>
        <text class="cell-title">账号安全</text>
        <text class="cell-sub">设置/重置登录密码</text>
        <text v-if="hasToken" class="cell-action" @click.stop="togglePhoneChange">{{ phoneChangeOpen ? '收起' : '换手机号' }}</text>
        <text class="cell-arrow">›</text>
      </view>
      <!-- 更换手机号卡区（仅登录态渲染；两步流：①验证当前手机号 ②绑定新手机号，中途收起即复位不产生半态） -->
      <view v-if="hasToken && phoneChangeOpen" class="phone-change">
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

    <!-- ==================== 7. 退出登录（仅登录态显示；二次确认后清登录态回首页） ==================== -->
    <button v-if="hasToken" class="btn-logout" @click="onLogout">退出登录</button>
    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 「我的」个人中心页（多多买菜工具风，白底+主红 #E02020）：
// 身份卡 / 优惠券概要入口 / 订单入口 / 评价概要入口 / 账号安全 / 退出登录。
// 登录态判定与渲染一律响应 store.user.token（mapState user）：
// token 失效接口 401 时 request 层统一清登录态，本页随 store 自动降级游客视图，不残留已登录 UI。
import { mapState } from 'vuex'
import { getMyCoupons, getMyReviews, updateUserAvatar, changePhone, sendCode } from '@/api/index.js'
import { DEFAULT_AVATAR, fileToAvatarDataURI, isAvatarTooLarge } from '@/utils/avatar.js'

// U1（轮2 找茬）：开发环境判断（口径同 front/api/config.js BASE_URL 与 invite/cash.vue IS_PROD）——
// 「开发环境验证码固定为 123456」提示仅非生产构建展示，生产包不向用户暴露固定码提示
const IS_DEV = process.env.NODE_ENV !== 'production'

export default {
  data() {
    return {
      // 优惠券原始列表（getMyCoupons 全量拉取，onShow 刷新；F-09 概要行只取 couponGroups.available.length 报数）
      coupons: [],
      couponError: false,
      // 我的评价列表（getMyReviews，服务端按 dish_id 填充 dishName）；F-11：仅驱动概要行 reviewSummary 报数，列表承载在 my-reviews 独立页
      reviews: [],
      reviewError: false,
      // ==================== F-08 更换手机号卡区 ====================
      phoneChangeOpen: false, // 卡区展开态（内联展开/收起，不跳页）
      phoneStep: 1, // 两步流：1=验证当前手机号 2=绑定新手机号
      pcOldPhoneCode: '', // 步骤①当前手机号验证码（本地暂存，步骤②提交时随三参数一并发送）
      pcNewPhone: '', // 步骤②新手机号
      pcNewPhoneCode: '', // 步骤②新手机号验证码
      pcCodeSending: false, // 验证码发送中（两步按钮共用防抖，同一时刻只会点其中一个）
      pcSubmitting: false, // 「确认更换」提交中防重
      pcCountdown1: 0, // 步骤①当前手机号 60s 倒计时
      pcTimer1: null,
      pcCountdown2: 0, // 步骤②新手机号 60s 倒计时（两步各持独立倒计时，互不串扰）
      pcTimer2: null,
      pcOldCodeSent: false, // U6（轮2 找茬）：步骤①验证码已成功发送（后端 200）标记——goPhoneStep2 未发码拦截
      pcCountdownPhone: '' // U9（轮2 找茬）：倒计时绑定的目标手机号（startPcCountdown 记录；步骤②换号立即允许给新号发码）
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
  computed: {
    ...mapState(['user']),
    // 是否已登录（token 鉴权）：401 时 request 层清登录态，此处响应式切换游客视图
    hasToken() { return !!(this.user && this.user.token) },
    // U1：开发环境提示显隐（生产构建隐藏「验证码固定为 123456」提示）
    isDevEnv() { return IS_DEV },
    // U9：步骤②发送按钮禁用口径与 sendNewPhoneCode 守卫同源——仅「同一新号倒计时中」禁用，
    // 换输入新号立即恢复可点并回「获取验证码」文案（旧口径 pcCountdown2>0 一刀切会把
    // 换号后的发码入口锁死 60s，守卫放开而按钮仍禁用等于没修）
    pcSend2Disabled() {
      return this.pcCodeSending || (this.pcCountdown2 > 0 && this.pcCountdownPhone === (this.pcNewPhone || '').trim())
    },
    // R8 头像展示源：登录用户已设置头像优先，否则默认品牌 SVG（游客态同默认）
    avatarSrc() {
      return (this.hasToken && this.user && this.user.avatar) || DEFAULT_AVATAR
    },
    // 优惠券三态归类（复审口径，严格执行）：
    // 可用 = status==='available' 且 (expireAt==null || expireAt>now)【expireAt 为 null 表示永不过期，必须归可用】
    // 已使用 = status==='used'
    // 已过期 = status==='available' 且 expireAt!=null 且 expireAt<=now
    // 其余 status（脏数据/未来扩展）不归任何组；expireAt 解析失败按 null 同"永不过期"归可用
    couponGroups() {
      const now = Date.now()
      const groups = { available: [], used: [], expired: [] }
      this.coupons.forEach(c => {
        if (!c || typeof c !== 'object') return
        if (c.status === 'used') { groups.used.push(c); return }
        if (c.status === 'available') {
          const ts = this.couponExpireTs(c)
          if (ts == null || ts > now) groups.available.push(c)
          else groups.expired.push(c)
        }
      })
      return groups
    },
    // F-09 概要行文案（顺序即优先级）：未登录「登录后查看」/加载失败「点击查看全部」/
    // 可用 0 张「暂无可用，去领券」/否则「可用 N 张」
    couponSummary() {
      if (!this.hasToken) return '登录后查看'
      if (this.couponError) return '点击查看全部'
      const n = this.couponGroups.available.length
      return n > 0 ? `可用 ${n} 张` : '暂无可用，去领券'
    },
    // F-11 概要行文案（顺序即优先级）：未登录「登录后查看」/加载失败「点击查看全部」/
    // 0 条「暂无评价」/否则「共 N 条评价」（N=length 合法：listByUser 无分页全量）。
    // reviewError 只驱动概要文案「点击查看全部」，重试责任交 my-reviews 独立页既有错误行（对齐券概要行口径）
    reviewSummary() {
      if (!this.hasToken) return '登录后查看'
      if (this.reviewError) return '点击查看全部'
      const n = this.reviews.length
      return n > 0 ? `共 ${n} 条评价` : '暂无评价'
    }
  },
  onShow() {
    // 每次进入刷新（从登录页返回后登录态变化同步数据）；未登录不发请求
    this.loadCoupons()
    this.loadReviews()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照搜索页同款方案）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  // onUnload 移出 H5 条件编译（F-08）：更换手机号双倒计时清理在所有端生效，防小程序端退出页面定时器泄漏；
  // 滚轮监听仅 H5 注册（onReady #ifdef 内），非 H5 端 _pageWheelHandler 恒为 null，清理分支天然跳过
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
    // 返回：有页面栈走 navigateBack；深链直达无栈时 reLaunch 回首页
    goBack() {
      if (getCurrentPages().length > 1) {
        uni.navigateBack()
      } else {
        uni.reLaunch({ url: '/pages/index/index' })
      }
    },
    // ==================== 数据加载 ====================
    async loadCoupons() {
      // 未登录（无 token / 401 后登录态已被 request 层清空）：清数据不发请求，展示登录引导态
      if (!this.hasToken) { this.coupons = []; this.couponError = false; return }
      try {
        const res = await getMyCoupons()
        this.coupons = Array.isArray(res && res.data) ? res.data : []
        this.couponError = false
      } catch (e) {
        // 401（token 失效）：request 层已清登录态，页面响应式切游客视图，不再置错误态；其余失败显示错误态可重试
        if (!e || e.code !== 401) this.couponError = true
      }
    },
    async loadReviews() {
      if (!this.hasToken) { this.reviews = []; this.reviewError = false; return }
      try {
        const res = await getMyReviews()
        this.reviews = Array.isArray(res && res.data) ? res.data : []
        this.reviewError = false
      } catch (e) {
        if (!e || e.code !== 401) this.reviewError = true
      }
    },
    // ==================== 优惠券概要入口（F-09） ====================
    // 概要行点击：未登录 goLogin（redirect 回 mine，方法内已带）；登录跳「我的优惠券」独立页
    // （F-10 拆分：三分 tab/券列表唯一承载点，无 anchor 定位机制）。加载失败 mine 侧不渲染错误行——
    // couponError 只驱动概要文案「点击查看全部」，重试责任交目标页既有错误行（契约第 8 条）
    goCoupons() {
      if (!this.hasToken) { this.goLogin(); return }
      uni.navigateTo({ url: '/pages/my-coupons/my-coupons' })
    },
    // 概要行点击（F-11）：未登录 goLogin（redirect 回 mine，方法内已带）；登录跳「我的评价」独立页
    // （评论列表唯一承载点；加载失败 mine 侧不渲染错误行——reviewError 只驱动概要文案
    // 「点击查看全部」，重试责任交目标页既有错误行，对齐券概要行口径）
    goReviews() {
      if (!this.hasToken) { this.goLogin(); return }
      uni.navigateTo({ url: '/pages/my-reviews/my-reviews' })
    },
    // 券 expireAt -> 毫秒时间戳：null/空/解析失败一律返回 null（按"永不过期"语义归可用）。
    // F-09 保留：couponGroups 归类内部调用此方法，误删则登录态≥1 张券时 TypeError→概要行崩溃白屏
    // （且游客态验收路径测不出该崩溃，risks 首条）
    couponExpireTs(c) {
      if (!c || c.expireAt == null || c.expireAt === '') return null
      const t = new Date(c.expireAt).getTime()
      return Number.isFinite(t) ? t : null
    },
    // ==================== 通用 ====================
    // 手机号脱敏兜底展示（口径照订单页 order.vue 同款正则）：138****1234
    maskPhone(p) {
      const s = String(p || '')
      return /^1[3-9]\d{9}$/.test(s) ? s.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2') : s
    },
    // 身份卡点击：游客态去登录；登录态无动作
    onUserCardTap() {
      if (!this.hasToken) this.goLogin()
    },
    // ==================== R8 头像设置 ====================
    // 头像点击：游客态与卡同跳登录；登录态弹设置（选图/恢复默认）——uni.showActionSheet
    // 两项映射 doPickAvatarImage / doResetAvatar，取消（tapIndex<0）无动作
    onAvatarTap() {
      if (!this.hasToken) { this.goLogin(); return }
      uni.showActionSheet({
        itemList: ['选择图片', '恢复默认头像'],
        success: (res) => {
          if (res.tapIndex === 0) this.doPickAvatarImage()
          else if (res.tapIndex === 1) this.doResetAvatar()
        }
      })
    },
    // 选图：H5 动态创建 input[type=file]（accept=image/*）触发相册/文件选择；
    // change → fileToAvatarDataURI（canvas 128×128 中心裁方 JPEG）→ 预校验长度 → PUT → store 同步。
    // 动态节点用完即弃（不挂常驻 DOM，避免 uniapp 模板对原生 input 的编译差异）。
    // 轮1 找茬修复：此前只在 change 里 removeChild——用户打开选择器后取消（change 永不触发）
    // 时节点残留 body 累积；补挂 'cancel' 事件（现代浏览器支持）+ 统一 cleanup（两路都走，
    // settled 防双触发，removeChild 前判 parentNode 存在）
    doPickAvatarImage() {
      // #ifdef H5
      try {
        const input = document.createElement('input')
        input.type = 'file'
        input.accept = 'image/*'
        input.style.display = 'none'
        let settled = false
        const cleanup = () => {
          if (settled) return
          settled = true
          try {
            if (input.parentNode) input.parentNode.removeChild(input)
          } catch (e0) {}
        }
        input.addEventListener('change', () => {
          const file = input.files && input.files[0]
          cleanup()
          if (!file) return
          fileToAvatarDataURI(file)
            .then((dataURI) => {
              if (isAvatarTooLarge(dataURI)) {
                uni.showToast({ title: '图片过大，请换一张试试', icon: 'none', duration: 2000 })
                return
              }
              this.applyAvatar(dataURI)
            })
            .catch(() => {
              uni.showToast({ title: '图片处理失败，请换一张试试', icon: 'none', duration: 2000 })
            })
        })
        // 用户取消选择（change 不触发）：cleanup 移除动态节点，防残留累积
        input.addEventListener('cancel', cleanup)
        document.body.appendChild(input)
        input.click()
      } catch (e) {
        uni.showToast({ title: '当前环境不支持选择图片', icon: 'none', duration: 2000 })
      }
      // #endif
      // #ifndef H5
      uni.showToast({ title: '请在 H5 端设置头像', icon: 'none', duration: 2000 })
      // #endif
    },
    // 恢复默认头像：已设置头像先二次确认（U4，与退出登录同款 modal，防误触清空），
    // 确认后 PUT {avatar:null}（后端列置 NULL）→ store 同步清空 → 展示回默认 SVG；
    // 未设置头像保持「当前已是默认头像」提示不动
    doResetAvatar() {
      if (this.user && !this.user.avatar) {
        uni.showToast({ title: '当前已是默认头像', icon: 'none', duration: 1500 })
        return
      }
      // U4（轮2 找茬）：恢复默认是破坏性操作，加二次确认
      uni.showModal({
        title: '恢复默认头像',
        content: '将清空当前头像，确定吗？',
        success: (r) => {
          if (r.confirm) this.applyAvatar(null)
        }
      })
    },
    // 头像统一提交：PUT 成功 → setUser 局部合并 avatar（safeSetStorage 同步）→ toast；
    // 失败展示后端文案（400 格式/过大、401 已被 request 层清登录态走重登录）。
    // 轮1 找茬修复（high）：setUser mutation 是 {...DEFAULT_USER, ...payload} 全量替换语义，
    // 只传 {avatar} 会把 token/id/phone 清成游客态——必须展开当前 user 合并（找茬实录）
    async applyAvatar(dataURI) {
      // U5①（轮2 找茬）：重入不再静默 return——静默让用户以为点击无效而反复点
      if (this._avatarSubmitting) {
        uni.showToast({ title: '头像处理中，请稍候', icon: 'none', duration: 1500 })
        return
      }
      this._avatarSubmitting = true
      // U5②：PUT 全程 showLoading，完成（成功/失败/异常三路 finally）hideLoading
      uni.showLoading({ title: '处理中…' })
      // 结果 toast 统一延后到 hideLoading 之后发出：uni H5 端 showToast/showLoading 共用
      // 同一容器，先 toast 后 hideLoading 会把结果 toast 一并吞掉（时序坑，前置规避）
      let toast = null
      try {
        const res = await updateUserAvatar(dataURI)
        if (res && res.code === 200) {
          this.$store.commit('setUser', { ...this.user, avatar: res.data && res.data.avatar })
          toast = { title: res.message || '头像已更新', duration: 1500 }
        } else {
          toast = { title: (res && res.message) || '头像设置失败', duration: 2000 }
        }
      } catch (e) {
        // U5③：401（token 失效）request 层已清登录态切游客视图，本页随 store 降级，
        // 不再误导「头像设置失败」toast；其余失败（400 格式/过大、网络异常）保留提示
        if (!e || e.code !== 401) toast = { title: '头像设置失败，请稍后再试', duration: 2000 }
      } finally {
        uni.hideLoading()
        this._avatarSubmitting = false
      }
      if (toast) uni.showToast({ title: toast.title, icon: 'none', duration: toast.duration })
    },
    // 游客态登录入口：跳独立登录页并携带 redirect，登录成功后解码回跳本页
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/mine/mine') }) },
    // 账号安全行：同入口跳登录页（登录态下该页展示设置密码卡区，语义即设置/重置密码）
    goSecurity() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/mine/mine') }) },
    // ==================== F-08 更换手机号卡区 ====================
    // 行尾「更换/收起」小按钮：切换内联卡区（不跳页；@click.stop 已隔离 cell 整行 goSecurity）。
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
    // 只传 {phone} 会把 token/id/头像清成游客态，必须 {...this.user} 展开——与 applyAvatar 同口径）→
    // toast 脱敏新号（后端返回原始值，脱敏展示由前端负责）→ 卡区收起复位。
    // 失败：toast 后端 message 直出（400 该手机号已注册/验证码错误等）并保留现场可重试；
    // 401（登录态过期）已被 request 层清登录态，本页随 store 切游客视图、卡区 v-if 自动消失，无需再 toast
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
    // 我的订单行：整行跳订单页（四状态 tab 在订单页内部）
    goOrder() { uni.navigateTo({ url: '/pages/order/order' }) },
    // 邀请赚现金行（F-05）：整行跳邀请赚现金页（邀请码大卡/复制链接/统计/记录都在现金页内）
    goInviteCash() { uni.navigateTo({ url: '/pages/invite/cash' }) },
    // 退出登录：二次确认 → 清登录态（setClearUser 同步清本地存储与内存兜底 Map）→ toast → 回首页
    // 注意不清 pickupPerson（设备级数据，与登录态无关，由 setClearUser 天然不触碰）
    onLogout() {
      uni.showModal({
        title: '退出登录',
        content: '确定退出当前账号？',
        confirmText: '退出',
        confirmColor: '#E02020',
        success: (res) => {
          if (!res.confirm) return
          this.$store.commit('setClearUser')
          uni.showToast({ title: '已退出', icon: 'success' })
          uni.reLaunch({ url: '/pages/index/index' })
        }
      })
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 页面级滚轮兜底：手动驱动 body 滚动（照搜索页同款方案）。
    // 轮1 找茬修复：原「本页无弹层」注释不成立——本页有 showActionSheet（头像设置）与
    // showModal（退出登录确认），弹层打开时滚轮被无差别 preventDefault+滚 body 驱动背景滚动。
    // 参照 pickup.vue R9 分离守卫：事件目标命中 uni 弹层节点（actionsheet/modal/toast）时
    // 直接 return 放行，交 uni 弹层自身处理，不再驱动背景滚动；文本节点无 closest，按
    // nodeType 判一次向上借父元素
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
/* ==================== 多多买菜工具风 — 我的页（白底 + 主红 #E02020） ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; box-sizing: border-box; }

/* ==================== 1. 红色头部 + 身份卡（白卡叠压红头下缘） ==================== */
.mine-header {
  position: relative;
  background: linear-gradient(180deg, #E02020 0%, #E02020 55%, #F5F5F5 100%);
  padding: 24rpx 24rpx 72rpx;
  padding-top: calc(24rpx + env(safe-area-inset-top));
}
.mine-back { position: absolute; left: 20rpx; top: calc(20rpx + env(safe-area-inset-top)); width: 64rpx; height: 64rpx; display: flex; align-items: center; justify-content: center; z-index: 2; }
.mine-back-arrow { font-size: 40rpx; font-weight: 700; color: #FFFFFF; line-height: 1; }
.mine-title { display: block; font-size: 36rpx; font-weight: 700; color: #FFFFFF; padding: 8rpx 8rpx 24rpx 88rpx; }
.user-card {
  display: flex; align-items: center; gap: 20rpx;
  background-color: #FFFFFF; border-radius: 16rpx;
  padding: 28rpx 24rpx;
  box-shadow: 0 4rpx 16rpx rgba(224,32,32,.08);
}
.avatar-box {
  flex-shrink: 0; width: 96rpx; height: 96rpx; border-radius: 50%;
  background-color: #FFECE8;
  display: flex; align-items: center; justify-content: center;
  position: relative;
  overflow: visible;
}
/* R8 头像：image 铺满圆框（aspectFill），替代原 emoji 占位 */
.avatar-img {
  width: 96rpx; height: 96rpx; border-radius: 50%;
  display: block;
}
/* 登录态头像可点观感 */
.avatar-editable { cursor: pointer; }
.user-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.user-phone { font-size: 34rpx; font-weight: 700; color: #333333; }
.user-phone-guest { color: #E02020; }
.user-sub { font-size: 22rpx; color: #999999; }
.user-arrow { flex-shrink: 0; font-size: 36rpx; color: #CCCCCC; }

/* ==================== 白卡通用 ==================== */
.card {
  background-color: #FFFFFF; border-radius: 16rpx;
  margin: 20rpx 24rpx 0;
  padding: 24rpx;
}
/* ==================== 2/3/4/5/6. cell 行（券概要 / 订单 / 邀请赚现金 / 评价概要 / 账号安全） ==================== */
.cell-card { padding: 0; overflow: hidden; }
.cell { display: flex; align-items: center; gap: 16rpx; padding: 28rpx 24rpx; }
.cell-icon { font-size: 36rpx; flex-shrink: 0; }
.cell-title { flex-shrink: 0; font-size: 28rpx; font-weight: 600; color: #333333; }
.cell-sub {
  flex: 1; min-width: 0; text-align: right;
  font-size: 22rpx; color: #BBBBBB;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cell-arrow { flex-shrink: 0; font-size: 34rpx; color: #CCCCCC; }

/* ==================== 6.1 F-08 更换手机号卡区（风格照登录页输入胶囊/发送按钮/主按钮，红主题） ==================== */
/* 行尾「更换/收起」小按钮：红描边胶囊，与 cell-sub 间距由 cell 的 gap 提供 */
.cell-action {
  flex-shrink: 0;
  font-size: 24rpx; font-weight: 600; color: #E02020;
  padding: 6rpx 20rpx; line-height: 1.4;
  border: 1rpx solid #E02020; border-radius: 24rpx;
}
.phone-change {
  padding: 24rpx 24rpx 28rpx;
  border-top: 1rpx solid #F5F5F5;
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
/* uni-button 默认伪元素边框必须显式覆盖（本页 .btn-logout 同款前科坑） */
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

/* ==================== 7. 退出登录按钮（底部红字；显式覆盖 uni-button 默认圆角/行高/边框） ==================== */
.btn-logout {
  display: block;
  width: calc(100% - 48rpx); box-sizing: border-box;
  margin: 40rpx 24rpx 0; padding: 24rpx 0;
  font-size: 28rpx; font-weight: 600; color: #E02020;
  background-color: #FFFFFF;
  border: none; border-radius: 16rpx;
  line-height: 1.4; text-align: center;
}
.btn-logout::after { border: none; }
.page-bottom-space { height: env(safe-area-inset-bottom); }
</style>
