<template>
  <div class="page">
    <!-- FE-G6-10（读写可辨·写页徽标）：徽标挂在<b>页眉</b>而不是 state-box 里——原先「本页只读/会改数据」这类话
         只在 loading/error/forbidden 块内出现，ready 态一个字都不露，运营在日常态读不出这一页会不会动数据。
         本页有核销与取消两个写动作，所以常驻「本页会改数据」。 -->
    <div class="page-head">
      <h2 class="page-title">订单管理</h2>
      <el-tag type="danger" effect="dark" size="small" class="mode-badge">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      全量订单列表（运营面，不按门店归属过滤）+ 两个写动作：核销（completed）与取消（cancelled）。<br />
      列表源是<b>只读</b>的 GET /api/admin/v2/orders：外层恒 {list,total,pageNum,pageSize,filters}，
      筛选为<b>精确等值</b>（状态 / 门店 ID / 创建自然日，都不是模糊搜索），排序 create_time DESC、id DESC。<br />
      金额与时间显示的是<b>服务端定形串</b>，本页不做二次格式化；phone 由服务端脱敏（如 138****3869），本页不拼回明文、也不从别处凑。<br />
      状态映射（服务端出的是库里原值，文案由本页映射表给）：pending_pickup=待自提、completed=已完成、cancelled=已取消；
      payStatus：0=未支付、1=已支付、2=已退款（只有已支付单被取消后才出现 2）。
    </p>

    <!-- 「SQL 兜底」提示：只写手册的<b>节号</b>，不抄 SQL 正文（抄进前端就成了第二处口径真源，F-20.17 拆批 5b-2 钉死）。
         用普通 div 而不是 el-alert：两条提示条都要装多行富文本，自己控制样式比依赖组件槽位稳。 -->
    <div class="sql-fallback">
      <b>SQL 兜底</b>：核销（把订单置为已完成）可用 <code>docs/日常运营SQL手册.md</code> <b>第一节</b> 代办；
      取消订单请先用 <b>第五节</b>（查今日订单）定位再操作。本页面走的是接口，两边的状态机与留痕口径以手册那两节为准。
    </div>

    <!-- FE-G6-5 的教训：工具条住在状态块<b>之外</b>，loading / error / forbidden / ready 四态都留着筛选条，
         否则查询失败时运营连改筛选值的输入框都没有，只剩「重试」，只能拿同一个脏值反复撞 400。
         loading 或写请求在飞时置 disabled（但不消失）。 -->
    <div class="toolbar">
      <el-select v-model="filters.status" size="small" class="filter-select" clearable
                 placeholder="状态（三态之一，精确）"
                 :disabled="viewState === 'loading' || submitting" @change="onFilterChange">
        <el-option label="待自提 pending_pickup" value="pending_pickup" />
        <el-option label="已完成 completed" value="completed" />
        <el-option label="已取消 cancelled" value="cancelled" />
      </el-select>
      <!-- P4-13 A15/D1：门店筛选由手输 ID 改为真实门店下拉（GET /api/stores，匿名端点）；
           value 统一字符串（与筛选口径一致），label=门店名 (#id)。加载失败时退回手输 ID，
           筛选能力不净减（契约 §2.1/§3）——下拉与手输绑同一份 filters.storeId。 -->
      <el-select v-if="!storeChoicesError" v-model="filters.storeId" size="small" class="filter-select" clearable filterable
                 :loading="storeChoicesLoading"
                 placeholder="门店（不选=全部）"
                 :disabled="viewState === 'loading' || submitting" @change="onFilterChange">
        <el-option v-for="s in storeChoices" :key="s.id" :label="s.name + ' (#' + s.id + ')'" :value="String(s.id)" />
      </el-select>
      <el-input v-else v-model="filters.storeId" size="small" class="filter-input" clearable
                placeholder="门店 ID（手输，门店列表加载失败时的回退）"
                :disabled="viewState === 'loading' || submitting" @change="onFilterChange" />
      <el-date-picker v-model="filters.date" size="small" type="date" class="filter-date"
                      value-format="YYYY-MM-DD" format="YYYY-MM-DD" :clearable="true"
                      placeholder="按创建日（自然日，默认不限）"
                      :disabled="viewState === 'loading' || submitting" @change="onFilterChange" />
      <el-button size="small" type="primary" :disabled="viewState === 'loading' || submitting" @click="onFilterChange">查询</el-button>
      <el-button size="small" :disabled="viewState === 'loading' || submitting" @click="onReset">重置</el-button>
      <!-- P6-3 §2：订单 CSV 导出（只读，全量上限 5000 行）。在飞禁用照本页既有口径
           （:loading 表导出自身在飞 + disabled 照查询/重置同一组页面状态）；
           不加确认框——只读导出，与本页写动作的 ElMessageBox 确认体系无关（契约 §2 明定）。 -->
      <el-button size="small" :loading="exporting" :disabled="viewState === 'loading' || submitting" @click="onExportCsv">导出 CSV</el-button>
      <!-- P4-13 D2：下拉失败不阻断主列表（列表照常读），只在本行尾给可点重试；
           文案与全部筛选控件同一行，运营不必离开筛选区就能自救。 -->
      <span v-if="storeChoicesError" class="muted choices-fallback" :title="storeChoicesError">
        <el-button link type="primary" size="small" :disabled="storeChoicesLoading"
                   @click="loadStoreChoices">门店列表加载失败，点击重试</el-button>
      </span>
    </div>

    <!-- 写动作的结果条：同样放在状态块之外——写成功后要重拉列表，那一刻 viewState 是 'loading'，
         表格被卸载，如果这条提示长在表格里它会跟着消失，运营就看不到「刚才那一笔成没成」。 -->
    <div v-if="writeResult" class="result-bar" :class="'result-bar-' + writeResult.kind">
      <div class="result-title">{{ writeResult.title }}</div>
      <div v-if="writeResult.serverText" class="result-line">服务端原文：{{ writeResult.serverText }}</div>
      <div v-if="writeResult.hint" class="result-line">{{ writeResult.hint }}</div>
    </div>

    <div v-if="viewState === 'loading'" class="state-box">
      <div class="state-title">正在加载</div>
      <div class="state-desc">订单列表读取中…</div>
    </div>

    <div v-else-if="viewState === 'forbidden'" class="state-box">
      <div class="state-title">没有访问权限</div>
      <div class="state-desc">{{ errorText }}</div>
      <div class="state-desc">403 与 401 不同义：登录态是有效的，但服务端判定本账号不能读这一面。请核对运营账号的角色与授权。</div>
      <el-button size="small" @click="load()">重试</el-button>
    </div>

    <div v-else-if="viewState === 'error'" class="state-box">
      <div class="state-title">读取失败</div>
      <div class="state-desc">{{ errorText }}</div>
      <div v-if="codeHint" class="state-desc">{{ codeHint }}</div>
      <el-button size="small" @click="load()">重试</el-button>
    </div>

    <template v-else>
      <!-- FE-G6-9（表头粘性，F-20.28 B2-1 实测裁定）：原先表格无 max-height、根高 1132px、表体不内部滚动，
           页面滚到底后表头 top=-304 整条离开视口，14 列宽表滚动时对不上列名。
           设 max-height 后表体在 .el-table 内部滚动、表头常驻；offset 实算：页头 56 + 页眉/说明/工具条 ≈ 343 +
           分页器 ~48 + 底部余量 ≈ 380，即 100vh-420 在 1440×900 与 1280×800 两档都不把分页器推出视口。 -->
      <el-table :data="rows" border stripe size="small" class="table" max-height="calc(100vh - 420px)">
        <!-- 空表 / 筛选无命中 → 「暂无订单」，不是报错：后端回的是 {list:[],total:0} -->
        <template #empty>
          <span class="empty">暂无订单（当前筛选下没有命中）</span>
        </template>
        <!-- 19 位雪花 id：服务端 JSON 里就是<b>字符串</b>，列上按字符串显示，点写动作时原样回传（绝不 Number()） -->
        <el-table-column prop="id" label="订单 ID" min-width="180" show-overflow-tooltip />
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="支付" min-width="90">
          <template #default="{ row }">{{ payStatusText(row.payStatus) }}</template>
        </el-table-column>
        <el-table-column label="支付时间" min-width="160">
          <template #default="{ row }"><span class="muted">{{ row.payTime }}</span></template>
        </el-table-column>
        <!-- totalPrice 是服务端 moneyText 定的两位小数字符串，原样显示、不做数字换算 -->
        <el-table-column prop="totalPrice" label="金额" min-width="90" />
        <el-table-column prop="pickupDate" label="自提日" min-width="110" />
        <!-- phone 已在服务端过 PhoneMask（D-15）：这一列就是掩码形态 -->
        <el-table-column prop="phone" label="手机（已脱敏）" min-width="130" />
        <el-table-column label="自提点" min-width="160" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.storeName }">{{ row.storeName }}</span></template>
        </el-table-column>
        <el-table-column label="自提点地址" min-width="220" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.storeAddress }">{{ row.storeAddress }}</span></template>
        </el-table-column>
        <!-- FE-G6-11 撤掉恒空列（F-20.28 B 路 B2-5 实测裁定）：原先这里有一列「地址（下单快照）」，
             库里 orders.address 实测 <b>71/71 全是空串</b>（nulls=0、empties=71、valued=0），
             首屏 20 行该列一律挂 .muted 显示空白——一列固定占 180px 的宽度、却永远没有内容，
             只会把真正有信息的列挤出横向滚动区。写侧也没有任何路径会给它值：
             OrderServiceImpl#createOrder 只是透传 C 端 dto.getAddress()（本仓 C 端是自提模式，不下发该字段），
             FreeServiceImpl#claim 直接写死 ""。所以<b>本页不再渲染这一列</b>；
             后端出参的 address 键照旧下发（不改后端、不改库），页面不读它就是了。 -->
        <el-table-column label="门店 ID" min-width="90">
          <template #default="{ row }"><span class="muted">{{ row.storeId }}</span></template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" min-width="160" />
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }"><span class="muted">{{ row.updateTime }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <!-- 服务端状态机只允许 pending_pickup 迁出（AdminServiceImpl#updateOrderStatus 那句「当前状态不可变更」就是这个门）。
                 非待自提的行没有可用迁出 → 显示一句实况说明，而不是摆两个必然被 400 拒掉的按钮钓运营来点。
                 这不是「本地改行」：这一格读的是<b>服务端上一轮回的行值</b>，写完一律重拉列表（见 runWrite 那一行）。 -->
            <template v-if="row.status === 'pending_pickup'">
              <el-button size="small" type="success" plain
                         :disabled="viewState === 'loading' || submitting"
                         @click="onWrite(row, 'completed')">核销</el-button>
              <el-button size="small" type="danger" plain
                         :disabled="viewState === 'loading' || submitting"
                         @click="onWrite(row, 'cancelled')">取消订单</el-button>
            </template>
            <span v-else class="muted">无可用动作（仅待自提可核销/取消）</span>
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
          :disabled="submitting"
          @size-change="onSizeChange"
          @current-change="onPageChange"
        />
      </div>
    </template>
  </div>
