<template>
  <view class="page-container">
    <!-- ==================== 0. 口径说明（页头恒显条，照 settlements.vue 的 caliber-card 姿势） ====================
             说的不是某一行，是全列表的口径：归属=自己菜品的全部评价（含待审）；
             「一评一回」= 再点回复即覆盖更新，保留最新一条 -->
    <view class="card caliber-card">
      <text class="card-title">评价管理</text>
      <text class="caliber-line">列表为本人菜品的全部评价（含待审核），回复后顾客在商品页可见</text>
      <text class="caliber-line">每条评价可回复一次：再次回复将覆盖上一条，仅保留最新回复</text>
    </view>

    <!-- ==================== 1. 加载中（首屏与回页重拉走整页 loading，避免旧列表闪现） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载评价…</text>
    </view>

    <!-- ==================== 2. 403 引导态（确定性失败，重试必然同一错误，照 settlements.vue 分态） ==================== -->
    <view v-else-if="viewState === 'forbidden'" class="card guide-card">
      <text class="guide-title">暂无权限查看评价</text>
      <text class="guide-text">当前账号不是可查看的商家主体，或商家已停业，请回工作台确认账号状态</text>
      <text v-if="forbiddenMessage" class="guide-tip">{{ forbiddenMessage }}</text>
      <text class="guide-link" @click="goWorkspace">回工作台</text>
    </view>

    <!-- ==================== 3. 错误态（两层式照母版：人话主文案 + 后端原文小字 + 429 冷却） ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-lead">{{ errorLeadText }}</text>
        <text v-if="errorText && errorCode !== 404" class="guide-tip">{{ errorText }}</text>
        <text v-if="errorRetryable && !retryLock" class="error-retry" @click="loadReviews(true)">点击重试</text>
        <text v-else-if="errorRetryable" class="error-retry error-retry-cooling">{{ retryPillText }}</text>
        <text v-if="!errorRetryable" class="guide-link" @click="goWorkspace">回工作台</text>
      </view>
    </view>

    <!-- ==================== 4. 空态（成功且确认为空才许说「还没有评价」） ==================== -->
    <view v-else-if="viewState === 'empty'" class="card empty-card">
      <view class="empty-row">
        <text class="empty-text">还没有评价；顾客评价自己菜品的商品后会展示在这里</text>
      </view>
    </view>

    <!-- ==================== 5. 就绪：评价列表（触底「加载更多」追加，照 plan-orders 口径） ==================== -->
    <template v-else>
      <view class="card list-card">
        <view v-for="(r, ri) in reviews" :key="r.id" class="review-item">
          <!-- 行 1：菜品名 + 待审角标（auditStatus='pending' 转述，其余值不猜） -->
          <view class="review-head">
            <text class="review-dish">{{ r.dishName || '未命名商品' }}</text>
            <text v-if="r.auditStatus === 'pending'" class="review-pending">待审核</text>
          </view>
          <!-- 行 2：星级（clampRating 截断 [1,5] 照 detail.vue T-M3-07 口径）+ 时间（createTime 原样转述） -->
          <view class="review-meta">
            <view class="review-stars">
              <text v-for="s in 5" :key="s" :class="s <= clampRating(r.rating) ? 'star active' : 'star'">★</text>
            </view>
            <text class="review-time">{{ r.createTime || '--' }}</text>
          </view>
          <!-- 行 3：评价内容（原样转述；后端已转义、WXML/模板插值不再二次处理） -->
          <text class="review-content">{{ r.content }}</text>
          <!-- 行 4：评价图缩略三列网格（P10-6 网格姿势照 detail.vue 评价预览区；点击大图预览） -->
          <view class="review-imgs" v-if="reviewImages(r).length">
            <view class="review-img-cell" v-for="(p, pi) in reviewImages(r)" :key="'rimg-' + r.id + '-' + pi">
              <image
                v-if="!imgFail[r.id + '-' + pi]"
                class="review-img"
                :src="resolveReviewImg(p)"
                mode="aspectFill"
                @error="onImgFail(r.id + '-' + pi)"
                @click="previewReviewImages(r, pi)"
              />
            </view>
          </view>
          <!-- 行 5：我的回复气泡（未回复不渲染；replyer/replyTime 只转述后端落库值） -->
          <view class="reply-bubble" v-if="r.replyContent">
            <view class="reply-head">
              <text class="reply-name">{{ r.replyer || '商家回复' }}</text>
              <text class="reply-time">{{ r.replyTime || '' }}</text>
            </view>
            <text class="reply-content">{{ r.replyContent }}</text>
          </view>
          <!-- 行 6：回复操作（飞行中置灰禁点，防同条并发回复） -->
          <view class="reply-actions">
            <text
              class="reply-btn"
              :class="{ 'reply-btn-busy': replyingId === r.id }"
              @click="openReply(r)"
            >{{ r.replyContent ? '修改回复' : '回复' }}</text>
          </view>
        </view>
      </view>

      <!-- 加载更多（照 plan-orders：hasMore 为 false 换成「共 N 条」计数行，N 只取后端 total） -->
      <view class="more-zone">
        <button
          v-if="hasMore"
          class="btn-more"
          :class="{ 'btn-more-cooling': retryLock > 0 }"
          :disabled="loadingMore || retryLock > 0"
          @click="loadMore"
        >{{ loadingMore ? '正在加载…' : '加载更多评价' }}</button>
        <text v-else class="shown-total">共 {{ total }} 条评价</text>
      </view>
    </template>

    <!-- ==================== 6. 回复输入弹层（自绘，照 C 端 review.vue 写评价弹层姿势） ====================
             遮罩 @click 收起防误触空开；textarea 阻止冒泡（.stop）免得点内容误关；
             字数上限 500 与列 VARCHAR(500)/服务端闸门同源，超长后端 400 文案透出 -->
    <view class="reply-mask" v-if="replyOpen" @click="closeReply">
      <view class="reply-sheet" @click.stop>
        <text class="reply-sheet-title">{{ replyTarget && replyTarget.replyContent ? '修改回复' : '回复评价' }}</text>
        <view class="reply-input-zone">
          <textarea
            class="reply-textarea"
            v-model="replyContent"
            :placeholder="replyPlaceholder"
            maxlength="500"
            :adjust-position="true"
            cursor-spacing="24"
          />
          <text class="char-count">{{ replyContent.length }}/500</text>
        </view>
        <view class="reply-sheet-actions">
          <button class="btn-ghost" :disabled="replySubmitting" @click="closeReply">取消</button>
          <button class="btn-main btn-submit" :class="{ 'btn-disabled': replySubmitting || !replyContent.trim() }" :disabled="replySubmitting || !replyContent.trim()" @click="submitReply">
            <text>{{ replySubmitting ? '提交中…' : '提交回复' }}</text>
          </button>
        </view>
      </view>
    </view>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端评价管理页（P11-1a D5）：GET /api/merchant/reviews 分页列表（{list,page,size,total,hasMore}）
