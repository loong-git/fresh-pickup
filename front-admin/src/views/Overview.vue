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

      <!-- F-50：当日「微信支付回调拒收」那一格（本片要解决的就是「没人会每分钟去点审计页」）。
           回调被持续拒收（证书过期、时间戳窗口不符、金额不符）意味着<b>已扣款的订单会永久丢单</b>——
           这条链到这里本来是「运营能查但看不见」，所以把它挂到运营每天进来看的这一页上。
           整格可点（count=0 也可点，运营可能要翻某一天的历史），点了跳审计页并带上 actor/action 两枚筛选。
           形态纪律：告警态<b>只改颜色与边框</b>，零定位、不改网格，绝不压到别的卡片；
           数值与副行都是服务端给的原值，本页不换算、不格式化（见 script 段 D-50-7 那条）。
           F-53 ②（标清统计范围，纯文案）：范围写进 card-label，<b>不</b>写进副行——
           数值已在 card-value 报过一次，副行再报一次数值就是本仓清过的「同屏双报」，所以副行仍只有
           「最近一次 HH:mm」。另一半成因是：点了跳过去的审计页没有日期筛选、列的是全部历史，
           同屏两个数会被读成对不上，所以口径说明那条也补了「两个数都对、是范围不同」。
           F-54 改动 A（消掉 F-53 自己留下的松动）：F-53 把范围硬写成「（当日）」，但页顶可以选日期，
           选了之后这一格数的是所选那一天的窗口，此时 label 写「当日」就是谎报。现在 label 与零值支副行
           都只在「（当日）/（所选日）」两枚措辞里取值（同一判据 = pickedDate 是否为空），非零支副行至今
           一字未动；label <b>不</b>写具体日期值，理由见 script 段 F-54 改动 A 那条（与 D-50-7 同一写法，
           注释里不重复标识名，免得多一条要跟着改名的引用）。 -->
      <div class="card card--jump" :class="{ 'card--alert': notifyRejectAlert }"
           role="button" tabindex="0" title="跳到审计记录并自动筛出这些行"
           @click="goNotifyRejectAudit" @keyup.enter="goNotifyRejectAudit">
        <div class="card-label">支付回调拒收{{ notifyRejectScopeLabel }}</div>
        <div class="card-value">{{ metrics.notifyRejectCount }}</div>
        <div class="card-sub">{{ notifyRejectSubText }}</div>
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
          <li><b>支付回调拒收</b>那一格与前四键<b>同一个自然日窗口，但不是同一张表</b>：它数的是审计表里
              action = pay.notify_reject 的行数——微信回调被拒时由服务端自己写下，<b>不是某个运营人员做了这件事</b>。
              这一格与订单数/金额不可互相加减换算。副行「最近一次」是服务端定好的 HH:mm，本页只展示。
              label 与副行的窗口措辞只有「（当日）」与「（所选日）」两种，跟着页顶有没有选日期走，
              <b>不写具体日期</b>：不选日期时窗口由服务端 CURDATE() 决定今日，页顶选了就是所选那一天——
              具体是哪一天一律以页顶「统计窗口」那行为准，这里不重复报第二遍。
              点这一格跳过去的审计记录页<b>列表不限日期</b>（它一共只有操作者/动作/目标类型/目标 ID
              四个筛选框，<b>没有日期筛选</b>），所以这一格的数与审计页的「共 N 条」<b>不是同一个范围，
              两个数都对</b>——这一格报 2 次、跳过去看到「共 3 条」是范围不同（一天 vs 不限日期），
              不是哪一边数错了。</li>
        </ul>
      </div>
    </div>
  </div>
</template>

