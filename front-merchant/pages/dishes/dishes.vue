<template>
  <view class="page-container">
    <!-- ==================== 1. 筛选区（关键字 + 三 tab + 类目） ====================
             三个条件都作为 query 交列表接口，本页不做二次过滤：归属集合与筛选口径由服务端唯一裁决
             （tab/category/keyword 的宽松语义见 F-13.6 边界 14，前端不复制一份判定逻辑） -->
    <view class="card filter-card">
      <view class="search-row">
        <input
          class="search-input"
          v-model="keyword"
          placeholder="搜索商品名称"
          placeholder-style="color:#BBBBBB;"
          confirm-type="search"
          @confirm="applyFilters"
        />
        <text class="search-go" @click="applyFilters">搜索</text>
        <text v-if="keyword" class="search-clear" @click="clearKeyword">清空</text>
      </view>
      <!-- 三 tab 母版照 front/pages/my-coupons/my-coupons.vue:19-29（只读参照，键值直接对齐后端 tab 参数） -->
      <view class="dish-tabs">
        <view
          class="dish-tab"
          v-for="t in tabs"
          :key="t.key"
          :class="{ active: tab === t.key }"
          @click="switchTab(t.key)"
        >
          <text>{{ t.label }}</text>
        </view>
      </view>
      <!-- 类目下拉：11 类只选不建（F-13.4），取值域来自字典 dish_category 组，页内不硬编码一份枚举 -->
      <picker class="cate-picker" mode="selector" :range="categoryLabels" :value="categoryIndex" @change="onCategoryChange">
        <view class="picker-row">
          <text class="picker-label">类目</text>
          <text class="picker-value">{{ categoryLabels[categoryIndex] }}</text>
          <text class="picker-arrow">›</text>
        </view>
      </picker>
      <!-- 类目可选项的来源不对劲时（退化到内置分类 / 读取失败）追加一行说明并可点重试：
           列表本身不受影响，故不切整页错误态，但也不能静默（商家会以为分类就这么几个） -->
      <text v-if="categoriesHint" class="cate-fail" @click="loadCategories">{{ categoriesHint }}</text>
    </view>

    <!-- ==================== 2. 加载中（首屏与筛选切换后的重拉都走这一态，避免旧列表闪现成「筛选结果」） ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载商品…</text>
    </view>

    <!-- ==================== 3. 错误态可重试（≠ 空态：空态是服务端确认「一条都没有」，错误态是根本没拿到数据） ====================
             403「无权限访问」也落这一态并原样透出后端 message：本页只由工作台入口进来，正常走不到；
             真撞上了说明角色出了问题，给重试入口比给一堆猜测文案有用 -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-text">{{ errorText || '商品加载失败，请检查网络' }}</text>
        <text class="error-retry" @click="loadDishes()">点击重试</text>
      </view>
    </view>

    <!-- ==================== 4. 就绪：列表 / 空态 ==================== -->
    <template v-else>
      <view v-if="dishes.length > 0" class="card list-card">
        <view class="dish-item" v-for="d in dishes" :key="d.id" @click="goEdit(d)">
          <!-- 图：merchantFileUrl 归一化后展示，加载失败/为空回退 emoji 色块——与 C 端 index.vue:118 同一套口径 -->
          <view class="dish-img" :style="{ backgroundColor: d.bgColor || '#F5F5F5' }">
            <image
              v-if="d.image && !imgFail[d.id]"
              class="dish-photo"
              :src="previewUrl(d.image)"
              mode="aspectFill"
              @error="onImgFail(d.id)"
            />
            <text v-else class="dish-emoji">{{ d.emoji || '🥬' }}</text>
          </view>
          <view class="dish-body">
            <view class="dish-title-row">
              <text class="dish-name">{{ d.name }}</text>
              <text class="dish-badge" :class="isOnSale(d) ? 'badge-on' : 'badge-off'">{{ isOnSale(d) ? '在售' : '已下架' }}</text>
            </view>
            <text class="dish-meta">{{ categoryText(d.category) }} · 库存 {{ d.stock }} · 限购 {{ d.limitBuy }}{{ d.unit }}</text>
            <view class="dish-price-row">
              <text class="price-symbol">¥</text>
              <text class="price-num">{{ fmtPrice(d.price) }}</text>
              <text class="price-unit">/{{ d.unit }}</text>
            </view>
          </view>
          <text class="cell-arrow">›</text>
        </view>
      </view>

      <!-- 空态分两种：带筛选条件的空（引导清筛）与一条都没有的空（引导新增）——
           后者若也只说「没有商品」，商家会以为搜索坏了，不会想到自己还没上新 -->
      <view v-else class="card empty-card">
        <view class="empty-row">
          <text class="empty-text">{{ hasFilter ? '没有符合条件的商品，换个筛选条件试试' : '还没有商品，先上新第一条吧' }}</text>
        </view>
        <text v-if="hasFilter" class="guide-link" @click="resetFilters">清空筛选条件</text>
        <button v-else class="btn-main" @click="goCreate"><text>新增商品</text></button>
      </view>
    </template>

    <!-- 常驻新增入口：列表为空时上方卡里也有一个，两处都能进（滚动到列表中部时不必回顶部找按钮） -->
    <view class="fab" @click="goCreate"><text class="fab-text">＋ 新增商品</text></view>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家端商品列表页（G-02 §F-13.4）：三 tab + 类目 + 关键字，全部条件交 GET /api/merchant/dishes，
