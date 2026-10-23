<template>
  <div class="page">
    <!-- 读写可辨：approve 会真的开通商家（建 merchant + merchant_profile 并绑定申请人），所以常驻「本页会改数据」。 -->
    <div class="page-head">
      <h2 class="page-title">商家入驻审核</h2>
      <el-tag type="danger" effect="dark" size="small" class="mode-badge">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      商家自助入驻申请（merchant_apply）的审核队列：列表源 GET /api/admin/merchant-applies，唯一筛选 status 为
      pending / approved / rejected 三态（空 = 全部），本页默认停在「待审核」；分页参数是 pageNum / pageSize（1~100，缺省 1/20）。<br />
      申请人手机号由服务端脱敏（形如 138****1234），本页原样显示、不拼回明文；提交时间是服务端定形串，原样显示。<br />
      审核走 PUT /api/admin/merchant-applies/{id}/audit：通过（approve）会在同一事务里开通商家并绑定申请人账号；
      驳回（reject）只置为已驳回并留下审核意见，<b>审核意见必填</b>。已审核的申请再审是幂等成功，不会重复开通。
    </p>

    <div class="toolbar">
      <!-- 默认「待审核」：运营打开这页先看要处理的。四态都留着，切换即从第 1 页重查。 -->
      <el-radio-group v-model="statusFilter" size="small" class="filter-group"
                      :disabled="loading || submitting" @change="onStatusChange">
        <el-radio-button v-for="opt in STATUS_OPTIONS" :key="opt.value" :value="opt.value">
          {{ opt.label }}
        </el-radio-button>
      </el-radio-group>
      <el-button size="small" :disabled="loading || submitting" @click="load">刷新</el-button>
      <!-- 代开通与自助申请并存：一期只有这条路（运营拿到手机号直接开），二期起两条都在。
           它不产生 merchant_apply 行，所以提交后列表不会多一行，这是预期。 -->
      <el-button size="small" type="primary" plain :disabled="submitting" @click="openCreate">代开通商家</el-button>
    </div>

    <div v-if="loading" class="state-box">
      <div class="state-title">正在加载</div>
      <div class="state-desc">商家入驻申请列表读取中…</div>
    </div>

    <div v-else-if="loadError" class="state-box">
      <div class="state-title">读取失败</div>
      <!-- 只展示请求层透出的后端原文（见 api/index.js 的 handleEnvelope），本页不补猜原因的文案。 -->
      <div class="state-desc">{{ loadError }}</div>
      <el-button size="small" @click="load">重试</el-button>
    </div>

    <template v-else>
      <el-table :data="rows" border stripe size="small" class="table" max-height="calc(100vh - 420px)">
        <template #empty>
          <span class="empty">暂无商家入驻申请（当前筛选下没有命中）</span>
        </template>
        <el-table-column prop="merchantName" label="商家名称" min-width="180" show-overflow-tooltip />
        <!-- applicantPhone 是服务端 PhoneMask 的脱敏值，原样渲染：不截断、不拼接、不 Number()。 -->
        <el-table-column label="申请人手机号（已脱敏）" min-width="150">
          <template #default="{ row }"><span :class="{ muted: !row.applicantPhone }">{{ row.applicantPhone }}</span></template>
        </el-table-column>
        <el-table-column label="联系人" min-width="120" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.contactName }">{{ row.contactName }}</span></template>
        </el-table-column>
        <el-table-column label="法人" min-width="120" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.legalPerson }">{{ row.legalPerson }}</span></template>
        </el-table-column>
        <el-table-column label="经营地址" min-width="240" show-overflow-tooltip>
          <template #default="{ row }"><span :class="{ muted: !row.address }">{{ row.address }}</span></template>
        </el-table-column>
        <!-- F-107 多证照：licenses 是后端原样下发的 JSON 字符串，解析容错在 script 的 parseLicenses；
             有数据时点张数开明细弹窗，无数据/坏数据一律「—」，绝不让表格整行渲染失败。 -->
        <el-table-column label="证照" width="96" align="center">
          <template #default="{ row }">
            <el-button v-if="licenseItemsOf(row).length" link type="primary" size="small"
                       @click="openLicenses(row)">{{ licenseItemsOf(row).length }} 张</el-button>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" effect="light">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="提交时间" min-width="160" />
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <!-- 只有 pending 行摆动作：已审核的行服务端只会幂等早退，摆按钮等于摆一个什么都改不了的入口。 -->
            <template v-if="row.status === 'pending'">
              <el-button size="small" type="success" plain
                         :disabled="loading || submitting" @click="onApprove(row)">通过</el-button>
              <el-button size="small" type="danger" plain
                         :disabled="loading || submitting" @click="openReject(row)">驳回</el-button>
            </template>
            <span v-else class="muted">已审核</span>
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

    <!-- 驳回弹窗放在状态块之外：提交后列表进 loading、表格被卸载，弹窗若在 v-else 里会连带关掉。 -->
    <el-dialog v-model="rejectVisible" title="驳回入驻申请" width="560px" :close-on-click-modal="false">
      <div class="dialog-target">
        申请 #{{ rejectRow.id }}「{{ rejectRow.merchantName }}」，申请人手机号：{{ rejectRow.applicantPhone }}（服务端脱敏值）
      </div>
      <!-- 盲审防护：驳回前先看证照（只读；坏数据由 parseLicenses 降级为空，统一显示「—」）。 -->
      <div class="license-review">
        <div class="license-review-title">证照（{{ rejectLicenses.length }} 张）</div>
        <div v-if="rejectLicenses.length" class="license-grid">
          <div v-for="(item, index) in rejectLicenses" :key="index" class="license-item">
            <el-image class="license-thumb" :src="item.fullUrl" :preview-src-list="rejectLicenseUrls"
                      :initial-index="index" fit="cover" preview-teleported>
              <template #error><div class="license-img-fallback">加载失败</div></template>
            </el-image>
            <span class="license-type">{{ item.typeLabel }}</span>
          </div>
        </div>
        <div v-else class="license-empty muted">—</div>
      </div>
      <el-form class="dialog-form" label-width="90px" @submit.prevent="onConfirmReject">
        <el-form-item label="审核意见">
          <el-input v-model="rejectRemark" type="textarea" :rows="3" clearable
                    placeholder="驳回时必填，会随审核结果展示给申请人" />
        </el-form-item>
      </el-form>
      <p class="dialog-note">
        本页与后端都会做非空校验；超过 255 字由服务端回 400，原文照实显示。
        驳回不删除申请行，申请人之后可以重新提交一条新申请。
      </p>
      <template #footer>
        <el-button size="small" :disabled="submitting" @click="rejectVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="danger" :loading="submitting" @click="onConfirmReject">确认驳回</el-button>
      </template>
    </el-dialog>

    <!-- 证照明细（只读）：点表格「N 张」打开；图片经请求层 resolveFileUrl 补 BASE_URL，点击可放大。 -->
    <el-dialog v-model="licensesVisible" title="证照明细" width="560px">
      <div class="dialog-target">
        申请 #{{ licenseRow.id }}「{{ licenseRow.merchantName }}」，共 {{ licenseDetailItems.length }} 张
      </div>
      <div class="license-grid">
        <div v-for="(item, index) in licenseDetailItems" :key="index" class="license-item">
          <el-image class="license-thumb" :src="item.fullUrl" :preview-src-list="licenseDetailUrls"
                    :initial-index="index" fit="cover" preview-teleported>
            <template #error><div class="license-img-fallback">加载失败</div></template>
          </el-image>
          <span class="license-type">{{ item.typeLabel }}</span>
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="licensesVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 平台代开通（一期唯一路径，二期保留）：不经过 merchant_apply，直接按手机号开通并绑定已注册用户。 -->
    <el-dialog v-model="createVisible" title="平台代开通商家" width="520px" :close-on-click-modal="false">
      <p class="dialog-note">
        按手机号开通：该号必须<b>已注册</b>（让商家先用验证码登录一次），且名下<b>没有</b>商家。
        开通会在同一事务里建商家主体、写入已通过的资质记录并绑定该账号，所以提交后
        <b>不会</b>在本页列表里多出一条申请——本页只显示自助申请队列。
      </p>
      <el-form class="dialog-form" label-width="90px" @submit.prevent="onSubmitCreate">
        <el-form-item label="商家名称">
          <el-input v-model="createForm.name" maxlength="100" show-word-limit clearable placeholder="必填" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="createForm.phone" maxlength="20" clearable placeholder="必填，须为已注册账号" />
        </el-form-item>
        <el-form-item label="联系人">
          <el-input v-model="createForm.contactName" maxlength="50" clearable placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" :disabled="createSubmitting" @click="createVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="primary" :loading="createSubmitting" @click="onSubmitCreate">确认开通</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 商家入驻审核（F-106 片 C）：结构与交互照 StoreApplies.vue（同一类审核页）。