<script setup>
// 只读大盘（F-20.11 Step 4）：GET /api/admin/v2/overview?date=
// 出参六键 {orderCount, totalAmount, pendingPickupCount, paidAmount, notifyRejectCount, notifyRejectLastAt}
//（前四键 = F-20.9 #2 的既有形状，后两键 = F-50 追加的当日「微信支付回调拒收」读数，同一窗口不同表）。
// ⚠ 后端出参里**没有 date 回显**（实测键集合就是上面那六个），所以「统计窗口」这一行只能标成
//   「服务端 CURDATE()」或用户在页面上选定的那个值——绝不由前端拿本机时钟算出「今天是几号」再显示，
//   那会变成第二处「今日」的真值（后端 SQL 与前端 JS 时钟可以不一致）。
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getAdminOverview, retryAfterText } from '../api'
// F-54 改动 B：跳转 query 的 actor / action 两枚值不在本页内联。它们的真值在服务端，
// 定义收在 ../constants.js 一处，与审计页那枚预设同源——服务端改名时两端只有一处要改，
// 不会再出现「一处改了另一处静默失配、跳过去筛出 0 行」。（引用一律写符号名，不写行号——F-27/F-41 定案。）
import { AUDIT_ACTOR_SYSTEM_WX_NOTIFY, AUDIT_ACTION_PAY_NOTIFY_REJECT } from '../constants'

const router = useRouter()

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
  paidAmount: '-',
  // F-50 两键：占位形态与其余四格一致（未取到数据是 '-'，不是 0——0 是「确实没有拒收」这句话，不能白给）。
  // notifyRejectLastAt 的 null 是服务端语义（本窗口内无拒收；窗口是今日还是所选那天由 date 参数决定），
  // ready 态之后才参与渲染。
  notifyRejectCount: '-',
  notifyRejectLastAt: null
})

const scopeText = computed(() =>
  pickedDate.value
    ? `统计窗口：${pickedDate.value}（选定值）`
    : '统计窗口：今日（由服务端 CURDATE() 决定，前端不推算日期）'
)

// F-50（D-50-5）：count > 0 才挂告警态；'-'（未取到数）经 Number 是 NaN，NaN > 0 为 false，
// 所以首屏/失败态都不会「亮一件没发生的事」。告警只是颜色与边框（见 style 段），零定位。
const notifyRejectAlert = computed(() => Number(metrics.value.notifyRejectCount) > 0)

// F-54 改动 A：card-label 的窗口后缀只在「（当日）/（所选日）」两枚措辞里取值，**不把日期值写进 label**。
// 为什么不写具体日期（这是本改动唯一的落点，也是它不可能谎报的构造性理由）：
// load() 有 FE-G6-3 在飞守卫——用户在请求在飞时改日期，会被 if (viewState.value === 'loading') return 早退，
// 于是 pickedDate 可能已经变成新值、卡片上画的还是上一轮那批数。此时 label 若写成「（10-14）」，
// 就造出「label 说 10-14、数其实是 10-13」这种**新谎报**（与 F-53 消掉的「选了日期还写当日」同一族）。
// 而「当日 / 所选日」这两种措辞只跟「用户有没有选过日期」这个布尔态有关、与具体日期值无关，
// 因此在守卫早退的任何时刻都不会说错——它说的是范围类型，不是范围本身。
// 具体日期不在这里重复：页顶 scopeText 已给「统计窗口：YYYY-MM-DD（选定值）」，那一行读的也是同一个 pickedDate。
const notifyRejectScopeLabel = computed(() => (pickedDate.value ? '（所选日）' : '（当日）'))

// F-50（D-50-7）：这一行只做展示——HH:mm 是后端 DATE_FORMAT('%H:%i') 定好的串，
// 本页不许拼时区、不许减 8 小时、不许 new Date() 再格式化（本仓为「同屏双报」和「时长谎报」清过好几批）。
// 「有行就有 MAX」由同一条 SQL 保证，所以 count 与这句副行不会互相打脸。
// F-54 改动 A：零值支的窗口措辞与 card-label 用同一判据（pickedDate 是否为空），
// 否则会出现「label 写所选日、副行写当日无拒收」的自我打脸。非零支「最近一次 HH:mm」一字不动。
const notifyRejectSubText = computed(() =>
  metrics.value.notifyRejectLastAt
    ? `最近一次 ${metrics.value.notifyRejectLastAt}`
    : (pickedDate.value ? '所选日无拒收' : '当日无拒收')
)

