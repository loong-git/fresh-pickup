<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">免费领活动</h2>
      <el-tag type="danger" effect="dark" size="small" class="mode-badge">本页会改数据</el-tag>
    </div>
    <p class="page-note">
      免费领活动（free_activity，单活动模型）与候选池（free_goods_pool）的运营配置页：
      活动配置读取 GET /api/admin/free/activity、保存走 PUT /api/admin/free/activity（只允许
      dishId / dailyQuota / threshold / status 四列）；候选池读取 GET /api/admin/free/pool、保存走
      PUT /api/admin/free/pool（全量替换）。商品下拉取 GET /api/admin/v2/dishes 的<b>在售</b>商品（onSale=1）。
    </p>

    <!-- 契约 §0.2 的硬红线：daily_claimed / daily_date 是领取链路的运行时计数器，管理端没有写入口。 -->
    <div class="counter-note">
      <b>dailyClaimed</b> 是领取链路的运行时计数，<b>本页不可修改</b>；它不是一个可配置的剩余量，改它会导致超发。
    </div>

    <div v-if="dishesError" class="options-error">
      在售商品列表读取失败：{{ dishesError }}
      <el-button size="small" :loading="dishesLoading" @click="loadDishes">重试</el-button>
    </div>
    <div v-if="dishesTruncated" class="options-note">
      在售商品超过本次已加载的 {{ onSaleDishes.length }} 件（最多翻 {{ DISH_MAX_PAGES }} 页、每页
      {{ DISH_PAGE_SIZE }} 件）；未出现在下拉里的商品无法在本页选择。
    </div>

    <!-- ==================== 上方：活动卡 + 编辑表单 ==================== -->
    <section class="panel">
      <div class="panel-head">
        <div>
          <div class="panel-title">活动配置</div>
          <div class="panel-sub">今日免费商品、已领/限量、满额门槛与上下线状态；保存后重拉本卡核对。</div>
        </div>
        <el-button size="small" :loading="activityLoading" :disabled="activityLoading || savingActivity"
                   @click="loadActivity">刷新</el-button>
      </div>

      <div v-if="activityLoading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">免费领活动读取中…</div>
      </div>

      <div v-else-if="activityError" class="state-box">
        <div class="state-title">读取失败</div>
        <!-- 只展示请求层透出的后端原文，本页不补猜原因。 -->
        <div class="state-desc">{{ activityError }}</div>
        <el-button size="small" @click="loadActivity">重试</el-button>
      </div>

      <template v-else-if="activity">
        <div class="metrics">
          <div class="metric">
            <div class="metric-label">今日免费商品</div>
            <div class="metric-value">{{ activity.dishName || '—' }}</div>
            <div class="metric-sub">dishId：{{ activity.dishId == null ? '—' : activity.dishId }}</div>
          </div>
          <div class="metric">
            <div class="metric-label">今日已领 / 限量</div>
            <div class="metric-value">{{ activity.dailyClaimed }} / {{ activity.dailyQuota }}</div>
            <div class="metric-sub">
              dailyClaimed / dailyQuota{{ Number(activity.dailyQuota) === 0 ? '（0 = 今日暂停领取）' : '' }}
            </div>
          </div>
          <div class="metric">
            <div class="metric-label">满额门槛</div>
            <div class="metric-value">{{ thresholdText }} 元</div>
            <div class="metric-sub">当日实付累计满此金额方可领取</div>
          </div>
          <div class="metric">
            <div class="metric-label">活动状态</div>
            <div class="metric-value">
              <el-tag :type="statusTagType" size="small" effect="light">{{ statusText }}</el-tag>
            </div>
            <div class="metric-sub">status：{{ activity.status || '—' }}</div>
          </div>
        </div>

        <!-- 表单只承载四列可管理字段；dailyClaimed 不在表单里，也没有对应输入控件。 -->
        <el-form class="edit-form" label-width="96px" @submit.prevent="onSaveActivity">
          <el-form-item label="免费商品">
            <el-select v-model="activityForm.dishId" class="full-width" filterable
                       :loading="dishesLoading" :disabled="dishesLoading || savingActivity || !dishesLoaded"
                       placeholder="请选择在售商品">
              <el-option v-for="d in onSaleDishes" :key="d.id"
                         :label="d.name || ('#' + d.id)" :value="d.id" />
            </el-select>
            <div v-if="currentDishOffSale" class="field-warn">
              当前免费商品「{{ activity.dishName || ('#' + activity.dishId) }}」已不在在售列表（可能已下架），
              下拉中不可选；不重选时保存不会改动该商品。
            </div>
          </el-form-item>
          <el-form-item label="每日限量">
            <el-input-number v-model="activityForm.dailyQuota" :min="0" :precision="0" :step="1"
                             controls-position="right" :disabled="savingActivity" />
            <span class="form-tip">≥0，0 = 暂停领取</span>
          </el-form-item>
          <el-form-item label="满额门槛">
            <el-input-number v-model="activityForm.threshold" :min="0" :precision="2" :step="1"
                             controls-position="right" :disabled="savingActivity" />
            <span class="form-tip">元，≥0</span>
          </el-form-item>
          <el-form-item label="活动状态">
            <el-switch v-model="activityForm.status" active-value="online" inactive-value="offline"
                       active-text="上线（online）" inactive-text="下线（offline）" :disabled="savingActivity" />
          </el-form-item>
        </el-form>

        <div class="save-row">
          <el-button size="small" type="primary" :loading="savingActivity"
                     :disabled="activityLoading || savingActivity" @click="onSaveActivity">保存活动配置</el-button>
          <span class="save-hint">只提交与当前值不同的字段；成功提示与失败原因都原样取自服务端 message。</span>
        </div>
      </template>
    </section>

    <!-- ==================== 下方：候选池多选 ==================== -->
    <section class="panel">
      <div class="panel-head">
        <div>
          <div class="panel-title">候选池</div>
          <div class="panel-sub">
            C 端免费领的可选商品（只列在售商品）；保存为全量替换，池内只保留本次勾选项。
          </div>
        </div>
        <el-button size="small" :loading="poolLoading" :disabled="poolLoading || savingPool"
                   @click="loadPool">刷新</el-button>
      </div>

      <div v-if="poolLoading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">候选池读取中…</div>
      </div>

      <div v-else-if="poolError" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ poolError }}</div>
        <el-button size="small" @click="loadPool">重试</el-button>
      </div>

      <template v-else>
        <!-- 池里可能留有已下架商品：后端原样返回 onSale=0，这里点名提示，保存后这些行会被移除。 -->
        <div v-if="offSalePoolRows.length" class="pool-warn">
          候选池中有 {{ offSalePoolRows.length }} 件已下架/失效商品：{{ offSalePoolText }}。
          本页只列在售商品，保存后这些行会被移除。
        </div>

        <div v-if="dishesError" class="empty-line">
          在售商品列表未就绪，候选池暂不可编辑（请先在上方重试加载在售商品）。
        </div>
        <div v-else-if="!onSaleDishes.length" class="empty-line">暂无可选的在售商品。</div>
        <el-checkbox-group v-else v-model="poolSelection" class="pool-group"
                           :disabled="savingPool || dishesLoading">
          <el-checkbox v-for="d in onSaleDishes" :key="d.id" :value="d.id" class="pool-item">
            {{ d.name || ('#' + d.id) }} <span class="muted">#{{ d.id }}</span>
          </el-checkbox>
        </el-checkbox-group>

        <div class="save-row">
          <el-button size="small" type="primary" :loading="savingPool"
                     :disabled="poolLoading || dishesLoading || !dishesLoaded || savingPool"
                     @click="onSavePool">保存候选池</el-button>
          <span class="save-hint">已选 {{ poolSelection.length }} 件；至少保留 1 件，空池会被前端拦下（服务端同样 400）。</span>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup>
