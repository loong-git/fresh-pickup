<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">限流观测</h2>
      <el-tag type="success" effect="plain" size="small">只读页（无写操作）</el-tag>
    </div>
    <!-- F-121 §3：页头说明（契约逐字）——命中即异常流量信号；本页只读，封禁操作见「封禁管理」。 -->
    <div class="accounting-banner">
      限流命中即异常流量信号（刷接口/恶意重试）；本页只读，封禁操作见「封禁管理」。
    </div>
    <p class="page-note">
      数据源是限流拦截器命中旁路写的 rate_limit_hit（F-121 §2）：拦截判定本身零涉及，落库失败只记 warn
      绝不影响拦截。<br />
      聚合读 GET /api/admin/rate-limit/hits?days=（IP 30/60s 只读档）：按「路由 + 维度键」分组，
      命中次数倒序取前 100 行；total 为该时间档内命中总次数（全量计数，不受前 100 截断影响）。<br />
      维度键形态：ip:&lt;IP&gt;:&lt;Controller#method&gt;（匿名接口）/ user:&lt;userId&gt;:&lt;Controller#method&gt;（登录接口，匿名兜底 ip:）。
    </p>

    <!-- 时间档单选（契约 §3：今天 1 / 近 7 天 / 近 30 天；后端窗口为 hit_time ≥ NOW()-days） -->
    <div class="toolbar">
      <!-- A3：切档即触发 loadHits()，而 loadHits 的在飞守卫会吞掉这次请求——days 已是新值、
           total 卡还是上一档的数。禁用控件才能保证「标签与数字同一窗口」；刷新按钮已有 :loading，不另加。 -->
      <el-radio-group v-model="days" size="small" :disabled="loading" @change="loadHits">
        <el-radio-button :value="1">今天</el-radio-button>
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
      </el-radio-group>
      <el-button size="small" :loading="loading" @click="loadHits">刷新</el-button>
      <span class="toolbar-hint">时间档：{{ windowHint }}</span>
    </div>

    <!-- total 卡（契约 §3）：该时间档内命中总次数（全量计数，不受 top-100 截断影响）。
         A4：total 的 null = 「未知」（读取失败），不是「零」——卡片照常渲染 0 会被读成「没有异常流量」；
         total-zero 只在 total === 0 时挂（null !== 0，天然不挂）。 -->
    <section class="total-card" :class="{ 'total-zero': total === 0 }">
      <div class="total-num">{{ total === null ? '—' : total }}</div>
      <div class="total-label">近 {{ days }} 天限流命中总次数</div>
    </section>

    <section class="panel">
      <div v-if="loading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">限流命中聚合读取中…</div>
      </div>
      <div v-else-if="error" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ error }}</div>
        <el-button size="small" @click="loadHits">重试</el-button>
      </div>
      <template v-else>
        <el-table :data="rows" border stripe size="small" max-height="calc(100vh - 460px)">
          <template #empty>
            <span class="muted">该时间档内无限流命中（若无压测/攻击流量，属正常态）</span>
          </template>
          <el-table-column type="index" label="#" width="56" />
          <el-table-column label="路由" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.route }}</span>
            </template>
          </el-table-column>
          <el-table-column label="维度键" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.bucketKey }}</span>
            </template>
          </el-table-column>
          <el-table-column label="命中次数" width="110" align="right">
            <template #default="{ row }">
              <b>{{ row.count }}</b>
            </template>
          </el-table-column>
          <el-table-column label="最近命中" width="170">
            <template #default="{ row }">
              <!-- lastHitAt 是服务端 DATE_FORMAT 定形的 "YYYY-MM-DD HH:mm:ss" 串，直接显示，不做二次格式化 -->
              <span class="mono">{{ row.lastHitAt }}</span>
            </template>
          </el-table-column>
          <el-table-column label="处置" width="120">
            <template #default="{ row }">
              <el-button v-if="row.bucketKey && row.bucketKey.startsWith('user:')" link type="primary" size="small"
                         @click="goBan(row)">去封禁</el-button>
              <span v-else class="muted" title="IP 维度请在封禁页按手机号/用户处理">—</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </section>
  </div>
