<template>
  <div class="page">
    <!-- FE-G6-10（读写可辨）：本页只读（它记录的是**别人的**写动作，自己一个写端点都不调）。
         常驻徽标与写页同一套形态；下句「……写动作的留痕」说的是记录对象，不是本页行为。
         F-45 订正：这一句原先写的是「Admin 侧写动作」，与事实不符——admin_audit_log 里也有商家侧的行，
         MerchantDishServiceImpl:166/263/310/333 与 MerchantServiceImpl:166/175 走的正是
         AdminAuditLogMapper.insert 这一条通道（actor 前缀 merchant:<userId>）。
         由这个事实得出一条用法约束：**按 action 筛会把两轨的行混在一起**（dish.update 两轨都写），
         要认定「是谁做的」必须看 actor 列——所以下方的预设只给 actor 恒定那一枚配 actor，
         其余预设给的 action 都是单轨的（实测清单见 script 段 QUICK_FILTERS 上方注释）。 -->
    <div class="page-head">
      <h2 class="page-title">审计记录</h2>
      <el-tag type="info" effect="plain" size="small" class="mode-badge">本页只读</el-tag>
    </div>
    <p class="page-note">
      Admin 与商家侧写动作的留痕（谁、做了什么、对哪一条），按 id 倒序分页；服务端手写 LIMIT/OFFSET + 独立 COUNT。<br />
      可用「操作者 / 动作 / 目标类型 / 目标 ID」四个筛选，<b>均为精确等值（不是模糊搜索），且不区分大小写</b>；
      「目标 ID」同样是精确等值。筛选后「共 N 条」与列表来自同一 WHERE 的独立 COUNT（同源，D-10）。<br />
      不想记字符串就用下方「常用筛选」——它只是把实测在用的 actor / action 填进上面这四个框，走的是同一次查询。
    </p>

    <!-- FE-G6-5：工具条住在状态块**之外**，与 Overview.vue 同构——
         loading / error / forbidden / ready 四种状态都保留筛选条，否则查询失败时
         运营连改筛选值的输入框都没有，只剩「重试」，只能拿同一个脏值反复撞 400。
         loading 期间用既有的单值 viewState 把输入框与查询/重置按钮置 disabled（不新增状态变量），
         但绝不从 DOM 卸载。表格 + 分页器仍只在 ready 态渲染（见下方 v-else）。
         F-20.16 四个精确等值筛选（文本输入即可，不引新组件）+ 重置。
         @change 在失焦/回车时触发（不是每次按键），配合下方 onFilterChange 把页码回第 1 页后重查；
         空串由请求层 withQuery 挡下、不下发（服务端按「不筛」处理）。筛选变化沿用 FE-G6-3 的在飞守卫。 -->
    <div class="toolbar">
      <el-input v-model="filters.actor" size="small" class="filter-input" clearable
                :disabled="viewState === 'loading'"
                placeholder="操作者（精确，不区分大小写）" @change="onFilterChange" />
      <el-input v-model="filters.action" size="small" class="filter-input" clearable
                :disabled="viewState === 'loading'"
                placeholder="动作（精确，如 dish.update）" @change="onFilterChange" />
      <el-input v-model="filters.targetType" size="small" class="filter-input" clearable
                :disabled="viewState === 'loading'"
                placeholder="目标类型（精确，如 dish）" @change="onFilterChange" />
      <el-input v-model="filters.targetId" size="small" class="filter-input" clearable
                :disabled="viewState === 'loading'"
                placeholder="目标 ID（精确等值）" @change="onFilterChange" />
      <el-button size="small" type="primary" :disabled="viewState === 'loading'" @click="onFilterChange">查询</el-button>
      <el-button size="small" :disabled="viewState === 'loading'" @click="onReset">重置</el-button>
    </div>

    <!-- F-45「常用筛选」预设：F-44 把微信回调拒收写进了本表（每拒一条落一行），但本页此前只有四个手填框，
         运营必须自己知道去填 system:wx-notify / pay.notify_reject 才查得到——功能等于没落地。这一排就是那个入口。
         形态纪律：点一下只把 actor/action 写进**上面那四个既有输入框绑的同一个 filters**，再调既有的 onFilterChange()；
         零第二套查询逻辑、零新状态变量、零新 api 函数（applyQuickFilter 是本文件内的普通函数，不发请求）。
         预设值一律来自 backEnd 实测写入点（清单见 script 段 QUICK_FILTERS 上方），没照 javadoc 的举例造。
         这一排也住在状态块**之外**（同 FE-G6-5）：400 之后运营要能立刻换一枚预设，而不是只剩「重试」。
         disabled 条件与上方查询/重置逐字一致（loading 期间置灰、不卸载）。 -->
    <div class="quick-filters">
      <span class="quick-filters__label">常用筛选</span>
      <el-button v-for="q in QUICK_FILTERS" :key="q.label" size="small"
                 :disabled="viewState === 'loading'" @click="applyQuickFilter(q)">{{ q.label }}</el-button>
    </div>
    <!-- F-45 追责红线：actor 是 system:wx-notify 的那些行由平台自己写下（回调被拒那一刻没有任何人在场操作），
         「看起来像有人做了这件事」的误读比没有这个入口更糟——这张表的用途之一就是事后追责。
         所以选中这一枚（或手填同一个 actor）时把这句话摊开在页面上。
         判据只看 filters.actor（既有那份筛选态），不新增状态变量；普通文档流一到两行（窄屏自然换行），
         不做任何定位、不参与表格布局，绝不压到表格。 -->
    <p v-if="filters.actor === AUDIT_ACTOR_SYSTEM_WX_NOTIFY" class="quick-filters__note">
      这类行的「操作者」是系统账号 <code>{{ AUDIT_ACTOR_SYSTEM_WX_NOTIFY }}</code>（微信回调被拒时由服务端自己写下），
      <b>不是某个运营人员做了这件事</b>。
    </p>

    <div v-if="viewState === 'loading'" class="state-box">
      <div class="state-title">正在加载</div>
      <div class="state-desc">审计记录读取中…</div>
    </div>

    <div v-else-if="viewState === 'forbidden'" class="state-box">
      <div class="state-title">没有访问权限</div>
      <div class="state-desc">{{ errorText }}</div>
      <div class="state-desc">本页只读；如确需查审计，请核对运营账号的状态与授权。</div>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <div v-else-if="viewState === 'error'" class="state-box">
      <div class="state-title">读取失败</div>
      <div class="state-desc">{{ errorText }}</div>
      <!-- 400 / 404 / 429 各有自己的可读文案（后端 message 原文在上，这一行是「下一步该做什么」） -->
      <div v-if="codeHint" class="state-desc">{{ codeHint }}</div>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <template v-else>
      <!-- FE-G6-9 表头粘性（理由与 offset 实算见 Orders.vue 同名注释）：表体内部滚动、表头常驻 -->
      <el-table :data="rows" border stripe size="small" class="table" max-height="calc(100vh - 420px)">
        <!-- 空表 → 「暂无审计记录」，不是报错：后端空表回的是 {list:[],total:0}（F-20.10 实测不 500） -->
        <template #empty>
          <span class="empty">暂无审计记录</span>
        </template>
        <el-table-column prop="id" label="ID" width="90" />
        <el-table-column prop="actor" label="操作者" min-width="150" />
        <el-table-column prop="action" label="动作" min-width="150" />
        <el-table-column prop="targetType" label="目标类型" min-width="120" />
        <el-table-column prop="targetId" label="目标 ID" min-width="100" />
        <!-- createdAt 是服务端 DATE_FORMAT 定形的 "YYYY-MM-DD HH:mm:ss" 串，直接显示，不做二次格式化 -->
        <el-table-column prop="createdAt" label="时间" min-width="170" />
        <!-- FE-G6-2：detail 是服务端写侧动作的原始 JSON（含 image 路径那种长串），单行省略号显示，
             悬停由 show-overflow-tooltip 给**全文**（Element Plus 弹层渲染的是单元格的完整文本，
             不做任何按长度裁切丢字）。原先那两条单元格样式会把一行撑到 ~300px 高。 -->
        <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
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
  </div>
