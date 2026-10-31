<template>
  <div class="page">
    <!-- FE-G6-10（读写可辨，照 Orders.vue 的范式）：本页有一个写动作（发车），徽标常驻页眉。 -->
    <div class="page-head">
      <h2 class="page-title">到货发车</h2>
      <el-tag type="danger" effect="dark" size="small" class="mode-badge">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      平台统一配货（中央仓凑一车送到自提点）：<b>运营选店、选日，点「发车」，明细由系统按该店该日的订单自动汇总</b>——本页不填任何数字。<br />
      发车后批次直接进入<b>「到货中」</b>，主理人端批次页立刻可见并可确认到货；确认后 C 端那笔订单会显示「货已到店，去拿货」。<br />
      应发件数把<b>未支付订单也计入</b>（与商家备货单口径逐字相同，两边数字必须一致，不一致是事故）。<br />
      重复发车是正常操作（发车后又来单）：该店该日批次还在「计划到货 / 到货中」时，再点一次会<b>按最新订单重算明细</b>；
      一旦批次已是「已确认 / 已登记短少」（货已收），再发车等于造假，服务端会 400 拒绝，本页按钮同步置灰。<br />
      日期缺省为<b>明天</b>（批次是给明天用的，与主理人端到货页同一口径）；过去日期服务端 400，本页日期框直接禁选昨天及以前。<br />
      预览与发车读写的都是服务端真值：发车提交后本页<b>重拉预览核对</b>，绝不在本地把状态改成「到货中」。
    </p>

    <!-- FE-G6-5：工具条住在状态块之外，四态都留着，改店/改日才有入口（照 Orders.vue）。 -->
    <div class="toolbar">
      <el-select v-model="storeId" size="small" class="filter-select" :clearable="false"
                 placeholder="自提点门店"
                 :disabled="viewState === 'loading' || submitting || storesLoading" @change="loadPreview">
        <el-option v-for="s in stores" :key="s.id" :label="storeLabel(s)" :value="s.id"
                   :disabled="s.status === 'closed'" />
      </el-select>
      <el-date-picker v-model="date" size="small" type="date" class="filter-date"
                      value-format="YYYY-MM-DD" format="YYYY-MM-DD" :clearable="false"
                      placeholder="发车日期（默认明天）" :disabled-date="disabledPastDate"
                      :disabled="viewState === 'loading' || submitting" @change="loadPreview" />
      <el-button size="small" type="primary" :disabled="viewState === 'loading' || submitting || !storeId || !date"
                 @click="loadPreview">重拉预览</el-button>
    </div>

    <!-- 门店列表读取失败/为空的结果条：也在状态块之外（预览区的报错与它的错不是同一件事，各摆各的）。 -->
    <div v-if="storesNote" class="result-bar" :class="'result-bar-' + storesNote.kind">
      <div class="result-title">{{ storesNote.title }}</div>
      <div v-if="storesNote.serverText" class="result-line">服务端原文：{{ storesNote.serverText }}</div>
      <div v-if="storesNote.hint" class="result-line">{{ storesNote.hint }}</div>
      <el-button v-if="storesNote.retryable" size="small" :disabled="storesLoading" @click="loadStores">重试门店列表</el-button>
    </div>

    <!-- 写动作结果条：状态块之外（发车成功那一刻预览进 loading、内容区被卸载，提示若在内容区会跟着消失）。 -->
    <div v-if="writeResult" class="result-bar" :class="'result-bar-' + writeResult.kind">
      <div class="result-title">{{ writeResult.title }}</div>
      <div v-if="writeResult.serverText" class="result-line">服务端原文：{{ writeResult.serverText }}</div>
      <div v-if="writeResult.hint" class="result-line">{{ writeResult.hint }}</div>
    </div>

    <div v-if="viewState === 'loading'" class="state-box">
      <div class="state-title">正在加载</div>
      <div class="state-desc">该店该日到货预览读取中…</div>
    </div>

    <div v-else-if="viewState === 'forbidden'" class="state-box">
      <div class="state-title">没有访问权限</div>
      <div class="state-desc">{{ errorText }}</div>
      <div class="state-desc">403 与 401 不同义：登录态是有效的，但服务端判定本账号不能读这一面。请核对运营账号的角色与授权。</div>
      <el-button size="small" @click="loadPreview">重试</el-button>
    </div>

    <div v-else-if="viewState === 'error'" class="state-box">
      <div class="state-title">读取失败</div>
      <div class="state-desc">{{ errorText }}</div>
      <div v-if="codeHint" class="state-desc">{{ codeHint }}</div>
      <el-button size="small" @click="loadPreview">重试</el-button>
    </div>

    <template v-else-if="preview">
      <!-- 汇总读数区：全部来自本轮预览回包（预览是本页唯一的状态真源，改店/改日/发车后都会重拉）。 -->
      <div class="summary">
        <div class="summary-item">
          <span class="summary-label">自提点</span>
          <span>{{ preview.storeName }}（门店 ID {{ preview.storeId }}）</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">发车日期</span>
          <span>{{ preview.date }}</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">订单数</span>
          <span>{{ preview.orderCount }} 笔</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">品种数</span>
          <span>{{ preview.dishKinds }} 个</span>
        </div>
        <div class="summary-item">
          <span class="summary-label">已有批次</span>
          <template v-if="preview.existing">
            <el-tag :type="statusTagType(preview.existing.status)" size="small" effect="light">
              {{ statusText(preview.existing.status) }}
            </el-tag>
            <span class="summary-sub">批次 #{{ preview.existing.id }} · 明细 {{ itemCountText(preview.existing.itemCount) }}</span>
            <span v-if="preview.existing.confirmTime" class="summary-sub">确认时间：{{ preview.existing.confirmTime }}</span>
            <span v-else class="summary-sub muted">尚无确认时间（还没人确认到货）</span>
          </template>
          <span v-else class="muted">该店该日还没有批次——点「发车」即新建并进入到货中</span>
        </div>
      </div>

      <div class="plan-head">应发明细（系统按该店该日订单汇总，本页不填数字；件数合计为对下表预览行数的求和）</div>
      <el-table :data="planRows" border stripe size="small" class="table">
        <template #empty>
          <span class="empty">该店该日暂无可发订单（服务端不会为它建空批次，发车会被 400 拒绝）</span>
        </template>
        <el-table-column prop="dishName" label="菜品" min-width="200" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.dishName }">{{ row.dishName == null || row.dishName === '' ? '（明细未带菜名）' : row.dishName }}</span></template>
        </el-table-column>
        <el-table-column prop="dishId" label="菜品 ID" min-width="100" />
        <el-table-column prop="qty" label="应发件数" min-width="110" />
      </el-table>
      <div v-if="planRows.length > 0" class="plan-total">合计 {{ totalQty }} 件（本页对上方预览行求和，读数出自服务端预览，本页不自造数值）</div>

      <div class="depart-bar">
        <el-input v-model="remark" size="small" class="remark-input" clearable
                  placeholder="发车备注（可选，随请求提交给服务端留痕）"
                  :disabled="viewState === 'loading' || submitting" />
        <el-button size="small" type="danger" :disabled="!canDepart" :loading="submitting" @click="onDepart">发车</el-button>
      </div>
      <!-- 置灰原因常驻说明：按钮灰了必须当场读得出为什么（照 Orders.vue「无可用动作」那句的姿势）。 -->
      <div v-if="departBlockReason" class="depart-block">{{ departBlockReason }}</div>
    </template>

    <div v-else class="state-box">
      <div class="state-title">等待选择门店</div>
      <div class="state-desc">门店列表还没有可用数据（读取失败或列表为空），上方结果条写了具体原因。选好自提点与日期后本页会自动拉取预览。</div>
    </div>
  </div>
