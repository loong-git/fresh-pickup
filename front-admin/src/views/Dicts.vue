<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">字典管理</h2>
      <el-tag type="danger" effect="dark" size="small" class="mode-badge">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      字典四组与词条的运营维护页：左侧切换分组并启停，右侧维护当前组词条（word / definition / sortOrder，不分页）。<br />
      读取走 GET /api/admin/dict/groups 与 GET /api/admin/dict/{group}/items；新增 / 编辑 / 删除 / 启停走
      POST、PUT、DELETE、PUT /api/admin/dict/...，写成功后服务端会自动清除该组缓存，本页随后重拉分组与词条核对。<br />
      「从上游同步」只补本地没有的词条（已有行一律不动）：上游改名会表现为新名插入、旧名保留，需要手工清理旧名。<br />
      删除 dish_category 词条受引用保护：该分类下仍有商品时服务端回 400，本页原样展示后端文案；
      其余组无引用保护，删除后 C 端会回退 FALLBACK。
    </p>

    <div class="toolbar">
      <el-button size="small" type="primary" :disabled="!currentGroup || itemsLoading || submitting"
                 @click="openCreate">新增词条</el-button>
      <el-button size="small" :disabled="!currentGroup || itemsLoading || submitting"
                 @click="onEvict">清除缓存</el-button>
      <!-- §0.8：停用组不同步（停用语义是「返空且不回退上游」），只有 enabled=1 的当前组可用 -->
      <el-button size="small" type="warning"
                 :disabled="!currentGroup || Number(currentGroup.enabled) !== 1 || itemsLoading || submitting"
                 @click="onSyncFromUpstream">从上游同步</el-button>
    </div>

    <div v-if="writeResult" class="result-bar">
      <div class="result-title">{{ writeResult.title }}</div>
      <div v-if="writeResult.serverText" class="result-line">服务端原文：{{ writeResult.serverText }}</div>
    </div>

    <div class="dict-layout">
      <aside class="group-panel">
        <div class="panel-title">分组</div>
        <div v-if="groupsLoading" class="panel-state">分组加载中…</div>
        <div v-else-if="groupsError" class="panel-state">
          <div class="panel-error">{{ groupsError }}</div>
          <el-button size="small" @click="reloadAll">重试</el-button>
        </div>
        <ul v-else-if="groups.length" class="group-list">
          <li v-for="g in groups" :key="g.code" class="group-item"
              :class="{ active: g.code === currentGroupCode }"
              tabindex="0"
              @click="selectGroup(g.code)"
              @keydown.enter="selectGroup(g.code)">
            <div class="group-main">
              <div class="group-name">{{ g.name }}</div>
              <div class="group-code">{{ g.code }}</div>
            </div>
            <div class="group-meta">
              <span class="group-count">{{ g.itemCount }} 条</span>
              <el-switch :model-value="Number(g.enabled) === 1" size="small"
                         :loading="togglingCode === g.code"
                         :disabled="submitting || (togglingCode !== '' && togglingCode !== g.code)"
                         @click.stop
                         @change="(val) => onToggleGroup(g, val)" />
            </div>
          </li>
        </ul>
        <div v-else class="panel-state">暂无分组</div>
      </aside>

      <section class="items-panel">
        <div class="items-head">
          <div class="items-title">
            <template v-if="currentGroup">{{ currentGroup.name }} <span class="items-code">{{ currentGroup.code }}</span></template>
            <template v-else>未选择分组</template>
          </div>
          <span v-if="currentGroup" class="items-meta">共 {{ items.length }} 条</span>
        </div>

        <!-- 契约 §0.7：除 dish_category 外的三组没有引用保护，删除后 C 端读路径会回退到 FALLBACK 词表。 -->
        <div v-if="currentGroup && currentGroup.code !== 'dish_category'" class="fallback-tip">
          该组无引用保护，删除后前端会回退 FALLBACK
        </div>

        <div v-if="itemsLoading" class="state-box">
          <div class="state-title">正在加载</div>
          <div class="state-desc">词条读取中…</div>
        </div>
        <div v-else-if="itemsError" class="state-box">
          <div class="state-title">读取失败</div>
          <div class="state-desc">{{ itemsError }}</div>
          <el-button size="small" @click="loadItems(currentGroupCode)">重试</el-button>
        </div>
        <template v-else>
          <el-table :data="items" border stripe size="small" class="table" max-height="calc(100vh - 380px)">
            <template #empty>
              <span class="empty">当前分组暂无词条</span>
            </template>
            <el-table-column prop="word" label="word" min-width="180" show-overflow-tooltip />
            <el-table-column label="definition" min-width="320" show-overflow-tooltip>
              <template #default="{ row }"><span :class="{ muted: !row.definition }">{{ row.definition }}</span></template>
            </el-table-column>
            <el-table-column prop="sortOrder" label="sortOrder" width="120" />
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" plain :disabled="submitting || itemsLoading"
                           @click="openEdit(row)">编辑</el-button>
                <el-button size="small" type="danger" plain :disabled="submitting || itemsLoading"
                           @click="onDelete(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </section>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="560px" :close-on-click-modal="false">
      <div v-if="dialogMode === 'edit'" class="dialog-target">
        词条 #{{ editingId }}，所属分组：{{ currentGroupCode }}
      </div>
      <el-form class="dialog-form" label-width="110px" @submit.prevent="onSubmit">
        <el-form-item label="word">
          <el-input v-model="form.word" maxlength="100" show-word-limit clearable
                    placeholder="必填，最长 100 个字符" />
        </el-form-item>
        <el-form-item label="definition">
          <el-input v-model="form.definition" type="textarea" :rows="3" maxlength="500" show-word-limit clearable
                    placeholder="选填，最长 500 个字符；dish_category 组形如 code:meat" />
        </el-form-item>
        <el-form-item label="sortOrder">
          <el-input-number v-model="form.sortOrder" :precision="0" :step="10" controls-position="right" />
        </el-form-item>
      </el-form>
      <p class="dialog-note">
        提交成功后由服务端自动清除该组缓存，本页重拉词条与分组计数，不本地改行。
        sortOrder 清空表示本次不下发（新增时服务端按 0 落库）。
      </p>
      <template #footer>
        <el-button size="small" :disabled="submitting" @click="dialogVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="primary" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 字典管理（F-108 §0.7 片 2）：结构与错误处理照 MerchantApplies.vue / StoreApplies.vue。
