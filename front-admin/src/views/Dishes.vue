<template>
  <div class="page">
    <!-- FE-G6-10（读写可辨·写页徽标）：徽标常驻页眉、ready 态就可见（原先这类话只写在 state-box 里，出错才看得到）。 -->
    <div class="page-head">
      <h2 class="page-title">商品管理</h2>
      <el-tag type="danger" effect="dark" size="small" class="mode-badge">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      全量商品列表（运营面，不按商家归属过滤）+ 一个写动作：改价 / 补库存 / 上下架（同一个端点的三个可选字段，<b>部分更新</b>）。<br />
      列表源是<b>只读</b>的 GET /api/admin/v2/dishes：外层恒 {list,total,pageNum,pageSize,filters}，
      筛选为<b>精确等值</b>（商家 ID / 在售标记 / 分类），<b>排序 id ASC</b>（升序，不是「新建在前」）；没有关键字搜索。<br />
      金额（price / seckillPrice）与时间（createTime / updateTime）显示的是<b>服务端定形串</b>，本页不做二次格式化；
      goodRate 也是服务端算好的字符串（如 94.3%，可为空）。<br />
      供货价（F-117，平台-商家核算维度，C 端不下发）：未设显示「—」（未设供货价的商品不进结算聚合），
      在编辑弹窗维护（<b>留空=清除</b>，与上面三个字段「留空=不改」的语义不同，弹窗里有说明）。<br />
      布尔映射（服务端出 0/1 原值，文案由本页映射表给）：onSale 1=上架、0=下架；seckill 1=秒杀、0=非秒杀（非秒杀行 seckillPrice 恒为空）。<br />
      审核态（F-116 先发后审）：出库里原值 ok/rejected，行操作含「驳回」（必填理由 1~200，驳回后 C 端即刻不可见不可买，恢复需运营）与「恢复」；
      驳回/恢复不改动上下架状态（两列正交）。<br />
      一期<b>只改不建</b>：平台侧本就没有「新建商品」端点（dish.create 全是商家侧动作），所以本页没有「新建商品」按钮，不是漏做。
    </p>

    <div class="sql-fallback">
      <b>SQL 兜底</b>：本页面三个动作在 <code>docs/日常运营SQL手册.md</code> 里各有对应小节——
      改价见 <b>第二节</b>、补库存见 <b>第三节</b>、上下架见 <b>第四节</b>。手册那三节里「库存是<b>直接置值</b>、不是累加」这一点，
      与本页面写的是同一件事；两边都改得动，但审计留痕只有本页面这一条有。
    </div>

    <!-- FE-G6-5 的教训：工具条在状态块之外，四态都留着；loading / 写在飞时 disabled 但不消失。 -->
    <div class="toolbar">
      <el-input v-model="filters.merchantId" size="small" class="filter-input" clearable
                placeholder="商家 ID（精确等值，如 1）"
                :disabled="viewState === 'loading' || submitting" @change="onFilterChange" />
      <el-select v-model="filters.onSale" size="small" class="filter-select" clearable
                 placeholder="在售状态（0/1，精确）"
                 :disabled="viewState === 'loading' || submitting" @change="onFilterChange">
        <el-option label="上架 onSale=1" :value="1" />
        <el-option label="下架 onSale=0" :value="0" />
      </el-select>
      <el-input v-model="filters.category" size="small" class="filter-input" clearable
                placeholder="分类（精确等值，可中文，如 蔬菜）"
                :disabled="viewState === 'loading' || submitting" @change="onFilterChange" />
      <el-select v-model="filters.auditStatus" size="small" class="filter-select" clearable
                 placeholder="审核态（ok/rejected，精确）"
                 :disabled="viewState === 'loading' || submitting" @change="onFilterChange">
        <el-option label="正常 auditStatus=ok" value="ok" />
        <el-option label="已驳回 auditStatus=rejected" value="rejected" />
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
      <div class="state-desc">商品列表读取中…</div>
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
          <span class="empty">暂无商品（当前筛选下没有命中）</span>
        </template>
        <el-table-column prop="id" label="ID" min-width="80" />
        <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="price" label="价格" min-width="90" />
        <el-table-column label="秒杀" min-width="90">
          <template #default="{ row }">{{ seckillText(row.seckill) }}</template>
        </el-table-column>
        <el-table-column label="秒杀价" min-width="90">
          <template #default="{ row }"><span :class="{ muted: !row.seckillPrice }">{{ row.seckillPrice }}</span></template>
        </el-table-column>
        <!-- F-117 供货价（平台-商家核算维度）：未设显「—」并挂 tooltip 说明结算聚合口径；设了出定形两位小数串 -->
        <el-table-column label="供货价" min-width="90">
          <template #default="{ row }">
            <el-tooltip v-if="row.supplyPrice == null" content="未设供货价的商品不进结算聚合" placement="top">
              <span class="muted">—</span>
            </el-tooltip>
            <span v-else>{{ row.supplyPrice }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" min-width="80" />
        <el-table-column label="在售" min-width="90">
          <template #default="{ row }">
            <el-tag :type="row.onSale === 1 ? 'success' : 'info'" size="small" effect="light">{{ binaryText(row.onSale) }}</el-tag>
          </template>
        </el-table-column>
        <!-- 审核态（F-116 §2.3）：出库里原值由本页映射 tag；已驳回行把驳回理由挂 tooltip（悬停可读） -->
        <el-table-column label="审核态" min-width="100">
          <template #default="{ row }">
            <el-tooltip v-if="auditRejected(row) && row.auditReason"
                        :content="'驳回理由：' + row.auditReason" placement="top">
              <el-tag type="danger" size="small" effect="light">已驳回</el-tag>
            </el-tooltip>
            <el-tag v-else-if="auditRejected(row)" type="danger" size="small" effect="light">已驳回</el-tag>
            <el-tag v-else type="success" size="small" effect="light">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="soldCount" label="销量" min-width="90" />
        <el-table-column prop="category" label="分类" min-width="110" show-overflow-tooltip />
        <el-table-column label="单位" min-width="80">
          <template #default="{ row }"><span class="muted">{{ row.unit }}</span></template>
        </el-table-column>
        <el-table-column label="好评率" min-width="100">
          <template #default="{ row }"><span :class="{ muted: !row.goodRate }">{{ row.goodRate }}</span></template>
        </el-table-column>
        <el-table-column label="限购" min-width="80">
          <template #default="{ row }"><span class="muted">{{ row.limitBuy }}</span></template>
        </el-table-column>
        <el-table-column label="商家 ID" min-width="90">
          <template #default="{ row }"><span class="muted">{{ row.merchantId }}</span></template>
        </el-table-column>
        <el-table-column label="图片路径" min-width="160" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.image }">{{ row.image }}</span></template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="160" />
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }"><span class="muted">{{ row.updateTime }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain
                       :disabled="viewState === 'loading' || submitting"
                       @click="openDialog(row)">改价/库存</el-button>
            <!-- F-116 §2.3 行操作：驳回（弹窗必填理由 1~200 + 二次确认）/ 恢复（确认弹窗）；
                 已驳回行不显示驳回钮（服务端幂等但 UI 不诱导重复操作），恢复钮同理只对驳回行出现 -->
            <el-button v-if="!auditRejected(row)" size="small" type="danger" plain
                       :disabled="viewState === 'loading' || submitting"
                       @click="openReject(row)">驳回</el-button>
            <el-button v-else size="small" type="warning" plain
                       :disabled="viewState === 'loading' || submitting"
                       @click="onRestore(row)">恢复</el-button>
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

    <!-- 更新表单也在状态块之外：提交成功后列表会进 loading 态、表格被卸载，
         这时表单若挂在 v-else 里会连带消失，把「刚提交了什么」这段上下文一起弄丢。
         （EP 2.14 的 el-dialog：appendTo 默认确实是 "body"，但 teleport 的开关是 `disabled = appendTo==="body" ? !appendToBody : false`，而 appendToBody 默认 false——即**默认不挪**、就地渲染；要把 DOM 挪进 body 必须显式写 append-to-body。F-97 实测订正：原句把这条因果写反了。） -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle"
               width="620px" :close-on-click-modal="false">
      <p class="dialog-note">
        价格 / 库存 / 在售<b>都可以留空</b>：服务端语义是「传了才更新」，留空的那一项不会下发、也不会被改动。<br />
        供货价（F-117）<b>不同</b>：留空提交 = 清除（回 NULL，未设供货价的商品不进结算聚合）；填了就设置（须大于 0、最多两位小数）。
        price/stock/onSale 与 supplyPrice 全都没表态（前三个留空且供货价本就未设）时，服务端会回 400
        「无更新字段（可传 price / stock / onSale / supplyPrice）」（该 400 在写库之前就抛，不动任何数据），本页把原文如实显示。
      </p>
      <el-form class="dialog-form" label-width="110px" @submit.prevent="onSubmit">
        <el-form-item :label="'价格（当前 ' + displayOf(editing.price) + '）'">
          <el-input v-model="form.price" size="small" clearable
                    placeholder="留空=不改；改则填数字，如 9.90" />
        </el-form-item>
        <el-form-item :label="'库存（当前 ' + displayOf(editing.stock) + '）'">
          <el-input v-model="form.stock" size="small" clearable
                    placeholder="留空=不改；注意是「直接置为」不是累加" />
        </el-form-item>
        <el-form-item :label="'在售（当前 ' + binaryText(editing.onSale) + '）'">
          <el-select v-model="form.onSale" size="small" clearable placeholder="留空=不改" class="dialog-select">
            <el-option label="上架（onSale=1）" :value="1" />
            <el-option label="下架（onSale=0）" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item :label="'供货价（当前 ' + (editing.supplyPrice == null ? '未设' : displayOf(editing.supplyPrice)) + '）'">
          <el-input v-model="form.supplyPrice" size="small" clearable
                    placeholder="留空=清除；建议 0.85×原价" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dialogVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="onSubmit">提交更新</el-button>
      </template>
    </el-dialog>

    <!-- 驳回理由弹窗（F-116 §2.3）：理由必填 1~200，确认文案点名「驳回后 C 端即刻不可见不可买，恢复需运营操作」；
         与更新表单同理挂在状态块之外（提交后列表重拉会卸载表格，弹窗不能跟着消失） -->
    <el-dialog v-model="rejectDialogVisible" :title="rejectDialogTitle"
               width="560px" :close-on-click-modal="false">
      <p class="dialog-note">
        驳回<b>不会改动</b>该商品的上下架状态（on_sale 与审核态两列正交）；驳回后 C 端<b>即刻不可见不可买</b>
        （列表/搜索/秒杀/推荐同时消失，下单被拦），<b>恢复需运营在本页操作</b>。
        秒杀配位与免费领池不受本动作管理：秒杀集会因审核态自动隐藏，池内商品需自行移出（免费领管理页）。
      </p>
      <el-form class="dialog-form" label-width="110px" @submit.prevent="onSubmitReject">
        <el-form-item label="驳回理由" :class="{ 'reject-field-error': rejectError }">
          <el-input v-model="rejectForm.reason" type="textarea" :rows="3" maxlength="200" show-word-limit
                    placeholder="必填，1~200 个字符；将展示给商家（商家端可见）" />
        </el-form-item>
        <div v-if="rejectError" class="reject-error-line">{{ rejectError }}</div>
      </el-form>
      <template #footer>
        <el-button size="small" @click="rejectDialogVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="danger" :loading="submitting" @click="onSubmitReject">下一步：确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 商品管理（F-20.22 Step 5b-2 四页之一）：
