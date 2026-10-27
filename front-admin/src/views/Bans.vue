<template>
  <div class="page">
    <!-- 读写可辨：本页两个 tab 的动作都会真的钉状态位（PUT .../status），所以常驻「本页会改数据」。 -->
    <div class="page-head">
      <h2 class="page-title">封禁管理</h2>
      <el-tag type="danger" effect="dark" size="small">本页会改数据</el-tag>
    </div>
    <!-- F-122 §4.1：页头横幅三段逐字（契约原文，含加粗处），不得改写口径。 -->
    <div class="ban-banner">
      <p>封禁用户：登录即拒，已签发的登录态也会立即失效（需登录的请求一律 403）。</p>
      <p>封禁商家：C 端商品<b>照常展示</b>，但该商家商品的<b>下单会被拒绝</b>；商家端写操作与看板/营销接口 403（备货计划/供货明细按既有口径仍可读）。</p>
      <p>封禁<b>不会</b>自动下架商品、清秒杀场或移出免费领池；在途订单请用「订单管理」处理。</p>
    </div>
    <p class="page-note">
      本页会修改数据：用户 tab 写 user.status（active/banned），商家 tab 写 merchant.status（active/suspended），各只改一列。<br />
      列表读 GET /api/admin/users 与 GET /api/admin/merchants（筛选均为精确等值，分页 pageNum/pageSize）；
      写走 PUT /api/admin/users/{id}/status 与 PUT /api/admin/merchants/{id}/status（body {action: ban|unban}）。<br />
      两域独立：封 user 不级联其名下商家，封商家也不改其账号的 user 状态（该手机号仍可登录 C 端）；已清退（terminated）商家不可经本页变更状态。<br />
      手机号/联系电话由服务端脱敏，本页原样显示、不拼回明文；时间列是服务端定形串，原样显示、不做二次格式化。
    </p>

    <el-tabs v-model="activeTab" class="ban-tabs" @tab-change="onTabChange">
      <!-- ==================== 用户 tab ==================== -->
      <el-tab-pane label="用户" name="users">
        <div class="toolbar">
          <el-input v-model="userFilters.userId" size="small" class="filter-input filter-input--id" type="number"
                    min="1" clearable
                    :disabled="userLoading || userSubmitting"
                    placeholder="用户ID（精确等值，正整数）" @keyup.enter="onUserFilterChange" />
          <el-input v-model="userFilters.phone" size="small" class="filter-input" clearable
                    :disabled="userLoading || userSubmitting"
                    placeholder="手机号（精确等值）" @keyup.enter="onUserFilterChange" />
          <el-radio-group v-model="userFilters.status" size="small"
                          :disabled="userLoading || userSubmitting" @change="onUserFilterChange">
            <el-radio-button v-for="opt in USER_STATUS_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
          <el-button size="small" type="primary" :disabled="userLoading || userSubmitting" @click="onUserFilterChange">查询</el-button>
          <el-button size="small" :disabled="userLoading || userSubmitting" @click="onUserReset">重置</el-button>
          <!-- A4/P4-7 §3：数字只由成功态决定。loading 说「正在统计…」、失败说「统计不可用」，
               绝不把 catch 里为分页器保留的 0 念成「确实没有」。 -->
          <span class="toolbar-hint">{{ userTotalText }}</span>
        </div>

        <div v-if="userLoading" class="state-box">
          <div class="state-title">正在加载</div>
          <div class="state-desc">用户列表读取中…</div>
        </div>
        <div v-else-if="userError" class="state-box">
          <div class="state-title">读取失败</div>
          <div class="state-desc">{{ userError }}</div>
          <el-button size="small" @click="loadUsers">重试</el-button>
        </div>
        <template v-else>
          <el-table :data="userRows" border stripe size="small" class="table" max-height="calc(100vh - 520px)">
            <template #empty>
              <span class="muted">当前筛选下没有用户</span>
            </template>
            <el-table-column prop="id" label="ID" width="90" />
            <!-- phone 是服务端 PhoneMask 的脱敏值，原样渲染：不截断、不拼接、不 Number()。 -->
            <el-table-column label="手机号" min-width="150">
              <template #default="{ row }">
                <span v-if="row.phone" class="mono">{{ row.phone }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="110">
              <template #default="{ row }">{{ roleText(row.role) }}</template>
            </el-table-column>
            <el-table-column label="商家ID" width="100">
              <template #default="{ row }">
                <span v-if="row.merchantId == null" class="muted">—</span>
                <span v-else>#{{ row.merchantId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="userStatusTagType(row.status)" size="small" effect="light">{{ userStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <!-- createdAt 是服务端 DATE_FORMAT 定形的 "YYYY-MM-DD HH:mm:ss" 串，直接显示，不做二次格式化 -->
            <el-table-column prop="createdAt" label="注册时间" min-width="170" />
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'active'" size="small" type="danger" plain
                           :disabled="userSubmitting || userLoading" @click="onUserBan(row)">封禁</el-button>
                <el-button v-else-if="row.status === 'banned'" size="small" type="success" plain
                           :disabled="userSubmitting || userLoading" @click="onUserUnban(row)">解封</el-button>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="userPageNum"
              v-model:page-size="userPageSize"
              :total="userTotal"
              :page-sizes="PAGE_SIZES"
              layout="total, sizes, prev, pager, next"
              background
              @size-change="onUserSizeChange"
              @current-change="onUserPageChange"
            />
          </div>
        </template>
      </el-tab-pane>

      <!-- ==================== 商家 tab ==================== -->
      <!-- lazy：首次切入才挂载表格内容（与「首次切入才拉列表」配套），也避开 el-table 在隐藏容器里量不到宽度、
           固定列错位的既有毛病 -->
      <el-tab-pane label="商家" name="merchants" lazy>
        <div class="toolbar">
          <el-radio-group v-model="merchantFilters.status" size="small"
                          :disabled="merchantLoading || merchantSubmitting" @change="onMerchantFilterChange">
            <el-radio-button v-for="opt in MERCHANT_STATUS_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </el-radio-button>
          </el-radio-group>
          <el-button size="small" type="primary" :disabled="merchantLoading || merchantSubmitting" @click="onMerchantFilterChange">查询</el-button>
          <el-button size="small" :disabled="merchantLoading || merchantSubmitting" @click="onMerchantReset">重置</el-button>
          <!-- A4/P4-7 §3：同上，失败时不把 0 当成「确实没有商家」。 -->
          <span class="toolbar-hint">{{ merchantTotalText }}</span>
        </div>

        <div v-if="merchantLoading" class="state-box">
          <div class="state-title">正在加载</div>
          <div class="state-desc">商家列表读取中…</div>
        </div>
        <div v-else-if="merchantError" class="state-box">
          <div class="state-title">读取失败</div>
          <div class="state-desc">{{ merchantError }}</div>
          <el-button size="small" @click="loadMerchants">重试</el-button>
        </div>
        <template v-else>
          <el-table :data="merchantRows" border stripe size="small" class="table" max-height="calc(100vh - 520px)">
            <template #empty>
              <span class="muted">当前筛选下没有商家</span>
            </template>
            <el-table-column prop="id" label="ID" width="80" />
            <el-table-column label="商家名称" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.name }}</template>
            </el-table-column>
            <!-- contactPhone 是服务端 PhoneMask 的脱敏值，原样渲染。 -->
            <el-table-column label="联系电话" min-width="150">
              <template #default="{ row }">
                <span v-if="row.contactPhone" class="mono">{{ row.contactPhone }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="联系人" min-width="120" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.contactName">{{ row.contactName }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-tag :type="merchantStatusTagType(row.status)" size="small" effect="light">{{ merchantStatusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="创建时间" min-width="170" />
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.status === 'active'" size="small" type="danger" plain
                           :disabled="merchantSubmitting || merchantLoading" @click="onMerchantBan(row)">封禁</el-button>
                <el-button v-else-if="row.status === 'suspended'" size="small" type="success" plain
                           :disabled="merchantSubmitting || merchantLoading" @click="onMerchantUnban(row)">解封</el-button>
                <!-- terminated 行按钮置灰：已清退不可经本片端点变更封禁状态（服务端 400 同句）。
                     title 挂在外层 span 上——disabled 的按钮在浏览器里不派发鼠标事件，原生 title 弹不出来。 -->
                <span v-else-if="row.status === 'terminated'" class="btn-disabled-wrap" title="已清退商家不可变更封禁状态">
                  <el-button size="small" type="danger" plain disabled title="已清退商家不可变更封禁状态">封禁</el-button>
                </span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager">
            <el-pagination
              v-model:current-page="merchantPageNum"
              v-model:page-size="merchantPageSize"
              :total="merchantTotal"
              :page-sizes="PAGE_SIZES"
              layout="total, sizes, prev, pager, next"
              background
              @size-change="onMerchantSizeChange"
              @current-change="onMerchantPageChange"
            />
          </div>
        </template>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
// 封禁管理（F-122 §4.1）：结构与错误处理照 Stores.vue / MerchantApplies.vue（同组运营写侧页）。
//   读 = GET /api/admin/users（userId/phone/status/pageNum/pageSize）
//        GET /api/admin/merchants（status/pageNum/pageSize）
//        出参统一 {list,total,pageNum,pageSize,filters}；行键：
//        用户 {id,phone,role,merchantId,status,createdAt}；商家 {id,name,contactPhone,contactName,status,createdAt}
//   写 = PUT /api/admin/users/{id}/status、PUT /api/admin/merchants/{id}/status，body {action:'ban'|'unban'}
// 纪律：
//   1) 两个 tab 各持一份筛选/分页/loading/error 状态，互不干扰；商家 tab 首次切入才拉取（省一次首屏请求）；
//   2) 四条二次确认文案逐字取自契约 §4.1（用户封禁/解封、商家封禁/解封），不得改写口径；
//   3) 成功 toast 用后端 message，随后**重拉列表**核对，绝不本地改行；失败只呈现请求层透出的后端 message；
//   4) 商家 terminated 行不给可点按钮（置灰 + title「已清退商家不可变更封禁状态」）——服务端同样 400 同句；
//   5) 两域独立、零级联：本页只发状态位，不碰 token/商品/秒杀/免费领池/订单，口径见页头横幅。
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getAdminUsers,
  getAdminMerchants,
  updateAdminUserStatus,
  updateAdminMerchantStatus
} from '../api'