</template>

<script setup>
// 订单管理（F-20.22 Step 5b-2 四页之一；原名「核销台」，与主理人端 verify.vue 撞名，2026-10-20 改）：
//   列表 = GET /api/admin/v2/orders（5b-1 交付的只读 v2 列表；status/storeId/date 三个筛选全可选、精确等值）
//   写   = PUT /api/admin/orders/{id}/status（AdminController 旧面，运营 JWT 第二轨，F-20.17 事实 2 已实测整条写链路可用）
// 三条本页级纪律：
//   ① 零裸 fetch、零自拼 URL：列表与写动作都是从 ../api 拿现成函数（纪律 1），Authorization 由请求层自动挂；
//   ② 每个写动作 ElMessageBox.confirm 二次确认，文案把<b>后果</b>写全（纪律 2）；确认框点「取消」/关闭/Esc
//      一律 reject → askConfirm 返回 false → 函数当场 return，一条请求都不发；
//   ③ 写成功后一律 load() 重拉列表，绝不在前端把行本地改成 completed/cancelled（纪律 3）：
//      幂等早退、并发翻态这些只有服务端状态机知道，本地改行就是说谎。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminOrders, updateAdminOrderStatus, getStores, retryAfterText, exportAdminOrders } from '../api'

const PAGE_SIZES = [10, 20, 50, 100]

