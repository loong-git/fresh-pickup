<template>
  <view class="page-container">
    <!-- ==================== 1. 身份卡（navigationStyle custom 自绘：红色头部 + 白卡叠压） ==================== -->
    <view class="mine-header">
      <!-- 返回箭头：有页面栈 navigateBack，深链直达无栈兜底回首页（与搜索页 goBack 同口径） -->
      <view class="mine-back" @click="goBack"><text class="mine-back-arrow">&lt;</text></view>
      <text class="mine-title">我的</text>
      <!-- 身份卡：登录态展示脱敏手机号；游客态点击跳独立登录页（带 redirect 回本页） -->
      <view class="user-card" @click="onUserCardTap">
        <view class="avatar-box"><text class="avatar-emoji">👤</text></view>
        <view class="user-info">
          <text v-if="hasToken" class="user-phone">{{ maskPhone(user.phone) }}</text>
          <text v-else class="user-phone user-phone-guest">点击登录</text>
          <text class="user-sub">{{ hasToken ? '欢迎回来，生鲜好货天天低价' : '登录后享优惠券/订单/评价服务' }}</text>
        </view>
        <text v-if="!hasToken" class="user-arrow">›</text>
      </view>
    </view>

    <!-- ==================== 2. 我的优惠券卡（登录态拉取，三态 tab 前端归类） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">我的优惠券</text></view>
      <!-- 游客态：登录引导文案 -->
      <view v-if="!hasToken" class="guide-row" @click="goLogin">
        <text class="guide-text">🔒 登录后查看我的优惠券</text>
        <text class="guide-go">去登录 ›</text>
      </view>
      <template v-else>
        <!-- 错误态：加载失败可点击重试（区别于空态，与订单页 M3-T-05 同口径） -->
        <view v-if="couponError" class="error-row" @click="loadCoupons">
          <text class="error-text">优惠券加载失败，请检查网络</text>
          <text class="error-retry">点击重试</text>
        </view>
        <template v-else>
          <!-- 三分 tab：可用 / 已使用 / 已过期（归类规则见 computed couponGroups 注释，严格执行） -->
          <view class="coupon-tabs">
            <view
              class="coupon-tab"
              v-for="t in couponTabs"
              :key="t.key"
              :class="{ active: couponTab === t.key }"
              @click="switchCouponTab(t.key)"
            >
              <text>{{ t.label }}</text>
            </view>
          </view>
          <!-- 券列表：可用态正常色，已使用/已过期置灰 -->
          <view v-if="currentCoupons.length > 0" class="coupon-list">
            <view
              class="coupon-item"
              v-for="c in currentCoupons"
              :key="c.id"
              :class="{ 'coupon-disabled': couponTab !== 'available' }"
            >
              <view class="coupon-face">
                <view class="coupon-amount-line">
                  <text class="coupon-symbol">¥</text>
                  <text class="coupon-amount">{{ fmtCouponAmount(c.amount) }}</text>
                </view>
                <text class="coupon-threshold">{{ fmtThreshold(c.threshold) }}</text>
              </view>
              <view class="coupon-body">
                <text class="coupon-main">{{ couponMainText(c) }}</text>
                <text class="coupon-expire">{{ couponExpireText(c) }}</text>
              </view>
              <text v-if="couponTab === 'used'" class="coupon-flag">已使用</text>
              <text v-else-if="couponTab === 'expired'" class="coupon-flag">已过期</text>
            </view>
          </view>
          <view v-else class="empty-row"><text class="empty-text">该状态下暂无优惠券</text></view>
        </template>
      </template>
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

    <!-- ==================== 4. 邀请好友行（F-01：整行跳邀请页，邀请码/复制链接/记录都在邀请页内） ==================== -->
    <view class="card cell-card" @click="goInvite">
      <view class="cell">
        <text class="cell-icon">🎁</text>
        <text class="cell-title">邀请好友</text>
        <text class="cell-sub">邀新人注册双方得券</text>
        <text class="cell-arrow">›</text>
      </view>
    </view>

    <!-- ==================== 5. 我的评价卡（登录态拉取；content 一律 {{ }} 文本插值渲染，严禁 v-html/rich-text 反转义，防存储型 XSS） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">我的评价</text></view>
      <!-- 游客态：登录引导文案 -->
      <view v-if="!hasToken" class="guide-row" @click="goLogin">
        <text class="guide-text">💬 登录后查看我的评价</text>
        <text class="guide-go">去登录 ›</text>
      </view>
      <template v-else>
        <!-- 错误态：加载失败可点击重试 -->
        <view v-if="reviewError" class="error-row" @click="loadReviews">
          <text class="error-text">评价加载失败，请检查网络</text>
          <text class="error-retry">点击重试</text>
        </view>
        <view v-else-if="reviews.length > 0" class="review-list">
          <view class="review-item" v-for="r in reviews" :key="r.id">
            <view class="review-top">
              <text class="review-dish">{{ r.dishName }}</text>
              <text class="review-badge" :class="'ra-' + reviewClass(r.auditStatus)">{{ reviewStatusText(r.auditStatus) }}</text>
            </view>
            <!-- content 文本插值（Vue 插值自动转义，无 v-html/rich-text） -->
            <text class="review-content">{{ r.content }}</text>
            <text v-if="formatDay(r.createdAt)" class="review-time">{{ formatDay(r.createdAt) }}</text>
          </view>
        </view>
        <view v-else class="empty-row"><text class="empty-text">暂无评价，去商品详情写下第一条吧</text></view>
      </template>
    </view>

    <!-- ==================== 6. 账号安全行（登录态下登录页会展示设置密码卡区，语义即设置/重置密码） ==================== -->
    <view class="card cell-card" @click="goSecurity">
      <view class="cell">
        <text class="cell-icon">🔐</text>
        <text class="cell-title">账号安全</text>
        <text class="cell-sub">设置/重置登录密码</text>
        <text class="cell-arrow">›</text>
      </view>
    </view>

    <!-- ==================== 7. 退出登录（仅登录态显示；二次确认后清登录态回首页） ==================== -->
    <button v-if="hasToken" class="btn-logout" @click="onLogout">退出登录</button>
    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 「我的」个人中心页（多多买菜工具风，白底+主红 #E02020）：