</template>

<script setup>
// 审计日志分页（F-20.11 Step 4 + F-20.16 Step 5a）：
// GET /api/admin/v2/audit-logs?pageNum=&pageSize=&actor=&action=&targetType=&targetId=
// 出参 {list,total,pageNum,pageSize,filters}；行内恰七键 id/actor/action/targetType/targetId/detail/createdAt。
// F-20.16 收 G-06 DoD ④：四个可选**精确等值**筛选（非模糊、不区分大小写），任一变化回第 1 页 + 沿用 FE-G6-3 在飞守卫。
// F-45 起筛选条下面还有一排「常用筛选」预设——它是那四个框的**快捷填法**，不是第二条链路
//   （预设只写 filters 既有的四个键 + 调既有 onFilterChange，见本段末尾 QUICK_FILTERS / applyQuickFilter）。
// F-50 起本页还能**带着 query 进来**（大盘那一格「支付回调拒收」点一下跳过来）：
//   收 query 走的仍是上面那一条路——只把 route.query 里同名的那四枚键写进既有 filters，再照常 load()，
//   零新状态变量、零第二个查询函数，applyQuickFilter / onFilterChange 一字未动（详见文件末尾 onMounted）。
// ⚠ 出参**没有 ip**（AdminAuditLogMapper 的 SELECT 连这一列都没查，RD 最小暴露）——
//   表格里不许凭空加这一列，也不许从别处凑。
// ⚠ pageSize 越界（0 / >100 / 负数）后端是 **400 而不是夹到边界**（F-20.10 追认），
//   所以可选页大小只能提供 [10,20,50,100]，不给任何越界选项。
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { getAdminAuditLogs, retryAfterText } from '../api'
// F-54 改动 B：actor / action 这两枚字符值的真值在服务端，定义收在 ../constants.js 一处（复述而非第二份真值）。
// 本页与大盘那一格的跳转 query 共用同一枚出口，服务端改名时两端只会有一处要改。
import { AUDIT_ACTOR_SYSTEM_WX_NOTIFY, AUDIT_ACTION_PAY_NOTIFY_REJECT } from '../constants'