//   列表 = GET /api/admin/merchant-applies（片 B2 补的运营端读端点；status/pageNum/pageSize 三参）
//   写   = PUT /api/admin/merchant-applies/{id}/audit，body {action:'approve'|'reject', remark?}
//          （AdminController 的 Map<String,String> 端点，请求层在 remark 为空时整个键不下发）
// 与门店申请审核的差别（后端语义，本页不自行加判定）：
//   reject 的 remark 是**必填**（空 → 400「驳回时必须填写审核意见」），所以驳回走弹窗填意见；
//   已审核（approved/rejected）再审是**幂等成功**（不像 store_apply 那样回「不可再次审核」）。
// 错误一律只呈现请求层透出的后端 message（ElMessage），本页不预置猜原因的提示表。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMerchantApplies, auditMerchantApply, createAdminMerchant, resolveFileUrl } from '../api'

const PAGE_SIZES = [10, 20, 50, 100]

// 与后端 MERCHANT_APPLY_LIST_STATUSES 同集合；空串 = 全部（withQuery 会挡下，不下发 status 参数）。
const STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '待审核', value: 'pending' },
  { label: '已通过', value: 'approved' },
  { label: '已驳回', value: 'rejected' }
]
const STATUS_TEXT = { pending: '待审核', approved: '已通过', rejected: '已驳回' }
const statusText = (v) => STATUS_TEXT[v] || (v == null ? '' : String(v))
const statusTagType = (v) => (v === 'pending' ? 'warning' : v === 'approved' ? 'success' : 'info')