</template>

<script setup>
// 到货发车（F-63）：运营端补上 arrival_batch 的创建方（平台统一配货，中央仓=平台自己）。
//   预览 = GET /api/admin/arrivals/preview?storeId=&date=（只读；出参八键，契约见 ../api 同名函数注释）
//   发车 = POST /api/admin/arrivals，body {storeId, batchDate, remark?}（Admin 旧面写端点，IP 10 次/分）
//   门店 = GET /api/stores（既有公开端点，匿名——带运营 JWT 反而被 AuthInterceptor 的 typ=admin 反隔离 403）
// 三条本页级纪律（逐条照 Orders.vue）：
//   ① 零裸 fetch、零自拼 URL，函数全从 ../api 拿（纪律 1）；
//   ② 发车必须过 askConfirm 二次确认，弹层把后果写全（哪个店、哪天、几笔订单、几个品种、是否重算），
//      点「确认执行」才发那一次 POST；取消/关闭/Esc 一律零请求（纪律 2）；
//   ③ 发车成功与否由重拉预览核对，绝不本地改状态——批次态、明细数只有服务端状态机知道（纪律 3）。
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { getStores, getArrivalPreview, departArrival, retryAfterText } from '../api'

// 四态文案与主理人端到货页（front-partner/pages/arrival/arrival.vue 的 STATUS_TEXT）同一份措辞，
// 两端读同一个批次说的是同一种话；表里没有的值一律原样显示，不编「未知状态」。
const STATUS_TEXT = { planned: '计划到货', arriving: '到货中', confirmed: '已确认', short: '已登记短少' }
const statusText = (v) => STATUS_TEXT[v] || (v == null ? '' : String(v))
const statusTagType = (v) => (v === 'arriving' ? 'warning' : v === 'confirmed' ? 'success' : v === 'short' ? 'danger' : 'info')
const itemCountText = (n) => (n == null ? '—' : n + ' 项')

