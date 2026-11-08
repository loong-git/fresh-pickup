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
          <!-- P10-6b（D5）评价图选择区：添加图片（最多3张）入口 + 已选缩略预览可删（点缩略图可预览）；
               ≤3 上限由入口 v-if + chooseImage 动态余量 + 服务端图数<3 闸门三重把关 -->
          <view class="img-pick-group">
            <view class="img-thumbs">
              <view class="img-thumb" v-for="(img, i) in pickedImages" :key="img.local">
                <image class="img-thumb-img" :src="img.local" mode="aspectFill" @click="previewPicked(i)" />
                <text class="img-thumb-del" @click.stop="removePicked(i)">✕</text>
              </view>
              <view class="img-add" v-if="pickedImages.length < 3" @click="chooseImage">
                <text class="img-add-plus">＋</text>
                <text class="img-add-text">添加图片（最多3张）</text>
              </view>
            </view>
          </view>
          <!-- P4-2（D3）提交单飞：submitting 中灰态禁用 + 文案切换（姿势照 index.vue btn-confirm）；同帧双击由方法首行早退拦 -->
          <button class="btn-submit" :class="{ 'btn-submit-disabled': submitting }" :disabled="submitting" @click="submitReview">{{ submitting ? '提交中…' : '提交评价' }}</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getDishDetail, getReviews, submitReview, uploadReviewImage } from '@/api/index.js'

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
      // P10-6b（D5）弹窗已选评价图：{ local: chooseImage 临时路径, remote: 上传成功后回填的后端
      // 相对路径（/uploads/reviews/…，未传为 null）}。remote 有两用——重试时跳过已传成功的图
      // （防服务端图数<3 闸门把重复上传拒掉）、上传态随数据走不另立第二套状态
      pickedImages: [],
      // P4-2（D3）提交单飞标志：校验全过后置 true，finally 退回（成功/失败都退），失败后可再点重试
      submitting: false
    }
  },
  async onLoad(options) {
    this.dishId = parseInt(options.dishId)
    // F-124 §1.3：客户端幂等键（≤64 字符，格式 r-{dishId}-{毫秒时间戳}-{4位随机}）——
    // 页面加载生成一次；提交失败弹窗保留原键，同图重试复用同一键，服务端按 clientRequestId
    // 幂等返回首次评价（断网/超时重试不重复落库）；提交成功后作废，下一条评价换新键。
    // 存实例级 this._clientRequestId（非 data）：不参与模板渲染，不引入第二套提交状态
    this._clientRequestId = this.genClientRequestId()
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
    // F-124 §1.3：客户端幂等键生成（家族命名照 index.vue genClientRequestId；格式 r-{dishId}-{毫秒}-{4位随机}，
    // 长约 22 字符 ≤64 与后端列/DTO 对齐）。一处定义，onLoad 与 openWrite 两处调用，防格式漂移
    genClientRequestId() {
      return 'r-' + this.dishId + '-' + Date.now() + '-' +
        String(Math.floor(Math.random() * 10000)).padStart(4, '0')
    },
    openWrite() {
      this.newRating = 5
      this.reviewContent = ''
      // P10-6b（D5）新弹窗会话 = 新提交意图：已选图一并清空（失败重试不经 openWrite，预览保留）
      this.pickedImages = []
      // F-124 §1.3 配套：每次新开弹窗即备新键（新弹窗会话 = 新提交意图 = 新键）。提交失败弹窗保留、
      // 不经 openWrite 直接重试 → 仍复用同键（同意图重试幂等才有效）；成功作废后的下一条评价
      // 也经此处拿到新键，不会带着 null 提交。无条件换新另防一类边缘：失败实为响应丢失（服务端
      // 已落库）时，旧键若被「关弹窗重开的新内容」复用会被误判幂等吞掉新输入
      this._clientRequestId = this.genClientRequestId()
      this.showPopup = true
    },
    // ==================== 评价图选择（P10-6b D5） ====================
    // chooseImage 选图：count 按剩余余量传（≤3 总上限由本方法早退 + 入口 v-if + 服务端闸门三重把关）；
    // 取消/失败静默（H5 文件选择器取消走 fail，不提示）
    chooseImage() {
      const remain = 3 - this.pickedImages.length
      if (remain <= 0) {
        uni.showToast({ title: '最多添加3张图片', icon: 'none' })
        return
      }
      uni.chooseImage({
        count: remain,
        success: (res) => {
          const paths = (res && res.tempFilePaths) || []
          paths.slice(0, remain).forEach(p => {
            this.pickedImages.push({ local: p, remote: null })
          })
        },
        fail: () => {}
      })
    },
    // 点已选缩略图本地预览（uni.previewImage，current 按 URL 口径传）
    previewPicked(i) {
      const urls = this.pickedImages.map(img => img.local)
      uni.previewImage({ urls, current: urls[i] })
    },
    // 删除已选图（已传成功的图删后服务端行保留——后端无删图端点，图行仅驳回时随评价同删）；
    // @click.stop 防误触缩略图预览
    removePicked(i) {
      this.pickedImages.splice(i, 1)
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
        // F-124 §1.3：body 带客户端幂等键——失败保留原值（同图重试复用同键，服务端幂等返回首次评价）；
        // 成功在下方置 null 作废（下一条评价换新键），DTO 可空不强制、向后兼容
        const reviewRes = await submitReview(this.dishId, {
          rating: this.newRating,
          content: this.reviewContent.trim(),
          clientRequestId: this._clientRequestId
        })
        // P4-2（D4）request 层业务失败必 reject（api/index.js 封装），resolve 到手即业务已成功，
        // 上一版恒真的业务码等值判断已删，直接使用 resolve 值不再判
        // P10-6b（D5/D7）带图提交：submitReview 成功返回 R<Review>（后端 ReviewController#addReview
        // R.ok(saved) 透出实体，data.id 即评价主键，已实读核实）→ 逐张上传弹窗已选图。
        // 拍板口径：任一张失败即 toast 后端 message 并 return（保留弹窗与预览可重试）——
        // 幂等键未作废（成功支才置 null），重试按 clientRequestId 幂等返回同一评价不产生重复行；
        // 已传成功的图按 remote 跳过只补传失败张（防服务端图数<3 闸门拒重复上传）
        const reviewId = reviewRes && reviewRes.data && reviewRes.data.id
        if (this.pickedImages.length && reviewId == null) {
          // 理论不达分支（契约出参含 id 已核实）：评价已落库但拿不到 id，图无处挂——
          // 诚实 toast 且不关弹窗不静默丢图，用户可 ✕ 退出
          uni.showToast({ title: '评价已提交，图片上传失败', icon: 'none' })
          return
        }
        for (let i = 0; i < this.pickedImages.length; i++) {
          const img = this.pickedImages[i]
          if (img.remote) continue
          try {
            const upRes = await uploadReviewImage(this.dishId, reviewId, img.local)
            img.remote = (upRes && upRes.data && upRes.data.path) || null
          } catch (upErr) {
            // 单张失败即停：toast 后端人话（400 校验/429 限流等透出），弹窗+预览保留可重试
            uni.showToast({ title: (upErr && upErr.message) || '图片上传失败，请重试', icon: 'none' })
            return
          }
        }
        // P4-2（D5）成功 → 关弹窗 → toast → 重拉列表；重拉包 catch 置 loadError 走既有错误态+重试
        // （loadAll），成功 toast 不撤回——评价已成功的事实不被列表刷新失败抹掉
        this.showPopup = false
        // F-124 §1.3：提交成功即作废幂等键（置 null）——本条评价已落库，该键使命终结；
        // 下一条评价换新键。置 null 只在成功支：失败保留原值，同图重试必须复用同键幂等才有效
        this._clientRequestId = null
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

.page-container { min-height: 100vh; background-color: var(--c-fill); padding-bottom: 120rpx; }

/* 商品简要 */
.dish-brief { display: flex; align-items: center; gap: 20rpx; padding: 24rpx; background-color: #FFFFFF; margin-bottom: 12rpx; }
.dish-emoji { width: 120rpx; height: 120rpx; border-radius: 12rpx; display: flex; align-items: center; justify-content: center; font-size: 64rpx; flex-shrink: 0; }
.dish-info { display: flex; flex-direction: column; gap: 10rpx; }
.dish-name { font-size: 30rpx; font-weight: 700; color: var(--c-text); }
.dish-price { font-size: 28rpx; color: #FF6600; font-weight: 700; }

/* 评价区 */
.review-section { background-color: #FFFFFF; padding: 24rpx; }
.section-header { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 24rpx; }
.section-title { font-size: 28rpx; font-weight: 700; color: var(--c-text); }
.review-count { font-size: 22rpx; color: var(--c-text-3); }
.review-list { display: flex; flex-direction: column; gap: 32rpx; }
.review-item { border-bottom: 1rpx solid var(--c-fill); padding-bottom: 32rpx; }
.review-item:last-child { border-bottom: none; padding-bottom: 0; }
.review-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14rpx; }
.review-user { display: flex; align-items: center; gap: 12rpx; }
.review-avatar { width: 52rpx; height: 52rpx; background: linear-gradient(135deg, #FF6600, var(--c-warn-bright)); border-radius: 50%; font-size: 24rpx; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-weight: 700; }
.review-name { font-size: 26rpx; color: var(--c-text); font-weight: 600; }
.review-stars { display: flex; gap: 2rpx; }
.star { font-size: 26rpx; color: var(--c-border); }
.star.active { color: #FF6600; }
/* 超长评价内容折叠为 2 行（M3-T-07 line-clamp），expanded 时解除折叠 */
.review-content { font-size: 26rpx; color: var(--c-text-2); line-height: 1.7; margin-bottom: 12rpx; display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; word-break: break-all; }
.review-content.expanded { -webkit-line-clamp: 999; }
.review-expand { display: block; font-size: 24rpx; color: #FF6600; margin-bottom: 12rpx; }
.review-time { font-size: 22rpx; color: var(--c-disabled); }

/* 空状态 */
.review-empty { display: flex; flex-direction: column; align-items: center; padding: 80rpx 0; gap: 16rpx; }
.empty-icon { font-size: 80rpx; }
.empty-text { font-size: 26rpx; color: var(--c-disabled); }
/* 错误态重试按钮（M3-T-05 部分） */
.btn-retry { margin-top: 24rpx; min-width: 240rpx; font-size: 26rpx; font-weight: 600; color: #FF6600; background-color: #FFFFFF; border: 1rpx solid #FF6600; border-radius: 0; padding: 14rpx 48rpx; line-height: 1.4; }

/* 底部栏 */
.bottom-bar { position: fixed; bottom: 0; left: 0; right: 0; height: 100rpx; background-color: #FFFFFF; display: flex; align-items: center; justify-content: center; box-shadow: 0 -2rpx 16rpx rgba(0,0,0,.06); z-index: 100; }
.btn-write { flex: 1; margin: 0 24rpx; height: 80rpx; background: linear-gradient(135deg, #FF6600, var(--c-warn-bright)); color: #FFFFFF; font-size: 28rpx; font-weight: 700; border: none; border-radius: 0; display: flex; align-items: center; justify-content: center; }

/* 弹窗 */
.popup-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(0,0,0,.5); z-index: 200; display: flex; align-items: flex-end; justify-content: center; }
.popup-panel { width: 100%; background-color: #FFFFFF; border-radius: 24rpx 24rpx 0 0; }
.popup-header { display: flex; justify-content: space-between; align-items: center; padding: 28rpx 32rpx; border-bottom: 1rpx solid var(--c-fill); }
.popup-title { font-size: 30rpx; font-weight: 700; color: var(--c-text); }
.popup-close { font-size: 32rpx; color: var(--c-disabled); }
.popup-body { padding: 32rpx; }
.rating-row { display: flex; align-items: center; gap: 20rpx; margin-bottom: 24rpx; }
.rating-label { font-size: 26rpx; color: var(--c-text-2); font-weight: 600; }
.star-picker { display: flex; gap: 8rpx; }
.star-picker .star { font-size: 48rpx; color: var(--c-border); }
.star-picker .star.active { color: #FF6600; }
.form-group { margin-bottom: 24rpx; position: relative; }
.review-textarea { width: 100%; height: 240rpx; background-color: var(--c-fill); border-radius: 12rpx; padding: 20rpx; font-size: 26rpx; color: var(--c-text); box-sizing: border-box; line-height: 1.7; }
.char-count { position: absolute; bottom: 12rpx; right: 16rpx; font-size: 20rpx; color: var(--c-disabled); }
.btn-submit { width: 100%; height: 88rpx; background: linear-gradient(135deg, #FF6600, var(--c-warn-bright)); color: #FFFFFF; font-size: 28rpx; font-weight: 700; border: none; border-radius: 0; display: flex; align-items: center; justify-content: center; }
/* 提交飞行中禁用态（P4-2 D3）：置灰提示「提交中…」，姿势照 index.vue btn-confirm-disabled（浅色底 + 降透明白字） */
.btn-submit-disabled { background: var(--c-warn-hair); color: rgba(255,255,255,.9); }

/* P10-6b（D5）评价图选择区（新代码一律 var(--c-*)，配合片 3 灰阶语义化） */
.img-pick-group { margin-bottom: 24rpx; }
.img-thumbs { display: flex; flex-wrap: wrap; gap: 16rpx; }
.img-thumb { position: relative; width: 160rpx; height: 160rpx; border-radius: 12rpx; overflow: hidden; border: 1rpx solid var(--c-border); background-color: var(--c-fill); }
.img-thumb-img { width: 100%; height: 100%; display: block; }
.img-thumb-del { position: absolute; top: 0; right: 0; width: 40rpx; height: 40rpx; line-height: 40rpx; text-align: center; font-size: 24rpx; color: var(--c-text-3); background-color: var(--c-fill); border-radius: 0 0 0 12rpx; }
.img-add { width: 160rpx; height: 160rpx; border: 2rpx dashed var(--c-border); border-radius: 12rpx; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8rpx; background-color: var(--c-fill); }
.img-add-plus { font-size: 48rpx; color: var(--c-text-4); line-height: 1; }
.img-add-text { font-size: 20rpx; color: var(--c-text-3); }
</style>
