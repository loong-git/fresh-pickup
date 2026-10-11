<template>
  <view class="page-container">
    <!-- ==================== 0. 写闸门红条（F-13 决策④） ====================
             非 active 商家的写请求一律 403「无权限访问」（读仍可），文案优先取后端原文；
             没打过写请求时用工作台/登录页存进 store 的 me.status 预判，两种来源都只是把三个写动作禁掉 -->
    <view v-if="writeBlocked" class="card block-card">
      <text class="block-text">{{ blockReason }}</text>
    </view>

    <!-- ==================== 1. 编辑态详情加载中 / 可重试 ==================== -->
    <view v-if="viewState === 'loading'" class="card loading-card">
      <text class="loading-text">正在加载商品…</text>
    </view>
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-text">{{ errorText || '商品加载失败，请检查网络' }}</text>
        <text class="error-retry" @click="loadDish()">点击重试</text>
      </view>
    </view>

    <!-- ==================== 2. 表单 ==================== -->
    <template v-else>
      <view class="card form-card">
        <view class="field-row">
          <text class="field-label">名称</text>
          <input class="field-input" v-model="form.name" maxlength="100" placeholder="必填，如 山东红富士苹果" placeholder-style="color:#BBBBBB;" />
        </view>
        <view class="field-row">
          <text class="field-label">价格</text>
          <input class="field-input" v-model="form.price" type="digit" placeholder="必填，单位元" placeholder-style="color:#BBBBBB;" />
          <text class="field-suffix">元</text>
        </view>
        <view class="field-row">
          <text class="field-label">单位</text>
          <input class="field-input" v-model="form.unit" maxlength="20" placeholder="必填，如 斤 / 盒 / 份" placeholder-style="color:#BBBBBB;" />
        </view>
        <!-- 类目：11 类只选不建（F-13.4），取值域来自字典 dish_category，页内不硬编码枚举 -->
        <picker class="row-picker" mode="selector" :range="categoryLabels" :value="categoryIndex" @change="onCategoryChange">
          <view class="field-row">
            <text class="field-label">类目</text>
            <text class="field-picker">{{ categoryDisplay }}</text>
            <text class="cell-arrow">›</text>
          </view>
        </picker>
        <text v-if="categoriesHint" class="field-hint field-hint-warn" @click="loadCategories">{{ categoriesHint }}</text>

        <!-- 库存两态分开：新增带初始值（POST 必填），编辑只给增量（PUT 见 stock 非 null 即 400，R7'） -->
        <view v-if="!isEdit" class="field-row">
          <text class="field-label">库存</text>
          <input class="field-input" v-model="form.stock" type="number" placeholder="必填，0~99999" placeholder-style="color:#BBBBBB;" />
        </view>
        <view v-else class="field-row field-row-wrap">
          <text class="field-label">库存</text>
          <text class="stock-now">当前 {{ currentStock }}</text>
          <view class="sign-btns">
            <text class="sign-btn" :class="{ active: stockSign === 'plus' }" @click="setStockSign('plus')">＋</text>
            <text class="sign-btn" :class="{ active: stockSign === 'minus' }" @click="setStockSign('minus')">−</text>
          </view>
          <input class="field-input stock-input" v-model="stockDelta" type="number" placeholder="数量" placeholder-style="color:#BBBBBB;" />
          <button class="btn-sub" :class="{ 'btn-sub-disabled': adjusting || writeBlocked }" :disabled="adjusting || writeBlocked" @click="adjustStock">
            <text>{{ adjusting ? '调整中…' : '调整' }}</text>
          </button>
        </view>
        <text v-if="isEdit" class="field-hint">商家端只能按增减量改库存（单次 ±1000 以内），要精确改数找平台</text>

        <view class="field-row">
          <text class="field-label">限购</text>
          <input class="field-input" v-model="form.limitBuy" type="number" placeholder="必填，1~20" placeholder-style="color:#BBBBBB;" />
        </view>
        <view class="field-row">
          <text class="field-label">emoji</text>
          <input class="field-input" v-model="form.emoji" maxlength="50" placeholder="无图时 C 端显示的占位表情" placeholder-style="color:#BBBBBB;" />
        </view>
        <view class="field-row">
          <text class="field-label">背景色</text>
          <input class="field-input" v-model="form.bgColor" maxlength="20" placeholder="如 #FFF1E6" placeholder-style="color:#BBBBBB;" />
          <view class="color-chip" :style="{ backgroundColor: safeColor }"></view>
        </view>
        <view class="field-row field-row-col">
          <text class="field-label">描述</text>
          <textarea class="field-textarea" v-model="form.description" maxlength="2000" placeholder="选填，2000 字以内" placeholder-style="color:#BBBBBB;" />
        </view>

        <!-- 标签：最多 5 个、每个 ≤12 字（服务端 @Size 同口径，本地拦一句是为了不让用户白填） -->
        <view class="field-row field-row-col">
          <text class="field-label">标签</text>
          <view class="tag-list" v-if="tags.length > 0">
            <view class="tag-chip" v-for="(t, i) in tags" :key="t + '_' + i" @click="removeTag(i)">
              <text class="tag-text">{{ t }}</text>
              <text class="tag-x">×</text>
            </view>
          </view>
          <view class="tag-row">
            <input class="field-input tag-input" v-model="tagInput" maxlength="12" placeholder="如 产地直发，回车或点添加" placeholder-style="color:#BBBBBB;" @confirm="addTag" />
            <button class="btn-sub" :class="{ 'btn-sub-disabled': tags.length >= 5 }" :disabled="tags.length >= 5" @click="addTag"><text>添加</text></button>
          </view>
          <text class="field-hint">最多 5 个，每个 12 字以内；点标签可删除</text>
        </view>

        <!-- 图片：出参取 path 键入库（/static/images/uploads/<名>），预览再经 merchantFileUrl 归一化。
             取 url 键入库正是决策①要修的静默假成功——这里把入库路径显示出来，验收一眼可辨 -->
        <view class="field-row field-row-col">
          <text class="field-label">商品图</text>
          <view class="img-box" :style="{ backgroundColor: safeColor }">
            <image v-if="previewImage && !imgFail" class="img-photo" :src="previewImage" mode="aspectFill" @error="imgFail = true" />
            <text v-else class="img-emoji">{{ form.emoji || '🥬' }}</text>
          </view>
          <view class="img-btns">
            <button class="btn-sub" :class="{ 'btn-sub-disabled': uploading || writeBlocked }" :disabled="uploading || writeBlocked" @click="chooseImage">
              <text>{{ uploading ? '上传中…' : (image ? '换一张' : '选图上传') }}</text>
            </button>
            <text v-if="image" class="img-clear" @click="clearImage">移除图片</text>
          </view>
          <text v-if="image" class="img-path">入库值：{{ image }}</text>
          <text v-else class="field-hint">jpg/png/webp，5MB 以内；不传图则 C 端与本页都显示上面的表情占位</text>
        </view>
      </view>

      <!-- 上下架（编辑态独有）：on_sale 既不在新增白名单（DB 默认 1 = 新增即上架，F-13.6 边界 13），
           PUT /{id} 也不收它，改状态只有这条专用端点 -->
      <view v-if="isEdit" class="card sale-card">
        <view class="field-row field-row-flat">
          <text class="field-label">在售状态</text>
          <text class="sale-badge" :class="onSale === 1 ? 'badge-on' : 'badge-off'">{{ onSale === 1 ? '在售' : '已下架' }}</text>
          <button class="btn-sub" :class="{ 'btn-sub-disabled': writeBlocked || busySale || onSale === 1 }" :disabled="writeBlocked || busySale || onSale === 1" @click="changeOnSale(1)"><text>上架</text></button>
          <button class="btn-sub" :class="{ 'btn-sub-disabled': writeBlocked || busySale || onSale === 0 }" :disabled="writeBlocked || busySale || onSale === 0" @click="changeOnSale(0)"><text>下架</text></button>
        </view>
      </view>

      <view class="card save-card">
        <button
          class="btn-main"
          :class="{ 'btn-disabled': saving || writeBlocked }"
          :disabled="saving || writeBlocked"
          @click="save"
        ><text>{{ saveText }}</text></button>
        <text v-if="writeBlocked" class="field-hint">当前账号不可保存商品（见顶部提示）</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商品新增/编辑共用页（G-02 §F-13.4）：?id= 有值为编辑态，进页面拉详情回填；无 id 为新增。