// 身份卡 / 优惠券三态卡 / 订单入口 / 评价列表 / 账号安全 / 退出登录。
// 登录态判定与渲染一律响应 store.user.token（mapState user）：
// token 失效接口 401 时 request 层统一清登录态，本页随 store 自动降级游客视图，不残留已登录 UI。
import { mapState } from 'vuex'
import { getMyCoupons, getMyReviews } from '@/api/index.js'

// 评价审核状态 -> 中文徽标（approved=已展示 / pending=审核中 / rejected=未通过）
const REVIEW_STATUS_MAP = { approved: '已展示', pending: '审核中', rejected: '未通过' }

// F-01 券名映射：邀请体系固定模板券的展示名（与后端 CouponServiceImpl.TEMPLATES 展示名一致：
// invite_reward=邀请奖励券 / newbie_gift=新人见面礼）；不在映射内的券按「满X减Y」通用文案展示
const COUPON_KEY_NAME = {
  invite_reward: '邀请奖励券',
  newbie_gift: '新人见面礼'
}

export default {
  data() {
    return {
      // 优惠券三分 tab
      couponTabs: [
        { key: 'available', label: '可用' },
        { key: 'used', label: '已使用' },
        { key: 'expired', label: '已过期' }
      ],
      couponTab: 'available',
      // 优惠券原始列表（getMyCoupons 全量返回，含 used 券，前端按三态归类）
      coupons: [],
      couponError: false,
      // 我的评价列表（getMyReviews，服务端按 dish_id 填充 dishName）
      reviews: [],
      reviewError: false
    }
  },
  computed: {
    ...mapState(['user']),
    // 是否已登录（token 鉴权）：401 时 request 层清登录态，此处响应式切换游客视图
    hasToken() { return !!(this.user && this.user.token) },
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
    // 当前 tab 的券列表
    currentCoupons() { return this.couponGroups[this.couponTab] || [] }
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
  onUnload() {
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
  },
  // #endif
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
    // ==================== 优惠券 ====================
    // tab 切换：仅本地归类结果切换，不重新请求
    switchCouponTab(key) { this.couponTab = key },
    // 券 expireAt -> 毫秒时间戳：null/空/解析失败一律返回 null（按"永不过期"语义归可用）
    couponExpireTs(c) {
      if (!c || c.expireAt == null || c.expireAt === '') return null
      const t = new Date(c.expireAt).getTime()
      return Number.isFinite(t) ? t : null
    },
    // 有效期文案：expireAt 缺失/解析失败显示「长期有效」，否则「有效期至 yyyy-MM-dd」
    couponExpireText(c) {
      const ts = this.couponExpireTs(c)
      if (ts == null) return '长期有效'
      return `有效期至 ${this.formatDay(ts)}`
    },
    // 券主文案：F-01 映射券（invite_reward/newbie_gift）前缀展示名（如「邀请奖励券·满50减15」），
    // 其余券维持「满X减Y」通用文案；threshold/amount 脏数据兜底（threshold 无效按无门槛「减X」）
    couponMainText(c) {
      const name = COUPON_KEY_NAME[c && c.couponKey]
      const t = Number(c && c.threshold)
      const a = Number(c && c.amount)
      if (!Number.isFinite(a)) return name || '优惠券'
      const cut = Number.isFinite(t) && t > 0
        ? `满${this.fmtCouponAmount(t)}减${this.fmtCouponAmount(a)}`
        : `减${this.fmtCouponAmount(a)}`
      return name ? `${name}·${cut}` : cut
    },
    // 券面金额：整数原样展示，小数保留两位，脏数据按 0 兜底
    fmtCouponAmount(v) {
      const n = Number(v)
      if (!Number.isFinite(n)) return '0'
      return Number.isInteger(n) ? String(n) : n.toFixed(2)
    },
    // 券面门槛小字：满X可用 / 无门槛
    fmtThreshold(v) {
      const n = Number(v)
      return Number.isFinite(n) && n > 0 ? `满${this.fmtCouponAmount(n)}可用` : '无门槛'
    },
    // ==================== 评价 ====================
    // 审核状态文案：approved 展示（兼容实体 NULL=正常展示语义）/ pending 审核中 / rejected 未通过；未知状态按「审核中」兜底
    reviewStatusText(s) {
      if (s == null || s === 'approved') return REVIEW_STATUS_MAP.approved
      return REVIEW_STATUS_MAP[s] || REVIEW_STATUS_MAP.pending
    },
    // 审核状态样式 class 后缀：未知状态走 pending 样式
    reviewClass(s) {
      if (s == null || s === 'approved') return 'approved'
      return REVIEW_STATUS_MAP[s] ? s : 'pending'
    },
    // ==================== 通用 ====================
    // 时间 -> yyyy-MM-dd（脏数据返回空串由模板隐藏）
    formatDay(v) {
      if (!v) return ''
      const d = new Date(v)
      if (!Number.isFinite(d.getTime())) return ''
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    },
    // 手机号脱敏兜底展示（口径照订单页 order.vue 同款正则）：138****1234
    maskPhone(p) {
      const s = String(p || '')
      return /^1[3-9]\d{9}$/.test(s) ? s.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2') : s
    },
    // 身份卡点击：游客态去登录；登录态无动作
    onUserCardTap() {
      if (!this.hasToken) this.goLogin()
    },
    // 游客态登录入口：跳独立登录页并携带 redirect，登录成功后解码回跳本页
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/mine/mine') }) },
    // 账号安全行：同入口跳登录页（登录态下该页展示设置密码卡区，语义即设置/重置密码）
    goSecurity() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/mine/mine') }) },
    // 我的订单行：整行跳订单页（四状态 tab 在订单页内部）
    goOrder() { uni.navigateTo({ url: '/pages/order/order' }) },
    // 邀请好友行（F-01）：整行跳邀请页（邀请码大卡/复制链接/规则/统计/记录都在邀请页内）
    goInvite() { uni.navigateTo({ url: '/pages/invite/invite' }) },
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
}
.avatar-emoji { font-size: 52rpx; }
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
.card-head { margin-bottom: 20rpx; }
.card-title { font-size: 30rpx; font-weight: 700; color: #333333; }

/* 游客态登录引导行 */
.guide-row {
  display: flex; align-items: center; justify-content: space-between;
  background-color: #FFF7F0; border-radius: 12rpx;
  padding: 24rpx;
}
.guide-text { font-size: 26rpx; color: #666666; }
.guide-go { flex-shrink: 0; margin-left: 16rpx; font-size: 26rpx; font-weight: 600; color: #E02020; }

/* 错误态行（可点击重试） */
.error-row { display: flex; flex-direction: column; align-items: center; gap: 16rpx; padding: 32rpx 0; }
.error-text { font-size: 24rpx; color: #999999; }
.error-retry {
  font-size: 24rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 28rpx;
  padding: 8rpx 36rpx; line-height: 1.4;
}

/* ==================== 2. 优惠券三分 tab（照订单页 status-tab 样式） ==================== */
.coupon-tabs { display: flex; gap: 12rpx; margin-bottom: 20rpx; }
.coupon-tab {
  flex: 1; text-align: center;
  font-size: 26rpx; color: #666666;
  background-color: #F5F5F5; border-radius: 24rpx;
  padding: 12rpx 0; border: 1rpx solid #EEEEEE;
}
.coupon-tab.active { color: #FFFFFF; font-weight: 700; background-color: #E02020; border-color: #E02020; }

/* 券项：左券面（红字金额+门槛）+ 右主体（满X减Y+有效期）+ 状态角标 */
.coupon-list { display: flex; flex-direction: column; gap: 16rpx; }
.coupon-item {
  display: flex; align-items: center; gap: 20rpx;
  border: 1rpx solid #FFE3E0; border-radius: 12rpx;
  padding: 20rpx;
  background: linear-gradient(90deg, #FFF5F4, #FFFFFF);
}
.coupon-face {
  flex-shrink: 0; width: 170rpx; box-sizing: border-box;
  display: flex; flex-direction: column; align-items: center; gap: 4rpx;
  border-right: 1rpx dashed #F0C8C5;
  padding-right: 20rpx;
}
.coupon-amount-line { display: flex; align-items: baseline; color: #E02020; }
.coupon-symbol { font-size: 24rpx; font-weight: 700; }
.coupon-amount { font-size: 44rpx; font-weight: 700; line-height: 1; }
.coupon-threshold { font-size: 20rpx; color: #E02020; }
.coupon-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.coupon-main { font-size: 28rpx; font-weight: 700; color: #333333; }
.coupon-expire { font-size: 22rpx; color: #999999; }
.coupon-flag {
  flex-shrink: 0;
  font-size: 20rpx; font-weight: 700; color: #999999;
  border: 1rpx solid #DDDDDD; border-radius: 20rpx;
  padding: 4rpx 14rpx;
}
/* 已使用/已过期券整体置灰弱化 */
.coupon-disabled { opacity: .55; filter: grayscale(.6); }

/* 空态行 */
.empty-row { display: flex; justify-content: center; padding: 40rpx 0; }
.empty-text { font-size: 24rpx; color: #CCCCCC; }

/* ==================== 3/4/6. cell 行（我的订单 / 邀请好友 / 账号安全） ==================== */
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

/* ==================== 5. 评价列表 ==================== */
.review-list { display: flex; flex-direction: column; }
.review-item { padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5; }
.review-item:last-child { border-bottom: none; padding-bottom: 4rpx; }
.review-top { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; margin-bottom: 10rpx; }
.review-dish {
  flex: 1; min-width: 0;
  font-size: 26rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.review-badge { flex-shrink: 0; font-size: 20rpx; font-weight: 700; padding: 4rpx 14rpx; border-radius: 20rpx; }
/* 审核状态徽标：approved 绿 / pending 橙 / rejected 灰（配色照订单页支付徽标体系） */
.ra-approved { color: #07C160; background-color: #E8F8EF; }
.ra-pending { color: #FF6600; background-color: #FFF3EB; }
.ra-rejected { color: #999999; background-color: #F5F5F5; }
.review-content { display: block; font-size: 24rpx; color: #666666; line-height: 1.6; word-break: break-all; white-space: pre-wrap; }
.review-time { display: block; margin-top: 8rpx; font-size: 20rpx; color: #CCCCCC; }

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
