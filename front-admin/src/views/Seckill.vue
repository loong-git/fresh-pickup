<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">秒杀商品</h2>
      <el-tag type="danger" effect="dark" size="small">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      秒杀商品集只写 dish 的 <b>seckill / seckill_price</b> 两列：进秒杀（设价）/ 调价 / 移出。
      列表读 GET /api/admin/seckill/dishes；写走 PUT /api/admin/seckill/dishes/{id}。<br />
      移出<b>不回清</b>秒杀价（历史价保留，C 端按 seckill=1 才生效）；下架品允许预先配秒杀
      （C 端秒杀列表自会过滤在售，上架即生效）。折扣力度 = (原价 − 秒杀价) / 原价。
    </p>

    <div class="toolbar">
      <el-radio-group v-model="tab" size="small">
        <el-radio-button label="all">全部（{{ dishes.length }}）</el-radio-button>
        <el-radio-button label="in">秒杀中（{{ inCount }}）</el-radio-button>
        <el-radio-button label="out">未进（{{ dishes.length - inCount }}）</el-radio-button>
      </el-radio-group>
      <el-button size="small" :loading="loading" :disabled="submitting" @click="loadDishes">刷新</el-button>
      <span class="toolbar-hint">秒杀中 {{ inCount }} 件；按 id 升序。</span>
    </div>

    <section class="panel">
      <div v-if="loading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">秒杀商品列表读取中…</div>
      </div>
      <div v-else-if="error" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ error }}</div>
        <el-button size="small" @click="loadDishes">重试</el-button>
      </div>
      <template v-else>
        <el-table :data="filteredDishes" border stripe size="small" max-height="calc(100vh - 330px)">
          <template #empty>
            <span class="muted">该分类下暂无商品</span>
          </template>
          <el-table-column label="商品" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.name || ('#' + row.id) }}</span>
              <span class="muted"> #{{ row.id }}</span>
            </template>
          </el-table-column>
          <el-table-column label="原价" width="90" align="right">
            <template #default="{ row }">{{ moneyText(row.price) }}</template>
          </el-table-column>
          <el-table-column label="秒杀价" width="90" align="right">
            <template #default="{ row }">
              <span v-if="Number(row.seckill) === 1 && row.seckillPrice != null" class="seckill-price">
                {{ moneyText(row.seckillPrice) }}
              </span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="折扣力度" width="100" align="right">
            <template #default="{ row }">
              <el-tag v-if="Number(row.seckill) === 1 && row.seckillPrice != null"
                      type="danger" size="small" effect="light">省 {{ discountText(row) }}%</el-tag>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="stock" label="库存" width="80" align="right" />
          <el-table-column prop="soldCount" label="已售" width="80" align="right" />
          <el-table-column label="在售态" width="90">
            <template #default="{ row }">
              <el-tag :type="Number(row.onSale) === 1 ? 'success' : 'info'" size="small" effect="light">
                {{ Number(row.onSale) === 1 ? '在售' : '已下架' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }">
              <template v-if="Number(row.seckill) === 1">
                <el-button size="small" type="primary" plain :disabled="submitting" @click="openEdit(row)">调价</el-button>
                <el-button size="small" type="danger" plain :disabled="submitting" @click="onRemove(row)">移出秒杀</el-button>
              </template>
              <el-button v-else size="small" type="primary" plain :disabled="submitting" @click="openEdit(row)">
                进秒杀·设价
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </section>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" :close-on-click-modal="false">
      <div v-if="editingRow" class="dialog-target">
        商品 #{{ editingRow.id }} {{ editingRow.name }}；原价 {{ moneyText(editingRow.price) }}
        <template v-if="Number(editingRow.seckill) === 1 && editingRow.seckillPrice != null">
          ，当前秒杀价 {{ moneyText(editingRow.seckillPrice) }}
        </template>
      </div>
      <el-form label-width="90px" @submit.prevent="onSubmit">
        <el-form-item label="秒杀价">
          <el-input v-model="form.price" clearable placeholder="必填，正数且最多两位小数，须低于原价" />
        </el-form-item>
        <div v-if="editingRow && Number(editingRow.onSale) !== 1" class="field-warn">
          该商品已下架：允许预先配秒杀，C 端秒杀列表不展示下架品，上架后即生效。
        </div>
      </el-form>
      <p class="dialog-note">
        校验与后端同款（必填、&gt;0、两位小数、低于原价），后端为准；
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
// 秒杀商品集管理（F-112 §2）：结构与错误处理照 Aliases.vue（同组菜单 F-109/F-110）。
//   读 = GET /api/admin/seckill/dishes（十键行，id 升序，秒杀中/未进的筛选在本页 tab 做）
//   写 = PUT /api/admin/seckill/dishes/{id}，body {seckill, seckillPrice}
// 纪律：
//   1) 只动 seckill / seckill_price 两列，本页不提供价格/库存/上下架入口（那是商品管理页的事）；
//   2) 移出（seckill=0）不回清秒杀价——服务端语义，本页只发 {seckill:0}，不发价；
//   3) 弹窗校验与后端同款（必填、>0、两位小数、低于原价），后端为准；错误一律 ElMessage +
//      请求层透出的后端 message 原文，本页不预置猜原因的提示表；
//   4) 保存/移出成功后一律重拉列表，绝不本地改行。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminSeckillDishes, updateAdminSeckillDish } from '../api'

const dishes = ref([])
const loading = ref(false)
const error = ref('')
const submitting = ref(false)
const tab = ref('all')

const dialogVisible = ref(false)
const editingRow = ref(null)
const form = ref({ price: '' })

const inCount = computed(() => dishes.value.filter((d) => Number(d.seckill) === 1).length)

const filteredDishes = computed(() => {
  if (tab.value === 'in') return dishes.value.filter((d) => Number(d.seckill) === 1)
  if (tab.value === 'out') return dishes.value.filter((d) => Number(d.seckill) !== 1)
  return dishes.value
})

const dialogTitle = computed(() => (editingRow.value && Number(editingRow.value.seckill) === 1
  ? '调价' + (editingRow.value ? ' #' + editingRow.value.id : '')
  : '进秒杀·设价' + (editingRow.value ? ' #' + editingRow.value.id : '')))

// 金额展示：服务端回原始数值，本页统一两位小数（读路径纯展示，不换算口径）。
function moneyText(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(2) : '—'
}

// 折扣力度（契约 §2）：(price − seckillPrice) / price 百分比，一位小数。
function discountText(row) {
  const price = Number(row.price)
  const sp = Number(row.seckillPrice)
  if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(sp)) return '—'
  return (((price - sp) / price) * 100).toFixed(1)
}