// F-107 多证照（只读展示）：中文名只在本常量维护一份，后端只存英文 key；未知 type 一律落「其他」。
const LICENSE_TYPES = {
  business_license: '营业执照',
  food_business_license: '食品经营许可证',
  food_production_license: '食品生产许可证',
  other: '其他'
}
// 与后端校验上限同值：只用于挡脏数据把明细撑爆，不新增业务规则。
const MAX_LICENSES = 6

function licenseTypeLabelOf(type) {
  // 只认自有键：防 type 撞上 Object 原型属性（如 constructor）时渲染出函数。
  return Object.prototype.hasOwnProperty.call(LICENSE_TYPES, type) ? LICENSE_TYPES[type] : LICENSE_TYPES.other
}

// licenses 是 JSON 数组的字符串（老数据 null / 空串）。try/catch + Array.isArray 双重容错：
// 坏 JSON / 非数组 / 非对象条目一律跳过，最坏也只让本列显示「—」，不影响表格其余列。
function parseLicenses(raw) {
  if (!raw) return []
  let list = null
  try {
    list = typeof raw === 'string' ? JSON.parse(raw) : raw
  } catch (e) {
    return []
  }
  if (!Array.isArray(list)) return []
  const items = []
  for (let i = 0; i < list.length && items.length < MAX_LICENSES; i++) {
    const item = list[i]
    if (!item || typeof item !== 'object' || typeof item.url !== 'string' || !item.url) continue
    items.push({ typeLabel: licenseTypeLabelOf(item.type), fullUrl: resolveFileUrl(item.url) })
  }
  return items
}

function licenseItemsOf(row) {
  return row ? parseLicenses(row.licenses) : []
}

const loading = ref(false)
const loadError = ref('')
const submitting = ref(false)

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
// 默认「待审核」：运营进页先看要处理的单子。
const statusFilter = ref('pending')

const rejectVisible = ref(false)
const rejectRow = ref({})
const rejectRemark = ref('')
const rejectLicenses = computed(() => parseLicenses(rejectRow.value.licenses))
const rejectLicenseUrls = computed(() => rejectLicenses.value.map((item) => item.fullUrl))

// 证照明细（只读弹窗）：与驳回弹窗共用 parseLicenses，打开时才绑定行。
const licensesVisible = ref(false)
const licenseRow = ref({})
const licenseDetailItems = computed(() => parseLicenses(licenseRow.value.licenses))
const licenseDetailUrls = computed(() => licenseDetailItems.value.map((item) => item.fullUrl))

// 代开通（平台按手机号直接开）：与审核互斥地用自己的提交态，避免一个弹窗在转时把另一个的按钮也禁掉。
const createVisible = ref(false)
const createSubmitting = ref(false)
const createForm = ref({ name: '', phone: '', contactName: '' })

function openCreate() {
  createForm.value = { name: '', phone: '', contactName: '' }
  createVisible.value = true
}

async function onSubmitCreate() {
  if (createSubmitting.value) return
  const name = String(createForm.value.name || '').trim()
  const phone = String(createForm.value.phone || '').trim()
  const contactName = String(createForm.value.contactName || '').trim()
  // 前端只挡"明显填错"，业务判定（未注册/已绑定）一律交给后端，文案也用它给的。
  if (!name) { ElMessage.warning('请填写商家名称'); return }
  if (!/^1[3-9]\d{9}$/.test(phone)) { ElMessage.warning('请填写正确的手机号'); return }
  createSubmitting.value = true
  try {
    const envelope = await createAdminMerchant({ name, phone, contactName })
    ElMessage.success(envelope.message || '商家已开通')
    createVisible.value = false
    await load()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    createSubmitting.value = false
  }
}

