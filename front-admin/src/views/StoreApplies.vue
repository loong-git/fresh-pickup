<template>
  <div class="page">
    <!-- FE-G6-10（读写可辨）：徽标常驻页眉、ready 态就可见。approve 同事务建店并绑主理人、不可翻转，所以常驻「本页会改数据」。 -->
    <div class="page-head">
      <h2 class="page-title">入驻审核</h2>
      <el-tag type="danger" effect="dark" size="small" class="mode-badge">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      全量入驻申请列表（运营面）+ 一个写动作的两种取值：通过并发布门店（approve）与驳回（reject），两者都可带一句审核意见。<br />
      列表源是<b>只读</b>的 GET /api/admin/v2/store-applies：外层恒 {list,total,pageNum,pageSize,filters}，排序 id DESC（最新在前），
      唯一筛选 status 是三态白名单 pending / approved / rejected（精确等值，白名单外 400）。<br />
      ⚠ 出参<b>没有</b>资质材料图（proof_photos 在 SQL 层就没查询：执照图的匿名映射仍是悬项）、也没有申请人内部 id（user_id）。
      所以本页<b>没有「查看资质材料」这一列</b>，这是实况、不是漏做，也不许从别的端处凑。<br />
      contactPhone 由服务端脱敏（如 138****5678，该列可空则原样为空），本页不拼回明文；
      auditedAt / createdAt 是服务端 DATE_FORMAT 定形串，原样显示。状态映射：pending=待审核、approved=已通过并发布、rejected=已驳回。
    </p>

    <!-- 纪律 7：手册的「approve 不可用 SQL 代办」这句语义必须带上（它是本页存在的唯一理由之一），
         但仍然只写节号、不抄 SQL 正文。 -->
    <div class="sql-fallback">
      <b>SQL 兜底</b>：见 <code>docs/日常运营SQL手册.md</code> <b>第七节</b>。该节明写着 <b>approve 不可用 SQL 代办</b>——
      通过这一步要在同一事务里新建门店并把归属绑给申请人，SQL 侧没有等价姿势，<b>只能走本页面这一个端点</b>；
      第七节能兜底的是「驳回」那半条（置态 + 留审核意见）。
    </div>

    <!-- FE-G6-5：工具条在状态块之外，四态都留着；loading / 写在飞时 disabled 但不消失。 -->
    <div class="toolbar">
      <el-select v-model="filters.status" size="small" class="filter-select" clearable
                 placeholder="申请状态（三态之一，精确）"
                 :disabled="viewState === 'loading' || submitting" @change="onFilterChange">
        <el-option label="待审核 pending" value="pending" />
        <el-option label="已通过 approved" value="approved" />
        <el-option label="已驳回 rejected" value="rejected" />
      </el-select>
      <el-button size="small" type="primary" :disabled="viewState === 'loading' || submitting" @click="onFilterChange">查询</el-button>
      <el-button size="small" :disabled="viewState === 'loading' || submitting" @click="onReset">重置</el-button>
    </div>

    <div v-if="writeResult" class="result-bar" :class="'result-bar-' + writeResult.kind">
      <div class="result-title">{{ writeResult.title }}</div>
      <div v-if="writeResult.serverText" class="result-line">服务端原文：{{ writeResult.serverText }}</div>
      <div v-if="writeResult.hint" class="result-line">{{ writeResult.hint }}</div>
    </div>

    <div v-if="viewState === 'loading'" class="state-box">
      <div class="state-title">正在加载</div>
      <div class="state-desc">入驻申请列表读取中…</div>
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
      <el-table :data="rows" border stripe size="small" class="table" max-height="calc(100vh - 420px)">
        <template #empty>
          <span class="empty">暂无入驻申请（当前筛选下没有命中；现库 store_apply 表本就可能是空的，这不是故障）</span>
        </template>
        <el-table-column prop="id" label="申请 ID" min-width="90" />
        <el-table-column prop="proposedName" label="拟定店名" min-width="170" show-overflow-tooltip />
        <el-table-column prop="proposedAddress" label="拟定地址" min-width="240" show-overflow-tooltip />
        <el-table-column prop="lng" label="经度" min-width="110" />
        <el-table-column prop="lat" label="纬度" min-width="110" />
        <el-table-column label="服务" min-width="140" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.service }">{{ row.service }}</span></template>
        </el-table-column>
        <el-table-column label="联系人" min-width="120" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.contactName }">{{ row.contactName }}</span></template>
        </el-table-column>
        <el-table-column label="联系电话（已脱敏）" min-width="140">
          <template #default="{ row }"><span :class="{ muted: !row.contactPhone }">{{ row.contactPhone }}</span></template>
        </el-table-column>
        <el-table-column label="状态" min-width="130">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核意见" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.auditRemark }">{{ row.auditRemark }}</span></template>
        </el-table-column>
        <el-table-column label="审核时间" min-width="160">
          <template #default="{ row }"><span class="muted">{{ row.auditedAt }}</span></template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" min-width="160" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <!-- 服务端只允许 pending 迁出，且「审核结论不可翻转」（AdminServiceImpl#auditStoreApply 那句
                 「该申请已审核完成，不可再次审核」就是这个门）。非 pending 的行没有可用动作 →
                 摆一句实况说明，不摆两个必然被拒的按钮。读的是服务端刚回的行值，不是本地状态。 -->
            <template v-if="row.status === 'pending'">
              <el-button size="small" type="success" plain
                         :disabled="viewState === 'loading' || submitting"
                         @click="openDialog(row, 'approve')">通过并发布</el-button>
              <el-button size="small" type="danger" plain
                         :disabled="viewState === 'loading' || submitting"
                         @click="openDialog(row, 'reject')">驳回</el-button>
            </template>
            <span v-else class="muted">已审核完成，结论不可翻转</span>
          </template>
        </el-table-column>
      </el-table>

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

    <!-- 审核意见表单也在状态块之外（提交后列表会进 loading、表格被卸载，表单若在 v-else 里会连带消失）。
         EP 2.14 的 el-dialog 里 appendTo 默认虽是 "body"，teleport 实际由 appendToBody（默认 false）决定，即**默认不挪**；要挪必须显式写 append-to-body（F-97 实测订正原句的反向因果）。 -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="620px"
               :close-on-click-modal="false">
      <div class="dialog-target">
        申请 #{{ pending.id }}「{{ pending.proposedName }}」，拟定地址：{{ pending.proposedAddress }}，
        联系电话：{{ pending.contactPhone }}（服务端脱敏值）
      </div>
      <el-form class="dialog-form" label-width="90px" @submit.prevent="onSubmit">
        <el-form-item label="审核意见">
          <el-input v-model="remark" type="textarea" :rows="3" clearable
                    placeholder="可留空。留空则服务端不写 audit_remark（不会把空串塞进留痕列）" />
        </el-form-item>
      </el-form>
      <p class="dialog-note">
        这一栏只是<b>可选的留痕文字</b>；点「下一步」才会弹二次确认，真正提交与否由那一步决定。
        超过 255 字本页不替你裁、也不替你猜——服务端会回 400「审核意见不能超过 255 字」，原文照实显示。
      </p>
      <template #footer>
        <el-button size="small" @click="dialogVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="onSubmit">下一步：二次确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 入驻审核（F-20.22 Step 5b-2 四页之一）：
