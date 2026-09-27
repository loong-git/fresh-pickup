<template>
  <view class="page-container">
    <!-- ==================== 1. 红色头部（白卡叠红头，多多买菜风格） ==================== -->
    <view class="coupons-header">
      <text class="coupons-slogan">领券中心</text>
      <text class="coupons-slogan-sub">新人见面礼与天天领券，下单更划算</text>
    </view>

    <!-- ==================== 2. 可领券卡片区（GET /api/coupons/center：满X减Y + 立即领取/已领取置灰） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">可领券</text></view>
      <!-- 游客态：登录引导（redirect 回本页） -->
      <view v-if="!hasToken" class="guide-row" @click="goLogin">
        <text class="guide-text">🔒 登录后领取优惠券</text>
        <text class="guide-go">去登录 ›</text>
      </view>
      <template v-else>
        <!-- 错误态：加载失败可点击重试（与我的页错误行同口径） -->
        <view v-if="centerError" class="error-row" @click="loadCenter">
          <text class="error-text">可领券加载失败，请检查网络</text>
          <text class="error-retry">点击重试</text>
        </view>
        <!-- 券卡列表：claimed=true 直出「已领取」置灰（展示态，限领以 claim 接口后端校验为准） -->
        <view v-else-if="centerCoupons.length > 0" class="center-list">
          <view class="coupon-item" v-for="(c, i) in centerCoupons" :key="c.couponKey || i">
            <view class="coupon-face">
              <view class="coupon-amount-line">
                <text class="coupon-symbol">¥</text>
                <text class="coupon-amount">{{ fmtCouponAmount(c.amount) }}</text>
              </view>
              <text class="coupon-threshold">{{ fmtThreshold(c.threshold) }}</text>
            </view>
            <view class="coupon-body">
              <text class="coupon-main">{{ couponMainText(c) }}</text>
              <text class="coupon-desc">领取后可在「我的优惠券」中查看使用</text>
            </view>
            <button
              class="btn-claim"
              :class="{ 'btn-claim-disabled': c.claimed || claimingKey === c.couponKey }"
              :disabled="c.claimed || claimingKey === c.couponKey"
              @click="onClaim(c)"
            ><text>{{ claimBtnText(c) }}</text></button>
          </view>
        </view>
        <view v-else class="empty-row">
          <text class="empty-text">{{ centerLoaded ? '暂无可领取的优惠券' : '加载中…' }}</text>
        </view>
      </template>
    </view>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 领券中心页（F-05/F-10，多多买菜风格，白底+主红 #E02020，默认导航栏标题「领券中心」）：
// F-10 起本页还原为纯领券中心——仅「可领券」卡片区（GET /api/coupons/center：满X减Y + 立即领取/已领取置灰，
// 领取走既有 claim 接口）；「我的优惠券」三分 tab 已拆分为独立页 pages/my-coupons/my-coupons（mine 概要行直达），
// F-09 引入的 mine 概要行滚动定位机制随拆分整体废除（本页无 onLoad/滚动定位逻辑）。
// F-05 修订：领取失败 toast 透出后端 message（含 newbie_gift「每人限领 1 张」lifetime 拒绝）并刷新列表，
// 失败不静默（对齐全站无静默口径）；claimed 置灰为展示态，实际限领以 claim 接口后端校验为准。
// 登录态判定与渲染响应 store.user.token（mapState user）：401 时 request 层统一清登录态，
// 本页随 store 自动降级游客视图；未登录 goLogin 带 redirect 回本页。
import { mapState } from 'vuex'
import { getCouponCenter, claimCoupon } from '@/api/index.js'

// F-05 券名映射：固定模板券的展示名（与后端 CouponServiceImpl.TEMPLATES 展示名一致，我的优惠券页同款）；
// 券轨下线的「邀请奖励券」模板已随 F-05.1 删除（存量券回落通用文案，不影响核销），
// 不在映射内的券按「满X减Y」通用文案展示
const COUPON_KEY_NAME = {
  newbie_gift: '新人见面礼'
}