// D-18：枚举/布尔列服务端出的是库里原值，文案映射表写在页面顶部（与本表同一份措辞）。
// 映射表里没有的值一律<b>原样显示</b>，不编一个「未知状态」出来骗运营。
const STATUS_TEXT = { pending_pickup: '待自提', completed: '已完成', cancelled: '已取消' }
const PAY_STATUS_TEXT = { 0: '未支付', 1: '已支付', 2: '已退款' }
const statusText = (v) => STATUS_TEXT[v] || (v == null ? '' : String(v))
const payStatusText = (v) => PAY_STATUS_TEXT[v] || (v == null ? '' : String(v))
const statusTagType = (v) => (v === 'pending_pickup' ? 'warning' : v === 'completed' ? 'success' : 'info')

// viewState 只承担<b>列表读取</b>这一条状态机（单值互斥：loading / ready / forbidden / error）；
// 写动作的在飞由 submitting 一个布尔位承担，两者不互相覆盖（否则写成功后页面回不到 ready）。
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
// 本页筛选态的唯一真源就是这一份（后端回显的 filters 不参与渲染）。空串由请求层 withQuery 挡下、不下发。
const filters = ref({ status: '', storeId: '', date: '' })

// P4-13 A15/D1：门店下拉的数据源（GET /api/stores 是既有匿名端点，与 Arrivals.vue 同源）。
// 三件套各管一件事：choices=选项、loading=加载中、error=失败（非空即退回手输 ID + 行尾重试）。
const storeChoices = ref([])
const storeChoicesLoading = ref(false)
const storeChoicesError = ref('')

