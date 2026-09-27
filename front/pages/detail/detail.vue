<template>
  <view class="page-container">
    <!-- ============ 0. 深链非法/商品不存在空态页（T-M3-05）：pageError 为 true 时整页替换主内容 ============ -->
    <view class="page-error" v-if="pageError">
      <text class="pe-icon">🛒</text>
      <text class="pe-text">{{ pageErrorText }}</text>
      <button class="pe-btn" @click="backHome">回首页</button>
      <text class="pe-retry" v-if="pageErrorRetryable" @click="retryLoad">点击重试</text>
    </view>

    <!-- 主内容：加载成功才渲染（加载失败/非法 id 时上面的空态页接管） -->
    <template v-if="!pageError">
    <!-- 可滚动主体：大图 + 全部内容区（内容较多，整体随页面滚动） -->
    <!-- :scroll-top + scroll-with-animation 供"回顶部"悬浮钮平滑滚顶；@scroll 监听滚动距离控制悬浮钮显隐 -->
    <scroll-view class="main-scroll" scroll-y="true" :scroll-top="scrollTopVal" :scroll-with-animation="true" @scroll="onMainScroll">

      <!-- ============ 1. 商品大图（swiper 五帧轮播：帧1主图真实图片优先，失败/为空回退 emoji 色块 T-M3-01；帧2-5 商品海报）+ 浮层标签 ============ -->
      <view class="detail-image" :style="{ backgroundColor: dish.bgColor || '#F5F5F5' }">
        <swiper class="detail-swiper" :circular="true" @change="onGalleryChange">
          <!-- 帧 1：主图（背景由外层容器绑 dish.bgColor 透出） -->
          <swiper-item>
            <view class="slide-main">
              <image v-if="dish.image && !imgFail" class="detail-photo" :src="dish.image" mode="aspectFill" @error="imgFail = true" />
              <text v-else class="detail-emoji">{{ dish.emoji }}</text>
            </view>
          </swiper-item>
          <!-- 帧 2-5：商品海报帧（emoji 色块设计语言，背景取 bgColor 同色系渐变衍生） -->
          <swiper-item v-for="(g, gi) in galleryFrames" :key="'gs' + gi">
            <view class="slide-poster" :style="{ background: g.bg }">
              <text class="slide-poster-emoji">{{ dish.emoji }}</text>
              <text class="slide-poster-text">{{ g.text }}</text>
            </view>
          </swiper-item>
        </swiper>
        <!-- 左下：销量标签（绑定真实 soldText；无销量整段隐藏不展示捏造销量，口径与标题区 stat-sold 一致） -->
        <view class="image-sold" v-if="dish.soldText">
          <text class="image-sold-text">🔥{{ dish.soldText }}</text>
        </view>
        <!-- 左下：白底圆角服务保障浮层（绿字，字典 service_tags 前 3 项 · 分隔） -->
        <view class="image-service">
          <text class="image-service-text">✓<text v-for="(t, i) in serviceFloatTags" :key="'sf' + i">{{ i > 0 ? ' · ' : '' }}{{ t }}</text></text>
        </view>
        <!-- 右下：图片指示（随轮播切换） -->
        <view class="image-index">
          <text class="image-index-text">{{ galleryCurrent + 1 }}/5</text>
        </view>
      </view>

      <!-- ============ 2. 秒杀红色价格条（仅秒杀商品显示） ============ -->
      <view class="seckill-wrap" v-if="dish.seckill">
        <view class="seckill-bar">
          <view class="seckill-main">
            <!-- 左：秒杀价大字 + 限购标签 -->
            <view class="seckill-left">
              <text class="seckill-price">⚡秒杀¥{{ fmtPrice(dish.seckillPrice) }}</text>
              <text class="seckill-limit">限购{{ dish.limitBuy }}件</text>
            </view>
            <!-- 右：超级秒杀 + 十分位倒计时（复用 23:00 截单逻辑，utils/time.js 共用；归零态直接切置灰文案不闪跳） -->
            <view class="seckill-right">
              <text class="seckill-right-title">超级秒杀</text>
              <text class="seckill-countdown" :class="{ 'countdown-zero': countdownZero }">{{ countdownZero ? '已切明日场次' : countdownText + '.' + countdownTenth }}</text>
            </view>
          </view>
        </view>
        <!-- 红条下方浅红进度条：黄色进度（M4-T-03 绑定真实比例 sold_count/(sold_count+stock)）+ 疯抢文案 -->
        <view class="seckill-subbar">
          <view class="subbar-track">
            <view class="subbar-fill" :style="{ width: seckillProgressPercent + '%' }"></view>
          </view>
          <text class="subbar-text">正在疯抢 | {{ dish.soldText || '热卖中' }}</text>
        </view>
      </view>

      <!-- ============ 3. 标题信息区 ============ -->
      <view class="info-section">
        <!-- 非秒杀：普通价格区（现价 + 划线价 + 截单倒计时） -->
        <block v-if="!dish.seckill">
          <view class="detail-price-row">
            <text class="detail-price-symbol">¥</text>
            <text class="detail-price">{{ fmtPrice(dish.price) }}</text>
            <text class="detail-unit">/{{ dish.unit }}</text>
            <text v-if="originalPriceText" class="detail-original">¥{{ originalPriceText }}</text>
          </view>
          <view class="countdown-box">
            <text class="countdown-icon">⏱</text>
            <!-- 归零展示态直接切"已切明日场次"，数字隐藏（T-M3-04 归零不闪跳） -->
            <text class="countdown-label">{{ countdownZero ? '已切明日场次' : (isAfterCutoff ? '下一场开始' : '距截单还剩') }}</text>
            <text class="countdown-value" v-if="!countdownZero">{{ countdownText }}</text>
          </view>
        </block>

        <!-- 标题行：[秒]红标 + 商品名 -->
        <view class="title-row">
          <text v-if="dish.seckill" class="sec-badge">秒</text>
          <text class="detail-name">{{ dish.name }}</text>
        </view>
        <!-- 副标题行：绿色服务小标（字典 service_tags 前 2 项）+ tags 橙色小字（| 拼接） -->
        <view class="title-sub">
          <text class="green-tag" v-for="tag in serviceTags.slice(0, 2)" :key="tag">✓{{ tag }}</text>
          <text v-if="tagsText" class="orange-tags">{{ tagsText }}</text>
        </view>
        <!-- 好评率 / 销量 -->
        <view class="title-stats">
          <text v-if="dish.goodRate" class="stat-rate">好评率{{ dish.goodRate }}</text>
          <text v-if="dish.soldText" class="stat-sold">{{ dish.soldText }}</text>
        </view>
        <text class="stock-text">库存 {{ dish.stock }}{{ dish.unit }}</text>
      </view>

      <!-- ============ 4. 推荐理由格：tags 逐项铺开 + 最右固定储存条件格 ============ -->
      <view class="reason-card">
        <view class="reason-cell" v-for="(t, i) in (dish.tags || [])" :key="'tag' + i">
          <text class="reason-word">{{ t }}</text>
          <text class="reason-sub">推荐理由</text>
        </view>
        <view class="reason-cell">
          <text class="reason-word">常温</text>
          <text class="reason-sub">储存条件</text>
        </view>
      </view>

      <!-- ============ 5. 秒杀播报条（仅秒杀商品显示） ============ -->
      <view class="notice-bar" v-if="dish.seckill">
        <view class="notice-head">
          <text class="notice-title">超级秒杀</text>
          <text class="notice-divider">|</text>
          <text class="notice-cut">{{ dish.cutText }}，{{ buyersText }}</text>
        </view>
        <!-- 内嵌白色圆角子条：固定服务标语（不再伪装他人下单播报，无轮换定时器） -->
        <view class="notice-sub">
          <text class="notice-text">📣 {{ noticeSlogan }}</text>
        </view>
      </view>

      <!-- ============ 6. 服务保障行 ============ -->
      <view class="service-card">
        <view class="service-item">
          <view class="service-left">
            <text class="service-check">✓</text>
            <text class="service-text"><text v-for="(t, i) in serviceTags" :key="'st' + i">{{ i > 0 ? '·' : '' }}{{ t }}</text></text>
          </view>
          <text class="service-arrow">›</text>
        </view>
        <view class="service-item service-item-last">
          <view class="service-left">
            <text class="service-clock">🕐</text>
            <!-- T-M3-04 自提时间动态化：按 utils/time.js 截单口径推导（今天23点前下单→明天自提，之后→后天） -->
            <text class="service-text">{{ pickupTimeText }}</text>
          </view>
        </view>
      </view>

      <!-- ============ 7. 商品评价（入口与预览逻辑不变，标题右侧加好评率） ============ -->
      <view class="section-block review-entry" v-if="reviews.length > 0" @click="goToReview">
        <view class="entry-header">
          <view class="entry-left">
            <text class="entry-icon">📝</text>
            <text class="entry-text">商品评价</text>
          </view>
          <view class="entry-right">
            <text v-if="dish.goodRate" class="entry-rate">好评率{{ dish.goodRate }}</text>
            <text class="entry-count">{{ reviews.length }}条 ›</text>
          </view>
        </view>
        <view class="review-preview">
          <view class="preview-item" v-for="r in reviews.slice(0, 2)" :key="r.id">
            <view class="preview-user">
              <text class="preview-avatar">{{ r.reviewer.charAt(0) }}</text>
              <text class="preview-name">{{ r.reviewer }}</text>
            </view>
            <view class="preview-stars">
              <!-- rating 脏数据兜底：截断到 [1,5] 区间再点亮星星（T-M3-07） -->
              <text v-for="s in 5" :key="s" :class="s <= clampRating(r.rating) ? 'star active' : 'star'">★</text>
            </view>
            <text class="preview-content">{{ r.content }}</text>
          </view>
        </view>
      </view>

      <!-- ============ 8. 图文详情：描述 + 参数表（两列灰底）+ 色块图 ============ -->
      <view class="section-block">
        <text class="section-title">商品详情</text>
        <text class="desc-content">{{ dish.description }}</text>

        <!-- 参数表：两列灰底（数据源为 computed.paramList，与下方"查看全部"弹层共用同一份参数数据） -->
        <view class="param-grid">
          <view class="param-cell" v-for="p in paramList" :key="'param-' + p.label">
            <text class="param-label">{{ p.label }}</text>
            <text class="param-value" :class="{ 'param-link': p.link }">{{ p.value }}</text>
          </view>
        </view>

        <!-- 查看全部入口：灰色小字靠左，点击展开"商品详情"底部弹层 -->
        <view class="param-more" @click="openDetailPopup">
          <text class="param-more-text">查看全部 ›</text>
        </view>

        <view class="desc-images">
          <view class="desc-img-item" v-for="i in 15" :key="i" :style="{ backgroundColor: dish.bgColor || '#F5F5F5' }">
            <text class="desc-img-emoji">{{ dish.emoji }}</text>
            <text class="desc-img-label">{{ dish.name }} 0{{ i }}</text>
          </view>
        </view>
      </view>
      <!-- ============ 11. 价格说明（白卡：两段划线价规则 + 商品说明） ============ -->
      <view class="price-note-card">
        <text class="pn-title">价格说明</text>
        <text class="pn-text">1.划线价格：指商品展示的参考价，如厂商指导价、正品零售价、市面常见价或该商品曾经展示过的销售价等，并非原价；由于地区、时间的差异性和市场行情波动，指导价、零售价等可能会与您购物时展示的不一致，该价格仅供您参考。</text>
        <text class="pn-text">2.未划线价格：指商品的实时价格，不因表述的差异改变性质，具体的成交价格根据商品参加活动，或使用优惠券等发生变化，最终以订单结算价格为准。</text>
        <text class="pn-subtitle">商品说明</text>
        <text class="pn-text pn-text-last">该商品由平台内商家提供。</text>
      </view>

      <!-- ============ 12. 查看精选推荐（展开/收起文字按钮） ============ -->
      <view class="rec-toggle" @click="toggleRec">
        <text class="rec-toggle-text">{{ recExpanded ? '收起精选推荐' : '查看精选推荐' }}</text>
        <!-- CSS 绘制 V 形箭头：默认 45° 为 ⌄（查看态），展开态转 -135° 为 ^（收起态） -->
        <view class="rec-arrow" :class="{ 'rec-arrow-up': recExpanded }"></view>
      </view>

      <!-- ============ 13. 精选推荐（展开后显示；卡片仿首页多多卡，v-if 挂载时淡入上移） ============ -->
      <view class="rec-section" v-if="recExpanded">
        <view class="rec-header">
          <text class="rec-heart">❤️</text>
          <text class="rec-title">精选推荐</text>
        </view>
        <view class="rec-card" v-for="dish in recommendList" :key="dish.id">
          <!-- 左侧 240rpx 图：真实图片优先，加载失败/为空回退 emoji 色块（T-M3-01） -->
          <view class="rec-img" :style="{ backgroundColor: dish.bgColor || '#F5F5F5' }">
            <image v-if="dish.image && !recImgFail[dish.id]" class="rec-photo" :src="dish.image" mode="aspectFill" @error="onRecImgFail(dish.id)" />
            <text v-else class="rec-emoji">{{ dish.emoji }}</text>
          </view>
          <!-- 右侧信息 -->
          <view class="rec-body">
            <view class="rec-title-row">
              <text v-if="dish.seckill" class="rec-sec-tag">秒</text>
              <text class="rec-name">{{ dish.name }}</text>
            </view>
            <text class="rec-tags">{{ (dish.tags || []).join(' | ') }}</text>
            <view class="rec-sold-row">
              <text class="rec-rush">{{ dish.rushText }}</text>
              <text class="rec-sold">{{ dish.soldText }}</text>
            </view>
            <view class="rec-promo-row">
              <block v-if="dish.seckill">
                <text class="rec-tag-cut">{{ dish.cutText }}</text>
                <text class="rec-tag-limit">限购{{ dish.limitBuy }}件</text>
              </block>
              <text v-else class="rec-rate">好评率{{ dish.goodRate }}</text>
            </view>
            <!-- 价格行 + 右侧按钮/步进器（与首页同构：加购/加减联动全局购物车；金额统一两位小数） -->
            <view class="rec-price-row">
              <view class="rec-price-left">
                <block v-if="dish.seckill">
                  <text class="rec-price-label">秒杀</text>
                  <text class="rec-price-symbol">¥</text>
                  <text class="rec-price-big">{{ fmtPrice(dish.seckillPrice) }}</text>
                </block>
                <block v-else>
                  <text class="rec-price-symbol">¥</text>
                  <text class="rec-price-big">{{ fmtPrice(dish.price) }}</text>
                </block>
              </view>
              <view class="rec-action" @click.stop>
                <view v-if="cartMap[dish.id]" class="rec-stepper">
                  <view class="rec-step-btn rec-step-minus" @click="minusRec(dish)"><text>−</text></view>
                  <text class="rec-step-num">{{ cartMap[dish.id].quantity }}</text>
                  <view class="rec-step-btn rec-step-plus" @click="plusRec(dish)"><text>+</text></view>
                </view>
                <view v-else class="rec-add-btn" @click="addRec(dish)">
                  <text>立即抢购</text>
                </view>
              </view>
            </view>
            <!-- 秒杀卡内超级秒杀条：倒计时全场共用（复用 data.countdownText/countdownTenth，不新建定时器） -->
            <view class="rec-seckill-bar" v-if="dish.seckill">
              <text class="rec-seckill-label">超级秒杀</text>
              <text class="rec-seckill-countdown" :class="{ 'rec-countdown-zero': countdownZero }">{{ countdownZero ? '已切明日场次' : countdownText + '.' + countdownTenth }}</text>
            </view>
          </view>
        </view>
        <view v-if="recommendList.length === 0" class="rec-empty"><text>暂无推荐商品</text></view>
      </view>
      <view class="scroll-bottom-pad"></view>
    </scroll-view>

    <!-- ============ 9. 底部操作栏（保留 grabbed 分栏机制） ============ -->
    <view class="bottom-bar" :class="{ 'bar-split': grabbed }">
      <!-- 空车：整条直角大红块按钮 -->
      <button v-if="!grabbed" class="btn-grab" @click="addToCart">
        <text class="grab-main">⚡立即抢购</text>
        <text class="grab-sub">仅剩 {{ countdownText }}.{{ countdownTenth }}</text>
      </button>
      <!-- 购物车非空：三段分栏 -->
      <block v-else>
        <view class="bar-cart">
          <text class="cart-icon">🛒</text>
          <text class="cart-badge" v-if="cartCount > 0">{{ cartCount }}</text>
        </view>
        <!-- 中：去支付（现价合计）+ 已减金额，点击 toast 演示 -->
        <view class="bar-pay" @click="goPay">
          <text class="pay-main">去支付¥{{ payText }} ›</text>
          <text class="pay-sub">已减 ¥{{ savingText }}</text>
        </view>
        <!-- 右：直角大红块两行，点击加购可连续累加（到限购只提示） -->
        <button class="btn-grab btn-grab-right" @click="addToCart">
          <text class="grab-main">⚡立即抢购</text>
          <text class="grab-sub">仅剩 {{ countdownText }}.{{ countdownTenth }}</text>
        </button>
      </block>
    </view>

    <!-- ============ 11. 回顶部悬浮钮（精选推荐展开且下滚超过阈值时出现，点击平滑滚回顶部） ============ -->
    <view class="back-top-btn" v-if="recExpanded && recScrolled" @click="backToTop">
      <text class="bt-arrow">⌃</text>
      <text class="bt-text">顶部</text>
    </view>

    <!-- ============ 10. 商品详情底部弹层（"查看全部"展开的完整参数版） ============ -->
    <view class="detail-popup-mask" v-if="showDetailPopup" @click="closeDetailPopup">
      <!-- 白色圆角面板：底部滑出，高度自适应内容（最高 55vh）；stop 防止点面板误触遮罩关闭 -->
      <view class="detail-popup" @click.stop>
        <!-- 顶部：居中标题 + 右上关闭 -->
        <view class="popup-header">
          <text class="popup-title">商品详情</text>
          <text class="popup-close" @click="closeDetailPopup">✕</text>
        </view>
        <!-- 内容区：完整两列参数表（与页内参数表共用 paramList）；商家资质列可点跳资质页 -->
        <scroll-view class="popup-scroll" scroll-y="true">
          <view class="param-grid popup-param-grid">
            <view class="param-cell" v-for="p in paramList" :key="'pop-param-' + p.label" @click="p.link && goQualification()">
              <text class="param-label">{{ p.label }}</text>
              <text class="param-value" :class="{ 'param-link': p.link }">{{ p.value }}</text>
            </view>
          </view>
        </scroll-view>
      </view>
    </view>
    </template>
  </view>