// P4-13 D4（A11）：限流页「去封禁」跳来时带 userId；只读进入本页时的这一份 query，不新增 watch。
const route = useRoute()

const PAGE_SIZES = [10, 20, 50, 100]

// 与后端 USER_STATUSES / MERCHANT_STATUSES 同集合；空串 = 全部（withQuery 挡下，不下发 status）。
const USER_STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '正常', value: 'active' },
  { label: '已封禁', value: 'banned' }
]
const MERCHANT_STATUS_OPTIONS = [
  { label: '全部', value: '' },
  { label: '正常营业', value: 'active' },
  { label: '停业整顿', value: 'suspended' },
  { label: '已清退', value: 'terminated' }
]

// 状态文案与 tag 色（契约 §4.1 逐字：user active=正常 success / banned=已封禁 danger；
// merchant active=正常营业 success / suspended=停业整顿 warning / terminated=已清退 info）。
const USER_STATUS_TEXT = { active: '正常', banned: '已封禁' }
const USER_STATUS_TAG = { active: 'success', banned: 'danger' }
const MERCHANT_STATUS_TEXT = { active: '正常营业', suspended: '停业整顿', terminated: '已清退' }
const MERCHANT_STATUS_TAG = { active: 'success', suspended: 'warning', terminated: 'info' }

