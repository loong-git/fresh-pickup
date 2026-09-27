<template>
  <view class="page-container">
    <!-- ==================== 我的优惠券卡区（F-10：自领券中心页整块迁出为独立页；三态 tab 唯一承载点，mine 概要行直达本页） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">我的优惠券</text></view>
      <!-- 游客态：登录引导文案（redirect 回本页，登录成功后 onShow 自动刷新数据） -->
      <view v-if="!hasToken" class="guide-row" @click="goLogin">
        <text class="guide-text">🔒 登录后查看我的优惠券</text>
        <text class="guide-go">去登录 ›</text>
      </view>
      <template v-else>
        <!-- 错误态：加载失败可点击重试（区别于空态） -->
        <view v-if="couponError" class="error-row" @click="loadCoupons">
          <text class="error-text">优惠券加载失败，请检查网络</text>
          <text class="error-retry">点击重试</text>
        </view>
        <template v-else>
          <!-- 三分 tab：可用 / 已使用 / 已过期（归类规则见 computed couponGroups 注释，与我的页 mine.vue 口径严格一致） -->
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

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 我的优惠券页（F-10：自领券中心页「我的优惠券」卡区整块迁出为独立页，修订 F-09 的合并承载方案）：
// 三分 tab（可用/已使用/已过期）唯一承载点，归类与渲染逻辑照原 mine.vue 口径（含 expire_at 过滤与券名映射）；
// 数据流 getMyCoupons（GET /api/coupons，登录）：onShow 刷新、错误可重试、空态、游客登录引导（goLogin 带 redirect 回本页）。
// 登录态判定与渲染响应 store.user.token（mapState user）：401 时 request 层统一清登录态，
// 本页随 store 自动降级游客视图，不残留已登录 UI。
import { mapState } from 'vuex'
import { getMyCoupons } from '@/api/index.js'

// F-05 券名映射：固定模板券的展示名（与后端 CouponServiceImpl.TEMPLATES 展示名一致，同 coupons.vue）；
// 不在映射内的券按「满X减Y」通用文案展示
const COUPON_KEY_NAME = {
  newbie_gift: '新人见面礼'
}

export default {
  data() {
    return {
      // 三分 tab
      couponTabs: [
        { key: 'available', label: '可用' },
        { key: 'used', label: '已使用' },
        { key: 'expired', label: '已过期' }
      ],
      couponTab: 'available',
      // 优惠券原始列表（getMyCoupons 全量返回，含 used 券，前端按三态归类）
      coupons: [],
      couponError: false
    }
  },
  computed: {
    ...mapState(['user']),
    // 是否已登录（token 鉴权）：401 时 request 层清登录态，此处响应式切换游客视图
    hasToken() { return !!(this.user && this.user.token) },
    // 优惠券三态归类（口径与我的页 mine.vue 严格一致）：
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
    // 每次进入刷新（从登录页回跳后登录态变化同步数据）；未登录不发请求
    if (this.hasToken) {
      this.loadCoupons()
    }
  },
  watch: {
    // 登录态变化兜底：会话内被 401 清态时随 store 清空已登录数据，切游客视图不残留
    hasToken(v) {
      if (v) {
        this.loadCoupons()
      } else {
        this.coupons = []
        this.couponError = false
      }
    }
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照我的页/领券中心同款方案）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  // 滚轮监听仅 H5 注册（上方 onReady #ifdef 内），非 H5 端 _pageWheelHandler 恒为 undefined，清理分支天然跳过
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
    // GET /api/coupons（登录）：我的优惠券全量（含 used），三分归类见 couponGroups；
    // 401（token 失效）：request 层已清登录态，页面响应式切游客视图，不再置错误态；其余失败显示错误态可重试
    async loadCoupons() {
      if (!this.hasToken) return
      try {
        const res = await getMyCoupons()
        this.coupons = Array.isArray(res && res.data) ? res.data : []
        this.couponError = false
      } catch (e) {
        if (!e || e.code !== 401) this.couponError = true
      }
    },
    // ==================== 三分 tab ====================
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
    // 时间 -> yyyy-MM-dd（脏数据返回空串由模板隐藏）
    formatDay(v) {
      if (!v) return ''
      const d = new Date(v)
      if (!Number.isFinite(d.getTime())) return ''
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    },
    // 游客态登录入口：跳独立登录页并携带 redirect，登录成功后解码回跳本页（F-10 独立页路径）
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/my-coupons/my-coupons') }) },
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
/* ==================== 多多买菜风格 — 我的优惠券页（白底 + 主红 #E02020，F-10 自领券中心页随块迁移） ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; box-sizing: border-box; }

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

/* ==================== 券项（左券面+右主体） ==================== */
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
/* 已使用/已过期券整体置灰弱化（照我的页） */
.coupon-disabled { opacity: .55; filter: grayscale(.6); }

/* ==================== 三分 tab（照我的页样式） ==================== */
.coupon-tabs { display: flex; gap: 12rpx; margin-bottom: 20rpx; }
.coupon-tab {
  flex: 1; text-align: center;
  font-size: 26rpx; color: #666666;
  background-color: #F5F5F5; border-radius: 24rpx;
  padding: 12rpx 0; border: 1rpx solid #EEEEEE;
}
.coupon-tab.active { color: #FFFFFF; font-weight: 700; background-color: #E02020; border-color: #E02020; }

/* 空态行 */
.empty-row { display: flex; justify-content: center; padding: 40rpx 0; }
.empty-text { font-size: 24rpx; color: #CCCCCC; }

.page-bottom-space { height: env(safe-area-inset-bottom); }
</style>
