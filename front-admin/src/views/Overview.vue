<template>
  <div class="page">
    <!-- FE-G6-10（读写可辨）：「本页只读」原先只在 state-box（出错态）里出现，ready 态一个字都不露——
         运营在日常态读不出这页不会动数据。这里挂常驻徽标（与写页的「本页会改数据」同一套形态、颜色区分）。 -->
    <div class="page-head">
      <h2 class="page-title">当日大盘</h2>
      <el-tag type="info" effect="plain" size="small" class="mode-badge">本页只读</el-tag>
    </div>
    <p class="page-note">{{ scopeText }}</p>

    <div class="toolbar">
      <el-date-picker
        v-model="pickedDate"
        type="date"
        value-format="YYYY-MM-DD"
        format="YYYY-MM-DD"
        :clearable="true"
        placeholder="选日期（默认今日）"
        @change="onDateChange"
      />
      <el-button :loading="viewState === 'loading'" @click="load">查询</el-button>
    </div>

    <!-- loading / ready / forbidden / error 四个态互斥，单值 viewState 决定渲染哪一块。
         另有 'idle'：仅在 onMounted 里出现一瞬（同一同步 tick 内即被 load() 置回 'loading'），
         不会单独渲染出来，它存在的唯一理由是别让首屏被 load() 的在飞守卫拦掉。 -->
    <div v-if="viewState === 'loading'" class="state-box">
      <div class="state-title">正在加载</div>
      <div class="state-desc">大盘数据读取中…</div>
    </div>

    <div v-else-if="viewState === 'forbidden'" class="state-box">
      <div class="state-title">没有访问权限</div>
      <div class="state-desc">{{ errorText }}</div>
      <div class="state-desc">本页只读；如确需运营端数据，请核对运营账号的状态与授权。</div>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <div v-else-if="viewState === 'error'" class="state-box">
      <div class="state-title">读取失败</div>
      <div class="state-desc">{{ errorText }}</div>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <div v-else class="cards">
      <div class="card">
        <div class="card-label">订单数</div>
        <div class="card-value">{{ metrics.orderCount }}</div>
      </div>
      <div class="card">
        <div class="card-label">总金额</div>
        <div class="card-value">{{ metrics.totalAmount }}</div>
      </div>
      <div class="card">
        <div class="card-label">待自提单数</div>
        <div class="card-value">{{ metrics.pendingPickupCount }}</div>
      </div>
      <div class="card">
        <div class="card-label">已收款金额</div>
        <div class="card-value">{{ metrics.paidAmount }}</div>
      </div>

      <!-- 口径说明（F-20.10 实测事实，照后端 SQL 逐条写实话；两套金额分母不同，不做任何互相换算） -->
      <div class="caliber">
        <p class="caliber-title">口径说明</p>
        <ul>
          <li>订单数 = 统计窗口内创建的订单条数（含已取消的单，窗口按订单 create_time 计）。</li>
          <li>总金额 = 同一窗口内<b>全部订单</b>的应付合计，与是否支付成功无关。</li>
          <li>待自提单数 = 同一窗口内状态为待取货（status = pending_pickup）的订单条数。</li>
          <li>已收款金额 = 同一窗口内<b>仅支付成功</b>（pay_status = 1）的那部分订单的应付合计。</li>
          <li>因此「总金额」与「已收款金额」不是同一分母：前者按全部订单、后者只按已支付订单，
              两个数各自独立，本页不把它们相减、也不把它们当成同一口径的两个阶段。</li>
          <li>金额由服务端按两位小数为定形的字符串回传（如 577.40），空数据日回的是 0 与 "0.00"，
              不是空值——那表示「这一日确实没有单」，不是「没查到」。</li>
          <li>统计窗口：不选日期时由服务端 CURDATE() 决定今日，前端不做任何日期推算；
              选定日期时按该自然日 [00:00, 次日 00:00) 计。</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
// 只读大盘（F-20.11 Step 4）：GET /api/admin/v2/overview?date=
// 出参四键 {orderCount, totalAmount, pendingPickupCount, paidAmount}（AdminV2ServiceImpl:119-122）。
// ⚠ 后端出参里**没有 date 回显**（实测只有这四个键），所以「统计窗口」这一行只能标成
//   「服务端 CURDATE()」或用户在页面上选定的那个值——绝不由前端拿本机时钟算出「今天是几号」再显示，
//   那会变成第二处「今日」的真值（后端 SQL 与前端 JS 时钟可以不一致）。
import { computed, onMounted, ref } from 'vue'
import { getAdminOverview, retryAfterText } from '../api'

