<template>
  <view class="page-container" :class="{ 'with-bar': !isCartEmpty }">
    <!-- ==================== 红色头部（navigationStyle custom 自绘）：返回 + 标题 + 右上「规则｜明细」 ==================== -->
    <view class="free-header">
      <view class="free-back" @click="goBack"><text class="free-back-arrow">&lt;</text></view>
      <text class="free-title">免费领商品</text>
      <view class="free-header-right">
        <text class="header-link" @click="onRules">规则</text>
        <text class="header-divider">|</text>
        <text class="header-link" @click="onDetail">明细</text>
      </view>
    </view>

    <!-- ==================== 加载失败态（≠活动下线：请求异常给重试入口） ==================== -->
    <view v-if="loadError" class="error-state">
      <text class="state-icon">⚠️</text>
      <text class="state-label">活动加载失败</text>
      <text class="state-sub">网络异常或服务暂不可用，请稍后重试</text>
      <button class="btn-retry" @click="retryAll"><text>点击重试</text></button>
    </view>

    <!-- ==================== 首载中（避免 activity 未返回时闪现"暂无活动"空态） ==================== -->
    <view v-else-if="!loaded" class="empty-state">
      <text class="state-label">加载中…</text>
    </view>

    <!-- ==================== 暂无活动态（后端 activityStatus='empty'：无今日免费活动） ==================== -->
    <view v-else-if="activityEmpty" class="empty-state">
      <text class="state-icon">🎁</text>
      <text class="state-label">今日暂无免费活动</text>
      <text class="state-sub">好货天天有，明天再来看看吧</text>
    </view>

    <!-- ==================== 活动暂未开始态（后端 activityStatus='offline'：下线/商品下架） ==================== -->
    <view v-else-if="offline" class="empty-state">
      <text class="state-icon">🕒</text>
      <text class="state-label">活动暂未开始</text>
      <text class="state-sub">开放时间以公告为准，敬请期待</text>
    </view>

    <template v-else>
      <!-- ==================== 播报条（登录态今日已有 free 领取记录才显示，静态一条不做假播报） ==================== -->
      <view class="broadcast-bar" v-if="broadcastText">
        <text class="broadcast-icon">📢</text>
        <text class="broadcast-text">{{ broadcastText }}</text>
      </view>

      <!-- ==================== 免费商品大卡（金色舞台感：今天带走角标 + 大图 + 图上悬浮更换 + 规格行） ==================== -->
      <view class="hero-card" v-if="selectedDish">
        <view class="hero-stage">
          <view class="hero-badge"><text class="hero-badge-text">今天带走</text></view>
          <view class="hero-img-wrap">
            <image
              v-if="selectedDish.image && !heroImgFail"
              class="hero-img"
              :src="selectedDish.image"
              mode="aspectFill"
              @error="onHeroImgFail"
            />
            <view v-else class="hero-img hero-img-fallback" :style="{ backgroundColor: selectedDish.bgColor || '#FFF3E0' }">
              <text class="hero-img-emoji">{{ selectedDish.emoji || '🎁' }}</text>
            </view>
            <view class="hero-change" @click="openChange"><text class="hero-change-text">更换 ›</text></view>
          </view>
        </view>
        <view class="hero-info">
          <view class="hero-name-row">
            <text class="hero-name">{{ selectedDish.name }}</text>
            <text class="hero-unit" v-if="selectedDish.unit">{{ selectedDish.unit }}</text>
          </view>
          <view class="hero-price-row">
            <text class="hero-zero">¥0.00</text>
            <text class="hero-zero-tag">0元领</text>
            <text class="hero-origin">¥{{ fmtPrice(selectedDish.price) }}</text>
          </view>
          <text class="hero-quota-tip" v-if="parsedActivity.quota > 0">每日限量 {{ parsedActivity.quota }} 份 · 先到先得，领完即止</text>
        </view>
      </view>

      <!-- ==================== 倒计时行（当日 23:00 截单口径，utils/time.js getCutoffInfo，每秒 tick） ==================== -->
      <view class="countdown-row">
        <text class="cd-icon">⏰</text>
        <text class="cd-value">{{ countdownText }}</text>
        <text class="cd-suffix">后过期</text>
      </view>

      <!-- ==================== 进度条大按钮（未达标滚到选购区 / 达标直接领取 / 已领·抢完置灰） ==================== -->
      <view class="claim-area">
        <button
          class="btn-claim"
          :class="claimButton.cls"
          :disabled="claimButton.disabled"
          @click="onClaim"
        >
          <view class="claim-fill" v-if="!claimButton.disabled" :style="{ width: progressPercent + '%' }"></view>
          <text class="btn-claim-text">{{ claimButton.text }}</text>
        </button>
      </view>

      <!-- ==================== 商品选购区（分类 tab 前端过滤 + 商品卡列表，加购走 Vuex 共享购物车） ==================== -->
      <view class="goods-section">
        <view class="cat-tab-bar">
          <scroll-view class="cat-tabs-scroll" scroll-x>
            <view class="cat-tabs">
              <view
                class="cat-tab"
                :class="{ active: currentCategory === t.key }"
                v-for="t in freeTabs"
                :key="t.key"
                @click="switchCategory(t.key)"
              ><text>{{ t.name }}</text></view>
            </view>
          </scroll-view>
          <view class="cat-all" @click="openCatAll"><text>≡ 全部</text></view>
        </view>

        <!-- 全部分类下拉（数据源 dish_category 字典，点击真实过滤，与首页弹层同款语义） -->
        <view class="cat-drop" v-if="showCatAll">
          <view class="cat-drop-mask" @click="closeCatAll"></view>
          <view class="cat-drop-panel">
            <view class="category-grid">
              <view class="category-item" v-for="c in allCategories" :key="c.key" @click="pickCategory(c)">
                <view class="category-icon" :style="{ backgroundColor: c.bg }"><text>{{ c.emoji }}</text></view>
                <text class="category-name">{{ c.name }}</text>
              </view>
            </view>
          </view>
        </view>

        <!-- 商品卡列表 -->
        <view class="dish-list">
          <view class="error-state error-state-inline" v-if="goodsError" @click="retryGoods">
            <text class="error-text">商品加载失败，请检查网络</text>
            <text class="error-retry">点击重试</text>
          </view>
          <template v-else>
            <view class="dish-card" v-for="dish in displayDishes" :key="dish.id" @click="goToDetail(dish)">
              <view class="dish-img" :style="{ backgroundColor: dish.bgColor || '#F5F5F5' }">
                <image v-if="dish.image && !imgFail[dish.id]" class="dish-photo" :src="dish.image" mode="aspectFill" @error="onImgFail(dish.id)" />
                <text v-else class="dish-emoji">{{ dish.emoji }}</text>
              </view>
              <view class="dish-body">
                <view class="dish-title-row">
                  <text v-if="dish.seckill" class="dish-seckill-tag">秒</text>
                  <text class="dish-name">{{ dish.name }}</text>
                </view>
                <text class="dish-tags">{{ (dish.tags || []).join(' | ') }}</text>
                <view class="dish-sold-row">
                  <text class="dish-rush">{{ dish.rushText }}</text>
                  <text class="dish-sold">{{ dish.soldText }}</text>
                </view>
                <view class="dish-promo-row">
                  <block v-if="dish.seckill">
                    <text class="tag-cut">{{ dish.cutText }}</text>
                    <text class="tag-limit">限购{{ dish.limitBuy }}件</text>
                  </block>
                  <text v-else class="dish-rate">好评率{{ dish.goodRate }}</text>
                </view>
                <view class="dish-price-row">
                  <view class="price-left">
                    <block v-if="dish.seckill">
                      <text class="price-seckill-label">秒杀</text>
                      <text class="price-symbol">¥</text>
                      <text class="price-big">{{ fmtPrice(dish.seckillPrice) }}</text>
                    </block>
                    <block v-else>
                      <text class="price-symbol">¥</text>
                      <text class="price-big">{{ fmtPrice(dish.price) }}</text>
                    </block>
                  </view>
                  <view class="dish-action" @click.stop>
                    <view v-if="cartMap[dish.id]" class="stepper">
                      <view class="step-btn step-minus" @click="minusDish(dish)"><text>−</text></view>
                      <text class="step-num">{{ cartMap[dish.id].quantity }}</text>
                      <view class="step-btn step-plus" @click="plusDish(dish)"><text>+</text></view>
                    </view>
                    <view v-else class="add-btn" @click="addDish(dish)">
                      <text>{{ dish.seckill ? '立即抢购' : '加入购物车' }}</text>
                    </view>
                  </view>
                </view>
              </view>
            </view>
            <view v-if="!goodsLoading && displayDishes.length === 0" class="empty-state empty-state-inline">
              <text>暂无商品</text>
            </view>
          </template>
        </view>
      </view>

      <!-- ==================== 我的领取记录（领取后该 0 元单在此展示；未登录引导） ==================== -->
      <view class="claims-block">
        <view class="claims-head">
          <text class="claims-title">我的领取记录</text>
        </view>

        <view v-if="!hasToken" class="claims-login" @click="goLogin">
          <text class="claims-login-icon">👤</text>
          <text class="claims-login-text">登录后查看领取记录</text>
          <text class="claims-login-arrow">›</text>
        </view>

        <template v-else>
          <view v-if="claimsError" class="claims-state" @click="retryClaims">
            <text class="claims-state-text">领取记录加载失败，点击重试</text>
          </view>

          <view v-else-if="claims.length > 0">
            <view class="claim-item" v-for="(c, i) in claims" :key="c.id || i">
              <view class="claim-item-main">
                <text class="claim-item-name">{{ c.dishName || '免费商品' }}</text>
                <text class="claim-item-date">{{ claimDateText(c) }}</text>
              </view>
              <view class="claim-item-right">
                <text class="claim-item-free">¥0.00</text>
                <text class="claim-item-status" :class="'cs-' + claimStatusKey(c)">{{ claimStatusText(c) }}</text>
              </view>
            </view>
            <view class="load-more" v-if="claims.length < claimsTotal">
              <button class="btn-more" :disabled="claimsLoading" @click="loadMoreClaims"><text>{{ claimsLoading ? '加载中…' : '加载更多' }}</text></button>
            </view>
          </view>

          <view v-else-if="claimsLoading" class="claims-state">
            <text class="claims-state-text">加载中…</text>
          </view>
          <view v-else class="claims-state">
            <text class="claims-state-text">暂无领取记录，快去领取今日免费商品吧</text>
          </view>
        </template>
      </view>
    </template>

    <!-- ==================== 平台优惠条（购物车非空时固定于购物车栏上方） ==================== -->
    <view class="platform-bar" v-if="!isCartEmpty && hasCouponText" @click="onPlatformTap">
      <text class="pb-label">平台优惠</text>
      <text class="pb-divider">|</text>
      <text class="pb-coupon">{{ mainCouponText }}</text>
      <text class="pb-arrow">&gt;</text>
    </view>

    <!-- ==================== 底部购物车栏（数量/合计/已减/去支付：写 openCheckout=1 后 reLaunch 首页，与 detail/search 同链路） ==================== -->
    <view class="cart-bar" v-if="!isCartEmpty">
      <!-- 购物车图标区：点击打开页内轻购物车弹层 -->
      <view class="cart-left" @click="openCartPopup">
        <view class="cart-icon-box">
          <text class="cart-icon">🛒</text>
          <text class="cart-badge" v-if="cartCount > 0">{{ cartCount }}</text>
        </view>
        <view class="cart-info">
          <view class="cart-price-line">
            <text class="cart-total-symbol">¥</text>
            <text class="cart-total-price">{{ fmtPrice(cartTotalPrice) }}</text>
          </view>
          <text class="cart-reduced">已减 ¥{{ fmtPrice(cartSaving) }}</text>
        </view>
      </view>
      <button class="btn-pay" @click="goPayFromFree">
        <text class="pay-main">去支付</text>
        <view class="pay-sub-wrap" v-if="mainCouponText"><text class="pay-coupon">{{ mainCouponText }}</text></view>
      </button>
    </view>

    <!-- ==================== 轻购物车弹层（与首页弹层同骨架：服务保障条 + 提货人行 + 标题/管理 + 凑单助手条 + 富卡片行（商品图/名称/划线价/现价/秒标/勾选圈/删除/步进器）+ 管理模式/常规模式双底部条，mask/✕ 关闭） ==================== -->
    <view class="cart-popup-mask" v-if="showCartPopup" @click="closeCartPopup">
      <view class="cart-popup" @click.stop>
        <!-- 服务保障条（字典 service_tags 渲染，对照首页弹层顶部同款） -->
        <view class="service-bar">
          <view class="service-item" v-for="tag in serviceTags" :key="tag">
            <text class="service-check">✓</text><text>{{ tag }}</text>
          </view>
        </view>
        <!-- 提货人行（对照首页弹层 pickup-row：👤 姓名 手机号 ›；免费页不复制修改提货人弹窗，点击仅提示回首页确认订单修改） -->
        <view class="pickup-row" @click="onPickupPersonFree">
          <text class="pr-icon">👤</text>
          <text class="pr-name">{{ pickupPerson.name }}</text>
          <text class="pr-phone">{{ pickupPerson.phone }}</text>
          <text class="pr-arrow">›</text>
        </view>
        <!-- 标题行：右侧「管理/退出管理」切换管理模式（item 勾选圈 + 底部全选/删除条），语义对照首页 toggleEdit -->
        <view class="cart-popup-head">
          <text class="cart-popup-title">购物车</text>
          <text class="cart-popup-count" v-if="cartCount > 0">共{{ cartCount }}件</text>
          <view class="cart-popup-head-right">
            <text v-if="cart.length > 0" class="cart-manage-btn" @click="toggleEditFree">{{ editingFree ? '退出管理' : '管理' }}</text>
            <text class="cart-popup-close" @click="closeCartPopup">✕</text>
          </view>
        </view>
        <!-- 凑单助手条（对照首页弹层 helper-bar 同款文案/配色） -->
        <view class="helper-bar">
          <text class="hb-title">凑单助手</text>
          <text class="hb-divider">|</text>
          <text class="hb-text">已减 {{ fmtPrice(cartSaving) }} 元，下单立享</text>
        </view>
        <scroll-view class="cart-popup-scroll" scroll-y>
          <view v-if="isCartEmpty" class="cart-popup-empty"><text>购物车是空的，去挑点好货吧</text></view>
          <template v-else>
            <view class="cart-popup-item" v-for="item in cart" :key="item.id">
              <!-- 勾选圆圈（仅管理模式展示，点击走 Vuex toggleChecked mutation；阻止冒泡） -->
              <view v-if="editingFree" class="check-circle" :class="{ checked: item.checked }" @click.stop="toggleCheckFree(item)">
                <text v-if="item.checked">✓</text>
              </view>
              <!-- emoji 图：与首页弹层逐字同款（平底 bgColor + emoji，无 image 渲染——dish SVG 本身是渐变底，观感会不一致） -->
              <view class="fci-img" :style="{ backgroundColor: dishOf(item).bgColor || '#F5F5F5' }">
                <text>{{ item.emoji || dishOf(item).emoji || '🛒' }}</text>
              </view>
              <view class="fci-body">
                <view class="fci-name-row">
                  <text v-if="isSeckillItem(item)" class="fci-seckill-tag">秒</text>
                  <text class="fci-name">{{ item.name }}</text>
                </view>
                <view class="fci-price-row">
                  <text class="fci-original">¥{{ fmtPrice(item.originalPrice) }}</text>
                  <view class="fci-price-line">
                    <text class="fci-symbol">¥</text>
                    <text class="fci-price">{{ fmtPrice(item.price) }}</text>
                  </view>
                  <!-- 折扣标签：仅秒杀件显示，按 unitSave/originalPrice 换算，算不出省略（首页弹层同款） -->
                  <text class="fci-discount" v-if="isSeckillItem(item) && discountRate(item)">{{ discountRate(item) }}</text>
                </view>
              </view>
              <!-- 右侧：管理模式单件「删除」 / 常规模式步进器（走 Vuex increaseById/decreaseById，均阻止冒泡） -->
              <view class="fci-action" @click.stop>
                <text v-if="editingFree" class="fci-delete" @click="removeItemFree(item.id)">删除</text>
                <view v-else class="stepper">
                  <view class="step-btn step-minus" @click="decreaseById(item.id)"><text>−</text></view>
                  <text class="step-num">{{ item.quantity }}</text>
                  <view class="step-btn step-plus" @click="increaseById(item.id)"><text>+</text></view>
                </view>
              </view>
            </view>
          </template>
        </scroll-view>
        <!-- 管理模式底部操作条（对照首页弹层 309-315：左全选勾选圈 + 已选计数 + 右红「删除」批量删，二次确认） -->
        <view class="cart-total-bar" v-if="editingFree">
          <view class="check-all" @click="toggleAllFree">
            <view class="check-circle" :class="{ checked: allChecked }"><text v-if="allChecked">✓</text></view>
            <text class="check-all-text">全选</text>
          </view>
          <text class="manage-count">已选 {{ checkedCount }} 件</text>
          <button class="btn-manage-delete" @click="onDeleteCheckedFree"><text>删除</text></button>
        </view>
        <!-- 常规模式底部合计条（对照首页弹层 318-327：已选件数 + ¥合计 + 已减 + 立即支付红按钮，空车置灰；
             点击写 openCheckout=1 后 reLaunch 首页，首页 onShow 消费标志打开确认订单弹层完成支付） -->
        <view class="cart-total-bar" v-else>
          <text class="check-all-text">已选 {{ checkedCount }} 件</text>
          <view class="ctb-price">
            <view class="ctb-price-line">
              <text class="fci-symbol">¥</text>
              <text class="ctb-price-num">{{ fmtPrice(cartTotalPrice) }}</text>
            </view>
            <text class="ctb-saving">已减 ¥{{ fmtPrice(cartSaving) }}</text>
          </view>
          <button class="btn-pay-popup" :class="{ 'btn-pay-disabled': isCartEmpty }" @click="payFromCartFree"><text>立即支付</text></button>
        </view>
      </view>
    </view>

    <!-- ==================== 免费领商品引导弹窗（共享组件 free-gift-guide：onShow 每次进入判定弹出，活动 online 且今日未领取才弹；✕/遮罩只关本次，无持久化门） ==================== -->
    <free-gift-guide
      :visible="guideVisible"
      :dish-name="guideDish.name"
      :dish-image="guideDish.image"
      :dish-emoji="guideDish.emoji"
      :dish-bg="guideDish.bgColor"
      :threshold="threshold"
      :remaining="remainingToThreshold"
      :reached="reached"
      @close="closeGuide"
      @go-order="guideGoOrder"
    />

    <!-- ==================== 更换免费商品弹窗（两列网格 = 候选池 pool，已选卡置灰） ==================== -->
    <view class="popup-mask" v-if="showChange" @click="closeChange">
      <view class="change-popup" @click.stop>
        <view class="change-head">
          <view class="change-titles">
            <text class="change-title">请选择你需要更换的免费商品</text>
            <text class="change-sub">更换商品不影响进度</text>
          </view>
          <text class="change-close" @click="closeChange">✕</text>
        </view>
        <scroll-view class="change-scroll" scroll-y>
          <view class="change-grid">
            <view class="change-card" v-for="p in poolList" :key="p.dishId">
              <view class="change-img" :style="{ backgroundColor: p.bgColor || '#F5F5F5' }">
                <image v-if="p.image && !changeImgFail[p.dishId]" class="change-photo" :src="p.image" mode="aspectFill" @error="onChangeImgFail(p.dishId)" />
                <text v-else class="change-emoji">{{ p.emoji }}</text>
              </view>
              <text class="change-unit" v-if="p.unit">{{ p.unit }}</text>
              <text class="change-name">{{ p.name }}</text>
              <view class="change-btn" :class="{ 'change-btn-current': isSelected(p) }" @click="pickChange(p)">
                <text>{{ isSelected(p) ? '已选择' : '更换' }}</text>
              </view>
            </view>
            <view v-if="poolList.length === 0" class="change-empty"><text>暂无可更换的免费商品</text></view>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<script>