//   读 = GET /api/admin/dict/groups + GET /api/admin/dict/{group}/items（后者不分页）
//   写 = POST /api/admin/dict/{group}/items、PUT /api/admin/dict/items/{id}、
//        DELETE /api/admin/dict/items/{id}、PUT /api/admin/dict/groups/{code}
//   缓存 = POST /api/admin/dict/{group}/evict（§0.4 既有端点；写操作成功后服务端已自动 evict）
// 纪律：错误一律只念请求层透出的后端 message（ElMessage），不预置猜原因的提示表；
// 写成功后一律重拉（分组 + 词条），绝不本地改行——引用保护这类判定只在服务端。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAdminDictGroups,
  getAdminDictItems,
  createAdminDictItem,
  updateAdminDictItem,
  deleteAdminDictItem,
  updateAdminDictGroup,
  evictAdminDict,
  syncAdminDictGroup
} from '../api'

// 与后端 DICT_GROUP_DISH_CATEGORY 同一枚 code：本页只用它决定两条提示的显隐，判定本身仍在服务端。
const GROUP_DISH_CATEGORY = 'dish_category'

const groups = ref([])
const groupsLoading = ref(false)
const groupsError = ref('')
const currentGroupCode = ref('')

const items = ref([])
const itemsLoading = ref(false)
const itemsError = ref('')
// 快速切组时丢弃旧请求的回包：只有最后一次发起的读取允许落屏。
let itemsRequestSeq = 0

const submitting = ref(false)
const togglingCode = ref('')
// 写失败的后端原文留在页面上（ElMessage 会消失，这一条不消失）；只放服务端 message，不补解释。
const writeResult = ref(null)

const dialogVisible = ref(false)
const dialogMode = ref('create')
const editingId = ref(null)
const form = ref({ word: '', definition: '', sortOrder: 0 })

const currentGroup = computed(() =>
  groups.value.find((g) => g.code === currentGroupCode.value) || null)
const dialogTitle = computed(() => (dialogMode.value === 'create'
  ? '新增词条'
  : '编辑词条' + (editingId.value != null ? ' #' + editingId.value : '')))

async function loadGroups() {
  // 返回值只服务 reloadAll：被并发调用挡下时回 false，词条读取交给真正拿到分组的那一次。
  if (groupsLoading.value) return false
  groupsLoading.value = true
  groupsError.value = ''
  try {
    const envelope = await getAdminDictGroups()
    groups.value = Array.isArray(envelope && envelope.data) ? envelope.data : []
    // 当前选中组被删掉/改名时退回第一组；组列表为空则清空右侧。
    if (!groups.value.some((g) => g.code === currentGroupCode.value)) {
      currentGroupCode.value = groups.value.length ? groups.value[0].code : ''
    }
    return true
  } catch (err) {
    const e = err || {}
    groups.value = []
    currentGroupCode.value = ''
    groupsError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
    return false
  } finally {
    groupsLoading.value = false
  }
}