const viewState = ref('loading')
const errorText = ref('')
// F-22 FE-429-2：本页没有提示表（提示就是 errorText 那一行），429 的转述在 load() 的错误分支里拼；
// 这一枚 ref 存 reject 载荷的 e.data，非 429 时不参与渲染。
const errorData = ref(null)
// 空串 = 用户没选日期 = 不下发 date 参数（服务端 CURDATE() 决定窗口）
const pickedDate = ref('')

const metrics = ref({
  orderCount: '-',
  totalAmount: '-',
  pendingPickupCount: '-',
  paidAmount: '-'
})

const scopeText = computed(() =>
  pickedDate.value
    ? `统计窗口：${pickedDate.value}（选定值）`
    : '统计窗口：今日（由服务端 CURDATE() 决定，前端不推算日期）'
)

async function load() {
  // FE-G6-3 在飞守卫：请求未回时不再发第二条（日期 @change、「查询」与「重试」按钮同守这一条），
  // 否则乱序回包会把上一个窗口的数字画在当前 caption 下。照 Login.vue:101 的同一精神，
  // 复用本页已有的单值 viewState，不新增第二个状态变量、不加定时器。
  if (viewState.value === 'loading') return
  viewState.value = 'loading'
  errorText.value = ''
  errorData.value = null
  try {
    const envelope = await getAdminOverview(pickedDate.value)
    const data = envelope.data || {}
    // 逐键取并保留服务端形状：金额是服务端定形的两位小数串，不二次格式化、不做数字换算
    metrics.value = {
      orderCount: data.orderCount,
      totalAmount: data.totalAmount,
      pendingPickupCount: data.pendingPickupCount,
      paidAmount: data.paidAmount
    }
    viewState.value = 'ready'
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
      // 401 由请求层收敛（清 token + 跳 /login），本页只承接一句过渡文案：
      // 路由马上就会被替换，这里渲染什么都不重要，绝不能把它画成「没有数据」
      viewState.value = 'error'
      errorText.value = '登录状态已失效，正在返回登录页…'
      return
    }
    if (e.code === 403) {
      // 403 与 401 分开：登录态有效但没权限，引导态留在本页（现状读数：v2 面的闸门对静态密钥与
      // C 端 token 都回 401，403 走不到，分支保留是与审计页同一套语义）
      viewState.value = 'forbidden'
      errorText.value = e.message || '无权限访问'
      return
    }
    // 400（脏 date）/ 429（IP 30 次/分限流）/ 500 / 网络异常：后端 message 原样透出
    viewState.value = 'error'
    const code = Number(e.code) || 0
    errorData.value = e.data || null
    // F-22 FE-429-2：**只在 429** 时把后端如实给的 retryAfterSeconds 转述出来（转述、非推断，
    // 与 views/Login.vue:43 同一口径）；400/500/-1 后面跟一句「约 N 秒后可再试」是错的，所以不拼。
    // 缺载荷 / 非正数 → retryAfterText 回 ''，本行读数与改动前逐字相同。
    errorText.value = (e.message || '大盘数据读取失败，请稍后重试')
      + (code === 429 ? retryAfterText(errorData.value) : '')
  }
}

function onDateChange() {
  load()
}

onMounted(() => {
  // 首屏这一行是必须的：viewState 的初值 'loading' 只用于首屏渲染加载态占位，
  // 若原样进 load() 会被上面的在飞守卫当成「已在飞」而永久早退（一次请求都不发、页面卡在
  // 「正在加载」）。这里落到 'idle'，同一同步 tick 内 load() 立刻置回 'loading'，
  // 浏览器不会在两个值之间绘制，首屏渲染与改动前逐字相同。
  viewState.value = 'idle'
  load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0;
}
/* FE-G6-1 日期选择器宽度（原先靠 Element Plus 的 --el-date-editor-width: 220px 默认值，
   实测内层 input 只有 176px：220 −（11px×2 内边距 + 22px 前缀日历图标）= 176，placeholder 尾部被切。
   取 280px 是因为同一 44px 扣除量对内层 input 生效：280 − 44 = 236，
   使两种测量口径（根元素 .el-date-editor 280 / 内层 input 236）都落在验收线 220 以上。
   作用域限定在 .toolbar 内，不污染全局，也不动 vite.config.js。 */
.toolbar :deep(.el-date-editor) {
  width: 280px;
}
.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
}
.card {
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  padding: 18px 20px;
}
.card-label {
  font-size: 13px;
  color: #909399;
  margin-bottom: 8px;
}
.card-value {
  font-size: 26px;
  font-weight: 600;
  line-height: 1.2;
}
.caliber {
  grid-column: 1 / -1;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  padding: 16px 20px;
  font-size: 12px;
  color: #606266;
}
.caliber-title {
  font-size: 13px;
  font-weight: 600;
  margin: 0 0 8px;
  color: #303133;
}
.caliber ul {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
}
</style>