// user.role 只有 user/merchant 两个启用值（init.sql 列注释：admin 预留不启用）；
// 未知值原样展示，绝不猜一个中文名出来。
const ROLE_TEXT = { user: '普通用户', merchant: '商家' }

const userStatusText = (v) => USER_STATUS_TEXT[v] || (v == null ? '—' : String(v))
const userStatusTagType = (v) => USER_STATUS_TAG[v] || 'info'
const merchantStatusText = (v) => MERCHANT_STATUS_TEXT[v] || (v == null ? '—' : String(v))
const merchantStatusTagType = (v) => MERCHANT_STATUS_TAG[v] || 'info'
const roleText = (v) => (v == null || v === '' ? '—' : (ROLE_TEXT[v] || String(v)))

const activeTab = ref('users')

// ==================== 用户 tab 状态（独立一份） ====================
const userRows = ref([])
const userTotal = ref(0)
const userPageNum = ref(1)
const userPageSize = ref(20)
const userLoading = ref(false)
const userError = ref('')
const userSubmitting = ref(false)
const userFilters = ref({ userId: '', phone: '', status: '' })

// ==================== 商家 tab 状态（独立一份；首次切入才拉） ====================
const merchantRows = ref([])
const merchantTotal = ref(0)
const merchantPageNum = ref(1)
const merchantPageSize = ref(20)
const merchantLoading = ref(false)
const merchantError = ref('')
const merchantSubmitting = ref(false)
const merchantLoaded = ref(false)
const merchantFilters = ref({ status: '' })

