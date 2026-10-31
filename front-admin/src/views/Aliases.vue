<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">搜索别名</h2>
      <el-tag type="danger" effect="dark" size="small">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      搜索别名（dish_alias）只影响 C 端搜索命中：输入与别名<b>精确等值</b>时指向对应商品，
      不参与价格 / 库存 / 订单。列表读 GET /api/admin/aliases；新增 / 编辑 / 删除走
      POST、PUT、DELETE /api/admin/aliases。商品下拉取 GET /api/admin/v2/dishes 的<b>在售</b>商品（onSale=1）。<br />
      下架商品仍可保留别名（搜索命中后由既有下架过滤兜底），本页照实显示「已下架」，不会静默隐藏。
    </p>

    <div class="toolbar">
      <el-button size="small" type="primary" :disabled="loading || !dishesLoaded || submitting"
                 @click="openCreate">新增别名</el-button>
      <el-button size="small" :loading="loading" :disabled="submitting" @click="loadAliases">刷新</el-button>
      <span class="toolbar-hint">共 {{ aliases.length }} 条；按别名升序。</span>
    </div>

    <div v-if="dishesError" class="options-error">
      在售商品列表读取失败：{{ dishesError }}
      <el-button size="small" :loading="dishesLoading" @click="loadDishes">重试</el-button>
    </div>
    <div v-if="dishesTruncated" class="options-note">
      在售商品超过本次已加载的 {{ onSaleDishes.length }} 件（最多翻 {{ DISH_MAX_PAGES }} 页、每页
      {{ DISH_PAGE_SIZE }} 件）；未出现在下拉里的商品无法在本页选择。
    </div>

    <section class="panel">
      <div v-if="loading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">搜索别名读取中…</div>
      </div>
      <div v-else-if="error" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ error }}</div>
        <el-button size="small" @click="loadAliases">重试</el-button>
      </div>
      <template v-else>
        <el-table :data="aliases" border stripe size="small" max-height="calc(100vh - 330px)">
          <template #empty>
            <span class="muted">暂无搜索别名</span>
          </template>
          <el-table-column prop="alias" label="别名" min-width="180" show-overflow-tooltip />
          <el-table-column label="商品" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.dishName">{{ row.dishName }}</span>
              <span v-else class="muted">商品已不存在</span>
              <span class="muted"> #{{ row.dishId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="在售态" width="110">
            <template #default="{ row }">
              <el-tag :type="Number(row.onSale) === 1 ? 'success' : 'info'" size="small" effect="light">
                {{ Number(row.onSale) === 1 ? '在售' : '已下架' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain
                         :disabled="submitting || !dishesLoaded" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" type="danger" plain
                         :disabled="submitting" @click="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </section>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="520px" :close-on-click-modal="false">
      <div v-if="editingRow" class="dialog-target">
        别名 #{{ editingRow.id }}，当前商品：{{ editingRow.dishName || ('#' + editingRow.dishId) }}
      </div>
      <el-form label-width="80px" @submit.prevent="onSubmit">
        <el-form-item label="别名">
          <el-input v-model="form.alias" maxlength="50" show-word-limit clearable
                    placeholder="必填，trim 后最长 50 个字符" />
        </el-form-item>
        <el-form-item label="商品">
          <el-select v-model="form.dishId" class="full-width" filterable clearable
                     :loading="dishesLoading" :disabled="dishesLoading || !dishesLoaded"
                     placeholder="请选择商品（只列在售）">
            <el-option v-for="d in onSaleDishes" :key="d.id"
                       :label="d.name || ('#' + d.id)" :value="d.id" />
          </el-select>
          <div v-if="currentDishOffSale" class="field-warn">
            当前商品「{{ editingRow.dishName || ('#' + editingRow.dishId) }}」已下架，不在在售下拉中；
            不重选商品时保存只改别名，不会改动商品。
          </div>
        </el-form-item>
      </el-form>
      <p class="dialog-note">
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
// 搜索别名维护（F-110 §0.3）：结构与错误处理照 Dicts.vue / FreeActivity.vue。
//   读 = GET /api/admin/aliases（[{id,alias,dishId,dishName,onSale}]，alias 升序）
//   写 = POST /api/admin/aliases、PUT /api/admin/aliases/{id}、DELETE /api/admin/aliases/{id}
//   商品下拉 = GET /api/admin/v2/dishes?onSale=1（既有出参：id / name / onSale，分页 {list,total,...}）
// 纪律：
//   1) 别名只影响搜索命中，本页不提供价格/库存/订单入口；
//   2) 商品下拉只列在售商品；编辑一条挂在已下架商品上的别名时，不重选商品就只改别名；
//   3) 错误一律 ElMessage + 请求层透出的后端 message 原文，本页不预置猜原因的提示表；
//   4) 保存/删除成功后一律重拉列表，绝不本地改行（唯一键冲突这类判定只在服务端）。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAdminAliases,
  createAdminAlias,
  updateAdminAlias,
  deleteAdminAlias,
  getAdminDishes
} from '../api'

// 与后端 ALIAS_MAX_LENGTH / dish_alias.alias VARCHAR(50) 同值：前端只做即时提示，服务端仍会再判一次。
const ALIAS_MAX_LENGTH = 50
// GET /api/admin/v2/dishes 允许的最大页大小是 100（越界 400）；在售商品跨页时逐页取全。
const DISH_PAGE_SIZE = 100
// 防御性上界：避免商品表异常时无限翻页（每页 100 件，20 页 = 2000 件）。
const DISH_MAX_PAGES = 20

const aliases = ref([])
const loading = ref(false)
const error = ref('')
const submitting = ref(false)

const dialogVisible = ref(false)
const dialogMode = ref('create')
const editingRow = ref(null)
const form = ref({ alias: '', dishId: null })

const onSaleDishes = ref([])
const dishesLoading = ref(false)
const dishesError = ref('')
const dishesLoaded = ref(false)
const dishesTruncated = ref(false)

const dialogTitle = computed(() => (dialogMode.value === 'create'
  ? '新增别名'
  : '编辑别名' + (editingRow.value ? ' #' + editingRow.value.id : '')))

// 编辑对象的商品是否已不在在售列表：只在商品列表成功加载后判定，避免把「还没加载」误报成「已下架」。
const currentDishOffSale = computed(() => {
  const row = editingRow.value
  if (!row || row.dishId == null || !dishesLoaded.value) return false
  return !onSaleDishes.value.some((d) => Number(d.id) === Number(row.dishId))
})

function toNumberOrNull(value) {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

async function loadDishes() {
  if (dishesLoading.value) return
  dishesLoading.value = true
  dishesError.value = ''
  dishesTruncated.value = false
  try {
    const collected = []
    let total = 0
    let pageNum = 1
    while (pageNum <= DISH_MAX_PAGES) {
      const envelope = await getAdminDishes(pageNum, DISH_PAGE_SIZE, { onSale: 1 })
      const data = (envelope && envelope.data) || {}
      const list = Array.isArray(data.list) ? data.list : []
      list.forEach((d) => {
        if (!d || d.id == null) return
        // 服务端已按 onSale=1 过滤；这里再核一次，保证下拉里不可能出现下架商品。
        if (Number(d.onSale) !== 1) return
        collected.push({ id: Number(d.id), name: d.name == null ? '' : String(d.name) })
      })
      total = Number(data.total) || 0
      if (list.length === 0 || collected.length >= total) break
      pageNum += 1
    }
    // 防御性去重（同 id 只留一条），并保持服务端返回顺序。
    const seen = new Set()
    const unique = []
    collected.forEach((d) => {
      if (seen.has(d.id)) return
      seen.add(d.id)
      unique.push(d)
    })
    onSaleDishes.value = unique
    dishesLoaded.value = true
    dishesTruncated.value = total > unique.length
  } catch (err) {
    const e = err || {}
    onSaleDishes.value = []
    dishesLoaded.value = false
    dishesError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    dishesLoading.value = false
  }
}

async function loadAliases() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const envelope = await getAdminAliases()
    aliases.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    aliases.value = []
    error.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  if (!dishesLoaded.value) {
    ElMessage.warning('在售商品列表尚未就绪，请先重试加载')
    return
  }
  dialogMode.value = 'create'
  editingRow.value = null
  form.value = { alias: '', dishId: null }
  dialogVisible.value = true
}

function openEdit(row) {
  if (!row) return
  if (!dishesLoaded.value) {
    ElMessage.warning('在售商品列表尚未就绪，请先重试加载')
    return
  }
  dialogMode.value = 'edit'
  editingRow.value = row
  // 在售商品预选进下拉；已下架商品不在下拉里，留空表示「不重选 = 不改商品」。
  const currentDishId = toNumberOrNull(row.dishId)
  const inOnSaleList = onSaleDishes.value.some((d) => Number(d.id) === currentDishId)
  form.value = { alias: row.alias == null ? '' : String(row.alias), dishId: inOnSaleList ? currentDishId : null }
  dialogVisible.value = true
}

function validateForm() {
  const alias = String(form.value.alias == null ? '' : form.value.alias).trim()
  if (!alias) {
    ElMessage.warning('别名不能为空')
    return false
  }
  if (alias.length > ALIAS_MAX_LENGTH) {
    ElMessage.warning('别名最长 50 个字符')
    return false
  }
  if (toNumberOrNull(form.value.dishId) === null) {
    // 编辑一条挂在已下架商品上的别名时，允许不重选商品、只改别名；其余情况必须选商品。
    const mayKeepOffSaleDish = dialogMode.value === 'edit' && currentDishOffSale.value
    if (!mayKeepOffSaleDish) {
      ElMessage.warning('商品不能为空')
      return false
    }
  }
  return true
}

// 只收集与服务器当前值不同的列；没有变化时不发请求（避免一次「无更新字段」的 400）。
function buildEditPatch() {
  const row = editingRow.value
  const patch = {}
  if (!row) return patch
  const alias = String(form.value.alias == null ? '' : form.value.alias).trim()
  if (alias !== String(row.alias == null ? '' : row.alias)) patch.alias = alias
  const dishId = toNumberOrNull(form.value.dishId)
  if (dishId !== null && dishId !== Number(row.dishId)) patch.dishId = dishId
  return patch
}

async function onSubmit() {
  if (submitting.value) return
  if (!validateForm()) return
  const alias = String(form.value.alias).trim()
  submitting.value = true
  try {
    if (dialogMode.value === 'create') {
      const envelope = await createAdminAlias({ alias, dishId: toNumberOrNull(form.value.dishId) })
      ElMessage.success((envelope && envelope.message) || '别名已新增')
    } else {
      const patch = buildEditPatch()
      if (Object.keys(patch).length === 0) {
        ElMessage.warning('没有需要保存的修改')
        return
      }
      const envelope = await updateAdminAlias(editingRow.value.id, patch)
      ElMessage.success((envelope && envelope.message) || '别名已更新')
    }
    dialogVisible.value = false
    await loadAliases()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

async function onDelete(row) {
  if (!row || submitting.value) return
  const dishText = row.dishName ? row.dishName : '#' + row.dishId
  try {
    await ElMessageBox.confirm(
      '确定删除别名「' + row.alias + '」吗？\n'
      + '删除后 C 端搜索「' + row.alias + '」将不再命中「' + dishText + '」，该动作不可恢复。',
      '删除别名',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    // 取消确认：不发出任何请求
    return
  }
  submitting.value = true
  try {
    const envelope = await deleteAdminAlias(row.id)
    ElMessage.success((envelope && envelope.message) || '别名已删除')
    await loadAliases()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  // 两路读互不阻塞：别名列表失败不影响商品下拉，反之亦然。
  loadDishes()
  loadAliases()
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
  color: #57534E;
}
.panel {
  margin-top: 14px;
  padding: 16px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  box-shadow: 0 1px 3px rgba(28, 25, 23, 0.06);
}
.options-error {
  margin-top: 12px;
  padding: 10px 14px;
  background: #FCE9E9;
  border: 1px solid #F8D4D4;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #DC2626;
  word-break: break-all;
}
.options-note {
  margin-top: 12px;
  padding: 10px 14px;
  background: #F7F6F3;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #57534E;
}
.muted {
  color: #57534E;
  font-size: 12px;
}
.dialog-target {
  margin-bottom: 12px;
  font-size: 12px;
  color: #57534E;
}
.full-width {
  width: 100%;
}
.field-warn {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #B45309;
}
.dialog-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #57534E;
}
</style>