// 选购型免费领页（多多买菜风格）：
// 金色舞台大卡（今天带走角标/更换按钮）→ 23:00 截单倒计时 → 门槛进度大按钮（差额纯前端按购物车实时算）→
// 分类 tab 商品选购区（加购走 Vuex 共享购物车，去支付写 openCheckout=1 后 reLaunch 首页与 detail/search 同链路）。
// 数据：GET /api/free/current（pool 候选池/threshold/todayPaid/claimed/claimedOrder）+
// GET /api/dishes 全量前端按分类过滤；POST /api/free/claim body { dishId, items }（FreeClaimDTO 契约：
// items=当前购物车 [{dishId,quantity}]，后端按 dish 表价格重算合计做门槛校验，绝不信任前端金额；
// api 层 freeClaim 签名仍为 dishId-only 且本次仅限改本页，领取请求由页内 claimRequest 直发，语义复刻 request 封装）。
// 选中免费商品记 localStorage 'freeSelectedDish' 刷新保持；引导弹窗（共享组件 free-gift-guide）
// 每次进入（onShow）判定弹出：活动 online 且今日未领取就弹，无每日持久化门，已领取不弹；
// 当日已领取记 localStorage 'freeClaimDate' 按钮置灰；底部购物车图标点开页内轻购物车弹层。
import { mapState, mapGetters } from 'vuex'
import { freeCurrent, freeMyClaims, decorateDish } from '@/api/index.js'
import { getDictGroup, parseCoupon } from '@/api/dict.js'
import { BASE_URL } from '@/api/config.js'
import { getCutoffInfo, formatCountdown } from '@/utils/time.js'

// 关联订单状态 -> 中文徽标（口径同订单页 STATUS_MAP）
const ORDER_STATUS_TEXT = { pending_pickup: '待自提', completed: '已完成', cancelled: '已取消' }

// 免费页分类 tab（推荐/上新/生鲜/蔬菜/酒水/速食 + 「≡ 全部」）：过滤逻辑与首页同款但独立实现于本页
// all=推荐（全量+秒杀优先）；new=上新（最新上架，dishes 已按 id 倒序取前 10）；其余按字典 category code 过滤
const FREE_TABS = [
  { key: 'all', name: '推荐' },
  { key: 'new', name: '上新' },
  { key: 'fresh', name: '生鲜' },
  { key: 'vegetable', name: '蔬菜' },
  { key: 'wine', name: '酒水' },
  { key: 'fastfood', name: '速食' }
]