// 免费领活动管理（F-109 §0.3）：结构与错误处理照 MerchantApplies.vue / Dicts.vue。
//   活动 读 = GET /api/admin/free/activity，写 = PUT /api/admin/free/activity（部分更新四列）
//   候选池 读 = GET /api/admin/free/pool，写 = PUT /api/admin/free/pool（全量替换 dishIds）
//   选品 = GET /api/admin/v2/dishes?onSale=1（该端点既有出参：id / name / onSale，分页 {list,total,...}）
// 纪律：
//   1) dailyClaimed 是领取链路的运行时计数，页面只读展示，请求体里绝不出现 dailyClaimed / dailyDate；
//   2) 两个保存各自独立按钮与独立 loading（savingActivity / savingPool），互不禁用；
//   3) 错误一律 ElMessage + 请求层透出的后端 message 原文，本页不预置猜原因的提示表；
//   4) 保存成功后一律重拉（活动→loadActivity，池→loadPool），绝不本地改行。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  getAdminFreeActivity,
  updateAdminFreeActivity,
  getAdminFreePool,
  updateAdminFreePool,
  getAdminDishes
} from '../api'

// GET /api/admin/v2/dishes 允许的最大页大小是 100（越界 400）；在售商品跨页时逐页取全。
const DISH_PAGE_SIZE = 100
// 防御性上界：避免商品表异常时无限翻页（每页 100 件，20 页 = 2000 件）。
const DISH_MAX_PAGES = 20
// 与后端 FREE_POOL_EMPTY_MSG 同文：前端空池拦截复述服务端原话，不另造一句。
const FREE_POOL_EMPTY_TEXT = '候选池至少保留一件商品'

