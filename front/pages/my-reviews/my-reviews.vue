<template>
  <view class="page-container">
    <!-- ==================== 我的评价卡区（F-11：自我的页整块迁出为独立页；评论列表唯一承载点，mine 概要行直达本页；XSS 口径见文件头） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">我的评价</text></view>
      <!-- 游客态：登录引导文案（redirect 回本页，登录成功后 onShow 自动刷新数据） -->
      <view v-if="!hasToken" class="guide-row" @click="goLogin">
        <text class="guide-text">💬 登录后查看我的评价</text>
        <text class="guide-go">去登录 ›</text>
      </view>
      <template v-else>
        <!-- 错误态：加载失败可点击重试（区别于空态） -->
        <view v-if="reviewError" class="error-row" @click="loadReviews">
          <text class="error-text">评价加载失败，请检查网络</text>
          <text class="error-retry">点击重试</text>
        </view>
        <!-- 列表态：dishName falsy 显示「商品已删除」占位并整行禁点（canJump falsy 口径） -->
        <view v-else-if="reviews.length > 0" class="review-list">
          <view
            class="review-item"
            v-for="r in reviews"
            :key="r.id"
            :class="{ 'review-item-disabled': !canJump(r) }"
            @click="goDetail(r)"
          >
            <view class="review-top">
              <text class="review-dish">{{ r.dishName || '商品已删除' }}</text>
              <text class="review-badge" :class="'ra-' + reviewClass(r.auditStatus)">{{ reviewStatusText(r.auditStatus) }}</text>
            </view>
            <!-- 星级行：五星 ★ 照详情页 preview-stars 惯例（rating 脏数据经 clampRating 截断防御） -->
            <view class="review-stars">
              <text v-for="s in 5" :key="s" :class="s <= clampRating(r.rating) ? 'star active' : 'star'">★</text>
            </view>
            <!-- content 文本插值（Vue 插值自动转义，无 v-html/rich-text） -->
            <text class="review-content">{{ r.content }}</text>
            <view class="review-bottom">
              <text v-if="formatDay(r.createdAt)" class="review-time">{{ formatDay(r.createdAt) }}</text>
              <text v-if="canJump(r)" class="review-arrow">›</text>
            </view>
          </view>
        </view>
        <view v-else class="empty-row"><text class="empty-text">暂无评价，去商品详情写下第一条吧</text></view>
      </template>
    </view>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 我的评价页（F-11：自我的页「我的评价」卡区整块迁出为独立页，与 F-10 券拆分同模式）：
// 评论列表唯一承载点（dishName + 审核状态徽章 + rating 星级 + content + 时间），点条目跳对应商品详情页；
// 数据流 getMyReviews（GET /api/reviews/mine，登录）：onShow 刷新、错误可重试、空态、游客登录引导（goLogin 带 redirect 回本页）。
// XSS 硬约束：content 一律 {{ }} 文本插值渲染，严禁 v-html/rich-text 反转义（后端入库已 HtmlUtils.htmlEscape，前端插值是第二道防线）。
// 登录态判定与渲染响应 store.user.token（mapState user）：401 时 request 层统一清登录态，
// 本页随 store 自动降级游客视图，不残留已登录 UI。
import { mapState } from 'vuex'
import { getMyReviews } from '@/api/index.js'

// 评价审核状态 -> 中文徽标（approved=已展示 / pending=审核中 / rejected=未通过）
const REVIEW_STATUS_MAP = { approved: '已展示', pending: '审核中', rejected: '未通过' }

