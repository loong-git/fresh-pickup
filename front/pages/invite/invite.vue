<template>
  <view class="page-container">
    <!-- ==================== 1. 红色头部 + 邀请码大卡（白卡叠红头，多多买菜风格） ==================== -->
    <view class="invite-header">
      <!-- 顶部互链（F-04.3）：邀请赚现金（现金轨入口，与 cash 页券入口互链） -->
      <view class="header-links">
        <text class="header-link" @click="goCash">邀请赚现金 ›</text>
      </view>
      <text class="invite-slogan">邀请好友，双方都得券</text>
      <text class="invite-slogan-sub">好友注册即领新人见面礼，TA完成首单你得邀请奖励券</text>
      <!-- 邀请码大卡：登录态展示 8 位邀请码 + 复制链接；游客态引导登录 -->
      <view class="code-card">
        <text class="code-label">我的邀请码</text>
        <!-- 游客态占位：·······；加载中 ----；成功展示 8 位码（字距拉开便于抄写） -->
        <text class="code-value" v-if="hasToken">{{ loaded ? inviteCode : '········' }}</text>
        <text class="code-value code-value-guest" v-else>········</text>
        <text class="code-tip" v-if="hasToken && loaded">把下面的邀请链接发给好友，TA注册时自动绑定</text>
        <text class="code-tip" v-else-if="!hasToken">登录后生成专属邀请码，邀请好友双方得券</text>
        <text class="code-tip" v-else>正在获取邀请码…</text>
        <!-- 复制链接（H5 契约：location.origin + '/#/pages/login/login?invite=' + 邀请码）；游客态整卡引导登录 -->
        <button
          v-if="hasToken"
          class="btn-copy"
          :class="{ 'btn-copy-disabled': copying || !inviteCode }"
          :disabled="copying || !inviteCode"
          @click="copyInviteLink"
        ><text>{{ copying ? '复制中…' : '复制邀请链接' }}</text></button>
        <button v-else class="btn-copy" @click="goLogin"><text>去登录</text></button>
      </view>
    </view>

    <!-- ==================== 2. 邀请统计（登录且加载成功后展示：邀请 x 人 · 完成 y 人） ==================== -->
    <view class="card stat-card" v-if="hasToken && loaded">
      <view class="stat-item">
        <text class="stat-num">{{ invitedCount }}</text>
        <text class="stat-label">已邀请（人）</text>
      </view>
      <view class="stat-divider"></view>
      <view class="stat-item">
        <text class="stat-num">{{ completedCount }}</text>
        <text class="stat-label">已完成首单（人）</text>
      </view>
    </view>

    <!-- ==================== 3. 活动规则三步（静态内容，游客可见利于转化） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">活动规则</text></view>
      <view class="rule-item" v-for="(r, i) in rules" :key="i">
        <text class="rule-no">{{ i + 1 }}</text>
        <text class="rule-text">{{ r }}</text>
      </view>
    </view>

    <!-- ==================== 4. 邀请记录（登录态拉取；手机号后端已脱敏，前端原样展示不拼手机号） ==================== -->
    <view class="card">
      <view class="card-head"><text class="card-title">邀请记录</text></view>
      <!-- 游客态：登录引导 -->
      <view v-if="!hasToken" class="guide-row" @click="goLogin">
        <text class="guide-text">🔒 登录后查看我的邀请记录</text>
        <text class="guide-go">去登录 ›</text>
      </view>
      <template v-else>
        <!-- 错误态：加载失败可点击重试（与我的页错误行同口径；后端未上线/网络异常时呈现） -->
        <view v-if="loadError" class="error-row" @click="loadInvite">
          <text class="error-text">邀请信息加载失败，请检查网络</text>
          <text class="error-retry">点击重试</text>
        </view>
        <!-- 记录列表：脱敏手机号 + 状态标签（statusText 优先，脏数据按 status 兜底） -->
        <view v-else-if="records.length > 0" class="record-list">
          <view class="record-item" v-for="(r, i) in records" :key="i">
            <view class="record-info">
              <text class="record-phone">{{ r.phoneMasked }}</text>
              <text class="record-time">{{ formatDay(r.createdAt) }}</text>
            </view>
            <text class="record-badge" :class="isDone(r) ? 'rb-done' : 'rb-wait'">{{ statusText(r) }}</text>
          </view>
        </view>
        <view v-else class="empty-row">
          <text class="empty-text">{{ loaded ? '还没有邀请记录，复制链接发给好友吧' : '加载中…' }}</text>
        </view>
      </template>
    </view>

    <view class="page-bottom-space"></view>
  </view>
