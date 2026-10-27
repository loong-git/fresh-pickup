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
      <!-- 审核驳回横幅（F-116 §2.4 编辑态展示）：已驳回醒目提示 + 理由 + 「改品不洗白」文案；
           状态真源在库，本页只展示不维护——恢复唯一入口=运营端 restore -->
      <view v-if="isEdit && auditStatus === 'rejected'" class="card audit-reject-card">
        <text class="audit-reject-title">⚠ 该商品已被平台运营驳回</text>
        <text class="audit-reject-reason">驳回理由：{{ auditReason || '（运营未留理由）' }}</text>
        <text class="audit-reject-hint">驳回期间 C 端看不到也买不了该商品；修改并保存商品不会改变驳回状态，恢复请联系平台运营处理。</text>
      </view>
      <view class="card form-card">
        <view class="field-row de-field-name" :class="{ 'field-error': errorKey === 'name' }">
          <text class="field-label">名称</text>
          <input class="field-input" v-model="form.name" maxlength="100" placeholder="必填，如 山东红富士苹果" placeholder-style="color:#BBBBBB;" />
        </view>
        <view class="field-row de-field-price" :class="{ 'field-error': errorKey === 'price' }">
          <text class="field-label">价格</text>
          <input class="field-input" v-model="form.price" type="digit" placeholder="必填，单位元" placeholder-style="color:#BBBBBB;" />
          <text class="field-suffix">元</text>
        </view>
        <view class="field-row de-field-unit" :class="{ 'field-error': errorKey === 'unit' }">
          <text class="field-label">单位</text>
          <input class="field-input" v-model="form.unit" maxlength="20" placeholder="必填，如 斤 / 盒 / 份" placeholder-style="color:#BBBBBB;" />
        </view>
        <!-- 类目：11 类只选不建（F-13.4），取值域来自字典 dish_category，页内不硬编码枚举 -->
        <picker class="row-picker" mode="selector" :range="categoryLabels" :value="categoryIndex" @change="onCategoryChange">
          <view class="field-row de-field-category" :class="{ 'field-error': errorKey === 'category' }">
            <text class="field-label">类目</text>
            <text class="field-picker">{{ categoryDisplay }}</text>
            <text class="cell-arrow">›</text>
          </view>
        </picker>
        <!-- 重试走 retryCategories（带闸门，时长优先转述后端 data.retryAfterSeconds，拿不到才按 60s 保守上限起闸），
             不直连 loadCategories：/api/dict 是 IP 维度 10 次/分，连点必吃 429 -->
        <text v-if="categoriesHint" class="field-hint field-hint-warn" @click="retryCategories">{{ categoriesHint }}</text>

        <!-- 库存两态分开：新增带初始值（POST 必填），编辑只给增量（PUT 见 stock 非 null 即 400，R7'） -->
        <view v-if="!isEdit" class="field-row de-field-stock" :class="{ 'field-error': errorKey === 'stock' }">
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
          <!-- 方向回显：数量框是 type="number"（能打出负号），把当前符号钉在框旁边，
               让「方向只有 stockSign 一处真值」这件事在界面上也只看一处（框里打负号会被直接拦下，见 adjustStock） -->
          <text class="stock-sign-echo">{{ stockSign === 'minus' ? '−' : '＋' }}</text>
          <input class="field-input stock-input" v-model="stockDelta" type="number" placeholder="数量" placeholder-style="color:#BBBBBB;" />
          <button class="btn-sub" :class="{ 'btn-sub-disabled': adjusting || writeBlocked || leaving || writeGates.stock.seconds > 0 }" :disabled="adjusting || writeBlocked || leaving || writeGates.stock.seconds > 0" @click="adjustStock">
            <text>{{ adjusting ? '调整中…' : (writeGates.stock.seconds > 0 ? '库存冷却中' : '调整') }}</text>
          </button>
        </view>
        <text v-if="isEdit" class="field-hint">商家端只能按增减量改库存（单次 ±1000 以内），要精确改数找平台；数量只填正数，增减方向用 ＋/− 选</text>
        <!-- 库存支的常驻反馈行（X-7b）：429 冷却与 400/500/网络异常都落这一行，不再只有一条 1.5s 的 toast。
             秒数只从 writeGates.stock 那一枚现取，这一栏这一把锁只在这一处出现一个数（X-5）；
             行长在「库存」这一栏下面，说的就是库存那把桶，不是保存/上传/上下架那三把 -->
        <text v-if="writeFeedbackText('stock')" class="field-hint save-feedback">{{ writeFeedbackText('stock') }}</text>

        <view class="field-row de-field-limitBuy" :class="{ 'field-error': errorKey === 'limitBuy' }">
          <text class="field-label">限购</text>
          <input class="field-input" v-model="form.limitBuy" type="number" placeholder="必填，1~20" placeholder-style="color:#BBBBBB;" />
        </view>
        <view class="field-row de-field-emoji" :class="{ 'field-error': errorKey === 'emoji' }">
          <text class="field-label">emoji</text>
          <input class="field-input" v-model="form.emoji" maxlength="50" placeholder="无图时 C 端显示的占位表情" placeholder-style="color:#BBBBBB;" />
        </view>
        <view class="field-row de-field-bgColor" :class="{ 'field-error': errorKey === 'bgColor' }">
          <text class="field-label">背景色</text>
          <input class="field-input" v-model="form.bgColor" maxlength="20" placeholder="如 #FFF1E6" placeholder-style="color:#BBBBBB;" />
          <view class="color-chip" :style="{ backgroundColor: safeColor }"></view>
        </view>
        <view class="field-row field-row-col de-field-description" :class="{ 'field-error': errorKey === 'description' }">
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
            <button class="btn-sub" :class="{ 'btn-sub-disabled': uploading || writeBlocked || leaving || writeGates.upload.seconds > 0 }" :disabled="uploading || writeBlocked || leaving || writeGates.upload.seconds > 0" @click="chooseImage">
              <text>{{ uploading ? '上传中…' : (writeGates.upload.seconds > 0 ? '上传冷却中' : (image ? '换一张' : '选图上传')) }}</text>
            </button>
            <text v-if="image" class="img-clear" @click="clearImage">移除图片</text>
          </view>
          <text v-if="image" class="img-path">入库值：{{ image }}</text>
          <text v-else class="field-hint">jpg/png/webp，5MB 以内；不传图则 C 端与本页都显示上面的表情占位</text>
          <!-- 上传支的常驻反馈行（X-7b）：429 冷却与 400/500/网络异常都落这一行；
               秒数只从 writeGates.upload 那一枚现取，行长在「商品图」这一栏下面，报的就是上传那把桶 -->
          <text v-if="writeFeedbackText('upload')" class="field-hint save-feedback">{{ writeFeedbackText('upload') }}</text>
        </view>
      </view>

      <!-- 上下架（编辑态独有）：on_sale 既不在新增白名单（DB 默认 1 = 新增即上架，F-13.6 边界 13），
           PUT /{id} 也不收它，改状态只有这条专用端点 -->
      <view v-if="isEdit" class="card sale-card">
        <view class="field-row field-row-flat">
          <text class="field-label">在售状态</text>
          <text class="sale-badge" :class="onSale === 1 ? 'badge-on' : 'badge-off'">{{ onSale === 1 ? '在售' : '已下架' }}</text>
          <button class="btn-sub" :class="{ 'btn-sub-disabled': writeBlocked || busySale || onSale === 1 || writeGates.sale.seconds > 0 }" :disabled="writeBlocked || busySale || onSale === 1 || writeGates.sale.seconds > 0" @click="changeOnSale(1)"><text>上架</text></button>
          <button class="btn-sub" :class="{ 'btn-sub-disabled': writeBlocked || busySale || onSale === 0 || writeGates.sale.seconds > 0 }" :disabled="writeBlocked || busySale || onSale === 0 || writeGates.sale.seconds > 0" @click="changeOnSale(0)"><text>下架</text></button>
        </view>
        <!-- 语义（后端已改）：on_sale=0 既撤出推荐位，也会被下单链路硬拦
             （OrderServiceImpl#createOrder 的 for 循环拒 on_sale!=1，文案「〈商品名〉已下架，请重新选择」），故 hint 照实说「不能下单」 -->
        <text class="field-hint">下架后该商品不能下单，同时已从推荐位撤出</text>
        <!-- 上下架支的常驻反馈行（X-7b）：两个按钮点的是同一个方法，因此这一行报的就是同一把锁
             （MerchantDishController#setOnSale）；秒数只从 writeGates.sale 那一枚现取 -->
        <text v-if="writeFeedbackText('sale')" class="field-hint save-feedback">{{ writeFeedbackText('sale') }}</text>
      </view>

      <view class="card save-card">
        <!-- 停态刻意不做 HTML disabled：真 disabled 会把 click 吞掉，商家滚到表单底部点保存就什么都不发生
             （实测红条在长表单里 top=-1405 完全不在视口内，等于零反馈）。改法=视觉禁用 + 可点 + save() 首行 toast 指路 -->
        <button
          class="btn-main"
          :class="{ 'btn-disabled': saving || writeBlocked || leaving || retryLock > 0 }"
          :disabled="saving || leaving"
          @click="save()"
        ><text>{{ saveText }}</text></button>
        <text v-if="writeBlocked" class="field-hint">当前账号不可保存商品（见顶部提示）</text>
        <!-- 保存结果的常驻反馈行（X-7）：429 冷却与 400/500/网络异常都落这一行，不再只有一条 1.5s 的 toast
             （toast 一闪而过就等于没反馈——母版 front-partner/pages/store/store.vue 的同名行，F-18.19 §2）。
             秒数由 saveFeedbackText 从实时 retryLock 现取，这一屏这一把锁只在这一处出现一个数（X-5） -->
        <text v-if="saveFeedback" class="field-hint save-feedback">{{ saveFeedbackText }}</text>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商品新增/编辑共用页（G-02 §F-13.4）：?id= 有值为编辑态，进页面拉详情回填；无 id 为新增。