async function load() {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const envelope = await listMerchantApplies(pageNum.value, pageSize.value, { status: statusFilter.value })
    const data = envelope.data || {}
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
    // 服务端会回显真正生效的 pageNum / pageSize（不夹取）；与本地不一致时以服务端为准。
    if (Number(data.pageNum) > 0) pageNum.value = Number(data.pageNum)
    if (PAGE_SIZES.indexOf(Number(data.pageSize)) !== -1) pageSize.value = Number(data.pageSize)
  } catch (err) {
    const e = err || {}
    rows.value = []
    total.value = 0
    loadError.value = e.message || ''
    // 401 由请求层统一清态并跳登录页（api/index.js 的 handleEnvelope），这里只把后端（或兜底）原文念出来。
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function onStatusChange() {
  pageNum.value = 1
  load()
}

function onPageChange() {
  load()
}

function onSizeChange() {
  pageNum.value = 1
  load()
}

function approveTextOf(row) {
  return '确认通过申请 #' + row.id + '「' + row.merchantName + '」？\n\n'
    + '· 服务端会在同一事务里：新建商家与商家资质档案（审核态 approved），并把申请人账号绑定为该商家的商家账号。\n'
    + '· 申请会置为 approved 并记录审核时间；本页没有撤销入口（已审核的再审是幂等成功，不会重复开通）。\n'
    + '· 提交成功后本页重拉当前页列表，不在本地改行。'
}

async function onApprove(row) {
  if (submitting.value || loading.value) return
  try {
    await ElMessageBox.confirm(approveTextOf(row), '请确认这笔审核', {
      type: 'warning',
      confirmButtonText: '确认通过',
      cancelButtonText: '取消（不发出任何请求）',
      closeOnClickModal: false,
      distinguishCancelAndClose: true,
      customClass: 'admin-write-confirm'
    })
  } catch (e) {
    // 点「取消」/关闭/Esc 一律 reject → 吞掉，不发出任何请求。
    return
  }
  await runAudit(row, 'approve', '')
}

function openReject(row) {
  if (submitting.value || loading.value) return
  rejectRow.value = row
  rejectRemark.value = ''
  rejectVisible.value = true
}

// 只读明细，不受提交态约束（打开期间列表不会变；关掉即走）。
function openLicenses(row) {
  licenseRow.value = row
  licensesVisible.value = true
}

async function onConfirmReject() {
  if (submitting.value) return
  const remark = String(rejectRemark.value == null ? '' : rejectRemark.value).trim()
  // 前端先挡一次空值（后端同样会回 400「驳回时必须填写审核意见」，这里只是不白跑一趟）。提示语是表单指引，不是后端文案。
  if (remark === '') {
    ElMessage.warning('请填写审核意见（驳回时必填）')
    return
  }
  const ok = await runAudit(rejectRow.value, 'reject', remark)
  if (ok) rejectVisible.value = false
}

/**
 * 统一的写路径：成功只念后端 message，失败只念请求层透出的 message（两者都是既有文案，本页不加解释）。
 * 成功与否一律以重拉列表为准，绝不本地改行。
 */
async function runAudit(row, action, remark) {
  submitting.value = true
  try {
    const envelope = await auditMerchantApply(row.id, action, remark)
    if (envelope && envelope.message) ElMessage.success(envelope.message)
    await load()
    return true
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
    return false
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  load()
})
</script>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.filter-group {
  margin-right: 4px;
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
.dialog-target {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #f5f6f8;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
}
.dialog-form :deep(.el-form-item) {
  margin-bottom: 8px;
}
.dialog-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
}
/* F-107 多证照：明细弹窗与驳回弹窗共用同一套缩略图网格 */
.license-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.license-item {
  width: 148px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.license-thumb {
  width: 148px;
  height: 104px;
  border-radius: 6px;
  background: #f5f6f8;
  cursor: zoom-in;
}
.license-img-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  color: #909399;
  background: #f5f6f8;
}
.license-type {
  font-size: 12px;
  line-height: 1.4;
  color: #606266;
  text-align: center;
}
.license-review {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #f5f6f8;
  border-radius: 6px;
}
.license-review-title {
  margin-bottom: 8px;
  font-size: 12px;
  color: #606266;
}
.license-empty {
  font-size: 12px;
}
</style>