// + 行内回复 POST /api/merchant/reviews/{id}/reply（一评一回，软词 400 toast 后端 message）。
// 页面纪律照母版 settlements.vue / plan-orders.vue：
// ① 四态单值互斥 loading | ready | empty | forbidden | error，不用多布尔叠加；
// ② 分页照 plan-orders 的「触底加载更多」口径（评价无筛选维度，不照 settlements 换页制）：
//    page/size/total/hasMore 全以后端回显为准，追加走 concat 生成新数组；
// ③ 归属全在服务端（列表 SQL 谓词 + 回复 service 谓词），本页不发 merchantId；
//    401 由请求层清态回登录页，本页不处理跳转。
// 评价图网格照 C 端 detail.vue 评价预览区（P10-6b D5）同款：images 归一→resolve 拼后端→
// imgFail 记录表 404 兜底隐藏该格→previewImage 大图预览。
import { getMerchantReviews, replyMerchantReview, retrySecondsOf, coolingLeadText, cooldownPillText, resolveFileUrl } from '@/api/index.js'
import '@/styles/common.css'

// 本页路由（母版 H5 滚轮守卫用）
const PAGE_ROUTE = 'pages/reviews/reviews'

// 429 冷却秒数的回落档：列表 GET 限流 30 次/分、回复 POST 10 次/分（窗口均 60 秒），
// 回落取整个窗口是「后端不给数时唯一能确定的安全上限」——口径同 settlements.vue 同名常量
const RETRY_COOLING_SECONDS = 60

