<template>
  <view class="page-container">
    <!-- 顶部商品简要信息 -->
    <view class="dish-brief" v-if="dish">
      <view class="dish-emoji" :style="{ backgroundColor: dish.bgColor }">{{ dish.emoji }}</view>
      <view class="dish-info">
        <text class="dish-name">{{ dish.name }}</text>
        <text class="dish-price">¥{{ dish.price }}/{{ dish.unit }}</text>
      </view>
    </view>

    <!-- 评价列表 -->
    <view class="review-section">
      <view class="section-header">
        <text class="section-title">商品评价</text>
        <!-- S4（P4-3）：计数须经 loaded——加载中 reviews 恒空，此处会先报出一句假的「共 0 条」 -->
        <text class="review-count" v-if="loaded && reviews.length > 0">共 {{ reviews.length }} 条</text>
      </view>

      <!-- 错误态（M3-T-05 部分）：接口异常兜底，区别于"暂无评价"空态，可点击重试 -->
      <view v-if="loadError" class="review-empty">
        <text class="empty-icon">⚠️</text>
        <text class="empty-text">评价加载失败，请检查网络</text>
        <button class="btn-retry" @click="loadAll">点击重试</button>
      </view>
      <view v-else-if="reviews.length > 0" class="review-list">
        <view class="review-item" v-for="(r, idx) in reviews" :key="r.id || idx">
          <view class="review-top">
            <view class="review-user">
              <text class="review-avatar">{{ reviewerName(r).charAt(0) }}</text>
              <text class="review-name">{{ reviewerName(r) }}</text>
            </view>
            <view class="review-stars">
              <!-- M3-T-07：rating [1,5] 截断，脏数据不越界 -->
              <text v-for="s in 5" :key="s" :class="s <= clampRating(r.rating) ? 'star active' : 'star'">★</text>
            </view>
          </view>
          <!-- M3-T-07：超长内容默认折叠 2 行，可展开/收起 -->
          <text class="review-content" :class="{ expanded: expandedReviews[r.id || idx] }">{{ r.content }}</text>
          <text v-if="(r.content || '').length > 60" class="review-expand" @click="toggleExpand(r.id || idx)">{{ expandedReviews[r.id || idx] ? '收起' : '展开全部' }}</text>
          <text class="review-time">{{ formatTime(r.createTime) }}</text>
        </view>
      </view>
      <!-- S4（P4-3）：请求返回过之前不得断言「暂无评价」——「还没读到」不是「没有」 -->
      <view v-else-if="!loaded" class="review-empty">
        <text class="empty-text">正在加载…</text>
      </view>
      <view v-else-if="loaded" class="review-empty">
        <text class="empty-icon">📝</text>
        <text class="empty-text">暂无评价，快来抢沙发~</text>
      </view>
    </view>

    <!-- 底部发表按钮 -->
    <view class="bottom-bar">
      <button class="btn-write" @click="openWrite">写评价</button>
    </view>

    <!-- 写评价弹窗 -->
    <view class="popup-mask" v-if="showPopup" @click="showPopup = false">
      <view class="popup-panel" @click.stop>
        <view class="popup-header">
          <text class="popup-title">发表评价</text>
          <text class="popup-close" @click="showPopup = false">✕</text>
        </view>
        <view class="popup-body">
          <view class="rating-row">
            <text class="rating-label">评分</text>
            <view class="star-picker">
              <text v-for="s in 5" :key="s" :class="s <= newRating ? 'star active' : 'star'" @click="newRating = s">★</text>
            </view>
          </view>
          <view class="form-group">
            <textarea class="review-textarea" v-model="reviewContent" placeholder="分享您的购物体验..." maxlength="200" :adjust-position="true" cursor-spacing="24" />
            <text class="char-count">{{ reviewContent.length }}/200</text>
          </view>
          <!-- P4-2（D3）提交单飞：submitting 中灰态禁用 + 文案切换（姿势照 index.vue btn-confirm）；同帧双击由方法首行早退拦 -->
          <button class="btn-submit" :class="{ 'btn-submit-disabled': submitting }" :disabled="submitting" @click="submitReview">{{ submitting ? '提交中…' : '提交评价' }}</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getDishDetail, getReviews, submitReview } from '@/api/index.js'

