<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">券模板</h2>
      <el-tag type="danger" effect="dark" size="small">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      券模板只写 coupon_template 一张表：上新 / 改面额门槛天数限领总量 / 启停。<br />
      列表读 GET /api/admin/coupon-templates；写走 POST /api/admin/coupon-templates 与 PUT .../{id}。<br />
      <b>无删除</b>：已发过的模板删除 = 历史券的 key 悬挂，停用即运营语义。
      <b>key 与已发计数不可改</b>：核销走券上快照，改 key 只会让历史券对不上模板；
      issued_count 是配额原子扣减的运行时计数。已发券的面额门槛不受本页改动影响（快照语义）。
    </p>

    <div class="toolbar">
      <el-button size="small" type="primary" plain :disabled="submitting" @click="openCreate">上新模板</el-button>
      <el-button size="small" :loading="loading" :disabled="submitting" @click="loadTemplates">刷新</el-button>
      <span class="toolbar-hint">共 {{ templates.length }} 个；按 id 升序。C 端改动下一次请求即生效（无缓存）。</span>
    </div>

    <section class="panel">
      <div v-if="loading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">券模板列表读取中…</div>
      </div>
      <div v-else-if="error" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ error }}</div>
        <el-button size="small" @click="loadTemplates">重试</el-button>
      </div>
      <template v-else>
        <el-table :data="templates" border stripe size="small" max-height="calc(100vh - 330px)">
          <template #empty>
            <span class="muted">暂无券模板</span>
          </template>
          <el-table-column label="key" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.couponKey }}</span>
            </template>
          </el-table-column>
          <el-table-column label="名称" min-width="110">
            <template #default="{ row }">
              <span v-if="row.title">{{ row.title }}</span>
              <span v-else class="muted">满{{ moneyText(row.threshold) }}减{{ moneyText(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="门槛" width="80" align="right">
            <template #default="{ row }">{{ moneyText(row.threshold) }}</template>
          </el-table-column>
          <el-table-column label="抵扣" width="80" align="right">
            <template #default="{ row }">
              <span class="amount">{{ moneyText(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="validDays" label="天数" width="60" align="right" />
          <el-table-column label="限领/人" width="70" align="right">
            <template #default="{ row }">
              <span>{{ row.limitPerUser === 0 ? '不限' : row.limitPerUser }}</span>
            </template>
          </el-table-column>
          <el-table-column label="总量" width="80" align="right">
            <template #default="{ row }">
              <span v-if="row.totalQuota == null" class="muted">不限</span>
              <span v-else>{{ row.issuedCount }}/{{ row.totalQuota }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-switch :model-value="row.status === 'active'" size="small"
                         :disabled="submitting" @change="(on) => onToggle(row, on)" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :disabled="submitting" @click="openEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </section>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" :close-on-click-modal="false">
      <div v-if="editingRow" class="dialog-target">
        模板 #{{ editingRow.id }} key=<span class="mono">{{ editingRow.couponKey }}</span>（key 不可改）；
        已发 {{ editingRow.issuedCount }} 张（issued_count 不可改）。
      </div>
      <el-form label-width="100px" @submit.prevent="onSubmit">
        <el-form-item v-if="!editingRow" label="key">
          <el-input v-model="form.couponKey" clearable placeholder="必填，2-50 位小写字母/数字/下划线，不可与现存重复" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="form.title" clearable placeholder="选填，≤50 字；留空则前端按满X减Y展示" />
        </el-form-item>
        <el-form-item label="门槛（满X）">
          <el-input v-model="form.threshold" clearable placeholder="必填，正数最多两位小数" />
        </el-form-item>
        <el-form-item label="抵扣（减Y）">
          <el-input v-model="form.amount" clearable placeholder="必填，正数最多两位小数，须低于门槛" />
        </el-form-item>
        <el-form-item label="有效天数">
          <el-input v-model="form.validDays" clearable placeholder="必填，领取后有效天数 1-365" />
        </el-form-item>
        <el-form-item label="每人限领">
          <el-input v-model="form.limitPerUser" clearable placeholder="必填，≥0；0=不限领（全史含已用/过期）" />
        </el-form-item>
        <el-form-item label="总量配额">
          <el-input v-model="form.totalQuota" clearable placeholder="选填，≥0；留空=不限总量" />
        </el-form-item>
        <el-form-item v-if="!editingRow" label="状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="启用（active）" value="active" />
            <el-option label="停用（disabled）" value="disabled" />
          </el-select>
        </el-form-item>
        <div v-if="editingRow && Number(editingRow.issuedCount) > 0" class="field-warn">
          该模板已发 {{ editingRow.issuedCount }} 张：改面额/门槛/天数只影响之后的新领取，
          已发券核销按券上快照（C 端零影响，这是刻意的防资损语义）。
        </div>
      </el-form>
      <p class="dialog-note">
        校验与后端同款（key 形状与冲突、金额形状、抵扣&lt;门槛、天数 1-365、限领/总量 ≥0），后端为准；
        保存成功后重拉列表核对，不本地改行；失败原因原样取自服务端 message。
      </p>
      <template #footer>
        <el-button size="small" :disabled="submitting" @click="dialogVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 券模板管理（F-113 §4）：结构与错误处理照 Seckill.vue（同组菜单 F-109/F-110/F-112）。
//   读 = GET /api/admin/coupon-templates（十键行，id 升序）
//   写 = POST /api/admin/coupon-templates（上新）/ PUT /api/admin/coupon-templates/{id}（改配置+启停）
// 纪律：
//   1) 只写 coupon_template 一张表；无删除入口（停用即运营语义，契约 §4 点名不提供 DELETE）；
//   2) key/issuedCount 编辑时不出现输入框、也不随 body 下发（服务端白名单落库，带了也会被忽略）；
//   3) 弹窗校验与后端同款，后端为准；错误一律 ElMessage + 请求层透出的后端 message 原文；
//   4) 保存/启停成功后一律重拉列表，绝不本地改行。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminCouponTemplates, createAdminCouponTemplate, updateAdminCouponTemplate } from '../api'

const templates = ref([])
const loading = ref(false)
const error = ref('')
const submitting = ref(false)

const dialogVisible = ref(false)
const editingRow = ref(null)
const form = ref({ couponKey: '', title: '', threshold: '', amount: '', validDays: '', limitPerUser: '', totalQuota: '', status: 'active' })

const dialogTitle = computed(() => (editingRow.value ? '编辑模板 #' + editingRow.value.id : '上新模板'))

// 金额展示：服务端回原始数值，本页统一两位小数（读路径纯展示，不换算口径）。
function moneyText(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '—'
}

async function loadTemplates() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const envelope = await getAdminCouponTemplates()
    templates.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    templates.value = []
    error.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingRow.value = null
  form.value = { couponKey: '', title: '', threshold: '', amount: '', validDays: '7', limitPerUser: '1', totalQuota: '', status: 'active' }
  dialogVisible.value = true
}

function openEdit(row) {
  if (!row) return
  editingRow.value = row
  form.value = {
    title: row.title == null ? '' : row.title,
    threshold: String(row.threshold == null ? '' : row.threshold),
    amount: String(row.amount == null ? '' : row.amount),
    validDays: String(row.validDays == null ? '' : row.validDays),
    limitPerUser: String(row.limitPerUser == null ? '' : row.limitPerUser),
    totalQuota: row.totalQuota == null ? '' : String(row.totalQuota)
  }
  dialogVisible.value = true
}

// 两位小数正数校验（与后端同款：>0、最多两位小数）
function parseMoney(raw) {
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) return null
  const n = Number(raw)
  return n > 0 ? n : null
}

function parseNonNegInt(raw) {
  if (!/^\d+$/.test(raw)) return null
  return Number(raw)
}

// 弹窗校验（与后端 §4 同款）：返回 null 表示拦截（已 ElMessage 提示）。
function validateForm() {
  const f = form.value
  if (!editingRow.value) {
    const key = String(f.couponKey || '').trim()
    if (!/^[a-z0-9_]{2,50}$/.test(key)) {
      ElMessage.warning('key 须为 2-50 位小写字母/数字/下划线')
      return null
    }
  }
  if (String(f.title || '').trim().length > 50) {
    ElMessage.warning('名称最长 50 个字符')
    return null
  }
  const threshold = parseMoney(String(f.threshold || '').trim())
  if (threshold === null) {
    ElMessage.warning('门槛与抵扣须为正数（最多两位小数）')
    return null
  }
  const amount = parseMoney(String(f.amount || '').trim())
  if (amount === null) {
    ElMessage.warning('门槛与抵扣须为正数（最多两位小数）')
    return null
  }
  if (amount >= threshold) {
    ElMessage.warning('抵扣金额必须低于使用门槛')
    return null
  }
  const validDays = parseNonNegInt(String(f.validDays || '').trim())
  if (validDays === null || validDays < 1 || validDays > 365) {
    ElMessage.warning('有效天数须为 1-365')
    return null
  }
  const limitPerUser = parseNonNegInt(String(f.limitPerUser || '').trim())
  if (limitPerUser === null) {
    ElMessage.warning('每人限领须为大于等于 0 的整数')
    return null
  }
  let totalQuota = null
  const quotaRaw = String(f.totalQuota || '').trim()
  if (quotaRaw !== '') {
    totalQuota = parseNonNegInt(quotaRaw)
    if (totalQuota === null) {
      ElMessage.warning('总量配额须为大于等于 0 的整数（不限则不传）')
      return null
    }
  }
  return { threshold, amount, validDays, limitPerUser, totalQuota }
}

async function onSubmit() {
  if (submitting.value) return
  const parsed = validateForm()
  if (parsed === null) return
  // P4-12 §2.5：只在编辑分支拦（新增不拦，避免过密）；已发券走快照核销，改面额/门槛只影响新领取。
  if (editingRow.value) {
    try {
      await ElMessageBox.confirm(
        '编辑并保存模板「' + editingRow.value.couponKey + '」：\n'
        + '· 修改面额/门槛只影响之后的新领取，C 端领券中心下次请求即按新配置展示\n'
        + '· 已发券按领取时的快照核销，不受本次修改影响\n'
        + '· 本页没有撤销入口，改回需再次编辑提交',
        '请确认修改券模板',
        {
          type: 'warning',
          confirmButtonText: '确认保存',
          cancelButtonText: '取消（不发出任何请求）',
          customClass: 'admin-write-confirm'
        })
    } catch (e) {
      // 取消确认：不发出任何请求
      return
    }
  }
  submitting.value = true
  try {
    let envelope
    if (editingRow.value) {
      // 只带要改的七列；title 允许显式清空（空串=回「满X减Y」拼装），key/issuedCount 从不下发
      const body = {
        title: String(form.value.title || '').trim(),
        threshold: parsed.threshold,
        amount: parsed.amount,
        validDays: parsed.validDays,
        limitPerUser: parsed.limitPerUser
      }
      if (parsed.totalQuota !== null) body.totalQuota = parsed.totalQuota
      envelope = await updateAdminCouponTemplate(editingRow.value.id, body)
    } else {
      const body = {
        couponKey: String(form.value.couponKey || '').trim(),
        threshold: parsed.threshold,
        amount: parsed.amount,
        validDays: parsed.validDays,
        limitPerUser: parsed.limitPerUser,
        status: form.value.status || 'active'
      }
      const title = String(form.value.title || '').trim()
      if (title !== '') body.title = title
      if (parsed.totalQuota !== null) body.totalQuota = parsed.totalQuota
      envelope = await createAdminCouponTemplate(body)
    }
    ElMessage.success((envelope && envelope.message) || (editingRow.value ? '券模板已更新' : '券模板已新增'))
    dialogVisible.value = false
    await loadTemplates()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

// 启停开关：开=active / 关=disabled。关闭前二次确认（停用后 C 端领券中心立刻不展示、claim 400）。
async function onToggle(row, on) {
  if (!row || submitting.value) return
  const nextStatus = on ? 'active' : 'disabled'
  if (!on) {
    try {
      await ElMessageBox.confirm(
        '确定停用「' + row.couponKey + '」吗？\n'
        + '停用后 C 端领券中心立刻不展示该模板、claim 返回「无效的优惠券」；已发券不受影响（核销走券上快照）。',
        '停用券模板',
        {
          type: 'warning',
          confirmButtonText: '停用',
          cancelButtonText: '取消',
          customClass: 'admin-write-confirm'
        })
    } catch (e) {
      // 取消确认：不发出任何请求（开关由 model-value 受控，自动弹回）
      return
    }
  }
  submitting.value = true
  try {
    const envelope = await updateAdminCouponTemplate(row.id, { status: nextStatus })
    ElMessage.success((envelope && envelope.message) || '券模板已更新')
    await loadTemplates()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadTemplates()
})
</script>

<style scoped>
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
.amount {
  color: #f56c6c;
  font-weight: 600;
}
.mono {
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
}
.dialog-target {
  margin-bottom: 12px;
  font-size: 12px;
  color: #909399;
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
</style>