// 「全部」下拉网格视觉映射（字典只给名称与 code，emoji/圆底色按 code 本地补齐，与首页同款）
const CATEGORY_STYLE = {
  meat: { emoji: '🥩', bg: '#FFECE8' },
  vegetable: { emoji: '🥦', bg: '#EAF7EE' },
  fastfood: { emoji: '🍜', bg: '#FFF3E0' },
  wine: { emoji: '🍺', bg: '#E8F3FF' },
  fruit: { emoji: '🍎', bg: '#FFF1F0' },
  fresh: { emoji: '🐟', bg: '#E8F7FF' },
  frozen: { emoji: '🧊', bg: '#E8F3FF' },
  snack: { emoji: '🍪', bg: '#FFF3E0' },
  dairy: { emoji: '🥛', bg: '#FDE8F0' },
  grain: { emoji: '🍚', bg: '#FFFBE6' },
  general: { emoji: '🧻', bg: '#F4F4F5' }
}

// 选中免费商品 / 当日已领取标记持久化键（localStorage，契约指定；引导弹窗无持久化门，每次进入判定）
const SELECTED_KEY = 'freeSelectedDish'
const CLAIM_DATE_KEY = 'freeClaimDate'

export default {
  data() {
    return {
      loaded: false,         // 首次活动请求是否已返回（避免闪现空态）
      loadError: false,      // 活动加载失败错误态（≠暂无活动，给重试入口）
      activity: null,        // GET /api/free/current data（M8：含 pool/threshold/todayPaid/claimed/claimedOrder）
      claiming: false,       // 领取中防重
      claimedOrder: null,    // 本次会话领取成功的 0 元订单（刷新后以 current.claimedOrder 为准）
      selectedDishId: '',    // 当前选中的免费商品 dishId（onLoad 从 storage 恢复，更换时写回）
      heroImgFail: false,    // 大卡图加载失败回退 emoji 色块
      changeImgFail: {},     // 更换弹窗图加载失败记录表（dishId -> true）
      guideVisible: false,   // 免费领引导弹窗显隐（共享组件 free-gift-guide，onShow 判定弹出）
      showChange: false,     // 更换免费商品弹窗
      showCartPopup: false,  // 轻购物车弹层（底部栏购物车图标点开）
      editingFree: false,    // 弹层管理模式（页面级状态，独立于首页 editingCart：item 勾选圈 + 批量删除）
      // 轻购物车弹层服务保障条（字典 service_tags 渲染，对照首页弹层同款；默认值兜底）
      serviceTags: ['坏了包退', '晚到必赔', '极速退款'],
      showCatAll: false,     // 全部分类下拉
      countdownText: '00:00:00', // 23:00 截单倒计时（每秒 tick）
      countdownTimer: null,
      // 商品选购区
      dishes: [],            // 全量商品（GET /api/dishes 一次拉取，前端按 tab 过滤）
      goodsLoading: false,
      goodsError: false,
      imgFail: {},           // 商品卡图加载失败记录表
      currentCategory: 'all',
      allCategories: [],     // 全部分类下拉（dish_category 字典）
      coupons: [],           // 券字典（平台优惠条/去支付副文案）
      // 我的领取记录
      claims: [],
      claimsTotal: 0,
      claimsPageNum: 1,
      claimsLoading: false,
      claimsError: false,
      // 播报条文案（登录态今日已有 free 领取记录才显示）
      broadcastText: ''
    }
  },
  computed: {
    ...mapState(['user', 'cart', 'pickupPerson']),
    ...mapGetters(['cartCount', 'cartTotalPrice', 'isCartEmpty', 'cartSaving', 'checkedCount']),
    // 管理模式是否全选（口径同首页 allChecked：购物车非空且全部已勾选）
    allChecked() { return this.cart.length > 0 && this.cart.every(item => item.checked) },
    // 已勾选项列表（管理模式批量删除遍历用，口径同首页 checkedItems）
    checkedItems() { return this.cart.filter(item => item.checked) },
    hasToken() { return !!(this.user && this.user.token) },
    freeTabs() { return FREE_TABS },
    // 活动开放状态：优先后端 activityStatus（'online'/'offline'/'empty'），兼容旧口径 status；
    // 均缺省时按候选池/dish 有值视为开放（防误伤）
    activityStatus() {
      const a = this.activity || {}
      if (typeof a.activityStatus === 'string' && a.activityStatus) return a.activityStatus
      if (typeof a.status === 'string' && a.status) return a.status
      return (this.poolList.length || (a.dish && typeof a.dish === 'object')) ? 'online' : 'empty'
    },
    activityEmpty() { return this.activityStatus === 'empty' },
    offline() { return this.activityStatus !== 'online' && this.activityStatus !== 'empty' },
    // 免费商品候选池（M8）：pool 归一化；后端未下发/为空时回退 dish 单商品兜底，保证大卡可渲染
    poolList() {
      const a = this.activity || {}
      const norm = (d) => {
        if (!d || typeof d !== 'object') return null
        const id = d.dishId != null ? d.dishId : d.id
        if (id == null) return null
        return {
          dishId: id,
          name: d.name || '免费商品',
          image: d.image || '',
          unit: d.unit || '',
          price: Number(d.price) || 0,
          emoji: d.emoji || '🎁',
          bgColor: d.bgColor || '#FFF3E0'
        }
      }
      const list = (Array.isArray(a.pool) ? a.pool : []).map(norm).filter(Boolean)
      if (list.length) return list
      const single = norm(a.dish)
      return single ? [single] : []
    },
    // 当前选中的免费商品：本地选择优先（storage 恢复/更换写入），无效时回退候选池第一个
    selectedDish() {
      if (!this.poolList.length) return null
      const found = this.poolList.find(p => String(p.dishId) === String(this.selectedDishId))
      return found || this.poolList[0]
    },
    // 引导弹窗展示数据（共享组件 props 归一化）：selectedDish 为空时给占位默认，组件内有 emoji/bg 兜底
    guideDish() {
      const d = this.selectedDish || {}
      return { name: d.name || '免费商品', image: d.image || '', emoji: d.emoji || '🎁', bgColor: d.bgColor || '#FFF3E0' }
    },
    // 满额门槛：后端 threshold 优先（DECIMAL(10,2)），缺省按默认 50 兜底（m8 迁移默认值）
    threshold() {
      const t = Number(this.activity && this.activity.threshold)
      return Number.isFinite(t) && t > 0 ? t : 50
    },
    // 当前选中免费商品 id（字符串化防 number/string 类型差）：免费项识别的唯一判定口径（最可靠）
    selectedFreeDishId() {
      const d = this.selectedDish
      return (d && d.dishId != null) ? String(d.dishId) : null
    },
    // 购物车非免费项实付合计 Σ(price×quantity)：差额纯前端按购物车实时算，加购/减购/删除即时联动；
    // 免费项（id === 当前选中免费 dishId）不计入——0 元随赠单与购物车分离，其原价不参与门槛
    cartNoFreeTotal() {
      return this.cart.reduce((total, item) => {
        if (this.selectedFreeDishId != null && String(item.id) === this.selectedFreeDishId) return total
        return total + (Number(item.price) || 0) * (Number(item.quantity) || 0)
      }, 0)
    },
    // 距门槛差额（购物车口径）：max(0, threshold - cartNoFreeTotal)，两位小数钳浮点尾差
    cartRemaining() {
      return Math.max(0, +(this.threshold - this.cartNoFreeTotal).toFixed(2))
    },
    // 购物车口径是否已达门槛（cartNoFreeTotal >= threshold，可点击直接领取）
    cartReached() { return this.cartRemaining <= 0 },
    // 当日实付累计（登录态后端下发；契约字段保留解析，差额已改购物车口径，本值不再用于按钮/引导）
    todayPaid() {
      const v = Number(this.activity && this.activity.todayPaid)
      return Number.isFinite(v) && v > 0 ? v : 0
    },
    // 距门槛差额（后端口径，保留兼容解析；差额现以 cartRemaining 购物车实时口径为准）
    remainingToThreshold() {
      const given = Number(this.activity && this.activity.remainingToThreshold)
      if (Number.isFinite(given) && given >= 0) return given
      return Math.max(0, +(this.threshold - this.todayPaid).toFixed(2))
    },
    // 是否已达门槛（后端口径，保留兼容；按钮现以 cartReached 为准）
    reached() { return this.remainingToThreshold <= 0 },
    // 门槛进度百分比（大按钮内进度填充，购物车口径），钳 0~100
    progressPercent() {
      if (this.threshold <= 0) return 100
      return Math.min(100, Math.max(0, Math.round((this.cartNoFreeTotal / this.threshold) * 100)))
    },
    // 当前登录用户今日是否已领：current.claimed 优先，兼容旧 claimedToday；
    // 本次会话刚领取成功 / localStorage 'freeClaimDate' 为今日 / 领取记录里已有今日一条时兜底 true（覆盖后端缺字段场景）
    claimedFlag() {
      const a = this.activity || {}
      if (a.claimed === true || Number(a.claimed) === 1) return true
      const legacy = a.claimedToday
      if (legacy === true || Number(legacy) === 1) return true
      if (this.claimedOrder) return true
      if (this.claimStoredToday()) return true
      return this.claimsHasToday()
    },
    // 每日限量计数解析（quota/remaining 同旧口径，remaining 由后端按限量与现库存取小下发）
    parsedActivity() {
      const a = this.activity || {}
      const quota = Math.max(0, Math.floor(Number(a.dailyQuota != null ? a.dailyQuota : a.quota) || 0))
      const rawRemaining = Number(a.remaining)
      const remainingGiven = Number.isFinite(rawRemaining) && rawRemaining >= 0 ? Math.floor(rawRemaining) : null
      let claimed = Number(a.claimedCount)
      if (!Number.isFinite(claimed) || claimed < 0) {
        claimed = remainingGiven != null ? Math.max(0, quota - remainingGiven) : 0
      }
      const remaining = remainingGiven != null ? remainingGiven : Math.max(0, quota - claimed)
      return { quota, claimed, remaining }
    },
    // 进度大按钮状态机：已领 > 抢完 > 领取中 > 购物车未达标（去凑单） > 购物车达标（点击免费领取）；
    // 差额文案随购物车变化实时联动（threshold - cartNoFreeTotal，两位小数）
    claimButton() {
      if (this.claimedFlag) return { text: '今日已领，明天再来', disabled: true, cls: 'btn-claim-disabled' }
      if (this.parsedActivity.remaining <= 0) return { text: '今日已抢完', disabled: true, cls: 'btn-claim-disabled' }
      if (this.claiming) return { text: '领取中…', disabled: true, cls: 'btn-claim-disabled' }
      if (!this.cartReached) return { text: `再挑选${this.fmtPrice(this.cartRemaining)}元，今天带走免费商品`, disabled: false, cls: '' }
      return { text: `已满${this.fmtThreshold(this.threshold)}元，点击免费领取`, disabled: false, cls: 'btn-claim-ready' }
    },
    // 选购区列表：推荐=全量+秒杀优先；上新=最新上架前 10；其余按 category code 前端过滤
    displayDishes() {
      const list = this.dishes
      if (this.currentCategory === 'all') {
        const sec = list.filter(d => d.seckill)
        const rest = list.filter(d => !d.seckill)
        return sec.concat(rest)
      }
      if (this.currentCategory === 'new') return list.slice(0, 10)
      return list.filter(d => d.category === this.currentCategory)
    },
    // 购物车 id -> 项 的映射，用于列表判断"已加购"与数量
    cartMap() {
      const map = {}
      this.cart.forEach(item => { map[item.id] = item })
      return map
    },
    // 全量商品 id -> 商品 的映射（步进器限购回查）
    dishById() {
      const map = {}
      this.dishes.forEach(d => { map[d.id] = d })
      return map
    },
    // 平台优惠条/去支付副文案：优先第一条可用券，按 threshold/amount 拼"满x减y"（首页/搜索页同口径）
    mainCouponText() {
      const list = this.coupons || []
      if (!list.length) return ''
      const c = list.find(x => !x.locked) || list[0]
      if (c.threshold && c.amount) return `满${this.fmtThreshold(c.threshold)}减${this.fmtThreshold(c.amount)}`
      return c.main || ''
    },
    hasCouponText() { return !!this.mainCouponText }
  },
  watch: {
    // 更换选中商品后大卡图重置（不同商品图不同，失败标记不可沿用）
    selectedDishId() { this.heroImgFail = false }
  },
  onLoad() {
    // 选中免费商品恢复（localStorage 'freeSelectedDish'，刷新保持；无效值由 syncSelectedDish 纠正）
    try {
      const saved = uni.getStorageSync(SELECTED_KEY)
      if (saved != null && saved !== '') this.selectedDishId = saved
    } catch (e) { /* 存储异常按未保存处理 */ }
    this.loadGoods()
    this.loadDicts()
    this.startCountdown()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，
    // 手动接管页面滚动（照搜索页同款方案；弹窗打开时不拦截，弹层内部自滚）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  onUnload() {
    // H5 滚轮兜底清理（_pageWheelHandler 仅 H5 onReady 注册，其它平台为空天然 no-op）
    if (this._pageWheelHandler) {
      // #ifdef H5
      document.removeEventListener('wheel', this._pageWheelHandler)
      // #endif
      this._pageWheelHandler = null
    }
    // 倒计时定时器清理（各端通用）
    if (this.countdownTimer) {
      clearInterval(this.countdownTimer)
      this.countdownTimer = null
    }
  },
  onShow() {
    // 引导弹窗先收起：本次进入是否弹出待 current 返回后判定（避免上次会话残留数据闪空）
    this.guideVisible = false
    // 每次进入刷新（从登录页返回后登录态变化同步数据；商品列表失败时顺带重试）
    this.loadCurrent()
    if (this.hasToken) {
      this.claimsPageNum = 1
      this.loadMyClaims()
    } else {
      this.claims = []
      this.claimsTotal = 0
      this.claimsError = false
      this.broadcastText = ''
    }
    if (this.goodsError) this.loadGoods()
  },
  methods: {
    // ==================== 通用 ====================
    // 返回：有页面栈走 navigateBack；深链直达无栈时 reLaunch 回首页（mine/search 页同口径）
    goBack() {
      if (getCurrentPages().length > 1) {
        uni.navigateBack()
      } else {
        uni.reLaunch({ url: '/pages/index/index' })
      }
    },
    // 登录入口：跳登录页并携带 redirect，登录成功后解码回跳本页（全站同款 FRONT_CONTRACT）
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/free/free') }) },
    // 「明细」轻实现：跳订单页（免费单为 clientRequestId 以 free- 开头的 0 元单）
    onDetail() {
      uni.navigateTo({ url: '/pages/order/order' })
      this.showToast('已为您筛选免费领取订单')
    },
    // 「规则」弹窗（门槛口径/截单口径/限量口径，金额随 current.threshold 动态拼入；门槛按购物车重算口径）
    onRules() {
      uni.showModal({
        title: '活动规则',
        content: `每人每日限领 1 份\n购物车满 ${this.fmtThreshold(this.threshold)} 元可获得领取资格（免费商品不计入门槛金额）\n当日 23:00 截单，免费商品凭 0 元订单到自提点随单自提\n数量有限先到先得`,
        showCancel: false,
        confirmText: '我知道了',
        confirmColor: '#E02020'
      })
    },
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 2000 }) },
    // 金额统一两位小数展示（脏数据兜底 0.00，全站同口径）
    fmtPrice(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n.toFixed(2) : '0.00'
    },
    // 门槛/券面额展示（去尾零：50.00→50 / 49.50→49.5，脏数据按 0）
    fmtThreshold(v) {
      const n = Number(v)
      return Number.isFinite(n) ? String(+(n.toFixed(2))) : '0'
    },
    // 图片加载失败：大卡/商品卡/更换卡分别标记回退 emoji 色块
    onHeroImgFail() { this.heroImgFail = true },
    onImgFail(id) { this.imgFail = { ...this.imgFail, [id]: true } },
    onChangeImgFail(id) { this.changeImgFail = { ...this.changeImgFail, [id]: true } },
    // 本地今日 yyyy-MM-dd（claimDate 展示/今日已领兜底判定用）
    localToday() {
      const d = new Date()
      const m = String(d.getMonth() + 1).padStart(2, '0')
      const day = String(d.getDate()).padStart(2, '0')
      return `${d.getFullYear()}-${m}-${day}`
    },
    // 今日已领兜底判定（localStorage 日期口径）：'freeClaimDate' 等于本地今日即视为已领（当日按钮置灰，次日自动恢复）
    claimStoredToday() {
      try { return uni.getStorageSync(CLAIM_DATE_KEY) === this.localToday() } catch (e) { return false }
    },
    // ==================== 数据加载 ====================
    // 今日免费活动（匿名可调，M8 data 扩展）：失败置错误态可重试；成功后同步选中商品/播报/引导弹窗
    async loadCurrent() {
      try {
        const res = await freeCurrent()
        const data = (res && res.data) || null
        this.activity = data
        this.loadError = false
        this.loaded = true
        this.syncSelectedDish()
        this.refreshBroadcast()
        this.maybeShowGuide()
      } catch (e) {
        // 401（token 失效）：request 层已清登录态，页面响应式切游客视图；current 匿名可读统一按加载失败处理
        this.loadError = true
        this.loaded = true
      }
    },
    // 选中商品同步：本地选择不在候选池（首次进入/池更新/存储脏数据）时回退候选池第一个
    syncSelectedDish() {
      if (!this.poolList.length) return
      const found = this.poolList.find(p => String(p.dishId) === String(this.selectedDishId))
      if (!found) this.selectedDishId = this.poolList[0].dishId
    },
    // 页面局部请求（与首页/搜索页同款）：GET /api/dishes 全量拉取，decorateDish 增强后前端按 tab 过滤
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
    async loadGoods() {
      this.goodsLoading = true
      this.goodsError = false
      try {
        const all = await this.dishRequest({})
        all.sort((a, b) => b.id - a.id) // 与首页列表同口径（id 倒序，上新 tab 直接取前 10）
        this.dishes = all
      } catch (e) {
        this.dishes = []
        this.goodsError = true
      } finally {
        this.goodsLoading = false
      }
    },
    retryGoods() {
      if (this.goodsError) this.loadGoods()
    },
    // 字典：全部分类下拉 + 券文案 + 服务保障标签；getDictGroup 失败内部回退内置默认，此处仅空数组兜底
    async loadDicts() {
      const [cats, cps, tags] = await Promise.all([
        getDictGroup('dish_category').catch(() => []),
        getDictGroup('coupon_template').catch(() => []),
        getDictGroup('service_tags').catch(() => [])
      ])
      this.allCategories = (cats || []).map(d => {
        const key = String((d && d.definition) || '').replace(/^code:/, '').trim()
        const fb = CATEGORY_STYLE[key] || {}
        return { key, name: (d && d.word) || key, emoji: fb.emoji || '🏷️', bg: fb.bg || '#F4F4F5' }
      }).filter(c => c.key)
      this.coupons = (cps || []).map(d => {
        const c = parseCoupon(d.definition) || {}
        return { main: d.word, threshold: c.threshold, amount: c.amount, state: c.state || '', locked: c.state !== 'available' }
      })
      // 服务保障标签（轻购物车弹层服务条用，对照首页弹层同款口径）
      this.serviceTags = (tags || []).map(d => d.word)
    },
    // 我的领取记录（登录，分页）：pageNum=1 替换式加载，翻页追加；401 静默切游客视图
    async loadMyClaims() {
      if (!this.hasToken) { this.claims = []; this.claimsTotal = 0; return }
      this.claimsLoading = true
      try {
        const res = await freeMyClaims(this.claimsPageNum, 10)
        const data = (res && res.data) || {}
        const list = Array.isArray(data.list) ? data.list : (Array.isArray(data) ? data : [])
        this.claims = this.claimsPageNum === 1 ? list : this.claims.concat(list)
        const total = Number(data.total)
        this.claimsTotal = Number.isFinite(total) && total >= 0 ? total : (this.claimsPageNum === 1 ? list.length : this.claimsTotal)
        this.claimsError = false
        this.refreshBroadcast()
      } catch (e) {
        if (!e || e.code !== 401) this.claimsError = true
      } finally {
        this.claimsLoading = false
      }
    },
    loadMoreClaims() {
      if (this.claimsLoading || this.claims.length >= this.claimsTotal) return
      this.claimsPageNum += 1
      this.loadMyClaims()
    },
    retryClaims() {
      this.claimsPageNum = 1
      this.loadMyClaims()
    },
    // 整页重试（活动加载失败态按钮）
    retryAll() {
      this.loadCurrent()
      if (this.hasToken) this.retryClaims()
    },
    // ==================== 倒计时（当日 23:00 截单，与秒杀截单口径同步，每秒 tick） ====================
    // 时间计算统一走 utils/time.js getCutoffInfo（countdownTarget/diffMs），formatCountdown 出 HH:mm:ss；
    // 跨过截单后目标自动切明天 23:00，隔日活动/进度属新一天数据，重拉一次 current 保持口径同步
    startCountdown() {
      const tick = () => {
        const info = getCutoffInfo()
        const cd = formatCountdown(info.diffMs)
        this.countdownText = cd.text
        if (info.isAfterCutoff && !this._cutoffReloaded) {
          this._cutoffReloaded = true
          this.loadCurrent()
        }
        if (!info.isAfterCutoff) this._cutoffReloaded = false
      }
      tick()
      this.countdownTimer = setInterval(tick, 1000)
    },
    // ==================== 分类 tab / 全部下拉 ====================
    switchCategory(cat) { this.currentCategory = cat },
    openCatAll() { this.showCatAll = true },
    closeCatAll() { this.showCatAll = false },
    pickCategory(c) {
      this.switchCategory(c.key)
      this.closeCatAll()
    },
    // ==================== 滚动定位 ====================
    // 滚到商品选购区（未达标大按钮/引导弹窗「去下单」共用目标；购物车图标已改开轻购物车弹层）
    scrollToGoods() {
      // #ifdef H5
      // index.html 锁 html,body overflow:hidden 后 documentElement 不可滚，uni.pageScrollTo（滚 window）静默无效；
      // H5 页面级滚动容器是 document.body（onPageWheel 同口径），scrollIntoView 可编程滚动 body，平滑定位到选购区
      const el = document.querySelector('.goods-section')
      if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
      // #endif
      // #ifndef H5
      // 小程序端 pageScrollTo 正常：rect.top（视口相对）+ 当前滚动量 = 内容坐标目标，留 16px 呼吸
      const q = uni.createSelectorQuery().in(this)
      q.select('.goods-section').boundingClientRect()
      q.selectViewport().scrollOffset()
      q.exec((res) => {
        if (!res || !res[0]) return
        const rect = res[0]
        const cur = (res[1] && res[1].scrollTop) || 0
        const target = Math.max(0, cur + rect.top - 16)
        uni.pageScrollTo({ scrollTop: target, duration: 300 })
      })
      // #endif
    },
    // ==================== 加购/步进器（与首页/搜索页同款交互，走 Vuex 共享购物车） ====================
    limitOf(id) {
      const d = this.dishById[id]
      return d && d.limitBuy ? d.limitBuy : 0
    },
    addDish(dish) { this.$store.commit('addToCart', dish) },
    plusDish(dish) {
      if (!this.cartMap[dish.id]) { this.addDish(dish); return }
      this.increaseById(dish.id)
    },
    minusDish(dish) { this.decreaseById(dish.id) },
    increaseById(id) {
      const item = this.cartMap[id]
      if (!item) return
      const limit = this.limitOf(id)
      const next = item.quantity + 1
      if (limit && item.quantity >= limit) {
        this.showToast(`限购${limit}件`)
        return
      }
      this.$store.commit('updateQuantity', { dishId: id, quantity: next })
      if (limit && next === limit) this.showToast(`限购${limit}件`)
    },
    decreaseById(id) {
      const item = this.cartMap[id]
      if (!item) return
      if (item.quantity <= 1) {
        this.$store.commit('removeFromCart', id)
      } else {
        this.$store.commit('updateQuantity', { dishId: id, quantity: item.quantity - 1 })
      }
    },
    goToDetail(dish) { uni.navigateTo({ url: `/pages/detail/detail?id=${dish.id}` }) },
    // ==================== 底部购物车栏 ====================
    onPlatformTap() { this.showToast(`${this.mainCouponText}，已在购物车生效`) },
    // 去支付：搜索页同链路——写 openCheckout=1 后 reLaunch 首页，首页 onShow 打开确认订单（用后即清）
    goPayFromFree() {
      // 游客下单拦截：加购不拦，结算未登录先引导去独立登录页（带 redirect 回本页续走）
      if (!this.user.token) {
        uni.showModal({
          title: '登录提示',
          content: '登录后即可下单，是否前往登录？',
          confirmText: '去登录',
          cancelText: '再逛逛',
          success: (r) => {
            if (r.confirm) this.goLogin()
          }
        })
        return
      }
      try { uni.setStorageSync('openCheckout', '1') } catch (e) {
        // 存储异常静默：标志缺失时首页不弹，购物车数据仍全局共享不丢失
      }
      uni.reLaunch({ url: '/pages/index/index' })
    },
    // ==================== 轻购物车弹层（与首页弹层同骨架：管理模式勾选/删除 + 常规模式合计/立即支付） ====================
    openCartPopup() { this.showCartPopup = true },
    closeCartPopup() {
      this.showCartPopup = false
      this.editingFree = false // 关弹层复位管理模式（对照首页 closeCartPopup，下次打开回到常规展示）
    },
    // 提货人行点击：免费页不复制首页修改提货人弹窗（修改入口统一收在首页确认订单弹层），最小实现仅 toast 提示
    onPickupPersonFree() { this.showToast('到首页确认订单可修改提货人') },
    // 管理/退出管理模式（语义对照首页 toggleEdit）：退出时复位勾选——常规展示无 item 勾选圈（加购默认勾选），
    // 若管理模式内取消勾选未删除就退出，会残留无法修改的未勾选项，导致底部合计/已选件数与列表不符
    toggleEditFree() {
      this.editingFree = !this.editingFree
      if (!this.editingFree) {
        this.cart.forEach(item => { if (!item.checked) this.$store.commit('toggleChecked', item.id) })
      }
    },
    // 单件勾选圈点击：走 Vuex toggleChecked mutation（口径同首页）
    toggleCheckFree(item) { this.$store.commit('toggleChecked', item.id) },
    // 全选/取消全选：走 Vuex toggleAllChecked mutation（「非全选→全勾选、已全选→全取消」）
    toggleAllFree() { this.$store.commit('toggleAllChecked') },
    // 管理模式单件删除：删光时一并退出管理模式，避免空列表残留底部管理操作条（对照首页 removeItem）
    removeItemFree(id) {
      this.$store.commit('removeFromCart', id)
      if (this.cart.length === 0) this.editingFree = false
    },
    // 管理模式批量删除（对照首页 onDeleteChecked）：未勾选轻提示拦截；showModal 二次确认后循环 removeFromCart
    // （store 无批量 mutation）并退出管理模式，勾选状态随 item 移除自然清空
    onDeleteCheckedFree() {
      if (this.checkedCount === 0) { this.showToast('请先勾选要删除的商品'); return }
      uni.showModal({
        title: '删除商品',
        content: `是否删除选中的 ${this.checkedCount} 件商品？`,
        confirmText: '删除',
        confirmColor: '#E02020',
        success: (res) => {
          if (!res.confirm) return
          this.checkedItems.forEach(item => this.$store.commit('removeFromCart', item.id))
          this.editingFree = false
        }
      })
    },
    // 合计栏「立即支付」（对照首页 payFromCart 的空车提示；游客登录拦截复用 goPayFromFree）：
    // 与原去结算同链路——先关弹层，再由 goPayFromFree 写 openCheckout=1 后 reLaunch 首页完成支付
    payFromCartFree() {
      if (this.isCartEmpty) { this.showToast('请先添加商品'); return }
      this.closeCartPopup()
      this.goPayFromFree()
    },
    // 弹层商品行回查原始商品（image/bgColor/seckill 在购物车项上没有，需回查；首页 dishOf 同款）
    dishOf(item) { return this.dishById[item.id] || {} },
    // 弹层商品行 [秒] 标判定：优先回查商品，回查不到按 unitSave>1 兜底（首页 isSeckillItem 同款）
    isSeckillItem(item) {
      const d = this.dishOf(item)
      if (d && typeof d.seckill === 'boolean') return d.seckill
      return item.unitSave > 1
    },
    // 折扣标签：按 unitSave/originalPrice 换算（如 7.6折），算不出返回空串省略（首页 discountRate 同款）
    discountRate(item) {
      const orig = Number(item.originalPrice)
      const save = Number(item.unitSave)
      if (!orig || orig <= 0 || !isFinite(save) || save < 0) return ''
      const paid = orig - save
      if (paid <= 0) return ''
      const zhe = (paid / orig) * 10
      if (zhe <= 0 || zhe >= 10) return ''
      return zhe.toFixed(1).replace(/\.0$/, '') + '折'
    },
    // ==================== 更换免费商品弹窗 ====================
    openChange() {
      if (!this.poolList.length) { this.showToast('暂无可更换的免费商品'); return }
      this.showChange = true
    },
    closeChange() { this.showChange = false },
    isSelected(p) { return String(p.dishId) === String(this.selectedDishId) },
    // 点某卡「更换」：选中该商品 + localStorage 'freeSelectedDish' 持久化 + 关弹窗，大卡响应式刷新
    pickChange(p) {
      if (this.isSelected(p)) return
      this.selectedDishId = p.dishId
      try { uni.setStorageSync(SELECTED_KEY, p.dishId) } catch (e) { /* 存储异常静默，本次会话内存态仍生效 */ }
      this.closeChange()
    },
    // ==================== 免费领商品引导弹窗（共享组件 free-gift-guide） ====================
    // 每次进入（onShow → loadCurrent 返回后判定）都弹，已删除原每日一次的 localStorage 日期门：
    // 活动 online 且今日未领取才弹；今日已领取（claimed，登录态口径取 GET /api/free/current 的 claimed）
    // 不弹，匿名（无 token，后端 claimed=false）视为未领取照常弹；✕/遮罩关闭只收本次，不影响下次触发。
    // loadCurrent 也会在领取成功/跨截单重拉后调用，此时 claimed 已为 true，判定天然收敛不再弹。
    maybeShowGuide() {
      if (this.activityStatus !== 'online') return
      const a = this.activity || {}
      const claimed = a.claimed === true || Number(a.claimed) === 1 ||
        a.claimedToday === true || Number(a.claimedToday) === 1
      if (claimed) return
      if (!this.selectedDish) return
      this.guideVisible = true
    },
    closeGuide() { this.guideVisible = false },
    // 引导弹窗「去下单」：关弹窗并滚到商品选购区（语义与参考图一致=引导去凑单下单）
    guideGoOrder() {
      this.guideVisible = false
      this.scrollToGoods()
    },
    // ==================== 领取 ====================
    onClaim() {
      const st = this.claimButton
      if (!st || st.disabled) {
        // 置灰态点击给原因提示（领取中不加扰）
        if (st && st.disabled && !this.claiming) {
          if (this.claimedFlag) this.showToast('今日已领取，明天再来')
          else if (this.parsedActivity.remaining <= 0) this.showToast('今日免费份额已领完，明天再来')
        }
        return
      }
      // 购物车未达标：大按钮是凑单入口，滚到商品选购区挑商品（不发起领取）
      if (!this.cartReached) { this.scrollToGoods(); return }
      // 已达标未登录：弹「登录提示」跳登录页带 redirect 回本页（全站同款）
      if (!this.hasToken) {
        uni.showModal({
          title: '登录提示',
          content: '请先登录后领取免费商品',
          confirmText: '去登录',
          confirmColor: '#E02020',
          success: (res) => {
            if (res.confirm) this.goLogin()
          }
        })
        return
      }
      this.doClaim()
    },
    // 领取提交的购物车清单：[{dishId, quantity}]（契约 FreeClaimDTO.items）；
    // 免费项本身（id === 当前选中免费 dishId）剔除——0 元随赠单与购物车分离，其原价不应参与后端门槛重算，与 cartNoFreeTotal 口径一致
    claimItems() {
      return this.cart
        .filter(item => !(this.selectedFreeDishId != null && String(item.id) === this.selectedFreeDishId))
        .map(item => ({ dishId: item.id, quantity: item.quantity }))
    },
    // 领取请求（页内直发 POST /api/free/claim）：契约升级为 FreeClaimDTO { dishId, items }，
    // api 层 freeClaim(dishId) 签名仍只提交 { dishId } 且本次仅限改本页文件，为满足固定契约在页内直发；
    // 语义逐项复刻 api/index.js request 封装：Bearer token 头 / POST 提交类 20s / 401 清登录态 / 结构化 reject
    claimRequest(payload) {
      return new Promise((resolve, reject) => {
        let token = ''
        try {
          const cached = uni.getStorageSync('user')
          const u = typeof cached === 'string' ? JSON.parse(cached) : cached
          token = (u && typeof u.token === 'string' && u.token) ? u.token : ''
        } catch (e) { /* 存储异常按游客处理 */ }
        const header = { 'Content-Type': 'application/json' }
        if (token) header.Authorization = 'Bearer ' + token
        uni.request({
          url: BASE_URL + '/api/free/claim',
          method: 'POST',
          data: payload,
          timeout: 20000,
          header,
          success: (res) => {
            const body = (res.data && typeof res.data === 'object') ? res.data : null
            // 401（HTTP 状态码或信封 code 命中）：与 api 层同口径清登录态（setClearUser 同步删本地存储）
            if (res.statusCode === 401 || (body && body.code === 401)) {
              this.$store.commit('setClearUser')
              reject({ code: 401, message: '请先登录' })
              return
            }
            if (res.statusCode === 200 && body && body.code === 200) {
              resolve(body)
            } else {
              reject({
                code: body && body.code != null ? body.code : res.statusCode,
                message: (body && body.message) || '服务开小差了，请稍后重试'
              })
            }
          },
          fail: () => reject({ code: -1, message: '网络异常' })
        })
      })
    },
    // 领取（POST /api/free/claim body { dishId, items }）：选中商品由服务端校验候选池归属，items=当前购物车
    // 由服务端按 dish 表价格重算合计做门槛校验（绝不信任前端金额）；成功 toast 固定文案 +
    // 记 localStorage 'freeClaimDate'（当日不再重复领取，按钮置灰）+ current/记录区刷新；
    // 门槛不足等失败透出后端 message（形如「再挑选30.01元，今天带走免费商品」，按重算值）
    async doClaim() {
      if (this.claiming) return
      const dish = this.selectedDish
      if (!dish || dish.dishId == null) { this.showToast('请先选择免费商品'); return }
      this.claiming = true
      try {
        const res = await this.claimRequest({ dishId: dish.dishId, items: this.claimItems() })
        this.claimedOrder = (res && res.data) || {}
        try { uni.setStorageSync(CLAIM_DATE_KEY, this.localToday()) } catch (e) { /* 存储异常静默，后端 UNIQUE 幂等仍兜底 */ }
        uni.showToast({ title: '0元领取成功，凭订单到自提点自提', icon: 'none', duration: 2500 })
        this.refreshBroadcast()
        this.loadCurrent()
        this.claimsPageNum = 1
        this.loadMyClaims()
      } catch (e) {
        if (e && e.code === 401) {
          this.showToast('请先登录后领取')
        } else {
          // 无效商品/未达门槛/重复领取/已抢完：后端 400 message 人话透出，保留现场可重试
          this.showToast((e && e.message) || '网络异常')
        }
        // 失败后端可能已落状态（如已被他人领完），重拉保持按钮态同步
        this.loadCurrent()
      } finally {
        this.claiming = false
      }
    },
    // ==================== 播报条 ====================
    // 登录态下今日已有 free 领取记录才显示，静态一条「138****xxxx 成功领取 {dishName}」，无则不显示（不做假播报）
    refreshBroadcast() {
      const a = this.activity || {}
      const claimedNow = a.claimed === true || Number(a.claimed) === 1
      if (!this.hasToken || (!claimedNow && !this.claimedOrder)) { this.broadcastText = ''; return }
      const order = this.claimedOrder || a.claimedOrder || null
      this.broadcastText = `${this.maskedPhone()} 成功领取 ${this.claimedDishName(order)}`
    },
    maskedPhone() {
      const p = (this.user && this.user.phone) || ''
      return /^\d{11}$/.test(p) ? p.slice(0, 3) + '****' + p.slice(7) : '138****xxxx'
    },
    // 领取商品名：0 元单摘要顶层 dishName（后端 claimedOrderSummary 契约字段）优先，
    // → items 首行 → 今日领取记录 → 当前选中商品兜底
    claimedDishName(order) {
      const o = order || {}
      if (typeof o.dishName === 'string' && o.dishName) return o.dishName
      if (Array.isArray(o.items) && o.items.length) {
        const it = o.items[0] || {}
        if (it.dishName || it.name) return it.dishName || it.name
      }
      const todayClaim = this.claims.find(c => (c && c.today === true) || this.claimDateText(c).indexOf('今日') === 0)
      if (todayClaim && todayClaim.dishName) return todayClaim.dishName
      return (this.selectedDish && this.selectedDish.name) || '免费商品'
    },
    // ==================== 领取记录行展示 ====================
    // 领取日期：claimDate 优先（yyyy-MM-dd 或 ISO 串取前 10 位），createTime 兜底；等于本地今日时前缀"今日"
    claimDateText(c) {
      const day = String((c && (c.claimDate || c.createTime)) || '').slice(0, 10)
      if (!day) return ''
      return day === this.localToday() ? `今日 · ${day}` : day
    },
    // 今日已领兜底判定：领取记录里已有今日一条（后端 claimed 缺字段时的第二信号）
    claimsHasToday() {
      return this.claims.some(c => (c && c.today === true) || this.claimDateText(c).indexOf('今日') === 0)
    },
    // 关联订单状态键：orderStatus 优先，status 兜底，再退 order 对象内层（防御后端字段命名差异）
    claimStatusKey(c) {
      if (!c || typeof c !== 'object') return ''
      const k = c.orderStatus || c.status || (c.order && c.order.status) || ''
      return typeof k === 'string' ? k : ''
    },
    claimStatusText(c) {
      const k = this.claimStatusKey(c)
      return ORDER_STATUS_TEXT[k] || k || '已领取'
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 手动驱动 body 滚动（照搜索页同款方案）；引导/更换/轻购物车/全部下拉弹层打开时不拦截（弹层内部自滚）
    onPageWheel(e) {
      if (this.guideVisible || this.showChange || this.showCartPopup || this.showCatAll) return
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* ==================== 选购型免费领页 — 多多买菜风格（白底 + 主红 #E02020 + 金色舞台大卡） ==================== */

.page-container {
  min-height: 100vh;
  background-color: #F5F5F5;
  padding-bottom: 40rpx;
}
/* 购物车栏在场（非空车）时底部留出 购物车栏120rpx + 平台优惠条60rpx + 呼吸，避免固定栏遮住最后内容 */
.page-container.with-bar { padding-bottom: 210rpx; }

/* ==================== 红色头部（custom 自绘：返回 + 标题 + 右上规则｜明细） ==================== */
.free-header {
  position: relative;
  display: flex;
  align-items: center;
  background: linear-gradient(180deg, #E02020 0%, #E02020 55%, #F5F5F5 100%);
  padding: 24rpx 24rpx 20rpx;
  padding-top: calc(24rpx + env(safe-area-inset-top));
}
.free-back {
  position: absolute; left: 20rpx; top: calc(20rpx + env(safe-area-inset-top));
  width: 64rpx; height: 64rpx;
  display: flex; align-items: center; justify-content: center;
  z-index: 2;
}
.free-back-arrow { font-size: 40rpx; font-weight: 700; color: #FFFFFF; line-height: 1; }
.free-title { display: block; font-size: 36rpx; font-weight: 700; color: #FFFFFF; padding-left: 88rpx; }
.free-header-right {
  position: absolute; right: 24rpx; top: calc(28rpx + env(safe-area-inset-top));
  display: flex; align-items: center; gap: 14rpx;
  z-index: 2;
}
.header-link { font-size: 26rpx; font-weight: 600; color: #FFFFFF; padding: 4rpx 6rpx; }
.header-divider { font-size: 24rpx; color: rgba(255,255,255,.6); }

/* ==================== 播报条（登录态今日已领才显示） ==================== */
.broadcast-bar {
  display: flex; align-items: center; gap: 10rpx;
  margin: 16rpx 24rpx 0;
  height: 60rpx; padding: 0 20rpx;
  background: linear-gradient(90deg, #FFF1F0 0%, #FFF7E0 100%);
  border-radius: 30rpx;
  overflow: hidden;
}
.broadcast-icon { font-size: 26rpx; flex-shrink: 0; line-height: 1; }
.broadcast-text {
  flex: 1; min-width: 0;
  font-size: 22rpx; font-weight: 600; color: #C2541F;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

/* ==================== 错误态/空态 ==================== */
.error-state, .empty-state {
  display: flex; flex-direction: column; align-items: center;
  padding: 120rpx 40rpx 40rpx;
}
.empty-state-inline { padding: 60rpx 20rpx; }
.error-state-inline { padding: 40rpx 20rpx; }
.state-icon { font-size: 80rpx; margin-bottom: 20rpx; }
.state-label { font-size: 30rpx; font-weight: 600; color: #333333; margin-bottom: 12rpx; }
.state-sub { font-size: 24rpx; color: #999999; }
.btn-retry {
  margin-top: 32rpx; min-width: 240rpx;
  font-size: 26rpx; font-weight: 600; color: #FFFFFF;
  background-color: #E02020; border: none; border-radius: 32rpx;
  padding: 14rpx 48rpx; line-height: 1.4;
}
.btn-retry::after { border: none; }

/* ==================== 免费商品大卡（金色舞台感·紧凑版：图区 340rpx + 下方名称/价格行紧凑排列） ==================== */
.hero-card {
  margin: 20rpx 24rpx 0;
  border-radius: 24rpx;
  overflow: hidden;
  background: linear-gradient(180deg, #FFF6DC 0%, #FFFDF6 45%, #FFFFFF 100%);
  border: 1rpx solid #F7E7B8;
  box-shadow: 0 8rpx 24rpx rgba(255, 184, 0, 0.12);
}
.hero-stage { position: relative; padding: 16rpx 16rpx 0; }
.hero-badge {
  position: absolute; left: 16rpx; top: 16rpx;
  z-index: 2;
  padding: 6rpx 14rpx;
  background: linear-gradient(90deg, #FFD84D 0%, #FFB800 100%);
  border-radius: 20rpx 20rpx 20rpx 4rpx;
  box-shadow: 0 4rpx 8rpx rgba(255, 184, 0, 0.35);
}
.hero-badge-text { font-size: 20rpx; font-weight: 700; color: #7A3F00; }
.hero-img-wrap { position: relative; }
.hero-img { width: 100%; height: 340rpx; display: block; border-radius: 12rpx; }
.hero-img-fallback {
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #FFECE8 0%, #FFF3E0 100%);
}
.hero-img-emoji { font-size: 110rpx; line-height: 1; }
/* 图上悬浮「更换 ›」小按钮（随图区同步缩小） */
.hero-change {
  position: absolute; right: 14rpx; bottom: 14rpx;
  padding: 6rpx 14rpx;
  background-color: rgba(0, 0, 0, 0.45);
  border-radius: 22rpx;
}
.hero-change-text { font-size: 20rpx; font-weight: 600; color: #FFFFFF; }
.hero-info { padding: 16rpx 20rpx 20rpx; }
.hero-name-row { display: flex; align-items: baseline; gap: 10rpx; }
.hero-name { font-size: 30rpx; font-weight: 700; color: #333333; flex: 1; min-width: 0; }
/* 规格行：灰色小胶囊 */
.hero-unit {
  flex-shrink: 0;
  font-size: 20rpx; color: #666666;
  background-color: #F5F5F5; border-radius: 8rpx;
  padding: 2rpx 12rpx;
}
.hero-price-row { display: flex; align-items: baseline; gap: 10rpx; margin-top: 8rpx; }
.hero-zero { font-size: 32rpx; font-weight: 700; color: #E02020; }
.hero-zero-tag {
  font-size: 20rpx; font-weight: 700; color: #FFFFFF;
  background-color: #E02020; border-radius: 8rpx;
  padding: 2rpx 10rpx;
}
.hero-origin { font-size: 24rpx; color: #999999; text-decoration: line-through; }
.hero-quota-tip { display: block; margin-top: 8rpx; font-size: 20rpx; color: #999999; }

/* ==================== 倒计时行（当日 23:00 截单） ==================== */
.countdown-row {
  display: flex; align-items: center; justify-content: center; gap: 10rpx;
  margin: 16rpx 24rpx 0;
  height: 72rpx;
  background-color: #FFFFFF;
  border-radius: 36rpx;
}
.cd-icon { font-size: 28rpx; line-height: 1; }
.cd-value { font-size: 30rpx; font-weight: 700; color: #E02020; font-variant-numeric: tabular-nums; }
.cd-suffix { font-size: 24rpx; color: #999999; }

/* ==================== 进度条大按钮（未达标凑单 / 达标领取 / 置灰态；uni-button 默认样式显式覆盖） ==================== */
.claim-area { margin: 20rpx 24rpx 0; }
.btn-claim {
  position: relative;
  width: 100%; height: 92rpx;
  margin: 0; padding: 0;
  background-color: #FF7A5C;
  color: #FFFFFF; font-size: 28rpx; font-weight: 700;
  border: none; border-radius: 46rpx;
  overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.btn-claim::after { border: none; }
/* 进度填充：当日实付越接近门槛，深红铺得越满；达标时铺满全钮 */
.claim-fill {
  position: absolute; left: 0; top: 0; bottom: 0;
  background: linear-gradient(90deg, #FF5A3C 0%, #E02020 100%);
  transition: width 0.3s;
}
.btn-claim-text { position: relative; z-index: 1; line-height: 92rpx; }
/* 达标态：整钮渐变红 + 微光阴影强调可领 */
.btn-claim.btn-claim-ready {
  background: linear-gradient(90deg, #FF5A3C 0%, #E02020 100%);
  box-shadow: 0 8rpx 20rpx rgba(224, 32, 32, 0.35);
}
/* 置灰态（已领/抢完/领取中）：禁用视觉 */
.btn-claim.btn-claim-disabled { background-color: #CCCCCC; box-shadow: none; }
.btn-claim[disabled] { color: #FFFFFF; background-color: #CCCCCC; }

/* ==================== 商品选购区（分类 tab + 商品卡列表） ==================== */
.goods-section {
  position: relative;
  margin: 20rpx 24rpx 0;
  /* H5 scrollIntoView 定位时顶部留 16px 呼吸（对照小程序端 target 的 -16 口径） */
  scroll-margin-top: 16px;
}
.cat-tab-bar {
  display: flex; align-items: stretch;
  background-color: #FFFFFF;
  border-radius: 16rpx;
  overflow: hidden;
}
.cat-tabs-scroll { flex: 1; min-width: 0; white-space: nowrap; }
.cat-tabs-scroll ::v-deep .uni-scroll-view::-webkit-scrollbar { display: none; }
.cat-tabs { display: inline-flex; align-items: center; padding: 0 8rpx; }
.cat-tab { position: relative; flex-shrink: 0; padding: 22rpx 24rpx; }
.cat-tab text { font-size: 28rpx; color: #333333; font-weight: 500; }
.cat-tab.active text { color: #E02020; font-weight: 700; }
.cat-tab.active::after {
  content: '';
  position: absolute; left: 50%; bottom: 10rpx;
  transform: translateX(-50%);
  width: 40rpx; height: 6rpx; border-radius: 3rpx;
  background-color: #E02020;
}
.cat-all {
  flex-shrink: 0;
  display: flex; align-items: center;
  padding: 0 20rpx;
  border-left: 1rpx solid #F0F0F0;
}
.cat-all text { font-size: 26rpx; font-weight: 600; color: #333333; }

/* 全部分类下拉（面板贴 tab 栏下沿，遮罩全屏点击关闭） */
.cat-drop { position: absolute; top: 0; left: 0; right: 0; z-index: 40; }
.cat-drop-mask { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background-color: rgba(0,0,0,.35); }
.cat-drop-panel {
  position: absolute; top: 92rpx; left: 0; right: 0;
  background-color: #FFFFFF;
  border-radius: 16rpx;
  padding: 24rpx;
  box-shadow: 0 12rpx 32rpx rgba(0,0,0,.12);
}
.category-grid { display: flex; flex-wrap: wrap; }
.category-item {
  width: 20%; box-sizing: border-box;
  display: flex; flex-direction: column; align-items: center; gap: 10rpx;
  padding: 16rpx 0;
}
.category-icon {
  width: 84rpx; height: 84rpx; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
}
.category-icon text { font-size: 40rpx; line-height: 1; }
.category-name { font-size: 22rpx; color: #333333; }

/* 商品卡（复制自搜索页同款卡片，scoped 隔离） */
.dish-list { margin-top: 20rpx; }
.error-text { font-size: 26rpx; color: #999999; }
.error-retry {
  font-size: 26rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 32rpx;
  padding: 10rpx 44rpx;
  margin-top: 20rpx;
}
.dish-card {
  display: flex;
  background-color: #FFFFFF; border-radius: 12rpx;
  border: 1rpx solid #EEEEEE; box-sizing: border-box;
  padding: 16rpx; margin-bottom: 16rpx;
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04);
}
.dish-img {
  width: 240rpx; height: 240rpx; flex-shrink: 0;
  border-radius: 8rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.dish-emoji { font-size: 100rpx; }
.dish-photo { width: 100%; height: 100%; display: block; }
.dish-body {
  flex: 1; min-width: 0; margin-left: 20rpx;
  display: flex; flex-direction: column;
}
.dish-title-row { display: flex; align-items: center; min-width: 0; }
.dish-seckill-tag {
  flex-shrink: 0; margin-right: 8rpx;
  font-size: 18rpx; font-weight: 700; color: #FFFFFF;
  background-color: #E02020; border-radius: 4rpx; padding: 0 8rpx;
  line-height: 30rpx;
}
.dish-name {
  flex: 1; min-width: 0;
  font-size: 28rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.dish-tags {
  margin-top: 8rpx;
  font-size: 20rpx; color: #999999;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.dish-sold-row { display: flex; align-items: center; gap: 12rpx; margin-top: 8rpx; }
.dish-rush { font-size: 20rpx; font-weight: 600; color: #E02020; flex-shrink: 0; }
.dish-sold { font-size: 20rpx; color: #999999; }
.dish-promo-row { display: flex; align-items: center; gap: 10rpx; margin-top: 8rpx; min-height: 30rpx; }
.tag-cut {
  font-size: 18rpx; color: #E02020; font-weight: 600;
  border: 1rpx solid #E02020; border-radius: 6rpx; padding: 0 8rpx;
  line-height: 26rpx;
}
.tag-limit {
  font-size: 18rpx; color: #999999;
  border: 1rpx solid #DDDDDD; border-radius: 6rpx; padding: 0 8rpx;
  line-height: 26rpx;
}
.dish-rate { font-size: 20rpx; color: #999999; }
.dish-price-row {
  margin-top: auto;
  display: flex; align-items: flex-end; justify-content: space-between;
}
.price-left { display: flex; align-items: baseline; min-width: 0; }
.price-seckill-label { font-size: 20rpx; font-weight: 700; color: #E02020; margin-right: 4rpx; }
.price-symbol { font-size: 20rpx; font-weight: 700; color: #E02020; }
.price-big { font-size: 38rpx; font-weight: 700; color: #E02020; line-height: 1; }
.dish-action { flex-shrink: 0; margin-left: 16rpx; }
.add-btn {
  min-width: 160rpx; height: 56rpx; padding: 0 22rpx; box-sizing: border-box;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #FF5A3C, #E02020);
  border-radius: 28rpx;
}
.add-btn text { font-size: 22rpx; font-weight: 700; color: #FFFFFF; }
.empty-state text { font-size: 26rpx; color: #CCCCCC; }

/* 步进器（与首页/搜索页同款） */
.stepper { display: flex; align-items: center; }
.step-btn {
  width: 46rpx; height: 46rpx; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
}
.step-btn text { font-size: 30rpx; font-weight: 700; line-height: 1; }
.step-minus { border: 1rpx solid #E02020; background-color: #FFFFFF; }
.step-minus text { color: #E02020; }
.step-plus { background-color: #E02020; }
.step-plus text { color: #FFFFFF; }
.step-num { min-width: 56rpx; text-align: center; font-size: 26rpx; font-weight: 700; color: #333333; }

/* ==================== 我的领取记录 ==================== */
.claims-block {
  margin: 32rpx 24rpx 0;
  background-color: #FFFFFF;
  border-radius: 24rpx;
  padding: 28rpx;
}
.claims-head { display: flex; align-items: center; margin-bottom: 8rpx; }
.claims-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.claims-login {
  display: flex; align-items: center; gap: 16rpx;
  padding: 28rpx 0 16rpx;
}
.claims-login-icon { font-size: 40rpx; line-height: 1; }
.claims-login-text { flex: 1; font-size: 26rpx; color: #666666; }
.claims-login-arrow { font-size: 36rpx; color: #CCCCCC; line-height: 1; }
.claim-item {
  display: flex; align-items: center; justify-content: space-between; gap: 20rpx;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #F5F5F5;
}
.claim-item:last-child { border-bottom: none; }
.claim-item-main { flex: 1; min-width: 0; }
.claim-item-name { display: block; font-size: 28rpx; font-weight: 600; color: #333333; }
.claim-item-date { display: block; margin-top: 8rpx; font-size: 22rpx; color: #999999; }
.claim-item-right { flex-shrink: 0; display: flex; flex-direction: column; align-items: flex-end; gap: 8rpx; }
.claim-item-free { font-size: 28rpx; font-weight: 700; color: #E02020; }
.claim-item-status { font-size: 20rpx; font-weight: 700; padding: 4rpx 14rpx; border-radius: 20rpx; }
.cs-pending_pickup { color: #FF6600; background-color: #FFF3EB; }
.cs-completed { color: #999999; background-color: #F5F5F5; }
.cs-cancelled { color: #BBBBBB; background-color: #FAFAFA; }
.claims-state { display: flex; align-items: center; justify-content: center; padding: 40rpx 0 16rpx; }
.claims-state-text { font-size: 24rpx; color: #999999; }
.load-more { display: flex; justify-content: center; padding: 20rpx 0 8rpx; }
.btn-more {
  min-width: 240rpx; font-size: 24rpx; color: #666666;
  background-color: #FFFFFF; border: 1rpx solid #EEEEEE; border-radius: 28rpx;
  padding: 10rpx 40rpx; line-height: 1.4;
}
.btn-more::after { border: none; }
.btn-more[disabled] { color: #CCCCCC; background-color: #FAFAFA; border-color: #EEEEEE; }

/* ==================== 平台优惠条 + 底部购物车栏 ==================== */
.platform-bar {
  position: fixed; left: 0; right: 0; bottom: 120rpx; z-index: 99;
  height: 60rpx;
  display: flex; align-items: center; gap: 12rpx;
  padding: 0 24rpx;
  background-color: #FFFFFF;
  border-top: 1rpx solid #F0F0F0;
}
.pb-label { font-size: 22rpx; font-weight: 700; color: #333333; }
.pb-divider { font-size: 22rpx; color: #DDDDDD; }
.pb-coupon { font-size: 22rpx; font-weight: 600; color: #E02020; }
.pb-arrow { margin-left: auto; font-size: 22rpx; color: #999999; }

.cart-bar {
  position: fixed; bottom: 0; left: 0; right: 0; height: 120rpx;
  background-color: #FFFFFF; display: flex; align-items: stretch;
  overflow: hidden;
  box-shadow: 0 -2rpx 16rpx rgba(0,0,0,.08); z-index: 100;
}
.cart-left { display: flex; align-items: center; gap: 12rpx; padding: 0 16rpx 0 24rpx; flex: 2; min-width: 0; }
.cart-icon-box { position: relative; width: 52rpx; height: 52rpx; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.cart-icon { font-size: 44rpx; line-height: 1; }
.cart-badge {
  position: absolute; top: -4rpx; right: -4rpx;
  min-width: 30rpx; height: 30rpx; padding: 0 6rpx; box-sizing: border-box;
  background-color: #E02020; border-radius: 15rpx;
  font-size: 20rpx; color: #FFFFFF; text-align: center; line-height: 30rpx;
}
.cart-info { display: flex; flex-direction: column; justify-content: center; gap: 4rpx; margin-left: auto; min-width: 0; }
.cart-price-line { display: flex; align-items: baseline; }
.cart-total-symbol { font-size: 26rpx; color: #E02020; font-weight: 700; }
.cart-total-price { font-size: 40rpx; font-weight: 700; color: #E02020; line-height: 1; }
.cart-reduced { font-size: 20rpx; color: #999999; }
/* uniapp button 默认样式显式覆盖（去边框/圆角/内边距，::after 伪元素边框一并去掉） */
.btn-pay {
  flex: 3; min-width: 0; margin: 0;
  background-color: #E02E24;
  color: #FFFFFF; border: none; border-radius: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6rpx;
  padding: 16rpx; line-height: 1.3;
}
.btn-pay::after { border: none; }
.pay-main { font-size: 30rpx; font-weight: 700; color: #FFFFFF; }
.pay-sub-wrap { display: flex; align-items: center; }
.pay-coupon { font-size: 22rpx; background-color: #FFD84D; color: #C23A0F; border-radius: 8rpx; padding: 2rpx 10rpx; font-weight: 600; }

/* ==================== 轻购物车弹层（白底圆角固定 80vh 高，与首页弹层同骨架：服务保障条 + 提货人行 + 标题/管理 + 凑单助手条 + 富卡片行（图/名称/划线价/现价/秒标/勾选圈/删除/步进器）+ 管理模式/常规模式双底部条，mask 点击关闭） ==================== */
.cart-popup-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0,0,0,.5); z-index: 150;
  display: flex; align-items: flex-end; justify-content: center;
}
.cart-popup {
  width: 100%;
  background-color: #FFFFFF; border-radius: 24rpx 24rpx 0 0;
  height: 80vh; /* 固定 80% 屏高（对照首页 .cart-popup 同值），空车/有车一致，底栏恒钉弹层底部 */
  box-sizing: border-box;
  display: flex; flex-direction: column;
  overflow: hidden; /* 对照首页：圆角内裁切，列表区超出不出弹层 */
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  animation: cartPop 0.22s ease;
}
@keyframes cartPop {
  from { transform: translateY(30%); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}
/* 服务保障条（对照首页购物车弹层顶部同款：✓绿勾 + 灰字标签，字典 service_tags 渲染；
   负 margin 抵消弹层内边距贴边全宽，顶角随面板圆角） */
.service-bar {
  flex-shrink: 0;
  display: flex; justify-content: space-around; align-items: center;
  margin: -24rpx -24rpx 12rpx;
  padding: 14rpx 24rpx;
  background-color: #F5F5F5;
  border-radius: 24rpx 24rpx 0 0;
}
.service-item { display: flex; align-items: center; gap: 4rpx; font-size: 20rpx; color: #666666; }
.service-check { color: #07C160; font-weight: 700; }
/* 提货人行（对照首页弹层 pickup-row：👤 姓名 手机号 ›；弹层自带左右内边距，无需再缩进） */
.pickup-row {
  flex-shrink: 0;
  display: flex; align-items: center; gap: 10rpx;
  padding: 0 0 12rpx;
}
.pr-icon { font-size: 28rpx; flex-shrink: 0; }
.pr-name {
  font-size: 26rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pr-phone { font-size: 24rpx; color: #666666; }
.pr-arrow { margin-left: auto; flex-shrink: 0; font-size: 30rpx; color: #CCCCCC; }
.cart-popup-head { flex-shrink: 0; display: flex; align-items: center; margin-bottom: 12rpx; }
.cart-popup-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.cart-popup-count { margin-left: 12rpx; font-size: 22rpx; color: #999999; }
.cart-popup-head-right { margin-left: auto; display: flex; align-items: center; gap: 28rpx; }
/* 管理入口：灰字（红留给管理模式内的勾选态与删除按钮），进入管理模式后文案变「退出管理」（对照首页 cart-manage-btn） */
.cart-manage-btn { font-size: 24rpx; font-weight: 600; color: #666666; }
.cart-popup-close { font-size: 32rpx; color: #CCCCCC; padding: 4rpx 8rpx; line-height: 1; }
/* 凑单助手条（对照首页弹层 helper-bar 同款文案/配色；弹层自带左右内边距） */
.helper-bar {
  flex-shrink: 0;
  display: flex; align-items: center; gap: 12rpx;
  margin: 0 0 12rpx; padding: 10rpx 20rpx;
  background-color: #FFF7F0; border-radius: 10rpx;
}
.hb-title { font-size: 22rpx; font-weight: 700; color: #E02020; }
.hb-divider { font-size: 22rpx; color: #F0D8C0; }
.hb-text { font-size: 22rpx; color: #E0662A; }
/* 勾选圆圈（对照首页弹层 check-circle：管理模式 item 勾选与底部全选复用同款红勾选态） */
.check-circle {
  width: 36rpx; height: 36rpx; flex-shrink: 0;
  border: 2rpx solid #DDDDDD; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background-color: #FFFFFF;
}
.check-circle.checked { border: none; background-color: #E02020; }
.check-circle text { font-size: 20rpx; font-weight: 700; color: #FFFFFF; line-height: 1; }
/* 列表滚动区（对照首页 .cart-items 高度策略：flex:1 撑满弹层剩余高度内部滚动，去掉原 44vh 上限——
   item 多时列表内滚、item 少时也占满高度，底部条始终钉在弹层底部） */
.cart-popup-scroll { flex: 1; min-height: 0; }
/* 商品行富卡片（对照首页弹层 ci-* 同款数值；fci- 前缀防与本页 .dish-* 等类名冲突，scoped 隔离） */
.cart-popup-item { display: flex; align-items: center; gap: 16rpx; padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5; }
.cart-popup-item:last-child { border-bottom: none; }
.fci-img {
  width: 120rpx; height: 120rpx; flex-shrink: 0;
  border-radius: 8rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.fci-emoji { font-size: 56rpx; line-height: 1; }
.fci-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.fci-name-row { display: flex; align-items: center; min-width: 0; }
.fci-seckill-tag {
  flex-shrink: 0; margin-right: 8rpx;
  font-size: 16rpx; font-weight: 700; color: #FFFFFF;
  background-color: #E02020; border-radius: 4rpx; padding: 0 6rpx;
  line-height: 26rpx;
}
.fci-name {
  flex: 1; min-width: 0;
  font-size: 26rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.fci-price-row { display: flex; align-items: baseline; gap: 12rpx; }
.fci-original { font-size: 20rpx; color: #BBBBBB; text-decoration: line-through; }
.fci-price-line { display: flex; align-items: baseline; }
.fci-symbol { font-size: 18rpx; font-weight: 700; color: #E02020; }
.fci-price { font-size: 30rpx; font-weight: 700; color: #E02020; line-height: 1; }
.fci-discount {
  font-size: 18rpx; font-weight: 600; color: #E02020;
  background-color: #FFECE8; border-radius: 6rpx; padding: 2rpx 8rpx;
}
.fci-action { flex-shrink: 0; }
/* 管理模式单件「删除」文字按钮（对照首页弹层 ci-delete 同款红描边胶囊） */
.fci-delete {
  display: inline-block;
  font-size: 22rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 24rpx;
  padding: 6rpx 20rpx;
}
/* 空车占位（对照首页 cart-items-empty：撑满列表区后垂直+水平居中，不再靠 padding 撑高度） */
.cart-popup-empty { height: 100%; display: flex; align-items: center; justify-content: center; }
.cart-popup-empty text { font-size: 24rpx; color: #CCCCCC; }
/* 底部操作条（对照首页弹层 cart-total-bar：管理模式=全选+已选+删除，常规模式=已选+合计+已减+立即支付；
   弹层自身已带左右与底部 safe-area 内边距，此处不再重复） */
.cart-total-bar { flex-shrink: 0; display: flex; align-items: center; padding-top: 16rpx; border-top: 1rpx solid #F0F0F0; }
.check-all { display: flex; align-items: center; gap: 10rpx; flex-shrink: 0; }
.check-all-text { font-size: 24rpx; color: #333333; }
.manage-count { margin-left: 20rpx; font-size: 24rpx; color: #666666; }
/* uni-button 默认样式显式覆盖（去边框/圆角/内边距，::after 伪元素边框一并去掉） */
.btn-manage-delete {
  margin: 0 0 0 auto;
  min-width: 160rpx; height: 72rpx; line-height: 72rpx;
  background-color: #E02020; color: #FFFFFF;
  font-size: 26rpx; font-weight: 700;
  border: none; border-radius: 36rpx; padding: 0 44rpx;
  text-align: center;
}
.btn-manage-delete::after { border: none; }
.ctb-price { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: flex-end; gap: 2rpx; margin-right: 16rpx; }
.ctb-price-line { display: flex; align-items: baseline; }
.ctb-price-num { font-size: 36rpx; font-weight: 700; color: #E02020; line-height: 1; }
.ctb-saving { font-size: 20rpx; color: #999999; }
.btn-pay-popup {
  flex-shrink: 0; margin: 0;
  min-width: 200rpx; height: 76rpx; line-height: 76rpx;
  background-color: #E02E24; color: #FFFFFF;
  font-size: 26rpx; font-weight: 700;
  border: none; border-radius: 38rpx; padding: 0 36rpx;
  text-align: center;
}
.btn-pay-popup::after { border: none; }
/* 空车时支付按钮置灰禁用（点击仍走 payFromCartFree 的 toast 提示） */
.btn-pay-popup.btn-pay-disabled { background-color: #CCCCCC; }

/* ==================== 弹层通用 ==================== */
.popup-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0,0,0,.5); z-index: 200;
  display: flex; align-items: flex-end; justify-content: center;
}

/* 引导弹窗样式已抽离共享组件 components/free-gift-guide/free-gift-guide.vue（样式随组件走，两页观感一致） */

/* ==================== 更换免费商品弹窗（底部弹层，两列网格） ==================== */
.change-popup {
  width: 100%;
  background-color: #FFFFFF; border-radius: 24rpx 24rpx 0 0;
  max-height: 80vh;
  box-sizing: border-box;
  display: flex; flex-direction: column;
  padding: 28rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
}
.change-head {
  flex-shrink: 0;
  display: flex; align-items: flex-start; justify-content: space-between;
  margin-bottom: 20rpx;
}
.change-titles { flex: 1; min-width: 0; padding-right: 16rpx; }
.change-title { display: block; font-size: 30rpx; font-weight: 700; color: #333333; }
.change-sub { display: block; margin-top: 8rpx; font-size: 22rpx; color: #999999; }
.change-close { font-size: 32rpx; color: #CCCCCC; padding: 4rpx 8rpx; line-height: 1; }
.change-scroll { flex: 1; min-height: 0; max-height: 58vh; }
.change-grid { display: flex; flex-wrap: wrap; gap: 20rpx; padding-bottom: 8rpx; }
.change-card {
  width: calc(50% - 10rpx); box-sizing: border-box;
  border: 1rpx solid #F0F0F0; border-radius: 16rpx;
  padding: 16rpx;
  display: flex; flex-direction: column; align-items: center;
}
.change-img {
  width: 200rpx; height: 200rpx;
  border-radius: 12rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.change-photo { width: 100%; height: 100%; display: block; }
.change-emoji { font-size: 90rpx; line-height: 1; }
.change-unit { margin-top: 12rpx; font-size: 20rpx; color: #999999; }
.change-name {
  margin-top: 6rpx;
  font-size: 26rpx; font-weight: 600; color: #333333;
  text-align: center; line-height: 1.3;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.change-btn {
  margin-top: 16rpx; width: 100%; height: 56rpx;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #FF5A3C, #E02020);
  border-radius: 28rpx;
}
.change-btn text { font-size: 24rpx; font-weight: 700; color: #FFFFFF; }
/* 当前已选中的卡：「更换」置灰文案「已选择」 */
.change-btn-current { background-color: #F5F5F5; }
.change-btn-current text { color: #BBBBBB; }
.change-empty { width: 100%; display: flex; justify-content: center; padding: 60rpx 0; }
.change-empty text { font-size: 24rpx; color: #CCCCCC; }
</style>