async function loadDishes() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const envelope = await getAdminSeckillDishes()
    dishes.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    dishes.value = []
    error.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  if (!row) return
  editingRow.value = row
  form.value = { price: '' }
  dialogVisible.value = true
}

// 弹窗校验（与后端 §1.2 同款）：必填、数字、>0、最多两位小数、低于该商品原价。
function validateForm() {
  const raw = String(form.value.price == null ? '' : form.value.price).trim()
  if (!raw) {
    ElMessage.warning('秒杀价必须为正数（最多两位小数）')
    return null
  }
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) {
    ElMessage.warning('秒杀价必须为正数（最多两位小数）')
    return null
  }
  const price = Number(raw)
  if (!(price > 0)) {
    ElMessage.warning('秒杀价必须为正数（最多两位小数）')
    return null
  }
  const origin = Number(editingRow.value && editingRow.value.price)
  if (Number.isFinite(origin) && price >= origin) {
    ElMessage.warning('秒杀价必须低于原价 ' + origin.toFixed(2))
    return null
  }
  return price
}

async function onSubmit() {
  if (submitting.value) return
  const price = validateForm()
  if (price === null) return
  submitting.value = true
  try {
    const envelope = await updateAdminSeckillDish(editingRow.value.id, { seckill: 1, seckillPrice: price })
    ElMessage.success((envelope && envelope.message) || '秒杀商品已更新')
    dialogVisible.value = false
    await loadDishes()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

async function onRemove(row) {
  if (!row || submitting.value) return
  const nameText = row.name ? row.name : '#' + row.id
  try {
    await ElMessageBox.confirm(
      '确定把「' + nameText + '」移出秒杀吗？\n'
      + '移出后 C 端秒杀列表不再出现该商品；秒杀价不回清（历史价保留），重新进秒杀时再设新价。',
      '移出秒杀',
      {
        type: 'warning',
        confirmButtonText: '移出',
        cancelButtonText: '取消',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    // 取消确认：不发出任何请求
    return
  }
  submitting.value = true
  try {
    const envelope = await updateAdminSeckillDish(row.id, { seckill: 0 })
    ElMessage.success((envelope && envelope.message) || '秒杀商品已更新')
    await loadDishes()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadDishes()
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
.seckill-price {
  color: #f56c6c;
  font-weight: 600;
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