const stores = ref([])
const storesLoading = ref(false)
const storesNote = ref(null)
const storeId = ref('')
// 日期缺省=明天（F-60/F-63 同一口径：批次是给明天用的）。本页永远显式带 date，不吃服务端缺省，
// 保证「页面显示的日期」与「请求用的日期」永远是同一个值。
const date = ref(tomorrowStr())

// viewState 只承担<b>预览读取</b>这一条状态机；发车在飞由 submitting 一个布尔位承担（同 Orders.vue）。
const viewState = ref('loading')
const errorText = ref('')
const errorCode = ref(0)
const errorData = ref(null)
const submitting = ref(false)
const writeResult = ref(null)

const preview = ref(null)
const remark = ref('')

const planRows = computed(() => (preview.value && Array.isArray(preview.value.plan) ? preview.value.plan : []))
// 合计只做预览行的求和（读数全部来自本轮服务端回包），不引入任何本地推算的件数
const totalQty = computed(() => planRows.value.reduce((acc, it) => acc + (Number(it && it.qty) || 0), 0))

// 发车闸门：读的是本轮预览回包里服务端的 departable 与 plan，本页不重拼该判定。
const canDepart = computed(() => {
  const p = preview.value
  return !!p && viewState.value === 'ready' && !submitting.value
    && p.departable !== false && planRows.value.length > 0
})
const departBlockReason = computed(() => {
  const p = preview.value
  if (!p || viewState.value !== 'ready') return ''
  if (p.departable === false) {
    const st = statusText(p.existing && p.existing.status)
    return '按钮置灰原因：该店该日批次已是「' + (st || '终态') + '」——货已收，再发车等于造假，服务端会 400 拒绝，本页不提供给药方入口。'
  }
  if (planRows.value.length === 0) {
    return '按钮置灰原因：该店该日无可发订单（orderCount=' + (p.orderCount == null ? '?' : p.orderCount) + '）。服务端不会建空批次（400），换日期/换门店，或等订单进来再发这一车。'
  }
  return ''
})

function fmtDate(d) {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return y + '-' + m + '-' + day
}
function tomorrowStr() {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  return fmtDate(d)
}
// 过去日期在服务端是判据②直接 400（batchDate < 今天），本页把昨天及以前直接在日期框禁选——
// 这是把服务端已有的闸门复述一遍，不是本页另立判据；今天仍可发车（只有严格早于今天才被拒）。
function disabledPastDate(time) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return time.getTime() < today.getTime()
}
// 门店下拉读数：名字 + ID + 地址；status=closed 的置灰不可选是全站既有约定
// （StoreController 类注释 + C 端 pickup 页同姿势），不是本页新发明的限制。
function storeLabel(s) {
  const closed = s.status === 'closed' ? '（已停业，不可发车）' : ''
  const addr = s.address ? ' · ' + s.address : ''
  return s.name + '（ID ' + s.id + '）' + addr + closed
}