//   列表 = GET /api/admin/v2/dishes（5b-1 交付；merchantId/onSale/category 三个精确等值筛选，无关键字搜索）
//   写   = PUT /api/admin/dishes/{id}，body {price?, stock?, onSale?}（AdminController 旧面，运营 JWT 第二轨）
// 本页最要紧的一条实况：<b>stock 是直接置值，不是在当前值上累加</b>（`AdminServiceImpl#updateDish` 用的是
// LambdaUpdateWrapper#set，不是 increaseStock），所以表单标题、确认文案两处都要把这层写明白，
// 否则运营按「补库存」的直觉填 10，会把 50 库存改成 10。
// 一期只改不建：Admin 面没有「新建商品」端点（F-20.17 事实 3 附注），本页不提供，也不是漏做。
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { getAdminDishes, updateAdminDish, auditAdminDish, retryAfterText } from '../api'

const PAGE_SIZES = [10, 20, 50, 100]

// D-18：0/1 原值的映射表写在页面顶部
const BINARY_TEXT = { 1: '上架', 0: '下架' }
const SECKILL_TEXT = { 1: '秒杀', 0: '非秒杀' }
const binaryText = (v) => BINARY_TEXT[v] || (v == null ? '' : String(v))
const seckillText = (v) => SECKILL_TEXT[v] || (v == null ? '' : String(v))
// null / undefined 显示成空串（而不是字符串 "null"）；其余原样透传，不做数字或金额格式化
const displayOf = (v) => (v == null ? '' : String(v))
// 审核态（F-116）：库里只有 'ok'/'rejected' 两枚（m26 列口径）；'rejected' 即驳回行。
// 未知值按「非驳回」渲染正常 tag——脏值原样透传会在服务端 400 白名单处暴露，本页不猜第二真源
const auditRejected = (row) => row && row.auditStatus === 'rejected'

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
const filters = ref({ merchantId: '', onSale: '', category: '', auditStatus: '' })