const PAGE_SIZES = [10, 20, 50, 100]

// F-50：只用来读**进入本页时**那一份 query——不 watch、不回写 URL、不新增任何状态变量。
const route = useRoute()

// 初值 'loading' 只服务首屏渲染；首屏那一跳见文件末尾的 onMounted
const viewState = ref('loading')
const errorText = ref('')
const errorCode = ref(0)
// F-22 FE-429-2：429 信封 data 里的 retryAfterSeconds 走这一枚 ref 到 codeHint（其余码不转述秒数）
const errorData = ref(null)

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)

// F-20.16 生效筛选（页面这一份 filters 就是本页筛选态的唯一真源；后端出参 filters 只是回显，不再另建第二处）。
// 空串由请求层 withQuery 挡下、不下发 → 服务端按「该条件不生效」处理。
const filters = ref({ actor: '', action: '', targetType: '', targetId: '' })

// ==================== F-45「常用筛选」预设（纯入口，不是第二条查询链路） ====================
// 预设值取自 backEnd 里**真正会落 admin_audit_log** 的写入点，逐条实测（不是照 javadoc 举例造）：
//   grep -rnE "writeAudit\(|NOTIFY_REJECT_ACTOR|private static final String ACTION" backEnd/src/main/java --include=*.java
// 由该结果 + AdminAuthServiceImpl:152/321（AUDIT_ACTION_LOGIN='admin.login'、actor='admin_user:'+id）
// 与四处落库点（row.setActor：AdminServiceImpl:521 / AdminAuthServiceImpl:360 /
//  MerchantDishServiceImpl:433 / MerchantServiceImpl:249，全走同一个 AdminAuditLogMapper.insert）汇总，
// 会落库的 action 实测共 12 个：
//   Admin 轨 5 个（AdminServiceImpl:201/231/260/315/417）
//     order.status / dish.update / review.audit / merchant.create / store_apply.audit
//     ← actor = admin:<key 摘要 8>（静态密钥轨）或 admin_user:<id>（运营 JWT 轨，auditActor:626 归一）
//   系统 1 个（AdminServiceImpl:444）  pay.notify_reject  ← actor = system:wx-notify（F-44，没有人在场）
//   登录 1 个（AdminAuthServiceImpl:361）  admin.login     ← actor = admin_user:<id>
//   商家轨 6 个（MerchantDishServiceImpl:166/263/310/333、MerchantServiceImpl:166/175）
//     dish.create / dish.update / dish.stock / dish.onsale / merchant.profile / merchant.upload
//     ← actor = merchant:<userId>
// 选这四枚的理由（上限 4，按运营真要查的频率 + 单轨可追责性）：
//   1) pay.notify_reject —— 本片的存在理由，actor 固定，配 actor 一起填才筛得准；
//   2) admin.login —— 「谁登录过」，actor 是逐人变化的 admin_user:<id>，故只填 action、不填 actor；
//   3) order.status / 4) store_apply.audit —— 只有 AdminServiceImpl 一处写入点（单轨，action 即足够定责）。
// 没选 dish.update：它在 AdminServiceImpl:231 与 MerchantDishServiceImpl:263 各写一次，
// 单给一枚 action 会把两轨的行混在一起，反而造出「谁改了菜品」的误读（页首注释同一件事）。
// ⚠ 这四枚的字符全落在服务端 FILTER_PATTERN（^[A-Za-z0-9_.:-]{1,64}$，AdminV2ServiceImpl#FILTER_PATTERN）内，
//    且 audit-logs 面**没有值域白名单**（白名单只管 orders/reviews/dishes/store-applies 那几枚），
//    所以填进去是「按值等值过滤」而不是「白名单外 400」。
// F-54 改动 B：这里原先有一枚本页自己定义的 actor 字面量常量，连同下面那枚内联的 action，
// 再加上 Overview.vue 跳转 query 里的两份，同一件事在本端被写了四遍。四遍就是四份真值，
// 服务端改名时必有一处静默失配（失配的那边只表现为筛出 0 行，不报错）。
// 现在两枚值都从 ../constants.js 导入，本页不再有本地定义；导出名即服务端符号名的直译，
// 出处见那个文件的头注释（引用一律写符号名、不写行号——F-27/F-41 定案）。
const QUICK_FILTERS = [
  { label: '微信回调拒收（系统）', actor: AUDIT_ACTOR_SYSTEM_WX_NOTIFY, action: AUDIT_ACTION_PAY_NOTIFY_REJECT },
  { label: '运营登录', action: 'admin.login' },
  { label: '改订单状态', action: 'order.status' },
  { label: '门店入驻审核', action: 'store_apply.audit' }
]