// 四条口径写在这里以免看代码时猜：
// ① 入库图片用上传出参的 path 键（/static/images/uploads/<名>），显示用 merchantFileUrl()——两者不能混，
//    把后端映射地址写进 dish.image 会让 C 端显示不出来（决策①）；
// ② 编辑态不提交 stock（后端见 stock 非 null 即 400 并指路增量端点，R7'），库存只走 +N/−N；
// ③ 校验先给本地人话文案，最终以服务端 400 原文为准并原样 toast（口径唯一裁决权在后端）。
// ④ 保存撞 429 = 常驻行 + 秒数 + 闸门三件套（X-7）：秒数只转述后端 data.retryAfterSeconds（api 层
//    retrySecondsOf 取值），这把锁只管保存这一支——读侧（loadDish 重拉详情）不碰它（X-1 点名的病）。
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
  merchantFileUrl,
  retrySecondsOf,
  coolingFullText,
  coolingLeadText
} from '@/api/index.js'
import '@/styles/common.css'

// 保存成功后回列表的停留时长：太短 toast 还没看清就跳页，等于没告知
const BACK_DELAY = 900
// 类目字典重试冷却（FEB-9）：/api/dict 是 IP 维度 10 次/分，连点「点击重试」正是 429 的成因。
// F-24-B/FB-3：闸门时长优先转述后端 data.retryAfterSeconds（api 层降级出参的
// degradedRetryAfterSeconds，源头 RateLimitInterceptor.java:79），取不到才回落到下面这枚保守上限。
// 回落值 60 秒 ＝ 字典档 @RateLimit 的 windowSeconds（DictController.java:28 是 IP 10 次/60 秒），
// 不是回速：同一档配 Refill.greedy，实测回速 6 秒 —— 回落时这一闸门会多白拦 54 秒。
const CATEGORY_RETRY_GAP_FALLBACK_MS = 60 * 1000

// 保存撞 429 时闸门的回落秒数（X-7）：本端点档位是 MerchantDishController 的 USER 10 次 / 60 秒
// （POST / 与 PUT /{id} 两枚 @RateLimit 的 windowSeconds 都是 60），与上面那枚保守上限同档，
// 故直接由它换算而来——本批红线是「任何情况下不许新增写死的时长字面量」，这里不再写第二个 60。
// 这一页保存支的秒数来源只有两条：后端 data.retryAfterSeconds（经 api 层 retrySecondsOf 转述）
// 与这枚窗口保守上限，页内不再有三个数。
const SAVE_RETRY_GATE_FALLBACK_SECONDS = CATEGORY_RETRY_GAP_FALLBACK_MS / 1000

// 另外三条写路径（图片上传 / 库存增量 / 上下架）的回落秒数（X-7b）：三枚桶也全是 USER 10 次 / 60 秒
// （MerchantController#upload、MerchantDishController#adjustStock、MerchantDishController#setOnSale），
// 与保存支同档，所以直接复用上面那枚换算值——本批红线是「任何情况下不许新增写死的时长字面量」，
// 这里不再写第三个 60；页内时长出处仍然只有两条：后端 data.retryAfterSeconds 与这枚窗口保守上限。
const WRITE_GATE_FALLBACK_SECONDS = SAVE_RETRY_GATE_FALLBACK_SECONDS

// 429 那一句说法（X-7 → F-28.5 路 B）：本页四把写锁（保存 / 图片上传 / 库存增量 / 上下架）**必须各自点名**——
// 改前这里是一份页内私有的「操作过于频繁，请 Ns 后再试」，四把锁共用一句无归因的话，
// 商家分不清「上传被限流」和「保存被限流」，也就不知道另一枚按钮此刻其实还能按。
// 现在句子由 api 层冷却文案族生成（coolingFullText / coolingLeadText），本页只留这张「锁键 → 锁名」表，
// 单位与句式全端一处（X-8）；秒数仍只从 retrySecondsOf 转述出来的那一枚实时值出，本页零推算、零写死。
const COOLING_LOCK_LABEL = {
  save: '保存',
  upload: '图片上传',
  stock: '库存调整',
  sale: '上下架'
}
// 类目字典那把锁的锁名（与列表页 dishes.vue 的 categoriesHint 同一个名字，同一把桶就该同一句说法）
const COOLING_LABEL_CATEGORY = '类目'

// 降级出参 → 后端给的真值秒数（X-13 顺带登记项：把本页这份「读后端值否则回落」与 api 层
// retrySecondsOf 的取值口径对齐——同样「有限正数才算数」、同样 Math.ceil 向上取整；单位换算留给下面）：
// 没给 / 不可解析 / 非正数一律回 0，而 0 同时是 categoriesHint「不许印秒数」的判据
// （与列表页 dishes.vue 的 catRetryBackendSeconds 同一职责：给了数就把时长写进那一句，没给就不写）
function categoryBackendSeconds(res) {
  const n = Number(res && res.degradedRetryAfterSeconds)
  return Number.isFinite(n) && n > 0 ? Math.ceil(n) : 0
}

// 降级出参 → 本次闸门毫秒数：只有 api 层真的从 429 的 data 里转述到值才用它，
// 没有（上面回 0）就回落，中途不造任何「看起来像后端给的」的数
function categoryRetryGapMs(res) {
  const n = categoryBackendSeconds(res)
  return n > 0 ? n * 1000 : CATEGORY_RETRY_GAP_FALLBACK_MS
}
// 背景色合法形态（FEB-5）：C 端 detail.vue 的 slide-poster 用 background 简写绑 g.bg（由 dish.bgColor 衍生，不是 background-color），
// url(//a.co/x) 这类是「合法 CSS」——只在本地预览上兜底不够，脏值必须在下发前就拦掉。
// 本页预览（safeColor）与提交校验（validate）共用这一份正则
const HEX_COLOR_RE = /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/