// ---------- 读侧「下一步」文案 ----------
// 纪律 4：400 在服务端有<b>两句</b>诚实原文（AdminV2ServiceImpl 的 BAD_REQUEST_MSG「请求参数有误，请检查后重试」
// 与 GlobalExceptionHandler:70 对类型绑不进去的「参数格式不正确」）。服务端只回一枚 400、不说明命中哪一句，
// 所以这一条把两句都覆盖并列出各自的触发面，由运营自己核对；本页<b>不猜</b>是哪一句（猜即编造，FE-G6-6 抓的就是这个）。
const CODE_HINTS = {
  400: '请求参数不在允许范围内。服务端对 400 有两句原文且不说明命中哪一句，本页不猜，以上方后端原文为准：'
    + '「请求参数有误，请检查后重试」= 值过不了白名单/形状闸门（状态不是三态之一、门店 ID 或日期形状不合法、页码 <1 或每页超出 1~100）；'
    + '「参数格式不正确」= 值绑不进目标类型（门店 ID 填了非数字、pageNum/pageSize 不是整数）。'
    + '分页请核对：页码从 1 起、每页只能选 10/20/50/100 四档。',
  404: '订单列表接口不可达（后端服务未起或路径已变）。请确认后端服务在运行，再点「重试」。',
  429: '读取过于频繁（只读列表按来源 IP 限流 30 次/分）。请稍候再翻页。',
  // FE-G6-7（读侧）：500 是服务端自己抛的内部错误，请求是送达并被服务端接住的，
  // 与「-1」（请求层自己判定的网络失败/abort）是两回事，本页绝不把 500 说成链路问题。
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一条读取时自己抛的。'
    + '本页这次读数不可信——不要拿这一屏的行列值当库内真值用。'
    + '下一步：稍后点上方「查询」或「重试」重拉本列表核对；若同一条件反复出现这一条，请让运维查服务端日志（本页不猜具体原因）。',
  '-1': '请求未能送达（网络异常或 10s 查询档超时）。请确认后端服务已启动，并用 http://localhost:5177 打开本页面'
    + '（用 127.0.0.1 形态打开会被后端 CORS 全拦，渲染成这一条假故障——后端其实活得好好的）。'
}
// 未知码走 ''：与 AuditLogs.vue 同一份「宁可不说话也不猜」的诚实（FE-G6-7 纪律 4）。
// 只有 code 恰为 -1 时，CODE_HINTS['-1'] 那条关于链路/超时的文案才被引用。
// F-22 FE-429-2：读侧只有 429 才追加工具层转述的那句「服务端约 N 秒后可再试。」；
// 400/404/500/-1 一律不拼（对它们是假信息），缺载荷时 retryAfterText 回 '' → 本条与改动前逐字相同。
const codeHint = computed(() => (CODE_HINTS[String(errorCode.value)] || '')
  + (errorCode.value === 429 ? retryAfterText(errorData.value) : ''))