const activity = ref(null)
const activityLoading = ref(false)
const activityError = ref('')
const savingActivity = ref(false)
// 表单四键与 PUT body 一一对应；dailyClaimed / dailyDate 刻意不在这里。
const activityForm = ref({ dishId: null, dailyQuota: 0, threshold: 0, status: 'offline' })

const poolRows = ref([])
const poolLoading = ref(false)
const poolError = ref('')
const savingPool = ref(false)
const poolSelection = ref([])

const onSaleDishes = ref([])
const dishesLoading = ref(false)
const dishesError = ref('')
const dishesLoaded = ref(false)
const dishesTruncated = ref(false)

const thresholdText = computed(() => {
  const t = activity.value ? activity.value.threshold : null
  if (t === null || t === undefined || t === '') return '—'
  const n = Number(t)
  return Number.isFinite(n) ? String(n) : String(t)
})

const statusText = computed(() => {
  const s = activity.value ? activity.value.status : ''
  if (s === 'online') return '进行中（online）'
  if (s === 'offline') return '已下线（offline）'
  return s ? String(s) : '—'
})

const statusTagType = computed(() => (activity.value && activity.value.status === 'online' ? 'success' : 'info'))

// 当前活动商品是否已不在在售列表：只在商品列表成功加载后判定，避免把「还没加载」误报成「已下架」。
// 运营一旦在下拉里重选了在售商品（form.dishId 非空），这条针对「已保存商品」的提示就隐藏。
const currentDishOffSale = computed(() => {
  const a = activity.value
  if (!a || a.dishId == null || !dishesLoaded.value) return false
  if (toNumberOrNull(activityForm.value.dishId) !== null) return false
  return !onSaleDishes.value.some((d) => Number(d.id) === Number(a.dishId))
})

// 池内 onSale!=1 的行（含商品已删除导致 dishName=null 的脏行）：只提示，不可勾选。
const offSalePoolRows = computed(() => poolRows.value.filter((row) => !row || Number(row.onSale) !== 1))
const offSalePoolText = computed(() => offSalePoolRows.value
  .map((row) => (row && row.dishName ? String(row.dishName) : '#' + (row && row.dishId != null ? row.dishId : '?')))
  .join('、'))

