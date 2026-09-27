<template>
  <view class="page-container">
    <!-- ==================== 顶部白色导航（navigationStyle custom 自绘）：< 返回 + 浅灰描边圆角搜索框（自动聚焦）+ 红字"搜索" ==================== -->
    <view class="nav-bar">
      <view class="nav-back" @click="goBack"><text class="nav-back-arrow">&lt;</text></view>
      <!-- 浅灰描边圆角胶囊：红放大镜（CSS 绘制）+ 真实 input（自动聚焦）+ 竖分隔线 + 红字"搜索"（与首页一体式搜索条同构） -->
      <view class="nav-search-box">
        <view class="nav-search-icon"></view>
        <input
          class="nav-search-input"
          v-model="keyword"
          :focus="true"
          placeholder="输入商品名称"
          placeholder-style="color:#BBBBBB;"
          confirm-type="search"
          @confirm="onSearch"
          @input="onKeywordInput"
        />
        <view class="nav-search-divider"></view>
        <view class="nav-search-go" @click="onSearch"><text class="nav-search-go-text">搜索</text></view>
      </view>
    </view>

    <!-- ==================== 内容区（两态：未搜索=发现页三大块；输入关键词点"搜索"=结果列表） ==================== -->
    <view class="page-body" :class="{ 'page-body-with-bar': !isCartEmpty }">
      <template v-if="!searchMode">
        <!-- a. 最近搜索（历史为空整块不显示；🗑 弹居中确认框，确认后清空 search_history） -->
        <view class="ss-block" v-if="searchHistory.length > 0">
          <view class="ss-head">
            <view class="ss-title-row">
              <text class="ss-title-icon">🕐</text>
              <text class="ss-title">最近搜索</text>
            </view>
            <text class="ss-trash" @click="onClearHistory">🗑</text>
          </view>
          <view class="ss-chips">
            <view class="ss-chip" v-for="(w, i) in searchHistory" :key="'hist-' + i" @click="onChipSearch(w)">
              <text class="ss-chip-text">{{ w }}</text>
            </view>
          </view>
        </view>

        <!-- b. 搜索发现（🔄 点击打乱重排；词胶囊带随机 emoji + 随机 2-3 个红字"热"角标；点击词即搜索） -->
        <view class="ss-block">
          <view class="ss-head">
            <text class="ss-title">搜索发现</text>
            <view class="ss-refresh" @click="shuffleDiscover">
              <text class="ss-refresh-icon">🔄</text>
              <text class="ss-refresh-text">换一换</text>
            </view>
          </view>
          <view class="ss-chips">
            <view class="sd-chip" v-for="(w, i) in discoverWords" :key="w.word + '-' + i" @click="onChipSearch(w.word)">
              <text class="sd-chip-emoji">{{ w.emoji }}</text>
              <text class="sd-chip-text">{{ w.word }}</text>
              <text v-if="w.hot" class="sd-chip-hot">热</text>
            </view>
          </view>
        </view>

        <!-- c. 地区热卖榜单（横向滑动 scroll-view：两张约 85vw 榜单卡并排，各 14 个商品） -->
        <view class="ss-block ss-rank" v-if="!loadError">
          <view class="ss-head">
            <text class="ss-title">地区热卖榜单</text>
          </view>
          <scroll-view class="rank-scroll" scroll-x>
            <!-- 榜单一：⭐ 广东省热卖｜大家都在买（全量按 soldCount 降序取 14） -->
            <view class="rank-card">
              <view class="rank-card-head">
                <text class="rank-card-title">⭐ 广东省热卖</text>
                <text class="rank-card-sep">｜</text>
                <text class="rank-card-sub">大家都在买</text>
              </view>
              <view class="rank-item" v-for="(d, i) in rankGeneral" :key="'g-' + d.id">
                <!-- 排名角标：1 金 2 银 3 铜，4+ 灰 -->
                <text class="rank-no" :class="i < 3 ? 'rank-no-' + (i + 1) : ''">{{ i + 1 }}</text>
                <view class="rank-img" :style="{ backgroundColor: d.bgColor || '#F5F5F5' }" @click="goToDetail(d)">
                  <image v-if="d.image && !imgFail[d.id]" class="rank-photo" :src="d.image" mode="aspectFill" @error="onImgFail(d.id)" />
                  <text v-else class="rank-emoji">{{ d.emoji }}</text>
                </view>
                <view class="rank-body">
                  <text class="rank-name" @click="goToDetail(d)">{{ d.name }}</text>
                  <text class="rank-sold">已售 {{ d.soldCount }} 件</text>
                  <view class="rank-bottom">
                    <view class="rank-price-line">
                      <text class="rank-symbol">¥</text>
                      <text class="rank-price">{{ fmtPrice(rankPrice(d)) }}</text>
                    </view>
                    <!-- 已加购显示步进器，未加购显示红底"加入购物车"（与首页列表卡一致） -->
                    <view class="rank-action" @click.stop>
                      <view v-if="cartMap[d.id]" class="stepper">
                        <view class="step-btn step-minus" @click="minusDish(d)"><text>−</text></view>
                        <text class="step-num">{{ cartMap[d.id].quantity }}</text>
                        <view class="step-btn step-plus" @click="plusDish(d)"><text>+</text></view>
                      </view>
                      <view v-else class="rank-add" @click="addDish(d)"><text>加入购物车</text></view>
                    </view>
                  </view>
                </view>
              </view>
              <view v-if="rankGeneral.length === 0" class="rank-empty"><text>暂无数据</text></view>
            </view>
            <!-- 榜单二：📊 生鲜热卖榜（fresh/vegetable/frozen 过滤后按 soldCount 降序取 14） -->
            <view class="rank-card">
              <view class="rank-card-head">
                <text class="rank-card-title">📊 生鲜热卖榜</text>
              </view>
              <view class="rank-item" v-for="(d, i) in rankFresh" :key="'f-' + d.id">
                <text class="rank-no" :class="i < 3 ? 'rank-no-' + (i + 1) : ''">{{ i + 1 }}</text>
                <view class="rank-img" :style="{ backgroundColor: d.bgColor || '#F5F5F5' }" @click="goToDetail(d)">
                  <image v-if="d.image && !imgFail[d.id]" class="rank-photo" :src="d.image" mode="aspectFill" @error="onImgFail(d.id)" />
                  <text v-else class="rank-emoji">{{ d.emoji }}</text>
                </view>
                <view class="rank-body">
                  <text class="rank-name" @click="goToDetail(d)">{{ d.name }}</text>
                  <text class="rank-sold">已售 {{ d.soldCount }} 件</text>
                  <view class="rank-bottom">
                    <view class="rank-price-line">
                      <text class="rank-symbol">¥</text>
                      <text class="rank-price">{{ fmtPrice(rankPrice(d)) }}</text>
                    </view>
                    <view class="rank-action" @click.stop>
                      <view v-if="cartMap[d.id]" class="stepper">
                        <view class="step-btn step-minus" @click="minusDish(d)"><text>−</text></view>
                        <text class="step-num">{{ cartMap[d.id].quantity }}</text>
                        <view class="step-btn step-plus" @click="plusDish(d)"><text>+</text></view>
                      </view>
                      <view v-else class="rank-add" @click="addDish(d)"><text>加入购物车</text></view>
                    </view>
                  </view>
                </view>
              </view>
              <view v-if="rankFresh.length === 0" class="rank-empty"><text>暂无数据</text></view>
            </view>
          </scroll-view>
        </view>
        <!-- 榜单加载失败错误态（≠空态）：点击重试 -->
        <view class="error-state" v-if="loadError" @click="loadDishes">
          <text class="error-text">榜单加载失败，请检查网络</text>
          <text class="error-retry">点击重试</text>
        </view>
      </template>

      <template v-else>
        <!-- 搜索结果头：关键词 + 取消（回发现页三大块） -->
        <view class="search-result-head">
          <text class="srh-text">"{{ keyword }}" 的搜索结果</text>
          <text class="srh-cancel" @click="exitSearch">取消</text>
        </view>
        <!-- 结果列表：同首页商品卡样式，可加购 -->
        <view class="dish-list">
          <view class="dish-card" v-for="dish in searchResults" :key="dish.id" @click="goToDetail(dish)">
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
          <!-- 空态：搜索请求中不显示 -->
          <view v-if="!searchLoading && searchResults.length === 0" class="empty-state">
            <text>没有找到相关商品</text>
          </view>
        </view>
      </template>
    </view>

    <!-- ==================== 底部购物车栏（复刻首页 cart-bar；"去支付"写 openCheckout 标志后跳回首页打开确认订单） ==================== -->
    <view class="cart-bar" v-if="!isCartEmpty">
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
      <button class="btn-pay" @click="goPayFromSearch">
        <text class="pay-main">去支付</text>
        <view class="pay-sub-wrap" v-if="mainCouponText"><text class="pay-coupon">{{ mainCouponText }}</text></view>
      </button>
    </view>

    <!-- ==================== 购物车弹层（复刻首页 cart-popup 结构与交互，数据同 store 全局共享） ==================== -->
    <view class="popup-mask" v-if="showCartDetail" @click="closeCartPopup">
      <view class="cart-popup" @click.stop>
        <!-- 服务保障条（字典 service_tags 渲染，失败回退内置默认） -->
        <view class="service-bar">
          <view class="service-item" v-for="tag in serviceTags" :key="tag">
            <text class="service-check">✓</text><text>{{ tag }}</text>
          </view>
        </view>
        <!-- 提货人行（点击弹修改提货人弹窗，与首页同款交互） -->
        <view class="pickup-row" @click="openPersonPopup">
          <text class="pr-icon">👤</text>
          <text class="pr-name">{{ pickupPerson.name }}</text>
          <text class="pr-phone">{{ pickupPerson.phone }}</text>
          <text class="pr-arrow">›</text>
        </view>
        <!-- 标题行：购物车 | 清空(二次确认)/删除商品/完成 | ✕ -->
        <view class="cart-title-row">
          <text class="cart-title">购物车</text>
          <view class="cart-title-right">
            <text v-if="cart.length > 0" class="cart-clear" @click="onClearCart">清空</text>
            <text class="cart-edit" @click="toggleEdit">{{ editingCart ? '完成' : '删除商品' }}</text>
            <text class="cart-close" @click="closeCartPopup">✕</text>
          </view>
        </view>
        <!-- 凑单助手条 -->
        <view class="helper-bar">
          <text class="hb-title">凑单助手</text>
          <text class="hb-divider">|</text>
          <text class="hb-text">已减 {{ fmtPrice(cartSaving) }} 元，下单立享</text>
        </view>
        <!-- 商品行（有商品时滚动；空车时显示居中占位） -->
        <scroll-view v-if="cart.length > 0" class="cart-items" scroll-y>
          <view class="cart-item" v-for="item in cart" :key="item.id">
            <view class="check-circle" :class="{ checked: item.checked }" @click.stop="toggleCheck(item)">
              <text v-if="item.checked">✓</text>
            </view>
            <view class="ci-main" @click="goToCartItemDetail(item)">
              <view class="ci-img" :style="{ backgroundColor: (dishOf(item).bgColor) || '#F5F5F5' }">
                <text>{{ item.emoji || dishOf(item).emoji || '🛒' }}</text>
              </view>
              <view class="ci-body">
                <view class="ci-name-row">
                  <text v-if="isSeckillItem(item)" class="ci-seckill-tag">秒</text>
                  <text class="ci-name">{{ item.name }}</text>
                </view>
                <view class="ci-desc-row">
                  <text class="ci-restore">即将恢复原价</text>
                  <text class="ci-sold" v-if="dishOf(item).soldText">{{ dishOf(item).soldText }}</text>
                  <text class="ci-limit" v-if="dishOf(item).limitBuy">限购{{ dishOf(item).limitBuy }}件</text>
                </view>
                <view class="ci-price-row">
                  <text class="ci-original">¥{{ fmtPrice(item.originalPrice) }}</text>
                  <view class="ci-price-line">
                    <text class="ci-symbol">¥</text>
                    <text class="ci-price">{{ fmtPrice(item.price) }}</text>
                  </view>
                  <text class="ci-discount" v-if="isSeckillItem(item) && discountRate(item)">{{ discountRate(item) }}</text>
                </view>
              </view>
            </view>
            <view class="ci-action" @click.stop>
              <text v-if="editingCart" class="ci-delete" @click="removeItem(item.id)">删除</text>
              <view v-else class="stepper">
                <view class="step-btn step-minus" @click="decreaseById(item.id)"><text>−</text></view>
                <text class="step-num">{{ item.quantity }}</text>
                <view class="step-btn step-plus" @click="increaseById(item.id)"><text>+</text></view>
              </view>
            </view>
          </view>
        </scroll-view>
        <view v-else class="cart-items cart-items-empty">
          <view class="cart-empty">
            <text class="cart-empty-icon">🛒</text>
            <text class="cart-empty-text">购物车还是空的，去逛逛吧</text>
          </view>
        </view>
        <!-- 底部合计栏（空车保留，支付按钮置灰禁用） -->
        <view class="cart-total-bar">
          <view class="check-all" @click="toggleAll">
            <view class="check-circle" :class="{ checked: allChecked }"><text v-if="allChecked">✓</text></view>
            <text class="check-all-text">已选 {{ checkedCount }} 件</text>
          </view>
          <view class="ctb-price">
            <view class="ctb-price-line">
              <text class="ci-symbol">¥</text>
              <text class="ctb-price-num">{{ fmtPrice(checkedTotalPrice) }}</text>
            </view>
            <text class="ctb-saving">已减 ¥{{ fmtPrice(cartSaving) }}</text>
          </view>
          <button class="btn-pay-popup" :class="{ 'btn-pay-disabled': cart.length === 0 }" @click="payFromCart"><text>立即支付</text></button>
        </view>
      </view>
    </view>

    <!-- ==================== 修改提货人弹窗（与首页同款：居中白色圆角） ==================== -->
    <view class="popup-mask popup-mask-center" v-if="showPersonPopup" @click="showPersonPopup = false">
      <view class="person-popup" @click.stop>
        <text class="person-close" @click="showPersonPopup = false">✕</text>
        <text class="person-title">修改提货人信息</text>
        <view class="form-group">
          <text class="form-label">提货人</text>
          <input class="form-input" v-model="formPersonName" placeholder="请输入提货人姓名" />
        </view>
        <view class="form-group">
          <text class="form-label">手机号</text>
          <input class="form-input" v-model="formPersonPhone" type="number" maxlength="11" placeholder="请输入手机号码" />
        </view>
        <button class="btn-save-person" @click="savePerson">保存</button>
      </view>
    </view>
  </view>
