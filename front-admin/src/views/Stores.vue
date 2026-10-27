<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">自提点管理</h2>
      <el-tag type="danger" effect="dark" size="small">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      存量 store 的运营维护：改名称/地址/服务说明/营业状态/营业时间/日容量/接单开关。<br />
      列表读 GET /api/admin/stores（不含 contact_phone/photos/lng/lat，PII 与地图真源不在本页）；写走 PUT /api/admin/stores/{id}。<br />
      <b>无新建无删除</b>：门店的创建只经入驻审核，删除无运营语义。
      <b>owner_user_id/contact_phone/photos/lng/lat 不可改</b>：账号归属/PII/上传链路/地图真源不在本片语义内。
      改 status 后 C 端该店即刻置灰/可选（零缓存）；改日容量真实生效到下单拒单（0 与空均为不限容）。
    </p>

    <div class="toolbar">
      <el-button size="small" :loading="loading" :disabled="submitting" @click="loadStores">刷新</el-button>
      <span class="toolbar-hint">共 {{ stores.length }} 家；按 id 升序。主理人空 = 平台直营。</span>
    </div>

    <section class="panel">
      <div v-if="loading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">自提点列表读取中…</div>
      </div>
      <div v-else-if="error" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ error }}</div>
        <el-button size="small" @click="loadStores">重试</el-button>
      </div>
      <template v-else>
        <el-table :data="stores" border stripe size="small" max-height="calc(100vh - 330px)">
          <template #empty>
            <span class="muted">暂无自提点</span>
          </template>
          <el-table-column label="名称" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.name }}</template>
          </el-table-column>
          <el-table-column label="地址" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ row.address }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag :type="row.status === 'open' ? 'success' : 'info'" size="small">
                {{ row.status === 'open' ? '营业中' : '停业' }}
              </el-tag>
              <el-switch :model-value="row.status === 'open'" size="small" class="quick-switch"
                         :disabled="submitting" @change="(on) => onStatusToggle(row, on)" />
            </template>
          </el-table-column>
          <el-table-column label="营业时间" width="110">
            <template #default="{ row }">
              <span v-if="row.openTime || row.closeTime">{{ row.openTime || '—' }} ~ {{ row.closeTime || '—' }}</span>
              <span v-else class="muted">未设置</span>
            </template>
          </el-table-column>
          <el-table-column label="日容量" width="80" align="right">
            <template #default="{ row }">
              <span v-if="row.dailyCapacity == null || row.dailyCapacity === 0" class="muted">不限</span>
              <span v-else>{{ row.dailyCapacity }}</span>
            </template>
          </el-table-column>
          <el-table-column label="接单" width="120">
            <template #default="{ row }">
              <el-switch :model-value="row.accepting === 1" size="small"
                         :disabled="submitting" @change="(on) => onAcceptingToggle(row, on)" />
              <span class="switch-label" :class="{ off: row.accepting !== 1 }">
                {{ row.accepting === 1 ? '接单中' : '已暂停' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="主理人" width="90">
            <template #default="{ row }">
              <span v-if="row.ownerUserId == null" class="muted">平台直营</span>
              <span v-else>#{{ row.ownerUserId }}</span>
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
        门店 #{{ editingRow.id }}（{{ editingRow.name }}）；
        主理人 {{ editingRow.ownerUserId == null ? '空 = 平台直营' : '#' + editingRow.ownerUserId }}（不可改）。
      </div>
      <el-form label-width="110px" @submit.prevent="onSubmit">
        <el-form-item label="名称">
          <el-input v-model="form.name" clearable placeholder="必填，1-100 个字符" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.address" clearable placeholder="必填，1-255 个字符" />
        </el-form-item>
        <el-form-item label="服务说明">
          <el-input v-model="form.service" clearable placeholder="选填，≤50 字（如 冷冻冷藏）" />
        </el-form-item>
        <el-form-item label="营业状态">
          <el-select v-model="form.status" style="width: 160px">
            <el-option label="营业中（open）" value="open" />
            <el-option label="停业（closed）" value="closed" />
          </el-select>
        </el-form-item>
        <el-form-item label="营业开始">
          <el-input v-model="form.openTime" clearable placeholder="HH:MM，留空=不改动现值（如 08:00）" />
        </el-form-item>
        <el-form-item label="营业结束">
          <el-input v-model="form.closeTime" clearable placeholder="HH:MM，留空=不改动现值（如 20:30）" />
        </el-form-item>
        <el-form-item label="日容量">
          <el-input v-model="form.dailyCapacity" clearable placeholder="≥0 整数，留空=不改动现值；0=不限容" />
        </el-form-item>
        <el-form-item label="接单开关">
          <el-select v-model="form.accepting" style="width: 160px">
            <el-option label="接单中（1）" :value="1" />
            <el-option label="已暂停（0）" :value="0" />
          </el-select>
        </el-form-item>
        <div class="field-warn">
          contact_phone（主理人端维护）、photos（上传链路）、经纬度（地图真源）、主理人归属不在本页可改范围。
        </div>
      </el-form>
      <p class="dialog-note">
        校验与后端同款（名称/地址长度、服务说明 ≤50、营业时间 HH:MM、日容量 ≥0、状态与接单值域），后端为准；
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
// 自提点存量管理（F-114 §2）：结构与错误处理照 CouponTemplates.vue（同组菜单 F-109/F-110/F-112/F-113）。
//   读 = GET /api/admin/stores（十一键行，id 升序）
//   写 = PUT /api/admin/stores/{id}（白名单八字段部分更新；编辑与快捷开关同端点）
// 纪律：
//   1) 只写 store 白名单八列；无新建/删除入口（创建只经入驻审核，删除无运营语义）；
//   2) ownerUserId/contactPhone/photos/lng/lat 编辑时不出现输入框、也不随 body 下发（服务端白名单落库）；
//   3) 状态/接单快捷开关在关方向带二次确认（改 status 说明 C 端置灰；关接单说明不影响既有单），
//      开方向是恢复不确认（照 CouponTemplates 的启停开关姿势）；
//   4) 弹窗校验与后端同款，后端为准；错误一律 ElMessage + 请求层透出的后端 message 原文；
//   5) 保存/快捷开关成功后一律重拉列表，绝不本地改行。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminStores, updateAdminStore } from '../api'

const stores = ref([])
const loading = ref(false)
const error = ref('')
const submitting = ref(false)

const dialogVisible = ref(false)
const editingRow = ref(null)
const form = ref({ name: '', address: '', service: '', status: 'open', openTime: '', closeTime: '', dailyCapacity: '', accepting: 1 })

const dialogTitle = computed(() => (editingRow.value ? '编辑门店 #' + editingRow.value.id : '编辑门店'))

async function loadStores() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const envelope = await getAdminStores()
    stores.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    stores.value = []
    error.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  if (!row) return
  editingRow.value = row
  form.value = {
    name: row.name == null ? '' : String(row.name),
    address: row.address == null ? '' : String(row.address),
    service: row.service == null ? '' : String(row.service),
    status: row.status === 'closed' ? 'closed' : 'open',
    openTime: row.openTime == null ? '' : String(row.openTime),
    closeTime: row.closeTime == null ? '' : String(row.closeTime),
    dailyCapacity: row.dailyCapacity == null ? '' : String(row.dailyCapacity),
    accepting: row.accepting === 0 ? 0 : 1
  }
  dialogVisible.value = true
}

// 非负整数校验（与后端同款：≥0）
function parseNonNegInt(raw) {
  if (!/^\d+$/.test(raw)) return null
  return Number(raw)
}

// 弹窗校验（与后端 §1.2 同款）：返回 null 表示拦截（已 ElMessage 提示）。
function validateForm() {
  const f = form.value
  const name = String(f.name || '').trim()
  if (name.length < 1 || name.length > 100) {
    ElMessage.warning('门店名称须为 1-100 个字符')
    return null
  }
  const address = String(f.address || '').trim()
  if (address.length < 1 || address.length > 255) {
    ElMessage.warning('门店地址须为 1-255 个字符')
    return null
  }
  if (String(f.service || '').trim().length > 50) {
    ElMessage.warning('服务说明最长 50 个字符')
    return null
  }
  const timeRule = /^([01]\d|2[0-3]):[0-5]\d$/
  for (const [label, raw] of [['营业开始', f.openTime], ['营业结束', f.closeTime]]) {
    const t = String(raw || '').trim()
    if (t !== '' && !timeRule.test(t)) {
      ElMessage.warning(label + '时间格式应为 HH:MM')
      return null
    }
  }
  const capRaw = String(f.dailyCapacity || '').trim()
  if (capRaw !== '') {
    const cap = parseNonNegInt(capRaw)
    if (cap === null) {
      ElMessage.warning('日接单容量须为大于等于 0 的整数')
      return null
    }
  }
  if (f.accepting !== 0 && f.accepting !== 1) {
    ElMessage.warning('accepting 仅允许 0/1')
    return null
  }
  return { name, address, service: String(f.service || '').trim(), capRaw }
}

async function onSubmit() {
  if (submitting.value) return
  const parsed = validateForm()
  if (parsed === null) return
  // P4-12 §2.3：仅「非 closed → closed」方向拦；原状态取编辑行（openEdit 的基线），其它字段保存不弹。
  const wasClosed = editingRow.value ? editingRow.value.status === 'closed' : false
  if (form.value.status === 'closed' && !wasClosed) {
    try {
      await ElMessageBox.confirm(
        '把门店「' + parsed.name + '」置为「暂停营业」后，C 端该店立即不可选、不可下单（已有订单不受影响）。确定？',
        '请确认暂停营业',
        {
          type: 'warning',
          confirmButtonText: '确认停业',
          cancelButtonText: '取消（不发出任何请求）',
          closeOnClickModal: false,
          customClass: 'admin-write-confirm'
        })
    } catch (e) {
      // 点「取消」/关闭/Esc 一律 reject → 吞掉，不发出任何请求。
      return
    }
  }
  submitting.value = true
  try {
    // 白名单八字段逐列组装：留空的时间/容量键 = 不改动现值（服务端 null=不改），不从不下发
    const body = {
      name: parsed.name,
      address: parsed.address,
      service: parsed.service,
      status: form.value.status === 'closed' ? 'closed' : 'open',
      accepting: form.value.accepting === 0 ? 0 : 1
    }
    const openTime = String(form.value.openTime || '').trim()
    if (openTime !== '') body.openTime = openTime
    const closeTime = String(form.value.closeTime || '').trim()
    if (closeTime !== '') body.closeTime = closeTime
    if (parsed.capRaw !== '') body.dailyCapacity = Number(parsed.capRaw)
    const envelope = await updateAdminStore(editingRow.value.id, body)
    ElMessage.success((envelope && envelope.message) || '门店已更新')
    dialogVisible.value = false
    await loadStores()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

// 状态快捷开关：开=open / 关=closed。关闭前二次确认（契约 §2 文案：closed 后 C 端该店置灰不可选）。
async function onStatusToggle(row, on) {
  if (!row || submitting.value) return
  if (!on) {
    try {
      await ElMessageBox.confirm(
        '确定停业「' + row.name + '」吗？\n'
        + 'closed 后 C 端该店置灰不可选（下单不能选它）；再开回 open 立即恢复可选。不影响既有订单。',
        '停业门店',
        {
          type: 'warning',
          confirmButtonText: '停业',
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
    const envelope = await updateAdminStore(row.id, { status: on ? 'open' : 'closed' })
    ElMessage.success((envelope && envelope.message) || '门店已更新')
    await loadStores()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

// 接单快捷开关：开=1 / 关=0。关闭前二次确认（契约 §2 文案：暂停接单不影响既有单）。
async function onAcceptingToggle(row, on) {
  if (!row || submitting.value) return
  if (!on) {
    try {
      await ElMessageBox.confirm(
        '确定暂停「' + row.name + '」的接单吗？\n'
        + '暂停接单不影响既有单（已下单照常取货）；该店当日不再接新单，与营业状态相互独立。',
        '暂停接单',
        {
          type: 'warning',
          confirmButtonText: '暂停',
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
    const envelope = await updateAdminStore(row.id, { accepting: on ? 1 : 0 })
    ElMessage.success((envelope && envelope.message) || '门店已更新')
    await loadStores()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadStores()
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
.quick-switch {
  margin-left: 8px;
}
.switch-label {
  margin-left: 6px;
  font-size: 12px;
}
.switch-label.off {
  color: #909399;
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