//   列表 = GET /api/admin/v2/store-applies（5b-1 交付；唯一筛选 status 三态白名单）
//   写   = PUT /api/admin/store-applies/{id}/audit，body {action, remark?}
//          （AdminController 旧面：这个端点<b>没有 DTO</b>，读的是 @RequestBody Map<String,String> 的 action / remark 两键，
//           所以请求层只发字符串值，remark 为空时整个键不下发 = 服务端不 SET audit_remark）
// 本页最要紧的一条语义（纪律 2 + 纪律 7 同时点到）：approve 是<b>同事务三写</b>
//   （置态 + INSERT store + admin_audit_log），且把 store.owner_user_id 绑成申请人——
//   那是这家店归属的唯一生产写入点；结论不可翻转、幂等早退，所以<b>本页面无法回滚它</b>，
//   手册第七节也明写「approve 不可用 SQL 代办」。reject 则只置态 + 留 audit_remark，不建店。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminStoreApplies, auditAdminStoreApply, retryAfterText } from '../api'

const PAGE_SIZES = [10, 20, 50, 100]

const STATUS_TEXT = { pending: '待审核', approved: '已通过并发布', rejected: '已驳回' }
const statusText = (v) => STATUS_TEXT[v] || (v == null ? '' : String(v))
const statusTagType = (v) => (v === 'pending' ? 'warning' : v === 'approved' ? 'success' : v === 'rejected' ? 'info' : 'info')

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
const filters = ref({ status: '' })

