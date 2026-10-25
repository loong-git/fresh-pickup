<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">结算中心</h2>
      <el-tag type="danger" effect="dark" size="small">本页会改数据</el-tag>
    </div>
    <!-- F-118 §2：记账态恒显条——打款通道未接入（轨道 B），所有金额为平台-商家核算口径。
         paidChannelAbsent 由接口层带出（恒 true），页面恒显不依赖它，接口键缺失时也照样显示。 -->
    <div class="accounting-banner">
      记账态：打款通道未接入（轨道 B），所有金额为平台-商家核算口径。
      账单只增不改（无作废/重开），冲销在下一账期 refund 列负向计入。
    </div>
    <p class="page-note">
      列表读 GET /api/admin/settlements（十四键行，含实时对账标志 reconcileOk）；生成走 POST /api/admin/settlements/generate；明细弹窗读 GET /api/admin/settlements/{id}/items。<br />
      <b>对账 ✗ = 数据事故</b>（理论上不可达：金额或行数与挂锚明细对不上），行整行标红，出现即立即上报，不要自行处理数据。<br />
      缺货明细不结算（fulfill_status=shortage 排除）；未设供货价（supply_price 为空）与未支付单（pay_status≠1）不进聚合。
    </p>

    <div class="toolbar">
      <el-button size="small" type="primary" :disabled="submitting" @click="openGenerate">生成账期</el-button>
      <el-select v-model="filterMerchantId" clearable filterable placeholder="按商家筛（可空=全部）" style="width: 220px" @change="loadSettlements">
        <el-option v-for="m in merchantOptions" :key="m.value" :label="m.label" :value="m.value" />
      </el-select>
      <el-button size="small" :loading="loading" :disabled="submitting" @click="loadSettlements">刷新</el-button>
      <span class="toolbar-hint">共 {{ settlements.length }} 单；按 id 倒序（新账期在前）。</span>
    </div>

    <section class="panel">
      <div v-if="loading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">账期列表读取中…</div>
      </div>
      <div v-else-if="error" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ error }}</div>
        <el-button size="small" @click="loadSettlements">重试</el-button>
      </div>
      <template v-else>
        <!-- 对账 ✗ 行整行标红（数据事故态，理论上不可达） -->
        <el-table :data="settlements" border stripe size="small" max-height="calc(100vh - 380px)"
                  :row-class-name="reconcileRowClass">
          <template #empty>
            <span class="muted">暂无账期（首次生成前此列表为空，属正常态）</span>
          </template>
          <el-table-column label="账期" width="200">
            <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
          </el-table-column>
          <el-table-column label="商家" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.merchantName || ('#' + row.merchantId) }}</template>
          </el-table-column>
          <el-table-column label="账单号" min-width="150" show-overflow-tooltip>
            <template #default="{ row }"><span class="muted">{{ row.settleNo }}</span></template>
          </el-table-column>
          <el-table-column label="gross" width="90" align="right">
            <template #default="{ row }">{{ row.gross }}</template>
          </el-table-column>
          <el-table-column label="refund" width="90" align="right">
            <template #default="{ row }">{{ row.refund }}</template>
          </el-table-column>
          <el-table-column label="net" width="90" align="right">
            <template #default="{ row }"><b>{{ row.net }}</b></template>
          </el-table-column>
          <el-table-column label="明细数" width="80" align="right">
            <template #default="{ row }">{{ row.itemCount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag type="success" size="small">{{ row.status === 'accounted' ? '记账态' : row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对账" width="80" align="center">
            <template #default="{ row }">
              <span v-if="row.reconcileOk" class="reconcile-ok" title="金额与明细数和挂锚明细实时对得上">✓</span>
              <span v-else class="reconcile-bad" title="对不上即数据事故：立即上报，不要自行处理数据">✗</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :disabled="submitting" @click="openItems(row)">明细</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </section>

    <!-- 生成账期弹窗 -->
    <el-dialog v-model="generateVisible" title="生成账期" width="520px" :close-on-click-modal="false">
      <el-form label-width="100px" @submit.prevent="onGenerate">
        <el-form-item label="商家">
          <!-- 数据源=列表已出账商家去重 + allow-create 手输 merchantId（契约 §1 只授权三端点，
               无商家清单端点；首次对某商家出账直接输入其 merchantId 回车即成一项） -->
          <el-select v-model="form.merchantId" filterable allow-create default-first-option
                     placeholder="选已出账商家或输入 merchantId" style="width: 100%">
            <el-option v-for="m in merchantOptions" :key="m.value" :label="m.label" :value="m.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="账期起">
          <el-date-picker v-model="form.periodStart" type="date" value-format="YYYY-MM-DD"
                          placeholder="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="账期止">
          <el-date-picker v-model="form.periodEnd" type="date" value-format="YYYY-MM-DD"
                          placeholder="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <div class="field-warn">
          聚合口径：该商家账期内「已支付 · 非缺货 · 已设供货价」的未结算明细；gross=Σ(供货价×数量)。
          空期不会生成（400）；同期不可重出（uk 拦截）。账单只增不改。
        </div>
      </el-form>
      <p class="dialog-note">
        校验与后端同款（merchant 存在、start&lt;end、同期不重），后端为准；成功后重拉列表核对，不本地改行；
        失败原因原样取自服务端 message。
      </p>
      <template #footer>
        <el-button size="small" :disabled="submitting" @click="generateVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="onGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 明细弹窗 -->
    <el-dialog v-model="itemsVisible" :title="itemsTitle" width="760px" :close-on-click-modal="false">
      <div v-if="itemsLoading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">账单明细读取中…</div>
      </div>
      <div v-else-if="itemsError" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ itemsError }}</div>
        <el-button size="small" @click="openItems(itemsRow)">重试</el-button>
      </div>
      <template v-else>
        <!-- 孤儿行（挂锚明细的 orders 已删，orphan:true）整行标红警示——数据事故态（2026-10-25 验收裁决） -->
        <el-table :data="items" border stripe size="small" max-height="420px" :row-class-name="itemRowClass">
          <template #empty>
            <span class="muted">该账单暂无挂锚明细（对账异常态，请上报）</span>
          </template>
          <el-table-column label="订单号" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.orderId }}
              <el-tag v-if="row.orphan" type="danger" effect="dark" size="small" class="orphan-tag">订单已不存在</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="菜品" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.dishName }}</template>
          </el-table-column>
          <el-table-column label="供货价" width="90" align="right">
            <template #default="{ row }">{{ row.supplyPrice }}</template>
          </el-table-column>
          <el-table-column label="数量" width="70" align="right">
            <template #default="{ row }">{{ row.qty }}</template>
          </el-table-column>
          <el-table-column label="小计" width="90" align="right">
            <template #default="{ row }">{{ row.subtotal }}</template>
          </el-table-column>
          <el-table-column label="支付" width="110" align="center">
            <template #default="{ row }">
              <span v-if="row.orphan" class="orphan-cell">订单已不存在</span>
              <span v-else>{{ row.payStatus === 1 ? '已支付' : row.payStatus === 2 ? '已退款' : '未支付' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="履约" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.fulfillStatus === 'shortage' ? 'danger' : 'success'" size="small">
                {{ row.fulfillStatus === 'shortage' ? '缺货' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <p class="dialog-note">
          行 = 该账单挂锚的订单明细快照（下单时 dish_name/供货价快照，不随改品漂移），只读；
          「订单已不存在」= 挂锚明细对应的 orders 行已被删（孤儿态，数据事故——对账标志会同步打 ✗），立即上报不要自行处理。
        </p>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 结算中心（F-118 §2）：结构与错误处理照 Stores.vue / CouponTemplates.vue（同组菜单 F-113/F-114 模式）。
//   读   = GET /api/admin/settlements（十四键行，id 倒序；reconcileOk 实时对账标志）
//   写   = POST /api/admin/settlements/generate（商家+起止日期；二次确认含聚合口径）
//   明细 = GET /api/admin/settlements/{id}/items（挂锚明细快照，只读）
// 纪律（契约 §2/§3）：
//   1) 顶部记账态横幅恒显（打款通道未接入轨道 B），paidChannelAbsent 是接口明示键、页面不依赖它显隐；
//   2) 对账 ✗ 行整行标红——语义是「数据事故，理论上不可达」，页面只上报不提供任何修复入口；
//   3) 无 PUT/DELETE（settlement 只增不改，冲销走下一期 refund 列）；
//   4) 生成弹窗二次确认文案说明聚合口径（已支付·非缺货·已设供货价）；
//   5) 生成成功后重拉列表，绝不本地改行；错误一律 ElMessage + 后端 message 原文。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminSettlements, generateAdminSettlement, getAdminSettlementItems } from '../api'

const settlements = ref([])
const loading = ref(false)
const error = ref('')
const submitting = ref(false)
const filterMerchantId = ref(null)

const generateVisible = ref(false)
const form = ref({ merchantId: null, periodStart: '', periodEnd: '' })

const itemsVisible = ref(false)
const itemsLoading = ref(false)
const itemsError = ref('')
const itemsRow = ref(null)
const items = ref([])
const itemsTitle = computed(() => (itemsRow.value ? '账单明细 · ' + itemsRow.value.settleNo : '账单明细'))

// 商家下拉选项：列表已出账商家去重（value 存字符串——el-select allow-create 产出的手输值是字符串，
// 统一字符串键，发请求时再转数字）
const merchantOptions = computed(() => {
  const seen = new Map()
  settlements.value.forEach((row) => {
    if (row.merchantId != null && !seen.has(String(row.merchantId))) {
      seen.set(String(row.merchantId), row.merchantName ? row.merchantName + ' (#' + row.merchantId + ')' : '商家 #' + row.merchantId)
    }
  })
  return Array.from(seen, ([value, label]) => ({ value, label }))
})

async function loadSettlements() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const envelope = await getAdminSettlements(filterMerchantId.value)
    settlements.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    settlements.value = []
    error.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

// 对账 ✗ 行整行标红（row-class-name）；✓ 行不额外着色
function reconcileRowClass({ row }) {
  return row.reconcileOk === false ? 'settle-broken' : ''
}

// 明细弹窗孤儿行（orders 已删）整行标红（2026-10-25 验收裁决：不再显示成空的"异常态"）
function itemRowClass({ row }) {
  return row.orphan ? 'item-orphan' : ''
}

function openGenerate() {
  form.value = { merchantId: null, periodStart: '', periodEnd: '' }
  generateVisible.value = true
}

// 生成前校验（与后端同款形状）：merchantId 必填、两日期齐全
function validateForm() {
  const f = form.value
  const merchantId = Number(f.merchantId)
  if (!Number.isInteger(merchantId) || merchantId <= 0) {
    ElMessage.warning('商家必填：选已出账商家或输入 merchantId')
    return null
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(f.periodStart || '')) || !/^\d{4}-\d{2}-\d{2}$/.test(String(f.periodEnd || ''))) {
    ElMessage.warning('账期起止都必填（YYYY-MM-DD）')
    return null
  }
  if (String(f.periodStart) >= String(f.periodEnd)) {
    ElMessage.warning('账期起须早于账期止')
    return null
  }
  return { merchantId, periodStart: String(f.periodStart), periodEnd: String(f.periodEnd) }
}

async function onGenerate() {
  if (submitting.value) return
  const parsed = validateForm()
  if (parsed === null) return
  // 二次确认（契约 §2）：说明聚合口径与「只增不改」后果
  try {
    await ElMessageBox.confirm(
      '确认为商家 #' + parsed.merchantId + ' 生成账期 ' + parsed.periodStart + ' ~ ' + parsed.periodEnd + ' 吗？\n'
      + '聚合口径：该商家本期「已支付 · 非缺货 · 已设供货价」的未结算明细，gross=Σ(供货价×数量)。\n'
      + '账单只增不改：生成后无作废/重开入口，冲销在下一账期 refund 列负向计入；同期不可重出。',
      '生成账期',
      {
        type: 'warning',
        confirmButtonText: '生成',
        cancelButtonText: '取消',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    return // 取消确认：不发出任何请求
  }
  submitting.value = true
  try {
    const envelope = await generateAdminSettlement(parsed)
    ElMessage.success((envelope && envelope.message) || '账期已生成')
    generateVisible.value = false
    await loadSettlements()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

async function openItems(row) {
  if (!row) return
  itemsRow.value = row
  items.value = []
  itemsError.value = ''
  itemsVisible.value = true
  itemsLoading.value = true
  try {
    const envelope = await getAdminSettlementItems(row.id)
    items.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    items.value = []
    itemsError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    itemsLoading.value = false
  }
}

onMounted(() => {
  loadSettlements()
})
</script>

<style scoped>
.accounting-banner {
  margin-top: 12px;
  padding: 10px 14px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  color: #b88230;
  font-size: 13px;
  line-height: 1.6;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin-top: 14px;
}
.toolbar-hint {
  font-size: 12px;
  color: #909399;
}
.panel {
  margin-top: 14px;
  padding: 16px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.reconcile-ok {
  color: #67c23a;
  font-weight: 600;
}
.reconcile-bad {
  color: #f56c6c;
  font-weight: 700;
}
.field-warn {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #e6a23c;
}
.dialog-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
/* 对账 ✗ 数据事故行：整行标红（全局样式作用点，scoped 对 row-class-name 生成的行类不可达，
   故走 :deep 穿透——与表格行内 el-tag 的既有用法同一机制） */
:deep(.el-table .settle-broken),
:deep(.el-table .settle-broken td.el-table__cell) {
  background: #fef0f0 !important;
}
/* 明细弹窗孤儿行（orders 已删）同款标红警示 */
:deep(.el-table .item-orphan),
:deep(.el-table .item-orphan td.el-table__cell) {
  background: #fef0f0 !important;
}
.orphan-tag {
  margin-left: 6px;
}
.orphan-cell {
  color: #f56c6c;
  font-size: 12px;
}
</style>