// ---------- 读侧「下一步」文案（400 两句通用原文的纪律照 Orders.vue：本页不猜命中哪一句） ----------
const CODE_HINTS = {
  400: '请求参数不在允许范围内。服务端对 400 不说明命中哪一句，本页不猜，以上方后端原文为准。'
    + '本页可能触发 400 的形状：storeId 缺失或绑不进数字、date 不是 YYYY-MM-DD 的合法日期、'
    + '发车日期早于今天（预览侧对过去日期是否 400 以服务端原文为准——发车侧钉死是 400）。',
  404: '预览接口回了 404。两种可能本页不猜哪一种：① 该端点尚未部署到当前运行的后端'
    + '（F-63 是新增端点，旧字节码没有它，此时任何 storeId/date 都是 404）；② 门店不存在（发车侧对查无此店回「门店不存在」）。'
    + '请确认后端已更新至含 F-63 的版本且服务在运行，再点「重试」。',
  429: '读取过于频繁（预览按来源 IP 限流，与只读列表同档 30 次/分）。请勿连点「重拉预览」。',
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一条读取时自己抛的。'
    + '本页这次读数不可信——不要拿这一屏的汇总件数当真值用。'
    + '下一步：稍后点「重拉预览」；若同一条件反复出现，请让运维查服务端日志（本页不猜具体原因）。',
  '-1': '请求未能送达（网络异常或 10s 查询档超时）。请确认后端服务已启动，并用 http://localhost:5177 打开本页面'
    + '（用 127.0.0.1 形态打开会被后端 CORS 全拦，渲染成这一条假故障——后端其实活得好好的）。'
}
// 未知码走 ''：「宁可不说话也不猜」。只有 code 恰为 -1 才允许说「未能送达」。
// 429 的秒数只转述后端 data.retryAfterSeconds 的真值（用全站既有的 retryAfterText：运营面所有端点
// windowSeconds 恒为 60，冷却上界就是 60 秒，主理人端那套 >60 的中文分档在本页永远走不到，
// 故不为它开第二个函数——详见 api/index.js 里 retryAfterText 的说明）
// 缺载荷回 ''，本页退回上面那句不带秒数的文案，绝不自己编一个冷却时长）。
const codeHint = computed(() => (CODE_HINTS[String(errorCode.value)] || '')
  + (errorCode.value === 429 ? retryAfterText(errorData.value) : ''))

// ---------- 写侧「下一步」文案 ----------
const WRITE_CODE_HINTS = {
  400: '这笔发车被 400 拒绝。400 不说明命中哪一句，本页不猜。通用两句是'
    + '「请求参数有误，请检查后重试」与「参数格式不正确」；本端点的业务判据（顺序在服务端已钉死）：'
    + '发车日期早于今天 → 400；该店该日批次已是 confirmed/short（货已收，再发车是造假）→ 400；'
    + '该店该日无可发订单（不建空批次）→ 400；并发下真撞了该店该日唯一键 → 400「该店该日已有批次，请刷新后重试」。'
    + '请以「服务端原文」那一行为准，逐句对照后再改。',
  403: '登录态有效但服务端判定本账号不能发车（403 ≠ 登录失效，本页不清登录态）。'
    + '发车会新建到货批次，请核对运营账号的角色与授权。',
  404: '后端原文应为「门店不存在」：所选门店 id 在库里已经没有（或本页门店列表已过期）。'
    + '下一步：点上方「重试门店列表」重取门店，再对现存的门店操作。',
  429: '发车请求过于频繁（Admin 写面按来源 IP 限流 10 次/分，比预览更紧）。一天一车发完就停，不要连点。',
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这笔发车时自己抛的——不是没送到。'
    + '⚠ 这一笔到底有没有建批次不作数（发车是整体事务，要么批次+明细+留痕全成要么全滚，但回执不可信）。'
    + '下一步：点上方「重拉预览」核对 existing——若已出现「到货中」的批次，说明那一笔已成功，'
    + '再点一次也只会按最新订单重算明细、不会建出第二个批次；若仍无批次，稍后重试或让运维查日志（本页不猜原因）。',
  '-1': '请求未能送达（网络异常或 20s 提交档超时）。⚠ 超时不等于「没生效」：服务端可能已经建完批次，只是回执没回来。'
    + '下一步：先点上方「重拉预览」核对 existing，再决定要不要重发——批次已在途时重发是安全的幂等重算，不是重复建批。'
}
function writeHintOf(code, data) {
  const key = String(Number(code))
  const known = WRITE_CODE_HINTS[key]
  if (known !== undefined) return known + (Number(code) === 429 ? retryAfterText(data) : '')
  return '服务端回了本页没有预置说明的码 ' + key + '。本页不猜原因，以「服务端原文」那一行为准；'
    + '如需核对可点上方「重拉预览」再看一次。'
}

