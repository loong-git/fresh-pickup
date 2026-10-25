<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">主理人钱包</h2>
      <el-tag type="success" effect="plain" size="small">只读页（无写操作）</el-tag>
    </div>
    <!-- F-119 §3：记账态恒显说明——钱包余额只被生成账期跑批记账增减，无人工调额/提现端点
         （运营不可手改钱包，防资损；写路径只有跑批）。提现属轨道 B。 -->
    <div class="accounting-banner">
      记账态：佣金余额只由结算跑批自动记账（已支付订单按订单实付 3% 入账、退款订单冲回），
      本页无任何写操作、无人工调额入口；提现通道未接入（轨道 B）。
    </div>
    <p class="page-note">
      列表读 GET /api/admin/partner-wallets（按钱包 id 升序）：user（手机号脱敏）/店名/余额/流水数。<br />
      逐单佣金流水（类型/单号/金额±/余额快照/费率）在主理人端「我的佣金」页自查；
      账单维度佣金净值在「结算中心」列表 commission 列，Σ 流水可互核。
    </p>

    <div class="toolbar">
      <el-button size="small" :loading="loading" @click="loadWallets">刷新</el-button>
      <span class="toolbar-hint">共 {{ wallets.length }} 个钱包；按钱包 id 升序（首次记账时建）。无钱包=尚无主理人订单入账，属正常态。</span>
    </div>

    <section class="panel">
      <div v-if="loading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">钱包列表读取中…</div>
      </div>
      <div v-else-if="error" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ error }}</div>
        <el-button size="small" @click="loadWallets">重试</el-button>
      </div>
      <template v-else>
        <el-table :data="wallets" border stripe size="small" max-height="calc(100vh - 380px)">
          <template #empty>
            <span class="muted">暂无钱包（尚无主理人佣金入账，属正常态）</span>
          </template>
          <el-table-column label="主理人" width="150">
            <template #default="{ row }">
              <span v-if="row.userPhone" class="mono">{{ row.userPhone }}</span>
              <span v-else class="muted">#{{ row.userId }}（用户已注销）</span>
            </template>
          </el-table-column>
          <el-table-column label="店名" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="(row.storeNames || []).length">{{ row.storeNames.join('、') }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="余额（元）" width="120" align="right">
            <template #default="{ row }">
              <b :class="{ 'neg-balance': Number(row.balance) < 0 }">{{ row.balance }}</b>
              <el-tooltip v-if="Number(row.balance) < 0" content="冲回金额超过余额所致，后端如实记账；佣金已付情形轨道 B 处理" placement="top">
                <span class="neg-mark"> ⚠</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="流水数" width="90" align="right">
            <template #default="{ row }">{{ row.flowCount }}</template>
          </el-table-column>
        </el-table>
      </template>
    </section>
  </div>
</template>

<script setup>
// 主理人钱包只读页（F-119 §3）：照 F-114 Stores.vue 页面模式，无写操作——
// 契约红线：无人工调额/提现（轨道 B）、钱包写路径只有跑批记账，本页连二次确认弹层都不需要。
// 唯一动作 = 刷新（GET /api/admin/partner-wallets，IP 30/60s 只读档）；结构与错误处理照 Settlements.vue。
// 余额负值如实展示并带 ⚠ 提示（冲回减至负是契约 §2-2 明文的如实记账语义，不是数据事故）。
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getAdminPartnerWallets } from '../api'

const wallets = ref([])
const loading = ref(false)
const error = ref('')

async function loadWallets() {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const envelope = await getAdminPartnerWallets()
    wallets.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    wallets.value = []
    error.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadWallets()
})
</script>

<style scoped>
.accounting-banner {
  margin-top: 12px;
  padding: 10px 14px;
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 6px;
  color: #529b2e;
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
.mono {
  font-family: Menlo, Consolas, monospace;
  font-size: 12px;
}
.neg-balance {
  color: #f56c6c;
}
.neg-mark {
  color: #e6a23c;
}
</style>