// F-50：这一格是审计页那枚预设的入口。跳转只带 actor/action 两枚 query，
// 值与 AuditLogs.vue 的「微信回调拒收（系统）」预设同一（那边 onMounted 收 query 走既有 filters）。
// F-54 改动 B：这两枚值原先在本页与那页各内联一遍（共四份），现在两处都 import ../constants.js 的同一枚出口，
// 「同一」不再是抄来的巧合而是同一个绑定；服务端改名时只会有一处要改。
function goNotifyRejectAudit() {
  router.push({
    path: '/audit-logs',
    query: { actor: AUDIT_ACTOR_SYSTEM_WX_NOTIFY, action: AUDIT_ACTION_PAY_NOTIFY_REJECT }
  })
}

async function load() {
  // FE-G6-3 在飞守卫：请求未回时不再发第二条（日期 @change、「查询」与「重试」按钮同守这一条），
  // 否则乱序回包会把上一个窗口的数字画在当前 caption 下。照 Login.vue 的同一精神，
  // 复用本页已有的单值 viewState，不新增第二个状态变量、不加定时器。
  if (viewState.value === 'loading') return
  viewState.value = 'loading'
  errorText.value = ''
  errorData.value = null
  try {
    const envelope = await getAdminOverview(pickedDate.value)
    const data = envelope.data || {}
    // 逐键取并保留服务端形状：金额是服务端定形的两位小数串，不二次格式化、不做数字换算；
    // F-50 那两键同样原样收（计数是数字、时间是 HH:mm 串或显式 null）
    metrics.value = {
      orderCount: data.orderCount,
      totalAmount: data.totalAmount,
      pendingPickupCount: data.pendingPickupCount,
      paidAmount: data.paidAmount,
      notifyRejectCount: data.notifyRejectCount,
      notifyRejectLastAt: data.notifyRejectLastAt
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
    // 400（脏 date）/ 500 / 网络异常：后端 message 原样透出；429 会在其后追加服务端 data.retryAfterSeconds 给的秒数。
    viewState.value = 'error'
    const code = Number(e.code) || 0
    errorData.value = e.data || null
    // F-22 FE-429-2：**只在 429** 时把后端如实给的 retryAfterSeconds 转述出来（转述、非推断，
    // 与 views/Login.vue:43 同一口径）；400/500/-1 后面跟一句「约 N 秒后可再试」是错的，所以不拼。
    // 缺载荷 / 非正数 → retryAfterText 回 ''，本行读数与改动前逐字相同。
    // X-6（F-28.2 片 4）：这一行的分隔符**不在本页**——由 api 层 retryAfterText 自带前导空格给出。
    // 本页前段是后端 message 原文（R.java:26「操作过于频繁，请稍后再试」结尾无句号），
    // 页面若去判「末字符有没有句号」再决定补不补，就把前端呈现拴在了后端文案的标点形状上，属本条禁例；
    // 也不许在本页补句号——其余 5 页的提示表自带句号，各页各补就会各处长出双标点。
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
/* F-50：那一格整格可点，但只用 cursor 与副行提示它是个入口——不改尺寸、不改网格，
   五张卡片仍是同一张 grid 里的同一种盒子（第四列之后的第五格自然换行到下一行第一格）。 */
.card--jump {
  cursor: pointer;
}
/* F-50（D-50-5）：告警态**只用颜色与边框**表达（红边框 + 淡红底 + 数值转红），
   不加任何 position、不改 padding/尺寸，因此不可能压到相邻元素或口径说明块。
   正常态与告警态的盒子高度逐字相同，切换时网格不跳动。 */
.card--alert {
  border-color: #f56c6c;
  background: #fef0f0;
}
.card--alert .card-value {
  color: #c45656;
}
.card-sub {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
.card--alert .card-sub {
  color: #c45656;
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