// ---------- 门店列表（既有公开端点，匿名 GET；失败不影响预览区自身状态机，只挂结果条） ----------
async function loadStores() {
  storesLoading.value = true
  storesNote.value = null
  try {
    const envelope = await getStores()
    const list = Array.isArray(envelope.data) ? envelope.data : []
    stores.value = list
    if (list.length === 0) {
      storesNote.value = {
        kind: 'warning', title: '门店列表为空', serverText: '', retryable: true,
        hint: 'GET /api/stores 回了空数组——当前一个自提点都没有，发车无处可发。请先在「入驻审核」页通过并发布一家门店。'
      }
      preview.value = null
      viewState.value = 'idle'
    } else {
      // 默认选第一家营业门店；全停业时保留空选合并如实说明（停业置灰是全站约定，本页不越权替运营选停业店）
      if (storeId.value === '' || !stores.value.some((s) => s.id === storeId.value)) {
        const open = stores.value.find((s) => s.status !== 'closed')
        storeId.value = open ? open.id : ''
        if (!open) {
          storesNote.value = {
            kind: 'warning', title: '门店全部停业', serverText: '', retryable: true,
            hint: '列表里有门店但 status 全是 closed（已停业），下拉框已置灰不可选——停业自提点发车没有收货方。'
          }
        }
      }
      if (storeId.value !== '') return // 由 onMounted / 调用方决定要不要紧接着拉预览
      preview.value = null
      viewState.value = 'idle'
    }
  } catch (err) {
    const e = err || {}
    stores.value = []
    storeId.value = ''
    preview.value = null
    viewState.value = 'idle'
    storesNote.value = {
      kind: 'error',
      title: '门店列表读取失败（不影响下面的预览区）',
      serverText: e.message || '（后端未给出 message）',
      retryable: true,
      // 秒数只在 429 时转述后端真值，其余码不拼
      hint: Number(e.code) === 429
        ? '门店列表按来源 IP 限流 30 次/分，请稍候再试。' + retryAfterText(e.data)
        : '数据源是既有公开端点 GET /api/stores（匿名可读）。若这一条写着 403，多半是凭证姿势问题不是权限问题：'
          + '本请求已按 typ 隔离纪律不带 Authorization；若将来改成了带运营 JWT 的调用，会被服务端反隔离整段拦下。'
    }
  } finally {
    storesLoading.value = false
  }
}

// ---------- 预览读取（本页唯一状态真源；改店/改日/发车成功都回到这里重拉） ----------
async function loadPreview() {
  // 在飞守卫（同 Orders.vue：请求未回时不再发第二条，守卫复用单值 viewState）
  if (viewState.value === 'loading') return
  if (storeId.value === '' || !date.value) {
    preview.value = null
    viewState.value = 'idle'
    return
  }
  viewState.value = 'loading'
  errorText.value = ''
  errorCode.value = 0
  errorData.value = null
  try {
    const envelope = await getArrivalPreview(storeId.value, date.value)
    preview.value = envelope.data || null
    if (!preview.value) {
      viewState.value = 'error'
      errorCode.value = 0
      errorText.value = '预览接口回了 200 但 data 是空——没有可渲染的八键读数，本页不拿空壳当 ready 态。'
      return
    }
    // 显示日期一律以回包 date 回显为准（预览契约回显该键）；回显缺失才退回本次请求值
    if (preview.value.date) date.value = String(preview.value.date)
    if (preview.value.storeId !== undefined && preview.value.storeId !== null) storeId.value = preview.value.storeId
    viewState.value = 'ready'
  } catch (err) {
    const e = err || {}
    preview.value = null
    if (e.code === 401) {
      // 401 由请求层收敛（清 token + 跳登录页），页面只留一句过渡文案
      viewState.value = 'error'
      errorCode.value = 401
      errorText.value = '登录状态已失效，正在返回登录页…'
      return
    }
    if (e.code === 403) {
      viewState.value = 'forbidden'
      errorText.value = e.message || '无权限访问'
      return
    }
    viewState.value = 'error'
    errorCode.value = Number(e.code) || 0
    errorData.value = e.data || null
    errorText.value = e.message || '到货预览读取失败，请稍后重试'
  }
}