</template>

<script setup>
// 限流观测只读页（F-121 §3）：照 F-114 Stores.vue 页面模式（PartnerWallets.vue 同款骨架），无写操作——
// 契约红线：命中即异常流量信号，封禁操作见「封禁管理」，本页连二次确认弹层都不需要。
// 唯一动作 = 时间档切换/刷新（GET /api/admin/rate-limit/hits?days=，1/7/30 白名单，IP 30/60s 只读档）；
// 结构与错误处理照 PartnerWallets.vue：loading / error / 空表三态，信封判定全在 api 层。
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import { getAdminRateLimitHits } from '../api'

const router = useRouter()

const days = ref(7)
// A4：null = 「未知」，只有成功态才写数。与 Overview.vue 的「未取到数据是 '-'，不是 0」同一条纪律：
// 0 是「确实没有命中」这句话，失败时白给 0 就是替服务端撒谎。
const total = ref(null)
const rows = ref([])
const loading = ref(false)
const error = ref('')

// 时间档提示：days=1 的后端窗口是「近 24 小时」（hit_time ≥ NOW()-1 天），文案照实说，不冒充自然日
const windowHint = computed(() =>
  days.value === 1 ? '今天（近 24 小时，hit_time ≥ NOW()-1 天）' : `近 ${days.value} 天（hit_time ≥ NOW()-${days.value} 天）`
)

async function loadHits() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  // A3/A4：在飞即「未知」——切档瞬间 days 已是新档，若卡片还挂着上一档的数字，label 与 total 就不同窗口。
  // 只禁用控件挡不住这个瞬间（见契约 §3 的实测补充）。
  total.value = null
  try {
    const envelope = await getAdminRateLimitHits(days.value)
    const data = envelope && envelope.data ? envelope.data : {}
    rows.value = Array.isArray(data.list) ? data.list : []
    total.value = Number(data.total) || 0
  } catch (err) {
    const e = err || {}
    rows.value = []
    // A4：失败写回「未知」，不写 0（0 是「确实没有」这句话，只有成功态能给）
    total.value = null
    error.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

// P4-13 D3（A11）：user 维度的桶键（user:<id>:<路由标识>）给「去封禁」直达封禁页；
// 只解析 user:<id>: 里的正整数，解析不出就什么都不做（ip: 行在模板里本就不给按钮）。
function goBan(row) {
  const matched = /^user:(\d+):/.exec(String((row && row.bucketKey) || ''))
  if (!matched) return
  const id = Number(matched[1])
  if (!Number.isInteger(id) || id <= 0) return
  router.push({ path: '/bans', query: { userId: String(id) } })
}

onMounted(() => {
  loadHits()
})
</script>

<style scoped>
.accounting-banner {
  margin-top: 12px;
  padding: 10px 14px;
  background: #E8F5EE;
  border: 1px solid #D6F0E0;
  border-radius: 6px;
  color: #0B5C36;
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
  color: #57534E;
}
.total-card {
  display: inline-block;
  min-width: 200px;
  margin-top: 14px;
  padding: 14px 20px;
  background: #FBF1E6;
  border: 1px solid #F7E4CD;
  box-shadow: 0 1px 3px rgba(28, 25, 23, 0.06);
  border-radius: 10px;
  text-align: center;
}
.total-card.total-zero {
  background: #F1F0EF;
  border-color: #E7E5E0;
}
.total-num {
  font-size: 26px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: #D97706;
  line-height: 1.2;
}
.total-card.total-zero .total-num {
  color: #57534E;
}
.total-label {
  margin-top: 4px;
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
.muted {
  color: #57534E;
  font-size: 12px;
}
.mono {
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
}
</style>