// 三条口径写在这里以免看代码时猜：
// ① 入库图片用上传出参的 path 键（/static/images/uploads/<名>），显示用 merchantFileUrl()——两者不能混，
//    把后端映射地址写进 dish.image 会让 C 端显示不出来（决策①）；
// ② 编辑态不提交 stock（后端见 stock 非 null 即 400 并指路增量端点，R7'），库存只走 +N/−N；
// ③ 校验先给本地人话文案，最终以服务端 400 原文为准并原样 toast（口径唯一裁决权在后端）。
// 归属一律不传：六个端点的 merchant_id 由服务端 attribute 解析（R2/R3）。
import {
  getMerchantDish,
  createMerchantDish,
  updateMerchantDish,
  adjustMerchantDishStock,
  setMerchantDishOnSale,
  uploadMerchantImage,
  getDishCategories,
  getMerchantMe,
  merchantStatusText,
  merchantFileUrl
} from '@/api/index.js'
import '@/styles/common.css'

// 保存/删除成功后回列表的停留时长：太短 toast 还没看清就跳页，等于没告知
const BACK_DELAY = 900

export default {
  data() {
    return {
      dishId: '',
      isEdit: false,
      // 三态互斥（同工作台纪律）：loading | error | ready（ready 即表单可编辑）
      viewState: 'ready',
      errorText: '',
      // 表单值全部按字符串存（input 绑定态），提交时再转数字——中途态不能污染成 NaN
      form: {
        name: '',
        price: '',
        unit: '',
        category: '',
        // 新增态这两个必填字段的初值直接取 DB 列默认（init.sql:69 stock DEFAULT 0 / :74 limit_buy DEFAULT 5）：
        // 后端 @NotNull 缺了就 400，给个有出处的默认值比让商家对着空框猜强（F-13.6 边界 13）
        stock: '0',
        limitBuy: '5',
        emoji: '',
        bgColor: '',
        description: ''
      },
      tags: [],
      tagInput: '',
      // image 存入库值（不是可展示 URL），预览另走 previewImage computed
      image: '',
      imgFail: false,
      uploading: false,
      saving: false,
      categoryIndex: 0,
      categories: [],
      categoriesError: '',
      // true = 字典上游不可达、类目下拉用的是 api 层内置 11 类（照常可选，但要让商家知道来源）
      categoriesDegraded: false,
      // 编辑态库存：currentStock 只信服务端返回值（详情出参 / 调整接口返回的真实库存），页内不做加减估算
      currentStock: null,
      stockDelta: '',
      stockSign: 'plus',
      adjusting: false,
      onSale: 1,
      busySale: false,
      // 写请求撞上 403（非 active 停业/清退，F-13 决策④）：置 true 后三个写动作全禁，红条显后端原文
      forbidden: false,
      forbiddenMessage: ''
    }
  },
  computed: {
    categoryLabels() {
      return ['请选择类目'].concat(this.categories.map((c) => c.name))
    },
    // 类目行提示：退化成内置分类与读取失败各一句（都保留点击重试），真源正常时返回空串不出这一行
    categoriesHint() {
      if (this.categoriesDegraded) return '类目字典暂不可用，当前为内置分类，点击重试'
      return this.categoriesError ? '类目字典加载失败：' + this.categoriesError + '，点击重试' : ''
    },
    // picker 显示值：字典里查得到的 code 显中文名；查不到的（脏值/历史分类）原样显 code，不替后端猜
    categoryDisplay() {
      const hit = this.categories.filter((c) => c.code === this.form.category)[0]
      if (hit) return hit.name
      return this.form.category || '请选择类目'
    },
    // 预览地址：入库值经 api 层归一化（商家端 origin 下 C 端静态根路径取不到文件）
    previewImage() {
      return merchantFileUrl(this.image)
    },
    // 背景色色块：值不是 # 开头的合法形态时用灰底兜一下，避免把整块预览染成不可预测的颜色
    safeColor() {
      const c = (this.form.bgColor || '').trim()
      return /^#[0-9a-fA-F]{3,8}$/.test(c) ? c : '#F5F5F5'
    },
    // store 里的 me.status 预判（可能为 null：直接深链进本页时快照还没建）
    lockedByStatus() {
      const m = this.$store.state.merchant
      return !!(m && m.status && m.status !== 'active')
    },
    writeBlocked() {
      return this.forbidden || this.lockedByStatus
    },
    blockReason() {
      if (this.forbidden) return this.forbiddenMessage || '无权限访问'
      const m = this.$store.state.merchant
      // 状态走 merchantStatusText 出中文，红条不裸露后端枚举值（商家会当成系统报错）
      return '商家当前状态为 ' + merchantStatusText((m && m.status) || '未知') + '，非正常营业时不能新增或修改商品'
    },
    saveText() {
      if (this.saving) return '保存中…'
      return this.isEdit ? '保存修改' : '新增商品'
    }
  },
  async onLoad(options) {
    // options.id 有值即编辑态；导航栏标题靠运行时改，pages.json 只登记新增态的静态默认值
    const id = options && options.id ? String(options.id) : ''
    this.dishId = id
    this.isEdit = !!id
    if (this.isEdit) {
      this.viewState = 'loading'
      uni.setNavigationBarTitle({ title: '编辑商品' })
    }
    // 先补快照再拉详情（两者都会写响应式字段，故严格顺序执行）：
    // 深链直达时快照还没建、平台恢复营业后快照还停在 suspended，两种情况都会误禁写闸门
    await this.syncMerchantSnapshot()
    if (this.isEdit) await this.loadDish()
    this.loadCategories()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底（照 workspace.vue）：index.html 锁死 html,body overflow:hidden，
    // 本页表单比列表页更长，PC 上不接管滚轮就够不到保存按钮
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  // 滚轮监听与定时器仅在 H5/已注册路径下存在，清理分支按有无判空
  onUnload() {
    // #ifdef H5
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
    if (this._backTimer) {
      clearTimeout(this._backTimer)
      this._backTimer = null
    }
  },
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // toast 之后再回列表：立刻 navigateBack 会把 toast 一起带走，等于没告知
    toastThenBack(title) {
      this.showToast(title)
      this._backTimer = setTimeout(() => {
        this._backTimer = null
        uni.navigateBack()
      }, BACK_DELAY)
    },
    // ==================== 数据加载 ====================
    // store 的 me 快照缺失或非 active 时补拉一次（lockedByStatus 读的就是它）：
    // 失败一律静默——快照只是预判，真被停了写请求照样吃 403，拉不到不该拦住表单渲染
    async syncMerchantSnapshot() {
      const cached = this.$store.state.merchant
      if (cached && cached.status === 'active') return
      try {
        const res = await getMerchantMe()
        const me = (res && res.data) || null
        if (me && me.role === 'merchant') this.$store.commit('setMerchant', me)
      } catch (e) {
        // 维持原快照，不 toast、不切换视图
      }
    },
    async loadCategories() {
      try {
        const res = await getDishCategories()
        const list = (res && Array.isArray(res.data)) ? res.data : []
        // definition 形如 'code:meat'，code 即 dish.category 取值（与 C 端 index.vue codeOf 同一口径）
        this.categories = list
          .map((d) => ({ code: String((d && d.definition) || '').replace(/^code:/, '').trim(), name: (d && d.word) || '' }))
          .filter((c) => c.code)
        // degraded = 这一批是 api 层的内置兜底：category 是后端必填，没这一层新增就永远提交不出去
        this.categoriesDegraded = !!(res && res.degraded)
        this.categoriesError = ''
        this.syncCategoryIndex()
      } catch (e) {
        this.categoriesDegraded = false
        this.categoriesError = (e && e.message) || '未知错误'
      }
    },
    // 编辑态回填时字典可能比详情后到，故两边都调一次同步；查不到的 code 退回 0（显示值另有 categoryDisplay 兜）
    syncCategoryIndex() {
      const idx = this.categories.findIndex((c) => c.code === this.form.category)
      this.categoryIndex = idx >= 0 ? idx + 1 : 0
    },
    // GET /api/merchant/dishes/{id}：404 直接告知并回列表（列表页马上也会重拉，不在本页挂着半张空表单）
    async loadDish() {
      if (this._loadingDetail) return
      this._loadingDetail = true
      this.viewState = 'loading'
      try {
        const res = await getMerchantDish(this.dishId)
        const d = (res && res.data) || null
        if (!d) {
          this.toastThenBack('商品不存在')
          return
        }
        this.fillForm(d)
        this.viewState = 'ready'
      } catch (e) {
        if (e && e.code === 401) {
          this.viewState = 'loading'
          return
        }
        if (e && e.code === 404) {
          this.toastThenBack((e && e.message) || '商品不存在')
          return
        }
        if (e && e.code === 403) {
          this.forbidden = true
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
        }
        this.errorText = (e && e.message) || ''
        this.viewState = 'error'
      } finally {
        this._loadingDetail = false
      }
    },
    fillForm(d) {
      this.form.name = d.name == null ? '' : String(d.name)
      this.form.price = d.price == null ? '' : String(d.price)
      this.form.unit = d.unit == null ? '' : String(d.unit)
      this.form.category = d.category == null ? '' : String(d.category)
      this.form.emoji = d.emoji == null ? '' : String(d.emoji)
      this.form.bgColor = d.bgColor == null ? '' : String(d.bgColor)
      this.form.description = d.description == null ? '' : String(d.description)
      this.currentStock = d.stock == null ? null : Number(d.stock)
      this.form.limitBuy = d.limitBuy == null ? '5' : String(d.limitBuy)
      this.tags = Array.isArray(d.tags) ? d.tags.slice() : []
      this.image = typeof d.image === 'string' ? d.image : ''
      this.imgFail = false
      // on_sale 是 TINYINT 直出（1/0），脏值一律按「非 1 即下架」显示，与列表页同一口径
      this.onSale = Number(d.onSale) === 1 ? 1 : 0
      this.syncCategoryIndex()
    },
    // ==================== 表单交互 ====================
    onCategoryChange(e) {
      const idx = Number(e && e.detail && e.detail.value)
      this.categoryIndex = Number.isFinite(idx) ? idx : 0
      // 下标 0 是「请选择类目」占位：此时 category 留空，由提交前校验拦下（不能把占位当值下发，
      // 那会把已有分类写成空串——列是 NOT NULL，空串写得进去，等于静默改坏数据）
      const c = this.categoryIndex > 0 ? this.categories[this.categoryIndex - 1] : null
      this.form.category = c ? c.code : ''
    },
    setStockSign(sign) {
      this.stockSign = sign
    },
    addTag() {
      const t = (this.tagInput || '').trim()
      if (!t) {
        this.showToast('请先写个标签')
        return
      }
      if (t.length > 12) {
        this.showToast('单个标签不能超过 12 个字')
        return
      }
      if (this.tags.length >= 5) {
        this.showToast('标签最多 5 个')
        return
      }
      this.tags = this.tags.concat([t])
      this.tagInput = ''
    },
    removeTag(i) {
      this.tags = this.tags.filter((_, idx) => idx !== i)
    },
    // ==================== 图片 ====================
    // H5 端 tempFilePaths 是 blob 地址、小程序端是本地临时文件，api 层的上传通道两种都吃；
    // 体积/后缀不在这里判（白名单 + 魔数 + ≤5MB 由服务端 R5 校验链唯一裁决，前端只原样 toast 400 文案）
    chooseImage() {
      uni.chooseImage({
        count: 1,
        sizeType: ['compressed'],
        success: (res) => {
          const filePath = res && res.tempFilePaths && res.tempFilePaths[0]
          if (filePath) this.uploadImage(filePath)
        }
      })
    },
    // 上传出参两个键，入库只取 path（/static/images/uploads/<名>）；取 url 就是决策①点名的静默假成功
    async uploadImage(filePath) {
      this.uploading = true
      try {
        const res = await uploadMerchantImage(filePath)
        const data = (res && res.data) || null
        const path = data && typeof data.path === 'string' ? data.path : ''
        if (!path) {
          this.showToast('上传返回异常，请重试')
          return
        }
        this.image = path
        this.imgFail = false
        this.showToast('图片已上传，保存后生效')
      } catch (e) {
        if (e && e.code === 403) {
          this.forbidden = true
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
          return
        }
        if (e && e.code !== 401) this.showToast((e && e.message) || '上传失败')
      } finally {
        this.uploading = false
      }
    },
    // 清空意图：image 传空串是要真的写进库（C 端回退 emoji 占位），故这里不能只清预览
    clearImage() {
      this.image = ''
      this.imgFail = false
    },
    // ==================== 库存增量（编辑态） ====================
    async adjustStock() {
      const n = Math.abs(Number(this.stockDelta))
      if (!this.stockDelta || !Number.isInteger(n) || n <= 0) {
        this.showToast('请填写要调整的库存数量（正整数）')
        return
      }
      if (n > 1000) {
        this.showToast('单次库存调整不能超过 ±1000')
        return
      }
      const delta = this.stockSign === 'minus' ? -n : n
      this.adjusting = true
      try {
        const res = await adjustMerchantDishStock(this.dishId, delta)
        // 返回值是落库后的真实库存：并发下不等于「原库存 + delta」，必须以服务端返回覆盖，页内不自算
        this.currentStock = Number(res && res.data)
        this.stockDelta = ''
        this.showToast((res && res.message) || '库存已更新')
      } catch (e) {
        if (e && e.code === 403) {
          this.forbidden = true
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
          return
        }
        if (e && e.code === 404) {
          this.toastThenBack((e && e.message) || '商品不存在')
          return
        }
        if (e && e.code !== 401) this.showToast((e && e.message) || '库存调整失败')
      } finally {
        this.adjusting = false
      }
    },
    // ==================== 上下架 ====================
    async changeOnSale(onSale) {
      this.busySale = true
      try {
        const res = await setMerchantDishOnSale(this.dishId, onSale)
        this.onSale = Number(onSale)
        this.showToast((res && res.message) || '商品状态已更新')
      } catch (e) {
        if (e && e.code === 403) {
          this.forbidden = true
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
          return
        }
        if (e && e.code === 404) {
          this.toastThenBack((e && e.message) || '商品不存在')
          return
        }
        if (e && e.code !== 401) this.showToast((e && e.message) || '操作失败')
      } finally {
        this.busySale = false
      }
    },
    // ==================== 保存 ====================
    // 本地只拦「一眼能判的」长度/必填，语义校验（分类取值、价格精度等）交服务端，400 原文原样 toast
    validate() {
      const name = (this.form.name || '').trim()
      if (!name) return '请填写商品名称'
      if (name.length > 100) return '商品名称不能超过 100 个字'
      const price = String(this.form.price || '').trim()
      if (!price) return '请填写商品价格'
      if (!/^\d{1,8}(\.\d{1,2})?$/.test(price)) return '价格最多 8 位整数、2 位小数'
      if (!(Number(price) > 0)) return '价格必须大于 0'
      if (!this.form.category) return '请选择商品分类'
      const unit = (this.form.unit || '').trim()
      if (!unit) return '请填写商品单位（如 斤/盒/份）'
      if (unit.length > 20) return '单位不能超过 20 个字'
      if (!this.isEdit) {
        const stock = String(this.form.stock || '').trim()
        if (stock === '' || !/^\d+$/.test(stock)) return '请填写库存数量（0~99999 的整数）'
        if (Number(stock) > 99999) return '库存不能超过 99999'
      }
      const limitBuy = String(this.form.limitBuy || '').trim()
      if (!/^\d+$/.test(limitBuy)) return '请填写限购数量（1~20 的整数）'
      if (Number(limitBuy) < 1 || Number(limitBuy) > 20) return '限购数量要在 1~20 之间'
      if ((this.form.description || '').length > 2000) return '描述不能超过 2000 个字'
      if ((this.form.emoji || '').length > 50) return '表情符号过长（50 字以内）'
      if ((this.form.bgColor || '').length > 20) return '背景色值过长（20 字以内）'
      return ''
    },
    buildPayload() {
      const p = {
        name: (this.form.name || '').trim(),
        price: Number(this.form.price),
        unit: (this.form.unit || '').trim(),
        category: this.form.category,
        // 选填字段空串照发 = 主动清空（部分更新语义里 null 才是「本次不动」，见 api 层 pickDishFields）
        image: this.image,
        description: this.form.description || '',
        emoji: this.form.emoji || '',
        bgColor: this.form.bgColor || '',
        limitBuy: Number(this.form.limitBuy),
        tags: this.tags.slice()
      }
      // 编辑态刻意不带 stock：后端见 stock 非 null 即 400（R7' 商家端只开增量，api 层白名单也已排除）
      if (!this.isEdit) p.stock = Number(this.form.stock)
      return p
    },
    async save() {
      if (this.writeBlocked || this.saving) return
      const msg = this.validate()
      if (msg) {
        this.showToast(msg)
        return
      }
      this.saving = true
      const payload = this.buildPayload()
      try {
        const res = this.isEdit
          ? await updateMerchantDish(this.dishId, payload)
          : await createMerchantDish(payload)
        // 新增成功回列表：列表 onShow 会重拉，新行（on_sale 默认 1）自然出现在「全部/在售」里
        this.toastThenBack((res && res.message) || '已保存')
      } catch (e) {
        if (e && e.code === 403) {
          this.forbidden = true
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
          return
        }
        if (e && e.code === 404) {
          // 行不归属/已被删：留在本页没意义（再存还是 404），告知后回列表看真实集合
          this.toastThenBack((e && e.message) || '商品不存在')
          return
        }
        // 400/429/网络异常一律原样 toast 后端文案（校验口径的最终裁决权在服务端，前端不改写）
        if (e && e.code !== 401) this.showToast((e && e.message) || '保存失败，请稍后重试')
      } finally {
        this.saving = false
      }
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
/* 多多红单色板：灰底 + 白卡（.card/.error-row/.btn-main/.btn-disabled/.cell-arrow 取自 common.css） */

/* ==================== 0. 写闸门红条 ==================== */
.block-card {
  margin-top: 24rpx;
  background-color: #FFF7F0;
  border: 1rpx solid #FFE3E0;
}
.block-text { font-size: 24rpx; font-weight: 600; color: #E02020; line-height: 1.6; }

/* ==================== 1. 加载/错误态 ==================== */
.loading-card { margin-top: 56rpx; display: flex; justify-content: center; }
.loading-text { font-size: 26rpx; color: #999999; line-height: 1.6; }

/* ==================== 2. 表单行 ==================== */
.form-card { margin-top: 24rpx; padding: 8rpx 24rpx 24rpx; }
.field-row {
  display: flex; align-items: center; gap: 16rpx;
  padding: 22rpx 0; border-bottom: 1rpx solid #F5F5F5;
}
.field-row-flat { border-bottom: none; padding: 8rpx 0; }
/* 纵向排布的整块字段（描述/标签/图片）：标签在上一行，内容占满宽 */
.field-row-col { flex-direction: column; align-items: flex-start; gap: 12rpx; }
.field-row-wrap { flex-wrap: wrap; }
.field-label { width: 140rpx; flex-shrink: 0; font-size: 28rpx; color: #333333; line-height: 1.4; }
.field-row-col .field-label { width: auto; font-weight: 600; }
.field-input {
  flex: 1; min-width: 0;
  height: 68rpx; line-height: 68rpx;
  background-color: #F5F5F5; border-radius: 12rpx;
  padding: 0 20rpx; font-size: 28rpx; color: #333333;
}
.field-suffix { flex-shrink: 0; font-size: 24rpx; color: #999999; }
.field-picker {
  flex: 1; min-width: 0;
  font-size: 28rpx; color: #333333; line-height: 1.4;
  text-align: right;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
/* picker 显式占满整行：H5 下它编译成自定义元素，不声明块级的话整行点击区只剩文字那么宽 */
.row-picker { display: block; width: 100%; }
.field-textarea {
  width: 100%; height: 160rpx; box-sizing: border-box;
  background-color: #F5F5F5; border-radius: 12rpx;
  padding: 16rpx 20rpx; font-size: 26rpx; color: #333333; line-height: 1.6;
}
/* 字段级说明行：小字灰，不抢主文案；字典失败时同一位置改成可点重试的红字 */
.field-hint { font-size: 22rpx; color: #BBBBBB; line-height: 1.5; }
.field-hint-warn { color: #E02020; }

/* ==================== 库存增量控件（编辑态独有） ==================== */
.stock-now { font-size: 26rpx; color: #666666; min-width: 130rpx; }
.sign-btns { display: flex; gap: 8rpx; flex-shrink: 0; }
.sign-btn {
  width: 60rpx; height: 60rpx; line-height: 56rpx; text-align: center;
  font-size: 30rpx; color: #666666;
  background-color: #F5F5F5; border: 1rpx solid #EEEEEE; border-radius: 12rpx;
}
.sign-btn.active { color: #E02020; font-weight: 700; border-color: #E02020; background-color: #FFF7F0; }
.stock-input { flex: 0 0 160rpx; width: 160rpx; }

/* ==================== 次级按钮（common.css 只有主按钮，本页的上传/调整/上下架属次级动作） ==================== */
.btn-sub {
  flex-shrink: 0; margin: 0; padding: 0 28rpx;
  height: 64rpx; line-height: 64rpx;
  background-color: #FFFFFF; color: #E02020;
  font-size: 26rpx; font-weight: 600;
  border: 1rpx solid #E02020; border-radius: 999rpx;
}
.btn-sub.btn-sub-disabled { color: #F5B5B0; border-color: #F5B5B0; background-color: #FFFFFF; }

/* ==================== 标签 ==================== */
.tag-list { display: flex; flex-wrap: wrap; gap: 12rpx; }
.tag-chip {
  display: flex; align-items: center; gap: 6rpx;
  background-color: #FFF7F0; border: 1rpx solid #FFE3E0; border-radius: 999rpx;
  padding: 6rpx 16rpx;
}
.tag-text { font-size: 22rpx; color: #E02020; line-height: 1.4; }
.tag-x { font-size: 24rpx; color: #E02020; line-height: 1.4; padding: 0 4rpx; }
.tag-row { display: flex; align-items: center; gap: 12rpx; width: 100%; }
.tag-input { flex: 1; }

/* ==================== 图片 ==================== */
.img-box {
  width: 220rpx; height: 220rpx;
  border-radius: 12rpx; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.img-photo { width: 220rpx; height: 220rpx; }
.img-emoji { font-size: 96rpx; line-height: 1; }
.img-btns { display: flex; align-items: center; gap: 20rpx; }
.img-clear { font-size: 24rpx; color: #999999; text-decoration: underline; }
/* 入库值原样显示（不是可展示 URL）：验收时一眼能确认库里存的确实是 /static/images/uploads/<名> */
.img-path {
  width: 100%; box-sizing: border-box;
  font-size: 20rpx; color: #BBBBBB; line-height: 1.5;
  word-break: break-all;
}
.color-chip {
  width: 48rpx; height: 48rpx; flex-shrink: 0;
  border-radius: 8rpx; border: 1rpx solid #EEEEEE;
}

/* ==================== 上下架卡 ==================== */
.sale-card { margin-top: 24rpx; }
.sale-badge {
  flex: 1; font-size: 22rpx; font-weight: 600; line-height: 1.4;
  border-radius: 8rpx; padding: 6rpx 16rpx;
}
.badge-on { color: #FFFFFF; background-color: #E02020; }
.badge-off { color: #999999; background-color: #F5F5F5; }

/* ==================== 保存卡 ==================== */
.save-card { margin-top: 24rpx; display: flex; flex-direction: column; gap: 12rpx; }

/* 底部留白：最后一个字段离保存按钮近，别被键盘/手势条压住 */
.page-bottom-space { height: 60rpx; }
</style>