/**
 * 二次确认（纪律 2，逐字照 Orders.vue 的 askConfirm）：只有点「确认执行」才返回 true；
 * 点「取消」/右上角关闭/Esc 在 ElMessageBox 里一律 reject → 这里吞掉返回 false，调用方当场 return，
 * 一条请求都不发。customClass 由 App.vue 的全局样式把 \n 渲染成真换行。
 */
async function askConfirm(message) {
  try {
    await ElMessageBox.confirm(message, '请确认这笔发车', {
      type: 'warning',
      confirmButtonText: '确认执行',
      cancelButtonText: '取消（不发出任何请求）',
      closeOnClickModal: false,
      distinguishCancelAndClose: true,
      customClass: 'admin-write-confirm'
    })
    return true
  } catch (e) {
    return false
  }
}

// 确认文案：把后果写全（店、日、单数、品种数、是否重算，逐条来自 F-63 契约的判据表），不写「确定吗？」。
function confirmTextOf(p, trimmedRemark) {
  const lines = []
  lines.push('将向自提点「' + p.storeName + '」（门店 ID ' + p.storeId + '）发出 ' + p.date + ' 的到货批次（发车）。')
  lines.push('')
  if (p.existing) {
    lines.push('· 该店该日已有批次（id ' + p.existing.id + '，状态：' + statusText(p.existing.status)
      + '，明细 ' + itemCountText(p.existing.itemCount) + '）。本次发车是【重算】：会按最新订单删旧明细、重插新明细，'
      + '批次状态不变——发车后又来单是正常操作，这一笔就是把新单装进同一车。')
  } else {
    lines.push('· 该店该日还没有批次：本次发车会【新建】批次并直接进入「到货中」，'
      + '主理人端批次页立刻能看到它并点确认到货；确认后 C 端对应订单显示「货已到店，去拿货」。')
  }
  lines.push('· 汇总范围：' + p.orderCount + ' 笔订单、' + p.dishKinds + ' 个品种、共 ' + totalQty.value + ' 件'
    + '（件数为本页对预览明细行的求和；未支付订单也计入，与商家备货单同口径）。')
  lines.push('· 明细由系统汇总生成，本页不填任何数字；本页没有「撤销发车」入口，货收好后（已确认/已登记短少）这一天就再也发不了车。')
  lines.push('· 重复点第二次不会建出第二个批次（该店该日唯一），只会再按最新订单重算一次明细。')
  lines.push(trimmedRemark === ''
    ? '· 备注留空：整个 remark 键不下发，服务端按无备注处理。\n'
    : '· 将随请求提交发车备注（remark）：「' + trimmedRemark + '」。\n')
  lines.push('· 提交成功后本页会重拉预览核对，绝不在本地把状态改成「到货中」。')
  return lines.join('\n')
}