// 审核表单：pending 存的是服务端上一轮回的那一行 + 本次要做的动作（只在 ready 态能打开）
const dialogVisible = ref(false)
const pending = ref({})
const pendingAction = ref('')
const remark = ref('')
const dialogTitle = computed(() =>
  (pendingAction.value === 'approve' ? '通过并发布门店' : '驳回入驻申请')
  + (pending.value.id !== undefined && pending.value.id !== null ? ' #' + pending.value.id : ''))

const CODE_HINTS = {
  400: '请求参数不在允许范围内。服务端对 400 有两句原文且不说明命中哪一句，本页不猜，以上方后端原文为准：'
    + '「请求参数有误，请检查后重试」= 值过不了白名单/形状闸门（status 不是 pending/approved/rejected 之一、页码 <1 或每页超出 1~100）；'
    + '「参数格式不正确」= 值绑不进目标类型（pageNum/pageSize 填了非整数）。'
    + '分页请核对：页码从 1 起、每页只能选 10/20/50/100 四档。',
  404: '入驻申请列表接口不可达（后端服务未起或路径已变）。请确认后端服务在运行，再点「重试」。',
  429: '读取过于频繁（只读列表按来源 IP 限流 30 次/分）。请稍候再翻页。',
  // FE-G6-7（读侧）：500 是服务端接住这一请求后自己抛的，跟「-1」（请求层判定的网络失败/abort）是两回事。
  // 这一条不许出现「网络异常/超时/检查代理」字样。
  // 本页对 500 有一条特别值得点名的实况：`store_apply.proof_photos` 挂 JacksonTypeHandler，
  // 一旦这一列被手工 SQL 写成非 JSON 数组，selectById 就会反序列化炸掉并抛 500（F-20.23 现场 1），
  // 但这一条不给运营猜这个具体原因——猜就是编；让运维看服务端日志。
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一条读取时自己抛的。'
    + '本页这次读数不可信——不要拿这一屏的申请状态/审核意见当库内真值用。'
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
  400: '这笔写请求被 400 拒绝。400 不说明命中哪一句，本页不猜。通用两句是'
    + '「请求参数有误，请检查后重试」与「参数格式不正确」；本端点自己的业务文案有'
    + '「action 仅允许 approve（过审并发布门店）或 reject（驳回）」、「审核意见不能超过 255 字」、'
    + '「该申请已审核完成，不可再次审核」（结论不可翻转：approved 再 reject、rejected 再 approve 都会被拒）。'
    + '请以「服务端原文」那一行为准。',
  403: '登录态有效但服务端判定本账号不能审这一单（403 ≠ 登录失效，本页不清登录态）。'
    + '入驻审核会新建门店并绑定归属，请核对运营账号的角色与授权。',
  404: '后端原文应为「申请不存在」：这一行的申请 ID 在库里已经没有（被清理，或本页列表已过期）。'
    + '下一步：用上方「查询」重取列表后再对现存的 ID 操作。注意 approve 会新建门店，'
    + '拿一个已经不存在的申请 id 去试，只会白跑一次。',
  429: '写请求过于频繁（Admin 写面按来源 IP 限流 10 次/分，比列表的 30 次/分更紧）。'
    + '批量审核请分时段，不要连点。',
  // FE-G6-7（写侧）：500 ≠ 未送达。请求已经打到服务端、被接住、由服务端自己抛的。
  // F-20.23 现场 1 就是这条：一次 PUT store-applies/25/audit 服务端 500、信封 message「服务开小差了」，
  // 页面结果条却被写成「请求未能送达（网络异常或 20s 提交档超时）」，把运营引向"查网络/重启"的错误方向。
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一笔审核时自己抛的——不是没送到。'
    + '⚠ 这一笔到底有没有生效不作数：approve 是同事务三写（置态 + 建店 + 留痕），要么全成要么全滚，但回执不可信；'
    + 'reject 也只是把状态与审核意见写下去，服务端抛 500 就不能假定已生效。'
    + '下一步：点上方「查询」重拉列表核对这一行——若已显示 approved/rejected 并带上审核时间，说明那一笔已经成功、'
    + '不要再点第二次（幂等早退会让第二次拿到「该申请已审核完成」这类别的错，也不会重复建店）。'
    + '反复出现，请让运维查服务端日志（本页不猜原因）。',
  '-1': '请求未能送达（网络异常或 20s 提交档超时）。⚠ 超时不等于「没生效」：'
    + 'approve 是同事务三写（置态 + 建店 + 留痕），要么全成要么全滚，但回执丢了就无从判断。'
    + '下一步：点上方「查询」重拉列表核对这一行——若已显示 approved 并多了审核时间，说明那一笔已经成功；'
    + '此时不要再点第二次（服务端幂等早退，不会重复建店，但也拿不到「是否刚建了店」的信息）。'
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

// D4：空页回退的待补拉标志。模块级普通布尔而非 ref——它不参与渲染，只是本次 load 与下一次 load 之间的信号
let pageFixPending = false

async function load() {
  if (viewState.value === 'loading') return
  viewState.value = 'loading'
  errorText.value = ''
  errorCode.value = 0
  errorData.value = null
  try {
    const envelope = await getAdminStoreApplies(pageNum.value, pageSize.value, filters.value)
    const data = envelope.data || {}
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
    if (Number(data.pageNum) > 0) pageNum.value = Number(data.pageNum)
    if (PAGE_SIZES.indexOf(Number(data.pageSize)) !== -1) pageSize.value = Number(data.pageSize)
    viewState.value = 'ready'
    // D4：服务端对越界页回「空 list + 定值 total」而不是 400，所以「空页 + total>0 + 页码>1」是唯一可靠判据。
    // 只回退到 lastPage，且以 pageNum > lastPage 为前提，天然防循环；真正的补拉放在本次 load 结束之后。
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
    errorText.value = e.message || '入驻申请列表读取失败，请稍后重试'
  }
  // D4：补拉必须在 try/catch 全部结束之后——此时 viewState 已落成 ready，在飞守卫才放行；
  // 若在 try 里 await load()，请求会被自己的 loading 守卫吞掉，页码回退了却没有新数据。
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
  filters.value.status = ''
  pageNum.value = 1
  load()
}

function openDialog(row, action) {
  if (submitting.value || viewState.value === 'loading') return
  pending.value = row
  pendingAction.value = action
  remark.value = ''
  dialogVisible.value = true
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

// 确认文案：approve 的三写后果 + 「不可 SQL 代办」「本页面无法回滚」两句必须在这里也说一遍（纪律 2）。
// 与 Reviews 同理：这是纯文本进 ElMessageBox，强调用「」不用标签。
function confirmTextOf(row, action, trimmedRemark) {
  const remarkLine = trimmedRemark === ''
    ? '· 审核意见留空：服务端不会写 audit_remark（整个键不下发），只置状态与审核时间。\n'
    : '· 将写入的审核意见（audit_remark）：「' + trimmedRemark + '」\n'
  if (action === 'approve') {
    return '通过将入驻申请 ' + row.id + '「' + row.proposedName + '」并【发布门店】。\n\n'
      + '· 同一事务里做三件事：① 申请置为 approved 并记审核时间；② 新建一条 store（名称/地址/坐标/服务/联系电话'
      + '全部取自本申请行）；③ 写一条 admin_audit_log（第③步是旁路，失败只记日志、不回滚）。①②任一步失败整体回滚，不留「已发布却没置态」的半态。\n'
      + '· 新店的 store.owner_user_id 会被绑成【申请人】——这是这家店归属的唯一生产写入点，'
      + '此后这家店由主理人端（front-partner）自己管理。\n'
      + '· 【不可翻转】：通过后这条申请不能再驳回（服务端回「该申请已审核完成，不可再次审核」）。\n'
      + '· 【不可用 SQL 代办】：手册第七节明写这一条，只能走本页面这个端点。\n'
      + '· 【本页面无法回滚】：它会新建数据，且重复点第二次是幂等早退（不会重复建店，但也没有撤销入口）。\n'
      + remarkLine
      + '· 提交成功后本页会重拉列表，不在本地改这一行。'
  }
  return '驳回入驻申请 ' + row.id + '「' + row.proposedName + '」。\n\n'
    + '· 语义：只把申请置为 rejected 并记审核时间，同时写审核意见到 audit_remark——'
    + '【不新建门店】、【不改动任何用户或门店数据】、【不删除申请行】（行留着可追溯）。\n'
    + remarkLine
    + '· 驳回后申请人重新提交会生成一条【新申请】（新 id），原行保留；这不是「同一条被改回来」。\n'
    + '· 【不可翻转】：已驳回的申请不能再改为通过（同一条会被服务端「该申请已审核完成，不可再次审核」拒掉）。\n'
    + '· 会写一条 admin_audit_log（action=store_apply.audit）。这一步 SQL 侧也能兜底，见手册第七节。\n'
    + '· 提交成功后本页会重拉列表，不在本地改这一行。'
}

async function onSubmit() {
  if (submitting.value || viewState.value === 'loading') return
  const row = pending.value
  const action = pendingAction.value
  const trimmedRemark = String(remark.value == null ? '' : remark.value).trim()
  const ok = await askConfirm(confirmTextOf(row, action, trimmedRemark))
  if (!ok) {
    writeResult.value = {
      kind: 'info',
      title: '已取消，未发出任何请求',
      serverText: '',
      hint: '确认框点「取消」/关闭/Esc 只关框：这条申请既没被通过也没被驳回，表单还开着，可以继续改意见或关掉它。'
    }
    return
  }
  submitting.value = true
  writeResult.value = null
  try {
    // remark 为空串时请求层整个键不下发（见 api/index.js 的 auditAdminStoreApply）
    const envelope = await auditAdminStoreApply(row.id, action, trimmedRemark)
    writeResult.value = {
      kind: 'success',
      title: action === 'approve' ? '通过已提交（门店应已发布）' : '驳回已提交',
      serverText: envelope && envelope.message ? envelope.message : '',
      hint: action === 'approve'
        ? '列表已按当前筛选重新拉取（本页不本地改行）：这一行应显示 approved 并带上审核时间。'
          + '新建出的门店不在本页范围内，请到主理人端核对其归属与可见性。'
        : '列表已按当前筛选重新拉取：这一行应显示 rejected、审核意见与审核时间都由服务端回。'
    }
    dialogVisible.value = false
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
    // A2（D1）：失败时弹窗保持打开，页面流里的结果条被遮罩挡住，toast 是关框前唯一即时可见的信号；
    // 结果条不动，关框后仍可回看。401 已在上面 return（请求层清态跳登录，页面马上卸载，弹了也读不到）。
    ElMessage.error(e.message || '写操作未完成（未生效）')
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
  background: #fff;
  border: 1px solid var(--admin-border);
  border-left: 3px solid #D97706;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #57534E;
}
.sql-fallback code {
  font-family: Consolas, Menlo, monospace;
  background: #F7F6F3;
  padding: 0 4px;
  border-radius: 3px;
}
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
  width: 220px;
}
.table {
  background: #fff;
  margin-top: 16px;
}
.empty {
  color: #57534E;
  font-size: 13px;
}
.muted {
  color: #57534E;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.dialog-target {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #F7F6F3;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #57534E;
}
.dialog-form :deep(.el-form-item) {
  margin-bottom: 8px;
}
.dialog-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: #57534E;
}
</style>