export default {
  data() {
    return {
      reviews: [],
      // 分页态（照 plan-orders）：page/size 是下发参数、total/hasMore 只从回包转述；
      // hasMore 只认真假值，后端没给时按「已加载条数 < total」兜底（缺字段≠没有更多）
      page: 1,
      size: 20,
      total: 0,
      hasMore: false,
      loadingMore: false,
      // 五态互斥：loading | ready | empty | forbidden | error
      viewState: 'loading',
      errorText: '',
      errorCode: 0,
      forbiddenMessage: '',
      // 回复弹层态：replyTarget=行快照（null=关），replyOpen 控制遮罩显隐
      replyOpen: false,
      replyTarget: null,
      replyContent: '',
      replySubmitting: false,
      // 正在提交回复的行 id（按钮置灰防同条并发）；回复端点 10 次/分限流过严时也由它兜住连点
      replyingId: null,
      // 评价图 404 记录表（key = r.id + '-' + 下标，照 detail.vue imgFail 家族姿势）
      imgFail: {},
      // 429 冷却剩余秒数与定时器（口径照 settlements.vue：限流态下的重试必须有秒级闸门）；
      // 闸门真值存墙上时钟锚点（FE-5），retryLock 是每秒由锚点现算的派生读数
      retryLock: 0,
      retryLockTimer: null,
      retryLockUntil: 0
    }
  },
  computed: {
    // 错误卡主文案四路分流（照 settlements.vue errorLeadText，归因「评价」）
    errorLeadText() {
      if (this.errorCode === 429) return coolingLeadText('评价')
      if (this.errorCode === 404) return this.errorText
      if (this.errorCode === 500) return '评价加载失败，服务端处理出错，稍后重试'
      if (this.errorCode <= 0) return '评价加载失败，请检查网络后重试'
      return '评价加载失败，请稍后重试'
    },
    errorRetryable() {
      return this.errorCode !== 403 && this.errorCode !== 404
    },
    retryPillText() {
      return cooldownPillText(this.retryLock)
    },
    // 回复框占位：有上一版回复时提示覆盖语义（一评一回，避免商家不知道「再提一次会覆盖」）
    replyPlaceholder() {
      return (this.replyTarget && this.replyTarget.replyContent)
        ? '已有一条回复，提交将覆盖上一条…'
        : '写下对这条评价的回复…'
    }
  },
  onShow() {
    // FE-5：回前台先按锚点重算派生读数，再让下面那道 429 闸门判它（照 settlements.vue onShow）
    this.retryLock = this.retryLockRemaining()
    // 429 冷却闸门（判据照 settlements.vue onShow）：限流态下回页静默重拉是在给桶续命
    if (this.retryLock > 0) return
    // 每次进入整页重拉第一页（照 settlements onShow 口径）：回复/审核态流转后回页即见最新
    this.loadReviews(true)
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底（照 plan-orders.vue 的 onReady/onUnload 配对；弹层打开时不接管——见 onPageWheel 首行）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    this._unloaded = true
    // #ifdef H5
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
    // 429 冷却定时器清理（照 settlements.vue onUnload 收尾）
    this.stopRetryLock()
  },
  methods: {
    // ==================== 数据加载 ====================
    async loadReviews(resetView) {
      if (this._unloaded) return
      if (this._loadingList) {
        this._reloadQueued = true
        return
      }
      this._loadingList = true
      if (resetView || this.viewState === 'loading') this.viewState = 'loading'
      try {
        const res = await getMerchantReviews({ page: 1, size: this.size })
        // 分页五值全以后端回显为准（本端点越界 400 不夹取，回显即请求值，但纪律照 plan-orders 不走捷径）
        const data = (res && res.data) || {}
        const rows = Array.isArray(data.list) ? data.list : []
        const echoPage = Number(data.page)
        const echoSize = Number(data.size)
        this.page = echoPage >= 1 ? echoPage : 1
        this.size = echoSize >= 1 ? echoSize : this.size
        const echoTotal = Number(data.total)
        this.total = Number.isFinite(echoTotal) && echoTotal >= 0 ? echoTotal : rows.length
        this.reviews = rows
        this.hasMore = data.hasMore === true || data.hasMore === false
          ? data.hasMore
          : this.reviews.length < this.total
        this.errorText = ''
        this.errorCode = 0
        this.forbiddenMessage = ''
        this.stopRetryLock()
        this.viewState = this.reviews.length ? 'ready' : 'empty'
        // 整页重拉成功后回顶（重拉前列表可能已滚到深处，不回顶会停在空白上照 plan-orders 口径）
        if (resetView && this.viewState === 'ready') {
          // #ifdef H5
          document.body.scrollTop = 0
          // #endif
        }
      } catch (e) {
        // 失败清空列表与总数（照 settlements.vue：错误/引导卡整屏替换 ready 态，不留死数据）
        this.reviews = []
        this.total = 0
        this.hasMore = false
        const code = Number((e && e.code) || 0)
        if (code === 401) {
          // 跳转与清态都由请求层做完，这里只停在加载态（照母版纪律不闪错误卡）
          this.viewState = 'loading'
          return
        }
        if (code === 403) {
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
          this.errorText = ''
          this.errorCode = 403
          this.stopRetryLock()
          this.viewState = 'forbidden'
          return
        }
        this.errorText = (e && e.message) || ''
        this.errorCode = code
        if (code === 429) this.startRetryLock(e)
        else this.stopRetryLock()
        this.viewState = 'error'
      } finally {
        this._loadingList = false
        if (this._reloadQueued) {
          if (this._unloaded) return
          this._reloadQueued = false
          this.loadReviews(true)
        }
      }
    },
    // ==================== 加载更多（触底追加，照 plan-orders loadMore 口径） ====================
    loadMore() {
      // 闸门全在这一条里：冷却/在飞/没有更多三判据（照 plan-orders；H5 触底补丁无条件调用是安全的）
      if (this.retryLock > 0 || this._loadingList || this.loadingMore || !this.hasMore) return
      this.loadingMore = true
      const targetPage = this.page + 1
      getMerchantReviews({ page: targetPage, size: this.size }).then((res) => {
        const data = (res && res.data) || {}
        const rows = Array.isArray(data.list) ? data.list : []
        // 追加前按 id 去重（照 plan-orders appendRows）：回包晚于重拉时旧页行不得拼进新列表
        const seen = {}
        this.reviews.forEach((r) => { seen[r && r.id] = true })
        const fresh = rows.filter((r) => r && !seen[r.id])
        // concat 生成新数组（照 plan-orders appendRows 尾注：Vue2 数组下标直改不触发渲染）
        this.reviews = this.reviews.concat(fresh)
        // 页码/总数仍照回显推进（size 不动：同页同档位）
        const echoPage = Number(data.page)
        this.page = echoPage >= 1 ? echoPage : targetPage
        const echoTotal = Number(data.total)
        this.total = Number.isFinite(echoTotal) && echoTotal >= 0 ? echoTotal : this.total
        this.hasMore = data.hasMore === true || data.hasMore === false
          ? data.hasMore
          : this.reviews.length < this.total
        this.loadingMore = false
      }).catch(() => {
        // 追加失败不打断已看到的列表：toast 一声，重按「加载更多」即重试（照追加式分页轻失败口径）
        this.loadingMore = false
        uni.showToast({ title: '加载失败，请稍后重试', icon: 'none', duration: 1500 })
      })
    },
    // ==================== 回复（行内弹层） ====================
    openReply(r) {
      if (this.replyingId === r.id) return
      this.replyTarget = r
      // 回填上一版回复便于修改；null/undefined 归一空串（textarea v-model 不吃 null）
      this.replyContent = (r && typeof r.replyContent === 'string') ? r.replyContent : ''
      this.replyOpen = true
    },
    closeReply() {
      if (this.replySubmitting) return
      this.replyOpen = false
      this.replyTarget = null
      this.replyContent = ''
    },
    async submitReply() {
      const target = this.replyTarget
      const content = this.replyContent.trim()
      if (!target || !content || this.replySubmitting) return
      this.replySubmitting = true
      this.replyingId = target.id
      try {
        const res = await replyMerchantReview(target.id, content)
        // 本地同步行内三值（服务端一评一回 UPSERT；replyer/replyTime 由后端按库值下发——
        // message 原样转述，出参 data 为 null 无行可回，本行用提交值与回包回显值择一更新）
        this.reviews = this.reviews.map((row) => {
          if (!row || row.id !== target.id) return row
          return { ...row, replyContent: content, replyer: (row.replyer || '商家'), replyTime: this.nowText() }
        })
        this.replyOpen = false
        this.replyTarget = null
        this.replyContent = ''
        uni.showToast({ title: (res && res.message) || '回复成功', icon: 'none', duration: 1500 })
      } catch (e) {
        // 软词/硬词 400、超长 400、非自家 404：一律 toast 后端 message 原文（契约点名口径，不本地重写文案）；
        // 401 已由请求层清态回登录页，这里不再补 toast（避免双响）
        const code = Number((e && e.code) || 0)
        if (code !== 401) {
          uni.showToast({ title: (e && e.message) || '回复失败，请稍后重试', icon: 'none', duration: 2500 })
        }
      } finally {
        this.replySubmitting = false
        this.replyingId = null
      }
    },
    // 提交成功的行内时间回显：本地钟只做「刚回完那一刻的展示」，
    // 回页 onShow 整页重拉后即被服务端 reply_time 真值覆盖（页内不推算业务时长）
    nowText() {
      const d = new Date()
      const p = (n) => String(n).padStart(2, '0')
      return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },
    // ==================== 评价图展示（照 detail.vue P10-6b D5 同款） ====================
    reviewImages(r) {
      const list = (r && Array.isArray(r.images)) ? r.images : []
      return list.filter(p => typeof p === 'string' && p)
    },
    resolveReviewImg(p) {
      // 评价图由后端 /uploads/** 静态映射直出，相对路径必须拼 BASE_URL（照 detail.vue 同名函数）；
      // 拼接责任收敛在 api 层 resolveFileUrl（§3.1 唯一出口，禁止页内自拼 BASE_URL）
      return resolveFileUrl(typeof p === 'string' ? p : '')
    },
    onImgFail(key) {
      this.imgFail = { ...this.imgFail, [key]: true }
    },
    previewReviewImages(r, pi) {
      const urls = this.reviewImages(r).map(p => this.resolveReviewImg(p))
      uni.previewImage({ urls, current: urls[pi] })
    },
    // 星级截断 [1,5]（照 detail.vue clampRating T-M3-07 口径；脏值兜 1 不出 0 星）
    clampRating(v) {
      const n = Number(v)
      if (!Number.isFinite(n)) return 1
      return Math.min(5, Math.max(1, n))
    },
    // ==================== 跳转 ====================
    goWorkspace() {
      uni.reLaunch({ url: '/pages/workspace/workspace' })
    },
    // ==================== 429 冷却（照 settlements.vue 同款秒级禁用范式） ====================
    startRetryLock(err) {
      this.stopRetryLock()
      // 闸门真值存墙上时钟锚点（FE-5），秒数只由 api 层 retrySecondsOf 转述后端真值，
      // 取不到回落本页窗口常量；页内零推算时长
      this.retryLockUntil = Date.now() + retrySecondsOf(err, RETRY_COOLING_SECONDS) * 1000
      this.retryLock = this.retryLockRemaining()
      this.retryLockTimer = setInterval(() => {
        this.retryLock = this.retryLockRemaining()
        if (this.retryLock <= 0) this.stopRetryLock()
      }, 1000)
    },
    retryLockRemaining() {
      if (!(this.retryLockUntil > 0)) return 0
      return Math.max(0, Math.ceil((this.retryLockUntil - Date.now()) / 1000))
    },
    stopRetryLock() {
      if (this.retryLockTimer) { clearInterval(this.retryLockTimer); this.retryLockTimer = null }
      this.retryLockUntil = 0
      this.retryLock = 0
    },
    // ==================== H5 页面滚轮兜底（照 plan-orders.vue 两道守卫） ====================
    // #ifdef H5
    onPageWheel(e) {
      // ① 栈顶守卫：navigateTo/reLaunch 不保证触发本页 onUnload，栈里多挂一份会一次滚两倍
      let pages = []
      try {
        pages = getCurrentPages() || []
      } catch (err) {
        return
      }
      if (!pages.length || (pages[pages.length - 1] && pages[pages.length - 1].route) !== PAGE_ROUTE) return
      // ② 弹层放行：回复弹层/textarea/uni 弹窗打开时滚轮归弹层，页面不得抢滚
      const t = e.target
      const el = t && t.nodeType === 1 ? t : (t && t.parentElement)
      if (el && typeof el.closest === 'function'
        && el.closest('.uni-actionsheet, .uni-modal, .uni-toast, .uni-sample-toast, .uni-picker-container, textarea, input, uni-textarea, uni-input')) {
        return
      }
      e.preventDefault()
      document.body.scrollTop += e.deltaY
      // 触底翻页补丁（H5 原生滚动被吞后 uni.onReachBottom 不可达）；闸门全在 loadMore 里
      if (document.body.scrollTop + window.innerHeight >= document.body.scrollHeight - 50) this.loadMore()
    }
    // #endif
  }
}
</script>