// A4 / P4-7 §3：统计行文案只由状态决定，数字只在成功态出现。
//   loading → 「正在统计…」；error 非空 → 「统计不可用」；否则 → 原文案（共 N 条；按 id 倒序 / 升序）。
// 注意：两处 catch 里把 total 置 0 是**刻意保留**的——el-pagination 的 :total 必须是数字，不能沿用上一次的旧数；
// 而统计行已不渲染这个 0，所以它不是「失败显示 0」的残留。
const userTotalText = computed(() => {
  if (userLoading.value) return '正在统计…'
  if (userError.value) return '统计不可用'
  return '共 ' + userTotal.value + ' 条；按 id 倒序。'
})
const merchantTotalText = computed(() => {
  if (merchantLoading.value) return '正在统计…'
  if (merchantError.value) return '统计不可用'
  return '共 ' + merchantTotal.value + ' 条；按 id 升序。'
})

// 正整数（与后端「userId 非空必须 > 0」同口径；前端只挡明显填错，不白跑一趟请求）。
function isPositiveInt(raw) {
  return /^[1-9]\d*$/.test(raw)
}

// A8 空页回退（P4-7 §4 / D4）：两个 tab 各配一个模块级布尔，非 ref——它只决定「要不要再拉一次」，不参与渲染。
// 服务端对越界页回「空 list + 定值 total」而不是 400，所以「空行 + total>0 + 页码>1 且超过末页」是唯一可靠判据。
let userPageFixPending = false
let merchantPageFixPending = false

async function loadUsers() {
  if (userLoading.value) return
  userLoading.value = true
  userError.value = ''
  const rawUserId = String(userFilters.value.userId == null ? '' : userFilters.value.userId).trim()
  try {
    const envelope = await getAdminUsers({
      // userId 由 onChange 前置校验过；这里再兜一次 trim 后的形状，空串交给 withQuery 省略
      userId: rawUserId !== '' && isPositiveInt(rawUserId) ? rawUserId : '',
      phone: String(userFilters.value.phone || '').trim(),
      status: userFilters.value.status,
      pageNum: userPageNum.value,
      pageSize: userPageSize.value
    })
    const data = envelope.data || {}
    userRows.value = Array.isArray(data.list) ? data.list : []
    // total 用后端值（独立 COUNT 的结果），绝不用本页行数推算
    userTotal.value = Number(data.total) || 0
    // 服务端会回显真正生效的 pageNum / pageSize（不夹取）；与本地不一致时以服务端为准
    if (Number(data.pageNum) > 0) userPageNum.value = Number(data.pageNum)
    if (PAGE_SIZES.indexOf(Number(data.pageSize)) !== -1) userPageSize.value = Number(data.pageSize)
    // A8：末页最后一条被处理掉后停在空页 → 记下回退页码，等本次 load 收尾后再拉一次。
    // 条件 userPageNum > lastPage 天然防循环：回退后的页码不会再满足它。
    if (userRows.value.length === 0 && userTotal.value > 0 && userPageNum.value > 1) {
      const lastPage = Math.max(1, Math.ceil(userTotal.value / userPageSize.value))
      if (userPageNum.value > lastPage) {
        userPageNum.value = lastPage
        userPageFixPending = true
      }
    }
  } catch (err) {
    const e = err || {}
    userRows.value = []
    userTotal.value = 0
    userError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    userLoading.value = false
  }
  // 补拉必须放在 finally 之后：此时 userLoading 已落，在飞守卫才放行；不得在 try 里 await loadUsers()。
  if (userPageFixPending) {
    userPageFixPending = false
    return loadUsers()
  }
}