</template>

<script>
import { mapState } from 'vuex'
import { getDishDetail, getReviews, decorateDish, getSeckillCurrent } from '@/api/index.js'
import { getDictGroup } from '@/api/dict.js'
import { BASE_URL } from '@/api/config.js'
import { getCutoffInfo, formatCountdown, formatPickupTimeText, monotonicNow, parseServerTimeMs, sessionEndMs } from '@/utils/time.js'

export default {
  data() {
    return {
      dish: {},
      reviews: [],
      countdownText: '',
      countdownTenth: '0',
      isAfterCutoff: false,
      // 归零展示态：刚跨过 23:00 的 2s 宽限窗内为 true，直接切"已切明日场次"置灰（T-M3-04）
      countdownZero: false,
      countdownTimer: null,
      // M4-T-03：秒杀场次服务端模式（true=倒计时基准为 /api/seckill/current 的 serverTime+end）
      seckillServerMode: false,
      // 自提时间动态文案（utils/time.js 按截单口径推导，T-M3-04）
      pickupTimeText: '',
      // 页面级错误态（T-M3-05）：id 非法/404/网络失败 → "商品不存在或已下架"空态页（网络失败可重试）
      pageError: false,
      pageErrorText: '',
      pageErrorRetryable: false,
      // 大图/推荐卡图片加载失败标记（T-M3-01）：@error 回退 emoji 色块
      imgFail: false,
      // 头图轮播当前帧（0-4，指示器显示 galleryCurrent+1/5）
      galleryCurrent: 0,
      recImgFail: {},
      grabbed: false,
      // "商品详情"底部弹层开关（参数表"查看全部"入口）
      showDetailPopup: false,
      // 服务保障标签（字典 service_tags 渲染；此处为回退默认，onLoad 拉取成功后覆盖）
      serviceTags: ['坏了包退', '晚到必赔', '极速退款'],
      // 精选推荐：展开开关 + 商品列表（优先服务化接口 /api/dishes/recommend，失败回退本地截取）
      recExpanded: false,
      recommendList: [],
      // 回顶悬浮钮：滚动位置与显隐（scroll-view @scroll 中更新，阈值 600px）
      scrollPos: 0,
      recScrolled: false,
      // scroll-view 的 scroll-top 绑定值（仅回顶时变化，配合 scroll-with-animation 平滑滚动）
      scrollTopVal: 0
    }
  },
  computed: {
    // 登录态（与首页弹层同源 store.state.user）：底栏"立即抢购"未登录拦截用
    ...mapState(['user']),
    cartCount() { return this.$store.getters.cartCount },
    // 购物车 id -> 项 的映射（仿首页）：推荐卡判断"已加购"与展示数量
    cartMap() {
      const map = {}
      ;(this.$store.state.cart || []).forEach(item => { map[item.id] = item })
      return map
    },
    // tags 用 | 拼接（undefined 安全：无 tags 时不渲染）
    tagsText() {
      return Array.isArray(this.dish.tags) ? this.dish.tags.join(' | ') : ''
    },
    // "x人买过"：由 soldText（如 已卖1.5万件 → 1.5万人买过）合成
    buyersText() {
      const s = String(this.dish.soldText || '')
      const num = s.replace(/^已卖/, '').replace(/件$/, '')
      return num ? `${num}人买过` : '很多人买过'
    },
    // 非秒杀划线价（1.5 倍现价，异常时返回空串不渲染）
    originalPriceText() {
      const p = Number(this.dish.price)
      return Number.isFinite(p) && p > 0 ? (p * 1.5).toFixed(2) : ''
    },
    // 勾选商品应付金额（getter 缺失/为 0 时兜底 0.00，保证两位小数展示）
    payText() {
      return Number(this.$store.getters.checkedTotalPrice || 0).toFixed(2)
    },
    // 勾选商品已省金额（同上兜底）
    savingText() {
      return Number(this.$store.getters.cartSaving || 0).toFixed(2)
    },
    // 商品参数数据源（固定常量 + 按 unit 合成规格）：页内参数表与"查看全部"弹层共用，保证两处一致
    paramList() {
      const unit = this.dish.unit || '件'
      return [
        { label: '保质期', value: '30天' },
        { label: '规格', value: `30${unit}/份` },
        { label: '产地', value: '云南省红河哈尼族彝族自治州建水县' },
        { label: '商家资质', value: '查看详情 ›', link: true },
        { label: '储存方式', value: '常温' }
      ]
    },
    // 大图浮层只展示前 3 项（服务行展示全部，共用 serviceTags 数据源）
    serviceFloatTags() {
      return this.serviceTags.slice(0, 3)
    },
    // 秒杀播报条固定服务标语：由服务保障标签（字典 service_tags 前 3 项）派生，
    // 替代原 mock 轮播文案，不再暗示他人下单动态；字典拉取失败时仍取 data 内置默认，不会为空
    noticeSlogan() {
      return this.serviceTags.slice(0, 3).join(' · ') + '，产地直采放心购'
    },
    // 头图轮播帧 2-5（商品海报）：文案固定，背景由 bgColor 同色系渐变衍生（rgba 黑压暗，保证白字可读）
    galleryFrames() {
      const bg = this.dish.bgColor || '#FF6600'
      return [
        { text: '产地直采·新鲜到家', bg: bg },
        { text: '坏了包退·晚到必赔·极速退款', bg: `linear-gradient(180deg, ${bg}, rgba(0,0,0,.18))` },
        { text: '今日下单·明日门店自提', bg: `linear-gradient(0deg, ${bg}, rgba(0,0,0,.18))` },
        { text: '限时秒杀·多买多省', bg: `radial-gradient(circle at 50% 40%, ${bg} 30%, rgba(0,0,0,.28))` }
      ]
    },
    // M4-T-03：秒杀进度条真实比例 = sold_count / (sold_count + stock)，脏数据兜底 0、上限 100
    seckillProgressPercent() {
      const sold = Number(this.dish.soldCount) || 0
      const stockNum = Number(this.dish.stock)
      const stock = Number.isFinite(stockNum) && stockNum > 0 ? stockNum : 0
      const total = sold + stock
      if (total <= 0) return 0
      return Math.min(100, Math.round((sold / total) * 100))
    }
  },
  onLoad(options) {
    // 深链非法 id（NaN/undefined）：不发起请求，直接进"商品不存在或已下架"空态页（T-M3-05）
    const dishId = parseInt(options && options.id)
    if (!Number.isFinite(dishId)) {
      this.showPageError('商品不存在或已下架', false)
      return
    }
    this._dishId = dishId
    this.loadDetail(dishId)
  },
  onShow() {
    // M4-T-03：拉取当前秒杀场次（onShow 在 onLoad 后必触发一次，此后每次返回页面刷新；
    // 倒计时基准切到服务端时钟 serverTime+end，改本机时钟不影响；深链非法时跳过）
    if (this._dishId != null) this.loadSeckillSession()
  },
  onUnload() {
    // 清理倒计时定时器（含归零展示态定时器）
    if (this.countdownTimer) clearInterval(this.countdownTimer)
    if (this._zeroTimer) { clearTimeout(this._zeroTimer); this._zeroTimer = null }
  },
  methods: {
    // ==================== 数据加载（T-M3-05：失败错误态） ====================
    // 主详情加载：getDishDetail 404/网络失败 → 空态页；成功后拉评价/推荐/字典并启动倒计时
    async loadDetail(dishId) {
      this.pageError = false
      try {
        // 评价接口失败不阻塞主详情展示（单独兜底空数组），商品详情失败才进错误态
        const [dishRes, reviewRes] = await Promise.all([
          getDishDetail(dishId),
          getReviews(dishId).catch(() => ({ data: [] }))
        ])
        // getDishDetail 内部已按数据契约经 decorateDish 增强，dish 自带
        // seckill/seckillPrice/cutText/limitBuy/soldText/tags/goodRate 等字段
        const dishData = dishRes && dishRes.data
        // 404（不存在/已下架）或 data 为空：服务端已 reject 或结构缺失，均按空态页处理
        if (!dishData || dishData.id == null) {
          this.showPageError('商品不存在或已下架', false)
          return
        }
        this.dish = dishData
        this.imgFail = false
        this.recImgFail = {}
        this.reviews = (reviewRes && reviewRes.data) || []
        // 购物车里已有该商品（或购物车非空）则直接展示"分栏"态，返回再进不重置
        const cart = this.$store.state.cart || []
        this.grabbed = cart.some(i => i.id === dishId) || cart.length > 0
        // 精选推荐服务化（T-M3-13）：优先调 /api/dishes/recommend，失败回退本地截取
        this.fetchRecommend(dishId)
        this.startCountdown()
        // 服务保障标签接字典系统：service_tags 分组（word=显示文本，按 sortOrder 降序），
        // 拉取失败或结果为空时保持 data 内置默认，保证不出现空区块
        getDictGroup('service_tags').then(list => {
          const words = (Array.isArray(list) ? list : []).map(it => it && it.word).filter(Boolean)
          if (words.length) this.serviceTags = words
        }).catch(() => {})
      } catch (e) {
        // 网络异常（code:-1）可重试；404/其他按"商品不存在或已下架"
        if (e && e.code === -1) this.showPageError('网络异常，商品加载失败', true)
        else this.showPageError('商品不存在或已下架', false)
      }
    },
    // 空态页显隐与文案（T-M3-05）：retryable 为 true 时附"点击重试"
    showPageError(text, retryable) {
      this.pageError = true
      this.pageErrorText = text || '商品不存在或已下架'
      this.pageErrorRetryable = !!retryable
    },
    // 空态页"回首页"：项目 pages.json 无 tabBar（switchTab 静默失败），统一用 reLaunch
    backHome() {
      uni.reLaunch({ url: '/pages/index/index' })
    },
    // 空态页"点击重试"（仅网络异常时出现）
    retryLoad() {
      if (this._dishId != null) this.loadDetail(this._dishId)
    },
    // 页面局部请求（T-M3-13/T-M3-05）：api/index.js 归另一位同事维护，为避免文件冲突，
    // 推荐接口与回退全量拉取在页面内用 uni.request 直连（复用 config.js BASE_URL + decorateDish 增强）。
    // 建议后续由 api/index.js 统一导出：getRecommend(excludeId, limit)
    dishRequest(params) {
      return new Promise((resolve, reject) => {
        uni.request({
          url: BASE_URL + '/api/dishes',
          method: 'GET',
          data: params || {},
          timeout: 10000,
          success: (res) => {
            const body = res.data
            if (res.statusCode === 200 && body && body.code === 200 && Array.isArray(body.data)) {
              resolve(body.data.map(decorateDish))
            } else {
              reject({ code: body && body.code != null ? body.code : res.statusCode, message: (body && body.message) || '服务开小差了，请稍后重试' })
            }
          },
          fail: () => reject({ code: -1, message: '网络异常' })
        })
      })
    },
    // ==================== 精选推荐服务化（T-M3-13） ====================
    // 优先调 GET /api/dishes/recommend?excludeId=<当前id>&limit=5（seckill 优先 + 稳定排序）；
    // 接口异常/网络失败时回退现有本地截取逻辑，保证推荐区始终有内容
    async fetchRecommend(dishId) {
      try {
        // 登录态注入（与 api/index.js request 封装同构）：登录用户得个性化推荐
        // （F-02.1 s1 类目偏好/s2 协同依赖订单历史），游客无 token 不带头 → best-seller 兜底
        const stored = uni.getStorageSync('user')
        const recToken = (stored && typeof stored.token === 'string' && stored.token) ? stored.token : ''
        const recHeader = recToken ? { Authorization: 'Bearer ' + recToken } : {}
        const res = await new Promise((resolve, reject) => {
          uni.request({
            url: BASE_URL + '/api/dishes/recommend',
            method: 'GET',
            data: { excludeId: dishId, limit: 5 },
            timeout: 10000,
            header: recHeader,
            success: resolve,
            fail: () => reject(new Error('network'))
          })
        })
        const body = res.data
        // F-02.2 新契约 R<{strategy, items}>：items 在 body.data.items（原为裸数组）；
        // items 为 undefined（契约失败/旧结构）时 Array.isArray 判 false → throw 走下方回退
        const items = body && body.data && body.data.items
        if (res.statusCode === 200 && body && body.code === 200 && Array.isArray(items)) {
          this.recommendList = items.map(decorateDish).filter(d => d && d.id !== dishId).slice(0, 5)
          return
        }
        throw new Error((body && body.message) || 'recommend error')
      } catch (e) {
        // 失败回退：本地全量拉取 → 排除当前商品 → 前 5（拉取失败静默，推荐区显示"暂无推荐商品"）
        this.loadRecommendFallback(dishId)
      }
    },
    // 推荐回退：全量拉取后本地截取
    async loadRecommendFallback(dishId) {
      try {
        const all = await this.dishRequest({})
        this.recommendList = all.filter(d => d && d.id !== dishId).slice(0, 5)
      } catch (e) {
        // 静默：保留"暂无推荐商品"空态
      }
    },
    // 推荐卡图片加载失败：标记该卡回退 emoji（T-M3-01）
    onRecImgFail(id) {
      this.recImgFail = { ...this.recImgFail, [id]: true }
    },
    // 头图轮播切换：同步指示器序号（current 判空兜底，异常事件不改值）
    onGalleryChange(e) {
      const cur = e && e.detail && e.detail.current
      if (Number.isFinite(cur)) this.galleryCurrent = cur
    },
    // 金额统一两位小数展示（T-M3-07）：脏数据兜底 0.00
    fmtPrice(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n.toFixed(2) : '0.00'
    },
    // 评价星级截断到 [1,5]（T-M3-07）：rating 缺失/超界/非数字均安全兜底
    clampRating(v) {
      const n = Number(v)
      if (!Number.isFinite(n)) return 1
      return Math.min(5, Math.max(1, n))
    },
    goToReview() {
      if (this.dish && this.dish.id) {
        uni.navigateTo({ url: `/pages/review/review?dishId=${this.dish.id}` })
      }
    },
    // 23:00 截单倒计时（十分位 tick），秒杀红条/倒计时框/底栏/推荐卡共用；
    // 时间计算统一走 utils/time.js（T-M3-04），页面仅保留自己的定时器；
    // M4-T-03：服务端场次模式下倒计时基准为 /api/seckill/current 的 serverTime+end（服务端时钟
    // +单调时钟递减，改本机时钟不影响），end 过点重拉接口（切场）；回退模式目标 = 下一个 23:00；
    // 跨过截单/切场瞬间切"已切明日场次"置灰 2s 后重置，不闪跳
    startCountdown() {
      const tick = () => {
        // 归零展示态期间冻结数字，由 _zeroTimer 在 2s 后恢复
        if (this.countdownZero) return
        const info = getCutoffInfo()
        // 自提时间动态文案：时段取自提点 timeText、日期词按截单口径动态替换（与首页共用 formatPickupTimeText）
        this.pickupTimeText = formatPickupTimeText(this.$store.state.pickupPoint && this.$store.state.pickupPoint.timeText, info)
        // M4-T-03：服务端场次模式——倒计时基准为接口 serverTime+end
        if (this.seckillServerMode) {
          const remaining = this._seckillRemaining0 - (monotonicNow() - this._seckillMono0)
          if (remaining <= 0) { this.onSeckillSessionEnd(); return }
          const cd = formatCountdown(remaining)
          this.countdownText = cd.text
          this.countdownTenth = cd.tenth
          return
        }
        // 本地回退：原 23:00 截单口径（接口未上线/拉取失败时兜底）
        // 刚跨过今日 23:00（2s 宽限窗内）：归零帧直接切置灰态
        if (info.justPassedCutoff) { this.enterCountdownZero(info); return }
        this.isAfterCutoff = info.isAfterCutoff
        const cd = formatCountdown(info.diffMs)
        this.countdownText = cd.text
        this.countdownTenth = cd.tenth
      }
      tick()
      this.countdownTimer = setInterval(tick, 100)
    },
    // ==================== 秒杀场次（M4-T-03：服务端时钟倒计时） ====================
    // 拉取当前场次：以 serverTime 与本机钟差锚定场次剩余毫秒，tick 走单调时钟递减；
    // end 过点由 tick 触发 onSeckillSessionEnd 重拉（切场）。失败回退本地 23:00 截单口径
    async loadSeckillSession() {
      if (this._seckillFetching) return false
      this._seckillFetching = true
      try {
        const res = await getSeckillCurrent()
        const data = (res && res.data) || {}
        const serverNowMs = parseServerTimeMs(data.serverTime)
        const endMs = sessionEndMs(data.start, data.end, serverNowMs)
        if (serverNowMs == null || endMs == null) {
          this.seckillServerMode = false
          return false
        }
        this._seckillMono0 = monotonicNow()
        this._seckillRemaining0 = Math.max(0, endMs - serverNowMs)
        this.seckillServerMode = true
        return true
      } catch (e) {
        // 接口未上线(404)/网络异常：回退本地口径
        this.seckillServerMode = false
        return false
      } finally {
        this._seckillFetching = false
      }
    },
    // 切场（end 过点）：置灰"已切明日场次"并重拉接口；重拉失败 2s 后回退本地口径不闪跳；
    // 3s 冷却：服务端场次数据异常（顺延后仍已过点）时防止 100ms tick 连环触发重拉
    async onSeckillSessionEnd() {
      if (this._seckillSwitching) return
      const nowMono = monotonicNow()
      if (this._lastSwitchAt && nowMono - this._lastSwitchAt < 3000) return
      this._lastSwitchAt = nowMono
      this._seckillSwitching = true
      this.countdownZero = true
      const ok = await this.loadSeckillSession()
      if (ok) {
        this.countdownZero = false
      } else {
        setTimeout(() => {
          this.countdownZero = false
          this.seckillServerMode = false
        }, 2000)
      }
      this._seckillSwitching = false
    },
    // 归零展示态：置灰显示"已切明日场次"2s，随后重算（目标已是明天 23:00）
    enterCountdownZero(info) {
      this.countdownZero = true
      this.isAfterCutoff = !!(info && info.isAfterCutoff)
      if (this._zeroTimer) clearTimeout(this._zeroTimer)
      this._zeroTimer = setTimeout(() => {
        this._zeroTimer = null
        this.countdownZero = false
      }, 2000)
    },
    // ==================== 去支付：跳回首页打开确认订单 ====================
    // 详情页无确认订单弹窗：写 storage 标志 openCheckout=1 后 reLaunch 首页，
    // 首页 onShow 读取该标志（购物车非空）打开确认订单弹窗并用后即清（与搜索页 goPayFromSearch 同链路）
    goPay() {
      // 游客下单拦截：结算未登录先引导去独立登录页（带 redirect 回本页续走）；
      // 未登录判定与底栏"立即抢购"一致（!user.token）
      if (!this.user.token) {
        uni.showModal({
          title: '登录提示',
          content: '登录后即可下单，是否前往登录？',
          confirmText: '去登录',
          cancelText: '再逛逛',
          success: (r) => {
            if (r.confirm) uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/detail/detail?id=' + (this.dish && this.dish.id || '')) })
          }
        })
        return
      }
      try { uni.setStorageSync('openCheckout', '1') } catch (e) {
        // 存储异常静默：标志缺失时首页不弹，购物车数据仍全局共享不丢失
      }
      uni.reLaunch({ url: '/pages/index/index' })
    },
    // 底栏"立即抢购"（空车整条/分栏右钮共用）：游客下单拦截——未登录弹窗引导去登录页，
    // 登录成功后 reLaunch 回本页（redirect 带当前商品 id）；加购类入口（推荐卡/步进器）不拦截
    addToCart() {
      if (!this.user.token) {
        uni.showModal({
          title: '登录提示',
          content: '登录后即可下单，是否前往登录？',
          confirmText: '去登录',
          cancelText: '再逛逛',
          success: (r) => {
            if (r.confirm) uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/detail/detail?id=' + (this.dish && this.dish.id || '')) })
          }
        })
        return
      }
      // 加购：可连续点击累加；达到 limitBuy 限购后只提示不再加
      const dish = this.dish
      if (!dish || !dish.id) return
      const limit = Number(dish.limitBuy)
      const item = (this.$store.state.cart || []).find(i => i.id === dish.id)
      if (Number.isFinite(limit) && limit > 0 && item && item.quantity >= limit) {
        uni.showToast({ title: `限购${dish.limitBuy}件，已达上限`, icon: 'none' })
        return
      }
      // 提交 decorateDish 增强后的 dish，由 addToCart mutation 归一化入车
      this.$store.commit('addToCart', dish)
      this.grabbed = true
    },
    // 打开"商品详情"底部弹层（查看全部入口）
    openDetailPopup() {
      this.showDetailPopup = true
    },
    // 关闭"商品详情"底部弹层（遮罩点击 / 右上 ✕ 共用）
    closeDetailPopup() {
      this.showDetailPopup = false
    },
    // 弹层内"商家资质-查看详情"：先关弹层再跳资质页（路径与 pages.json 注册的 pages/qualification/qualification 一致）
    goQualification() {
      this.closeDetailPopup()
      uni.navigateTo({ url: '/pages/qualification/qualification' })
    },
    // ==================== 精选推荐与回顶部 ====================
    // 展开/收起精选推荐区块
    toggleRec() { this.recExpanded = !this.recExpanded },
    // scroll-view 滚动：超过 600px 标记（悬浮钮显示条件之一，需同时展开推荐）；
    // 悬浮钮显示期间才持续记录位置（回顶基准），减少高频 scroll 下的响应式开销
    onMainScroll(e) {
      const top = (e && e.detail && e.detail.scrollTop) || 0
      this.recScrolled = top > 600
      if (this.recExpanded && this.recScrolled) this.scrollPos = top
    },
    // 悬浮钮：平滑滚回顶部。scroll-top 基准已是 0（上次回顶后手动下滚）时先同步实际位置再归零，
    // 保证属性值变化触发 scroll-view 滚动；平滑由 scroll-with-animation 负责
    backToTop() {
      if (this.scrollTopVal !== 0) { this.scrollTopVal = 0; return }
      this.scrollTopVal = this.scrollPos
      setTimeout(() => { this.scrollTopVal = 0 }, 50)
    },
    // 推荐卡未加购 -> 首次加入（走 addToCart mutation normalize 入车，与首页 addDish 一致）
    addRec(dish) { this.$store.commit('addToCart', dish) },
    // 推荐卡步进器 "+"：达到 limitBuy 限购拦截 toast（与首页 increaseById 一致，含加到上限提示）
    plusRec(dish) {
      const item = this.cartMap[dish.id]
      if (!item) { this.addRec(dish); return }
      const limit = Number(dish.limitBuy) || 0
      const next = item.quantity + 1
      if (limit && item.quantity >= limit) {
        uni.showToast({ title: `限购${limit}件`, icon: 'none' })
        return
      }
      this.$store.commit('updateQuantity', { dishId: dish.id, quantity: next })
      // 加到限购数量时同样提示
      if (limit && next === limit) uni.showToast({ title: `限购${limit}件`, icon: 'none' })
    },
    // 推荐卡步进器 "-"：减到 0 直接 removeFromCart（与首页 decreaseById 一致）
    minusRec(dish) {
      const item = this.cartMap[dish.id]
      if (!item) return
      if (item.quantity <= 1) {
        this.$store.commit('removeFromCart', dish.id)
      } else {
        this.$store.commit('updateQuantity', { dishId: dish.id, quantity: item.quantity - 1 })
      }
    }
  }
}
</script>