// 各码的「下一步」文案：只给本层能确定的建议，不推断业务原因
// FE-G6-6：400 有**两个**来源（分页越界 / 筛选值脏形状），服务端只回一枚 400、不说明是哪一类，
// 所以这一条把两种因由**并列**写全，由运营自己逐项核对；前端不猜「到底是哪一个」（猜即编造），
// 也不按 filters 是否为空来分支文案。
const CODE_HINTS = {
  400: '请求参数不在允许范围内（分页与筛选两类都可能触发，服务端不说明是哪一类，请逐项核对）：'
    + '分页 —— 页码从 1 起，每页 1~100 条（服务端只验这个区间，本页只提供 10/20/50/100 四档）；'
    + '筛选值 —— 须为 1~64 个字符，且只含字母、数字、_ . : -，不模糊匹配、不带空格与通配符。',
  404: '审计查询接口没有可达（后端服务未起或路径已变），请确认后端在 3001 端口运行。',
  429: '读取过于频繁（服务端按来源 IP 限流 30 次/分），请稍候再翻页。'
}
// F-22 FE-429-2：只有 429 才把后端如实给的 retryAfterSeconds 接在后面（转述，非推断）；
// 400/404 与其余码**不拼**——「约 N 秒后可再试」对它们是假信息。缺载荷时 retryAfterText 回 ''，
// 本条文案与改动前逐字相同。
const codeHint = computed(() => (CODE_HINTS[errorCode.value] || '')
  + (errorCode.value === 429 ? retryAfterText(errorData.value) : ''))