// 首屏与分组读取失败后的「重试」共用：先分组、再当前组词条（只重拉分组会让右侧停在空表）。
async function reloadAll() {
  const ok = await loadGroups()
  if (ok && currentGroupCode.value) await loadItems(currentGroupCode.value)
}

// 写后刷新用：失败不清屏（避免一次瞬时读失败把刚写成功的界面清空），但错误照样发出。
async function refreshGroups() {
  try {
    const envelope = await getAdminDictGroups()
    const list = envelope && envelope.data
    if (!Array.isArray(list)) return
    groups.value = list
    if (!groups.value.some((g) => g.code === currentGroupCode.value)) {
      currentGroupCode.value = groups.value.length ? groups.value[0].code : ''
    }
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  }
}

async function loadItems(groupCode) {
  if (!groupCode) {
    items.value = []
    itemsError.value = ''
    itemsLoading.value = false
    return
  }
  const seq = ++itemsRequestSeq
  itemsLoading.value = true
  itemsError.value = ''
  items.value = []
  try {
    const envelope = await getAdminDictItems(groupCode)
    if (seq !== itemsRequestSeq) return
    items.value = Array.isArray(envelope && envelope.data) ? envelope.data : []
  } catch (err) {
    if (seq !== itemsRequestSeq) return
    const e = err || {}
    items.value = []
    itemsError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    if (seq === itemsRequestSeq) itemsLoading.value = false
  }
}

function selectGroup(code) {
  if (!code || code === currentGroupCode.value || submitting.value) return
  currentGroupCode.value = code
  writeResult.value = null
  loadItems(code)
}

// 分组启停：开关是受控的（:model-value 单向），请求成功后才把本地值翻过去；
// 失败时开关保持原态，错误只念后端 message。
async function onToggleGroup(group, checked) {
  if (!group || submitting.value || togglingCode.value) return
  const enabled = checked ? 1 : 0
  togglingCode.value = group.code
  writeResult.value = null
  try {
    const envelope = await updateAdminDictGroup(group.code, { enabled })
    if (envelope && envelope.message) ElMessage.success(envelope.message)
    group.enabled = enabled
    await refreshGroups()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
    writeResult.value = { title: '分组更新未完成', serverText: e.message || '' }
  } finally {
    togglingCode.value = ''
  }
}

function openCreate() {
  if (!currentGroup.value || submitting.value || itemsLoading.value) return
  dialogMode.value = 'create'
  editingId.value = null
  form.value = { word: '', definition: '', sortOrder: 0 }
  dialogVisible.value = true
}

function openEdit(row) {
  if (!currentGroup.value || submitting.value || itemsLoading.value) return
  const sort = Number(row.sortOrder)
  dialogMode.value = 'edit'
  editingId.value = row.id
  form.value = {
    word: row.word == null ? '' : String(row.word),
    definition: row.definition == null ? '' : String(row.definition),
    sortOrder: Number.isFinite(sort) ? sort : 0
  }
  dialogVisible.value = true
}

// 表单错误（词条为空 / 超长 / 同组重名）不做本地拦截也不自造文案：
// 直接提交，由服务端 400 的 message 原样回到本页。
async function onSubmit() {
  if (submitting.value) return
  const word = String(form.value.word == null ? '' : form.value.word).trim()
  const definition = String(form.value.definition == null ? '' : form.value.definition)
  const rawSort = form.value.sortOrder
  const sortOrder = rawSort === null || rawSort === undefined || rawSort === ''
    ? null
    : Number(rawSort)
  submitting.value = true
  writeResult.value = null
  try {
    let envelope
    if (dialogMode.value === 'create') {
      envelope = await createAdminDictItem(currentGroupCode.value, {
        word,
        definition,
        sortOrder: Number.isFinite(sortOrder) ? sortOrder : null
      })
    } else {
      envelope = await updateAdminDictItem(editingId.value, {
        word,
        definition,
        sortOrder: Number.isFinite(sortOrder) ? sortOrder : null
      })
    }
    if (envelope && envelope.message) ElMessage.success(envelope.message)
    dialogVisible.value = false
    await loadItems(currentGroupCode.value)
    await refreshGroups()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
    writeResult.value = {
      title: dialogMode.value === 'create' ? '新增未完成' : '编辑未完成',
      serverText: e.message || ''
    }
  } finally {
    submitting.value = false
  }
}

