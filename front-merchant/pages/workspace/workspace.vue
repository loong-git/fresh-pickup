<template>
  <view class="page-container">
    <!-- ==================== 1. 「暂无商家权限」引导态（me 回 403）
             与登录页同源语义：账号能登、角色不对，故不清登录态也不报「未登录」，
             只透出后端 message 并给「换个账号」出口（401 不在本页处理，请求层已统一回登录页） ==================== -->
    <view v-if="viewState === 'forbidden'" class="card guide-card">
      <text class="guide-title">暂无商家权限</text>
      <text class="guide-text">当前账号已登录，但不是商家账号，工作台不对其开放</text>
      <text v-if="forbiddenMessage" class="guide-tip">{{ forbiddenMessage }}</text>
      <button class="btn-main" @click="switchAccount"><text>换个账号</text></button>
      <!-- token 还在，刚被平台开通的商家点「重新检查」只重发一次 me 即可 200，不必清态重收验证码（换账号是重路径） -->
      <text class="guide-link" @click="loadMe">我已开通，重新检查</text>
    </view>

    <!-- ==================== 2. 首次加载中（store 无 me 快照时的占位，避免空白页） ==================== -->
    <view v-else-if="viewState === 'loading'" class="card checking-card">
      <text class="checking-text">正在加载商家资料…</text>
    </view>

    <!-- ==================== 3. 加载失败（区别于 403 引导态：可点重试；404「商家不存在」这类确定性失败重试永远失败，故同给换账号出口，与 P0-1 一起兜住死路） ==================== -->
    <view v-else-if="viewState === 'error'" class="card">
      <view class="error-row">
        <text class="error-text">{{ errorText || '商家资料加载失败，请检查网络' }}</text>
        <text class="error-retry" @click="loadMe">点击重试</text>
        <text class="guide-link" @click="switchAccount">换个账号</text>
      </view>
    </view>

    <!-- ==================== 4. 正常态：商家卡 + 登出/换账号出口 + 三个建设中入口 ==================== -->
    <template v-else>
      <view class="card merchant-card">
        <view class="merchant-head">
          <text class="merchant-name">{{ merchantName }}</text>
          <view class="merchant-tags">
            <text class="audit-tag" :class="'audit-' + auditKey">{{ auditText }}</text>
          </view>
          <!-- 商家生命周期状态只读透出（值域 active/suspended/terminated，见 m15-migration.sql:22）；
               停业/清退的执行拦截属 G-02/G-06，本页只保证「被停的商家看得到自己是什么状态」，不做任何跳转拦截 -->
          <text v-if="status && status !== 'active'" class="status-tip">商家当前状态：{{ statusText }}</text>
        </view>
        <view class="merchant-rows">
          <text class="merchant-row">联系人：{{ contactText }}</text>
          <!-- 手机号已由后端 maskPhone 脱敏输出（R4），前端不做二次处理也不猜原号 -->
          <text class="merchant-row">联系手机：{{ contactPhoneText }}</text>
        </view>
        <!-- 每次 onShow 重拉 me 的成功时刻（资料变更/审核状态流转后回页即可见，验收时用它辨认刷新确实发生） -->
        <text v-if="refreshText" class="refresh-tip">资料刷新于 {{ refreshText }}</text>
        <!-- 会话内已有完整快照时，onShow 重拉失败降级为本行提示，不把整页换成错误卡（对照用户端 mine.vue 分区块错误态） -->
        <text v-if="refreshFailed" class="refresh-fail" @click="loadMe">资料刷新失败，点击重试</text>
      </view>

      <!-- 正常态唯一登出/换账号出口：没有这一行，已登录用户既退不出也换不了号（403 分支的 switchAccount 走不到）；
           走 confirmSwitchAccount 带二次确认，403/错误态那两个出口仍直连 switchAccount -->
      <view class="card cell-card">
        <view class="cell" @click="confirmSwitchAccount">
          <text class="cell-logout">换个账号 / 退出登录</text>
        </view>
      </view>

      <!-- 三入口一期全置灰不可进（§3.3）：不给 › 箭头，只挂「建设中」角标；点击仅 toast 不跳转 -->
      <view class="card cell-card">
        <view
          class="cell cell-disabled"
          v-for="entry in entries"
          :key="entry.key"
          @click="onEntryTap"
        >
          <text class="cell-icon">{{ entry.icon }}</text>
          <text class="cell-title">{{ entry.title }}</text>
          <text class="cell-sub">{{ entry.hint }}</text>
          <text class="tag-building">建设中</text>
        </view>
      </view>
    </template>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 商家工作台占位页（G-01 §3.3）：一期只做「我是谁 + 三个建设中入口」，G-02 起逐页替换。
