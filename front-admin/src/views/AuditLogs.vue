<template>
  <div class="page">
    <h2 class="page-title">审计记录</h2>
    <p class="page-note">
      Admin 侧写动作的留痕（谁、做了什么、对哪一条），按 id 倒序分页；服务端手写 LIMIT/OFFSET + 独立 COUNT。
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
      <el-table :data="rows" border stripe size="small" class="table">
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
        <el-table-column prop="detail" label="详情" min-width="240" class-name="col-detail" />
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
// 审计日志分页（F-20.11 Step 4）：GET /api/admin/v2/audit-logs?pageNum=&pageSize=
// 出参 {list,total,pageNum,pageSize}；行内恰七键 id/actor/action/targetType/targetId/detail/createdAt。
// ⚠ 出参**没有 ip**（AdminAuditLogMapper:31-35 的 SELECT 连这一列都没查，RD 最小暴露）——
//   表格里不许凭空加这一列，也不许从别处凑。
// ⚠ pageSize 越界（0 / >100 / 负数）后端是 **400 而不是夹到边界**（F-20.10 追认），
//   所以可选页大小只能提供 [10,20,50,100]，不给任何越界选项。
import { computed, onMounted, ref } from 'vue'
import { getAdminAuditLogs } from '../api'

const PAGE_SIZES = [10, 20, 50, 100]

const viewState = ref('loading')
const errorText = ref('')
const errorCode = ref(0)

const rows = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)

// 各码的「下一步」文案：只给本层能确定的建议，不推断业务原因
const CODE_HINTS = {
  400: '分页参数不在允许范围内（页码从 1 起、每页 10/20/50/100 四档），请调整后重试。',
  404: '审计查询接口没有可达（后端服务未起或路径已变），请确认后端在 3001 端口运行。',
  429: '读取过于频繁（服务端按来源 IP 限流 30 次/分），请稍候再翻页。'
}
const codeHint = computed(() => CODE_HINTS[errorCode.value] || '')

async function load() {
  viewState.value = 'loading'
  errorText.value = ''
  errorCode.value = 0
  try {
    const envelope = await getAdminAuditLogs(pageNum.value, pageSize.value)
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

onMounted(() => {
  load()
})
</script>

<style scoped>
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
:deep(.col-detail) {
  word-break: break-all;
  white-space: pre-wrap;
}
</style>