async function onDelete(row) {
  if (submitting.value || itemsLoading.value) return
  const isDishCategory = currentGroupCode.value === GROUP_DISH_CATEGORY
  const message = '确认删除词条「' + row.word + '」（id ' + row.id + '）？\n\n'
    + (isDishCategory
      ? 'dish_category 组有引用保护：该分类下仍有商品时服务端会回 400 并拒绝删除，本页会原样展示后端文案。\n'
      : '该组无引用保护，删除后前端会回退 FALLBACK。\n')
    + '删除成功后服务端自动清除该组缓存，本页重拉词条与分组计数；本页不提供撤销。'
  try {
    await ElMessageBox.confirm(message, '请确认这笔删除', {
      type: 'warning',
      confirmButtonText: '确认删除',
      cancelButtonText: '取消（不发出任何请求）',
      closeOnClickModal: false,
      distinguishCancelAndClose: true,
      customClass: 'admin-write-confirm'
    })
  } catch (e) {
    // 点「取消」/关闭/Esc 一律 reject → 吞掉，不发出任何请求。
    return
  }
  submitting.value = true
  writeResult.value = null
  try {
    const envelope = await deleteAdminDictItem(row.id)
    if (envelope && envelope.message) ElMessage.success(envelope.message)
    await loadItems(currentGroupCode.value)
    await refreshGroups()
  } catch (err) {
    // dish_category 的引用保护 400 就走这里：ElMessage 与页内结果条都用 e.message 原文。
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
    writeResult.value = { title: '删除未完成', serverText: e.message || '' }
  } finally {
    submitting.value = false
  }
}

async function onEvict() {
  if (!currentGroupCode.value || submitting.value) return
  submitting.value = true
  writeResult.value = null
  try {
    const envelope = await evictAdminDict(currentGroupCode.value)
    if (envelope && envelope.message) ElMessage.success(envelope.message)
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
    writeResult.value = { title: '清除缓存未完成', serverText: e.message || '' }
  } finally {
    submitting.value = false
  }
}

// F-108 §0.8 上游同步（B 语义「只增不删」）：二次确认必须写明代价——只补本地没有的，
// 上游改名会造出「新名 + 旧名」两条，旧名要运营手工清理。成功 toast 念后端三个数字；
// 失败原样展示后端 message（上游不可用时就是 400「字典服务暂时不可用，请稍后重试」）。
async function onSyncFromUpstream() {
  if (!currentGroup.value || Number(currentGroup.value.enabled) !== 1 || submitting.value || itemsLoading.value) return
  const group = currentGroup.value
  const message = '确认从上游同步分组「' + group.name + '」（' + group.code + '）？\n\n'
    + '只补本地没有的词条，不动已有的；上游改名会表现为新名插入、旧名保留，需要手工清理旧名。\n'
    + '上游拉取失败时服务端会回 400，本地一行都不会变。'
  try {
    await ElMessageBox.confirm(message, '请确认同步', {
      type: 'warning',
      confirmButtonText: '确认同步',
      cancelButtonText: '取消（不发出任何请求）',
      closeOnClickModal: false,
      distinguishCancelAndClose: true,
      customClass: 'admin-write-confirm'
    })
  } catch (e) {
    // 点「取消」/关闭/Esc 一律 reject → 吞掉，不发出任何请求。
    return
  }
  submitting.value = true
  writeResult.value = null
  try {
    const envelope = await syncAdminDictGroup(group.code)
    const data = (envelope && envelope.data) || {}
    ElMessage.success('同步完成：上游共 ' + Number(data.upstreamTotal || 0) + ' 条，新增 '
      + Number(data.inserted || 0) + ' 条，跳过 ' + Number(data.skipped || 0) + ' 条')
    await loadItems(group.code)
    await refreshGroups()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
    writeResult.value = { title: '从上游同步未完成', serverText: e.message || '' }
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  await reloadAll()
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
.result-bar {
  margin-top: 12px;
  padding: 10px 14px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-left: 3px solid #f56c6c;
  border-radius: 6px;
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
  word-break: break-all;
}
.dict-layout {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  gap: 16px;
  margin-top: 16px;
  align-items: start;
}
.group-panel,
.items-panel {
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  padding: 14px;
}
.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 10px;
}
.panel-state {
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}
.panel-error {
  color: #f56c6c;
  word-break: break-all;
  margin-bottom: 8px;
}
.group-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.group-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  cursor: pointer;
  outline: none;
}
.group-item:hover {
  border-color: #c0c4cc;
}
.group-item.active {
  border-color: #e6a23c;
  background: #fdf6ec;
}
.group-name {
  font-size: 13px;
  font-weight: 600;
  color: #303133;
}
.group-code {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  word-break: break-all;
}
.group-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.group-count {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
}
.items-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}
.items-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.items-code {
  font-size: 11px;
  font-weight: 400;
  color: #909399;
  margin-left: 6px;
}
.items-meta {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}
.fallback-tip {
  margin-bottom: 10px;
  padding: 8px 12px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #b88230;
}
.table {
  background: #fff;
}
.empty {
  color: #909399;
  font-size: 13px;
}
.muted {
  color: #909399;
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
@media (max-width: 900px) {
  .dict-layout {
    grid-template-columns: 1fr;
  }
}
</style>