<style scoped>
/* P9-1/P9-2 双主题：色值全走 var(--c-*)（两组变量定义在 App.vue 的 :root / :root.dark）；
   .card/.guide-card/.error-row/.empty-row/.btn-main 等公共类在 styles/common.css 同走 var()。
   字面值只留星与回复气泡底等主题语义色——星星用 --c-warn（评分语义，两主题都有对应档），
   回复气泡底用 --c-primary-soft（品牌语义软底）。 */

/* ==================== 0. 口径说明卡 ==================== */
.caliber-card { margin-top: 24rpx; }
.caliber-line { display: block; margin-top: 12rpx; font-size: 22rpx; color: var(--c-text-3); line-height: 1.6; }

/* ==================== 1. 加载态 / 空态 ==================== */
.loading-card { margin-top: 24rpx; display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: var(--c-text-3); line-height: 1.6; }
.empty-card { margin-top: 24rpx; }
.guide-card { margin-top: 24rpx; }
/* 后端 403 原文透出行：小字弱化红（照 workspace.vue guide-tip） */
.guide-tip { font-size: 22rpx; color: var(--c-danger); opacity: .75; line-height: 1.5; text-align: center; }
/* 错误卡主文案（照 settlements.vue error-lead 同尺寸） */
.error-lead { font-size: 26rpx; color: var(--c-text-2); line-height: 1.6; text-align: center; }
/* 冷却中的重试：同一枚胶囊置灰（真闸在 loadReviews/loadMore 首行早退） */
.error-retry-cooling { color: var(--c-text-4); border-color: var(--c-border); cursor: not-allowed; }