// 数据加载纪律照用户端 front/pages/mine/mine.vue：onShow 每次重拉 me，回页即反映服务端最新状态。
// 页面不预判角色：role 判定与 401/403 分流全在 api 层（handleEnvelope），本页只按 code 渲染三态。
import { getMerchantMe } from '@/api/index.js'
import '@/styles/common.css'

// 资质审核三态文案（列值域见 m15-migration.sql:40 pending/approved/rejected）
const AUDIT_TEXT = {
  pending: '资质审核中',
  approved: '资质已通过',
  rejected: '资质已驳回'
}

// 商家生命周期三态文案（列值域见 m15-migration.sql:22 active/suspended/terminated）
const STATUS_TEXT = {
  active: '正常营业',
  suspended: '停业整顿',
  terminated: '已清退'
}

export default {
  data() {
    return {
      // 页面四态互斥（避免多面布尔标志互相打架留脏态）：loading | ready | forbidden | error
      viewState: 'loading',
      // me 出参快照（本页自持一份，渲染不依赖 store，保证 onShow 重拉后一定是服务端最新值）
      merchant: null,
      // error 态文案（后端 message 原文，无则兜底话术）
      errorText: '',
      // 403 引导态透出后端原文
      forbiddenMessage: '',
      // 本次 me 成功时刻（ms），仅驱动「资料刷新于」一行
      refreshAt: 0,
      // onShow 重拉失败但会话已有商家快照时的降级标记：卡片照常展示，只在卡底追加一行重试提示
      refreshFailed: false,
      // 一期占位三入口（静态列表放 data，与用户端 my-coupons.vue 的 couponTabs 同款；
      // key 仅作 v-for key 与 G-02 路由预留，本页不跳转）
      entries: [
        { key: 'goods', icon: '🛒', title: '商品管理', hint: '上新/改价/库存' },
        { key: 'stock', icon: '📦', title: '备货单', hint: '备货/发货' },
        { key: 'dashboard', icon: '📊', title: '数据看板', hint: '销量/核销' }
      ]
    }
  },
  computed: {
    merchantName() {
      return (this.merchant && this.merchant.name) || '未命名商家'
    },
    contactText() {
      const c = this.merchant && this.merchant.contactName
      return c || '未填写'
    },
    contactPhoneText() {
      const p = this.merchant && this.merchant.contactPhone
      return p || '未填写'
    },
    // 审核状态：profile.auditStatus 取值不在三态内时不猜文案（脏值原样展示，便于当场发现数据问题）
    auditKey() {
      const s = this.merchant && this.merchant.profile ? this.merchant.profile.auditStatus : ''
      return AUDIT_TEXT[s] ? s : 'unknown'
    },
    auditText() {
      const s = this.merchant && this.merchant.profile ? this.merchant.profile.auditStatus : ''
      return AUDIT_TEXT[s] || (s ? '未知状态：' + s : '资质状态未返回')
    },
    // 商家生命周期状态（后端 me.status，MerchantServiceImpl.java:66）：脏值/未知值原样展示，不替后端猜
    status() {
      return (this.merchant && this.merchant.status) || ''
    },
    statusText() {
      const s = this.status
      return STATUS_TEXT[s] || s
    },
    refreshText() {
      return this.refreshAt ? this.formatClock(this.refreshAt) : ''
    }
  },
  onShow() {
    // 每次进入重拉（对齐用户端 mine.vue onShow 刷新惯例）：审核状态与联系人改了都能第一时间反映
    this.loadMe()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动
    // （照用户端 my-coupons.vue 简版；弹层只有换账号确认的 showModal，瞬态小卡，故不带我的页那套弹层放行守卫）
    this._pageWheelHandler = (e) => this.onPageWheel(e)
    document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
  },
  // #endif
  // 滚轮监听仅 H5 注册（上方 onReady #ifdef 内），非 H5 端 _pageWheelHandler 恒为 undefined，清理分支天然跳过
  onUnload() {
    // #ifdef H5
    // 页面级滚轮兜底监听清理（与 onReady 注册配对）
    if (this._pageWheelHandler) {
      document.removeEventListener('wheel', this._pageWheelHandler)
      this._pageWheelHandler = null
    }
    // #endif
  },
  methods: {
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // ==================== 数据加载 ====================
    // GET /api/merchant/me：200 → ready；403 → 引导态；401 → 请求层已清 merchant_token 并 reLaunch 回登录页，
    // 本页只收起加载态不再发第二个请求；其余（网络异常/400/500）→ 错误态可点重试
    async loadMe() {
      // 并发守卫：错误卡重试/引导卡「重新检查」/刷新区「点击重试」都可连点，
      // 慢失败不能盖掉快成功——同一时刻只放行一个 me 请求
      if (this._loading) return
      this._loading = true
      try {
        const res = await getMerchantMe()
        const me = (res && res.data) || null
        this.merchant = me
        this.refreshAt = Date.now()
        this.refreshFailed = false
        // 已有 token 却拿到没有角色的空响应：按「非商家」处理，绝不渲染工作台内容（不放水）
        this.viewState = (me && me.role === 'merchant') ? 'ready' : 'forbidden'
        this.forbiddenMessage = (me && me.role === 'merchant') ? '' : '未返回商家主体信息'
        if (this.viewState === 'ready') this.$store.commit('setMerchant', me)
      } catch (e) {
        if (e && e.code === 403) {
          this.forbiddenMessage = (typeof e.message === 'string' ? e.message : '').trim()
          this.viewState = 'forbidden'
          return
        }
        if (e && e.code === 401) {
          // 跳登录页由请求层统一负责（商家端无游客态）；此处只把视图切回加载态，避免闪现错误卡
          this.viewState = 'loading'
          return
        }
        // 会话内已有完整商家快照：保持 ready 展示，只降级卡底一行重试提示（偶发网络抖动不该把已看到的数据整页换掉）
        if (this.merchant) {
          this.refreshFailed = true
          this.viewState = 'ready'
          return
        }
        // 首屏无快照才是整页错误态
        this.errorText = (e && e.message) || ''
        this.viewState = 'error'
      } finally {
        this._loading = false
      }
    },
    // ==================== 交互 ====================
    // 三入口一期全置灰：只 toast 提示，不跳任何页（G-02 逐页替换后才挂路由）
    onEntryTap() {
      uni.showToast({ title: '功能建设中，敬请期待', icon: 'none', duration: 1500 })
    },
    // 登出/换账号的执行体：清 merchant_token 与快照后回登录页换账号（403 引导态 / 错误态兜底行直连，
    // 正常态那一行经 confirmSwitchAccount 二次确认后才走到这里）
    switchAccount() {
      this.$store.commit('clearToken')
      uni.reLaunch({ url: '/pages/login/login' })
    },
    // 正常态「换个账号 / 退出登录」入口的二次确认：误触代价=重新登录并重收一次验证码（P1-2 想消除的痛点），
    // 故只在这一路包一层 modal；403 引导态与错误态的「换个账号」是用户主动求助出口、账号本就进不去，
    // 无误触代价，不给它们加确认（否则「重新检查」那条自救路径也被拖啰嗦）
    confirmSwitchAccount() {
      uni.showModal({
        title: '换个账号',
        content: '将退出当前登录的商家账号，需重新用验证码登录。确定换账号？',
        confirmText: '退出',
        confirmColor: '#E02020',
        success: (res) => {
          if (!res.confirm) return
          this.switchAccount()
        }
      })
    },
    // 时间戳 -> HH:mm:ss（脏值返回空串，模板随之隐藏这一行）
    formatClock(ms) {
      const d = new Date(ms)
      if (!Number.isFinite(d.getTime())) return ''
      const p = (n) => String(n).padStart(2, '0')
      return `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
    },
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
/* 多多红单色板：灰底页面 + 白卡（.card/.cell-card/.guide-card/.btn-main 等公共类在 styles/common.css） */

/* ==================== 1/2/3. 单卡承载的三态（本页自加间距，公共类不带 margin） ==================== */
.guide-card { margin-top: 56rpx; }
.checking-card { margin-top: 56rpx; display: flex; justify-content: center; }
.checking-text { font-size: 26rpx; color: #999999; line-height: 1.6; }
.guide-card .btn-main { margin-top: 16rpx; }
/* 后端 403 原文透出行：小字主红，与本页自撰文案区分，避免用户以为原因是前端编的 */
.guide-tip { font-size: 22rpx; color: #E02020; opacity: .75; line-height: 1.5; text-align: center; }

/* ==================== 4. 商家卡 ==================== */
.merchant-card { margin-top: 24rpx; }
/* 名称与徽标/状态行纵向排布：长名两行截断后不再与徽标同行，天然不重叠 */
.merchant-head { display: flex; flex-direction: column; align-items: flex-start; gap: 8rpx; }
/* 主体名后端锁定平台维护，前端只读展示；name 为 VARCHAR(100)（m15-migration.sql:19），
   单行截断会无处看全名，故改两行截断（-webkit-line-clamp 系，uniapp H5/小程序端均支持） */
.merchant-name {
  width: 100%;
  font-size: 34rpx; font-weight: 700; color: #333333; line-height: 1.4;
  display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2;
  overflow: hidden;
}
.merchant-tags { display: flex; align-items: center; gap: 12rpx; }
.audit-tag {
  flex-shrink: 0;
  font-size: 22rpx; font-weight: 600; line-height: 1.4;
  border-radius: 8rpx; padding: 6rpx 16rpx;
}
.audit-approved { color: #FFFFFF; background-color: #E02020; }
.audit-pending { color: #E02020; background-color: #FFF7F0; }
.audit-rejected { color: #E02020; background-color: #FFFFFF; border: 1rpx solid #E02020; }
/* 脏值/缺字段：中性灰，不与三态任一档混淆（不替后端猜状态） */
.audit-unknown { color: #999999; background-color: #F5F5F5; }
.merchant-rows { display: flex; flex-direction: column; gap: 8rpx; margin-top: 20rpx; }
.merchant-row { font-size: 24rpx; color: #666666; line-height: 1.6; }
.refresh-tip { display: block; margin-top: 20rpx; font-size: 20rpx; color: #CCCCCC; line-height: 1.4; }
/* 非 active 生命周期的提示行：主红小字（色板内既有值），只读展示不拦截 */
.status-tip { font-size: 22rpx; color: #E02020; opacity: .8; line-height: 1.5; }
/* 有快照时 onShow 重拉失败的降级重试行：与刷新时间行同位呼应，可点 */
.refresh-fail { display: block; margin-top: 8rpx; font-size: 22rpx; color: #E02020; line-height: 1.5; }

/* ==================== 4.5 登出/换账号行（.cell 体系取自 common.css，仅本页补居中红色字） ==================== */
.cell-logout { flex: 1; text-align: center; font-size: 28rpx; font-weight: 600; color: #E02020; }

/* ==================== 5. 三入口列表（.cell/.cell-disabled/.tag-building 均取自 common.css） ==================== */
.cell-card { margin-top: 24rpx; }
</style>
