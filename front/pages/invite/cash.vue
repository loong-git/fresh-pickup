<template>
  <view class="page-container">
    <!-- ==================== 1. 红色头部 + 余额大卡（白卡叠红头，对齐 invite.vue 风格） ==================== -->
    <view class="cash-header">
      <!-- 顶部互链（F-04.3）：邀好友得券（券轨入口，与 invite 页现金入口互链） -->
      <view class="header-links">
        <text class="header-link" @click="goInvite">邀好友得券 ›</text>
      </view>
      <text class="cash-slogan">邀请好友，赚现金奖励</text>
      <text class="cash-slogan-sub">每邀 1 位新人完成首单，你得 5 元现金，满 20 元即可提现</text>
      <!-- 余额大卡：登录态展示 ¥balance + 提现按钮；游客态引导登录 -->
      <view class="balance-card">
        <text class="balance-label">可提现余额（元）</text>
        <!-- 游客态占位：······；加载中 --；成功展示两位小数余额 -->
        <view class="balance-value-row" v-if="hasToken">
          <text class="balance-symbol">¥</text>
          <text class="balance-value">{{ loaded ? balanceText : '--' }}</text>
        </view>
        <view class="balance-value-row" v-else>
          <text class="balance-symbol balance-symbol-guest">¥</text>
          <text class="balance-value balance-value-guest">····</text>
        </view>
        <!-- 累计已入账（登录且加载成功后展示，让用户看到历史总收益） -->
        <text class="earned-tip" v-if="hasToken && loaded">累计已入账 ¥{{ totalEarnedText }}</text>
        <!-- 提现按钮三态（F-04.3 定稿口径「满 20 可提现全部余额」）：
             prod 置灰「提现功能即将开放」；<20 置灰「还差 X 元可提现」；≥20 可点「立即提现」 -->
        <button
          v-if="hasToken"
          class="btn-withdraw"
          :class="{ 'btn-withdraw-disabled': withdrawDisabled }"
          :disabled="withdrawDisabled"
          @click="onWithdraw"
        ><text>{{ withdrawBtnText }}</text></button>
        <button v-else class="btn-withdraw" @click="goLogin"><text>去登录</text></button>
        <text class="withdraw-note" v-if="hasToken && loaded && !isProd && canWithdraw">满 20 可提现全部余额</text>
        <!-- 演示环境到账明示（F-04.5 边界：三处明示之一，防虚假到账承诺） -->
        <text class="withdraw-note withdraw-note-mock" v-if="hasToken && loaded && !isProd && balanceNum > 0">演示环境模拟到账，不发生实际打款</text>
      </view>
    </view>

    <!-- ==================== 2. 冻结提示条（frozenTotal>0 才展示：注册冻结、新人首单支付后到账） ==================== -->
    <view class="card frozen-bar" v-if="hasToken && loaded && frozenTotalNum > 0">
      <text class="frozen-icon">🔒</text>
      <text class="frozen-text">冻结 ¥{{ frozenTotalText }}，新人完成首单后到账</text>
    </view>

    <!-- ==================== 3. 活动规则（静态内容，游客可见利于转化） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">规则说明</text></view>
      <view class="rule-item" v-for="(r, i) in rules" :key="i">
        <text class="rule-no">{{ i + 1 }}</text>
        <text class="rule-text">{{ r }}</text>
      </view>
    </view>

    <!-- ==================== 4. 现金流水（登录态拉取；类型标签/状态标签/±金额/时间倒序） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">现金流水</text></view>
      <!-- 游客态：登录引导 -->
      <view v-if="!hasToken" class="guide-row" @click="goLogin">
        <text class="guide-text">🔒 登录后查看我的现金余额与流水</text>
        <text class="guide-go">去登录 ›</text>
      </view>
      <template v-else>
        <!-- 错误态：加载失败可点击重试（口径照 invite 页错误行） -->
        <view v-if="loadError" class="error-row" @click="loadCash">
          <text class="error-text">现金信息加载失败，请检查网络</text>
          <text class="error-retry">点击重试</text>
        </view>
        <!-- 流水列表：类型标签 + 状态标签 + ±金额 + 时间（倒序，后端已排） -->
        <view v-else-if="flows.length > 0" class="flow-list">
          <view class="flow-item" v-for="(f, i) in flows" :key="i">
            <view class="flow-info">
              <view class="flow-title-row">
                <text class="flow-type" :class="f.type === 2 ? 'ft-withdraw' : 'ft-reward'">{{ flowTypeText(f) }}</text>
                <text class="flow-badge" :class="flowBadgeClass(f)">{{ flowStatusText(f) }}</text>
              </view>
              <text class="flow-time">{{ formatDay(f.createdAt) }}</text>
            </view>
            <text class="flow-amount" :class="f.type === 2 ? 'fa-out' : 'fa-in'">{{ f.type === 2 ? '-' : '+' }}¥{{ amountText(f.amount) }}</text>
          </view>
        </view>
        <view v-else class="empty-row">
          <text class="empty-text">{{ loaded ? '还没有现金流水，邀请好友赚现金吧' : '加载中…' }}</text>
        </view>
      </template>
    </view>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 邀请赚现金页（F-04.3，多多买菜风格，白底+主红 #E02020，默认导航栏标题「邀请赚现金」）：