// 本页只做渲染不做过滤；归属由服务端 attribute 解析，页面不发任何 merchantId（R2/R3）。
// 数据加载纪律照 workspace.vue：onShow 每次重拉（编辑页改完回页即反映最新值），带 in-flight 守卫防连点。
// 401 由 api 层统一清态回登录页，本页不再处理跳转；403 透 message 不清 token（handleEnvelope 语义）。
import { getMerchantDishes, getDishCategories, getMerchantMe, merchantFileUrl } from '@/api/index.js'
import '@/styles/common.css'

export default {
  data() {
    return {
      // 三 tab：key 直接就是后端 tab 参数值，不做一层映射翻译（多一层命名就多一处漂移面）
      tabs: [
        { key: 'all', label: '全部' },
        { key: 'on_sale', label: '在售' },
        { key: 'off_sale', label: '已下架' }
      ],
      tab: 'all',
      // keyword = 输入框实时值，appliedKeyword = 已提交查询的值：
      // 只绑一个的话每次按键都会打一次列表接口（读限流 30 次/分，边打字边查很快就 429）
      keyword: '',
      appliedKeyword: '',
      categoryIndex: 0, // picker 下标；0 固定是「全部类目」，真实分类从 1 开始
      categories: [],   // 字典 dish_category 映射后的 [{code,name}]
      // 字典拉取失败原文（后端 message）：非空即在下拉行渲染一行可点重试的提示，不静默降级
      categoriesError: '',
      // true = 字典上游不可达、当前下拉是 api 层的内置 11 类（列表照常可选，但得让商家知道来源）
      categoriesDegraded: false,
      dishes: [],
      // 三态互斥（同 workspace.vue 的 viewState 纪律，避免多面布尔标志打架留脏态）：loading | error | ready
      viewState: 'loading',
      errorText: '',
      // 图片加载失败标记（按 dish.id）：与 C 端 index.vue:521/770 同款——失败才回退 emoji，
      // 成功过的一次性标记在每次重拉后清掉，否则商家换了好图仍显示 emoji
      imgFail: {}
    }
  },
  computed: {
    // picker 的 range：首项恒为「全部类目」，其后接字典 11 类的中文名
    categoryLabels() {
      return ['全部类目'].concat(this.categories.map((c) => c.name))
    },
    // 当前生效的类目 code（'' 表示不带该筛选）
    category() {
      if (this.categoryIndex <= 0) return ''
      const c = this.categories[this.categoryIndex - 1]
      return c ? c.code : ''
    },
    // 是否带着任一筛选条件（决定空态文案是「清筛引导」还是「新增引导」）
    hasFilter() {
      return !!(this.appliedKeyword || this.category || this.tab !== 'all')
    },
    // 下拉提示行：退化成内置分类与读取失败各一句（都保留点击重试），正常字典态返回空串不出这一行
    categoriesHint() {
      if (this.categoriesDegraded) return '类目字典暂不可用，当前为内置分类，点击重试'
      return this.categoriesError ? '类目加载失败：' + this.categoriesError + '，点击重试' : ''
    }
  },
  onLoad() {
    this.loadCategories()
  },
  onShow() {
    // 每次进入重拉（编辑页保存/上下架/改库存后回页即可见），首屏也由这里发起
    this.loadDishes()
    this.syncMerchantSnapshot()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动
    // （照 workspace.vue 现有实现逐字搬，本页同样有长列表需要滚）
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
    // 类目字典：失败只让下拉退化（列表本身不受影响），故不切换整页 viewState，给一行可点的重试提示
    async loadCategories() {
      try {
        const res = await getDishCategories()
        const list = (res && Array.isArray(res.data)) ? res.data : []
        // definition 形如 'code:meat'，code 即 dish.category 的取值（与 C 端 index.vue codeOf 同一口径）
        this.categories = list
          .map((d) => ({ code: String((d && d.definition) || '').replace(/^code:/, '').trim(), name: (d && d.word) || '' }))
          .filter((c) => c.code)
        // degraded = 这一批是 api 层的内置兜底（字典上游不可达），下拉照常可选但须让商家看见来源
        this.categoriesDegraded = !!(res && res.degraded)
        this.categoriesError = ''
      } catch (e) {
        this.categoriesDegraded = false
        this.categoriesError = (e && e.message) || '未知错误'
      }
    },
    // store 的 me 快照缺失或停在非 active 时补拉一次（编辑页的写闸门读这份快照）：
    // 平台恢复营业后不必先绕一次工作台才解除假锁。失败静默——快照只是预判，
    // 真被停业的写请求照样吃 403，这一路拉不到不该打扰列表页
    async syncMerchantSnapshot() {
      const cached = this.$store.state.merchant
      if (cached && cached.status === 'active') return
      if (this._syncingMe) return
      this._syncingMe = true
      try {
        const res = await getMerchantMe()
        const me = (res && res.data) || null
        if (me && me.role === 'merchant') this.$store.commit('setMerchant', me)
      } catch (e) {
        // 拉不到就维持原快照，本页无用户可见副作用
      } finally {
        this._syncingMe = false
      }
    },
    // GET /api/merchant/dishes：200 → ready（含空数组）；403/400/500/网络异常 → error 可重试；401 由 api 层回登录页
    // @param {boolean} [resetView] 筛选条件变了要立刻退回加载态，让旧列表让位（否则会被读成新条件的结果）；
    //                              onShow 的静默重拉不传，已有列表时保持可见，避免每次回页闪一次白卡
    async loadDishes(resetView) {
      // 并发守卫：onShow 重拉 + tab 连点 + 错误卡重试可叠加，慢失败不能盖掉快成功
      if (this._loadingList) {
        // 在途期间筛选又变了：不能吞掉这一次（否则 tab 已切、列表还是上一批），排队等当前请求收尾后按最新条件重拉
        this._reloadQueued = true
        return
      }
      this._loadingList = true
      if (resetView || this.viewState !== 'ready') this.viewState = 'loading'
      try {
        const res = await getMerchantDishes({ tab: this.tab, category: this.category, keyword: this.appliedKeyword })
        this.dishes = (res && Array.isArray(res.data)) ? res.data : []
        // 每次成功都清失败标记：图换成功了还能继续显示图，不必刷新页面才恢复
        this.imgFail = {}
        this.errorText = ''
        this.viewState = 'ready'
      } catch (e) {
        if (e && e.code === 401) {
          // 跳转与清态都由请求层做完，这里只停在加载态，不闪现错误卡
          this.viewState = 'loading'
          return
        }
        this.errorText = (e && e.message) || ''
        this.viewState = 'error'
      } finally {
        this._loadingList = false
        if (this._reloadQueued) {
          this._reloadQueued = false
          this.loadDishes(true)
        }
      }
    },
    // ==================== 筛选交互 ====================
    applyFilters() {
      this.appliedKeyword = (this.keyword || '').trim()
      this.loadDishes(true)
    },
    clearKeyword() {
      this.keyword = ''
      this.appliedKeyword = ''
      this.loadDishes(true)
    },
    switchTab(key) {
      if (this.tab === key) return
      this.tab = key
      this.loadDishes(true)
    },
    onCategoryChange(e) {
      const idx = Number(e && e.detail && e.detail.value)
      this.categoryIndex = Number.isFinite(idx) ? idx : 0
      this.loadDishes(true)
    },
    resetFilters() {
      this.tab = 'all'
      this.categoryIndex = 0
      this.keyword = ''
      this.appliedKeyword = ''
      this.loadDishes(true)
    },
    // ==================== 渲染辅助 ====================
    // 商品图预览：入库值是 C 端静态根语义的 /static/images/uploads/<名>，商家端 origin 下取不到，
    // 归一化统一在 api 层 merchantFileUrl() 做（§3.1 唯一出口，页内不自拼 BASE_URL）
    previewUrl(u) {
      return merchantFileUrl(u)
    },
    onImgFail(id) {
      // 整体替换而不是 this.imgFail[id]=true：Vue3 下新增键能触发响应，但沿用 C 端 index.vue:770 的写法，
      // 两端同一姿势便于对照
      this.imgFail = { ...this.imgFail, [id]: true }
    },
    isOnSale(d) {
      return Number(d && d.onSale) === 1
    },
    categoryText(code) {
      const hit = this.categories.filter((c) => c.code === code)[0]
      // 字典里查不到的 code（脏值/历史分类）原样展示，不替后端猜中文名
      return hit ? hit.name : (code || '未分类')
    },
    // 价格两位小数（与 C 端 index.vue:774 fmtPrice 同口径，同一份数据两端显示不一致会被当成数据问题）
    fmtPrice(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n.toFixed(2) : '0.00'
    },
    // ==================== 跳转 ====================
    goEdit(d) {
      uni.navigateTo({ url: '/pages/dish-edit/dish-edit?id=' + d.id })
    },
    goCreate() {
      uni.navigateTo({ url: '/pages/dish-edit/dish-edit' })
    },
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
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.empty-row/.btn-main/.guide-link/.cell-arrow 取自 common.css） */

/* ==================== 1. 筛选卡 ==================== */
.filter-card { margin-top: 24rpx; padding: 20rpx 24rpx; }
.search-row { display: flex; align-items: center; gap: 16rpx; }
.search-input {
  flex: 1; min-width: 0; height: 72rpx; line-height: 72rpx;
  background-color: #F5F5F5; border-radius: 999rpx;
  padding: 0 24rpx; font-size: 26rpx; color: #333333;
}
.search-go { flex-shrink: 0; font-size: 26rpx; font-weight: 600; color: #E02020; padding: 8rpx 4rpx; }
.search-clear { flex-shrink: 0; font-size: 24rpx; color: #BBBBBB; padding: 8rpx 4rpx; }

/* 三 tab（照用户端 my-coupons.vue:288 .coupon-tabs 手感，激活态用商家端主红） */
.dish-tabs { display: flex; gap: 12rpx; margin-top: 20rpx; }
.dish-tab {
  flex: 1; text-align: center;
  font-size: 26rpx; color: #666666;
  background-color: #F5F5F5; border-radius: 24rpx;
  padding: 12rpx 0; border: 1rpx solid #EEEEEE;
}
.dish-tab.active { color: #FFFFFF; font-weight: 700; background-color: #E02020; border-color: #E02020; }

/* 类目下拉行（picker 是原生组件，整行可点，右侧 › 复用 common.css .cell-arrow） */
/* picker 显式占满整行：H5 下它编译成自定义元素，不声明块级的话整行点击区只剩文字那么宽 */
.cate-picker { display: block; width: 100%; }
.picker-row {
  display: flex; align-items: center; gap: 12rpx;
  margin-top: 20rpx; padding-top: 20rpx;
  border-top: 1rpx solid #F5F5F5;
}
.picker-label { flex-shrink: 0; font-size: 26rpx; color: #999999; }
.picker-value {
  flex: 1; min-width: 0; text-align: right;
  font-size: 26rpx; color: #333333;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* 字典拉取失败的降级行（本页筛选退化不影响列表，故只提示不给整页错误态，可点重试） */
.cate-fail { display: block; margin-top: 12rpx; font-size: 22rpx; color: #E02020; line-height: 1.5; }

/* ==================== 2. 加载中 ==================== */
.loading-card { margin-top: 24rpx; display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }

/* ==================== 4. 商品行 ==================== */
.list-card { margin-top: 24rpx; padding: 0; overflow: hidden; }
.dish-item { display: flex; align-items: center; gap: 16rpx; padding: 24rpx; border-bottom: 1rpx solid #F5F5F5; }
.dish-item:last-child { border-bottom: none; }
.dish-img {
  width: 140rpx; height: 140rpx; flex-shrink: 0;
  border-radius: 12rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.dish-photo { width: 140rpx; height: 140rpx; }
.dish-emoji { font-size: 64rpx; line-height: 1; }
.dish-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.dish-title-row { display: flex; align-items: center; gap: 12rpx; }
.dish-name {
  flex: 1; min-width: 0;
  font-size: 30rpx; font-weight: 600; color: #333333; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.dish-badge {
  flex-shrink: 0; font-size: 20rpx; font-weight: 600; line-height: 1.4;
  border-radius: 8rpx; padding: 4rpx 12rpx;
}
.badge-on { color: #FFFFFF; background-color: #E02020; }
.badge-off { color: #999999; background-color: #F5F5F5; }
.dish-meta { font-size: 22rpx; color: #999999; line-height: 1.5; }
.dish-price-row { display: flex; align-items: baseline; }
.price-symbol { font-size: 22rpx; font-weight: 700; color: #E02020; }
.price-num { font-size: 34rpx; font-weight: 700; color: #E02020; line-height: 1; }
.price-unit { font-size: 22rpx; color: #999999; margin-left: 4rpx; }

/* ==================== 4. 空态卡 ==================== */
.empty-card { margin-top: 24rpx; display: flex; flex-direction: column; align-items: center; }
.empty-card .btn-main { margin-top: 8rpx; }

/* ==================== 浮动新增按钮（fixed 定位不随 body 滚动，两端 origin 表现一致） ==================== */
.fab {
  position: fixed; right: 32rpx; bottom: 64rpx; z-index: 10;
  height: 84rpx; line-height: 84rpx; padding: 0 36rpx;
  background-color: #E02020; color: #FFFFFF;
  border-radius: 999rpx; box-shadow: 0 4rpx 16rpx rgba(224, 32, 32, 0.3);
}
.fab-text { font-size: 28rpx; font-weight: 700; color: #FFFFFF; }

/* 底部留白：给浮动按钮让位，别让它压住最后一行的箭头 */
.page-bottom-space { height: 140rpx; }
</style>