async function loadMerchants() {
  if (merchantLoading.value) return
  merchantLoading.value = true
  merchantLoaded.value = true
  merchantError.value = ''
  try {
    const envelope = await getAdminMerchants({
      status: merchantFilters.value.status,
      pageNum: merchantPageNum.value,
      pageSize: merchantPageSize.value
    })
    const data = envelope.data || {}
    merchantRows.value = Array.isArray(data.list) ? data.list : []
    merchantTotal.value = Number(data.total) || 0
    if (Number(data.pageNum) > 0) merchantPageNum.value = Number(data.pageNum)
    if (PAGE_SIZES.indexOf(Number(data.pageSize)) !== -1) merchantPageSize.value = Number(data.pageSize)
    // A8：商家侧同口径回退（判据见上方 userPageFixPending 注释）。
    if (merchantRows.value.length === 0 && merchantTotal.value > 0 && merchantPageNum.value > 1) {
      const lastPage = Math.max(1, Math.ceil(merchantTotal.value / merchantPageSize.value))
      if (merchantPageNum.value > lastPage) {
        merchantPageNum.value = lastPage
        merchantPageFixPending = true
      }
    }
  } catch (err) {
    const e = err || {}
    merchantRows.value = []
    merchantTotal.value = 0
    merchantError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    merchantLoading.value = false
  }
  // 同上：finally 之后才补拉（不得在 try 里 await loadMerchants()）。
  if (merchantPageFixPending) {
    merchantPageFixPending = false
    return loadMerchants()
  }
}

// 首次切到商家 tab 才发请求；之后切回不重发（刷新由查询/动作成功后重拉负责）。
function onTabChange(name) {
  if (name === 'merchants' && !merchantLoaded.value) loadMerchants()
}

function onUserFilterChange() {
  if (userLoading.value || userSubmitting.value) return
  const raw = String(userFilters.value.userId == null ? '' : userFilters.value.userId).trim()
  if (raw !== '' && !isPositiveInt(raw)) {
    ElMessage.warning('用户ID须为正整数（不按 ID 筛就留空）')
    return
  }
  // 筛选变化必须回第 1 页：原页码在新条件下很可能越界（后端回空 list，会被误读成「没有数据」）
  userPageNum.value = 1
  loadUsers()
}

function onUserReset() {
  if (userLoading.value || userSubmitting.value) return
  userFilters.value.userId = ''
  userFilters.value.phone = ''
  userFilters.value.status = ''
  userPageNum.value = 1
  loadUsers()
}

function onUserPageChange() {
  loadUsers()
}

function onUserSizeChange() {
  userPageNum.value = 1
  loadUsers()
}

function onMerchantFilterChange() {
  if (merchantLoading.value || merchantSubmitting.value) return
  merchantPageNum.value = 1
  loadMerchants()
}

function onMerchantReset() {
  if (merchantLoading.value || merchantSubmitting.value) return
  merchantFilters.value.status = ''
  merchantPageNum.value = 1
  loadMerchants()
}

function onMerchantPageChange() {
  loadMerchants()
}

function onMerchantSizeChange() {
  merchantPageNum.value = 1
  loadMerchants()
}