// 余额大卡（¥balance + 累计已入账 + 提现按钮）+ 冻结提示条（frozenTotal=0 隐藏）+ 规则说明
// + 现金流水列表（type 1=邀请奖励 2=提现；status 0=冻结中 1=已入账）+ 顶部互链「邀好友得券 ›」。
// 提现定稿口径「满 20 可提现全部余额」：<20 置灰显示差额、≥20 可点；
// prod 环境提现入口置灰「提现功能即将开放」（front/api/config.js 同款 NODE_ENV 判断）；
// 演示环境成功 toast/按钮注/规则三处均明示「模拟到账，不发生实际打款」（F-04.5 资损口碑防护）。
// 登录态判定与渲染响应 store.user.token（mapState user）：401 时 request 层统一清登录态，
// 本页随 store 自动降级游客视图；未登录 goLogin 带 redirect 回本页。
import { mapState } from 'vuex'
import { getCashInfo, withdrawCash } from '@/api/index.js'

// prod 环境判断（口径同 config.js:11 BASE_URL）：prod 提现入口置灰，接口保留登录态
const IS_PROD = process.env.NODE_ENV === 'production'

export default {
  data() {
    return {
      // 加载三态：loading 防重 / loadError 错误可重试 / loaded 成功后展示空态文案与按钮注
      loading: false,
      loadError: false,
      loaded: false,
      // 提交防重：提现请求进行中按钮禁用
      withdrawing: false,
      // /api/invite/cash 契约字段（F-04.2）：脏数据统一兜底
      balance: 0,
      frozenTotal: 0,
      totalEarned: 0,
      canWithdraw: false,
      withdrawMin: 20,
      flows: [],
      // 规则说明五条（F-04.3 定稿口径：冻结→首单后入账、5 元/新人、10 人封顶、满 20 提全部余额、演示环境模拟到账明示）
      rules: [
        '邀请好友注册，TA 完成首单支付后，你的 5 元现金奖励自动入账（注册时先冻结）',
        '每邀请 1 位新人得 5 元，最多奖励 10 人',
        '余额满 20 元可提现全部余额，提现 1 分钟限 1 次',
        '演示环境模拟到账，不发生实际打款；现金为独立余额，不与下单抵扣打通'
      ]
    }
  },
  computed: {
    ...mapState(['user']),
    // 是否已登录（token 鉴权）：401 时 request 层清登录态，此处响应式切换游客视图
    hasToken() { return !!(this.user && this.user.token) },
    // 数值兜底归一：后端 DECIMAL 可能回数字或字符串，统一 Number（NaN 归 0）
    balanceNum() { return this.toNum(this.balance) },
    frozenTotalNum() { return this.toNum(this.frozenTotal) },
    totalEarnedNum() { return this.toNum(this.totalEarned) },
    // prod 环境（isProd 挂 computed 供模板用）：提现入口置灰
    isProd() { return IS_PROD },
    // 可提现判定：契约 canWithdraw 优先，本地按 balance>=withdrawMin 兜底（口径一致）
    canWithdrawNow() {
      return this.balanceNum >= (this.toNum(this.withdrawMin) || 20)
    },
    // 提现按钮禁用态：prod 置灰 / 余额不足置灰 / 加载未完成置灰 / 提交中置灰
    withdrawDisabled() {
      if (this.isProd) return true
      if (!this.loaded) return true
      if (this.withdrawing) return true
      return !this.canWithdrawNow
    },
    // 提现按钮文案（F-04.3）：prod「提现功能即将开放」；<20 显示差额；≥20「立即提现」（提交中防重文案）
    withdrawBtnText() {
      if (this.isProd) return '提现功能即将开放'
      if (!this.loaded) return '加载中…'
      if (this.withdrawing) return '提现中…'
      if (this.canWithdrawNow) return '立即提现'
      const gap = (this.toNum(this.withdrawMin) || 20) - this.balanceNum
      return `还差 ${gap.toFixed(2)} 元可提现`
    },
    // 余额展示文本（两位小数）
    balanceText() { return this.amountText(this.balance) },
    frozenTotalText() { return this.amountText(this.frozenTotal) },
    totalEarnedText() { return this.amountText(this.totalEarned) }
  },
  onShow() {
    // 每次进入刷新（从登录页回跳后登录态变化同步数据；提现/入账后回页也刷新）；未登录不发请求
    if (this.hasToken) this.loadCash()
  },
  watch: {
    // 登录态变化兜底：会话内被 401 清态时随 store 清空已登录数据，切游客视图不残留
    hasToken(v) {
      if (v) {
        this.loadCash()
      } else {
        this.loaded = false
        this.loadError = false
        this.balance = 0
        this.frozenTotal = 0
        this.totalEarned = 0
        this.canWithdraw = false
        this.flows = []
      }
    }
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照 invite 页同款方案）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  onUnload() {
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
  },
  // #endif
  methods: {
    // ==================== 数据加载 ====================
    // GET /api/invite/cash（登录，token 由 request 层自动携带）
    async loadCash() {
      if (!this.hasToken || this.loading) return
      this.loading = true
      try {
        const res = await getCashInfo()
        const d = (res && res.data) || {}
        this.balance = d.balance
        this.frozenTotal = d.frozenTotal
        this.totalEarned = d.totalEarned
        this.canWithdraw = d.canWithdraw === true || Number(d.canWithdraw) === 1
        this.withdrawMin = d.withdrawMin != null ? d.withdrawMin : 20
        this.flows = Array.isArray(d.flows) ? d.flows : []
        this.loaded = true
        this.loadError = false
      } catch (e) {
        // 401（token 失效）：request 层已清登录态，页面响应式切游客视图，不再置错误态；其余失败显示错误态可重试
        if (!e || e.code !== 401) this.loadError = true
      } finally {
        this.loading = false
      }
    },
    // ==================== 提现 ====================
    // POST /api/invite/cash/withdraw（后端限流 1 次/分，余额不足 code:400 差额文案透出）：
    // 成功 toast 明示模拟到账（F-04.5），并刷新余额/流水；失败 toast message 保留现场
    onWithdraw() {
      if (this.withdrawDisabled) return
      const amountText = this.balanceText
      this.withdrawing = true
      withdrawCash().then(() => {
        uni.showToast({ title: `提现申请成功，¥${amountText} 将到账（演示环境模拟到账，不发生实际打款）`, icon: 'none', duration: 3000 })
        this.loadCash()
      }).catch((e) => {
        uni.showToast({ title: (e && e.message) || '提现失败，请稍后重试', icon: 'none' })
      }).finally(() => {
        this.withdrawing = false
      })
    },
    // ==================== 展示辅助 ====================
    // 数值归一：DECIMAL 回数字或字符串统一 Number，脏数据归 0
    toNum(v) {
      const n = Number(v)
      return Number.isFinite(n) ? n : 0
    },
    // 金额两位小数（脏数据归 0.00）
    amountText(v) {
      return this.toNum(v).toFixed(2)
    },
    // 流水类型文案/配色：1=邀请奖励 2=提现（脏数据按邀请奖励兜底不崩溃）
    flowTypeText(f) {
      return Number(f && f.type) === 2 ? '提现' : '邀请奖励'
    },
    // 状态标签：type=2 直接「提现」灰标；type=1 按 status 0=冻结中（橙）1=已入账（绿），
    // 后端若下发 statusText 人话直显（口径照 invite 页 statusText 优先）
    flowStatusText(f) {
      const t = f && f.statusText
      if (typeof t === 'string' && t) return t
      if (Number(f && f.type) === 2) return '提现'
      return Number(f && f.status) === 1 ? '已入账' : '冻结中'
    },
    flowBadgeClass(f) {
      if (Number(f && f.type) === 2) return 'rb-withdraw'
      return Number(f && f.status) === 1 ? 'rb-done' : 'rb-wait'
    },
    // 时间 -> yyyy-MM-dd（脏数据返回空串由模板隐藏；口径照 invite 页 formatDay）
    formatDay(v) {
      if (!v) return ''
      const d = new Date(v)
      if (!Number.isFinite(d.getTime())) return ''
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    },
    // ==================== 跳转 ====================
    // 游客态去登录：携带 redirect 回本页，登录成功后解码回跳（redirect 须为 / 开头页面路径）
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/invite/cash') }) },
    // 顶部互链：跳「邀请好友」（券轨，F-04 两页互链）
    goInvite() { uni.navigateTo({ url: '/pages/invite/invite' }) },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    onPageWheel(e) {
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* ==================== 多多买菜风格 — 邀请赚现金页（白底 + 主红 #E02020，对齐 invite 页） ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; box-sizing: border-box; }

/* ==================== 1. 红色头部 + 余额大卡（白卡叠红头下缘） ==================== */
.cash-header {
  position: relative;
  background: linear-gradient(180deg, #E02020 0%, #E02020 55%, #F5F5F5 100%);
  padding: 40rpx 24rpx 8rpx;
}
/* 顶部互链行：右对齐白色半透明胶囊（与 invite 页现金互链对称） */
.header-links { display: flex; justify-content: flex-end; margin-bottom: 4rpx; }
.header-link {
  font-size: 24rpx; font-weight: 600; color: #FFFFFF;
  background-color: rgba(255, 255, 255, 0.18);
  border: 1rpx solid rgba(255, 255, 255, 0.55);
  border-radius: 999rpx;
  padding: 8rpx 24rpx; line-height: 1.4;
}
.cash-slogan { display: block; text-align: center; font-size: 38rpx; font-weight: 700; color: #FFFFFF; line-height: 1.3; }
.cash-slogan-sub { display: block; margin-top: 12rpx; text-align: center; font-size: 22rpx; color: rgba(255, 255, 255, 0.92); line-height: 1.5; }
.balance-card {
  margin-top: 32rpx;
  background-color: #FFFFFF; border-radius: 24rpx;
  padding: 40rpx 32rpx 36rpx;
  box-shadow: 0 4rpx 16rpx rgba(224,32,32,.08);
  display: flex; flex-direction: column; align-items: center;
}
.balance-label { font-size: 24rpx; color: #999999; line-height: 1; }
/* ¥balance 大字：¥ 小一号上标风格，余额大字加粗主红；占位同色系防跳动 */
.balance-value-row { margin-top: 20rpx; display: flex; align-items: baseline; justify-content: center; }
.balance-symbol { font-size: 44rpx; font-weight: 700; color: #E02020; margin-right: 6rpx; }
.balance-value {
  font-size: 88rpx; font-weight: 800; line-height: 1.1;
  color: #E02020;
  font-family: "SF Mono", Menlo, Consolas, monospace;
  max-width: 100%;
}
.balance-symbol-guest, .balance-value-guest { color: #CCCCCC; }
.earned-tip { margin-top: 12rpx; font-size: 22rpx; color: #999999; line-height: 1.5; }
/* 提现主按钮（显式覆盖 uni-button 默认圆角/行高/边框，照 invite 页 btn-copy 口径） */
.btn-withdraw {
  width: 100%; height: 88rpx; line-height: 88rpx;
  margin-top: 32rpx; padding: 0;
  background-color: #E02020; color: #FFFFFF;
  font-size: 32rpx; font-weight: 700;
  border: none; border-radius: 999rpx;
}
.btn-withdraw.btn-withdraw-disabled { background-color: #F5B5B0; color: rgba(255, 255, 255, 0.9); }
/* 按钮注（满 20 提全部余额 / 演示环境模拟到账明示） */
.withdraw-note { margin-top: 16rpx; font-size: 22rpx; color: #999999; line-height: 1.5; text-align: center; }
.withdraw-note-mock { color: #FF6600; }

/* ==================== 2. 冻结提示条（frozenTotal>0 展示） ==================== */
.frozen-bar { display: flex; align-items: center; gap: 12rpx; padding: 20rpx 24rpx; }
.frozen-icon { font-size: 28rpx; line-height: 1; }
.frozen-text { font-size: 24rpx; color: #FF6600; line-height: 1.5; }

/* ==================== 白卡通用（照 invite 页） ==================== */
.card {
  background-color: #FFFFFF; border-radius: 16rpx;
  margin: 20rpx 24rpx 0;
  padding: 24rpx;
}
.card-head { margin-bottom: 20rpx; }
.card-title { font-size: 30rpx; font-weight: 700; color: #333333; }

/* ==================== 3. 规则说明 ==================== */
.rule-item { display: flex; align-items: flex-start; gap: 16rpx; margin-bottom: 20rpx; }
.rule-item:last-child { margin-bottom: 0; }
.rule-no {
  flex-shrink: 0; width: 36rpx; height: 36rpx; border-radius: 50%;
  background-color: #FFECE8; color: #E02020;
  font-size: 22rpx; font-weight: 700; text-align: center; line-height: 36rpx;
}
.rule-text { flex: 1; min-width: 0; font-size: 26rpx; color: #666666; line-height: 1.6; }

/* 游客态登录引导行（照 invite 页） */
.guide-row {
  display: flex; align-items: center; justify-content: space-between;
  background-color: #FFF7F0; border-radius: 12rpx;
  padding: 24rpx;
}
.guide-text { font-size: 26rpx; color: #666666; }
.guide-go { flex-shrink: 0; margin-left: 16rpx; font-size: 26rpx; font-weight: 600; color: #E02020; }

/* 错误态行（可点击重试，照 invite 页） */
.error-row { display: flex; flex-direction: column; align-items: center; gap: 16rpx; padding: 32rpx 0; }
.error-text { font-size: 24rpx; color: #999999; }
.error-retry {
  font-size: 24rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 28rpx;
  padding: 8rpx 36rpx; line-height: 1.4;
}

/* ==================== 4. 现金流水列表（类型+状态标签 / ±金额 / 时间倒序） ==================== */
.flow-list { display: flex; flex-direction: column; }
.flow-item { display: flex; align-items: center; gap: 16rpx; padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5; }
.flow-item:last-child { border-bottom: none; padding-bottom: 4rpx; }
.flow-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.flow-title-row { display: flex; align-items: center; gap: 12rpx; }
.flow-type { font-size: 28rpx; font-weight: 600; color: #333333; }
.flow-time { font-size: 22rpx; color: #CCCCCC; }
.flow-badge { flex-shrink: 0; font-size: 20rpx; font-weight: 700; padding: 4rpx 14rpx; border-radius: 20rpx; }
/* 状态徽标配色照 invite 页/我的页体系：冻结中橙 / 已入账绿 / 提现灰 */
.rb-wait { color: #FF6600; background-color: #FFF3EB; }
.rb-done { color: #07C160; background-color: #E8F8EF; }
.rb-withdraw { color: #999999; background-color: #F5F5F5; }
/* 金额：奖励 + 主红 / 提现 - 灰（支出弱化） */
.flow-amount { flex-shrink: 0; font-size: 30rpx; font-weight: 700; font-family: "SF Mono", Menlo, Consolas, monospace; }
.fa-in { color: #E02020; }
.fa-out { color: #999999; }

/* 空态行 */
.empty-row { display: flex; justify-content: center; padding: 40rpx 0; }
.empty-text { font-size: 24rpx; color: #CCCCCC; }

.page-bottom-space { height: env(safe-area-inset-bottom); }
</style>