export default {
  data() {
    return {
      // 我的评价原始列表（getMyReviews 全量返回，无分页；服务端按 dish_id 填充 dishName）
      reviews: [],
      reviewError: false
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
      this.loadReviews()
    }
  },
  watch: {
    // 登录态变化兜底：会话内被 401 清态时随 store 清空已登录数据，切游客视图不残留
    hasToken(v) {
      if (v) {
        this.loadReviews()
      } else {
        this.reviews = []
        this.reviewError = false
      }
    }
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照我的优惠券页同款方案）
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
    // GET /api/reviews/mine（登录）：我的评价全量（无分页）；
    // 401（token 失效）：request 层已清登录态，页面响应式切游客视图，不再置错误态；其余失败显示错误态可重试
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
    // ==================== 列表项 ====================
    // 整行点击跳商品详情：仅 canJump 通过（dishId 存在且 dishName 非空）才发起跳转；
    // 真实脏态 = dish_id 指向不存在的 dish（历史脏数据/手工库操作）→ dishName=null → 禁点置灰
    goDetail(r) {
      if (!r || !this.canJump(r)) return
      uni.navigateTo({ url: '/pages/detail/detail?id=' + r.dishId })
    },
    // 禁点口径：dish_id 列 NOT NULL（dishId=null 按 schema 不可发生，保留为纯防御）；
    // dishName 用 falsy 口径（同时覆盖 null 与空串），与展示兜底「商品已删除」一致；
    // detail 页对已下架/无效 dish 自有兜底空态（T-M3-05），本页不做预检
    canJump(r) {
      return r != null && r.dishId != null && !!r.dishName
    },
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
    // 评价星级截断到 [1,5]（照 detail.vue T-M3-07）：rating 缺失/超界/非数字均安全兜底
    clampRating(v) {
      const n = Number(v)
      if (!Number.isFinite(n)) return 1
      return Math.min(5, Math.max(1, n))
    },
    // ==================== 通用 ====================
    // 时间 -> yyyy-MM-dd（脏数据返回空串由模板隐藏）
    formatDay(v) {
      if (!v) return ''
      const d = new Date(v)
      if (!Number.isFinite(d.getTime())) return ''
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    },
    // 游客态登录入口：跳独立登录页并携带 redirect，登录成功后解码回跳本页（F-11 独立页路径）
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/my-reviews/my-reviews') }) },
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
/* ==================== 多多买菜风格 — 我的评价页（白底 + 主红 #E02020，F-11 自我的页随块迁移） ==================== */

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

/* ==================== 评价列表项 ==================== */
.review-list { display: flex; flex-direction: column; }
/* 可点行手型光标（H5 PC 端可点/禁点区分，照 detail.vue .review-entry 评价入口惯例） */
.review-item { padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5; cursor: pointer; }
.review-item:last-child { border-bottom: none; padding-bottom: 4rpx; }
/* 脏数据行（dishName 缺失，禁点）整体弱化：仅降透明度（opacity .55 为 F-11 规格钦定字面，券页惯例的 grayscale 滤镜未随迁）；cursor 显式回退 default 与可点行区分 */
.review-item-disabled { opacity: .55; cursor: default; }
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
/* 星级行（照详情页 preview-stars 逐键，容器改名 review-stars） */
.review-stars { display: flex; gap: 2rpx; margin-bottom: 8rpx; }
.star { font-size: 22rpx; color: #DDDDDD; }
.star.active { color: #FF6600; }
.review-content { display: block; font-size: 24rpx; color: #666666; line-height: 1.6; word-break: break-all; white-space: pre-wrap; }
/* 底部行：时间 + 右箭头两端对齐，与 content 的间距由本行 margin-top 承载 */
.review-bottom { display: flex; align-items: center; justify-content: space-between; margin-top: 8rpx; }
/* 时间从独立块改行内元素并去掉 margin-top（防与 review-bottom 的 margin-top 双重间距） */
.review-time { font-size: 20rpx; color: #CCCCCC; }
/* 右箭头（可跳行展示，观感照我的页 cell-arrow） */
.review-arrow { flex-shrink: 0; font-size: 34rpx; color: #CCCCCC; }

/* 空态行 */
.empty-row { display: flex; justify-content: center; padding: 40rpx 0; }
.empty-text { font-size: 24rpx; color: #CCCCCC; }

.page-bottom-space { height: env(safe-area-inset-bottom); }
</style>