/* ==================== 5. 评价列表 ==================== */
.list-card { margin-top: 24rpx; }
.review-item { padding: 28rpx 24rpx; border-bottom: 1rpx solid var(--c-border); }
.review-item:last-child { border-bottom: none; }
.review-head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.review-dish {
  flex: 1; min-width: 0;
  font-size: 28rpx; font-weight: 600; color: var(--c-text); line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 待审角标：软黄底黄字（auditStatus 原值转述，只认 pending 一枚已知值） */
.review-pending {
  flex-shrink: 0;
  font-size: 20rpx; font-weight: 600; line-height: 1.4;
  color: var(--c-warn); background-color: var(--c-warn-soft);
  border-radius: 8rpx; padding: 4rpx 12rpx;
}
.review-meta { display: flex; align-items: center; justify-content: space-between; margin-top: 12rpx; }
.review-stars { display: flex; gap: 4rpx; }
.star { font-size: 24rpx; color: var(--c-disabled); }
.star.active { color: var(--c-warn); }
.review-time { font-size: 22rpx; color: var(--c-text-3); }
.review-content { display: block; margin-top: 12rpx; font-size: 26rpx; color: var(--c-text-2); line-height: 1.7; word-break: break-all; }

/* 评价图缩略三列网格（尺寸照 detail.vue preview-imgs 同款）：正方格 aspectFill */
.review-imgs { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 16rpx; }
.review-img-cell {
  width: 200rpx; height: 200rpx;
  border-radius: 12rpx; overflow: hidden;
  background-color: var(--c-fill);
}
.review-img { width: 100%; height: 100%; }

/* 我的回复气泡：品牌软底+左侧品牌竖线，与评价正文拉开层级 */
.reply-bubble {
  margin-top: 16rpx; padding: 20rpx;
  background-color: var(--c-primary-soft);
  border-left: 6rpx solid var(--c-primary);
  border-radius: 12rpx;
}
.reply-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12rpx; }
.reply-name { font-size: 24rpx; font-weight: 600; color: var(--c-primary); }
.reply-time { flex-shrink: 0; font-size: 20rpx; color: var(--c-text-3); }
.reply-content { display: block; margin-top: 8rpx; font-size: 24rpx; color: var(--c-text-2); line-height: 1.7; word-break: break-all; }