// ---------- 写侧「下一步」文案 ----------
// 写端点的 400 面更宽（业务状态机文案 + @Valid 注解文案 + 通用两句），所以这里既列两句通用原文，
// 也点名本端点自己的业务文案；命中哪一句永远以「服务端原文」那一行为准。
const WRITE_CODE_HINTS = {
  400: '这笔写请求被 400 拒绝。400 不说明命中哪一句，本页不猜：通用两句是'
    + '「请求参数有误，请检查后重试」与「参数格式不正确」；本端点自己的业务文案有'
    + '「status 仅允许 completed（核销）或 cancelled（取消）」、「当前状态不可变更（仅待自提订单可核销/取消）」、「status 不能为空」。'
    + '请以「服务端原文」那一行为准，逐句对照后再改。',
  403: '登录态有效但服务端判定本账号不能做这个动作（403 ≠ 登录失效，所以本页不会清登录态）。'
    + '请核对运营账号的角色与授权；确需核销请换有权限的账号重新登录。',
  404: '后端原文应为「订单不存在」：这一行的订单 ID 在库里已经没有（被清理，或本页列表已过期）。'
    + '下一步：用上方「查询」按当前筛选重取列表，再对表里现存的 ID 操作；不要拿旧 ID 反复重试。',
  429: '写请求过于频繁（Admin 写面按来源 IP 限流 10 次/分，比列表的 30 次/分更紧）。'
    + '批量核销请分时段，不要连点。',
  // FE-G6-7（写侧）：500 ≠ 未送达。请求已经打到服务端、被接住、由服务端自己抛的，
  // 把这一条继续渲染成「请求未能送达」会把运营引向"查网络/重启"的错误方向。
  500: '服务端内部错误（HTTP 500）：请求已经送达并被服务端接住，这一枚码就是服务端在处理这一笔写动作时自己抛的——不是没送到。'
    + '⚠ 这一笔到底有没有落库不作数（服务端既然抛了 500，回执不可信）。'
    + '下一步：点上方「查询」按当前筛选重拉列表核对这一单的 status/payStatus；不要盲目重发，'
    + '幂等早退可能让第二次拿到「当前状态不可变更」这类别的错。反复出现，请让运维查服务端日志（本页不猜原因）。',
  '-1': '请求未能送达（网络异常或 20s 提交档超时）。⚠ 超时不等于「没生效」：服务端可能已经写完，只是回执没回来。'
    + '下一步：先点上方「查询」重拉列表核对这一单的当前状态，再决定要不要重做——'
    + '若它已是目标状态，说明那一笔已经成功（重复提交最坏只会拿到「当前状态不可变更…」）。'
}
// FE-G6-7：未知码的兜底<b>不能</b>再指向 WRITE_CODE_HINTS['-1']——那一条只描述请求层自己判定的网络/abort，
// 用它兜 500/501/502… 就是在给运营编造「未送达」这条不存在的因果。
// 只有 code 恰为 -1（网络失败或主动 abort，见 ../api/index.js 的 request catch）才允许走「未能送达」那条。
// 其它未预置的码：中性一句、把码原样念出来、明确「本页不猜原因」，让「服务端原文」那一行做真值。
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