async function load() {
  // FE-G6-3 在飞守卫：请求未回时不再发第二条（分页器 @current-change / @size-change、
  // 「重试」按钮同守这一条），否则乱序回包会把上一页的行画在下一页的高亮下。
  // 照 Login.vue:101 的同一精神，复用本页已有的单值 viewState，不新增第二个状态变量、不加定时器。
  if (viewState.value === 'loading') return
  viewState.value = 'loading'
  errorText.value = ''
  errorCode.value = 0
  errorData.value = null
  try {
    const envelope = await getAdminAuditLogs(pageNum.value, pageSize.value, filters.value)
    const data = envelope.data || {}
    // total 用后端值（独立 COUNT 的结果），绝不用本页行数推算——那会把「这一页几条」说成「一共几条」
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
    // 后端回显的生效页/页大小若与请求不同（本端点是原样回显），以回显为准，避免两处真值
    if (Number(data.pageNum) > 0) pageNum.value = Number(data.pageNum)
    if (PAGE_SIZES.indexOf(Number(data.pageSize)) !== -1) pageSize.value = Number(data.pageSize)
    viewState.value = 'ready'
  } catch (err) {
    const e = err || {}
    if (e.code === 401) {
      // 401 由请求层收敛（清 token + 跳 /login），这里只留一句过渡文案
      viewState.value = 'error'
      errorCode.value = 401
      errorText.value = '登录状态已失效，正在返回登录页…'
      return
    }
    if (e.code === 403) {
      // 403 与 401 分开：有效登录态 + 无权限 → 引导态留在本页
      viewState.value = 'forbidden'
      errorText.value = e.message || '无权限访问'
      return
    }
    viewState.value = 'error'
    errorCode.value = Number(e.code) || 0
    errorData.value = e.data || null
    errorText.value = e.message || '审计记录读取失败，请稍后重试'
  }
}

function onPageChange() {
  load()
}

function onSizeChange() {
  // 换每页条数后回到第 1 页：原页码在新页大小下很可能已经越界（后端不回 400，而是回空 list，
  // 那会让运营以为「这一档没有数据」）
  pageNum.value = 1
  load()
}

function onFilterChange() {
  // 任一筛选变化必须回第 1 页（与 onSizeChange 同因：原页码在新筛选条件下很可能越界，
  // 后端不回 400 而是回空 list，会被误读成「这一档没数据」）。load() 自带 FE-G6-3 在飞守卫。
  pageNum.value = 1
  load()
}

function onReset() {
  // 重置 = 清空四个筛选 + 回第 1 页 + 重查（回到无筛选全量）；逐字段清空而非整体替换，
  // 保持 filters 这一份引用与 v-model 绑定不变。
  filters.value.actor = ''
  filters.value.action = ''
  filters.value.targetType = ''
  filters.value.targetId = ''
  pageNum.value = 1
  load()
}

// F-45 预设按钮唯一的 handler：只做「把预设值写进既有那四个 filters 键」+ 调既有的 onFilterChange()。
// 不发第二条请求、不新增请求参数、不碰 total/pageNum 的取值口径（回第 1 页由 onFilterChange 负责），
// 所以预设与手填走的是**逐字同一条**查询路径（同一个 load、同一个在飞守卫、同一份 filters 真源）。
// 逐字段赋值（照 onReset 同姿势）：保住 filters 这一份引用与四个 v-model 绑定不变。
// 预设没给的字段一律清空——不清就会留着上一枚的 actor 与这一枚的 action 拼成一条谁也对不上的组合
// （例如先点「微信回调拒收」再点「运营登录」，残留的 system:wx-notify 会让后者查出 0 行）。
// 空串由请求层 withQuery 挡下、不下发，等价于「这一枚条件不生效」。
function applyQuickFilter(preset) {
  filters.value.actor = preset.actor || ''
  filters.value.action = preset.action || ''
  filters.value.targetType = preset.targetType || ''
  filters.value.targetId = preset.targetId || ''
  onFilterChange()
}