</template>

<script>
// 独立搜索页（复刻多多买菜搜索页）：首页搜索栏 uni.navigateTo 跳入。
// 商品卡/购物车栏/购物车弹层结构与样式复制自 pages/index/index.vue（两页均为独立组件 scoped 编译，同名类不冲突），
// 购物车数据全部走 Vuex store 全局共享（与首页/详情页一致）。
import { mapState, mapGetters } from 'vuex'
import { decorateDish } from '@/api/index.js'
import { getDictGroup, parseCoupon } from '@/api/dict.js'
import { BASE_URL } from '@/api/config.js'

// 搜索历史 storage 键（与首页共用同键同格式：JSON 字符串数组，去重最多 10 条）
const SEARCH_HISTORY_KEY = 'search_history'
const SEARCH_HISTORY_MAX = 10
// 搜索发现默认词库（需求指定 10 个 + 补足 15 个；刷新按钮打乱重排）
const DISCOVER_WORDS = ['鸡蛋', '沐浴露', '苹果醋24瓶', '烧鸭', '牙膏', '洗洁精', '纸巾', '牛肉', '酸奶', '西瓜', '排骨', '玉米', '哈密瓜', '虾仁', '抽纸']
// 发现词随机 emoji 池（每次洗牌随机分配）
const DISCOVER_EMOJIS = ['🥚', '🧴', '🍶', '🦆', '🪥', '🧽', '🧻', '🥩', '🥛', '🍉', '🌽', '🍖', '🦐', '🍈', '🍎']
// 生鲜热卖榜类目过滤范围（需求指定：fresh/vegetable/frozen）
const FRESH_CATEGORIES = ['fresh', 'vegetable', 'frozen']
// 每张榜单条数
const RANK_SIZE = 14