export default {
  data() {
    return {
      dishId: '',
      isEdit: false,
      // 三态互斥（同工作台纪律）：loading | error | ready（ready 即表单可编辑）
      viewState: 'ready',
      errorText: '',
      // 校验命中要落到「哪一栏」而不只是一句 toast（FEB-6）：字段名，供红描边 + 滚动定位用
      errorKey: '',
      // 新增态库存为 0 的提示只说一次（FEB-11），每次点保存都弹等于噪音
      stockZeroNoticed: false,
      // 总闸（FEB-3）：toast 之后到真正离开本页之前的 900ms 窗口里，saving 已被 finally 解禁、
      // 按钮看着能点，连点就会再发一个 PUT（白吃 10/分写额度 + 多一条 dish.update 审计）——故另设一刀切的闸
      leaving: false,
      // 表单值全部按字符串存（input 绑定态），提交时再转数字——中途态不能污染成 NaN
      form: {
        name: '',
        price: '',
        unit: '',
        category: '',
        // 新增态这两个必填字段的初值直接取 DB 列默认（init.sql 的 dish.stock 列 DEFAULT 0 / dish.limit_buy 列 DEFAULT 5）：
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
      // 保存撞 429 的闸门剩余秒数与驱动它的定时器（X-7）：改前这一支只有一条 duration:1500 的 toast，
      // toast 一消失整页就回到「看起来能保存」的原样，而限流桶根本没回满——商家只会连着再点。
      // 形状照写侧母版 front-partner/pages/store/store.vue 的同名两枚字段（onUnload 成对清）
      retryLock: 0,
      retryLockTimer: null,
      // 保存结果的常驻反馈行：{tone:'err'|'cooling', text:string}；null = 本次会话还没点过保存
      saveFeedback: null,
      // 另外三条写路径各自的 429 闸门与常驻行（X-7b）。key 就是桶身份（upload / stock / sale），
      // 三把锁互不相干：桶身份取「控制器类名 + 方法名」（见 RateLimitInterceptor#bucketKey），
      // 保存那把 retryLock 管不到它们，反之亦然（判断与后果见方法区那节注释）。
      // 每枚的形状：retryAt = 墙上时钟锚点（闸门真值，ms），seconds = 由它派生给模板的剩余秒数，
      // feedback = 该栏的常驻行 {tone, text}（cooling 一支存的是不带数字的整句，X-3 同纪律）
      writeGates: {
        upload: { retryAt: 0, seconds: 0, feedback: null },
        stock: { retryAt: 0, seconds: 0, feedback: null },
        sale: { retryAt: 0, seconds: 0, feedback: null }
      },
      // 三把闸门共用的一枚定时器（tick 只换算派生值，不各自 setInterval）：onUnload 单点成对清，
      // 三把全到期时它自己摘（见 tickWriteGates）
      writeGatesTimer: null,
      categoryIndex: 0,
      categories: [],
      categoriesError: '',
      // true = 字典上游不可达、类目下拉用的是 api 层内置 11 类（照常可选，但要让商家知道来源）
      categoriesDegraded: false,
      // 退化原因（与列表页同一分流）：'rate-limited' 是 429（等一会再点），其余是上游不可达（不是商家的错）
      categoriesRateLimited: false,
      // 本次类目闸门的秒数确实转述自后端 data.retryAfterSeconds 时的取值（0 = 不是后端给的）：
      // categoriesHint 只在它 > 0 时才把数写进那一句——闸门两种来源都要起，但「等待时长」这个读数
      // 只能转述后端给的，不能把窗口长度 60 冒充成它（X-7 后半，与列表页 catRetryBackendSeconds 同口径）
      categoryRetryBackendSeconds: 0,
      // 类目锁的**实时剩余秒数**（响应式）：由既有 tickWriteGates 每秒从墙上时钟锚点 _categoryRetryAt 现算后写入，
      // categoriesHint 报的就是这一枚（不再是起闸那一刻的死值）。与 categoryRetryBackendSeconds 并存、各司其职：
      // 后者是「这轮闸门确实转述自后端 data.retryAfterSeconds」的判据（决定要不要出数），前者是出出来的那个会走的数。
      categoryRetrySeconds: 0,
      // 编辑态库存：currentStock 只信服务端返回值（详情出参 / 调整接口返回的真实库存），页内不做加减估算
      currentStock: null,
      stockDelta: '',
      stockSign: 'plus',
      adjusting: false,
      onSale: 1,
      // 审核态（F-116 §2.4 展示类，编辑态详情回填）：rejected 时页面顶部横幅醒目提示；仅展示不提交
      auditStatus: '',
      auditReason: '',
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
      if (this.categoriesDegraded) {
        // 429 与「上游不可达」是两种成因，给的话不一样：前者让商家等（稍后再点），后者不怪商家（内置分类照常用）
        // X-7 后半 + 本轮实时化：闸门既按后端真值起，hint 就印那个数的**实时剩余**（与列表页 dishes.vue 的
        // categoriesHint 同一口径）。判据仍看 categoryRetryBackendSeconds > 0（＝这一轮闸门确实转述自后端
        // data.retryAfterSeconds），回落态一个数都不出——那是窗口长度不是等待时长（F-24.0 裁定 B-1a）；
        // 出数时取的是 categoryRetrySeconds，不是起闸那一刻的死值：它由 tickWriteGates 每秒从 _categoryRetryAt
        // 现算，等 30 秒再看就是「类目请求太频繁，30s后可重试」而不是冻在 60（改前本页印死值、与列表页实时值
        // 逐字同句却不动，正是本条要修的「静止读数冒充剩余时间」）。
        // 本屏这一把锁只在这一处报数（X-5 判据），retryCategories 被拦时那句 toast 刻意不带数。
        // F-28.5 路 B：出数那一支从括号制「（Ns）」改成 api 层 FULL 形，与列表页同类目锁同句式、同锁名
        if (this.categoriesRateLimited) {
          return this.categoryRetryBackendSeconds > 0
            ? coolingFullText(COOLING_LABEL_CATEGORY, this.categoryRetrySeconds)
            : coolingLeadText(COOLING_LABEL_CATEGORY)
        }
        return '类目字典暂不可用，当前为内置分类，点击重试'
      }
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
    // 背景色色块：值不是合法 #RGB/#RRGGBB/#RRGGBBAA 时用灰底兜一下，避免把整块预览染成不可预测的颜色
    // 正则与 validate() 用的 HEX_COLOR_RE 同一份（一处真值：本页预览与下发拦口不能两种口径）
    safeColor() {
      const c = (this.form.bgColor || '').trim()
      return HEX_COLOR_RE.test(c) ? c : '#F5F5F5'
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
      // 停态时按钮文字自己就说清「不能保存」，不只在颜色上区分（灰按钮 + 只换颜色，商家会以为是网络卡）
      if (this.writeBlocked) return '当前账号不可保存'
      // 429 冷却中把「为什么现在按下去不会发请求」写在按钮上（母版 store.vue 的 saveText 同形，X-7）
      if (this.retryLock > 0) return '保存冷却中'
      return this.isEdit ? '保存修改' : '新增商品'
    },
    // 保存支的常驻反馈行文案（X-7）：cooling 一支的秒数只从实时 retryLock 这一处出——
    // 存进带数字的整句会在冷却走完后仍挂着「请 60s 后再试」而按钮早已解禁（那就是 X-3 点名的病，
    // 母版＝front-partner/pages/apply/apply.vue 的 cooling 支，此处照它存不带数字的整句）
    // F-28.5 路 B：句子改由 api 层生成并点名「保存」这把锁（本页另有上传/库存/上下架三把独立写锁同屏）
    saveFeedbackText() {
      if (!this.saveFeedback) return ''
      if (this.saveFeedback.tone === 'cooling') {
        return this.retryLock > 0 ? coolingFullText(COOLING_LOCK_LABEL.save, this.retryLock) : this.saveFeedback.text
      }
      return this.saveFeedback.text
    }
  },
  watch: {
    // 描红只是「上一次校验拦在哪一栏」的记号：商家一开始改表单就撤掉，别让改好的栏还挂着红框
    form: {
      deep: true,
      handler() {
        this.errorKey = ''
      }
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
    // 新增态也要建快照（复核 C 项）：本页损失最大的正是「填了一半的新商品按返回直接没了」，
    // 而快照原先只在编辑态的 fillForm 里建，onBackPress 的「无快照即放行」会把这一支整个跳过。
    // 建在 loadCategories 之后：类目异步回填的不是 form 字段，不会让基线一建好就自判「已改」
    if (!this.isEdit) this._originalSnapshot = this.snapshotPayload()
  },
  // 每次回到本页都复查一次商家状态：仍停在 active 早退的话，「停留期间被停业」永远不会反映。
  // 只补快照（写闸门 lockedByStatus 读它），不碰未保存守卫基线（基线仍以 onLoad 为准）、不重拉商品
  onShow() {
    this.syncMerchantSnapshot()
  },
  // M3：未保存改动的离开守卫（判据与后果见下面两处放行条件）。
  // 实测边界（口径照母版 front-partner/pages/store/store.vue 的 onBackPress，D6）：
  // 导航栏返回按钮走 uni.navigateBack，会触发本钩子；浏览器物理「后退」是 popstate、不经过本钩子，
  // 那一支拦不住——所以本守卫只承诺「拦导航栏返回」，不声称拦所有返回。
  // 判据不新造（D5）：复用 FEB-10 为「没改动就别发 PUT」建的那份提交形态快照，
  // 两处问的本来就是同一件事（「这页和进来时比变了吗」），再造一枚 dirty 布尔就是两个真值会各说各话
  onBackPress() {
    // 放行①：leaving 总闸已置。本页所有离页出口都经 toastThenBack（首行 this.leaving = true），
    // 于是「保存成功自动回列表」「商品不存在回列表」这些系统带着人走的返回不会被二次弹框
    if (this.leaving) return false
    // 放行②：详情还没回填（编辑态 loadDish 失败/尚在飞）时无从比较，按原行为放行——
    // 守卫的前提是「知道进来时长什么样」，不知道就不该拦人（新增态的基线已在 onLoad 建好，不走这支）
    if (!this._originalSnapshot || this.snapshotPayload() === this._originalSnapshot) return false
    uni.showModal({
      title: '还有未保存的改动',
      content: '本页有未保存的修改，现在离开即丢弃。',
      confirmText: '留下继续改',
      cancelText: '丢弃并离开',
      success: (r) => {
        // 只有明确点了「丢弃」才放行；confirm（留下）与拿不到结果一律停在本页
        if (r && r.confirm) return
        this.leaveByBack()
      },
      fail: () => {
        // 弹框起不来的端：不能把人永久锁在表单里，退化为原行为（直接离开），改动是否丢弃由系统决定
        this.leaveByBack()
      }
    })
    return true
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
    // 保存支 429 闸门（X-7）：setInterval 挂在 window 上，不摘就会在页面卸载后继续每秒写一次已死组件
    this.stopRetryLock()
    // 另外三条写路径的闸门（X-7b）：这一枚 tick 同时驱动 upload/stock/sale 三把锁，摘一次三把全停表
    // （与 startWriteGate 里那句「没有表就起一张」成对）；残留的锚点值一并清掉，
    // 否则下一实例的点击侧会读到上一实例的数字——口径同下面那两行 _categoryRetryAt / _categoryRetryGapMs
    this.stopWriteGatesTimer()
    this.resetWriteGates()
    // 类目重试冷却用的是时间戳（没有单独的 setInterval 要清；tick 表已在上面那一对里摘掉），这里只把残留值置空。
    // _categoryRetryGapMs（FB-3 的闸门时长）一并清：留脏值会让下一实例的点击侧起闸用到上一实例的数字
    // （X-7 新加的 categoryRetryBackendSeconds 与本轮实时化新增的 categoryRetrySeconds 都是响应式 data，
    // 随实例一起销毁，本不必在这里清；categoryRetrySeconds 一并归零只为与 _categoryRetryAt 那两行成对，
    // 不让残留在同一实例生命周期内被别处读到）
    this._categoryRetryAt = 0
    this._categoryRetryGapMs = 0
    this.categoryRetrySeconds = 0
  },
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // 回列表（FEB-4）：深链/分享链接直达本页时页面栈长度为 1，uni.navigateBack() 在 H5 等于 history.go(-1)
    // 落到站外或无响应（实测连执行上下文都没了）。判栈姿势照 api/index.js 的 goLoginPage：
    // 先 getCurrentPages() 拿栈，能退才退，不能退就 reLaunch 到列表页（栈里不留本页，回退不会再撞回来）
    goBackToList() {
      let pages = []
      try {
        pages = getCurrentPages() || []
      } catch (e) {
        pages = []
      }
      if (pages.length > 1) {
        uni.navigateBack()
        return
      }
      uni.reLaunch({ url: '/pages/dishes/dishes' })
    },
    // onBackPress 的「丢弃并离开」出口：先置总闸再退。顺序反了就等于白做——
    // uni.navigateBack 自己也会触发 onBackPress，leaving 还是 false 的话会再弹一次同样的问。
    // 离页仍走 goBackToList（它含「栈深 ≤1 时 reLaunch 回列表」的兜底），不在这里裸调 uni.navigateBack()：
    // 深链直达时裸调就是 FEB-4 那条注释登记的「落到站外」
    leaveByBack() {
      this.leaving = true
      this.goBackToList()
    },
    // toast 之后再回列表：立刻 navigateBack 会把 toast 一起带走，等于没告知
    // 首行置 leaving 总闸：这 900ms 里 saving 会被 finally 解禁，但四个写入口一律看 leaving 早退
    toastThenBack(title) {
      this.leaving = true
      this.showToast(title)
      this._backTimer = setTimeout(() => {
        this._backTimer = null
        this.goBackToList()
      }, BACK_DELAY)
    },
    // ==================== 保存支的常驻反馈与 429 闸门（X-7） ====================
    // 为什么加在页面而不是 api 层：api 层 handleEnvelope 只做「结构化 reject」（401 才清态回登录页），
    // 429 的处置在三端各页都是页面层的事——闸门要定时器与响应式字段（retryLock/saveFeedback），
    // 常驻行要模板与类名，这些都不属于请求层；请求层唯一该管的是「把后端真值原样透出」（e.data 与
    // degradedRetryAfterSeconds）并只留一份取值函数（retrySecondsOf），本片正是复用它而不是再造一份。
    // 常驻反馈落一处（母版 front-partner/pages/store/store.vue 的 setFeedback）：行与 toast 同一串生成，
    // 免得「toast 说失败、行说成功」的分裂。429 那一支不走这里，它要存不带数字的整句，见 save() 的 catch
    setSaveFeedback(tone, text) {
      this.saveFeedback = { tone, text }
      this.showToast(text)
    },
    // 秒级自减闸门，形状照本页族内已有的列表页 dishes.vue 与写侧母版 store.vue（onUnload 成对清）
    // 只由保存这一支起/停：读侧（loadDish 重拉详情）绝不碰这把锁——那正是 X-1 点名的病，
    // 读一次失败把写侧的限流闸门清成 0，等于「等待」这件事整个被取消
    startRetryLock(err) {
      this.stopRetryLock()
      // 秒数唯一出处＝api 层 retrySecondsOf 转述的后端 data.retryAfterSeconds；后端没给数才回落到第二参
      //（那枚由窗口长度换算的保守上限 SAVE_RETRY_GATE_FALLBACK_SECONDS），页内不做任何加减推算
      this.retryLock = retrySecondsOf(err, SAVE_RETRY_GATE_FALLBACK_SECONDS)
      this.retryLockTimer = setInterval(() => {
        this.retryLock--
        if (this.retryLock <= 0) this.stopRetryLock()
      }, 1000)
    },
    stopRetryLock() {
      if (this.retryLockTimer) { clearInterval(this.retryLockTimer); this.retryLockTimer = null }
      this.retryLock = 0
    },
    // ==================== 另外三条写路径的 429 闸门与常驻行（X-7b） ====================
    // 为什么不能共用上面那把保存锁（这就是派单要我给的那句结论）：这四条写路径撞的是**四枚互不相干的桶**，
    // 桶身份是「控制器类名 + 方法名」（见 RateLimitInterceptor#bucketKey），所以
    // MerchantController#upload（图片上传）、MerchantDishController#adjustStock（库存增量）、
    // MerchantDishController#setOnSale（上下架，上架/下架两个按钮走同一个方法、因此是同一把锁）、
    // MerchantDishController#create / #update（保存支）各计各的配额。共用一枚就会反向误锁：
    // 上传被限流时「调整库存」明明还有额度却按不动，反过来库存的 429 会把保存按钮一起冻住——
    // 那与 X-1 拆读写锁要修的正是同一个病（一把锁管多桶 = 报出来的时长不是用户等的那把）。
    // 为什么这里用「墙上时钟锚点 + 派生秒数」而不是保存支的「每秒自减」：一屏最多三把独立闸门在跑，
    // 三对 setInterval 既难成对清也白耗；而自减在 H5 后台标签页会被浏览器把 interval 压到远慢于 1 秒，
    // 屏上的数与点击时的判据会越跑越偏。锚点仍只由 retrySecondsOf 转述的后端真值换算，页内零推算时长。
    startWriteGate(key, err) {
      const g = this.writeGates[key]
      // 秒数唯一出处＝api 层 retrySecondsOf 转述的 data.retryAfterSeconds；后端没给数才回落到同档窗口的
      // 保守上限 WRITE_GATE_FALLBACK_SECONDS（它就是 SAVE_RETRY_GATE_FALLBACK_SECONDS 的别名，不是新数值）
      g.retryAt = Date.now() + retrySecondsOf(err, WRITE_GATE_FALLBACK_SECONDS) * 1000
      g.seconds = this.writeGateRemaining(key)
      if (!this.writeGatesTimer) this.writeGatesTimer = setInterval(() => this.tickWriteGates(), 1000)
    },
    // 剩余秒数只从锚点现算（到期回 0，0 同时是模板隐掉冷却态的判据）：这样屏上的数、按钮的判定、
    // toast 里那句用的是同一个来源，不存在「两处数互相矛盾」
    writeGateRemaining(key) {
      const at = this.writeGates[key].retryAt
      if (!(at > 0)) return 0
      return Math.max(0, Math.ceil((at - Date.now()) / 1000))
    },
    tickWriteGates() {
      const keys = Object.keys(this.writeGates)
      let live = 0
      keys.forEach((k) => {
        const g = this.writeGates[k]
        if (!(g.retryAt > 0)) return
        g.seconds = this.writeGateRemaining(k)
        if (g.seconds > 0) live++
        else g.retryAt = 0
      })
      // 类目字典锁的实时剩余也由这一枚 interval 驱动（复用 tickWriteGates，不另起第二枚 setInterval）：
      // 锚点 _categoryRetryAt 是墙上时钟值，抗 H5 后台标签页把 interval 压到远慢于 1 秒（口径同写侧锚点）。
      // 只要它未到期（>0）就**必须计入 live**——漏掉 live 是本处头号风险：三把写闸门先归零会把 interval 摘掉，
      // 类目读数从此冻在最后一次 tick 上，正是本任务线在打的「静止读数冒充剩余时间」。
      if (this._categoryRetryAt > 0) {
        this.categoryRetrySeconds = Math.max(0, Math.ceil((this._categoryRetryAt - Date.now()) / 1000))
        if (this.categoryRetrySeconds > 0) live++
      }
      // 三把写闸门 + 类目锁全到期就自己摘表：留一枚每秒空转的 interval 会一直触发重渲染
      if (!live) this.stopWriteGatesTimer()
    },
    stopWriteGatesTimer() {
      if (this.writeGatesTimer) { clearInterval(this.writeGatesTimer); this.writeGatesTimer = null }
    },
    stopWriteGate(key) {
      const g = this.writeGates[key]
      g.retryAt = 0
      g.seconds = 0
      if (!this.writeGateAnyLive()) this.stopWriteGatesTimer()
    },
    writeGateAnyLive() {
      const keys = Object.keys(this.writeGates)
      return keys.some((k) => this.writeGates[k].retryAt > Date.now())
    },
    // onUnload 用：三把闸门一起清（残留值会让下一实例的点击侧读到上一实例的数字，同 _categoryRetryAt 那条纪律）
    resetWriteGates() {
      const keys = Object.keys(this.writeGates)
      keys.forEach((k) => {
        this.stopWriteGate(k)
        this.writeGates[k].feedback = null
      })
    },
    // 三条写路径各自的反馈落点（形状逐字照本页 setSaveFeedback：行与 toast 同一串生成，不会互相矛盾）。
    // 归属靠行所在的位置给出（商品图 / 库存 / 在售状态三栏各一行），满足 X-5 定稿判据
    // 「每处文案必须能看出它在报哪把锁」——这一屏四处时长分属四把独立锁，各报各的且各归各栏
    setWriteFeedback(key, tone, text) {
      this.writeGates[key].feedback = { tone, text }
      this.showToast(text)
    },
    // 该栏的常驻行文案：cooling 一支的秒数只从**那一枚**闸门的实时剩余值现取，存进句子里的是不带数字的整句，
    // 于是冷却走完时这一行自动回到「稍后再点重试」，不会停死在「请 60s 后再试」（X-3 点名的病）。
    // 归因取自 COOLING_LOCK_LABEL[key]——三把写锁各自的句子由 key 点名，不共用一句「太频繁」（X-5）
    writeFeedbackText(key) {
      const g = this.writeGates[key]
      if (!g.feedback) return ''
      if (g.feedback.tone === 'cooling') {
        return g.seconds > 0 ? coolingFullText(COOLING_LOCK_LABEL[key], g.seconds) : g.feedback.text
      }
      return g.feedback.text
    },
    // ==================== 数据加载 ====================
    // 每次 onShow 都复查：active 早退会让「停留期间被停业」永不反映；快照只是预判，失败静默
    //（lockedByStatus 读的就是它，真被停业的写请求照样吃 403，拉不到不该拦住表单渲染）
    async syncMerchantSnapshot() {
      // 首屏 onLoad 的 /me 未回时 onShow 会再发一次（uni 不 await onLoad），防重口径与 dishes.vue 对齐
      if (this._syncingMe) return
      this._syncingMe = true
      try {
        const res = await getMerchantMe()
        const me = (res && res.data) || null
        if (me && me.role === 'merchant') this.$store.commit('setMerchant', me)
      } catch (e) {
        // 维持原快照，不 toast、不切换视图
      } finally {
        this._syncingMe = false
      }
    },
    // 手动重试（红条 hint 的点击口）：闸门时长取最近一次后端给的 retryAfterSeconds（FB-3），
    // 没见过限流响应时按保守上限起闸——字典限流是 IP 维度，连点「点击重试」只会把 429 坐实
    // X-7：这一声刻意不带秒数（与列表页 dishes.vue 的 retryCategories 同口径）。本屏这一把锁的数只出现在
    // categoriesHint 那一行里一处（X-5 判据：同一把锁的时长在同一屏只许出现一处），hint 常驻、1.5s 的
    // toast 一闪而过，把数放在常驻那一行才是「看得见的那个数」
    retryCategories() {
      const now = Date.now()
      if (this._categoryRetryAt && now < this._categoryRetryAt) {
        this.showToast(coolingLeadText(COOLING_LABEL_CATEGORY))
        return
      }
      // || 回落不可省：_categoryRetryGapMs 是懒挂的实例属性，本页从未进过限流态时它是 undefined，
      // 直接相加会得到 NaN，而 now < NaN 恒假 → 闸门整体消失（A-2 那类「静默失效」的同形事故）
      this._categoryRetryAt = now + (this._categoryRetryGapMs || CATEGORY_RETRY_GAP_FALLBACK_MS)
      this.loadCategories()
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
        // degradedReason 是 api 层给的两个字面值（'rate-limited' / 'unreachable'）：分流文案用，不改名不自造
        this.categoriesRateLimited = !!(res && res.degraded && res.degradedReason === 'rate-limited')
        // FB-3：闸门保持本页原有的时间戳形态（不是 dishes 页的 retryLock 计数），只换数值来源。
        // 只在 api 层降级态更新这一枚实例值——成功态没有 retryAfterSeconds 可转述，
        // 用它覆盖会把上一次刚学到的后端真值冲回保守上限。存下来是给 retryCategories() 的点击侧共用，
        // 两侧各算各的就会出现「hint 说的秒数与闸门拦的秒数对不上」
        // X-7：同一份解析结果同时喂「闸门时长」与「hint 印的秒数」——两处都从 categoryBackendSeconds(res)
        // 这一枚真值导出，屏上那个数与拦点击的那把锁永远同源（后端没给时它是 0：闸门照起、句子里不印数）
        if (res && res.degraded) {
          this.categoryRetryBackendSeconds = categoryBackendSeconds(res)
          // 起闸瞬间把实时剩余置为后端给的那枚整轮时长（tickWriteGates 随后每秒从 _categoryRetryAt 覆盖它）；
          // 起闸这一行保证 hint 在第一次 tick 到来前也不会闪 0（「数从 60 开始往下走」而不是「从 0 往上跳」）
          this.categoryRetrySeconds = this.categoryRetryBackendSeconds
          this._categoryRetryGapMs = categoryRetryGapMs(res)
        }
        // || 兜的是「降级出参没带值且此前从未降级过」这种空档，防 now + undefined = NaN 让闸门失效
        if (this.categoriesRateLimited) {
          this._categoryRetryAt = Date.now() + (this._categoryRetryGapMs || CATEGORY_RETRY_GAP_FALLBACK_MS)
          // 类目锁起闸时保证 tickWriteGates 的 interval 在跑（口径同 startWriteGate 里的「没有表就起一张」）：
          // 这是复用同一枚 writeGatesTimer，不是新建第二枚 setInterval；三把写闸门与类目锁共此一张表。
          if (!this.writeGatesTimer) this.writeGatesTimer = setInterval(() => this.tickWriteGates(), 1000)
        }
        this.categoriesError = ''
        this.syncCategoryIndex()
      } catch (e) {
        this.categoriesDegraded = false
        this.categoriesRateLimited = false
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
      this.errorKey = ''
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
      // 审核态回填（F-116 §2.4 展示类）：只为驳回横幅服务；提交 payload 不含这两个键，
      // 后端白名单也不收——改品绝不改变审核态（防"随手保存洗白"）
      this.auditStatus = d.auditStatus || ''
      this.auditReason = d.auditReason || ''
      this.syncCategoryIndex()
      // FEB-10：存一份「进页面时」的提交形态快照（与 buildPayload 同形状、同字符串化口径），
      // 编辑态保存前拿它做 diff。没这一份，什么都没改点保存也会发全量 PUT：
      // buildPayload 每个键都非 null → pickDishFields 全下发 → 后端 setCount 恒 ≥10，
      // 「无更新字段」的防线永不命中，还多落一条 old==new 的 dish.update 审计（实测已见该形态）
      this._originalSnapshot = this.snapshotPayload()
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
      const raw = (this.tagInput || '').trim()
      if (!raw) {
        this.showToast('请先写个标签')
        return
      }
      // FEB-7：一次粘「产地直发,当季现摘」这种逗号串时拆开逐个收；
      // 重复值不再静默收下（原来 :key="t + '_' + i" 容忍了重复，界面上会出现两个一模一样的标签）
      const parts = raw.split(',')
      const next = this.tags.slice()
      let reason = ''
      for (let i = 0; i < parts.length; i++) {
        const t = (parts[i] || '').trim()
        if (!t) continue
        if (t.length > 12) { reason = '单个标签不能超过 12 个字'; break }
        if (next.indexOf(t) !== -1) { if (!reason) reason = '这个标签已经加过了'; continue }
        if (next.length >= 5) { reason = '标签最多 5 个'; break }
        next.push(t)
      }
      const changed = next.length !== this.tags.length
      if (changed) {
        this.tags = next
        this.tagInput = ''
      }
      if (reason) this.showToast(reason)
    },
    removeTag(i) {
      this.tags = this.tags.filter((_, idx) => idx !== i)
    },
    // ==================== 图片 ====================
    // H5 端 tempFilePaths 是 blob 地址、小程序端是本地临时文件，api 层的上传通道两种都吃；
    // 体积/后缀不在这里判（白名单 + 魔数 + ≤5MB 由服务端 R5 校验链唯一裁决，前端只原样 toast 400 文案）
    chooseImage() {
      // X-7b：闸门判在打开系统选图框**之前**——冷却里还让商家挑完一张图再说「太频繁」，是白走一遍控件
      // （这一声与那一栏的常驻行同源：同一枚锁的快照与实时值，允许差 1 秒，X-10 裁定）
      const gateLeft = this.writeGateRemaining('upload')
      if (gateLeft > 0) {
        this.showToast(coolingFullText(COOLING_LOCK_LABEL.upload, gateLeft))
        return
      }
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
      // M4：光靠模板的 :disabled 拦不住重复写——禁用态要等一次重渲染才生效，同一帧内的连点两击都能进到方法体。
      // 判自己这把锁的姿势照本页 save 的首行（if (this.saving || this.leaving) return），不新增状态。
      // 早退必须排在冷却提示之前：第二击不该再多报一声「冷却中」（上传支的闸门在 chooseImage 开选图框前就判掉了）
      if (this.uploading || this.leaving) return
      this.uploading = true
      // 上一轮的上传反馈（429 冷却句 / 400 原文）到这一次真发请求时作废：留着就是常驻行在说上一次的事
      // （口径同保存支的 this.saveFeedback = null）
      this.writeGates.upload.feedback = null
      try {
        const res = await uploadMerchantImage(filePath)
        const data = (res && res.data) || null
        const path = data && typeof data.path === 'string' ? data.path : ''
        if (!path) {
          // 「200 但没给 path 键」是契约漂移，不是一条转瞬就没的 toast 能打发的：落同一行的常驻反馈
          this.setWriteFeedback('upload', 'err', '上传返回异常，请重试')
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
        if (e && e.code === 429) {
          // X-7b：上传支的三件套（闸门 / 常驻行 / 真值转述），形状逐字照本页保存支的 429 那一段。
          // 这把锁是 MerchantController#upload 那一枚，与保存/库存/上下架三把互不覆盖。
          this.startWriteGate('upload', e)
          // 存的是不带数字的整句，屏上那个数由 writeFeedbackText 从实时闸门现取（X-3 同纪律）。
          // F-28.5 路 B：整句改点名「图片上传」（改前是无归因的「操作过于频繁，请稍后再试」，
          // 与后端 429 原文同串——但这一支从不渲染 e.message，所以同串只是巧合，换成归因句不与任何原文打架）
          this.writeGates.upload.feedback = { tone: 'cooling', text: coolingLeadText(COOLING_LOCK_LABEL.upload) }
          this.showToast(coolingFullText(COOLING_LOCK_LABEL.upload, this.writeGateRemaining('upload')))
          return
        }
        // 400/500/网络异常：后端文案原样透出，并同时落这一栏的常驻行——toast 一闪就没了，
        // 商家会以为「按下去什么都没发生」然后连点，那正是 X-7 判的病
        if (e && e.code !== 401) this.setWriteFeedback('upload', 'err', (e && e.message) || '上传失败')
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
      // M4：同 uploadImage 那条纪律——连点第二击是一次重复的库存增量写（delta 是「加」不是「设为」，
      // 两次就是 +2n），而 disabled 的重渲染延迟救不了它。判在冷却闸门之前，免得第二击多报一声「冷却中」
      if (this.adjusting || this.leaving) return
      // X-7b：库存增量是第三枚独立桶（MerchantDishController#adjustStock），冷却里再点只会续自己的桶
      const gateLeft = this.writeGateRemaining('stock')
      if (gateLeft > 0) {
        this.showToast(coolingFullText(COOLING_LOCK_LABEL.stock, gateLeft))
        return
      }
      const typed = Number(this.stockDelta)
      // FEB-2：这一栏原来是 Math.abs(Number(stockDelta))——数量框 type="number" 能打负号，
      // 打 -5 时符号态还明晃晃停在「＋」上，abs 把 -5 静默翻成 +5 真写进了库（实测审计落 delta=5、stock 180）。
      // 方向的真值只有 stockSign 一处：负数输入一律拒绝并指路，绝不在页内替商家改方向
      if (String(this.stockDelta || '').trim() !== '' && typed < 0) {
        this.showToast('数量只填正数，增减方向用 ＋/− 选')
        return
      }
      const n = Math.abs(typed)
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
      // 上一轮的库存反馈到这一次真发请求时作废（口径同保存支）
      this.writeGates.stock.feedback = null
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
        if (e && e.code === 429) {
          // X-7b：库存支的三件套（闸门 / 常驻行 / 真值转述），形状逐字照本页保存支的 429 那一段
          this.startWriteGate('stock', e)
          this.writeGates.stock.feedback = { tone: 'cooling', text: coolingLeadText(COOLING_LOCK_LABEL.stock) }
          this.showToast(coolingFullText(COOLING_LOCK_LABEL.stock, this.writeGateRemaining('stock')))
          return
        }
        // 400/500/网络异常：原文透出 + 落这一栏的常驻行，不再只有一条 1.5s 的 toast
        if (e && e.code !== 401) this.setWriteFeedback('stock', 'err', (e && e.message) || '库存调整失败')
      } finally {
        this.adjusting = false
      }
    },
    // ==================== 上下架 ====================
    async changeOnSale(onSale) {
      // M4：同 uploadImage 那条纪律。这一支还多一层意义——「上架」「下架」两个按钮点的是同一个方法，
      // 所以第一击在飞时另一支的点击也一并拦得住（早退排在冷却提示之前，第二击不多报一声「冷却中」）
      if (this.busySale || this.leaving) return
      // X-7b：上下架是第四枚独立桶（MerchantDishController#setOnSale）——「上架」「下架」两个按钮点的是
      // 同一个方法，因此共用这一把锁是准确的（同一枚桶），与保存/上传/库存那三把互不覆盖
      const gateLeft = this.writeGateRemaining('sale')
      if (gateLeft > 0) {
        this.showToast(coolingFullText(COOLING_LOCK_LABEL.sale, gateLeft))
        return
      }
      // D1/M14：只给「下架」加确认——下架有可见损失（C 端立即买不到），上架没有损失、加确认只会变啰嗦。
      // 判在 busySale 之前是刻意的：点「不下架」等于什么都没发生过，既不许锁住按钮也不许起冷却（那把锁
      // 对应的是 MerchantDishController#setOnSale 那一枚桶，为一次没发的请求起闸就是在偷商家的额度）
      if (Number(onSale) === 0) {
        const ok = await this.confirmOffSale()
        if (!ok) return
      }
      this.busySale = true
      // 上一轮的上下架反馈到这一次真发请求时作废（口径同保存支）
      this.writeGates.sale.feedback = null
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
        if (e && e.code === 429) {
          // X-7b：上下架支的三件套（闸门 / 常驻行 / 真值转述），形状逐字照本页保存支的 429 那一段
          this.startWriteGate('sale', e)
          this.writeGates.sale.feedback = { tone: 'cooling', text: coolingLeadText(COOLING_LOCK_LABEL.sale) }
          this.showToast(coolingFullText(COOLING_LOCK_LABEL.sale, this.writeGateRemaining('sale')))
          return
        }
        // 400/500/网络异常：原文透出 + 落「在售状态」那一栏的常驻行，不再只有一条 1.5s 的 toast
        if (e && e.code !== 401) this.setWriteFeedback('sale', 'err', (e && e.message) || '操作失败')
      } finally {
        this.busySale = false
      }
    },
    // 下架二次确认（M14）：Promise 化 uni.showModal，姿势照 C 端 front/pages/order/order.vue 的 confirmCancel(paid)，
    // 让「确认过才发请求」在代码里只有一种写法。关闭/fail 一律 false——弹框起不来的端宁可这一次下架不发，
    // 也不能替商家做「下架」这个有损失的决定。
    // 文案只写实测过的四条事实：
    // ① C 端列表/搜索不过滤 on_sale（DishServiceImpl#likeSearch 明写「列表保持全量」）→ 不能说「不可见」；
    // ② 真闸门在下单（OrderServiceImpl 的 onSale 判定，实测下架后下单回 400「已下架，请重新选择」）；
    // ③ 首页推荐与秒杀卡按 on_sale 过滤（RecommendServiceImpl / DishServiceImpl#listSeckill），那两处是立即消失的；
    // ④ 已下单不受影响：order_item 建单时快照了名称/价格，核销只看 orders.status + 取货码，不读 dish.on_sale
    confirmOffSale() {
      return new Promise((resolve) => {
        uni.showModal({
          title: '下架商品',
          content: '下架后顾客仍能搜到、看到本商品，但下单会被拦；首页推荐与秒杀位立即不再展示。已下单的订单不受影响。确定下架？',
          confirmText: '确认下架',
          cancelText: '不下架',
          success: (res) => resolve(!!(res && res.confirm)),
          fail: () => resolve(false)
        })
      })
    },
    // ==================== 保存 ====================
    // 本地只拦「一眼能判的」长度/必填，语义校验（分类取值、价格精度等）交服务端，400 原文原样 toast。
    // 返回 { msg, key }（FEB-6）：光有一句居中 toast，12 个字段的表单里商家不知道是哪栏拦下的，
    // key 用来给那一栏描红 + 滚过去；校验通过返回 null
    validate() {
      const fail = (msg, key) => ({ msg, key })
      const name = (this.form.name || '').trim()
      if (!name) return fail('请填写商品名称', 'name')
      if (name.length > 100) return fail('商品名称不能超过 100 个字', 'name')
      const price = String(this.form.price || '').trim()
      if (!price) return fail('请填写商品价格', 'price')
      // FEB-8：格式不合法与「位数超限」是两回事，原来一条正则不命中就回「最多 8 位整数、2 位小数」，
      // 而 .5 / 1e3 / 8. 既没超整数位也没超小数位——答非所问。提交口径不动（后端 @Digits 由后端批收紧），只拆文案
      if (!/^\d+(\.\d{1,2})?$/.test(price)) return fail('价格请填数字，最多两位小数（如 8.88）', 'price')
      if (!/^\d{1,6}(\.\d{1,2})?$/.test(price)) return fail('价格最多 6 位整数、2 位小数', 'price')
      if (!(Number(price) > 0)) return fail('价格必须大于 0', 'price')
      if (!this.form.category) return fail('请选择商品分类', 'category')
      const unit = (this.form.unit || '').trim()
      if (!unit) return fail('请填写商品单位（如 斤/盒/份）', 'unit')
      if (unit.length > 20) return fail('单位不能超过 20 个字', 'unit')
      if (!this.isEdit) {
        const stock = String(this.form.stock || '').trim()
        if (stock === '' || !/^\d+$/.test(stock)) return fail('请填写库存数量（0~99999 的整数）', 'stock')
        if (Number(stock) > 99999) return fail('库存不能超过 99999', 'stock')
      }
      const limitBuy = String(this.form.limitBuy || '').trim()
      if (!/^\d+$/.test(limitBuy)) return fail('请填写限购数量（1~20 的整数）', 'limitBuy')
      if (Number(limitBuy) < 1 || Number(limitBuy) > 20) return fail('限购数量要在 1~20 之间', 'limitBuy')
      if ((this.form.description || '').length > 2000) return fail('描述不能超过 2000 个字', 'description')
      if ((this.form.emoji || '').length > 50) return fail('表情符号过长（50 字以内）', 'emoji')
      const bgColor = (this.form.bgColor || '').trim()
      if (bgColor.length > 20) return fail('背景色值过长（20 字以内）', 'bgColor')
      // FEB-5：本页预览（safeColor）拦不住下发——脏值照原样进库，而 C 端 detail.vue 的 slide-poster 绑的是 background 简写（g.bg 由 dish.bgColor 衍生），
      // url(//a.co/x) 是合法 CSS（外呼追踪面）。非空且不是 #RGB/#RRGGBB/#RRGGBBAA 就拦在提交前
      if (bgColor && !HEX_COLOR_RE.test(bgColor)) return fail('背景色请用 #RRGGBB 形式，如 #FFF1E6', 'bgColor')
      return null
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
    // 提交形态的字符串化快照（FEB-10）：与 buildPayload 同形状，数字/数组统一成可比较的字符串，
    // 免得 8 与 '8'、[a] 与 'a' 这种「同一含义两种写法」被误判成有变更
    snapshotPayload() {
      const p = this.buildPayload()
      const keys = Object.keys(p).sort()
      const norm = {}
      keys.forEach((k) => {
        const v = p[k]
        norm[k] = Array.isArray(v) ? v.join('\u0001') : (v === null || v === undefined ? '' : String(v))
      })
      return JSON.stringify(norm)
    },
    // ==================== 校验反馈定位 ====================
    focusField(key) {
      if (!key) return
      this.errorKey = key
      this.scrollToField('.de-field-' + key)
    },
    scrollToField(sel) {
      // #ifdef H5
      // index.html 锁死 html,body overflow:hidden，本页滚动容器是 document.body（onPageWheel 同口径），
      // uni.pageScrollTo 滚的是 window，这里会静默无效——照 front/pages/free/free.vue 的 scrollToGoods 用 scrollIntoView
      try {
        const el = typeof document !== 'undefined' && typeof document.querySelector === 'function'
          ? document.querySelector(sel)
          : null
        if (el && el.scrollIntoView) el.scrollIntoView({ behavior: 'smooth', block: 'center' })
      } catch (e) {
        // 定位失败只是少了个滚动动画，toast 已经给出去了，不能因此抛错打断页面
      }
      // #endif
      // #ifndef H5
      try {
        const q = uni.createSelectorQuery().in(this)
        q.select(sel).boundingClientRect()
        q.selectViewport().scrollOffset()
        q.exec((res) => {
          if (!res || !res[0]) return
          const cur = (res[1] && res[1].scrollTop) || 0
          uni.pageScrollTo({ scrollTop: Math.max(0, cur + res[0].top - 16), duration: 300 })
        })
      } catch (e) {
        // 同上：小程序端查询异常也不该把反馈升级成报错
      }
      // #endif
    },
    // skipZeroStockNotice：仅由库存为 0 的确认框「仍要新增」回调内部传 true，模板上的点击不传值
    async save(skipZeroStockNotice) {
      // FEB-1：停态下点保存不再静默 return——按钮此处刻意「视觉禁用但可点」（HTML disabled 会吞掉 click），
      // 提示复用页面既有 hint 的语义（「见顶部提示」），红条本身已改 sticky 常驻视口
      if (this.writeBlocked) {
        this.showToast('当前账号不可保存商品（见顶部提示）')
        return
      }
      // X-7：429 闸门期内一次请求都不发——限流桶没回满之前再点只会续桶（USER 10 次/60 秒），
      // 而这一声与常驻行那句同源（同一枚锁的快照与实时值，允许差 1 秒，X-10 裁定）
      if (this.retryLock > 0) {
        this.showToast(coolingFullText(COOLING_LOCK_LABEL.save, this.retryLock))
        return
      }
      if (this.saving || this.leaving) return
      const ret = this.validate()
      if (ret) {
        this.showToast(ret.msg)
        this.focusField(ret.key)
        return
      }
      this.errorKey = ''
      // FEB-11：新增态库存 0 在本页与服务端都是合法值（validate 的 0~99999 口径没动、后端 @NotNull 也过得去），
      // 但 on_sale 走 DB 默认 1 → 建出来的是「C 端立刻可见却一件买不了」的商品。
      // 给一次可确认提示（问一句就继续，不硬拦），stockZeroNoticed 保证只问一次
      if (skipZeroStockNotice !== true && !this.isEdit && Number(this.form.stock) === 0 && !this.stockZeroNoticed) {
        uni.showModal({
          title: '库存为 0',
          content: '库存为 0，商品会在 C 端显示但无法下单，建议先填库存',
          confirmText: '仍要新增',
          cancelText: '去填库存',
          success: (r) => {
            // M10：标记只在商家明确选了「仍要新增」的那一刻消耗。改前它写在 showModal 之前，
            // 于是点「去填库存」也把这一问吞掉了——商家照着填好库存再保存时永远不再提醒，
            // 页面把他自己选的「先去填」当成了「已经问过并同意了」
            if (r && r.confirm) {
              this.stockZeroNoticed = true
              this.save(true)
            } else this.focusField('stock')
          }
          // 刻意不写 fail 支：起不了弹框的端什么都不置位，正好等于「没确认 = 不消耗这一问」
        })
        return
      }
      // FEB-10：编辑态且提交形态与进页面时一致 → 一个请求都不发（新增态无快照可比，保持原行为）
      if (this.isEdit) {
        const snapshot = this.snapshotPayload()
        if (this._originalSnapshot && snapshot === this._originalSnapshot) {
          this.toastThenBack('没有需要保存的修改')
          return
        }
      }
      this.saving = true
      // 上一轮的保存反馈（429 冷却句 / 400 原文）到这一次真发请求时作废：留着就是常驻行在说上一次的事
      this.saveFeedback = null
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
        if (e && e.code === 429) {
          // X-7：429 从此不再只有一条 duration:1500 的 toast（那是全链唯一漏接真值转述的写路径）。
          // 三件套齐上：① 闸门 startRetryLock（秒数由 api 层 retrySecondsOf 转述后端 data.retryAfterSeconds）
          // ② 常驻行 saveFeedback（存不带数字的整句，屏上那个数由 saveFeedbackText 从实时 retryLock 现取，
          //    于是冷却走完时这一行自动回到「稍后再点重试」而不会停死在「请 60s 后再试」——X-3 点名的病）
          // ③ 真值转述（api 层 coolingFullText，点名「保存」这把锁）。
          // F-28.5 路 B：改前这两句是无归因的「操作过于频繁…」（与后端 429 原文 R.java:26
          // MSG_TOO_MANY_REQUESTS 同串），但这一支从不渲染 e.message，同串只是巧合；
          // 换成归因句后商家才读得出「被锁的是保存，不是上传/库存/上下架那三把」
          this.startRetryLock(e)
          this.saveFeedback = { tone: 'cooling', text: coolingLeadText(COOLING_LOCK_LABEL.save) }
          this.showToast(coolingFullText(COOLING_LOCK_LABEL.save, this.retryLock))
          return
        }
        // 400/500/网络异常：后端文案原样透出（校验口径的最终裁决权在服务端，前端不改写），
        // 并同时落常驻行——toast 一消失就回到「看起来能保存」的原样，是 X-7 判的同一件事
        if (e && e.code !== 401) this.setSaveFeedback('err', (e && e.message) || '保存失败，请稍后重试')
      } finally {
        this.saving = false
      }
    },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 滚轮接管的两道守卫（照 dishes.vue 的 onPageWheel 与其上方注释，缺一条就是 bug）：
    // ① 栈顶守卫：navigateTo 不触发上一页 onUnload，列表页与本页的监听会同时挂在 document 上，
    //    不在栈顶还接管就是双份驱动滚动
    // ② 弹层放行：弹层打开时滚轮该归弹层，不该 preventDefault 后去滚背景。
    //    选择器集合必须带 .uni-picker-container —— 本页 :38 的类目下拉是 picker，实测弹层打开时
    //    背景被滚走 120px、而选项区自己滚不动，正是缺这条守卫的样子
    onPageWheel(e) {
      // getCurrentPages 在卸载竞态下可能拿不到栈（api 层 goLoginPage 同样做了守卫），拿不到就不接管滚动，
      // 绝不能在这里 preventDefault 把滚轮吞掉——那是「页面还活着但滚不动了」的成因
      let pages = []
      try {
        pages = getCurrentPages() || []
      } catch (err) {
        return
      }
      if (!pages.length || (pages[pages.length - 1] && pages[pages.length - 1].route) !== 'pages/dish-edit/dish-edit') return
      const t = e.target
      const el = t && t.nodeType === 1 ? t : (t && t.parentElement)
      if (el && typeof el.closest === 'function'
        && el.closest('.uni-actionsheet, .uni-modal, .uni-toast, .uni-sample-toast, .uni-picker-container')) {
        return
      }
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
/* sticky 而不是随表单滚走（FEB-1）：本页表单比视口长得多，实测红条 top=-1405/bottom=-1321，
   滚到底点保存时红条完全在视口外，「见顶部提示」这句指路等于指了个看不见的地方。
   本仓固定层先例见 App.vue:56-69 的全局提示条；这里是页内首屏卡，sticky 就够（不用 fixed，
   fixed 要自己处理左右外边距与导航栏高度，且会把下面的表单顶出一段空白） */
.block-card {
  position: sticky;
  top: 0;
  z-index: 20;
  margin-top: 24rpx;
  background-color: #FFF7F0;
  border: 1rpx solid #FFE3E0;
}
.block-text { font-size: 24rpx; font-weight: 600; color: #E02020; line-height: 1.6; }

/* ==================== 0b. 审核驳回横幅（F-116 §2.4 编辑态展示） ====================
   醒目但不 sticky（不是写闸门那种「每一笔提交都受它管」的常驻警示，进页面读一次即可）；
   橙底黑字与写闸门红条区分，理由行用红字保证一眼可见 */
.audit-reject-card { margin-top: 24rpx; display: flex; flex-direction: column; background-color: #FFF7E6; border: 1rpx solid #FFD591; }
.audit-reject-title { font-size: 26rpx; font-weight: 600; color: #B45309; line-height: 1.6; }
.audit-reject-reason { font-size: 24rpx; color: #E02020; line-height: 1.6; margin-top: 6rpx; }
.audit-reject-hint { font-size: 22rpx; color: #666666; line-height: 1.6; margin-top: 6rpx; }

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
/* 校验命中栏的描红（FEB-6）：12 个字段的表单里，一句居中 1.5s 的 toast 不足以说明「是哪栏拦的」，
   故另给一条行级视觉记号，配合 scrollIntoView/pageScrollTo 把这一栏带进视口。类名写在 <style> 里而非行内 style */
.field-error {
  box-shadow: inset 0 0 0 2rpx #E02020;
  border-radius: 12rpx;
  background-color: #FFF7F0;
}

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
/* 方向回显（FEB-2）：把当前 stockSign 钉在数量框旁边——方向只该有一处真值，
   框里能打出负号是这一行的历史坑（Math.abs 会把它静默翻正并真的写进库） */
.stock-sign-echo {
  flex-shrink: 0; font-size: 30rpx; font-weight: 700; line-height: 1.2;
  color: #E02020; min-width: 28rpx; text-align: center;
}

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
/* 保存支的常驻反馈行（X-7）：沿用 .field-hint 的字号，只把灰改成红——这一行与顶部的写闸门红条
   同为「有事发生」的记号，浅灰会被读成字段说明。写这一条而不是复用 .field-hint-warn（同色），
   是为了给「保存反馈」留一个自己的类名钩子（片 5 的 X-8 文案族统一要按 tone 分色时改这一处就够） */
.save-feedback { color: #E02020; font-weight: 600; }

/* 底部留白：最后一个字段离保存按钮近，别被键盘/手势条压住 */
.page-bottom-space { height: 60rpx; }
</style>