function toNumberOrNull(value) {
  if (value === null || value === undefined || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

function fillActivityForm(data) {
  const quota = toNumberOrNull(data.dailyQuota)
  const threshold = toNumberOrNull(data.threshold)
  activityForm.value = {
    dishId: toNumberOrNull(data.dishId),
    dailyQuota: quota === null ? 0 : quota,
    threshold: threshold === null ? 0 : threshold,
    // 库里的合法值只有 online/offline；异常空值按 offline 呈现，不凭空造第三种态。
    status: data.status === 'online' ? 'online' : 'offline'
  }
}

// 商品下拉刷新后，若表单里选中的商品已不在在售列表，清空选择：
// 保存时「未变化的 dishId」本来就不下发，因此不会把已下架商品重新写回活动。
function reconcileActivityFormDish() {
  if (!dishesLoaded.value) return
  const id = toNumberOrNull(activityForm.value.dishId)
  if (id === null) return
  if (!onSaleDishes.value.some((d) => Number(d.id) === id)) {
    activityForm.value.dishId = null
  }
}

function fillPoolSelection() {
  const ids = []
  poolRows.value.forEach((row) => {
    if (!row || row.dishId == null) return
    if (Number(row.onSale) !== 1) return
    ids.push(Number(row.dishId))
  })
  poolSelection.value = ids
  reconcilePoolSelection()
}

// 只保留在售下拉里存在的 id：下架/失效行在保存时会被全量替换自然移除。
function reconcilePoolSelection() {
  if (!dishesLoaded.value) return
  const allowed = new Set(onSaleDishes.value.map((d) => Number(d.id)))
  poolSelection.value = poolSelection.value.filter((id) => allowed.has(Number(id)))
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
    // 防御性去重（同名 id 只留一条），并保持服务端返回顺序。
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
    reconcileActivityFormDish()
    reconcilePoolSelection()
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

async function loadActivity() {
  if (activityLoading.value) return
  activityLoading.value = true
  activityError.value = ''
  try {
    const envelope = await getAdminFreeActivity()
    const data = (envelope && envelope.data && typeof envelope.data === 'object') ? envelope.data : {}
    activity.value = data
    fillActivityForm(data)
    reconcileActivityFormDish()
  } catch (err) {
    const e = err || {}
    activity.value = null
    activityError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    activityLoading.value = false
  }
}

async function loadPool() {
  if (poolLoading.value) return
  poolLoading.value = true
  poolError.value = ''
  try {
    const envelope = await getAdminFreePool()
    poolRows.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
    fillPoolSelection()
  } catch (err) {
    const e = err || {}
    poolRows.value = []
    poolSelection.value = []
    poolError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    poolLoading.value = false
  }
}

function validateActivityForm() {
  const rawQuota = activityForm.value.dailyQuota
  if (rawQuota === null || rawQuota === undefined || rawQuota === '') {
    ElMessage.warning('请填写每日限量（≥0，0 = 暂停）')
    return false
  }
  const quota = Number(rawQuota)
  if (!Number.isInteger(quota) || quota < 0) {
    ElMessage.warning('每日限量需为不小于 0 的整数（0 = 暂停）')
    return false
  }
  const rawThreshold = activityForm.value.threshold
  if (rawThreshold === null || rawThreshold === undefined || rawThreshold === '') {
    ElMessage.warning('请填写满额门槛（≥0）')
    return false
  }
  const threshold = Number(rawThreshold)
  if (!Number.isFinite(threshold) || threshold < 0) {
    ElMessage.warning('满额门槛需为不小于 0 的数值')
    return false
  }
  return true
}

// 只收集与服务器当前值不同的四列；四列都没变时不发请求（避免一次「无更新字段」的 400）。
function buildActivityPatch() {
  const current = activity.value || {}
  const form = activityForm.value
  const patch = {}

  const formDishId = toNumberOrNull(form.dishId)
  const currentDishId = toNumberOrNull(current.dishId)
  if (formDishId !== null && formDishId !== currentDishId) patch.dishId = formDishId

  const formQuota = Number(form.dailyQuota)
  const currentQuota = toNumberOrNull(current.dailyQuota)
  if (formQuota !== (currentQuota === null ? 0 : currentQuota)) patch.dailyQuota = formQuota

  const formThreshold = Number(form.threshold)
  const currentThreshold = toNumberOrNull(current.threshold)
  if (formThreshold !== (currentThreshold === null ? 0 : currentThreshold)) patch.threshold = formThreshold

  const currentStatus = current.status === 'online' ? 'online' : 'offline'
  if (form.status !== currentStatus) patch.status = form.status

  return patch
}

async function onSaveActivity() {
  if (savingActivity.value || activityLoading.value) return
  if (!validateActivityForm()) return
  const patch = buildActivityPatch()
  if (Object.keys(patch).length === 0) {
    ElMessage.warning('没有需要保存的修改')
    return
  }
  savingActivity.value = true
  try {
    const envelope = await updateAdminFreeActivity(patch)
    // 成功文案取服务端 message；兜底串是契约里逐字写死的同一句（不是本页另造的文案）。
    ElMessage.success((envelope && envelope.message) || '免费领活动已更新')
    // 成功与否一律以重拉为准；loadActivity 自己吞读失败并落 activityError，不会把保存误判为失败。
    await loadActivity()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    savingActivity.value = false
  }
}

async function onSavePool() {
  if (savingPool.value || poolLoading.value) return
  if (!dishesLoaded.value) {
    ElMessage.warning('在售商品列表尚未就绪，请先重试加载')
    return
  }
  if (poolSelection.value.length === 0) {
    // 前端先挡一次空池（服务端同样会 400）；文案用服务端原话。
    ElMessage.warning(FREE_POOL_EMPTY_TEXT)
    return
  }
  const selected = new Set()
  poolSelection.value.forEach((id) => {
    const n = Number(id)
    if (Number.isFinite(n)) selected.add(n)
  })
  const available = new Set(onSaleDishes.value.map((d) => Number(d.id)))
  const ids = []
  const seen = new Set()
  // 先在售列表校验、再保留池子现有顺序（服务端按入参顺序插入=C 端展示顺序，保存不该无端重排）。
  poolRows.value.forEach((row) => {
    if (!row || row.dishId == null) return
    const id = Number(row.dishId)
    if (!available.has(id) || !selected.has(id) || seen.has(id)) return
    seen.add(id)
    ids.push(id)
  })
  // 新勾选的商品按在售列表顺序追加到末尾。
  onSaleDishes.value.forEach((d) => {
    const id = Number(d.id)
    if (!selected.has(id) || seen.has(id)) return
    seen.add(id)
    ids.push(id)
  })
  if (ids.length === 0) {
    ElMessage.warning(FREE_POOL_EMPTY_TEXT)
    return
  }
  savingPool.value = true
  try {
    const envelope = await updateAdminFreePool(ids)
    ElMessage.success((envelope && envelope.message) || '候选池已更新')
    await loadPool()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    savingPool.value = false
  }
}

onMounted(() => {
  // 三路读互不阻塞：任一失败只影响自己那一块，其余界面照常可用。
  loadDishes()
  loadActivity()
  loadPool()
})
</script>

<style scoped>
.counter-note {
  margin-top: 12px;
  padding: 10px 14px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-left: 3px solid #e6a23c;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.7;
  color: #b88230;
}
.options-error {
  margin-top: 12px;
  padding: 10px 14px;
  background: #fef0f0;
  border: 1px solid #fde2e2;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #f56c6c;
  word-break: break-all;
}
.options-note {
  margin-top: 12px;
  padding: 10px 14px;
  background: #f5f6f8;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #606266;
}
.panel {
  margin-top: 16px;
  padding: 16px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}
.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}
.panel-sub {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}
.metric {
  padding: 10px 12px;
  background: #f5f6f8;
  border-radius: 6px;
}
.metric-label {
  font-size: 12px;
  color: #909399;
}
.metric-value {
  margin-top: 4px;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.4;
  color: #303133;
  word-break: break-all;
}
.metric-sub {
  margin-top: 2px;
  font-size: 11px;
  line-height: 1.5;
  color: #909399;
}
.edit-form {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.edit-form :deep(.el-form-item) {
  margin-bottom: 14px;
}
.full-width {
  width: 100%;
}
.form-tip {
  margin-left: 8px;
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
}
.field-warn {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: #e6a23c;
}
.pool-warn {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: #b88230;
  word-break: break-all;
}
.empty-line {
  font-size: 12px;
  line-height: 1.7;
  color: #909399;
}
.pool-group {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
}
.pool-item {
  margin-right: 0;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.save-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}
.save-hint {
  font-size: 12px;
  line-height: 1.6;
  color: #909399;
}
@media (max-width: 900px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .edit-form {
    grid-template-columns: 1fr;
  }
}
</style>