// A9/D5 排队标志：写成功那一刻列表可能正有一笔读在飞，既有在飞守卫会把这次重拉直接吞掉，
// 而结果条已经写下「列表已按当前筛选重新拉取」。force=true 的在飞不丢弃，只置这个标志，
// 由当前请求落地后的 finally 补拉一次——结果条与列表才同源。模块级布尔，不参与渲染。
let reloadQueued = false

async function load(force) {
  // FE-G6-3 在飞守卫：请求未回时不再发第二条（筛选 @change、分页器、「重试」同守这一条），
  // 否则乱序回包会把上一页的行画在下一页的高亮下。复用本页已有的单值 viewState。
  // A9/D5：写后重拉（force=true）在飞时不静默丢弃，只置排队标志，落地后由 finally 补拉一次。
  if (viewState.value === 'loading') {
    if (force) reloadQueued = true
    return
  }
  viewState.value = 'loading'
  errorText.value = ''
  errorCode.value = 0
  errorData.value = null
  try {
    const envelope = await getAdminOrders(pageNum.value, pageSize.value, filters.value)
    const data = envelope.data || {}
    rows.value = Array.isArray(data.list) ? data.list : []
    // total 用后端独立 COUNT 的值，绝不用本页行数推算（那会把「这一页几条」说成「一共几条」）
    total.value = Number(data.total) || 0
    if (Number(data.pageNum) > 0) pageNum.value = Number(data.pageNum)
    if (PAGE_SIZES.indexOf(Number(data.pageSize)) !== -1) pageSize.value = Number(data.pageSize)
    viewState.value = 'ready'
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
      // 401 由请求层收敛（清 token + 跳登录页），页面只留一句过渡文案（纪律 4）
      viewState.value = 'error'
      errorCode.value = 401
      errorText.value = '登录状态已失效，正在返回登录页…'
      // A9/D5：请求层已清态跳登录，本页不再补拉（否则会在登录页背后白打一次读请求）
      reloadQueued = false
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
    errorText.value = e.message || '订单列表读取失败，请稍后重试'
  } finally {
    // A9/D5：补拉放在 finally 里——catch 的 401/403 分支带 return，也必经这里；401 已在分支内清掉标志。
    // 此刻 viewState 已落定（不再是 'loading'），重入 load(true) 不会被在飞守卫吞掉，也不会自我循环
    //（第二趟进 finally 时标志已被本块清空）。
    if (reloadQueued) {
      reloadQueued = false
      load(true)
    }
  }
}

function onPageChange() {
  load()
}

function onSizeChange() {
  // 换每页条数回第 1 页：原页码在新页大小下很可能已越界，而服务端越界页回的是<b>空 list + 定值 total</b>（不是 400），
  // 那会被误读成「这一档没有数据」
  pageNum.value = 1
  load()
}

function onFilterChange() {
  // 任一筛选变化必须回第 1 页（同上因）。load() 自带在飞守卫。
  pageNum.value = 1
  load()
}

function onReset() {
  filters.value.status = ''
  filters.value.storeId = ''
  filters.value.date = ''
  pageNum.value = 1
  load()
}