</template>

<script>
// 邀请好友页（F-01，多多买菜双边拉新，白底+主红 #E02020，默认导航栏标题「邀请好友」）：
// 邀请码大卡（GET /api/invite/me 首次调用后端惰性生成，幂等）+ 复制链接 + 活动规则三步
// + 统计（邀请 x 人 · 完成 y 人）+ 脱敏记录列表（phoneMasked 后端已脱敏，statusText 直显）。
// 登录态判定与渲染响应 store.user.token（mapState user）：401 时 request 层统一清登录态，
// 本页随 store 自动降级游客视图，不残留已登录 UI；未登录 goLogin 带 redirect 回本页。
import { mapState } from 'vuex'
import { getInviteInfo } from '@/api/index.js'

export default {
  data() {
    return {
      // 加载三态：loading 防重 / loadError 错误可重试 / loaded 成功后展示统计与记录空态文案
      loading: false,
      loadError: false,
      loaded: false,
      copying: false,
      // /api/invite/me 契约字段（F-01.2）：脏数据统一兜底
      inviteCode: '',
      invitedCount: 0,
      completedCount: 0,
      records: [],
      // 活动规则三步（F-01.0 定稿口径：新人注册即发新人见面礼；邀请人在新人首单支付后得奖励券；20 人封顶）
      rules: [
        '复制邀请链接发给好友，好友通过链接进入注册页',
        '好友用手机号验证码注册成功，TA 立得新人见面礼券',
        '好友完成首单支付，你即得邀请奖励券（最多奖励20人）'
      ]
    }
  },
  computed: {
    ...mapState(['user']),
    // 是否已登录（token 鉴权）：401 时 request 层清登录态，此处响应式切换游客视图
    hasToken() { return !!(this.user && this.user.token) }
  },
  onShow() {
    // 每次进入刷新（从登录页回跳后登录态变化同步数据）；未登录不发请求
    if (this.hasToken) this.loadInvite()
  },
  watch: {
    // 登录态变化兜底：会话内被 401 清态时随 store 清空已登录数据，切游客视图不残留
    hasToken(v) {
      if (v) {
        this.loadInvite()
      } else {
        this.loaded = false
        this.loadError = false
        this.inviteCode = ''
        this.invitedCount = 0
        this.completedCount = 0
        this.records = []
      }
    }
  },
  // #ifdef H5
  onReady() {
    // 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照我的页同款方案）
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
    // GET /api/invite/me（登录，token 由 request 层自动携带）；首次调用后端惰性生成邀请码，重复调用幂等
    async loadInvite() {
      if (!this.hasToken || this.loading) return
      this.loading = true
      try {
        const res = await getInviteInfo()
        const d = (res && res.data) || {}
        this.inviteCode = String(d.inviteCode || '')
        this.invitedCount = Number(d.invitedCount) || 0
        this.completedCount = Number(d.completedCount) || 0
        this.records = Array.isArray(d.records) ? d.records : []
        this.loaded = true
        this.loadError = false
      } catch (e) {
        // 401（token 失效）：request 层已清登录态，页面响应式切游客视图，不再置错误态；其余失败显示错误态可重试
        if (!e || e.code !== 401) this.loadError = true
      } finally {
        this.loading = false
      }
    },
    // ==================== 复制邀请链接 ====================
    // H5 契约（F-01.3）：location.origin + '/#/pages/login/login?invite=' + 邀请码
    //（邀请码为后端 8 位去混淆字符集 [A-Z2-9]，无需再编码）；非 H5 端本期不做分享，退化为相对路径文本兜底
    buildInviteLink() {
      const hashPath = '#/pages/login/login?invite=' + this.inviteCode
      // #ifdef H5
      return window.location.origin + '/' + hashPath
      // #endif
      // #ifndef H5
      return '/' + hashPath
      // #endif
    },
    copyInviteLink() {
      if (!this.inviteCode || this.copying) return
      const link = this.buildInviteLink()
      this.copying = true
      uni.setClipboardData({
        data: link,
        success: () => {
          this.copying = false
          uni.showToast({ title: '链接已复制，快发给好友吧', icon: 'none', duration: 2000 })
        },
        fail: () => {
          this.copying = false
          // H5 个别浏览器无剪贴板权限：降级提示手动复制邀请码（大卡上即有）
          uni.showToast({ title: '复制失败，请手动抄写邀请码', icon: 'none' })
        }
      })
    },
    // ==================== 记录展示 ====================
    // 状态文案：契约 statusText（待完成首单/已完成）直显；缺失/脏数据按 status 兜底（0=待完成首单 1=已完成）
    statusText(r) {
      const t = r && r.statusText
      if (typeof t === 'string' && t) return t
      return this.isDone(r) ? '已完成' : '待完成首单'
    },
    // 完成判定：status===1 或 statusText 恰为「已完成」（驱动徽标配色）
    isDone(r) {
      return Number(r && r.status) === 1 || (r && r.statusText) === '已完成'
    },
    // 时间 -> yyyy-MM-dd（脏数据返回空串由模板隐藏；口径照我的页 formatDay）
    formatDay(v) {
      if (!v) return ''
      const d = new Date(v)
      if (!Number.isFinite(d.getTime())) return ''
      return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    },
    // ==================== 跳转 ====================
    // 游客态去登录：携带 redirect 回本页，登录成功后解码回跳（redirect 须为 / 开头页面路径）
    goLogin() { uni.navigateTo({ url: '/pages/login/login?redirect=' + encodeURIComponent('/pages/invite/invite') }) },
    // 顶部互链：跳「邀请赚现金」（现金轨，F-04 两页互链）
    goCash() { uni.navigateTo({ url: '/pages/invite/cash' }) },
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
/* ==================== 多多买菜风格 — 邀请好友页（白底 + 主红 #E02020） ==================== */

.page-container { min-height: 100vh; background-color: #F5F5F5; box-sizing: border-box; }

/* ==================== 1. 红色头部 + 邀请码大卡（白卡叠红头下缘） ==================== */
.invite-header {
  position: relative;
  background: linear-gradient(180deg, #E02020 0%, #E02020 55%, #F5F5F5 100%);
  padding: 40rpx 24rpx 8rpx;
}
/* 顶部互链行：右对齐白色半透明胶囊（与 cash 页券互链对称，F-04 两页互链） */
.header-links { display: flex; justify-content: flex-end; margin-bottom: 4rpx; }
.header-link {
  font-size: 24rpx; font-weight: 600; color: #FFFFFF;
  background-color: rgba(255, 255, 255, 0.18);
  border: 1rpx solid rgba(255, 255, 255, 0.55);
  border-radius: 999rpx;
  padding: 8rpx 24rpx; line-height: 1.4;
}
.invite-slogan { display: block; text-align: center; font-size: 38rpx; font-weight: 700; color: #FFFFFF; line-height: 1.3; }
.invite-slogan-sub { display: block; margin-top: 12rpx; text-align: center; font-size: 22rpx; color: rgba(255, 255, 255, 0.92); line-height: 1.5; }
.code-card {
  margin-top: 32rpx;
  background-color: #FFFFFF; border-radius: 24rpx;
  padding: 40rpx 32rpx 36rpx;
  box-shadow: 0 4rpx 16rpx rgba(224,32,32,.08);
  display: flex; flex-direction: column; align-items: center;
}
.code-label { font-size: 24rpx; color: #999999; line-height: 1; }
/* 8 位邀请码大字：等宽字体 + 字距拉开，占位串 ········ 同宽防跳动 */
.code-value {
  margin-top: 20rpx;
  font-size: 72rpx; font-weight: 800; line-height: 1.2;
  color: #E02020; letter-spacing: 10rpx;
  font-family: "SF Mono", Menlo, Consolas, monospace;
  max-width: 100%; overflow: hidden;
}
.code-value-guest { color: #CCCCCC; }
.code-tip { margin-top: 16rpx; font-size: 22rpx; color: #999999; line-height: 1.5; text-align: center; }
/* 复制链接主按钮（显式覆盖 uni-button 默认圆角/行高/边框，照登录页 btn-main 口径） */
.btn-copy {
  width: 100%; height: 88rpx; line-height: 88rpx;
  margin-top: 32rpx; padding: 0;
  background-color: #E02020; color: #FFFFFF;
  font-size: 32rpx; font-weight: 700;
  border: none; border-radius: 999rpx;
}
.btn-copy.btn-copy-disabled { background-color: #F5B5B0; color: rgba(255, 255, 255, 0.9); }

/* ==================== 白卡通用（照我的页） ==================== */
.card {
  background-color: #FFFFFF; border-radius: 16rpx;
  margin: 20rpx 24rpx 0;
  padding: 24rpx;
}
.card-head { margin-bottom: 20rpx; }
.card-title { font-size: 30rpx; font-weight: 700; color: #333333; }

/* ==================== 2. 统计卡（两列大数字） ==================== */
.stat-card { display: flex; align-items: center; padding: 32rpx 0; }
.stat-item { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 10rpx; }
.stat-num { font-size: 48rpx; font-weight: 700; color: #E02020; line-height: 1; }
.stat-label { font-size: 24rpx; color: #999999; }
.stat-divider { flex-shrink: 0; width: 1rpx; height: 56rpx; background-color: #EEEEEE; }

/* 游客态登录引导行（照我的页） */
.guide-row {
  display: flex; align-items: center; justify-content: space-between;
  background-color: #FFF7F0; border-radius: 12rpx;
  padding: 24rpx;
}
.guide-text { font-size: 26rpx; color: #666666; }
.guide-go { flex-shrink: 0; margin-left: 16rpx; font-size: 26rpx; font-weight: 600; color: #E02020; }

/* 错误态行（可点击重试，照我的页） */
.error-row { display: flex; flex-direction: column; align-items: center; gap: 16rpx; padding: 32rpx 0; }
.error-text { font-size: 24rpx; color: #999999; }
.error-retry {
  font-size: 24rpx; font-weight: 600; color: #E02020;
  border: 1rpx solid #E02020; border-radius: 28rpx;
  padding: 8rpx 36rpx; line-height: 1.4;
}

/* ==================== 3. 活动规则三步 ==================== */
.rule-item { display: flex; align-items: flex-start; gap: 16rpx; margin-bottom: 20rpx; }
.rule-item:last-child { margin-bottom: 0; }
.rule-no {
  flex-shrink: 0; width: 36rpx; height: 36rpx; border-radius: 50%;
  background-color: #FFECE8; color: #E02020;
  font-size: 22rpx; font-weight: 700; text-align: center; line-height: 36rpx;
}
.rule-text { flex: 1; min-width: 0; font-size: 26rpx; color: #666666; line-height: 1.6; }

/* ==================== 4. 邀请记录列表（脱敏手机号 + 状态徽标） ==================== */
.record-list { display: flex; flex-direction: column; }
.record-item { display: flex; align-items: center; gap: 16rpx; padding: 20rpx 0; border-bottom: 1rpx solid #F5F5F5; }
.record-item:last-child { border-bottom: none; padding-bottom: 4rpx; }
.record-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.record-phone { font-size: 28rpx; font-weight: 600; color: #333333; }
.record-time { font-size: 22rpx; color: #CCCCCC; }
.record-badge { flex-shrink: 0; font-size: 20rpx; font-weight: 700; padding: 4rpx 14rpx; border-radius: 20rpx; }
/* 状态徽标配色照我的页审核徽标体系：待完成首单橙 / 已完成绿 */
.rb-wait { color: #FF6600; background-color: #FFF3EB; }
.rb-done { color: #07C160; background-color: #E8F8EF; }

/* 空态行 */
.empty-row { display: flex; justify-content: center; padding: 40rpx 0; }
.empty-text { font-size: 24rpx; color: #CCCCCC; }

.page-bottom-space { height: env(safe-area-inset-bottom); }
</style>