export default {
  data() {
    return {
      // 搜索框绑定值（自动聚焦，placeholder"输入商品名称"）
      keyword: '',
      // 两态开关：false=发现页三大块；true=搜索结果列表
      searchMode: false,
      searchResults: [],   // 搜索结果（GET /api/dishes?keyword=，decorateDish 增强）
      searchLoading: false,// 搜索请求飞行中（空态在请求中不闪现）
      loadError: false,    // 榜单数据加载失败（≠空态，点击重试）
      imgFail: {},         // 图片加载失败记录表（id -> true，@error 后回退 emoji 色块）
      // 全量商品（榜单数据源 + 购物车行回查 seckill/limitBuy/soldText/bgColor）
      dishes: [],
      rankGeneral: [],     // 榜单一：全量按 soldCount 降序取 14
      rankFresh: [],       // 榜单二：fresh/vegetable/frozen 过滤后按 soldCount 降序取 14
      discoverWords: [],   // 搜索发现词（洗牌后 [{word, emoji, hot}]）
      searchHistory: [],   // 历史词（storage 键 search_history，与首页共用）
      // 购物车弹层/提货人弹窗开关与编辑态
      showCartDetail: false,
      editingCart: false,
      showPersonPopup: false,
      formPersonName: '',
      formPersonPhone: '',
      // 服务保障标签（字典 service_tags 渲染，购物车弹层服务条；失败回退内置默认）
      serviceTags: ['坏了包退', '晚到必赔', '极速退款'],
      // 券字典（购物车栏"去支付"副文案"满x减y"，与首页 mainCouponText 同口径）
      coupons: []
    }
  },
  computed: {
    ...mapState(['cart', 'pickupPerson', 'user']),
    ...mapGetters(['cartCount', 'cartTotalPrice', 'isCartEmpty', 'cartSaving', 'checkedCount', 'checkedTotalPrice']),
    // 购物车 id -> 项 的映射，用于榜单/结果列表判断"已加购"与数量
    cartMap() {
      const map = {}
      this.cart.forEach(item => { map[item.id] = item })
      return map
    },
    // 全量商品 id -> 商品 的映射，用于购物车行回查 seckill/limitBuy/soldText/bgColor
    dishById() {
      const map = {}
      this.dishes.forEach(d => { map[d.id] = d })
      return map
    },
    // "去支付"按钮券文案：优先第一条可用券，按 threshold/amount 拼"满x减y"（与首页同口径）
    mainCouponText() {
      const list = this.coupons || []
      if (!list.length) return ''
      const c = list.find(x => !x.locked) || list[0]
      if (c.threshold && c.amount) return `满${c.threshold}减${c.amount}`
      return c.main || ''
    },
    // 是否全部勾选（控制合计栏全选圆圈）
    allChecked() { return this.cart.length > 0 && this.cart.every(item => item.checked) }
  },
  onLoad(options) {
    this.loadSearchHistory()
    this.shuffleDiscover()
    this.loadDishes()
    this.loadDicts()
    // 首页携关键词跳入（首页"搜索"按钮/历史词点击会带 kw）：回填并直接执行搜索
    if (options && options.kw) {
      let kw = String(options.kw)
      try { kw = decodeURIComponent(kw) } catch (e) { /* 已是明文则原样使用 */ }
      this.keyword = kw
      this.doSearch()
    }
  },
  // #ifdef H5
  onReady() {
    // PC 浏览器鼠标无原生拖拽滚动：document 捕获阶段委托监听（榜单区被 v-if 销毁重建也不丢绑定）
    this._rankDragOnDown = this.onRankPointerDown.bind(this)
    document.addEventListener('pointerdown', this._rankDragOnDown, true)
    // 页面级滚轮兜底：html,body overflow:hidden 后滚轮默认滚动失效，手动接管页面滚动
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  onUnload() {
    if (this._rankDragOnDown) {
      document.removeEventListener('pointerdown', this._rankDragOnDown, true)
      this._rankDragOnDown = null
    }
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    this.unbindRankDrag() // 兜底清理拖拽中挂起的 move/up 监听
  },
  // #endif
  methods: {
    // ==================== 通用 ====================
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // 金额统一两位小数展示（脏数据兜底 0.00，与首页同口径）
    fmtPrice(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n.toFixed(2) : '0.00'
    },
    // 图片加载失败：标记该商品回退 emoji 色块
    onImgFail(id) {
      this.imgFail = { ...this.imgFail, [id]: true }
    },
    // 榜单价格：秒杀件取秒杀价，其余取现价（价格红色展示）
    rankPrice(d) {
      return d && d.seckill && d.seckillPrice != null ? d.seckillPrice : (d && d.price)
    },
    // ==================== 数据加载 ====================
    // 页面局部请求：与首页 dishRequest 同款（GET /api/dishes 支持 category/keyword 独立或组合，
    // R 信封 code===200 校验，decorateDish 增强 seckill/soldCount/image 等字段）。
    // 不走 api/index.js 的 getDishes（其 data:{category} 固定入参不适配本页 keyword 场景，且 api 层归另一位同事维护）
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
    // 一次拉全量：榜单排序/购物车行回查共用；失败显示错误态可重试
    async loadDishes() {
      this.loadError = false
      try {
        const all = await this.dishRequest({})
        all.sort((a, b) => b.id - a.id) // 与首页列表同口径（id 倒序）
        this.dishes = all
        // 榜单一：全量按 soldCount 降序取 14
        this.rankGeneral = [...all]
          .sort((a, b) => (Number(b.soldCount) || 0) - (Number(a.soldCount) || 0))
          .slice(0, RANK_SIZE)
        // 榜单二：fresh/vegetable/frozen 过滤后按 soldCount 降序取 14
        this.rankFresh = all
          .filter(d => FRESH_CATEGORIES.indexOf(d.category) !== -1)
          .sort((a, b) => (Number(b.soldCount) || 0) - (Number(a.soldCount) || 0))
          .slice(0, RANK_SIZE)
      } catch (e) {
        this.dishes = []
        this.rankGeneral = []
        this.rankFresh = []
        this.loadError = true
      }
    },
    // 字典：服务保障条（购物车弹层）+ 券文案（去支付副文案）；失败回退内置默认
    async loadDicts() {
      const [tags, cps] = await Promise.all([
        getDictGroup('service_tags').catch(() => []),
        getDictGroup('coupon_template').catch(() => [])
      ])
      if (Array.isArray(tags) && tags.length) this.serviceTags = tags.map(d => d.word)
      this.coupons = (cps || []).map(d => {
        const c = parseCoupon(d.definition) || {}
        return { main: d.word, threshold: c.threshold, amount: c.amount, state: c.state || '', locked: c.state !== 'available' }
      })
    },
    // ==================== 搜索发现（洗牌） ====================
    // 点击 🔄：Fisher-Yates 打乱默认词库重排，随机分配 emoji，随机 2-3 个词带红字"热"角标
    shuffleDiscover() {
      const pool = [...DISCOVER_WORDS]
      for (let i = pool.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1))
        const tmp = pool[i]; pool[i] = pool[j]; pool[j] = tmp
      }
      const hotCount = 2 + Math.floor(Math.random() * 2) // 2~3 个
      const hotIdx = new Set()
      while (hotIdx.size < hotCount && hotIdx.size < pool.length) {
        hotIdx.add(Math.floor(Math.random() * pool.length))
      }
      this.discoverWords = pool.map((word, i) => ({
        word,
        emoji: DISCOVER_EMOJIS[Math.floor(Math.random() * DISCOVER_EMOJIS.length)],
        hot: hotIdx.has(i)
      }))
    },
    // ==================== 搜索 ====================
    // 点击红字"搜索"/键盘确认：词非空才搜（空词 toast 提示）
    onSearch() {
      const kw = (this.keyword || '').trim()
      if (!kw) { this.showToast('请输入搜索关键词'); return }
      this.doSearch()
    },
    // 执行搜索：写入历史词（去重最多 10 条）→ 切结果态 → GET /api/dishes?keyword=
    async doSearch() {
      const kw = (this.keyword || '').trim()
      if (!kw) return
      this.saveSearchHistory(kw)
      this.searchMode = true
      this.searchLoading = true
      try {
        this.searchResults = await this.dishRequest({ keyword: kw })
      } catch (e) {
        this.searchResults = []
        this.showToast((e && e.message) || '网络异常')
      } finally {
        this.searchLoading = false
      }
    },
    // 输入时清空关键词：自动退出结果态回发现页（三大块）
    onKeywordInput() {
      if (!(this.keyword || '').trim() && this.searchMode) this.exitSearch()
    },
    // 点击历史/发现词胶囊 → 回填关键词并执行搜索
    onChipSearch(word) {
      this.keyword = word
      this.onSearch()
    },
    // 退出结果态：回发现页三大块
    exitSearch() {
      this.searchMode = false
      this.keyword = ''
      this.searchResults = []
    },
    // ==================== 最近搜索 ====================
    // 历史词读取（storage 键 search_history，脏数据兜底空数组；与首页同键同格式）
    loadSearchHistory() {
      try {
        const cached = uni.getStorageSync(SEARCH_HISTORY_KEY)
        const list = typeof cached === 'string' ? JSON.parse(cached) : cached
        this.searchHistory = Array.isArray(list) ? list.filter(w => typeof w === 'string' && w) : []
      } catch (e) {
        this.searchHistory = []
      }
    },
    // 历史词写入：去重置顶、最多 10 条；存储异常静默（不影响搜索本身）
    saveSearchHistory(word) {
      const list = this.searchHistory.filter(w => w !== word)
      list.unshift(word)
      this.searchHistory = list.slice(0, SEARCH_HISTORY_MAX)
      try {
        uni.setStorageSync(SEARCH_HISTORY_KEY, JSON.stringify(this.searchHistory))
      } catch (e) {
        // 存储失败静默降级：本次会话内存态仍可用
      }
    },
    // 🗑 清空历史：居中确认框（uni.showModal，取消/确定，遮罩变暗）确认后清 storage 与内存
    onClearHistory() {
      uni.showModal({
        title: '提示',
        content: '确认删除最近搜索记录吗？',
        cancelText: '取消',
        confirmText: '确定',
        confirmColor: '#E02020',
        success: (res) => {
          if (!res.confirm) return
          this.searchHistory = []
          try { uni.removeStorageSync(SEARCH_HISTORY_KEY) } catch (e) { /* 静默 */ }
        }
      })
    },
    // ==================== 跳转 ====================
    // < 返回：正常路径由首页 navigateTo 进入必有历史栈；H5 刷新/hash 直达后页面栈仅剩 1 页，
    // uni.navigateBack 会静默无动作且不触发 fail（框架已知行为），故先判栈深：有栈才返回，无栈兜底 reLaunch 回首页
    goBack() {
      if (getCurrentPages().length > 1) {
        uni.navigateBack()
      } else {
        uni.reLaunch({ url: '/pages/index/index' })
      }
    },
    goToDetail(dish) { uni.navigateTo({ url: `/pages/detail/detail?id=${dish.id}` }) },
    // ==================== 加购/步进器（与首页同款交互） ====================
    limitOf(id) {
      const d = this.dishById[id]
      return d && d.limitBuy ? d.limitBuy : 0
    },
    // 未加购 -> 首次加入（走 addToCart normalize；商品已 decorateDish 增强）
    addDish(dish) { this.$store.commit('addToCart', dish) },
    // 步进器 "+"
    plusDish(dish) {
      if (!this.cartMap[dish.id]) { this.addDish(dish); return }
      this.increaseById(dish.id)
    },
    // 步进器 "−"：减到 0 直接移除
    minusDish(dish) { this.decreaseById(dish.id) },
    // 通用加一（榜单/结果列表/购物车弹层共用）：已达限购拦截 toast
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
    // 通用减一：减到 0 直接 removeFromCart
    decreaseById(id) {
      const item = this.cartMap[id]
      if (!item) return
      if (item.quantity <= 1) {
        this.$store.commit('removeFromCart', id)
      } else {
        this.$store.commit('updateQuantity', { dishId: id, quantity: item.quantity - 1 })
      }
    },
    // ==================== 购物车栏/弹层 ====================
    openCartPopup() { this.showCartDetail = true },
    closeCartPopup() {
      this.showCartDetail = false
      this.editingCart = false
    },
    // 清空购物车：clearCart + 二次确认，清空后退出编辑模式
    onClearCart() {
      if (this.cart.length === 0) return
      uni.showModal({
        title: '清空购物车',
        content: '确定要清空购物车中的全部商品吗？',
        confirmText: '清空',
        confirmColor: '#E02020',
        success: (res) => {
          if (!res.confirm) return
          this.$store.commit('clearCart')
          this.editingCart = false
          this.showToast('购物车已清空')
        }
      })
    },
    toggleEdit() { this.editingCart = !this.editingCart },
    toggleCheck(item) { this.$store.commit('toggleChecked', item.id) },
    toggleAll() { this.$store.commit('toggleAllChecked') },
    removeItem(id) { this.$store.commit('removeFromCart', id) },
    // 购物车行点击跳商品详情：先复位弹层开关再跳转
    goToCartItemDetail(item) {
      this.showCartDetail = false
      this.editingCart = false
      uni.navigateTo({ url: '/pages/detail/detail?id=' + item.id })
    },
    // 购物车行回查原始商品（seckill/limitBuy/soldText/bgColor 在契约购物车项上没有，需回查）
    dishOf(item) { return this.dishById[item.id] || {} },
    // 购物车行 [秒] 标判定：优先回查商品，回查不到按 unitSave>1 兜底
    isSeckillItem(item) {
      const d = this.dishOf(item)
      if (d && typeof d.seckill === 'boolean') return d.seckill
      return item.unitSave > 1
    },
    // 折扣标签：按 unitSave/originalPrice 换算，算不出返回空串省略
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
    // 合计栏"立即支付"：校验同首页，通过后走"跳回首页打开确认订单"链路
    payFromCart() {
      if (this.cart.length === 0) { this.showToast('请先添加商品'); return }
      if (this.checkedCount === 0) { this.showToast('请先勾选商品'); return }
      this.closeCartPopup()
      this.goPayFromSearch()
    },
    // ==================== 去支付：跳回首页打开确认订单 ====================
    // 搜索页无确认订单弹窗：写 storage 标志 openCheckout=1 后 reLaunch 首页，
    // 首页 onShow 读取该标志（购物车非空）打开确认订单弹窗并用后即清
    goPayFromSearch() {
      // 游客下单拦截：加购不拦，结算未登录先引导去独立登录页（带 redirect 回本页续走）；
      // 未登录判定与首页购物车弹层一致（!user.token）
      if (!this.user.token) {
        uni.showModal({
          title: '登录提示',
          content: '登录后即可下单，是否前往登录？',
          confirmText: '去登录',
          cancelText: '再逛逛',
          success: (r) => {
            if (r.confirm) uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/search/search') })
          }
        })
        return
      }
      try { uni.setStorageSync('openCheckout', '1') } catch (e) {
        // 存储异常静默：标志缺失时首页不弹，购物车数据仍全局共享不丢失
      }
      uni.reLaunch({ url: '/pages/index/index' })
    },
    // ==================== 修改提货人弹窗（与首页同款） ====================
    openPersonPopup() {
      this.formPersonName = this.pickupPerson.name
      this.formPersonPhone = this.pickupPerson.phone
      this.showPersonPopup = true
    },
    savePerson() {
      const name = this.formPersonName.trim()
      const phone = this.formPersonPhone.trim()
      if (!name) { this.showToast('请输入提货人姓名'); return }
      if (!/^1[3-9]\d{9}$/.test(phone)) { this.showToast('请输入正确的手机号'); return }
      this.$store.commit('setPickupPerson', { name, phone })
      this.showPersonPopup = false
      this.showToast('保存成功')
    },
    // ==================== H5 榜单 PC 鼠标拖拽滚动 ====================
    // #ifdef H5
    // 页面级滚轮兜底：html,body overflow:hidden 后页面滚轮失效，手动驱动 body 滚动；
    // 购物车弹层/提货人弹窗打开时不拦截（弹层内部自滚）
    onPageWheel(e) {
      if (this.showCartDetail || this.showPersonPopup) return
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    },
    // PC 鼠标没有原生"按住拖动滚动"：pointerdown 委托接管榜单区拖拽（触屏不进入，仍走原生触摸滚动）
    onRankPointerDown(e) {
      if (e.pointerType !== 'mouse' || e.button !== 0) return // 仅左键；触屏/触控笔走 scroll-view 原生手势，避免双滚冲突
      const t = e.target
      if (!t || typeof t.closest !== 'function') return // target 可能为文本节点
      const scroller = t.closest('.rank-scroll')
      if (!scroller) return
      // 真实滚动容器是内层 .uni-scroll-view，取内容超宽（可滚）的那个
      const pane = Array.from(scroller.querySelectorAll('.uni-scroll-view'))
        .find(el => el.scrollWidth > el.clientWidth)
      if (!pane) return
      const startX = e.clientX
      const startY = e.clientY
      const startLeft = pane.scrollLeft
      let lastY = startY
      let dragged = false
      const onMove = (ev) => {
        const dx = ev.clientX - startX
        const dy = ev.clientY - startY
        if (Math.abs(dy) > Math.abs(dx)) {
          // 主轴纵向：按 dy 增量手动滚页面（上拖 scrollTop 增大、下拖减小，方向与手指一致）
          document.body.scrollTop -= ev.clientY - lastY
          lastY = ev.clientY
          if (Math.abs(dy) > 4) dragged = true
        } else {
          if (Math.abs(dx) > 4) dragged = true
          pane.scrollLeft = startLeft - dx
        }
      }
      const onUp = () => {
        this.unbindRankDrag()
        if (!dragged) return
        // 拖拽后吞掉随后一次 click，防止误触"加入购物车"或跳详情
        const swallow = (ce) => { ce.stopPropagation(); ce.preventDefault() }
        document.addEventListener('click', swallow, { capture: true, once: true })
        setTimeout(() => { document.removeEventListener('click', swallow, true) }, 200)
      }
      this._rankDrag = { onMove, onUp } // 非响应式临时引用，卸载时兜底清理
      document.addEventListener('pointermove', onMove)
      document.addEventListener('pointerup', onUp)
    },
    // 清理挂在 document 上的 move/up 临时监听
    unbindRankDrag() {
      const drag = this._rankDrag
      if (!drag) return
      document.removeEventListener('pointermove', drag.onMove)
      document.removeEventListener('pointerup', drag.onUp)
      this._rankDrag = null
    }
    // #endif
  }
}
</script>

