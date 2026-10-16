<template>
  <view class="page-container">
    <view class="page-header">
      <text class="page-title">我的订单</text>
      <text class="page-sub" v-if="hasToken && total > 0">共 {{ total }} 单</text>
    </view>

    <!-- 四状态 tab（全部/待自提/已完成/已取消）：对已加载数据做前端过滤 -->
    <view class="status-tabs" v-if="hasToken">
      <view
        class="status-tab"
        v-for="t in tabs"
        :key="t.key"
        :class="{ active: activeTab === t.key }"
        @click="switchTab(t.key)"
      >
        <text>{{ t.label }}</text>
      </view>
    </view>

    <!-- 引导态：未登录（M2 归属升级：订单按 token userId 过滤；401 已由 request 层清登录态，此处响应式呈现） -->
    <view v-if="!hasToken" class="guide-state">
      <text class="empty-icon">📱</text>
      <text class="empty-label">登录后查看订单</text>
      <text class="empty-sub">支持验证码或密码登录</text>
      <button class="btn-go" @click="goLogin">去登录</button>
    </view>

    <!-- 订单列表 -->
    <view v-else-if="filteredOrders.length > 0" class="order-list">
      <view class="order-card" v-for="order in filteredOrders" :key="order.id">
        <view class="order-top">
          <text class="order-id">订单号：{{ order.id }}</text>
          <view class="order-badges">
            <!-- 支付状态徽标（M2-T-05）：已支付绿 / 待支付橙 / 已退款灰（已支付单取消后端置 pay_status=2） -->
            <text class="pay-badge" :class="payStatus(order) === 1 ? 'pay-paid' : (payStatus(order) === 2 ? 'pay-refund' : 'pay-unpaid')">{{ payStatus(order) === 1 ? '已支付' : (payStatus(order) === 2 ? '已退款' : '待支付') }}</text>
            <text class="order-status" :class="'st-' + order.status">{{ statusMap[order.status] || order.status }}</text>
          </view>
        </view>

        <view class="order-items">
          <view class="order-item" v-for="(item, idx) in (order.items || [])" :key="idx">
            <text class="oi-name">{{ item.dishName }}</text>
            <text class="oi-qty">x{{ item.quantity }}</text>
            <text class="oi-price">¥{{ $fmtMoney((Number(item.price) || 0) * (Number(item.quantity) || 0)) }}</text>
          </view>
          <view v-if="!(order.items && order.items.length)" class="order-item-empty">
            <text>暂无明细</text>
          </view>
        </view>

        <view class="order-info">
          <!-- F-59 取货码：此前这一行在整个 C 端不存在（全仓零 pickupCode 引用），码只落在库与主理人端，
               提货人到店无从报码。数据本就在 GET /api/orders 出参里（Order#pickupCode），故零新接口、零新 SQL。
               放第一行且高亮：站在店里，这是这一屏唯一要看的东西。 -->
          <view v-if="canShowCode(order)" class="info-row info-row-code" @click="showCode(order)">
            <text class="info-label">取货码</text>
            <text class="info-val code-val">{{ order.pickupCode }}</text>
            <text class="code-hint">点击放大</text>
          </view>
          <view class="info-row">
            <text class="info-label">地址</text>
            <text class="info-val">{{ order.address || '到店自提（以自提点为准）' }}</text>
          </view>
          <view class="info-row">
            <text class="info-label">手机</text>
            <text class="info-val">{{ maskPhone(order.phone) }}</text>
          </view>
          <view class="info-row">
            <text class="info-label">时间</text>
            <text class="info-val">{{ formatTime(order.createTime) }}</text>
          </view>
        </view>

        <view class="order-footer">
          <!-- 待自提单可取消：仅 pending_pickup 显示，防重（取消中禁用），成功后刷新列表；
               已支付单取消即退款（后端置 pay_status=2），按钮文案区分 -->
          <button
            v-if="order.status === 'pending_pickup'"
            class="btn-cancel"
            :disabled="cancelingId === order.id"
            @click="onCancel(order)"
          >{{ cancelingId === order.id ? '取消中…' : (payStatus(order) === 1 ? '取消并退款' : '取消订单') }}</button>
          <text class="of-label">合计</text>
          <!-- M3-T-07：totalPrice 脏数据（null/undefined/非数字）由 $fmtMoney 按 0 兜底显示 0.00，不崩溃 -->
          <text class="of-price">¥{{ $fmtMoney(order.totalPrice) }}</text>
          <!-- 待支付单"去支付"（M2-T-05）：同收银台逻辑调 mock-pay，支付中禁用防重；
               仅 payStatus===0 展示（已支付/已退款均不入口） -->
          <button
            v-if="order.status === 'pending_pickup' && payStatus(order) === 0"
            class="btn-pay-order"
            :disabled="payingId === order.id"
            @click="onPay(order)"
          >{{ payingId === order.id ? '支付中…' : '去支付' }}</button>
        </view>
      </view>

      <!-- 加载更多：触底自动 + 按钮兜底 -->
      <view class="load-more">
        <button v-if="!finished" class="btn-more" :disabled="loading" @click="loadMore">
          {{ loading ? '加载中…' : '加载更多' }}
        </button>
        <text v-else class="no-more">— 没有更多了 —</text>
      </view>
    </view>

    <!-- 错误态（M3-T-05 部分）：接口异常时显示重试入口，区别于空态（请求失败不能当"暂无订单"） -->
    <view v-else-if="loadError" class="error-state">
      <text class="empty-icon">⚠️</text>
      <text class="empty-label">订单加载失败</text>
      <text class="empty-sub">网络异常或服务暂不可用，请稍后重试</text>
      <button class="btn-retry" @click="retryLoad">点击重试</button>
    </view>

    <!-- 空态：全部 tab 无单引导去逛逛；过滤 tab 无单给状态文案 -->
    <view v-else-if="!loading" class="empty-state">
      <template v-if="activeTab === 'all'">
        <text class="empty-icon">📦</text>
        <text class="empty-label">暂无订单</text>
        <text class="empty-sub">快去选购新鲜食材吧</text>
        <button class="btn-go" @click="goShopping">去逛逛</button>
      </template>
      <template v-else>
        <text class="empty-icon">🔍</text>
        <text class="empty-label">该状态下暂无订单</text>
      </template>
    </view>

    <!-- F-59 取货码出示层：主理人端核销台要的是「6 位数字」，而列表里那行 24rpx 的字在店门口隔着一个人递过去看不清，
         所以点开放大成屏。码只在「已支付 + 待自提」时存在（见 canShowCode），点遮罩或关闭即收起。 -->
    <view v-if="codeOrder" class="code-mask" @click="hideCode">
      <view class="code-panel" @click.stop>
        <text class="code-lead">到店报这 6 位数字</text>
        <text class="code-digits">{{ codeOrder.pickupCode }}</text>
        <text class="code-meta">{{ codeOrder.storeName || '到店自提（以自提点为准）' }}</text>
        <text class="code-meta">自提日 {{ codeOrder.pickupDate || '--' }}</text>
        <button class="code-close" @click="hideCode">关闭</button>
      </view>
    </view>
  </view>