async function onDepart() {
  // 写在飞守卫（与按钮 :disabled 互为双保险）
  if (submitting.value || viewState.value === 'loading') return
  const p = preview.value
  if (!p) return
  const trimmedRemark = String(remark.value == null ? '' : remark.value).trim()
  const ok = await askConfirm(confirmTextOf(p, trimmedRemark))
  if (!ok) {
    writeResult.value = {
      kind: 'info',
      title: '已取消，未发出任何请求',
      serverText: '',
      hint: '确认框点「取消」/关闭/Esc 只关框：没有发车、没有建批次、没有重算明细，页面与库里都没变。'
    }
    return
  }
  submitting.value = true
  writeResult.value = null
  try {
    // body 键名照契约逐字：batchDate（不是预览的 date）；remark 空则整个键不下发（../api 里收口）
    const envelope = await departArrival(p.storeId, p.date, trimmedRemark)
    const d = (envelope && envelope.data) || {}
    writeResult.value = {
      kind: 'success',
      title: d.idempotent === true ? '已在途批次已按最新订单重算明细' : '发车已提交',
      serverText: envelope && envelope.message ? envelope.message : '',
      hint: '服务端回：批次 #' + (d.batchId == null ? '—' : d.batchId)
        + '，状态 ' + statusText(d.status) + '，明细 ' + itemCountText(d.itemCount)
        + '，' + (d.dishKinds == null ? '?' : d.dishKinds) + ' 个品种 / ' + (d.orderCount == null ? '?' : d.orderCount) + ' 笔订单'
        + '，idempotent=' + (d.idempotent === true ? 'true（重算，未新建）' : d.idempotent === false ? 'false（新建批次）' : '—') + '。'
        + '预览已重拉——批次是否真的进入到货中，以下面 existing 那一行的重拉读数为准，本页不拿回执当状态。'
    }
    remark.value = ''
    // 纪律 3：写成功与否由重拉核对（loadPreview 自带在飞守卫，先把 viewState 让开）
    await loadPreview()
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
      writeResult.value = { kind: 'error', title: '登录状态已失效', serverText: '', hint: '正在返回登录页…' }
      return
    }
    // 失败时不本地改任何状态：预览那一轮读数仍是服务端上一轮给的真值，只把后端原文与「下一步」摆出来
    writeResult.value = {
      kind: 'error',
      title: e.code === 403 ? '没有权限执行发车' : '发车未完成（是否生效以重拉为准）',
      serverText: e.message || '（后端未给出 message）',
      hint: writeHintOf(e.code, e.data)
    }
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  // 门店是预览的前提：先门店、后预览（两段各自的状态互不覆盖）。
  await loadStores()
  if (storeId.value !== '') {
    // 首屏在飞守卫同 Orders.vue：viewState 初值 'loading' 会被守卫当「已在飞」永久早退，先落 'idle' 再进
    viewState.value = 'idle'
    loadPreview()
  }
})
</script>

<style scoped>
/* 结果条样式与 Orders.vue / StoreApplies.vue 同一份（三页各留一份 scoped，不抽公共件——照既有现状） */
.result-bar {
  margin-top: 12px;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  border-left: 3px solid #57534E;
}
.result-bar-success {
  border-left-color: #0F7B4A;
}
.result-bar-error {
  border-left-color: #DC2626;
}
.result-bar-info {
  border-left-color: #D97706;
}
.result-bar-warning {
  border-left-color: #D97706;
}
.result-title {
  font-size: 13px;
  font-weight: 600;
  color: #1C1917;
}
.result-line {
  font-size: 12px;
  line-height: 1.7;
  color: #57534E;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.filter-select {
  width: 320px;
}
.toolbar :deep(.el-date-editor) {
  width: 280px;
}
.summary {
  margin-top: 16px;
  padding: 12px 14px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(28, 25, 23, 0.06);
  display: flex;
  flex-wrap: wrap;
  gap: 8px 28px;
}
.summary-item {
  font-size: 13px;
  line-height: 1.8;
  display: flex;
  align-items: center;
  gap: 8px;
}
.summary-label {
  color: #57534E;
}
.summary-sub {
  color: #57534E;
  font-size: 12px;
}
.plan-head {
  margin-top: 16px;
  font-size: 12px;
  color: #57534E;
}
.table {
  background: #fff;
  margin-top: 8px;
}
.plan-total {
  margin-top: 8px;
  font-size: 12px;
  color: #57534E;
  text-align: right;
}
.depart-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.remark-input {
  width: 360px;
}
.depart-block {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.7;
  color: #B45309;
}
.empty {
  color: #57534E;
  font-size: 13px;
}
.muted {
  color: #57534E;
}
</style>