onMounted(() => {
  // 首屏这一行是必须的：viewState 初值 'loading' 只用于首屏渲染加载态占位，
  // 若原样进 load() 会被在飞守卫当成「已在飞」而永久早退（一次请求都不发、页面卡在「正在加载」）。
  // 落到 'idle' 后同一同步 tick 内 load() 立刻置回 'loading'，浏览器不会在两个值之间绘制。
  viewState.value = 'idle'
  // F-50（D-50-6）：带 query 进来时（大盘那一格跳过来就是这种）先把值填进**既有那四个 filters 键**，
  // 再照常 load()——预设、手填、外链从此是同一条查询路径（同一个 load、同一个在飞守卫、同一份 filters 真源）。
  // 只认 actor/action/targetType/targetId 这四枚同名键，其余 query 一律忽略（绝不因此长出第五份筛选态）。
  // 逐字段赋值（照 onReset / applyQuickFilter 同姿势）：保住 filters 这一份引用与四个 v-model 绑定不变。
  // 值必须是字符串，否则按「未传」处理成空串（?actor=a&actor=b 这类重复键 vue-router 回的是数组，
  // 数组既画不进输入框、也会在服务端 FILTER_PATTERN 上吃 400，所以在这儿就拦掉）。
  // 无 query 时这四行写的就是初值本身（'' → ''），本页行为与改动前逐字相同。
  const q = route.query || {}
  filters.value.actor = typeof q.actor === 'string' ? q.actor : ''
  filters.value.action = typeof q.action === 'string' ? q.action : ''
  filters.value.targetType = typeof q.targetType === 'string' ? q.targetType : ''
  filters.value.targetId = typeof q.targetId === 'string' ? q.targetId : ''
  load()
})
</script>

<style scoped>
/* F-20.16 筛选工具条：四个精确等值输入 + 查询/重置，窄屏自动换行，作用域内不污染全局 */
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.filter-input {
  width: 220px;
}
/* F-45「常用筛选」预设行：紧贴工具条下方，读成「辅助排」而不是第五个输入框——
   12px 的引导标签 + 与上方输入框同族的 small 按钮，左侧一道细竖线把它与手填区划开但不再画框。
   flex + gap 同 .toolbar，窄屏自动换行；整排零定位，绝不压到表格。 */
.quick-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding-left: 10px;
  border-left: 2px solid #ebeef5;
}
.quick-filters__label {
  color: #909399;
  font-size: 12px;
  letter-spacing: 1px;
}
/* F-45 系统事件那一句说明：短、普通文档流一到两行，靠左侧一道 warning 色竖线归到预设那一排。
   颜色比正文低一档但刻意不淡（#7a5a12 配 #e6a23c 那道线）——这句话是防误读的，
   淡到看不见就等于没写，而「有人做了这件事」的误读正是本条要挡住的。 */
.quick-filters__note {
  margin: 8px 0 0;
  padding-left: 10px;
  border-left: 2px solid #e6a23c;
  color: #7a5a12;
  font-size: 12px;
  line-height: 1.6;
}
.quick-filters__note code {
  padding: 0 4px;
  background: #f5f7fa;
  border-radius: 2px;
  color: #606266;
  font-size: 12px;
}
.table {
  background: #fff;
  margin-top: 16px;
}
.empty {
  color: #909399;
  font-size: 13px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
/* FE-G6-2：详情列原有的两条单元格样式（任意位置断词 + 保留原始换行符）已整条删除——
   它们让长 JSON 按内容里的换行一路撑高（单行实测 ~300px，20 行的表要滚三屏），
   现在由列上的 show-overflow-tooltip 承担「单行省略 + 悬停看全文」。 */
</style>