</template>

<script>
import { mapState } from 'vuex'
import { getOrders, cancelOrder, mockPay } from '@/api/index.js'

// 订单三态 -> 中文徽标（pending_pickup/completed/cancelled）
const STATUS_MAP = { pending_pickup: '待自提', completed: '已完成', cancelled: '已取消' }

export default {
  data() {
    return {
      // 状态 tab：all + 订单三态（前端过滤已加载数据，切换不重新请求）
      tabs: [
        { key: 'all', label: '全部' },
        { key: 'pending_pickup', label: '待自提' },
        { key: 'completed', label: '已完成' },
        { key: 'cancelled', label: '已取消' }
      ],
      activeTab: 'all',
      // 已加载订单（分页拼接的全量归属数据）
      orders: [],
      // 服务端返回的当前登录用户订单总数
      total: 0,
      // 分页状态：pageNum 拉取后自增；pageSize 固定 10
      pageNum: 1,
      pageSize: 10,
      loading: false,
      // 没有更多：已加载数 >= total 或本页为空
      finished: false,
      // 取消订单防重锁：正在取消的订单 id
      cancelingId: '',
      // 去支付防重锁：正在支付的订单 id
      payingId: '',
      // F-59 取货码出示层当前展示的那一单（null=收起）
      codeOrder: null,
      // 错误态标记（M3-T-05 部分）：重置加载（首屏/重试）接口异常时置 true，显示错误态而非空态
      loadError: false,
      statusMap: STATUS_MAP
    }
  },
  computed: {
    ...mapState(['user']),
    // 是否已登录（M2：token 鉴权，未登录显示引导态；401 时 request 层清登录态，此处响应式切换）
    hasToken() { return !!(this.user && this.user.token) },
    // 四 tab 前端过滤：all 不过滤，其余按 status 精确匹配
    filteredOrders() {
      if (this.activeTab === 'all') return this.orders
      return this.orders.filter(o => o.status === this.activeTab)
    }
  },
  onShow() {
    // 每次进入页面重置到第一页重新拉取（新下单/新取消后数据最新）
    this.loadOrders(true)
  },
  // 触底自动加载下一页
  onReachBottom() {
    this.loadMore()
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照搜索页同款方案）
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
    async loadOrders(reset = true) {
      // 未登录（无 token / 401 后登录态已被 request 层清空）：不发请求，展示登录引导态
      if (!this.hasToken) return
      if (reset) {
        this.pageNum = 1
        this.orders = []
        this.total = 0
        this.finished = false
      }
      if (this.loading) return
      this.loading = true
      try {
        // 本次请求发起前先清错误标记（失败会在 catch 重新置位）
        this.loadError = false
        // M2 归属升级：不带 phone 参数，后端按 token 解析的 userId 过滤
        const res = await getOrders(this.pageNum, this.pageSize)
        const data = (res && res.data) || {}
        const list = Array.isArray(data.list) ? data.list : []
        this.total = Number(data.total) || 0
        this.orders = reset ? list : this.orders.concat(list)
        // 已加载满 total 或本页空 → 没有更多
        this.finished = this.orders.length >= this.total || list.length === 0
        this.pageNum++
      } catch (e) {
        // M3-T-05 部分：首屏/重试加载失败 → 错误态 + 点击重试（区别于空态）；
        // 翻页（loadMore）失败仅 toast 透出，列表保留现场；
        // 401 时登录态已被清空，页面同步切换登录引导态
        if (reset) {
          this.loadError = true
        } else {
          this.showToast((e && e.message) || '网络异常')
        }
      } finally {
        this.loading = false
      }
    },
    // 错误态重试（M3-T-05 部分）：清错误标记并重置到第一页重新拉取
    retryLoad() {
      this.loadError = false
      this.loadOrders(true)
    },
    // 加载更多（pageNum 递增）：触底与按钮共用
    loadMore() {
      if (this.loading || this.finished || !this.hasToken) return
      this.loadOrders(false)
    },
    // tab 切换：仅本地过滤，不重新请求
    switchTab(key) { this.activeTab = key },
    // 取消订单：仅待自提单展示入口；后端校验状态，成功后刷新列表
    async onCancel(order) {
      if (this.cancelingId) return
      this.cancelingId = order.id
      try {
        await cancelOrder(order.id)
        // 已支付单取消即退款（后端置 pay_status=2），文案区分；未支付单维持原提示
        uni.showToast({ title: this.payStatus(order) === 1 ? '订单已取消，退款已原路退回' : '订单已取消', icon: 'success' })
        // 重置到第一页刷新（状态变化后各 tab 数据同步更新）
        this.loadOrders(true)
      } catch (e) {
        // 非 pending_pickup 等 400 透出后端 message；网络异常提示可重试
        this.showToast((e && e.message) || '网络异常')
      } finally {
        this.cancelingId = ''
      }
    },
    // 待支付单"去支付"：同收银台逻辑调 mock-pay（幂等），成功后刷新列表同步支付徽标
    async onPay(order) {
      if (this.payingId) return
      this.payingId = order.id
      // #ifdef MP-WEIXIN
      // 小程序端支付分叉预留（T-M4-04）：真实环境应先调后端预支付接口换取支付参数，
      // 再调 uni.requestPayment({ provider: 'wxpay', timeStamp, nonceStr, package, signType, paySign, ... })
      // 拉起微信支付；当前小程序与 H5 统一走模拟支付 mock-pay 现状
      // await uni.requestPayment({ provider: 'wxpay', ... })
      // #endif
      try {
        const paid = await mockPay(order.id)
        // F-59：与首页收银台同一处理——码在这这一刻才存在，先回进提示；随后 loadOrders 刷新，
        // 该单的「取货码」行也就出现了（两处支付入口行为必须一致，不能一处有一处没有）
        const picked = paid && paid.data && paid.data.pickupCode
        if (picked) {
          uni.showToast({ title: '支付成功，取货码 ' + picked, icon: 'none', duration: 3000 })
        } else {
          uni.showToast({ title: '支付成功', icon: 'success' })
        }
        this.loadOrders(true)
      } catch (e) {
        // 401（登录态失效）：request 层已清登录态，页面响应式切换登录引导态；其余失败 toast 保留现场
        if (!e || e.code !== 401) this.showToast((e && e.message) || '网络异常')
      } finally {
        this.payingId = ''
      }
    },
    // 支付状态读取（0 未支付 / 1 已支付 / 2 已退款）：兼容 payStatus（实体驼峰 JSON）与 pay_status（下划线）两种字段名，缺失按未支付
    payStatus(order) {
      const v = Number(order && order.payStatus != null ? order.payStatus : (order && order.pay_status))
      return v === 1 || v === 2 ? v : 0
    },
    // 取货码可见性判据（与后端发码时机同源，不是前端偏好）：
    // ① 码是「支付成功那一刻」才生成的，下单时 orders.pickup_code 为 NULL → 未支付一律不显示，也不显示空白占位；
    // ② 核销完成（completed）或被取消（cancelled）后码已无用，继续留在列表里等于把凭证长期摊在旁人眼前 → 收掉。
    // 走 payStatus() 而不是直读 order.payStatus：后端字段名两种形态都要兼容，判据不能在这里开第二套。
    canShowCode(order) {
      return !!(order && order.pickupCode) && this.payStatus(order) === 1 && order.status === 'pending_pickup'
    },
    showCode(order) {
      this.codeOrder = order
    },
    hideCode() {
      this.codeOrder = null
    },
    // 手机号脱敏兜底展示（后端已脱敏则原样显示）：138****1234
    maskPhone(p) {
      const s = String(p || '')
      return /^1[3-9]\d{9}$/.test(s) ? s.replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2') : s
    },
    // 金额格式化已上收为全局 $fmtMoney（M3-T-07，main.js 注册），模板直接使用，此处不再重复实现
    formatTime(ts) {
      if (!ts) return '--'
      const d = new Date(ts)
      return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')} ${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
    },
    showToast(title) { uni.showToast({ title, icon: 'none', duration: 1500 }) },
    // 缺陷-12 修复：项目无 tabBar 配置，switchTab 会静默失败，改用 reLaunch 跳回首页
    goShopping() { uni.reLaunch({ url: '/pages/index/index' }) },
    // 未登录引导态"去登录"：跳独立登录页并携带 redirect，登录成功后解码回跳本订单页
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/order/order') }) },
    // ==================== H5 页面滚轮兜底 ====================
    // #ifdef H5
    // 页面级滚轮兜底：手动驱动 body 滚动（照搜索页同款方案）；本页无弹层，无需弹层放行守卫，
    // 驱动 body.scrollTop 会触发框架滚动监听，触底 onReachBottom 自动翻页随之恢复
    onPageWheel(e) {
      e.preventDefault()
      document.body.scrollTop += e.deltaY
    }
    // #endif
  }
}
</script>