<style scoped>
/* ==================== 独立搜索页 — 多多买菜风格（购物车/商品卡样式复制自首页，scoped 隔离同名类不冲突） ==================== */

.page-container {
  min-height: 100vh;
  background-color: #F5F5F5;
}

/* ==================== 顶部白色导航（fixed 常驻：< 返回 + 浅灰描边圆角搜索框 + 红字"搜索"；底部极浅灰发丝线分隔） ==================== */
.nav-bar {
  position: fixed; top: 0; left: 0; right: 0; z-index: 90;
  display: flex; align-items: center; gap: 16rpx;
  padding: 16rpx 24rpx;
  padding-top: calc(16rpx + env(safe-area-inset-top));
  background-color: #FFFFFF;
  border-bottom: 1rpx solid #F0F0F0;
}
.nav-back {
  flex-shrink: 0; width: 48rpx; height: 64rpx;
  display: flex; align-items: center; justify-content: center;
}
.nav-back-arrow { font-size: 44rpx; font-weight: 700; color: #333333; line-height: 1; }
/* 浅灰描边圆角胶囊：高 64rpx 圆角 40rpx（border-box 描边不增高），内排 红放大镜 + input(flex:1) + 竖分隔线 + 红字"搜索" */
.nav-search-box {
  flex: 1; min-width: 0;
  box-sizing: border-box;
  display: flex; align-items: center; gap: 10rpx;
  height: 64rpx; padding: 0 24rpx;
  background-color: #FFFFFF; border: 1rpx solid #EEEEEE; border-radius: 40rpx;
  overflow: hidden;
}
/* 红色放大镜：CSS 绘制（红圆环 + 45° 手柄），白底上比 emoji 醒目且各端渲染一致 */
.nav-search-icon {
  position: relative; flex-shrink: 0;
  width: 24rpx; height: 24rpx;
  box-sizing: border-box;
  border: 4rpx solid #E02020; border-radius: 50%;
}
.nav-search-icon::after {
  content: '';
  position: absolute; right: -6rpx; bottom: -5rpx;
  width: 12rpx; height: 4rpx;
  background-color: #E02020; border-radius: 2rpx;
  transform: rotate(45deg);
}
.nav-search-input {
  flex: 1; min-width: 0; height: 64rpx; line-height: 64rpx;
  font-size: 26rpx; color: #333333;
}
.nav-search-divider {
  flex-shrink: 0;
  width: 1rpx; height: 32rpx;
  background-color: #EEEEEE;
}
.nav-search-go {
  flex-shrink: 0; align-self: stretch;
  display: flex; align-items: center;
  padding: 0 20rpx;
}
.nav-search-go-text { font-size: 28rpx; font-weight: 700; color: #E02020; }

/* 内容区：顶部让位 fixed 导航（96rpx + 安全区 + 16rpx 间距）；底部默认零留白（空车无购物车栏时最后一张榜单卡紧贴页底） */
.page-body {
  padding: calc(112rpx + env(safe-area-inset-top)) 0 0;
}
/* 购物车栏在场（非空车，与 cart-bar 的 v-if 同源）时底部留出栏高 120rpx + 20rpx 呼吸，避免固定栏遮住最后内容 */
.page-body-with-bar { padding-bottom: 140rpx; }

/* ==================== 发现页三大块通用（白卡圆角块） ==================== */
.ss-block {
  background-color: #FFFFFF; border-radius: 16rpx;
  margin: 20rpx 24rpx 0;
  padding: 24rpx;
}
.ss-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.ss-title-row { display: flex; align-items: center; gap: 8rpx; }
.ss-title-icon { font-size: 26rpx; }
.ss-title { font-size: 28rpx; font-weight: 700; color: #333333; }
.ss-trash { font-size: 30rpx; padding: 4rpx 8rpx; line-height: 1; }
.ss-refresh { display: flex; align-items: center; gap: 6rpx; padding: 4rpx 0; }
.ss-refresh-icon { font-size: 24rpx; }
.ss-refresh-text { font-size: 22rpx; color: #999999; }

/* 胶囊组通用 */
.ss-chips { display: flex; flex-wrap: wrap; gap: 16rpx; }
/* a. 最近搜索词胶囊 */
.ss-chip {
  max-width: 100%; box-sizing: border-box;
  background-color: #F5F5F5; border-radius: 28rpx;
  padding: 10rpx 26rpx;
}
.ss-chip-text {
  font-size: 24rpx; color: #333333;
  display: inline-block; max-width: 420rpx;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* b. 搜索发现词胶囊：emoji + 词 + 可选红字"热"角标 */
.sd-chip {
  position: relative;
  display: flex; align-items: center; gap: 6rpx;
  background-color: #F5F5F5; border-radius: 28rpx;
  padding: 10rpx 24rpx;
}
.sd-chip-emoji { font-size: 24rpx; }
.sd-chip-text {
  font-size: 24rpx; color: #333333;
  display: inline-block; max-width: 320rpx;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 红字"热"角标：右上角小圆牌（带小尾角，多多买菜样式） */
.sd-chip-hot {
  position: absolute; top: -10rpx; right: -8rpx;
  min-width: 28rpx; height: 28rpx; line-height: 28rpx;
  box-sizing: border-box; padding: 0 6rpx;
  background-color: #E02020; color: #FFFFFF;
  font-size: 16rpx; font-weight: 700; text-align: center;
  border-radius: 14rpx 14rpx 14rpx 2rpx;
}

/* ==================== c. 地区热卖榜单（横向滑动两张 85vw 榜单卡） ==================== */
.ss-rank { padding: 24rpx 0 0; } /* 底部留白收敛：榜单卡下不再垫 24rpx 白边，滚动到底时卡紧贴页底 */
.ss-rank .ss-head { padding: 0 24rpx; }
.rank-scroll { white-space: nowrap; cursor: grab; } /* PC 鼠标拖拽手型提示（触屏不受影响） */
/* 隐藏横向滚动条（与首页秒杀区同款处理） */
.rank-scroll ::v-deep .uni-scroll-view::-webkit-scrollbar { display: none; }
.rank-card {
  display: inline-block; width: 85vw; margin-left: 24rpx;
  box-sizing: border-box;
  background-color: #FFFFFF; border: 1rpx solid #EEEEEE; border-radius: 16rpx;
  vertical-align: top; white-space: normal; overflow: hidden;
  box-shadow: 0 2rpx 12rpx rgba(0,0,0,.04);
}
.rank-card:last-child { margin-right: 24rpx; }
.rank-card-head {
  display: flex; align-items: center; gap: 8rpx;
  padding: 20rpx 24rpx; min-height: 48rpx; box-sizing: border-box;
}
.rank-card-title { font-size: 28rpx; font-weight: 700; color: #333333; }
.rank-card-sep { font-size: 24rpx; color: #DDDDDD; }
.rank-card-sub { font-size: 22rpx; color: #999999; }
/* 榜单 item：排名角标 + 图 + 标题/已售/价格 + 加购按钮 */
.rank-item {
  display: flex; align-items: center; gap: 16rpx;
  padding: 16rpx 24rpx;
  border-top: 1rpx solid #F7F7F7;
}
/* 排名角标：1 金 2 银 3 铜，4+ 灰 */
.rank-no {
  flex-shrink: 0; width: 36rpx; height: 36rpx; line-height: 36rpx;
  border-radius: 8rpx; background-color: #F0F0F0; color: #CCCCCC;
  font-size: 20rpx; font-weight: 700; text-align: center;
}
.rank-no-1 { background-color: #FFB800; color: #FFFFFF; }
.rank-no-2 { background-color: #BFC5CD; color: #FFFFFF; }
.rank-no-3 { background-color: #D2955C; color: #FFFFFF; }
.rank-img {
  flex-shrink: 0; width: 120rpx; height: 120rpx;
  border-radius: 12rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.rank-photo { width: 100%; height: 100%; display: block; }
.rank-emoji { font-size: 56rpx; }
.rank-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.rank-name {
  font-size: 26rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.rank-sold { font-size: 20rpx; color: #999999; }
.rank-bottom { display: flex; align-items: center; justify-content: space-between; }
.rank-price-line { display: flex; align-items: baseline; }
.rank-symbol { font-size: 20rpx; font-weight: 700; color: #E02020; }
.rank-price { font-size: 32rpx; font-weight: 700; color: #E02020; line-height: 1; }
.rank-action { flex-shrink: 0; }
.rank-add {
  min-width: 132rpx; height: 52rpx; padding: 0 18rpx; box-sizing: border-box;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #FF5A3C, #E02020);
  border-radius: 26rpx;
}
.rank-add text { font-size: 20rpx; font-weight: 700; color: #FFFFFF; }
.rank-empty { padding: 40rpx 0; text-align: center; }
.rank-empty text { font-size: 24rpx; color: #CCCCCC; }

/* ==================== 榜单加载失败错误态 ==================== */
.error-state { display: flex; flex-direction: column; align-items: center; gap: 20rpx; padding: 120rpx 0; }
.error-text { font-size: 26rpx; color: #999999; }
.error-retry {
  font-size: 26rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 32rpx;
  padding: 10rpx 44rpx;
}

/* ==================== 搜索结果（同首页商品卡样式） ==================== */
.search-result-head { display: flex; align-items: center; padding: 24rpx 24rpx 8rpx; }
.srh-text { flex: 1; min-width: 0; font-size: 24rpx; color: #666666; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.srh-cancel { flex-shrink: 0; margin-left: 16rpx; font-size: 26rpx; font-weight: 600; color: #E02020; padding: 6rpx 8rpx; }
.dish-list { padding: 0 24rpx; }
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
.empty-state { display: flex; align-items: center; justify-content: center; padding: 120rpx 0; }
.empty-state text { font-size: 26rpx; color: #CCCCCC; }

/* ==================== 步进器（榜单/结果列表/购物车弹层共用，与首页同款） ==================== */
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

/* ==================== 底部购物车栏（复刻首页 cart-bar：🛒+角标 | 价格 | 去支付） ==================== */
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
/* uniapp button 覆盖默认样式（去边框/圆角/内边距，::after 伪元素边框一并去掉） */
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

/* ==================== 弹窗通用 ==================== */
.popup-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0,0,0,.5); z-index: 200;
  display: flex; align-items: flex-end; justify-content: center;
}
.popup-mask-center { align-items: center; z-index: 300; }

/* ==================== 购物车弹层（复刻首页 cart-popup，80vh） ==================== */
.cart-popup {
  width: 100%;
  background-color: #FFFFFF; border-radius: 24rpx 24rpx 0 0;
  height: 80vh;
  display: flex; flex-direction: column;
  overflow: hidden;
}
.service-bar {
  flex-shrink: 0;
  display: flex; justify-content: space-around; align-items: center;
  background-color: #F5F5F5; padding: 14rpx 24rpx;
}
.service-item { display: flex; align-items: center; gap: 4rpx; font-size: 20rpx; color: #666666; }
.service-check { color: #07C160; font-weight: 700; }
.pickup-row {
  flex-shrink: 0;
  display: flex; align-items: center; gap: 10rpx;
  padding: 20rpx 24rpx 4rpx;
}
.pr-icon { font-size: 28rpx; flex-shrink: 0; }
.pr-name {
  font-size: 26rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.pr-phone { font-size: 24rpx; color: #666666; }
.pr-arrow { margin-left: auto; flex-shrink: 0; font-size: 30rpx; color: #CCCCCC; }
.cart-title-row {
  flex-shrink: 0;
  display: flex; align-items: center; justify-content: space-between;
  padding: 20rpx 24rpx 4rpx;
}
.cart-title { font-size: 30rpx; font-weight: 700; color: #333333; }
.cart-title-right { display: flex; align-items: center; gap: 28rpx; }
.cart-clear { font-size: 24rpx; font-weight: 600; color: #666666; }
.cart-edit { font-size: 24rpx; font-weight: 600; color: #E02020; }
.cart-close { font-size: 32rpx; color: #CCCCCC; }
.helper-bar {
  flex-shrink: 0;
  display: flex; align-items: center; gap: 12rpx;
  margin: 12rpx 24rpx 0; padding: 10rpx 20rpx;
  background-color: #FFF7F0; border-radius: 10rpx;
}
.hb-title { font-size: 22rpx; font-weight: 700; color: #E02020; }
.hb-divider { font-size: 22rpx; color: #F0D8C0; }
.hb-text { font-size: 22rpx; color: #E0662A; }
.cart-items { flex: 1; min-height: 0; padding: 0 24rpx; box-sizing: border-box; }
.cart-items-empty { display: flex; align-items: center; justify-content: center; }
.cart-item {
  display: flex; align-items: center; gap: 16rpx;
  padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5;
}
.check-circle {
  width: 36rpx; height: 36rpx; flex-shrink: 0;
  border: 2rpx solid #DDDDDD; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  background-color: #FFFFFF;
}
.check-circle.checked { border: none; background-color: #E02020; }
.check-circle text { font-size: 20rpx; font-weight: 700; color: #FFFFFF; line-height: 1; }
.ci-main { flex: 1; min-width: 0; display: flex; align-items: center; gap: 16rpx; }
.ci-img {
  width: 120rpx; height: 120rpx; flex-shrink: 0;
  border-radius: 8rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.ci-img text { font-size: 56rpx; }
.ci-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.ci-name-row { display: flex; align-items: center; min-width: 0; }
.ci-seckill-tag {
  flex-shrink: 0; margin-right: 8rpx;
  font-size: 16rpx; font-weight: 700; color: #FFFFFF;
  background-color: #E02020; border-radius: 4rpx; padding: 0 6rpx;
  line-height: 26rpx;
}
.ci-name {
  flex: 1; min-width: 0;
  font-size: 26rpx; font-weight: 600; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ci-desc-row { display: flex; align-items: center; gap: 12rpx; overflow: hidden; white-space: nowrap; }
.ci-restore { font-size: 20rpx; font-weight: 600; color: #E02020; flex-shrink: 0; }
.ci-sold { font-size: 20rpx; color: #999999; flex-shrink: 0; }
.ci-limit { font-size: 20rpx; color: #999999; }
.ci-price-row { display: flex; align-items: baseline; gap: 12rpx; }
.ci-original { font-size: 20rpx; color: #BBBBBB; text-decoration: line-through; }
.ci-price-line { display: flex; align-items: baseline; }
.ci-symbol { font-size: 18rpx; font-weight: 700; color: #E02020; }
.ci-price { font-size: 30rpx; font-weight: 700; color: #E02020; line-height: 1; }
.ci-discount {
  font-size: 18rpx; font-weight: 600; color: #E02020;
  background-color: #FFECE8; border-radius: 6rpx; padding: 2rpx 8rpx;
}
.ci-action { flex-shrink: 0; }
.ci-delete {
  display: inline-block;
  font-size: 22rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 24rpx;
  padding: 6rpx 20rpx;
}
.cart-empty { display: flex; flex-direction: column; align-items: center; gap: 16rpx; }
.cart-empty-icon { font-size: 88rpx; opacity: .4; }
.cart-empty-text { font-size: 26rpx; color: #999999; }
.cart-total-bar {
  flex-shrink: 0;
  display: flex; align-items: center;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  border-top: 1rpx solid #F0F0F0;
}
.check-all { display: flex; align-items: center; gap: 10rpx; flex-shrink: 0; }
.check-all-text { font-size: 24rpx; color: #333333; }
.ctb-price { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: flex-end; gap: 2rpx; margin-right: 16rpx; }
.ctb-price-line { display: flex; align-items: baseline; }
.ctb-price-num { font-size: 36rpx; font-weight: 700; color: #E02020; line-height: 1; }
.ctb-saving { font-size: 20rpx; color: #999999; }
/* uniapp button 覆盖默认样式（含 ::after 伪元素边框） */
.btn-pay-popup {
  flex-shrink: 0; margin: 0;
  min-width: 200rpx; height: 76rpx; line-height: 76rpx;
  background-color: #E02E24; color: #FFFFFF;
  font-size: 26rpx; font-weight: 700;
  border: none; border-radius: 38rpx; padding: 0 36rpx;
  text-align: center;
}
.btn-pay-popup::after { border: none; }
/* 空车时支付按钮置灰禁用（点击仍走 payFromCart 的 toast 提示） */
.btn-pay-popup.btn-pay-disabled { background-color: #CCCCCC; }

/* ==================== 修改提货人弹窗（与首页同款） ==================== */
.person-popup {
  width: 590rpx;
  background-color: #FFFFFF; border-radius: 24rpx;
  padding: 40rpx 32rpx 32rpx;
  position: relative;
}
.person-close { position: absolute; top: 20rpx; right: 24rpx; font-size: 30rpx; color: #CCCCCC; padding: 8rpx; }
.person-title { display: block; text-align: center; font-size: 30rpx; font-weight: 700; color: #333333; margin-bottom: 28rpx; }
.btn-save-person {
  width: 100%; height: 80rpx; line-height: 80rpx;
  margin-top: 8rpx; padding: 0;
  background-color: #E02020; color: #FFFFFF;
  font-size: 28rpx; font-weight: 700;
  border: none; border-radius: 44rpx;
}
.btn-save-person::after { border: none; }
.form-group { margin-bottom: 24rpx; }
.form-label { display: block; font-size: 24rpx; font-weight: 600; color: #666666; margin-bottom: 12rpx; }
.form-input {
  width: 100%; height: 80rpx; line-height: 80rpx;
  background-color: #F5F5F5; border-radius: 12rpx; padding: 0 20rpx;
  font-size: 28rpx; color: #333333; box-sizing: border-box;
}
</style>