// M4-T-05：本地预检硬词表（与后端 backEnd/src/main/resources/sensitive-words.txt 硬词同款，
// 共 10 个，后端词表更新时须同步此数组）。命中硬词前端直接拦截不提交、保留输入；
// 软词（差评/骗子/假货，词表第二列标 soft）交由后端入库 audit_status='pending'，前端不拦截
const HARD_WORDS = ['赌博', '诈骗', '加微信', '代刷', '刷单', '博彩', '色情', '外挂', '私下转账', '代开发票']

export default {
  data() {
    return {
      dishId: null,
      dish: null,
      reviews: [],
      // 错误态标记（M3-T-05 部分）：首屏接口异常时置 true，显示错误态而非整页白屏/空态
      loadError: false,
      // S4（P4-3）：评价请求是否已返回过（成功/失败都置真，口径同 free.vue）
      // —— 空态与「共 N 条」计数都必须经它把关，否则请求在飞时报「暂无评价」/「共 0 条」
      loaded: false,
      // 长文本展开状态表（M3-T-07）：key 为评价 id，true = 已展开
      expandedReviews: {},
      showPopup: false,
      newRating: 5,
      reviewContent: '',
      // P4-2（D3）提交单飞标志：校验全过后置 true，finally 退回（成功/失败都退），失败后可再点重试
      submitting: false
    }
  },
  async onLoad(options) {
    this.dishId = parseInt(options.dishId)
    // M3-T-05 部分：加载失败兜底（错误态 + 重试），不再整页白屏
    await this.loadAll()
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
    // 拉取商品简要信息与评价列表：任一失败置 loadError 显示错误态（≠"暂无评价"空态）
    async loadAll() {
      this.loadError = false
      // 每次拉取都退回「没返回过」：错误态刚被清掉，若 loaded 还留着上一轮的真值，
      // 重试在飞的几百毫秒里判据会是「返回过 + 无错误 + 空列表」→「暂无评价」又替系统撒一次谎。
      // 手里已有评价时不受影响：列表分支在 v-else 链里先命中，不会改闪成加载行
      this.loaded = false
      try {
        const [dishRes, reviewRes] = await Promise.all([
          getDishDetail(this.dishId),
          getReviews(this.dishId)
        ])
        if (dishRes && dishRes.data) this.dish = dishRes.data
        this.reviews = reviewRes && Array.isArray(reviewRes.data) ? reviewRes.data : []
        this.loaded = true
      } catch (e) {
        this.loadError = true
        // 失败同样算「请求返回过」（D1）：错误行与加载行互斥，加载行不得在失败后常驻
        this.loaded = true
      }
    },
    // 评价人昵称兜底（M3-T-07）：脏数据 reviewer=null 显示"匿名"，避免 charAt(0) 报错
    reviewerName(r) { return (r && r.reviewer) ? String(r.reviewer) : '匿名' },
    // 评分 [1,5] 截断（M3-T-07）：null/非数字按 0 显示无星，有效值钳位到 1~5 并取整
    clampRating(v) {
      const n = Number(v)
      if (!Number.isFinite(n) || n <= 0) return 0
      return Math.min(5, Math.max(1, Math.round(n)))
    },
    // 长文本展开/收起切换（M3-T-07）：超 60 字折叠为 2 行，点击切换
    toggleExpand(id) {
      this.expandedReviews[id] = !this.expandedReviews[id]
    },
    openWrite() {
      this.newRating = 5
      this.reviewContent = ''
      this.showPopup = true
    },
    async submitReview() {
      // P4-2（D3）单飞早退：模板 :disabled 要等一次重渲染才生效，同帧双击靠方法首行拦第二发
      if (this.submitting) return
      if (!this.reviewContent.trim()) {
        uni.showToast({ title: '请输入评价内容', icon: 'none' })
        return
      }
      // M4-T-05：提交前本地预检硬词——命中直接 toast"评价包含违规内容"，
      // 不清空输入（reviewContent 保持原值），用户可修改后重试
      const content = this.reviewContent.trim()
      const hitWord = HARD_WORDS.find(w => content.includes(w))
      if (hitWord) {
        uni.showToast({ title: '评价包含违规内容', icon: 'none', duration: 2000 })
        return
      }
      // P4-2（D3）置位点：空内容校验与硬词预检全部通过之后、请求发出之前——校验失败不置位不耗锁，
      // 用户改内容重试零成本
      this.submitting = true
      try {
        // P4-2（D4）reviewer 随机昵称已删：后端 M6 起按登录手机号脱敏生成昵称，DTO 已废弃不读该字段，
        // 前端随机昵称是「昵称由前端生成」的误导源死代码，只传 rating/content
        await submitReview(this.dishId, {
          rating: this.newRating,
          content: this.reviewContent.trim()
        })
        // P4-2（D4）request 层业务失败必 reject（api/index.js 封装），resolve 到手即业务已成功，
        // 上一版恒真的业务码等值判断已删，直接使用 resolve 值不再判
        // P4-2（D5）成功 → 关弹窗 → toast → 重拉列表；重拉包 catch 置 loadError 走既有错误态+重试
        // （loadAll），成功 toast 不撤回——评价已成功的事实不被列表刷新失败抹掉
        this.showPopup = false
        uni.showToast({ title: '评价成功', icon: 'success' })
        try {
          const reviewRes = await getReviews(this.dishId)
          this.reviews = (reviewRes && reviewRes.data) || []
          // P4-2（D5）拿到新列表才复位 loadError：首屏加载失败置过 true 的用户直接提交成功时，
          // 错误态不得与「评价成功」同屏；重拉失败走下方 catch 置回 true（错误态自带重试）
          this.loadError = false
        } catch (e) {
          this.loadError = true
        }
      } catch (e) {
        // 提交失败不再静默：code!==200 透出后端 message，网络异常提示可重试（弹窗保留）
        uni.showToast({ title: (e && e.message) || '网络异常', icon: 'none' })
      } finally {
        this.submitting = false
      }
    },
    formatTime(ts) {
      if (!ts) return ''
      const d = new Date(ts)
      return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 页面级滚轮兜底：手动驱动 body 滚动（照搜索页同款方案）；
    // 写评价弹窗打开时不拦截（showPopup 弹层内部自滚，避免滚轮被页面吞掉）
    onPageWheel(e) {
      if (this.showPopup) return
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* ==================== 评价页 ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; padding-bottom: 120rpx; }

/* 商品简要 */
.dish-brief { display: flex; align-items: center; gap: 20rpx; padding: 24rpx; background-color: #FFFFFF; margin-bottom: 12rpx; }
.dish-emoji { width: 120rpx; height: 120rpx; border-radius: 12rpx; display: flex; align-items: center; justify-content: center; font-size: 64rpx; flex-shrink: 0; }
.dish-info { display: flex; flex-direction: column; gap: 10rpx; }
.dish-name { font-size: 30rpx; font-weight: 700; color: #333333; }
.dish-price { font-size: 28rpx; color: #FF6600; font-weight: 700; }

/* 评价区 */
.review-section { background-color: #FFFFFF; padding: 24rpx; }
.section-header { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 24rpx; }
.section-title { font-size: 28rpx; font-weight: 700; color: #333333; }
.review-count { font-size: 22rpx; color: #999999; }
.review-list { display: flex; flex-direction: column; gap: 32rpx; }
.review-item { border-bottom: 1rpx solid #F5F5F5; padding-bottom: 32rpx; }
.review-item:last-child { border-bottom: none; padding-bottom: 0; }
.review-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14rpx; }
.review-user { display: flex; align-items: center; gap: 12rpx; }
.review-avatar { width: 52rpx; height: 52rpx; background: linear-gradient(135deg, #FF6600, #FF8533); border-radius: 50%; font-size: 24rpx; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-weight: 700; }
.review-name { font-size: 26rpx; color: #333333; font-weight: 600; }
.review-stars { display: flex; gap: 2rpx; }
.star { font-size: 26rpx; color: #DDDDDD; }
.star.active { color: #FF6600; }
/* 超长评价内容折叠为 2 行（M3-T-07 line-clamp），expanded 时解除折叠 */
.review-content { font-size: 26rpx; color: #666666; line-height: 1.7; margin-bottom: 12rpx; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; word-break: break-all; }
.review-content.expanded { -webkit-line-clamp: 999; }
.review-expand { display: block; font-size: 24rpx; color: #FF6600; margin-bottom: 12rpx; }
.review-time { font-size: 22rpx; color: #CCCCCC; }

/* 空状态 */
.review-empty { display: flex; flex-direction: column; align-items: center; padding: 80rpx 0; gap: 16rpx; }
.empty-icon { font-size: 80rpx; }
.empty-text { font-size: 26rpx; color: #CCCCCC; }
/* 错误态重试按钮（M3-T-05 部分） */
.btn-retry { margin-top: 24rpx; min-width: 240rpx; font-size: 26rpx; font-weight: 600; color: #FF6600; background-color: #FFFFFF; border: 1rpx solid #FF6600; border-radius: 0; padding: 14rpx 48rpx; line-height: 1.4; }

/* 底部栏 */
.bottom-bar { position: fixed; bottom: 0; left: 0; right: 0; height: 100rpx; background-color: #FFFFFF; display: flex; align-items: center; justify-content: center; box-shadow: 0 -2rpx 16rpx rgba(0,0,0,.06); z-index: 100; }
.btn-write { flex: 1; margin: 0 24rpx; height: 80rpx; background: linear-gradient(135deg, #FF6600, #FF8533); color: #FFFFFF; font-size: 28rpx; font-weight: 700; border: none; border-radius: 0; display: flex; align-items: center; justify-content: center; }

/* 弹窗 */
.popup-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(0,0,0,.5); z-index: 200; display: flex; align-items: flex-end; justify-content: center; }
.popup-panel { width: 100%; background-color: #FFFFFF; border-radius: 24rpx 24rpx 0 0; }
.popup-header { display: flex; justify-content: space-between; align-items: center; padding: 28rpx 32rpx; border-bottom: 1rpx solid #F0F0F0; }
.popup-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.popup-close { font-size: 32rpx; color: #CCCCCC; }
.popup-body { padding: 32rpx; }
.rating-row { display: flex; align-items: center; gap: 20rpx; margin-bottom: 24rpx; }
.rating-label { font-size: 26rpx; color: #666666; font-weight: 600; }
.star-picker { display: flex; gap: 8rpx; }
.star-picker .star { font-size: 48rpx; color: #DDDDDD; }
.star-picker .star.active { color: #FF6600; }
.form-group { margin-bottom: 24rpx; position: relative; }
.review-textarea { width: 100%; height: 240rpx; background-color: #F5F5F5; border-radius: 12rpx; padding: 20rpx; font-size: 26rpx; color: #333333; box-sizing: border-box; line-height: 1.7; }
.char-count { position: absolute; bottom: 12rpx; right: 16rpx; font-size: 20rpx; color: #CCCCCC; }
.btn-submit { width: 100%; height: 88rpx; background: linear-gradient(135deg, #FF6600, #FF8533); color: #FFFFFF; font-size: 28rpx; font-weight: 700; border: none; border-radius: 0; display: flex; align-items: center; justify-content: center; }
/* 提交飞行中禁用态（P4-2 D3）：置灰提示「提交中…」，姿势照 index.vue btn-confirm-disabled（浅色底 + 降透明白字） */
.btn-submit-disabled { background: #FFB27F; color: rgba(255,255,255,.9); }
</style>