// P6-3 §2：订单 CSV 导出（GET /api/admin/orders/export，只读；导出端点无筛选参数，全量拉全上限 5000 行）。
// 不加确认框（只读导出与写动作确认体系无关，契约 §2 明定）；成功不弹 toast——浏览器自带下载提示，
// 再弹一条就是重复告知；失败只 ElMessage（不进 writeResult 结果条：它不是写动作，没有「写后重拉」的后续）。
const exporting = ref(false)
async function onExportCsv() {
  if (exporting.value) return
  exporting.value = true
  try {
    const r = await exportAdminOrders()
    // blob → a.download：anchor 先挂进文档再 click（Firefox 要求节点在文档内）；
    // revokeObjectURL 推迟到下一轮宏任务（setTimeout 0）——click 同步启动下载，但个别浏览器
    // 在同任务内 revoke 仍有竞态隐患，延后一拍零成本换稳。
    const url = URL.createObjectURL(r.blob)
    const a = document.createElement('a')
    a.href = url
    a.download = r.filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (err) {
    const e = err || {}
    ElMessage.error(e.message || '导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

// P4-13 D2：门店下拉的加载/失败态照 Arrivals 的姿势——失败只弹 toast + 记下原因（模板据此退回手输 ID
// 并在筛选行尾给「点击重试」），既不写本页 viewState，也不动 filters，所以订单列表照常可读可筛。
// 开拉时先清原因（同 Arrivals loadStores 的 storesNote=null）：重试期间下拉以 :loading 表达在飞。
async function loadStoreChoices() {
  if (storeChoicesLoading.value) return
  storeChoicesLoading.value = true
  storeChoicesError.value = ''
  try {
    const envelope = await getStores()
    storeChoices.value = Array.isArray(envelope && envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    storeChoices.value = []
    storeChoicesError.value = e.message || '门店列表加载失败'
    ElMessage.error(e.message || '门店列表加载失败')
  } finally {
    storeChoicesLoading.value = false
  }
}

/**
 * 二次确认（纪律 2）。只有点「确认执行」才返回 true；
 * 点「取消」、点右上角关闭、按 Esc 在 ElMessageBox 里一律是 reject → 这里吞掉并返回 false，
 * 调用方拿到 false 立刻 return。这就是「确认框点取消必须零请求」的唯一实现处——
 * 确认之后的那一次 await 是本函数之外仅有的网络调用。
 * customClass 让 App.vue 里那一条全局样式把 \\n 渲染成真的换行（scoped 样式作用不到挂在 body 下的弹层）。
 */
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

/**
 * F-61 取消理由输入。取消 = 单方面终止一笔已付款交易（后端会回补库存、已支付单置 pay_status=2
 * 「已退款」并按明细回退销量），所以这一枚刻意不是 confirm 而是 <b>prompt</b>：空白理由在前端就拒收，
 * 不给发出请求的机会——后端同样必填（AdminServiceImpl 那句「取消订单必须填写原因」），
 * 两边同判据、谁先到都不影响结论，前端的这一道只是让运营不必点下去才知道要填。
 * 返回 null = 点取消/关闭/Esc（等价「什么都没发生」）；返回字符串 = 已 trim 的理由。
 * 上限 255 与后端 @Size 同值，也与入驻审核意见同值——长度口径全站只留一把。
 */
async function askReason(message) {
  try {
    const r = await ElMessageBox.prompt(message, '取消订单需填写原因', {
      confirmButtonText: '确认取消该订单',
      cancelButtonText: '返回（不发出任何请求）',
      inputPlaceholder: '例如：顾客要求退款 / 缺货无法履约 / 重复下单',
      inputType: 'textarea',
      inputValidator: (v) => {
        const s = String(v == null ? '' : v).trim()
        if (!s) return '取消订单必须填写原因'
        if (s.length > 255) return '原因不能超过 255 字'
        return true
      },
      closeOnClickModal: false,
      distinguishCancelAndClose: true,
      customClass: 'admin-write-confirm'
    })
    return String((r && r.value) || '').trim()
  } catch (e) {
    return null
  }
}

// 确认文案：把后果写全（口径逐条来自 AdminServiceImpl#updateOrderStatus），不写「确定吗？」。
function confirmTextOf(id, status) {
  if (status === 'completed') {
    return '将把订单 ' + id + ' 置为「已完成」（核销）。\n\n'
      + '· 语义：买家已到自提点取走货，服务端把 status 由 pending_pickup 改为 completed，并写一条 admin_audit_log（order.status）。\n'
      + '· 不可回退：completed 不是本页能再改出来的状态（服务端状态机只允许待自提订单迁出）。\n'
      + '· 若它已被他人核销或取消，服务端会拒绝并回「当前状态不可变更（仅待自提订单可核销/取消）」，'
      + '本页如实显示，也不会在本地把行改掉。\n'
      + '· 核销只改状态：不回补库存、不动优惠券、不动支付状态。'
  }
  return '将把订单 ' + id + ' 置为「已取消」（取消订单）。\n\n'
    + '· 回补库存：按该单明细把数量加回各菜品的 stock。\n'
    + '· 回补优惠券：该单用掉的券会被退回可用。\n'
    + '· 已支付单：pay_status 由 1 置为 2（已退款），并按明细回退销量。'
    + '本仓是 mock 收银台，没有任何真实资金流——不会产生退款交易，只是把状态补齐。\n'
    + '· 同样只允许从 pending_pickup 迁出；已是 completed / cancelled 的单会被服务端拒绝。\n'
    + '· 取消后该单不可恢复为待自提，并写一条 admin_audit_log（order.status）。'
}

async function onWrite(row, status) {
  // 写侧在飞守卫：写请求未回、或列表正在重拉时一律不再发（与按钮的 :disabled 互为双保险）
  if (submitting.value || viewState.value === 'loading') return
  // 取消走「理由输入框」（F-61，必填），核销走普通二次确认（现场履约收尾，不逼运营编一句话）
  const needReason = status === 'cancelled'
  let reason = ''
  let ok = false
  if (needReason) {
    const r = await askReason(confirmTextOf(row.id, status))
    ok = r !== null
    reason = r || ''
  } else {
    ok = await askConfirm(confirmTextOf(row.id, status))
  }
  if (!ok) {
    // 确认框被取消 → 这一行之上没有任何网络调用，只如实说明「什么都没发生」
    writeResult.value = {
      kind: 'info',
      title: '已取消，未发出任何请求',
      serverText: '',
      hint: '确认框点「取消」/关闭/Esc 只关框：这一笔核销/取消没有提交，列表与库里都没有变化。'
    }
    return
  }
  submitting.value = true
  writeResult.value = null
  try {
    // row.id 是服务端回的 19 位雪花<b>字符串</b>，原样交给请求层拼路径（纪律：绝不 Number() 化，一化就丢精度 → 假 404）
    const envelope = await updateAdminOrderStatus(row.id, status, needReason ? reason : undefined)
    writeResult.value = {
      kind: 'success',
      title: status === 'completed' ? '核销已提交' : '取消已提交',
      serverText: envelope && envelope.message ? envelope.message : '',
      hint: '列表已按当前筛选重新拉取（本页不本地改行）。请以重拉后的行为准。'
    }
    // 纪律 3：写成功后<b>一律</b>重拉列表——这一行就是「不在前端本地改状态」的落点
    // A9/D5：force=true —— 若列表恰有一笔读在飞，load 会排队并在其落地后补拉，不会被在飞守卫静默吞掉
    await load(true)
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
      // 401 由请求层收敛清态跳登录，这里只留一句过渡文案
      writeResult.value = { kind: 'error', title: '登录状态已失效', serverText: '', hint: '正在返回登录页…' }
      return
    }
    // 失败时<b>不</b>重拉列表、也不改行：这一行在库里的状态没被这次请求改动过，
    // 页面上的行仍是服务端上一轮给的真值；只把后端原文与「下一步」如实摆出来。
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
  // 首屏这一行是必须的：viewState 初值 'loading' 只用于首屏占位，原样进 load() 会被在飞守卫当成
  // 「已在飞」而永久早退（一次请求都不发、页面卡在「正在加载」）。落到 'idle' 后同一同步 tick 内
  // load() 立刻置回 'loading'，浏览器不会在两个值之间绘制。
  viewState.value = 'idle'
  load()
  // P4-13：门店下拉独立加载（与订单列表互不阻塞，各自失败各自报）
  loadStoreChoices()
})
</script>

<style scoped>
/* 两条提示条（SQL 兜底 / 写动作结果）自己控制样式：都要装多行富文本，不依赖组件槽位 */
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
.filter-input {
  width: 200px;
}
.filter-select {
  width: 220px;
}
/* 与 Overview.vue 的 FE-G6-1 同源：日期选择器根元素给到 280px（内层 input 实测 236px），placeholder 不被切 */
.toolbar :deep(.el-date-editor) {
  width: 280px;
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
/* P4-13：门店下拉失败时的行尾重试行——与工具栏同一条 flex 行，垂直居中即可，不另造视觉 */
.choices-fallback {
  display: inline-flex;
  align-items: center;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