/* 行内回复操作：文字按钮靠右（不占大按钮面，列表节奏照 settlements 行内 hint） */
.reply-actions { display: flex; justify-content: flex-end; margin-top: 16rpx; }
.reply-btn {
  font-size: 26rpx; font-weight: 600;
  color: var(--c-primary);
  padding: 8rpx 24rpx;
  border: 1rpx solid var(--c-primary-hair);
  border-radius: 999rpx;
}
.reply-btn-busy { color: var(--c-text-4); border-color: var(--c-border); }

/* ==================== 加载更多（照 plan-orders btn-more 胶囊） ==================== */
.more-zone { display: flex; justify-content: center; padding: 24rpx 0 40rpx; }
.btn-more {
  min-width: 320rpx; height: 72rpx; line-height: 72rpx;
  font-size: 26rpx; font-weight: 600;
  color: var(--c-primary); background-color: var(--c-card);
  border: 1rpx solid var(--c-primary-hair); border-radius: 999rpx;
  padding: 0 40rpx;
}
.btn-more-cooling { color: var(--c-text-4); border-color: var(--c-border); cursor: not-allowed; }
.shown-total { font-size: 24rpx; color: var(--c-text-3); }

/* ==================== 6. 回复弹层（自绘遮罩+底部工作表，照 C 端 review.vue 写评价弹层姿势） ==================== */
.reply-mask {
  position: fixed; left: 0; top: 0; right: 0; bottom: 0;
  background-color: var(--c-shadow);
  z-index: 999;
  display: flex; align-items: flex-end;
}
.reply-sheet {
  width: 100%;
  background-color: var(--c-card);
  border-radius: 24rpx 24rpx 0 0;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.reply-sheet-title { display: block; font-size: 30rpx; font-weight: 700; color: var(--c-text); text-align: center; }
.reply-input-zone { position: relative; margin-top: 24rpx; }
.reply-textarea {
  width: 100%; height: 240rpx;
  background-color: var(--c-fill);
  border-radius: 12rpx; padding: 20rpx;
  font-size: 26rpx; color: var(--c-text);
  box-sizing: border-box; line-height: 1.7;
}
.char-count { position: absolute; bottom: 12rpx; right: 16rpx; font-size: 20rpx; color: var(--c-text-3); }
.reply-sheet-actions { display: flex; gap: 20rpx; margin-top: 24rpx; }
.btn-ghost {
  flex: 1; height: 80rpx; line-height: 80rpx;
  font-size: 28rpx; font-weight: 600;
  color: var(--c-text-2); background-color: var(--c-fill);
  border-radius: 999rpx; padding: 0;
}
.btn-submit { flex: 1.6; height: 80rpx; }
</style>