export default {
  data() {
    return {
      // ===== 可领券区（/api/coupons/center）=====
      centerCoupons: [],
      centerError: false,
      centerLoaded: false,
      centerLoading: false,
      claimingKey: '' // 领取中的 couponKey：按钮置「领取中…」防重复提交
    }
  },
  computed: {
    ...mapState(['user']),
    // 是否已登录（token 鉴权）：401 时 request 层清登录态，此处响应式切换游客视图
    hasToken() { return !!(this.user && this.user.token) }
  },
  onShow() {
    // 每次进入刷新（从登录页回跳后登录态变化同步数据）；未登录不发请求
    if (this.hasToken) {
      this.loadCenter()
    }
  },
  watch: {
    // 登录态变化兜底：会话内被 401 清态时随 store 清空已登录数据，切游客视图不残留
    hasToken(v) {
      if (v) {
        this.loadCenter()
      } else {
        this.centerCoupons = []
        this.centerError = false
        this.centerLoaded = false
        this.claimingKey = ''
      }
    }
  },
  // H5 滚轮兜底仅 H5 注册（#ifdef 内），非 H5 端 _pageWheelHandler 恒为 undefined，清理分支天然跳过
  onReady() {
    // #ifdef H5
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照我的页同款方案）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
    // #endif
  },
  onUnload() {
    // #ifdef H5
    // 页面级滚轮兜底监听清理（与 onReady 注册配对）
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
  },
  methods: {
    // ==================== 数据加载 ====================
    // GET /api/coupons/center（登录，token 由 request 层自动携带）：可领券卡片区数据源
    async loadCenter() {
      if (!this.hasToken || this.centerLoading) return
      this.centerLoading = true
      try {
        const res = await getCouponCenter()
        this.centerCoupons = Array.isArray(res && res.data) ? res.data : []
        this.centerLoaded = true
        this.centerError = false
      } catch (e) {
        // 401（token 失效）：request 层已清登录态，页面响应式切游客视图，不再置错误态；其余失败显示错误态可重试
        if (!e || e.code !== 401) this.centerError = true
      } finally {
        this.centerLoading = false
      }
    },
    // ==================== 领取 ====================
    // POST /api/coupons/claim（既有接口）：成功 toast + 刷新可领券列表（claimed 置灰联动）；
    // 失败（F-05.2 修订）：toast 透出后端 message——含 newbie_gift lifetime 拒绝「每人限领 1 张」——并刷新列表，
    // 不新增静默分支（claimed 置灰只是展示态，后端拒绝可能因 used/过期后重领等校验）
    onClaim(c) {
      const key = c && c.couponKey
      if (!key || c.claimed || this.claimingKey) return
      this.claimingKey = key
      claimCoupon(key).then(() => {
        uni.showToast({ title: '领取成功', icon: 'success' })
        this.loadCenter()
      }).catch((e) => {
        uni.showToast({ title: (e && e.message) || '领取失败，请稍后重试', icon: 'none' })
        this.loadCenter()
      }).finally(() => {
        this.claimingKey = ''
      })
    },
    // 领取按钮文案三态：已领取（claimed 置灰）/ 领取中…（提交防重）/ 立即领取
    claimBtnText(c) {
      if (c.claimed) return '已领取'
      if (this.claimingKey === c.couponKey) return '领取中…'
      return '立即领取'
    },
    // ==================== 券名/金额渲染（可领券区共用） ====================
    // 券主文案：映射券（newbie_gift）前缀展示名（如「新人见面礼·满30减8」），
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
    // ==================== 通用 ====================
    // 游客态登录入口：跳独立登录页并携带 redirect，登录成功后解码回跳本页
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/coupons/coupons') }) },
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
/* ==================== 多多买菜风格 — 领券中心页（白底 + 主红 #E02020） ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; box-sizing: border-box; }

/* ==================== 1. 红色头部 ==================== */
.coupons-header {
  position: relative;
  background: linear-gradient(180deg, #E02020 0%, #E02020 55%, #F5F5F5 100%);
  padding: 48rpx 24rpx 8rpx;
}
.coupons-slogan { display: block; text-align: center; font-size: 38rpx; font-weight: 700; color: #FFFFFF; line-height: 1.3; }
.coupons-slogan-sub { display: block; margin-top: 12rpx; text-align: center; font-size: 22rpx; color: rgba(255, 255, 255, 0.92); line-height: 1.5; }

/* ==================== 白卡通用（照我的页） ==================== */
.card {
  background-color: #FFFFFF; border-radius: 16rpx;
  margin: 20rpx 24rpx 0;
  padding: 24rpx;
}
.card-head { margin-bottom: 20rpx; }
.card-title { font-size: 30rpx; font-weight: 700; color: #333333; }

/* 游客态登录引导行（照我的页） */
.guide-row {
  display: flex; align-items: center; justify-content: space-between;
  background-color: #FFF7F0; border-radius: 12rpx;
  padding: 24rpx;
}
.guide-text { font-size: 26rpx; color: #666666; }
.guide-go { flex-shrink: 0; margin-left: 16rpx; font-size: 26rpx; font-weight: 600; color: #E02020; }

/* 错误态行（可点击重试，照我的页） */
.error-row { display: flex; flex-direction: column; align-items: center; gap: 16rpx; padding: 32rpx 0; }
.error-text { font-size: 24rpx; color: #999999; }
.error-retry {
  font-size: 24rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 28rpx;
  padding: 8rpx 36rpx; line-height: 1.4;
}

/* ==================== 2. 券项（左券面+右主体，右侧为领取按钮；我的优惠券页列表样式独立持有） ==================== */
.center-list { display: flex; flex-direction: column; gap: 16rpx; }
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
.coupon-desc { font-size: 20rpx; color: #BBBBBB; }

/* 领取按钮（红底白字胶囊；已领取/领取中置灰；显式覆盖 uni-button 默认样式） */
.btn-claim {
  flex-shrink: 0;
  margin: 0; padding: 0 28rpx;
  height: 64rpx; line-height: 64rpx;
  background-color: #E02020; color: #FFFFFF;
  font-size: 24rpx; font-weight: 700;
  border: none; border-radius: 999rpx;
}
.btn-claim.btn-claim-disabled { background-color: #CCCCCC; color: rgba(255, 255, 255, 0.9); }

/* 空态行 */
.empty-row { display: flex; justify-content: center; padding: 40rpx 0; }
.empty-text { font-size: 24rpx; color: #CCCCCC; }

.page-bottom-space { height: env(safe-area-inset-bottom); }
</style>
