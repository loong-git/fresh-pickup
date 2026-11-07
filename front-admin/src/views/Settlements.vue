<template>
  <div class="page">
    <div class="page-head">
      <h2 class="page-title">结算中心</h2>
      <el-tag type="danger" effect="dark" size="small">本页会改数据</el-tag>
    </div>
    <!-- F-118 §2 原记账态恒显条——P10-5 轨道 B D5 起打款走模拟通道：通道显式 MOCK 标注（红线：
         任何页面不得出现无通道标注的资金文案）。未打款行接口仍带 paidChannelAbsent，已打款行带 payChannel。 -->
    <div class="accounting-banner">
      打款走模拟通道（pay_channel=MOCK_SIM，无真实资金流），所有金额为平台-商家核算口径。
      账单只增不改（无作废/重开），冲销在下一账期 refund 列负向计入。
    </div>
    <p class="page-note">
      列表读 GET /api/admin/settlements（十六键行，F-119 §3 在 net 后补 commission 列、P10-5 D8 在 refund 后补
      deposit 列=本期代扣入保证金额，含实时对账标志 reconcileOk；
      P10-5 D5 起已打款行以 payChannel 替代 paidChannelAbsent 键）；生成走 POST /api/admin/settlements/generate；
      明细弹窗读 GET /api/admin/settlements/{id}/items；确认打款走 POST /api/admin/settlements/{id}/payout（幂等）。<br />
      <b>保证金代扣</b>（P10-5 D8）：商家被运营置过档位（下方「保证金管理」）且已缴不足时，生成账期会在同事务内
      从应结款代扣差额缴存保证金（deposit&gt;0 的行 net 已是扣后实结额），通道 MOCK（无真实资金流）。<br />
      <b>对账 ✗ = 数据事故</b>（理论上不可达：金额或行数与挂锚明细对不上），行整行标红，出现即立即上报，不要自行处理数据。<br />
      缺货明细不结算（fulfill_status=shortage 排除）；未设供货价（supply_price 为空）与未支付单（pay_status≠1）不进聚合。<br />
      <b>佣金</b>=本账单主理人佣金净值（F-119：已支付订单按订单实付 3% 入账、本期窗口退款订单冲回，正负相抵；
      逐单明细在主理人佣金流水，钱包见「主理人钱包」只读页）。
    </p>

    <div class="toolbar">
      <el-button size="small" type="primary" :disabled="submitting" @click="openGenerate">生成账期</el-button>
      <el-select v-model="filterMerchantId" clearable filterable placeholder="按商家筛（可空=全部）" style="width: 220px" @change="onFilterChange">
        <el-option v-for="m in merchantOptions" :key="m.value" :label="m.label" :value="m.value" />
      </el-select>
      <!-- F-125 §2：账期窗口筛选（YYYY-MM-DD，闭区间 [dateStart, dateEnd]，服务端对 period_start/period_end
           交叠比较）。日期控件在飞禁用照 P4-7 A3 口径：loading 中禁改，防「改了条件却被在飞守卫吞掉」的假筛选。 -->
      <el-date-picker v-model="filterDateStart" type="date" value-format="YYYY-MM-DD" placeholder="账期起"
                      style="width: 150px" :disabled="loading" />
      <el-date-picker v-model="filterDateEnd" type="date" value-format="YYYY-MM-DD" placeholder="账期止"
                      style="width: 150px" :disabled="loading" />
      <el-button size="small" type="primary" plain :loading="loading" :disabled="submitting" @click="applyFilters">筛选</el-button>
      <el-button link size="small" :disabled="loading" @click="clearDateFilter">清空</el-button>
      <el-button size="small" :loading="loading" :disabled="submitting" @click="loadSettlements">刷新</el-button>
      <!-- P6-3 §2：账单 CSV 导出（只读，上限 5000 行，merchantId/dateStart/dateEnd 同参透传）。
           在飞禁用照本页既有口径：:loading 表导出自身在飞，disabled 照日期控件/清空/刷新同一组
           （列表读取 loading 中禁点 + 生成弹窗 submitting 在飞禁点）；不加确认框（只读导出，
           与「生成账期」的二次确认体系无关，契约 §2 明定）。 -->
      <el-button size="small" :loading="exporting" :disabled="loading || submitting" @click="onExportCsv">导出 CSV</el-button>
      <span class="toolbar-hint">共 {{ total }} 单；按 id 倒序（新账期在前）。</span>
    </div>

    <!-- P4-6：生成成功提示。若当前筛选不是该商家，给一键切筛选，避免「生成了但列表里看不见」。 -->
    <div v-if="generatedHint" class="generated-hint">
      已为「{{ generatedHint.merchantName }}」(#{{ generatedHint.merchantId }}) 生成账期
      <span v-if="generatedHint.settleNo"> · {{ generatedHint.settleNo }}</span>。
      <el-button v-if="generatedHidden" link type="primary" size="small" :disabled="loading" @click="showGeneratedMerchant">按该商家筛选</el-button>
      <el-button link size="small" @click="generatedHint = null">关闭</el-button>
    </div>

    <section class="panel">
      <div v-if="loading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">账期列表读取中…</div>
      </div>
      <div v-else-if="error" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ error }}</div>
        <el-button size="small" @click="loadSettlements">重试</el-button>
      </div>
      <template v-else>
        <!-- 对账 ✗ 行整行标红（数据事故态，理论上不可达） -->
        <el-table :data="settlements" border stripe size="small" max-height="calc(100vh - 380px)"
                  :row-class-name="reconcileRowClass">
          <template #empty>
            <!-- F-125 契约 §2.2 增补：空态文案两支（UI 文案必须诚实）——有生效筛选时账单可能存在、
                 只是窗口没圈到，不能再说「首次生成前此列表为空，属正常态」；无筛选支原文案逐字保留。 -->
            <span v-if="hasActiveFilter" class="muted">当前筛选条件下暂无账期，可调整或清空筛选后重试</span>
            <span v-else class="muted">暂无账期（首次生成前此列表为空，属正常态）</span>
          </template>
          <el-table-column label="账期" width="200">
            <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
          </el-table-column>
          <el-table-column label="商家" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.merchantName || ('#' + row.merchantId) }}</template>
          </el-table-column>
          <el-table-column label="账单号" min-width="150" show-overflow-tooltip>
            <template #default="{ row }"><span class="muted">{{ row.settleNo }}</span></template>
          </el-table-column>
          <el-table-column label="gross" width="90" align="right">
            <template #default="{ row }">{{ row.gross }}</template>
          </el-table-column>
          <el-table-column label="refund" width="90" align="right">
            <template #default="{ row }">{{ row.refund }}</template>
          </el-table-column>
          <!-- P10-5 D8：本期代扣入保证金额（无档位商家恒 0；>0 时本行 net 已是扣后实结额） -->
          <el-table-column label="保证金" width="90" align="right">
            <template #default="{ row }">
              <span :class="{ 'deposit-cell-active': Number(row.deposit) > 0 }">{{ row.deposit }}</span>
            </template>
          </el-table-column>
          <el-table-column label="net" width="90" align="right">
            <template #default="{ row }"><b>{{ row.net }}</b></template>
          </el-table-column>
          <!-- F-119 §3：佣金列显值（F-118 列已建 m27、F-118 时代恒 0；F-119 起为跑批佣金净值） -->
          <el-table-column label="佣金" width="90" align="right">
            <template #default="{ row }">{{ row.commission }}</template>
          </el-table-column>
          <el-table-column label="明细数" width="80" align="right">
            <template #default="{ row }">{{ row.itemCount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <!-- P10-5 D5：paid 与 accounted 分态显——已打款必须带模拟通道标注（红线：无通道标注的资金文案不得出现） -->
              <el-tag v-if="row.status === 'paid'" type="warning" size="small">已打款·模拟通道</el-tag>
              <el-tag v-else-if="row.status === 'accounted'" type="success" size="small">记账态</el-tag>
              <el-tag v-else type="info" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="对账" width="80" align="center">
            <template #default="{ row }">
              <span v-if="row.reconcileOk" class="reconcile-ok" title="金额与明细数和挂锚明细实时对得上">✓</span>
              <span v-else class="reconcile-bad" title="对不上即数据事故：立即上报，不要自行处理数据">✗</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="170" fixed="right">
            <template #default="{ row }">
              <el-button size="small" type="primary" plain :disabled="submitting" @click="openItems(row)">明细</el-button>
              <!-- P10-5 D5：确认打款仅记账态显示（已打款行幂等不可再打）；二次确认照本页生成账期既有姿势 -->
              <el-button v-if="row.status === 'accounted'" size="small" type="warning" plain
                         :disabled="submitting || payoutSubmitting" @click="onPayout(row)">确认打款</el-button>
            </template>
          </el-table-column>
        </el-table>

        <!-- F-125 §2：分页器（形状照 Orders.vue 同款）：total 用后端独立 COUNT 的真值；
             在飞读由表格上方 state-box 覆盖，分页器只让位给写在飞（submitting），对齐 Orders 口径。 -->
        <div class="pager">
          <el-pagination
            v-model:current-page="pageNum"
            v-model:page-size="pageSize"
            :total="total"
            :page-sizes="PAGE_SIZES"
            layout="total, sizes, prev, pager, next"
            background
            :disabled="submitting"
            @size-change="onSizeChange"
            @current-change="onPageChange"
          />
        </div>
      </template>
    </section>

    <!-- ==================== P10-5 D7 保证金管理区（m1x 模拟触发器） ====================
         真实触发器①②③（营销白名单/风险阈值/信用分级，04 册 §3.1）在模拟轨由本区手动置档位等价替代；
         代扣（D8）不在本区——它在生成账期事务内自动发生（MOCK 通道），本区只管「档位现状+置档位」。
         档位建议值照 04 册 §3.3 三档：高档 8000（meat/fresh 活鲜）/中档 5000（dairy/frozen/wine）/
         低档 3000（其余六类）——均为模拟轨草案值，待真实 GMV 校准。 -->
    <section class="panel deposit-panel">
      <div class="deposit-head">
        <h3 class="deposit-title">保证金管理</h3>
        <span class="toolbar-hint">
          模拟触发器：为商家设置类目档位后，生成账期时若已缴不足会从应结款代扣（MOCK 通道，无真实资金流）；
          档位数值为模拟轨草案值（8000/5000/3000），待真实 GMV 校准。
        </span>
      </div>
      <div class="deposit-form">
        <el-select v-model="depositForm.merchantId" filterable :loading="merchantChoicesLoading"
                   :disabled="merchantChoicesLoading || !!merchantChoicesError"
                   placeholder="选择商家" style="width: 240px">
          <el-option v-for="m in merchantChoices" :key="m.id" :label="merchantLabel(m)" :value="String(m.id)">
            <span>{{ m.name }}</span>
            <span class="option-meta">#{{ m.id }} · {{ merchantStatusText(m.status) }}</span>
          </el-option>
        </el-select>
        <el-select v-model="depositForm.category" clearable placeholder="类目档位（可空）"
                   style="width: 220px" @change="onDepositCategoryChange">
          <el-option v-for="c in DEPOSIT_CATEGORY_OPTIONS" :key="c.value" :value="c.value"
                     :label="c.label + ' · 建议 ' + c.tier" />
        </el-select>
        <el-input v-model="depositForm.requiredAmount" placeholder="应缴额（0=停用）"
                  style="width: 170px" :disabled="depositSubmitting" />
        <el-button size="small" type="primary" :loading="depositSubmitting"
                   :disabled="depositSubmitting || merchantChoicesLoading || !!merchantChoicesError"
                   @click="onSetDepositRequired">设置档位</el-button>
        <el-button size="small" :loading="depositsLoading" @click="loadDeposits">刷新</el-button>
      </div>
      <el-table :data="deposits" border stripe size="small" max-height="320px" class="deposit-table">
        <template #empty>
          <span class="muted">暂无保证金账户（为商家设置档位后此处出现账户行）</span>
        </template>
        <el-table-column label="商家" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.merchantName || ('#' + row.merchantId) }}</template>
        </el-table-column>
        <el-table-column label="类目档位" width="110">
          <template #default="{ row }">{{ row.category || '--' }}</template>
        </el-table-column>
        <el-table-column label="应缴额" width="110" align="right">
          <template #default="{ row }">
            {{ Number(row.requiredAmount) > 0 ? row.requiredAmount : '未启用' }}
          </template>
        </el-table-column>
        <el-table-column label="已缴" width="100" align="right">
          <template #default="{ row }">{{ row.paidAmount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'active'" type="success" size="small">正常</el-tag>
            <el-tag v-else-if="row.status === 'arrears'" type="danger" size="small">欠费待补</el-tag>
            <el-tag v-else type="info" size="small">{{ DEPOSIT_STATUS_TEXT[row.status] || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="150">
          <template #default="{ row }">{{ row.updatedAt || '--' }}</template>
        </el-table-column>
      </el-table>
    </section>

    <!-- 生成账期弹窗 -->
    <el-dialog v-model="generateVisible" title="生成账期" width="520px" :close-on-click-modal="false">
      <el-form label-width="100px" @submit.prevent="onGenerate">
        <el-form-item label="商家">
          <!-- P4-6：真实商家下拉（GET /api/admin/merchants，分页拉全后本地过滤）；
               去掉 allow-create——错 merchantId 会生成错商家账单且 settlement 只增不改。 -->
          <el-select v-model="form.merchantId" filterable :loading="merchantChoicesLoading"
                     :disabled="merchantChoicesLoading || !!merchantChoicesError"
                     placeholder="按名称或 #id 搜索真实商家" style="width: 100%">
            <el-option v-for="m in merchantChoices" :key="m.id" :label="merchantLabel(m)" :value="String(m.id)">
              <span>{{ m.name }}</span>
              <span class="option-meta">#{{ m.id }} · {{ merchantStatusText(m.status) }}</span>
            </el-option>
          </el-select>
          <div v-if="merchantChoicesLoading" class="field-hint">正在加载商家列表…</div>
          <div v-else-if="merchantChoicesError" class="field-error">
            {{ merchantChoicesError }}
            <el-button link type="primary" size="small" @click="loadMerchantChoices">重试</el-button>
          </div>
          <div v-else-if="merchantChoices.length === 0" class="field-warn">
            没有可选的商家（无法生成账期）。
            <el-button link type="primary" size="small" @click="loadMerchantChoices">重新加载</el-button>
          </div>
          <div v-else class="field-hint">已加载 {{ merchantChoices.length }} 个商家；只能选真实商家，不再支持手输 merchantId。</div>
        </el-form-item>
        <el-form-item label="账期起">
          <el-date-picker v-model="form.periodStart" type="date" value-format="YYYY-MM-DD"
                          placeholder="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="账期止">
          <el-date-picker v-model="form.periodEnd" type="date" value-format="YYYY-MM-DD"
                          placeholder="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <div class="field-warn">
          聚合口径：该商家账期内「已支付 · 非缺货 · 已设供货价」的未结算明细；gross=Σ(供货价×数量)。
          空期不会生成（400）；同期不可重出（uk 拦截）。账单只增不改。
        </div>
      </el-form>
      <p class="dialog-note">
        校验与后端同款（merchant 存在、start&lt;end、同期不重），后端为准；成功后重拉列表核对，不本地改行；
        失败原因原样取自服务端 message。
      </p>
      <template #footer>
        <el-button size="small" :disabled="submitting" @click="generateVisible = false">取消（不发出任何请求）</el-button>
        <el-button size="small" type="primary" :loading="submitting"
                   :disabled="submitting || merchantChoicesLoading || !!merchantChoicesError || merchantChoices.length === 0"
                   @click="onGenerate">生成</el-button>
      </template>
    </el-dialog>

    <!-- 明细弹窗 -->
    <el-dialog v-model="itemsVisible" :title="itemsTitle" width="760px" :close-on-click-modal="false">
      <div v-if="itemsLoading" class="state-box">
        <div class="state-title">正在加载</div>
        <div class="state-desc">账单明细读取中…</div>
      </div>
      <div v-else-if="itemsError" class="state-box">
        <div class="state-title">读取失败</div>
        <div class="state-desc">{{ itemsError }}</div>
        <el-button size="small" @click="openItems(itemsRow)">重试</el-button>
      </div>
      <template v-else>
        <!-- 孤儿行（挂锚明细的 orders 已删，orphan:true）整行标红警示——数据事故态（2026-10-25 验收裁决） -->
        <el-table :data="items" border stripe size="small" max-height="420px" :row-class-name="itemRowClass">
          <template #empty>
            <span class="muted">该账单暂无挂锚明细（对账异常态，请上报）</span>
          </template>
          <el-table-column label="订单号" min-width="170" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.orderId }}
              <el-tag v-if="row.orphan" type="danger" effect="dark" size="small" class="orphan-tag">订单已不存在</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="菜品" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.dishName }}</template>
          </el-table-column>
          <el-table-column label="供货价" width="90" align="right">
            <template #default="{ row }">{{ row.supplyPrice }}</template>
          </el-table-column>
          <el-table-column label="数量" width="70" align="right">
            <template #default="{ row }">{{ row.qty }}</template>
          </el-table-column>
          <el-table-column label="小计" width="90" align="right">
            <template #default="{ row }">{{ row.subtotal }}</template>
          </el-table-column>
          <el-table-column label="支付" width="110" align="center">
            <template #default="{ row }">
              <span v-if="row.orphan" class="orphan-cell">订单已不存在</span>
              <span v-else>{{ row.payStatus === 1 ? '已支付' : row.payStatus === 2 ? '已退款' : '未支付' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="履约" width="80" align="center">
            <template #default="{ row }">
              <el-tag :type="row.fulfillStatus === 'shortage' ? 'danger' : 'success'" size="small">
                {{ row.fulfillStatus === 'shortage' ? '缺货' : '正常' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <p class="dialog-note">
          行 = 该账单挂锚的订单明细快照（下单时 dish_name/供货价快照，不随改品漂移），只读；
          「订单已不存在」= 挂锚明细对应的 orders 行已被删（孤儿态，数据事故——对账标志会同步打 ✗），立即上报不要自行处理。
        </p>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
// 结算中心（F-118 §2）：结构与错误处理照 Stores.vue / CouponTemplates.vue（同组菜单 F-113/F-114 模式）。
//   读   = GET /api/admin/settlements（F-118 十四键行 + F-119 §3 追加 commission，id 倒序；reconcileOk 实时对账标志；
//          F-125 §2 起分页出参 {list,total,pageNum,pageSize,filters} + 账期窗口闭区间筛选；
//          P10-5 D5 起已打款行以 payChannel 替代 paidChannelAbsent 键）
//   写   = POST /api/admin/settlements/generate（商家+起止日期；二次确认含聚合口径）
//          + POST /api/admin/settlements/{id}/payout（确认打款，P10-5 D5：条件置 paid+MOCK_SIM，
//          幂等——后端已 paid 再调返回当前态；二次确认说明模拟通道语义，仅记账态行显示入口）
//   明细 = GET /api/admin/settlements/{id}/items（挂锚明细快照，只读）
// 纪律（契约 §2/§3；3) 的「无更新」由 P10-5 D5 契约解除为「唯一打款条件 UPDATE」，其余不变）：
//   1) 顶部横幅恒显打款通道语义：模拟通道 MOCK_SIM 无真实资金流（红线：资金文案必须带通道标注），
//      paidChannelAbsent/payChannel 是接口按状态互斥的明示键、页面不依赖它显隐；
//   2) 对账 ✗ 行整行标红——语义是「数据事故，理论上不可达」，页面只上报不提供任何修复入口；
//   3) 无 PUT/DELETE（settlement 只增不改，冲销走下一期 refund 列；打款是唯一受契约允许的置态写）；
//   4) 生成/打款二次确认文案说明口径（聚合口径/模拟通道无真实资金流）；
//   5) 写成功后重拉列表，绝不本地改行；错误一律 ElMessage + 后端 message 原文。
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAdminSettlements, generateAdminSettlement, getAdminSettlementItems, getAdminMerchants, exportAdminSettlements, confirmAdminSettlementPayout, getAdminDeposits, setAdminDepositRequired } from '../api'

const settlements = ref([])
const loading = ref(false)
const error = ref('')
const submitting = ref(false)
const filterMerchantId = ref(null)
// F-125 §2：分页 + 账期窗口筛选态。dateStart/dateEnd 为 null=不筛（withQuery 对空值不下发，服务端走缺省）；
// pageNum/pageSize/total 三态由服务端分页出参驱动，total 绝不用本页行数推算。
const filterDateStart = ref(null)
const filterDateEnd = ref(null)
const pageNum = ref(1)
const pageSize = ref(20)
const total = ref(0)
const PAGE_SIZES = [10, 20, 50, 100]

// F-125 契约 §2.2 增补：是否有生效筛选——商家下拉或账期起/止任一非空。
// merchantId 是 String 或 null，clearable 清除后可能落 ''，null 与 '' 都算「无筛选」；
// 供空态文案两支使用：有筛选空=窗口没圈到（账单可能存在），无筛选空=首次生成前的正常态。
const hasActiveFilter = computed(() => (filterMerchantId.value != null && filterMerchantId.value !== '') || !!filterDateStart.value || !!filterDateEnd.value)

const generateVisible = ref(false)
const form = ref({ merchantId: null, periodStart: '', periodEnd: '' })
const merchantChoices = ref([])
const merchantChoicesLoading = ref(false)
const merchantChoicesError = ref('')
const generatedHint = ref(null)

const itemsVisible = ref(false)
const itemsLoading = ref(false)
const itemsError = ref('')
const itemsRow = ref(null)
const items = ref([])
const itemsTitle = computed(() => (itemsRow.value ? '账单明细 · ' + itemsRow.value.settleNo : '账单明细'))

// 列表筛选的商家选项：来自已出账列表去重（value 统一字符串，与 el-select 选项值同类型）；
// 生成弹窗的商家选项已改为真实商家列表（merchantChoices），两者数据源不同、不要混用。
const merchantOptions = computed(() => {
  const seen = new Map()
  settlements.value.forEach((row) => {
    if (row.merchantId != null && !seen.has(String(row.merchantId))) {
      seen.set(String(row.merchantId), row.merchantName ? row.merchantName + ' (#' + row.merchantId + ')' : '商家 #' + row.merchantId)
    }
  })
  return Array.from(seen, ([value, label]) => ({ value, label }))
})

// P4-6：真实商家选项（GET /api/admin/merchants，分页拉全后本地过滤）。
// 该端点没有名称过滤，pageSize 上限 100；商家多于 100 时必须按 total 继续翻页，不能只取第一页。
const MERCHANT_STATUS_TEXT = { active: '正常营业', suspended: '停业整顿', terminated: '已清退' }
const merchantStatusText = (s) => MERCHANT_STATUS_TEXT[s] || (s == null ? '未知' : String(s))
const merchantLabel = (m) => m.name + ' (#' + m.id + ')'

const generatedHidden = computed(() => !!(generatedHint.value && filterMerchantId.value != null
  && String(filterMerchantId.value) !== String(generatedHint.value.merchantId)))

async function loadMerchantChoices() {
  if (merchantChoicesLoading.value) return
  merchantChoicesLoading.value = true
  merchantChoicesError.value = ''
  try {
    const all = []
    const pageSize = 100
    let pageNum = 1
    let total = 0
    do {
      const envelope = await getAdminMerchants({ pageNum, pageSize })
      const data = envelope && envelope.data ? envelope.data : {}
      if (!Array.isArray(data.list)) {
        throw new Error('商家列表返回形状异常（list 不是数组）')
      }
      const list = data.list
      all.push(...list)
      total = Number(data.total) || 0
      pageNum += 1
      if (list.length === 0) {
        // total 与页不一致时防死循环；有 total 却返回 0 行说明数据不完整，不能静默当空
        if (total > 0) throw new Error('商家列表返回不完整（0 行 / 共 ' + total + '）')
        break
      }
    } while (all.length < total)
    merchantChoices.value = all
  } catch (err) {
    const e = err || {}
    merchantChoices.value = []
    merchantChoicesError.value = e.message || '商家列表加载失败'
    if (e.message) ElMessage.error(e.message)
  } finally {
    merchantChoicesLoading.value = false
  }
}

let reloadQueued = false

// force=true 时若已有请求在飞，不静默丢弃，而是等当前请求结束后再补拉一次
//（P4-6：生成成功/切换筛选后必须看到新数据，不能被既有在飞守卫吞掉）。
async function fetchSettlements(force) {
  if (loading.value) {
    if (force) reloadQueued = true
    return
  }
  loading.value = true
  error.value = ''
  // F-125 §2 空页回退（P4-7 A8）的局部 flag：本趟只补拉一次。回退目标是真末页（pageNum==lastPage），
  // 配合下方「越过末页才回退」判据，补拉趟不可能再置位——双保险防「补拉趟又空页」成环。
  let pageFixPending = false
  try {
    const envelope = await getAdminSettlements({
      merchantId: filterMerchantId.value,
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      dateStart: filterDateStart.value,
      dateEnd: filterDateEnd.value
    })
    const data = envelope && envelope.data ? envelope.data : {}
    settlements.value = Array.isArray(data.list) ? data.list : []
    // total 用后端独立 COUNT 的值，绝不拿本页行数推算（那会把「这一页几条」说成「一共几条」）
    total.value = Number(data.total) || 0
    // A8 空页回退：服务端对越界页回「空 list + 定值 total」而不是 400，所以
    // 「空行 + total>0 + 页码>1」是唯一可靠判据；回退到真末页（补拉在 finally 之后，只补一次）。
    if (settlements.value.length === 0 && total.value > 0 && pageNum.value > 1) {
      const lastPage = Math.max(1, Math.ceil(total.value / pageSize.value))
      if (pageNum.value > lastPage) {
        pageNum.value = lastPage
        pageFixPending = true
      }
    }
  } catch (err) {
    const e = err || {}
    settlements.value = []
    // 出错时 total 一并归零：toolbar-hint「共 N 单」不许在列表已空时还念上一轮的旧数（照 MerchantApplies 口径）
    total.value = 0
    error.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    loading.value = false
    if (reloadQueued) {
      reloadQueued = false
      fetchSettlements(true)
    }
  }
  // A8 补拉必须放在 finally 之后：此刻 loading 已落，在飞守卫才放行；不得在 try 里 await fetchSettlements()。
  if (pageFixPending) {
    return fetchSettlements(false)
  }
}

function loadSettlements() {
  return fetchSettlements(false)
}

function reloadSettlements() {
  return fetchSettlements(true)
}

// P6-3 §2：账单 CSV 导出（GET /api/admin/settlements/export，只读；分页循环拉全上限 5000 行）。
// 筛选同参透传：merchantId/dateStart/dateEnd 与列表查询同源（filterDateStart/End 为 null=不筛，
// filterMerchantId 为 null 或 ''=不筛，空值由请求层 withQuery 挡下不下发）——导出照当前生效筛选出数，
// 不带 pageNum/pageSize（导出端点没有分页参数，带了是死参数）。
// 不加确认框（只读导出与「生成账期」写确认体系无关，契约 §2 明定）；成功不弹 toast——
// 浏览器自带下载提示，再弹就是重复告知；失败只 ElMessage（照本页既有错误呈现口径）。
const exporting = ref(false)
async function onExportCsv() {
  if (exporting.value) return
  exporting.value = true
  try {
    const r = await exportAdminSettlements({
      merchantId: filterMerchantId.value,
      dateStart: filterDateStart.value,
      dateEnd: filterDateEnd.value
    })
    // blob → a.download：anchor 先挂进文档再 click（Firefox 要求节点在文档内）；
    // revokeObjectURL 推迟到下一轮宏任务（setTimeout 0）——click 同步启动下载，延后一拍零成本换稳。
    const url = URL.createObjectURL(r.blob)
    const a = document.createElement('a')
    a.href = url
    a.download = r.filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch (err) {
    const e = err || {}
    ElMessage.error(e.message || '导出失败，请稍后重试')
  } finally {
    exporting.value = false
  }
}

// 对账 ✗ 行整行标红（row-class-name）；✓ 行不额外着色
function reconcileRowClass({ row }) {
  return row.reconcileOk === false ? 'settle-broken' : ''
}

// 明细弹窗孤儿行（orders 已删）整行标红（2026-10-25 验收裁决：不再显示成空的"异常态"）
function itemRowClass({ row }) {
  return row.orphan ? 'item-orphan' : ''
}

function openGenerate() {
  form.value = { merchantId: null, periodStart: '', periodEnd: '' }
  generateVisible.value = true
  loadMerchantChoices()
}

// 列表筛选变化：清掉上一次生成提示，避免旧提示与新筛选并存
function onFilterChange() {
  generatedHint.value = null
  applyFilters()
}

// F-125 §2：任一筛选（商家下拉 / 日期「筛选」按钮）生效都回第 1 页再拉
// （照 P4-1 tab 收口纪律：原页码在新筛选下很可能已越界，会被误读成「这批没有数据」）。
function applyFilters() {
  // F-125 §3 验收 3：dateStart > dateEnd 前端拦截，不发请求、不改任何状态
  // （YYYY-MM-DD 定长字符串字典序即日期序，比较合法）。后端 400 仍是最后防线，前端不重复后端文案。
  // clearDateFilter 两值同清归 null，不会触发本判据。
  if (filterDateStart.value && filterDateEnd.value && filterDateStart.value > filterDateEnd.value) {
    ElMessage.warning('账期起须不晚于账期止，请调整后再筛选')
    return
  }
  pageNum.value = 1
  loadSettlements()
}

// F-125 §2：一键清空账期窗口（归 null=不筛），同样回第 1 页重拉。
function clearDateFilter() {
  filterDateStart.value = null
  filterDateEnd.value = null
  pageNum.value = 1
  loadSettlements()
}

function onSizeChange() {
  // 换每页条数回第 1 页：原页码在新页大小下很可能已越界，而服务端越界页回的是「空 list + 定值 total」（不是 400），
  // 那会被误读成「这一档没有数据」（照 Orders.vue 同款口径）
  pageNum.value = 1
  loadSettlements()
}

function onPageChange() {
  // v-model:current-page 已先把 pageNum 改到位，这里直接拉
  loadSettlements()
}

// 生成成功后若当前筛选看不到该商家，一键切到该商家（不偷偷改筛选）；
// 保留提示条，切完由 reloadSettlements 补拉（在飞时排队，不静默丢）。
// F-125 §2：生成的新账单 id 最大、必在第 1 页——切筛选后 pageNum 归 1，否则可能停在空页看不到它。
function showGeneratedMerchant() {
  if (!generatedHint.value) return
  filterMerchantId.value = String(generatedHint.value.merchantId)
  pageNum.value = 1
  reloadSettlements()
}

// 生成前校验（与后端同款形状）：商家必须是列表里的真实商家、两日期齐全
function validateForm() {
  const f = form.value
  const selected = merchantChoices.value.find((m) => String(m.id) === String(f.merchantId))
  if (!selected) {
    ElMessage.warning('请从下拉中选择真实商家（不再支持手输 merchantId）')
    return null
  }
  const merchantId = Number(selected.id)
  if (!Number.isInteger(merchantId) || merchantId <= 0) {
    ElMessage.warning('商家参数不合法，请重新选择')
    return null
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(f.periodStart || '')) || !/^\d{4}-\d{2}-\d{2}$/.test(String(f.periodEnd || ''))) {
    ElMessage.warning('账期起止都必填（YYYY-MM-DD）')
    return null
  }
  if (String(f.periodStart) >= String(f.periodEnd)) {
    ElMessage.warning('账期起须早于账期止')
    return null
  }
  return {
    merchantId,
    merchantName: selected.name,
    merchantStatus: selected.status,
    periodStart: String(f.periodStart),
    periodEnd: String(f.periodEnd)
  }
}

async function onGenerate() {
  if (submitting.value) return
  const parsed = validateForm()
  if (parsed === null) return
  // 二次确认（契约 §2）：说明聚合口径与「只增不改」后果；P4-6 回显商家名+#id，避免错商家
  const statusLine = parsed.merchantStatus && parsed.merchantStatus !== 'active'
    ? '\n注意：该商家当前为「' + merchantStatusText(parsed.merchantStatus) + '」，仍可按历史订单生成账期。'
    : ''
  try {
    await ElMessageBox.confirm(
      '确认为商家「' + parsed.merchantName + '」（#' + parsed.merchantId + '）生成账期 '
      + parsed.periodStart + ' ~ ' + parsed.periodEnd + ' 吗？' + statusLine + '\n'
      + '聚合口径：该商家本期「已支付 · 非缺货 · 已设供货价」的未结算明细，gross=Σ(供货价×数量)。\n'
      + '账单只增不改：生成后无作废/重开入口，冲销在下一账期 refund 列负向计入；同期不可重出。',
      '生成账期',
      {
        type: 'warning',
        confirmButtonText: '生成',
        cancelButtonText: '取消',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    return // 取消确认：不发出任何请求
  }
  submitting.value = true
  try {
    const envelope = await generateAdminSettlement({
      merchantId: parsed.merchantId,
      periodStart: parsed.periodStart,
      periodEnd: parsed.periodEnd
    })
    const created = envelope && envelope.data ? envelope.data : null
    generatedHint.value = {
      merchantId: parsed.merchantId,
      merchantName: parsed.merchantName,
      settleNo: created && created.settleNo ? created.settleNo : ''
    }
    ElMessage.success((envelope && envelope.message) || '账期已生成')
    generateVisible.value = false
    await reloadSettlements()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    submitting.value = false
  }
}

// ==================== P10-5 D7 保证金管理区（m1x 模拟触发器） ====================
// 列表读 GET /api/admin/deposits（只读），写走 POST /api/admin/deposits/required（二次确认照
// 本页生成账期/打款既有姿势：ElMessageBox + customClass 'admin-write-confirm'，文案显式说明
// 模拟轨草案值与 MOCK 代扣语义）；写成功后重拉账户列表，绝不本地改行。
// 类目档位建议值照 04 册 §3.3：高档 8000（meat/fresh）/中档 5000（dairy/frozen/wine）/低档 3000（其余）。
const DEPOSIT_CATEGORY_OPTIONS = [
  { value: 'meat', label: 'meat 肉类（高档）', tier: 8000 },
  { value: 'fresh', label: 'fresh 活鲜（高档）', tier: 8000 },
  { value: 'dairy', label: 'dairy 乳品（中档）', tier: 5000 },
  { value: 'frozen', label: 'frozen 冷冻（中档）', tier: 5000 },
  { value: 'wine', label: 'wine 酒水（中档）', tier: 5000 },
  { value: 'vegetable', label: 'vegetable 蔬菜（低档）', tier: 3000 },
  { value: 'fruit', label: 'fruit 水果（低档）', tier: 3000 },
  { value: 'grain', label: 'grain 粮油（低档）', tier: 3000 },
  { value: 'snack', label: 'snack 零食（低档）', tier: 3000 },
  { value: 'fastfood', label: 'fastfood 快餐（低档）', tier: 3000 },
  { value: 'general', label: 'general 通用（低档）', tier: 3000 }
]
const DEPOSIT_STATUS_TEXT = { none: '未启用', active: '正常', arrears: '欠费待补' }
const deposits = ref([])
const depositsLoading = ref(false)
const depositForm = ref({ merchantId: null, category: null, requiredAmount: '' })
const depositSubmitting = ref(false)

async function loadDeposits() {
  if (depositsLoading.value) return
  depositsLoading.value = true
  try {
    const envelope = await getAdminDeposits()
    deposits.value = Array.isArray(envelope && envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    deposits.value = []
    if (e.message) ElMessage.error(e.message)
  } finally {
    depositsLoading.value = false
  }
}

// 选类目自动预填建议档位金额（可改；清空类目不动金额）
function onDepositCategoryChange(category) {
  const hit = DEPOSIT_CATEGORY_OPTIONS.find((c) => c.value === category)
  if (hit) depositForm.value.requiredAmount = String(hit.tier)
}

async function onSetDepositRequired() {
  if (depositSubmitting.value) return
  const f = depositForm.value
  const selected = merchantChoices.value.find((m) => String(m.id) === String(f.merchantId))
  if (!selected) {
    ElMessage.warning('请从下拉中选择真实商家')
    return
  }
  const merchantId = Number(selected.id)
  if (!Number.isInteger(merchantId) || merchantId <= 0) {
    ElMessage.warning('商家参数不合法，请重新选择')
    return
  }
  const raw = String(f.requiredAmount == null ? '' : f.requiredAmount).trim()
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) {
    ElMessage.warning('应缴额须为非负数且最多两位小数（0=停用触发式缴存）')
    return
  }
  const requiredAmount = Number(raw)
  const stop = requiredAmount === 0
  const tierLine = stop
    ? '停用后该商家生成账期不再代扣保证金（已缴记录保留，状态回「未启用」）。'
    : '启用后生成账期时若该商家已缴不足，将从本期应结款代扣差额缴存保证金'
      + '（MOCK 通道，无真实资金流；代扣后实结 net=net−代扣额）。'
  try {
    await ElMessageBox.confirm(
      '确认为商家「' + selected.name + '」（#' + merchantId + '）设置保证金档位 '
      + (stop ? '0 元（停用）' : requiredAmount + ' 元')
      + (f.category ? '（类目 ' + f.category + '）' : '') + ' 吗？\n'
      + tierLine + '\n'
      + '档位数值为模拟轨草案值（8000/5000/3000），待真实 GMV 校准（04 册 §3.3）。',
      '设置保证金档位',
      {
        type: 'warning',
        confirmButtonText: '设置',
        cancelButtonText: '取消',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    return // 取消确认：不发出任何请求
  }
  depositSubmitting.value = true
  try {
    const envelope = await setAdminDepositRequired({
      merchantId,
      requiredAmount,
      category: f.category || undefined
    })
    ElMessage.success((envelope && envelope.message) || '保证金档位已更新（模拟触发器）')
    await loadDeposits()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    depositSubmitting.value = false
  }
}

// P10-5 D5 确认打款：仅记账态行可入（模板 v-if 兜底，这里再挡一道）；二次确认照本页生成账期
// 既有姿势（ElMessageBox + customClass 'admin-write-confirm'），文案显式说明模拟通道语义（红线：
// 资金文案必须带通道标注）。成功后重拉列表核对，绝不本地改行；幂等不靠前端——后端条件 UPDATE
// 已 paid 再调返回当前态不报错，前端只把后端 message 原样呈现。
const payoutSubmitting = ref(false)
async function onPayout(row) {
  if (payoutSubmitting.value || submitting.value) return
  if (!row || row.status !== 'accounted') return
  try {
    await ElMessageBox.confirm(
      '确认为账单 ' + row.settleNo + '（' + (row.merchantName || ('#' + row.merchantId)) + '，'
      + row.periodStart + ' ~ ' + row.periodEnd + '，net ' + row.net + ' 元）确认打款吗？\n'
      + '打款走模拟通道（MOCK_SIM，无真实资金流，测试环境演示闭环用），置后状态为「已打款」且不可回退。\n'
      + '同账单重复调用后端按幂等处理（返回当前态，不重复生效）。',
      '确认打款',
      {
        type: 'warning',
        confirmButtonText: '确认打款',
        cancelButtonText: '取消',
        customClass: 'admin-write-confirm'
      })
  } catch (e) {
    return // 取消确认：不发出任何请求
  }
  payoutSubmitting.value = true
  try {
    const envelope = await confirmAdminSettlementPayout(row.id)
    ElMessage.success((envelope && envelope.message) || '打款已确认（模拟通道）')
    await reloadSettlements()
  } catch (err) {
    const e = err || {}
    if (e.message) ElMessage.error(e.message)
  } finally {
    payoutSubmitting.value = false
  }
}

async function openItems(row) {
  if (!row) return
  itemsRow.value = row
  items.value = []
  itemsError.value = ''
  itemsVisible.value = true
  itemsLoading.value = true
  try {
    const envelope = await getAdminSettlementItems(row.id)
    items.value = envelope && Array.isArray(envelope.data) ? envelope.data : []
  } catch (err) {
    const e = err || {}
    items.value = []
    itemsError.value = e.message || ''
    if (e.message) ElMessage.error(e.message)
  } finally {
    itemsLoading.value = false
  }
}

onMounted(() => {
  loadSettlements()
  loadDeposits()
})
</script>

<style scoped>
.accounting-banner {
  margin-top: 12px;
  padding: 10px 14px;
  background: var(--admin-warn-bg);
  border: 1px solid var(--admin-warn-border);
  border-radius: 6px;
  color: var(--el-color-warning);
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
  color: var(--el-text-color-secondary);
}
/* F-125 §2：分页器行（照 Orders.vue 同款右对齐） */
.pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.panel {
  margin-top: 14px;
  padding: 16px;
  background: var(--admin-card);
  border: 1px solid var(--admin-border);
  border-radius: 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.reconcile-ok {
  color: var(--admin-ok-text);
  font-weight: 600;
}
.reconcile-bad {
  color: var(--el-color-danger);
  font-weight: 700;
}
.field-warn {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-color-warning);
}
.field-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.field-error {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-color-danger);
}
.option-meta {
  margin-left: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.generated-hint {
  margin-top: 10px;
  padding: 8px 12px;
  background: var(--admin-ok-bg);
  border: 1px solid var(--admin-ok-border);
  border-radius: 6px;
  color: var(--admin-ok-text);
  font-size: 13px;
}
.dialog-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
/* 对账 ✗ 数据事故行：整行标红（全局样式作用点，scoped 对 row-class-name 生成的行类不可达，
   故走 :deep 穿透——与表格行内 el-tag 的既有用法同一机制） */
:deep(.el-table .settle-broken),
:deep(.el-table .settle-broken td.el-table__cell) {
  background: var(--admin-danger-bg) !important;
}
/* 明细弹窗孤儿行（orders 已删）同款标红警示 */
:deep(.el-table .item-orphan),
:deep(.el-table .item-orphan td.el-table__cell) {
  background: var(--admin-danger-bg) !important;
}
.orphan-tag {
  margin-left: 6px;
}
.orphan-cell {
  color: var(--el-color-danger);
  font-size: 12px;
}
/* ==================== P10-5 D7 保证金管理区 ==================== */
.deposit-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}
.deposit-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.deposit-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  margin: 12px 0;
}
.deposit-table {
  margin-top: 4px;
}
/* 账期列表保证金列：本期有代扣的行加警示色读数（无代扣 0.00 走默认灰阶） */
.deposit-cell-active {
  color: var(--el-color-warning);
  font-weight: 600;
}
</style>