// 驳回弹窗（F-116 §2.3）：auditTarget 存目标行（只为标题与确认文案，真值以重拉后的列表为准）
const rejectDialogVisible = ref(false)
const rejectDialogTarget = ref({})
const rejectForm = ref({ reason: '' })
const rejectError = ref('')
const rejectDialogTitle = computed(() => (rejectDialogTarget.value.id === undefined
  ? '驳回商品'
  : '驳回商品 #' + rejectDialogTarget.value.id + '（' + displayOf(rejectDialogTarget.value.name) + '）'))

// 更新表单：editing 存的是服务端上一轮回的整行（只为把「当前值」摆进表单与确认文案，不是本地副本真值）
const dialogVisible = ref(false)
const editing = ref({})
const form = ref({ price: '', stock: '', onSale: '', supplyPrice: '' })
// 未选中任何行时标题回落成「更新商品」——不显示 #undefined，也不把旧行的 id 留在上面。
const dialogTitle = computed(() => (editing.value.id === undefined
  ? '更新商品（只改价格 / 库存 / 在售，三项都可留空）'
  : '更新商品 #' + editing.value.id + '（' + displayOf(editing.value.name) + '）'))

const CODE_HINTS = {
  400: '请求参数不在允许范围内。服务端对 400 有两句原文且不说明命中哪一句，本页不猜，以上方后端原文为准：'
    + '「请求参数有误，请检查后重试」= 值过不了白名单/形状闸门（onSale 不是 0/1、分类或页码形状不合法、页码 <1 或每页超出 1~100）；'
    + '「参数格式不正确」= 值绑不进目标类型（商家 ID 填了非数字、onSale 填了非整数、pageNum/pageSize 不是整数）。'
    + '分页请核对：页码从 1 起、每页只能选 10/20/50/100 四档。',
  404: '商品列表接口不可达（后端服务未起或路径已变）。请确认后端服务在运行，再点「重试」。',
  429: '读取过于频繁（只读列表按来源 IP 限流 30 次/分）。请稍候再翻页。',
  // FE-G6-7（读侧）：500 是服务端接住这一请求之后自己抛的，跟「-1」（请求层自己判定的网络失败/abort）
  // 是两回事；这一条不许出现「网络异常/超时/检查代理」字样，也不许由 '-1' 那条兜底。
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一条读取时自己抛的。'
    + '本页这次读数不可信——不要拿这一屏的价格/库存/在售当库内真值用。'
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

// 写侧 400 的覆盖面：通用两句 + 本端点自己的业务文案 + @Valid 注解文案（三组都可能出现，命中哪一句只看服务端原文）
const WRITE_CODE_HINTS = {
  400: '这笔写请求被 400 拒绝。400 不说明命中哪一句，本页不猜。三类可能同时存在：'
    + '通用两句「请求参数有误，请检查后重试」/「参数格式不正确」（body 里的 price/supplyPrice 填了非数字就走后者）；'
    + '业务校验「无更新字段（可传 price / stock / onSale / supplyPrice）」、'
    + '审核动作的「驳回理由必填，且不超过 200 个字符」（理由空白或超长）与「action 仅允许 reject/restore」；'
    + '字段注解「价格必须大于 0」（price < 0.01）、「库存不能为负数」（stock < 0）、'
    + '「onSale 仅允许 0（下架）或 1（上架）」、「供货价必须大于 0」（supplyPrice ≤ 0）、'
    + '「供货价最多两位小数」（supplyPrice 三位以上小数，F-117）。'
    + '具体命中哪一句，以「服务端原文」那一行为准。',
  403: '登录态有效但服务端判定本账号不能改商品（403 ≠ 登录失效，本页不清登录态）。'
    + '请核对运营账号的角色与授权；商品价与库存的归属仍是商家侧的，确需变更请让有权限的账号操作。',
  404: '后端原文应为「菜品不存在」：这一行的菜品 ID 在库里已经没有（被商家删除，或本页列表已过期）。'
    + '下一步：用上方「查询」重取列表后再对现存的 ID 操作；不要拿旧 ID 反复重试。',
  429: '写请求过于频繁（Admin 写面按来源 IP 限流 10 次/分，比列表的 30 次/分更紧）。'
    + '批量改价请分时段，不要连点。',
  // FE-G6-7（写侧）：500 ≠ 未送达。请求已经打到服务端、被接住、由服务端自己抛的；
  // 把这一条继续渲染成「请求未能送达」会把运营引向"查网络/重启"的错误方向。
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一笔写动作时自己抛的——不是没送到。'
    + '⚠ 这一笔到底有没有落库不作数（服务端既然抛了 500，回执不可信）。'
    + '下一步：点上方「查询」重拉列表核对这一行的价格/库存/在售再决定要不要重做，'
    + '不要盲目重发（服务端状态机可能已经把这一行推进了，幂等早退会让第二次拿到别的错）。'
    + '反复出现，请让运维查服务端日志（本页不猜原因）。',
  '-1': '请求未能送达（网络异常或 20s 提交档超时）。⚠ 超时不等于「没生效」：服务端可能已经写完，只是回执没回来。'
    + '下一步：先点上方「查询」重拉列表核对这一行的价格/库存/在售，再决定要不要重做。'
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

async function load() {
  if (viewState.value === 'loading') return
  viewState.value = 'loading'
  errorText.value = ''
  errorCode.value = 0
  errorData.value = null
  try {
    const envelope = await getAdminDishes(pageNum.value, pageSize.value, filters.value)
    const data = envelope.data || {}
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
    if (Number(data.pageNum) > 0) pageNum.value = Number(data.pageNum)
    if (PAGE_SIZES.indexOf(Number(data.pageSize)) !== -1) pageSize.value = Number(data.pageSize)
    viewState.value = 'ready'
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
    errorText.value = e.message || '商品列表读取失败，请稍后重试'
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
  filters.value.merchantId = ''
  filters.value.onSale = ''
  filters.value.category = ''
  filters.value.auditStatus = ''
  pageNum.value = 1
  load()
}

function openDialog(row) {
  if (submitting.value || viewState.value === 'loading') return
  // 表单三个值一律从<b>空</b>开始（只有「不改该项」才是安全的默认），
  // 当前值只显示在 label 里当作参照——不把 price 的定形串 "9.90" 预填进去，
  // 那会让「原值回写」变成一次隐形改动（金额没变但会新写一条 dish.update 审计）。
  editing.value = row
  form.value = { price: '', stock: '', onSale: '', supplyPrice: '' }
  dialogVisible.value = true
}

// 只下发运营<b>实际填过</b>的键：留空的键根本不进 body（服务端「传了才更新」）。
// FE-G6-8：price/stock <b>不按 Number 下发、原样字符串</b>——
// Jackson 把 JSON 字符串 "4.40" 绑进 BigDecimal 会保住标度（scale=2），@DecimalMin("0.01") 走 compareTo
// 与标度无关照常生效，落库值与库里既有值逐字相同；这样 admin_audit_log.detail 里记的 new 才是运营所见的 "4.40"，
// 而不是 Number 化后丢尾零的 "4.4"（留痕保真：审计面所见 = 运营面所填 = 库里所存）。
// 非数字串（"9.9a"）同样原样下发，让服务端给那句诚实的 400（Jackson 不能解析 → GlobalExceptionHandler
// 走 HttpMessageNotReadable 分支，回「参数格式不正确」），本页不拦、不改 0、不猜。
// stock 是整数列：Integer 从字符串 "50" 反序列化走 Jackson 的标量 coercion（默认允许），值不变、形态更留痕。
// onSale 是 select（值为 0/1 <b>数字</b>），服务端 @Min/@Max 对 Integer 生效得很，不为"统一"把它改成字符串。
// supplyPrice（F-117）是唯一<b>恒下发</b>的键：它的「留空=清除」必须以显式 null 才能表达（键缺失=服务端不动该列），
// 填了同 price 的标度保真纪律原样字符串下发。全空提交且原值未设时，服务端 400「无更新字段…」兜底。
function buildPatch() {
  const patch = {}
  const price = String(form.value.price == null ? '' : form.value.price).trim()
  if (price !== '') patch.price = price
  const stock = String(form.value.stock == null ? '' : form.value.stock).trim()
  if (stock !== '') patch.stock = stock
  const onSale = form.value.onSale
  if (onSale === 0 || onSale === 1) patch.onSale = onSale
  const supplyPrice = String(form.value.supplyPrice == null ? '' : form.value.supplyPrice).trim()
  patch.supplyPrice = supplyPrice === '' ? null : supplyPrice
  return patch
}

function patchText(patch) {
  const lines = []
  if (patch.price !== undefined) {
    lines.push('· 改价：单价写为 ' + patch.price + '（当前 ' + displayOf(editing.value.price)
      + '）。只影响此后新下的订单，已生成的订单金额不受影响。')
  }
  if (patch.stock !== undefined) {
    lines.push('· 补库存：库存将被直接置为 ' + patch.stock + '（当前 ' + displayOf(editing.value.stock)
      + '）。注意这是「置为」而不是「在当前值上加」——填 10 就是把库存变成 10。')
  }
  if (patch.onSale !== undefined) {
    lines.push(patch.onSale === 1
      ? '· 上下架：该商品置为「上架」（on_sale=1），C 端可以下单。'
      : '· 上下架：该商品置为「下架」（on_sale=0），C 端不再能下单该商品；已经生成的订单不受影响。')
  }
  if (patch.supplyPrice !== undefined) {
    lines.push(patch.supplyPrice === null
      ? '· 供货价：清除（回 NULL）——未设供货价的商品不进结算聚合（当前 '
        + (editing.value.supplyPrice == null ? '本就未设' : displayOf(editing.value.supplyPrice)) + '）。'
      : '· 供货价：写为 ' + patch.supplyPrice + '（当前 '
        + (editing.value.supplyPrice == null ? '未设' : displayOf(editing.value.supplyPrice)) + '）。')
  }
  if (patch.price === undefined && patch.stock === undefined && patch.onSale === undefined
    && patch.supplyPrice === null && editing.value.supplyPrice == null) {
    lines.push('· 注意：四个字段实际都没改变（供货价本就未设，「清除」是空操作），服务端会回 '
      + '400「无更新字段（可传 price / stock / onSale / supplyPrice）」，不改任何数据。')
  }
  lines.push('· 未填的字段不会下发，服务端不会改动它们（供货价除外：它恒下发，留空即清除）。');
  lines.push('· 成功后服务端写一条 admin_audit_log（action=dish.update，detail 记 old/new，未提交的列显式记 null——既有留痕口径）。')
  return lines.join('\n')
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

async function onSubmit() {
  if (submitting.value || viewState.value === 'loading') return
  const patch = buildPatch()
  const ok = await askConfirm(
    '将更新商品 ' + editing.value.id + '（' + editing.value.name + '）。\n\n' + patchText(patch)
      + '\n· 提交成功后本页会重拉列表，不在本地改这一行。'
  )
  if (!ok) {
    writeResult.value = {
      kind: 'info',
      title: '已取消，未发出任何请求',
      serverText: '',
      hint: '确认框点「取消」/关闭/Esc 只关框：这次更新没有提交，表单还开着，可以继续改填或关掉它。'
    }
    return
  }
  submitting.value = true
  writeResult.value = null
  try {
    const envelope = await updateAdminDish(editing.value.id, patch)
    writeResult.value = {
      kind: 'success',
      title: '商品更新已提交',
      serverText: envelope && envelope.message ? envelope.message : '',
      hint: '列表已按当前筛选重新拉取（本页不本地改行），表单已关闭。请以重拉后的行为准。'
    }
    dialogVisible.value = false
    await load()
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
      writeResult.value = { kind: 'error', title: '登录状态已失效', serverText: '', hint: '正在返回登录页…' }
      return
    }
    // 失败：表单保持打开（内容还在），但页面不把行本地回滚、也不假装成功
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

// F-116 §2.3 驳回/恢复（写面走 PUT /api/admin/dishes/{id}/audit，既有改价/补库存/上下架端点不动）。
// reject 理由本地先拦一句 1~200（与服务端 trim 后同口径），最终以服务端 400 原文为准并如实转述；
// 二次确认文案逐字包含契约点名的两句：「驳回后 C 端即刻不可见不可买」「恢复需运营操作」。
function openReject(row) {
  if (submitting.value || viewState.value === 'loading') return
  rejectDialogTarget.value = row
  rejectForm.value = { reason: '' }
  rejectError.value = ''
  rejectDialogVisible.value = true
}

async function onSubmitReject() {
  if (submitting.value || viewState.value === 'loading') return
  const reason = String(rejectForm.value.reason == null ? '' : rejectForm.value.reason).trim()
  if (reason.length < 1 || reason.length > 200) {
    rejectError.value = '驳回理由必填，1~200 个字符（当前 ' + reason.length + ' 个）'
    return
  }
  rejectError.value = ''
  const target = rejectDialogTarget.value
  const ok = await askConfirm(
    '将驳回商品 ' + target.id + '（' + displayOf(target.name) + '）。\n\n'
      + '· 驳回后 C 端即刻不可见不可买（列表/搜索/秒杀/推荐同时消失，下单被拦）。\n'
      + '· 恢复需运营操作（本页该行的「恢复」按钮），商家端无法自行恢复。\n'
      + '· 驳回理由（下方原文）会展示给商家：' + reason + '\n'
      + '· 该商品的上下架状态不受影响；提交成功后本页会重拉列表。'
  )
  if (!ok) return
  submitting.value = true
  writeResult.value = null
  try {
    const envelope = await auditAdminDish(target.id, { action: 'reject', reason })
    rejectDialogVisible.value = false
    writeResult.value = {
      kind: 'success',
      title: '驳回已提交（商品 #' + target.id + '）',
      serverText: envelope && envelope.message ? envelope.message : '',
      hint: '列表已按当前筛选重新拉取。若当前开着审核态筛选（全部/正常），需切到「已驳回」或清除筛选才能看到该行。'
    }
    await load()
  } catch (err) {
    handleWriteFailure(err, '驳回')
  } finally {
    submitting.value = false
  }
}

async function onRestore(row) {
  if (submitting.value || viewState.value === 'loading') return
  const ok = await askConfirm(
    '将恢复商品 ' + row.id + '（' + displayOf(row.name) + '）。\n\n'
      + '· 恢复后审核态置回「正常」，C 端恢复可见可买（若该商品同时是下架状态，仍不可买）。\n'
      + '· 驳回理由将被清空；本次操作会记一条 dish.audit 审计。'
  )
  if (!ok) return
  submitting.value = true
  writeResult.value = null
  try {
    const envelope = await auditAdminDish(row.id, { action: 'restore' })
    writeResult.value = {
      kind: 'success',
      title: '恢复已提交（商品 #' + row.id + '）',
      serverText: envelope && envelope.message ? envelope.message : '',
      hint: '列表已按当前筛选重新拉取；驳回理由已随恢复清空（body 带 reason 会被服务端忽略）。'
    }
    await load()
  } catch (err) {
    handleWriteFailure(err, '恢复')
  } finally {
    submitting.value = false
  }
}

// 驳回/恢复共用的失败转述：复用本页 writeHintOf（服务端原文 + 分码人话），不另造第二套口径
function handleWriteFailure(err, actionName) {
  const e = err || {}
  if (e.code === 401) {
    writeResult.value = { kind: 'error', title: '登录状态已失效', serverText: '', hint: '正在返回登录页…' }
    return
  }
  writeResult.value = {
    kind: 'error',
    title: (e.code === 403 ? '没有权限执行该动作' : actionName + '未完成（未生效）'),
    serverText: e.message || '（后端未给出 message）',
    hint: writeHintOf(e.code, e.data)
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
  border-left: 3px solid #e6a23c;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
}
.sql-fallback code {
  font-family: Consolas, Menlo, monospace;
  background: #f5f6f8;
  padding: 0 4px;
  border-radius: 3px;
}
.result-bar {
  margin-top: 12px;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  border-left: 3px solid #909399;
}
.result-bar-success {
  border-left-color: #67c23a;
}
.result-bar-error {
  border-left-color: #f56c6c;
}
.result-bar-info {
  border-left-color: #e6a23c;
}
.result-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.result-line {
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.filter-input {
  width: 210px;
}
.filter-select {
  width: 190px;
}
.table {
  background: #fff;
  margin-top: 16px;
}
.empty {
  color: #909399;
  font-size: 13px;
}
.muted {
  color: #909399;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.dialog-note {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
}
.dialog-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.dialog-select {
  width: 220px;
}
.reject-field-error :deep(.el-textarea__inner) {
  border-color: #f56c6c;
}
.reject-error-line {
  margin: -6px 0 0 110px;
  font-size: 12px;
  color: #f56c6c;
}
</style>
