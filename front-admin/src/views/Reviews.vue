<template>
  <div class="page">
    <!-- FE-G6-10（读写可辨）：徽标常驻页眉、ready 态就可见（原先这类话只写在 state-box 里，出错才看得到）。
         本页的 reject 是物理删行、不可恢复，所以常驻「本页会改数据」。 -->
    <div class="page-head">
      <h2 class="page-title">评价审核</h2>
      <el-tag type="danger" effect="dark" size="small" class="mode-badge">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      全量评价队列（运营面）+ 一个写动作的两种取值：过审（approve）与驳回（reject）。<br />
      列表源是<b>只读</b>的 GET /api/admin/v2/reviews：外层恒 {list,total,pageNum,pageSize,filters}，排序 id DESC（最新在前）；
      唯一筛选 auditStatus 是两枚 <b>UI 伪值</b>——pending=待人工复审、visible=展示中（服务端 SQL 侧把 visible 映射成
      audit_status IS NULL），白名单外一律 400。<br />
      ⚠ <b>库里不存在「已驳回」这一态</b>：reject 在 Service 里是 deleteById 物理删行，删掉就没有这一行了，
      所以本页只有「待人工复审」和「展示中」两档，没有「已驳回列表」这种东西。<br />
      ⚠ 行内<b>没有菜品名</b>（出参只有 dishId 这个指针）：要看名字请点上方「按菜品 ID 对齐名称」，
      本页拿商品列表的 id→名称映射在前端对齐，绝不为此改动后端。<br />
      图片列（P10-6 A5 评价带图）：出参每行带 images（/uploads/reviews/... 相对路径列表，每评价 ≤3 张，无图为空数组）；
      前端经请求层 resolveFileUrl 拼后端基址直读（运营端刻意不配 vite proxy，见 vite.config.js 注释），
      点缩略图可放大预览，无图行该格空白不渲染。
      时间列 createTime 是服务端 DATE_FORMAT 定形串，原样显示；行里的 auditStatus 是库里原值（'pending' 或空=展示中）。
    </p>

    <div class="sql-fallback">
      <b>SQL 兜底</b>：待人工复审的评价怎么处理，见 <code>docs/日常运营SQL手册.md</code> <b>第六节</b>。
      注意该节覆盖的是「让它对外展示」这一步；<b>驳回（物理删行）在 SQL 侧没有等价姿势</b>——
      删行会连带影响菜品好评分的分子分母，只有本页面这一个端点会照业务口径处理。
    </div>

    <!-- FE-G6-5：工具条在状态块之外，四态都留着；loading / 写在飞时 disabled 但不消失。 -->
    <div class="toolbar">
      <el-select v-model="filters.auditStatus" size="small" class="filter-select" clearable
                 placeholder="审核状态（两枚伪值，精确）"
                 :disabled="viewState === 'loading' || submitting" @change="onFilterChange">
        <el-option label="待人工复审 pending" value="pending" />
        <el-option label="展示中 visible" value="visible" />
      </el-select>
      <el-button size="small" type="primary" :disabled="viewState === 'loading' || submitting" @click="onFilterChange">查询</el-button>
      <el-button size="small" :disabled="viewState === 'loading' || submitting" @click="onReset">重置</el-button>
      <!-- 菜品名对齐是<b>显式按需</b>的一次只读请求：首屏那一条列表请求不掺第二条（门禁断言的是「首屏恰 1 条列表请求」）。 -->
      <el-button size="small" :disabled="viewState === 'loading' || submitting" @click="loadDishNames">
        {{ dishNameMap === null ? '按菜品 ID 对齐名称' : '重新对齐名称' }}
      </el-button>
    </div>

    <div v-if="writeResult" class="result-bar" :class="'result-bar-' + writeResult.kind">
      <div class="result-title">{{ writeResult.title }}</div>
      <div v-if="writeResult.serverText" class="result-line">服务端原文：{{ writeResult.serverText }}</div>
      <div v-if="writeResult.hint" class="result-line">{{ writeResult.hint }}</div>
    </div>

    <div v-if="viewState === 'loading'" class="state-box">
      <div class="state-title">正在加载</div>
      <div class="state-desc">评价队列读取中…</div>
    </div>

    <div v-else-if="viewState === 'forbidden'" class="state-box">
      <div class="state-title">没有访问权限</div>
      <div class="state-desc">{{ errorText }}</div>
      <div class="state-desc">403 与 401 不同义：登录态是有效的，但服务端判定本账号不能读这一面。请核对运营账号的角色与授权。</div>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <div v-else-if="viewState === 'error'" class="state-box">
      <div class="state-title">读取失败</div>
      <div class="state-desc">{{ errorText }}</div>
      <div v-if="codeHint" class="state-desc">{{ codeHint }}</div>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <template v-else>
      <!-- FE-G6-9 表头粘性（理由与 offset 实算见 Orders.vue 同名注释）：表体内部滚动、表头常驻 -->
      <!-- P11-1b D8 批量审核：selection 列全行可选；row-key=id + reserve-selection 让选择跨翻页/刷新保留
           （reject 物理删行后保选 key 失主，批量回执按条记「评价不存在」，随后本页统一清选）。 -->
      <el-table ref="tableRef" :data="rows" :row-key="rowKeyOf" border stripe size="small" class="table" max-height="calc(100vh - 420px)" @selection-change="onSelectionChange">
        <template #empty>
          <span class="empty">暂无评价（当前筛选下没有命中；现库 review 表本就可能是空的，这不是故障）</span>
        </template>
        <el-table-column type="selection" width="42" reserve-selection fixed="left" />
        <el-table-column prop="id" label="ID" min-width="90" />
        <el-table-column label="菜品" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="dishNameOf(row.dishId)">{{ dishNameOf(row.dishId) }}（#{{ row.dishId }}）</span>
            <span v-else class="muted">#{{ row.dishId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="rating" label="评分" min-width="80" />
        <el-table-column prop="reviewer" label="评价人" min-width="120" show-overflow-tooltip />
        <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.content }">{{ row.content }}</span></template>
        </el-table-column>
        <!-- P10-6 A5 收尾（评价带图）：images 是后端 /uploads/reviews/... 相对路径列表，经请求层 resolveFileUrl
             补 BASE_URL 直读后端静态映射（运营端无 vite proxy——vite.config.js 注释点名相对路径必须拼完整后端地址）；
             点击放大走 el-image 自带 preview-src-list + preview-teleported（防被表格 overflow 裁剪），
             姿势照 MerchantApplies.vue 的证照缩略图。无图（images 空/缺键）整格不渲染，不出占位假图。 -->
        <el-table-column label="图片" min-width="150">
          <template #default="{ row }">
            <div v-if="reviewImageUrlsOf(row).length" class="review-imgs">
              <el-image v-for="(url, index) in reviewImageUrlsOf(row)" :key="url + ':' + index"
                        class="review-thumb" :src="url" :preview-src-list="reviewImageUrlsOf(row)"
                        :initial-index="index" fit="cover" preview-teleported>
                <template #error><div class="review-img-fallback">加载失败</div></template>
              </el-image>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="审核状态" min-width="130">
          <template #default="{ row }">
            <el-tag :type="row.auditStatus === 'pending' ? 'warning' : 'success'" size="small" effect="light">
              {{ auditText(row.auditStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="160" />
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <!-- approve 对「已是展示中」的行是空转（置 NULL 再置 NULL），所以只给待复审行摆这个按钮；
                 reject 对任何行都有效（它删行），所以两档都给。这不是本地改行，读的是服务端刚回的行值。 -->
            <el-button v-if="row.auditStatus === 'pending'" size="small" type="success" plain
                       :disabled="viewState === 'loading' || submitting"
                       @click="onWrite(row, 'approve')">过审展示</el-button>
            <el-button size="small" type="danger" plain
                       :disabled="viewState === 'loading' || submitting"
                       @click="onWrite(row, 'reject')">驳回并删除</el-button>
            <span v-if="row.auditStatus !== 'pending'" class="muted inline-note">（已是展示中，无需再过审）</span>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="dishNameNote" class="align-note">{{ dishNameNote }}</div>

      <!-- P11-1b D8 底部批量操作条：选中>0 才可点；驳回在 onBatch 内另有 ElMessageBox 强二次确认
           （文案列出条数与「物理删除不可恢复」警示）。按钮常驻不 v-if 抖动，禁用态即状态。 -->
      <div class="batch-bar">
        <span class="batch-count">已选 {{ selectedRows.length }} 条（选择跨翻页保留）</span>
        <el-button size="small" type="success" plain
                   :disabled="!selectedRows.length || submitting || viewState === 'loading'"
                   @click="onBatch('approve')">批量过审</el-button>
        <el-button size="small" type="danger" plain
                   :disabled="!selectedRows.length || submitting || viewState === 'loading'"
                   @click="onBatch('reject')">批量驳回</el-button>
        <el-button v-if="selectedRows.length" size="small" link
                   :disabled="submitting" @click="clearSelection">取消选择</el-button>
      </div>

      <div class="pager">
        <el-pagination
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="PAGE_SIZES"
          layout="total, sizes, prev, pager, next"
          background
          @size-change="onSizeChange"
          @current-change="onPageChange"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
// 评价审核（F-20.22 Step 5b-2 四页之一）：
//   列表 = GET /api/admin/v2/reviews（5b-1 交付；唯一筛选 auditStatus 是两枚 UI 伪值，D-17 的 SQL 映射钉死
//          pending → audit_status='pending'、visible → audit_status IS NULL）
//   写   = PUT /api/admin/reviews/{id}/audit，body {action: approve|reject}（AdminController 旧面，运营 JWT 第二轨）
// 两个必须由页面说清楚的实况：
//   ① reject 是 <b>物理删除</b>（AdminServiceImpl#auditReview 的 deleteById），不可恢复，且库里因此不存在「已驳回」态；
//   ② 出参<b>没有 dishName</b>。对齐名称只能拿同批的 dishes 只读列表在前端做 id→名称映射（契约的指定做法），
//      绝不为此改后端。这一步做成<b>按需点击</b>的：门禁断言「每页首屏恰 1 条列表请求」，
//      所以首屏只发 reviews 那一条，名称对齐是运营自己点的第二条只读请求。
// P10-6 A5 收尾：出参每行新增 images（后端复用 ReviewService#fillImages 一条 IN 查询批量填充的
// /uploads/reviews/... 相对路径列表），本页经请求层 resolveFileUrl 拼后端基址后用 el-image 展示与预览。
// P11-1b D8 批量审核：POST /api/admin/v2/reviews/batch-audit（body {ids, action}），逐条独立执行、
// 单条失败不断批；回执 {succeeded, failed:[{id,reason}]}。选择列跨翻页保留（row-key + reserve-selection）。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminReviews, getAdminDishes, auditAdminReview, batchAuditAdminReviews, retryAfterText, resolveFileUrl } from '../api'

const PAGE_SIZES = [10, 20, 50, 100]

// 行里 auditStatus 是库里原值：'pending' 或 null（=展示中）。null 不是「没查到」，D-17/D-18 的语义就是「已放行」。
const auditText = (v) => (v === 'pending' ? '待人工复审' : v === null || v === undefined || v === '' ? '展示中' : String(v))

const viewState = ref('loading')
const errorText = ref('')
const errorCode = ref(0)
// F-22 FE-429-2：读侧 429 的 e.data（只为转述 retryAfterSeconds；写侧直接把 e.data 递给 writeHintOf）
const errorData = ref(null)
const submitting = ref(false)
const writeResult = ref(null)

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const filters = ref({ auditStatus: '' })

// P11-1b D8 批量审核：selection 列的选中行数组（selection-change 维护）；tableRef 供 clearSelection 收口。
// row-key=id + reserve-selection：翻页/刷新后按 id 恢复选中——批量回执后必须显式 clearSelection，
// 否则被 reject 物理删除的行虽已不在列表，其保选 key 仍会出现在下一次 selection-change 里。
const tableRef = ref(null)
const selectedRows = ref([])
const rowKeyOf = (row) => row.id

function onSelectionChange(val) {
  selectedRows.value = val || []
}

function clearSelection() {
  if (tableRef.value && typeof tableRef.value.clearSelection === 'function') {
    tableRef.value.clearSelection()
  }
  selectedRows.value = []
}

// dishNameMap === null 表示「还没点过对齐」；点过之后是一个 Map（可能为空 Map）。
const dishNameMap = ref(null)
const dishNameNote = ref('')

const CODE_HINTS = {
  400: '请求参数不在允许范围内。服务端对 400 有两句原文且不说明命中哪一句，本页不猜，以上方后端原文为准：'
    + '「请求参数有误，请检查后重试」= 值过不了白名单/形状闸门（auditStatus 不是 pending/visible 这两枚伪值之一、页码 <1 或每页超出 1~100）；'
    + '「参数格式不正确」= 值绑不进目标类型（pageNum/pageSize 填了非整数）。'
    + '分页请核对：页码从 1 起、每页只能选 10/20/50/100 四档。',
  404: '评价队列接口不可达（后端服务未起或路径已变）。请确认后端服务在运行，再点「重试」。',
  429: '读取过于频繁（只读列表按来源 IP 限流 30 次/分）。请稍候再翻页。',
  // FE-G6-7（读侧）：500 是服务端接住这一请求后自己抛的，跟「-1」（请求层判定的网络失败/abort）是两回事。
  // 这一条不许出现「网络异常/超时/检查代理」字样。
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一条读取时自己抛的。'
    + '本页这次读数不可信——不要拿这一屏的审核状态当库内真值用（尤其别把「看不到」当成「已被驳回」）。'
    + '下一步：稍后点上方「查询」或「重试」重拉本列表核对；若同一条件反复出现这一条，请让运维查服务端日志（本页不猜具体原因）。',
  '-1': '请求未能送达（网络异常或 10s 查询档超时）。请确认后端服务已启动，并用 http://localhost:5177 打开本页面'
    + '（用 127.0.0.1 形态打开会被后端 CORS 全拦，渲染成这一条假故障）。'
}
// 未知码走 ''：与 AuditLogs.vue 同一份「宁可不说话也不猜」（FE-G6-7 纪律 4）。
// 只有 code 恰为 -1 时，CODE_HINTS['-1'] 那条关于链路/超时的文案才被引用。
// F-22 FE-429-2：读侧只有 429 才追加工具层转述的那句「服务端约 N 秒后可再试。」；
// 400/404/500/-1 一律不拼（对它们是假信息），缺载荷时 retryAfterText 回 '' → 本条与改动前逐字相同。
const codeHint = computed(() => (CODE_HINTS[String(errorCode.value)] || '')
  + (errorCode.value === 429 ? retryAfterText(errorData.value) : ''))

const WRITE_CODE_HINTS = {
  400: '这笔写请求被 400 拒绝。400 不说明命中哪一句，本页不猜：通用两句是'
    + '「请求参数有误，请检查后重试」与「参数格式不正确」；本端点自己的业务文案是'
    + '「action 仅允许 approve（过审展示）或 reject（驳回删除）」、「action 不能为空」。'
    + '请以「服务端原文」那一行为准。',
  403: '登录态有效但服务端判定本账号不能审评价（403 ≠ 登录失效，本页不清登录态）。'
    + '请核对运营账号的角色与授权。',
  404: '后端原文应为「评价不存在」。两种可能都要想到：'
    + '① 这一行刚被别的审核「驳回删除」了（reject 是物理删行，删掉就查无此评价，重复驳回必然落这一句）；'
    + '② 本页列表已过期。下一步：用上方「查询」重取列表，再对表里现存的 ID 操作。',
  429: '写请求过于频繁（Admin 写面按来源 IP 限流 10 次/分，比列表的 30 次/分更紧）。'
    + '批量过审请分时段，不要连点。',
  // FE-G6-7（写侧）：500 ≠ 未送达。请求已经打到服务端、被接住、由服务端自己抛的；
  // 把这一条继续渲染成「请求未能送达」会把运营引向"查网络/重启"的错误方向。
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一笔审核时自己抛的——不是没送到。'
    + '⚠ 这一笔到底有没有生效不作数（服务端既然抛了 500，回执不可信；reject 有可能已经把行删了、approve 有可能已经置好态）。'
    + '下一步：点上方「查询」重拉列表核对——这一行不见了就是已经删除成功、显示「展示中」就是过审到位；'
    + '拿不准就先不要重发（幂等早退会让第二次拿到别的错）。反复出现，请让运维查服务端日志（本页不猜原因）。',
  '-1': '请求未能送达（网络异常或 20s 提交档超时）。⚠ 超时不等于「没生效」：approve 可能已经置好、reject 可能已经把行删掉。'
    + '下一步：先点上方「查询」重拉列表核对——这一行不见了就是已经删除成功，别再重做驳回。'
}
// FE-G6-7：未知码的兜底<b>不能</b>再指向 WRITE_CODE_HINTS['-1']——那一条只描述请求层自己判定的网络失败/abort，
// 用它兜 500/501/502… 就是在给运营编造「未送达」这条不存在的因果。
// 只有 code 恰为 -1（网络失败或主动 abort，见 ../api/index.js 的 request catch）才允许说「未能送达」。
// F-22 FE-429-2：写侧多收一枚 data（reject 载荷原样递进来），**只在 code === 429** 时把后端如实给的
// retryAfterSeconds 转述追加上去（Admin 写面 IP 10/分，写侧 429 是真会发生的）。
// 400/403/404/500/-1 不拼——「约 N 秒后可再试」对它们是假信息；缺载荷时 retryAfterText 回 ''。
// 末句的未知码兜底不拼：429 已在 WRITE_CODE_HINTS 表内，走到那句的码必然不是 429。
function writeHintOf(code, data) {
  const key = String(Number(code))
  const known = WRITE_CODE_HINTS[key]
  if (known !== undefined) return known + (Number(code) === 429 ? retryAfterText(data) : '')
  return '服务端回了本页没有预置说明的码 ' + key + '。本页不猜原因，以「服务端原文」那一行为准；'
    + '如需核对可点上方「查询」重拉列表再决定下一步。'
}

// A8 空页回退（P4-7 §4 / D4）：模块级布尔，非 ref——它只决定「要不要再拉一次」，不参与渲染。
// 服务端对越界页回「空 list + 定值 total」而不是 400，所以「空行 + total>0 + 页码>1 且超过末页」是唯一可靠判据。
let pageFixPending = false

async function load() {
  if (viewState.value === 'loading') return
  viewState.value = 'loading'
  errorText.value = ''
  errorCode.value = 0
  errorData.value = null
  try {
    const envelope = await getAdminReviews(pageNum.value, pageSize.value, filters.value)
    const data = envelope.data || {}
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
    if (Number(data.pageNum) > 0) pageNum.value = Number(data.pageNum)
    if (PAGE_SIZES.indexOf(Number(data.pageSize)) !== -1) pageSize.value = Number(data.pageSize)
    viewState.value = 'ready'
    // A8：审走最后一页后停在空页（reject 是物理删行，末条被删时必然落这一幕）→ 记下回退页码，
    // 等本次 load 收尾后再拉一次。条件 pageNum > lastPage 天然防循环。
    if (rows.value.length === 0 && total.value > 0 && pageNum.value > 1) {
      const lastPage = Math.max(1, Math.ceil(total.value / pageSize.value))
      if (pageNum.value > lastPage) {
        pageNum.value = lastPage
        pageFixPending = true
      }
    }
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
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
    errorText.value = e.message || '评价队列读取失败，请稍后重试'
  }
  // 补拉必须放在 try/catch 全部结束之后：此时 viewState 已离开 'loading'，在飞守卫才放行；
  // 不得在 try 里 await load()。（catch 的 401/403 分支已 return，走到这里时 pageFixPending 必为 false。）
  if (pageFixPending) {
    pageFixPending = false
    return load()
  }
}

function onPageChange() {
  load()
}

function onSizeChange() {
  pageNum.value = 1
  load()
}

function onFilterChange() {
  pageNum.value = 1
  load()
}

function onReset() {
  filters.value.auditStatus = ''
  pageNum.value = 1
  load()
}

const dishNameOf = (dishId) => {
  const map = dishNameMap.value
  if (!map || dishId === null || dishId === undefined) return ''
  return map.get(String(dishId)) || ''
}

// P10-6 A5 收尾：评价图相对路径（/uploads/reviews/...）→ 后端可直读完整 URL。
// 拼基址的责任收敛在请求层 resolveFileUrl（页面零自拼 BASE_URL，照 api/index.js 文件头分层纪律；
// MerchantApplies.vue 证照缩略图同款姿势）。行缺 images 键/非数组/含空串一律滤掉——
// 绝不给 el-image 喂空 src 渲染成假图；无图回空数组，模板 v-if 据此整格不渲染。
const reviewImageUrlsOf = (row) => {
  const imgs = row && Array.isArray(row.images) ? row.images : []
  return imgs.filter((u) => typeof u === 'string' && u).map((u) => resolveFileUrl(u))
}

/**
 * 按需把商品列表拉来做 id→名称对齐（契约指定的做法：不改后端、不 JOIN，用同批的 dishes 只读列表在前端对齐）。
 * 一次只读请求，pageSize=100 是本端点允许的最大页大小（越界会被服务端 400 拒绝）。
 * 诚实边界：映射只覆盖<b>这一页</b>的商品，商品总数超过 100 时未落在该页的菜品仍只显示 ID——
 * 这一点在表下方的 align-note 里如实写明，不假装「名称已全量对齐」。
 */
async function loadDishNames() {
  if (submitting.value || viewState.value === 'loading') return
  submitting.value = true
  try {
    const envelope = await getAdminDishes(1, 100, {})
    const data = envelope.data || {}
    const list = Array.isArray(data.list) ? data.list : []
    const map = new Map()
    list.forEach((d) => {
      if (d && d.id !== null && d.id !== undefined) map.set(String(d.id), String(d.name == null ? '' : d.name))
    })
    dishNameMap.value = map
    const totalCount = Number(data.total) || 0
    dishNameNote.value = totalCount > list.length
      ? '菜品名对齐来自商品列表的第 1 页（每页 100 条），共 ' + totalCount + ' 件商品只取到 ' + list.length
        + ' 件；未落在这一页的菜品仍只显示 ID。'
      : '菜品名已按商品列表（共 ' + list.length + ' 件）对齐；对齐是一次只读请求，不改动任何数据。'
  } catch (err) {
    const e = err || {}
    // 对齐失败不影响审核本身：如实说明并回落到「只显示 dishId」，绝不编一个菜名出来。
    // 这一条只读请求与列表那一条是两回事，所以这里只给一句人话 + 重试点法，不铺开码表文案。
    dishNameNote.value = '菜品名对齐未完成：' + (e.message || '未知原因')
      + '（列表仍只显示菜品 ID，评价内容与两个审核动作都不受影响；可再点一次「按菜品 ID 对齐名称」重试。）'
  } finally {
    submitting.value = false
  }
}

async function askConfirm(message) {
  try {
    await ElMessageBox.confirm(message, '请确认这笔写操作', {
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

// 后果写全（口径来自 AdminServiceImpl#auditReview）：approve 是置 NULL 恢复展示，reject 是 deleteById 物理删行。
// ⚠ 这一段是<b>纯文本</b>（ElMessageBox 没开 dangerouslyUseHTMLString，写了标签会被当字面量显示，
//   而靠 HTML 串又要拼用户提交的评价内容，那是自己开注入口）——所以强调一律用「」，不用标签。
function confirmTextOf(row, action) {
  const dishPart = row.dishId === null || row.dishId === undefined
    ? '（该评价没有菜品指针）'
    : '（菜品 #' + row.dishId + (dishNameOf(row.dishId) ? ' ' + dishNameOf(row.dishId) : '，名称未对齐') + '）'
  if (action === 'approve') {
    return '将把评价 ' + row.id + dishPart + ' 置为「展示中」（过审）。\n\n'
      + '· 语义：服务端把 audit_status 置为 NULL，这条评价「立刻恢复对外展示」，会出现在 C 端对应菜品的评价里。\n'
      + '· 可重复执行（幂等）：对已经是展示中的评价再做一次，结果不变。\n'
      + '· 会写一条 admin_audit_log（action=review.audit），留痕旧值是原 audit_status、新值是 approve。\n'
      + '· 过审不删内容、不改评分，也不动这条评价对应的菜品。'
  }
  return '将「驳回并删除」评价 ' + row.id + dishPart + '。\n\n'
    + '· 这是物理删除：服务端走的是 deleteById，「行一旦删除不可恢复」——本页面与库里都不会出现「已驳回」这一态，'
    + '也没有任何撤销入口；确需留证请事先由 DBA 备份该列内容。\n'
    + '· 评价正文是用户提交的内容，删除后 C 端不再展示，评分也不再计入该菜品。\n'
    + '· 对同一条重复驳回：第二次会拿到 404「评价不存在」（行已经没了，这是实况不是故障）。\n'
    + '· 仍会写一条 admin_audit_log（action=review.audit），但它记的是留痕，不能用来还原被删的行。'
}

// P11-1b D8 批量二次确认文案：approve 列语义（逐条幂等）；reject 列条数 +「物理删除不可恢复」警示。
// 与单条 confirmTextOf 同一份纯文本纪律（ElMessageBox 没开 dangerouslyUseHTMLString，强调用「」不用标签）。
function batchConfirmTextOf(count, action) {
  if (action === 'approve') {
    return '将把选中的 ' + count + ' 条评价批量置为「展示中」（过审）。\n\n'
      + '· 逐条执行单条过审语义：服务端把每条的 audit_status 置为 NULL，立刻恢复对外展示；'
      + '已是展示中的行幂等不受影响。\n'
      + '· 每条各写一条 admin_audit_log（action=review.audit），留痕与单条过审一致。\n'
      + '· 一次批量=一次写请求（限流按一次计）；单条失败会按条记下原因，不影响其余条继续执行。'
  }
  return '将「批量驳回并删除」选中的 ' + count + ' 条评价。\n\n'
    + '· ⚠ 这是物理删除，不可恢复：服务端逐条删除评价行（连同评价图片行与图片文件一并删除），'
    + '删除后没有任何撤销入口，本页面与库里都不会出现「已驳回」这一态；确需留证请事先由 DBA 备份。\n'
    + '· 删除后 C 端不再展示这些评价，评分也不再计入对应菜品。\n'
    + '· 已被他人先删的行会按条记为失败（「评价不存在」），不影响其余行继续执行。\n'
    + '· 每条各写一条 admin_audit_log（action=review.audit），但它记的是留痕，不能用来还原被删的行。'
}

/**
 * 批量审核（P11-1b D8）：一次请求提交全部选中 id，服务端逐条独立执行。
 * 反馈双通道：ElMessage toast 念「成功/失败计数」短句（D8 的 toast 语义），
 * result-bar 常驻承载完整回执（失败明细最多列 5 条，长文案不该塞进 toast）。
 * 完成后重拉列表 + 清空选择（reject 删的行自然消失；approve 置态由服务端回值刷新）。
 */
async function onBatch(action) {
  if (submitting.value || viewState.value === 'loading') return
  const ids = selectedRows.value.map((row) => row.id)
  if (!ids.length) return
  const ok = await askConfirm(batchConfirmTextOf(ids.length, action))
  if (!ok) {
    writeResult.value = {
      kind: 'info',
      title: '已取消，未发出任何请求',
      serverText: '',
      hint: '确认框点「取消」/关闭/Esc 只关框：选中的评价一条都没有被改动，更没有被告删除。'
    }
    return
  }
  submitting.value = true
  writeResult.value = null
  const actionText = action === 'approve' ? '批量过审' : '批量驳回（删除）'
  try {
    const envelope = await batchAuditAdminReviews(ids, action)
    const data = envelope && envelope.data ? envelope.data : {}
    const succeeded = Number(data.succeeded) || 0
    const failed = Array.isArray(data.failed) ? data.failed : []
    const failedHead = failed.slice(0, 5)
      .map((f) => '#' + (f && f.id !== null && f.id !== undefined ? f.id : '?')
        + '：' + (f && f.reason ? f.reason : '（后端未给出原因）'))
      .join('；')
    ElMessage({
      type: failed.length ? 'warning' : 'success',
      message: actionText + '完成：成功 ' + succeeded + ' 条、失败 ' + failed.length + ' 条',
      grouping: true
    })
    writeResult.value = {
      kind: failed.length ? 'error' : 'success',
      title: actionText + '已提交',
      serverText: envelope && envelope.message ? envelope.message : '',
      hint: '共提交 ' + ids.length + ' 条：成功 ' + succeeded + ' 条、失败 ' + failed.length + ' 条。'
        + (failedHead ? '失败明细（最多列 5 条）：' + failedHead + '。' : '')
        + '列表已重新拉取、选择已清空。'
    }
    await load()
    clearSelection()
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
      writeResult.value = { kind: 'error', title: '登录状态已失效', serverText: '', hint: '正在返回登录页…' }
      return
    }
    // 整批 400/404/429/500：请求级失败，服务端保证「整批未生效」，选择保留便于修正后重发
    writeResult.value = {
      kind: 'error',
      title: e.code === 403 ? '没有权限执行该动作' : actionText + '未执行（整批未生效）',
      serverText: e.message || '（后端未给出 message）',
      hint: writeHintOf(e.code, e.data) + '（选择已保留，可核对后重发；翻页/刷新会按 id 恢复选中。）'
    }
  } finally {
    submitting.value = false
  }
}

async function onWrite(row, action) {
  if (submitting.value || viewState.value === 'loading') return
  const ok = await askConfirm(confirmTextOf(row, action))
  if (!ok) {
    writeResult.value = {
      kind: 'info',
      title: '已取消，未发出任何请求',
      serverText: '',
      hint: '确认框点「取消」/关闭/Esc 只关框：这条评价没有被改动，更没有被告删除。'
    }
    return
  }
  submitting.value = true
  writeResult.value = null
  try {
    const envelope = await auditAdminReview(row.id, action)
    writeResult.value = {
      kind: 'success',
      title: action === 'approve' ? '过审已提交' : '驳回（删除）已提交',
      serverText: envelope && envelope.message ? envelope.message : '',
      hint: action === 'approve'
        ? '列表已按当前筛选重新拉取（本页不本地改行）：这一行的 auditStatus 应由服务端回成空值，文案显示「展示中」。'
        : '列表已按当前筛选重新拉取：这一行应当「不再出现」（行已被物理删除）。若它还在，说明删除未生效，请核对服务端原文。'
    }
    await load()
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
      writeResult.value = { kind: 'error', title: '登录状态已失效', serverText: '', hint: '正在返回登录页…' }
      return
    }
    writeResult.value = {
      kind: 'error',
      title: e.code === 403 ? '没有权限执行该动作' : '写操作未完成（未生效）',
      serverText: e.message || '（后端未给出 message）',
      hint: writeHintOf(e.code, e.data)
    }
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  viewState.value = 'idle'
  load()
})
</script>

<style scoped>
.sql-fallback {
  margin-top: 12px;
  padding: 10px 14px;
  background: var(--admin-card);
  border: 1px solid var(--admin-border);
  border-left: 3px solid var(--el-color-warning);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
.sql-fallback code {
  font-family: Consolas, Menlo, monospace;
  background: var(--admin-card-soft);
  padding: 0 4px;
  border-radius: 3px;
}
.result-bar {
  margin-top: 12px;
  padding: 10px 14px;
  background: var(--admin-card);
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  border-left: 3px solid var(--el-color-info);
}
.result-bar-success {
  border-left-color: var(--el-color-success);
}
.result-bar-error {
  border-left-color: var(--el-color-danger);
}
.result-bar-info {
  border-left-color: var(--el-color-warning);
}
.result-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.result-line {
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.filter-select {
  width: 220px;
}
.table {
  background: var(--admin-card);
  margin-top: 16px;
}
.empty {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.inline-note {
  font-size: 12px;
}
/* P10-6 A5：评价图缩略（每评价 ≤3 张，44px 方块横排）；fallback 与缩略同尺寸防行高跳动 */
.review-imgs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.review-thumb {
  width: 44px;
  height: 44px;
  border-radius: 4px;
  cursor: zoom-in;
}
.review-img-fallback {
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  background: var(--admin-card-soft);
  border-radius: 4px;
}
.align-note {
  margin-top: 10px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
/* P11-1b D8 底部批量操作条：表格下、分页前；变量照本页既有体系（--admin- 与 --el- 两族） */
.batch-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  padding: 10px 14px;
  background: var(--admin-card);
  border: 1px solid var(--admin-border);
  border-radius: 6px;
}
.batch-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