// 用户封禁（契约 §4.1 文案逐字）：确认前把「登录即拒 + 旧登录态立即失效」摊开。
async function onUserBan(row) {
  if (!row || userSubmitting.value || userLoading.value) return
  try {
    await ElMessageBox.confirm(
      '封禁后该用户将无法登录，已签发的登录态也会立即失效。确定封禁用户 #' + row.id + '？',
      '封禁用户',
      {
        type: 'warning',
        confirmButtonText: '确认封禁',
        cancelButtonText: '取消（不发出任何请求）',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    // 点「取消」/关闭/Esc 一律 reject → 吞掉，不发出任何请求
    return
  }
  await runUserStatus(row, 'ban')
}

// 用户解封（契约 §4.1 文案逐字）。
async function onUserUnban(row) {
  if (!row || userSubmitting.value || userLoading.value) return
  try {
    await ElMessageBox.confirm(
      '解封后该用户可立即重新登录并恢复全部功能。确定解封用户 #' + row.id + '？',
      '解封用户',
      {
        type: 'warning',
        confirmButtonText: '确认解封',
        cancelButtonText: '取消（不发出任何请求）',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    return
  }
  await runUserStatus(row, 'unban')
}

/**
 * 用户状态写路径：成功只念后端 message，失败只念请求层透出的 message；成功与否一律以重拉列表为准。
 */
async function runUserStatus(row, action) {
  userSubmitting.value = true
  try {
    const envelope = await updateAdminUserStatus(row.id, action)
    ElMessage.success((envelope && envelope.message) || (action === 'ban' ? '用户已封禁' : '用户已解封'))
    await loadUsers()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    userSubmitting.value = false
  }
}

// 商家封禁（契约 §4.1 文案逐字）：四条影响面必须写全——可见性不变、写/看板营销接口 403（备货明细读放行）、无自动下架/清秒杀/移池、在途单归订单管理。
async function onMerchantBan(row) {
  if (!row || merchantSubmitting.value || merchantLoading.value) return
  try {
    await ElMessageBox.confirm(
      '封禁后：① C 端该商家商品仍展示，但下单会被拒绝；② 商家端写操作与看板/营销接口返回 403（备货计划/供货明细按既有口径仍可读）；'
      + '③ 不会自动下架商品、不会清秒杀场/免费领池；④ 该商家在途订单需由运营端「订单管理」处理。确定封禁商家「'
      + row.name + '」？',
      '封禁商家',
      {
        type: 'warning',
        confirmButtonText: '确认封禁',
        cancelButtonText: '取消（不发出任何请求）',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    return
  }
  await runMerchantStatus(row, 'ban')
}

// 商家解封（契约 §4.1 文案逐字）。
async function onMerchantUnban(row) {
  if (!row || merchantSubmitting.value || merchantLoading.value) return
  try {
    await ElMessageBox.confirm(
      '解封后商家恢复接单与商家端操作；已下架商品不会自动恢复。确定解封商家「' + row.name + '」？',
      '解封商家',
      {
        type: 'warning',
        confirmButtonText: '确认解封',
        cancelButtonText: '取消（不发出任何请求）',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    return
  }
  await runMerchantStatus(row, 'unban')
}

/**
 * 商家状态写路径：同上——成功念后端 message 并重拉当前 tab，失败念后端 message，绝不本地改行。
 */
async function runMerchantStatus(row, action) {
  merchantSubmitting.value = true
  try {
    const envelope = await updateAdminMerchantStatus(row.id, action)
    ElMessage.success((envelope && envelope.message) || (action === 'ban' ? '商家已封禁' : '商家已解封'))
    await loadMerchants()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    merchantSubmitting.value = false
  }
}

onMounted(() => {
  // P4-13 D4：先读 route.query.userId，合法正整数才预填（与输入框的 isPositiveInt 同口径）；
  // 预填完只调用一次 loadUsers（原调用合并在这里，不重复请求）。
  const rawUserId = route.query ? route.query.userId : undefined
  if (rawUserId != null && /^[1-9]\d*$/.test(String(rawUserId))) {
    userFilters.value.userId = String(rawUserId)
  }
  loadUsers()
})
</script>

<style scoped>
.ban-banner {
  margin-top: 12px;
  padding: 10px 14px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 6px;
  color: #7a5a12;
  font-size: 13px;
  line-height: 1.7;
}
.ban-banner p {
  margin: 0;
}
.ban-banner p + p {
  margin-top: 4px;
}
.ban-tabs {
  margin-top: 14px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}
.filter-input {
  width: 220px;
}
.filter-input--id {
  width: 200px;
}
.toolbar-hint {
  font-size: 12px;
  color: #909399;
}
.state-box {
  margin-top: 14px;
}
.table {
  background: #fff;
  margin-top: 14px;
}
.muted {
  color: #909399;
  font-size: 12px;
}
.mono {
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
}
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
}
/* disabled 的 el-button 不派发鼠标事件，title 挂在这层包裹上才能弹出来 */
.btn-disabled-wrap {
  display: inline-block;
}
</style>