<style scoped>
/* ==================== 天猫生鲜风格 — 订单页 ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; padding: 24rpx; box-sizing: border-box; }
.page-header { display: flex; align-items: baseline; justify-content: space-between; padding: 12rpx 8rpx 24rpx; }
.page-title { font-size: 34rpx; font-weight: 700; color: #333333; }
.page-sub { font-size: 24rpx; color: #999999; }

/* 四状态 tab */
.status-tabs { display: flex; gap: 12rpx; margin-bottom: 20rpx; }
.status-tab {
  flex: 1; text-align: center;
  font-size: 26rpx; color: #666666;
  background-color: #FFFFFF; border-radius: 24rpx;
  padding: 12rpx 0; border: 1rpx solid #EEEEEE;
}
.status-tab.active { color: #FFFFFF; font-weight: 700; background-color: #E02020; border-color: #E02020; }

/* 订单卡片 */
.order-card { background-color: #FFFFFF; border-radius: 12rpx; margin-bottom: 20rpx; overflow: hidden; }
.order-top { display: flex; justify-content: space-between; align-items: center; padding: 20rpx 24rpx; border-bottom: 1rpx solid #F5F5F5; }
.order-id { font-size: 24rpx; color: #999999; }
.order-status { font-size: 22rpx; font-weight: 700; padding: 4rpx 16rpx; border-radius: 20rpx; }
.st-pending_pickup { color: #FF6600; background-color: #FFF3EB; }
.st-completed { color: #999999; background-color: #F5F5F5; }
.st-cancelled { color: #BBBBBB; background-color: #FAFAFA; }

/* 支付状态徽标（M2-T-05）：已支付绿 / 待支付橙 */
.order-badges { display: flex; align-items: center; gap: 8rpx; flex-shrink: 0; }
.pay-badge { font-size: 20rpx; font-weight: 700; padding: 4rpx 14rpx; border-radius: 20rpx; }
.pay-paid { color: #07C160; background-color: #E8F8EF; }
.pay-unpaid { color: #FF6600; background-color: #FFF3EB; }
/* 已退款徽标（已支付单取消后端置 pay_status=2）：灰底灰字弱化展示 */
.pay-refund { color: #999999; background-color: #F5F5F5; }

.order-items { padding: 14rpx 24rpx; }
.order-item { display: flex; align-items: center; padding: 10rpx 0; }
/* 超长商品名 clamp（M3-T-07）：单行省略，min-width:0 允许 flex 子项收缩触发 ellipsis */
.oi-name { flex: 1; min-width: 0; font-size: 26rpx; color: #333333; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.oi-qty { font-size: 24rpx; color: #999999; margin: 0 20rpx; }
.oi-price { font-size: 26rpx; font-weight: 600; color: #FF3333; }
.order-item-empty { padding: 12rpx 0; font-size: 22rpx; color: #CCCCCC; }

.order-info { padding: 0 24rpx; border-top: 1rpx solid #F5F5F5; }
.info-row { display: flex; align-items: flex-start; padding: 12rpx 0; }
.info-label { font-size: 22rpx; color: #999999; width: 80rpx; flex-shrink: 0; }
.info-val { font-size: 22rpx; color: #666666; flex: 1; }

/* F-59 取货码行与出示层：字号刻意大于其余信息行——这一行是顾客在店门口唯一要递出去看的东西，
   和「地址/手机/时间」不是一个优先级。颜色跟站内已支付徽标同族（#07C160 系），不新造色板。 */
.info-row-code { align-items: center; }
.code-val { font-size: 34rpx; font-weight: 700; letter-spacing: 4rpx; color: #07A05A; }
.code-hint { font-size: 20rpx; color: #BBBBBB; flex-shrink: 0; }
.code-mask { position: fixed; left: 0; top: 0; right: 0; bottom: 0; background: rgba(0, 0, 0, 0.72); display: flex; align-items: center; justify-content: center; z-index: 9999; }
.code-panel { width: 600rpx; padding: 56rpx 40rpx 40rpx; background: #FFFFFF; border-radius: 24rpx; display: flex; flex-direction: column; align-items: center; }
.code-lead { font-size: 26rpx; color: #666666; }
.code-digits { font-size: 108rpx; font-weight: 800; letter-spacing: 12rpx; color: #1A1A1A; margin: 24rpx 0 8rpx; }
.code-meta { font-size: 24rpx; color: #999999; margin-top: 8rpx; text-align: center; }
.code-close { margin-top: 40rpx; width: 320rpx; height: 76rpx; line-height: 76rpx; font-size: 28rpx; color: #FFFFFF; background: #07A05A; border-radius: 38rpx; border: none; }

.order-footer { display: flex; align-items: center; justify-content: flex-end; gap: 12rpx; padding: 18rpx 24rpx; border-top: 1rpx solid #F5F5F5; }
.of-label { font-size: 24rpx; color: #999999; }
.of-price { font-size: 34rpx; font-weight: 700; color: #FF3333; }
/* 取消订单按钮：白底红字描边，取消中禁用置灰 */
.btn-cancel {
  margin-right: auto;
  font-size: 24rpx; font-weight: 600; color: #E02020;
  background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 28rpx;
  padding: 8rpx 28rpx; line-height: 1.4;
}
.btn-cancel[disabled] { color: #CCCCCC; border-color: #DDDDDD; background-color: #FAFAFA; }

/* 待支付单"去支付"按钮：红底白字主按钮（覆盖 uniapp button 默认样式含 ::after） */
.btn-pay-order {
  margin-left: 12rpx;
  font-size: 24rpx; font-weight: 600; color: #FFFFFF;
  background-color: #E02020; border: none; border-radius: 28rpx;
  padding: 8rpx 28rpx; line-height: 1.4;
}
.btn-pay-order::after { border: none; }
.btn-pay-order[disabled] { color: #FFFFFF; background-color: #F5B5B0; }

/* 加载更多 */
.load-more { display: flex; justify-content: center; padding: 8rpx 0 24rpx; }
.btn-more {
  min-width: 240rpx; font-size: 24rpx; color: #666666;
  background-color: #FFFFFF; border: 1rpx solid #EEEEEE; border-radius: 28rpx;
  padding: 10rpx 40rpx; line-height: 1.4;
}
.btn-more[disabled] { color: #CCCCCC; background-color: #FAFAFA; border-color: #EEEEEE; }
.no-more { font-size: 22rpx; color: #CCCCCC; padding: 10rpx 0; }

/* 引导态/空状态 */
.guide-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding-top: 240rpx; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding-top: 240rpx; }
.empty-icon { font-size: 80rpx; margin-bottom: 20rpx; }
.empty-label { font-size: 30rpx; font-weight: 700; color: #333333; margin-bottom: 10rpx; }
.empty-sub { font-size: 24rpx; color: #999999; margin-bottom: 40rpx; }
.btn-go { background: linear-gradient(90deg, #FF6B35, #FF6600); color: #FFFFFF; font-size: 26rpx; font-weight: 700; border: none; border-radius: 0; padding: 18rpx 56rpx; }

/* 错误态（M3-T-05 部分）：白底红字描边重试按钮 */
.error-state { display: flex; flex-direction: column; align-items: center; justify-content: center; padding-top: 240rpx; }
.btn-retry { min-width: 240rpx; font-size: 26rpx; font-weight: 600; color: #E02020; background-color: #FFFFFF; border: 1rpx solid #E02020; border-radius: 0; padding: 16rpx 56rpx; line-height: 1.4; }
</style>