<style scoped>
/* ==================== 多多买菜风格 — 详情页 ==================== */

.page-container { position: fixed; top: var(--window-top, 0px); left: 0; right: 0; bottom: 0; background-color: #F5F5F5; display: flex; flex-direction: column; overflow: hidden; }

/* 可滚动主体 */
.main-scroll { flex: 1; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch; }
.scroll-bottom-pad { height: 12rpx; }

/* ---------- 1. 大图区 ---------- */
.detail-image { position: relative; width: 100%; height: 520rpx; display: flex; align-items: center; justify-content: center; }
.detail-emoji { font-size: 160rpx; }
/* 真实图片（T-M3-01）：铺满 520rpx 大图容器，@error/空值回退 emoji 色块 */
.detail-photo { width: 100%; height: 100%; display: block; }
/* 头图五帧轮播：uniapp swiper 默认高 150px，必须显式高度 */
.detail-swiper { width: 100%; height: 520rpx; }
/* 帧 1 主图：swiper-item 非 flex 容器，包一层居中（背景由外层 .detail-image 透出） */
.slide-main { width: 100%; height: 100%; display: flex; align-items: center; justify-content: center; }
/* 帧 2-5 海报：居中大号 emoji + 一行短文案（白字加阴影，浅色底也可读） */
.slide-poster { width: 100%; height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 24rpx; }
.slide-poster-emoji { font-size: 150rpx; text-shadow: 0 6rpx 16rpx rgba(0,0,0,.18); }
.slide-poster-text { font-size: 28rpx; color: #FFFFFF; font-weight: 700; letter-spacing: 2rpx; text-shadow: 0 2rpx 10rpx rgba(0,0,0,.45); }

/* ---------- 0. 空态页（T-M3-05：id 非法/404/加载失败） ---------- */
.page-error { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 24rpx; background-color: #FFFFFF; }
.pe-icon { font-size: 100rpx; opacity: .4; }
.pe-text { font-size: 28rpx; color: #666666; }
.pe-btn {
  margin-top: 8rpx; min-width: 280rpx; height: 80rpx; line-height: 80rpx;
  padding: 0 48rpx; font-size: 28rpx; font-weight: 700; color: #FFFFFF;
  background-color: #E02020; border: none; border-radius: 44rpx;
}
.pe-btn::after { border: none; }
.pe-retry { font-size: 26rpx; color: #E02020; padding: 8rpx 20rpx; }
/* 左下：销量标签（深色半透明圆角） */
.image-sold { position: absolute; left: 20rpx; bottom: 84rpx; background-color: rgba(0,0,0,.5); border-radius: 999rpx; padding: 6rpx 18rpx; }
.image-sold-text { font-size: 20rpx; color: #FFFFFF; }
/* 左下：白底圆角服务保障浮层（绿字） */
.image-service { position: absolute; left: 20rpx; bottom: 20rpx; background-color: #FFFFFF; border-radius: 999rpx; padding: 8rpx 18rpx; }
.image-service-text { font-size: 20rpx; color: #00B578; font-weight: 600; }
/* 右下：图片指示 1/5 */
.image-index { position: absolute; right: 20rpx; bottom: 20rpx; background-color: rgba(0,0,0,.45); border-radius: 999rpx; padding: 4rpx 16rpx; }
.image-index-text { font-size: 20rpx; color: #FFFFFF; }

/* ---------- 2. 秒杀红色价格条（仅秒杀商品） ---------- */
.seckill-bar { background: linear-gradient(90deg, #E02020, #FF4A3A); padding: 20rpx 24rpx; }
.seckill-main { display: flex; align-items: center; justify-content: space-between; }
.seckill-left { display: flex; align-items: center; gap: 14rpx; }
.seckill-price { font-size: 44rpx; color: #FFFFFF; font-weight: 800; line-height: 1.1; }
.seckill-limit { font-size: 20rpx; color: #FFFFFF; border: 2rpx solid rgba(255,255,255,.85); border-radius: 8rpx; padding: 2rpx 12rpx; white-space: nowrap; }
.seckill-right { display: flex; flex-direction: column; align-items: flex-end; gap: 4rpx; flex-shrink: 0; margin-left: 16rpx; }
.seckill-right-title { font-size: 22rpx; color: #FFFFFF; }
.seckill-countdown { font-size: 34rpx; color: #FFFFFF; font-weight: 800; line-height: 1.1; font-variant-numeric: tabular-nums; }
/* 归零展示态：置灰"已切明日场次"（T-M3-04 归零不闪跳），字号收窄避免撑爆红条 */
.seckill-countdown.countdown-zero { font-size: 24rpx; font-weight: 600; color: rgba(255,255,255,.72); }
/* 红条下方浅红进度条：黄色进度 + 疯抢文案 */
.seckill-subbar { background-color: #FCE4E2; display: flex; align-items: center; gap: 16rpx; padding: 12rpx 24rpx; }
.subbar-track { flex: 1; height: 14rpx; border-radius: 7rpx; background-color: #F3BDB8; overflow: hidden; }
.subbar-fill { height: 100%; border-radius: 7rpx; background-color: #FFC300; }
.subbar-text { font-size: 20rpx; color: #E02020; font-weight: 600; white-space: nowrap; }

/* ---------- 3. 标题信息区 ---------- */
.info-section { padding: 24rpx 28rpx; background-color: #FFFFFF; margin-bottom: 12rpx; }
/* 非秒杀普通价格区（沿用现有样式） */
.detail-price-row { display: flex; align-items: baseline; margin-bottom: 16rpx; }
.detail-price-symbol { font-size: 28rpx; color: #E02020; font-weight: 700; }
.detail-price { font-size: 52rpx; color: #E02020; font-weight: 700; line-height: 1; }
.detail-unit { font-size: 24rpx; color: #999999; margin-left: 6rpx; }
.detail-original { font-size: 24rpx; color: #999999; text-decoration: line-through; margin-left: 16rpx; }
.countdown-box { display: flex; align-items: center; gap: 10rpx; background: linear-gradient(90deg, #FFF3EB, #FFF0E8); padding: 16rpx 20rpx; border-radius: 10rpx; margin-bottom: 16rpx; }
.countdown-icon { font-size: 22rpx; }
.countdown-label { font-size: 22rpx; color: #FF6600; }
.countdown-value { font-size: 30rpx; font-weight: 700; color: #FF6600; font-variant-numeric: tabular-nums; }
/* 标题行：[秒]红标 + 商品名 */
.title-row { display: flex; align-items: flex-start; gap: 12rpx; margin-bottom: 14rpx; }
.sec-badge { flex-shrink: 0; background-color: #E02020; color: #FFFFFF; font-size: 20rpx; font-weight: 700; border-radius: 6rpx; padding: 4rpx 10rpx; margin-top: 6rpx; }
/* 长商品名两行截断（T-M3-07）：超两行省略号收尾 */
.detail-name { font-size: 36rpx; font-weight: 700; color: #333333; line-height: 1.4; flex: 1; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; word-break: break-all; }
/* 副标题行：绿色小标 + tags 橙色小字 */
.title-sub { display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx; margin-bottom: 14rpx; }
.green-tag { font-size: 20rpx; color: #00B578; background-color: #EAF9F1; border-radius: 6rpx; padding: 4rpx 10rpx; }
.orange-tags { font-size: 22rpx; color: #FF6600; }
/* 好评率 / 销量 */
.title-stats { display: flex; align-items: center; gap: 24rpx; margin-bottom: 10rpx; }
.stat-rate { font-size: 22rpx; color: #FF6600; font-weight: 600; }
.stat-sold { font-size: 22rpx; color: #999999; }
.stock-text { font-size: 24rpx; color: #CCCCCC; display: block; }

/* ---------- 4. 推荐理由格（tags 逐项 + 固定储存条件格） ---------- */
.reason-card { background-color: #FFFFFF; padding: 24rpx 12rpx; margin-bottom: 12rpx; display: flex; align-items: stretch; }
.reason-cell { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8rpx; border-right: 2rpx solid #F2F2F2; padding: 4rpx 8rpx; }
.reason-cell:last-child { border-right: none; }
.reason-word { font-size: 26rpx; color: #333333; font-weight: 600; }
.reason-sub { font-size: 20rpx; color: #999999; }

/* ---------- 5. 秒杀播报条（仅秒杀商品） ---------- */
.notice-bar { margin: 0 24rpx 12rpx; background: linear-gradient(90deg, #E02020, #FF4A3A); border-radius: 16rpx; padding: 16rpx 20rpx; }
.notice-head { display: flex; align-items: center; gap: 12rpx; margin-bottom: 12rpx; }
.notice-title { font-size: 26rpx; color: #FFFFFF; font-weight: 800; white-space: nowrap; }
.notice-divider { font-size: 22rpx; color: rgba(255,255,255,.6); }
.notice-cut { font-size: 22rpx; color: #FFFFFF; flex: 1; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
/* 内嵌白色圆角子条 */
.notice-sub { background-color: #FFFFFF; border-radius: 999rpx; padding: 8rpx 20rpx; }
.notice-text { font-size: 20rpx; color: #E02020; }

/* ---------- 6. 服务保障行 ---------- */
.service-card { background-color: #FFFFFF; padding: 4rpx 28rpx; margin-bottom: 12rpx; }
.service-item { display: flex; align-items: center; justify-content: space-between; padding: 22rpx 0; border-bottom: 2rpx solid #F5F5F5; }
.service-item-last { border-bottom: none; }
.service-left { display: flex; align-items: center; gap: 10rpx; }
.service-check { font-size: 24rpx; color: #00B578; font-weight: 700; }
.service-clock { font-size: 24rpx; }
.service-text { font-size: 24rpx; color: #333333; }
.service-arrow { font-size: 26rpx; color: #CCCCCC; }

/* ---------- 通用白卡区块（评价 / 图文详情） ---------- */
.section-block { background-color: #FFFFFF; padding: 28rpx 28rpx; margin-bottom: 12rpx; }
.section-title { font-size: 28rpx; font-weight: 700; color: #333333; display: block; margin-bottom: 20rpx; }
.desc-content { font-size: 26rpx; color: #666666; line-height: 1.9; display: block; margin-bottom: 32rpx; white-space: pre-line; }

/* 参数表：两列灰底 */
.param-grid { display: flex; flex-wrap: wrap; gap: 12rpx; margin-bottom: 28rpx; }
.param-cell { width: calc(50% - 6rpx); box-sizing: border-box; background-color: #F7F7F7; border-radius: 8rpx; padding: 16rpx 20rpx; display: flex; flex-direction: column; gap: 6rpx; }
.param-label { font-size: 22rpx; color: #999999; }
.param-value { font-size: 24rpx; color: #333333; line-height: 1.5; word-break: break-all; }
.param-link { color: #E02020; }
/* 查看全部入口：灰色小字，靠左 */
.param-more { margin-bottom: 24rpx; }
.param-more-text { font-size: 24rpx; color: #999999; }

/* 图文色块图 */
.desc-images { display: flex; flex-direction: column; gap: 12rpx; }
.desc-img-item { width: 100%; height: 280rpx; border-radius: 12rpx; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.desc-img-emoji { font-size: 64rpx; margin-bottom: 12rpx; }
.desc-img-label { font-size: 20rpx; color: #999999; }

/* ---------- 7. 评价区（逻辑不变） ---------- */
.review-entry { cursor: pointer; }
.entry-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.entry-left { display: flex; align-items: center; gap: 12rpx; }
.entry-icon { font-size: 32rpx; }
.entry-text { font-size: 28rpx; font-weight: 700; color: #333333; }
.entry-right { display: flex; align-items: center; gap: 12rpx; }
.entry-rate { font-size: 24rpx; color: #E02020; font-weight: 600; }
.entry-count { font-size: 24rpx; color: #999999; }
.review-preview { display: flex; flex-direction: column; gap: 20rpx; }
.preview-item { background-color: #FFF8F5; border-radius: 12rpx; padding: 20rpx; }
.preview-user { display: flex; align-items: center; gap: 10rpx; margin-bottom: 10rpx; }
.preview-avatar { width: 44rpx; height: 44rpx; background: linear-gradient(135deg, #FF6600, #FF8533); border-radius: 50%; font-size: 20rpx; color: #FFFFFF; display: flex; align-items: center; justify-content: center; font-weight: 700; flex-shrink: 0; }
.preview-name { font-size: 24rpx; color: #333333; font-weight: 600; }
.preview-stars { display: flex; gap: 2rpx; margin-bottom: 8rpx; }
.star { font-size: 22rpx; color: #DDDDDD; }
.star.active { color: #FF6600; }
.preview-content { font-size: 24rpx; color: #666666; line-height: 1.6; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }

/* ---------- 9. 底部操作栏 ---------- */
.bottom-bar { background-color: #FFFFFF; box-shadow: 0 -2rpx 20rpx rgba(0,0,0,.06); flex-shrink: 0; }
/* uni-button 显式覆盖：直角大红块、无默认边框圆角与 line-height:2.55 */
.btn-grab { width: 100%; background-color: #E02020; color: #FFFFFF; border: none; border-radius: 0; padding: 14rpx 0 22rpx; margin: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8rpx; line-height: 1.3; font-size: 26rpx; }
.btn-grab::after { border: none; }
.grab-main { font-size: 26rpx; font-weight: 700; line-height: 1.3; }
.grab-sub { font-size: 24rpx; opacity: .9; letter-spacing: 1rpx; font-variant-numeric: tabular-nums; line-height: 1.3; }

/* 分栏态：购物车 + 去支付 + 抢购按钮 */
.bar-split { display: flex; align-items: stretch; }
.bar-cart { position: relative; width: 110rpx; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.cart-icon { font-size: 44rpx; }
.cart-badge { position: absolute; top: 14rpx; left: 50%; margin-left: -16rpx; min-width: 30rpx; height: 30rpx; padding: 0 6rpx; box-sizing: border-box; border-radius: 15rpx; background-color: #E02020; color: #FFFFFF; font-size: 18rpx; display: flex; align-items: center; justify-content: center; }
/* 中：去支付 + 已减 */
.bar-pay { flex: 1; display: flex; flex-direction: column; justify-content: center; gap: 6rpx; padding: 0 24rpx; overflow: hidden; }
.pay-main { font-size: 28rpx; font-weight: 700; color: #E02020; white-space: nowrap; font-variant-numeric: tabular-nums; }
.pay-sub { font-size: 22rpx; color: #999999; }
/* 右：直角大红块两行 */
.btn-grab-right { width: auto; min-width: 300rpx; flex-shrink: 0; padding: 14rpx 28rpx 22rpx; }

/* ---------- 10. 商品详情底部弹层 ---------- */
/* 半透明黑色遮罩：点击空白处关闭；v-if 挂载时淡入（动画与 v-if 配对，每次打开重新播放） */
.detail-popup-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(0,0,0,.5); z-index: 999; animation: popupMaskIn .2s ease both; }
/* 白色圆角面板：底部滑出，高度自适应内容、最高 55vh */
.detail-popup { position: absolute; left: 0; right: 0; bottom: 0; background-color: #FFFFFF; border-radius: 24rpx 24rpx 0 0; max-height: 55vh; display: flex; flex-direction: column; animation: popupSlideUp .25s ease both; }
/* 顶部标题栏：标题居中，✕ 绝对定位右上 */
.popup-header { position: relative; flex-shrink: 0; display: flex; align-items: center; justify-content: center; padding: 28rpx 0 20rpx; }
.popup-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.popup-close { position: absolute; right: 28rpx; top: 50%; transform: translateY(-50%); font-size: 30rpx; color: #999999; line-height: 1; padding: 8rpx; }
/* 内容区：超出 55vh 时内部滚动（复用 main-scroll 的 flex:1 + min-height:0 模式） */
.popup-scroll { flex: 1; min-height: 0; overflow-y: auto; box-sizing: border-box; padding: 0 28rpx 28rpx; }
.popup-param-grid { margin-bottom: 0; }
.popup-param-grid .param-link { cursor: pointer; }
/* 动画：遮罩淡入 + 面板自底上滑 */
@keyframes popupMaskIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes popupSlideUp { from { transform: translateY(100%); } to { transform: translateY(0); } }

/* ---------- 11. 价格说明（白卡） ---------- */
.price-note-card { background-color: #FFFFFF; padding: 28rpx; }
.pn-title { display: block; font-size: 28rpx; font-weight: 700; color: #333333; margin-bottom: 16rpx; }
.pn-text { display: block; font-size: 24rpx; color: #999999; line-height: 1.8; margin-bottom: 16rpx; }
.pn-text-last { margin-bottom: 0; }
.pn-subtitle { display: block; font-size: 26rpx; font-weight: 700; color: #333333; margin-bottom: 12rpx; }

/* ---------- 12. 查看精选推荐（居中胶囊按钮：白底 + 浅灰描边，展开/收起同款，与上下内容各留 20rpx） ---------- */
.rec-toggle { display: flex; align-items: center; justify-content: center; width: fit-content; margin: 20rpx auto; background-color: #FFFFFF; border: 1rpx solid #EEEEEE; border-radius: 40rpx; padding: 14rpx 40rpx; box-sizing: border-box; }
.rec-toggle-text { font-size: 26rpx; color: #666666; }
/* CSS chevron：12rpx 方块取右+下两条 3rpx 边框，rotate(45deg) 成 ⌄；展开态 -135° 成 ^；flex 子项随父级 align-items:center 垂直居中 */
.rec-arrow { width: 12rpx; height: 12rpx; margin-left: 8rpx; box-sizing: border-box; border-right: 3rpx solid #666666; border-bottom: 3rpx solid #666666; transform: rotate(45deg); transition: transform .2s; }
.rec-arrow-up { transform: rotate(-135deg); }

/* ---------- 13. 精选推荐（卡片复刻首页多多卡：240rpx 图 + 信息列 + 步进器） ---------- */
.rec-section { animation: recFadeIn .25s ease both; }
.rec-header { display: flex; align-items: center; justify-content: center; gap: 10rpx; padding: 8rpx 0 20rpx; }
.rec-heart { font-size: 30rpx; }
.rec-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.rec-card { display: flex; background-color: #FFFFFF; border-radius: 12rpx; border: 1rpx solid #EEEEEE; box-sizing: border-box; padding: 16rpx; margin-bottom: 16rpx; box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04); }
.rec-img { width: 240rpx; height: 240rpx; flex-shrink: 0; border-radius: 8rpx; overflow: hidden; display: flex; align-items: center; justify-content: center; }
.rec-emoji { font-size: 100rpx; }
/* 推荐卡真实图片（T-M3-01）：铺满 240rpx 色块容器，@error 回退 emoji */
.rec-photo { width: 100%; height: 100%; display: block; }
.rec-body { flex: 1; min-width: 0; margin-left: 20rpx; display: flex; flex-direction: column; }
.rec-title-row { display: flex; align-items: center; min-width: 0; }
.rec-sec-tag { flex-shrink: 0; margin-right: 8rpx; font-size: 18rpx; font-weight: 700; color: #FFFFFF; background-color: #E02020; border-radius: 4rpx; padding: 0 8rpx; line-height: 30rpx; }
.rec-name { flex: 1; min-width: 0; font-size: 28rpx; font-weight: 600; color: #333333; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rec-tags { margin-top: 8rpx; font-size: 20rpx; color: #999999; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rec-sold-row { display: flex; align-items: center; gap: 12rpx; margin-top: 8rpx; }
.rec-rush { font-size: 20rpx; font-weight: 600; color: #E02020; flex-shrink: 0; }
.rec-sold { font-size: 20rpx; color: #999999; }
.rec-promo-row { display: flex; align-items: center; gap: 10rpx; margin-top: 8rpx; min-height: 30rpx; }
.rec-tag-cut { font-size: 18rpx; color: #E02020; font-weight: 600; border: 1rpx solid #E02020; border-radius: 6rpx; padding: 0 8rpx; line-height: 26rpx; }
.rec-tag-limit { font-size: 18rpx; color: #999999; border: 1rpx solid #DDDDDD; border-radius: 6rpx; padding: 0 8rpx; line-height: 26rpx; }
.rec-rate { font-size: 20rpx; color: #999999; }
.rec-price-row { margin-top: auto; display: flex; align-items: flex-end; justify-content: space-between; }
.rec-price-left { display: flex; align-items: baseline; min-width: 0; }
.rec-price-label { font-size: 20rpx; font-weight: 700; color: #E02020; margin-right: 4rpx; }
.rec-price-symbol { font-size: 20rpx; font-weight: 700; color: #E02020; }
.rec-price-big { font-size: 38rpx; font-weight: 700; color: #E02020; line-height: 1; }
.rec-action { flex-shrink: 0; margin-left: 16rpx; }
.rec-add-btn { min-width: 160rpx; height: 56rpx; padding: 0 22rpx; box-sizing: border-box; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #FF5A3C, #E02020); border-radius: 28rpx; }
.rec-add-btn text { font-size: 22rpx; font-weight: 700; color: #FFFFFF; }
.rec-stepper { display: flex; align-items: center; }
.rec-step-btn { width: 46rpx; height: 46rpx; border-radius: 50%; display: flex; align-items: center; justify-content: center; }
.rec-step-btn text { font-size: 30rpx; font-weight: 700; line-height: 1; }
.rec-step-minus { border: 1rpx solid #E02020; background-color: #FFFFFF; }
.rec-step-minus text { color: #E02020; }
.rec-step-plus { background-color: #E02020; }
.rec-step-plus text { color: #FFFFFF; }
.rec-step-num { min-width: 56rpx; text-align: center; font-size: 26rpx; font-weight: 700; color: #333333; }
/* 秒杀卡内超级秒杀条：红底圆角小条，左标题右倒计时（全场共用同一倒计时） */
.rec-seckill-bar { margin-top: 12rpx; display: flex; align-items: center; justify-content: space-between; background: linear-gradient(90deg, #E02020, #FF4A3A); border-radius: 8rpx; padding: 8rpx 16rpx; }
.rec-seckill-label { font-size: 20rpx; color: #FFFFFF; font-weight: 700; }
.rec-seckill-countdown { font-size: 20rpx; color: #FFFFFF; font-weight: 700; font-variant-numeric: tabular-nums; }
/* 推荐卡内归零展示态：置灰（T-M3-04） */
.rec-seckill-countdown.rec-countdown-zero { color: rgba(255,255,255,.72); font-weight: 600; }
.rec-empty { display: flex; align-items: center; justify-content: center; padding: 60rpx 0; }
.rec-empty text { font-size: 24rpx; color: #CCCCCC; }

/* ---------- 14. 回顶部悬浮钮（精选推荐展开且下滚超过阈值时出现） ---------- */
.back-top-btn { position: fixed; right: 24rpx; bottom: 280rpx; z-index: 50; width: 88rpx; height: 88rpx; border-radius: 50%; background-color: #FFFFFF; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.15); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4rpx; animation: recFadeIn .2s ease both; }
.bt-arrow { font-size: 26rpx; color: #333333; line-height: 1; }
.bt-text { font-size: 18rpx; color: #666666; line-height: 1; }
/* 动画：精选推荐展开 / 悬浮钮出现时的淡入上移 */
@keyframes recFadeIn { from { opacity: 0; transform: translateY(20rpx); } to { opacity: 1; transform: translateY(0); } }
</style>
