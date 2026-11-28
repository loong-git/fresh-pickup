<template>
  <view class="page">
    <view class="page-title"><text class="page-title-text">浏览足迹</text></view>

    <!-- 列表：storage 只读展示（记录在 detail loadDetail 成功路径，本页零写） -->
    <view v-if="history.length > 0" class="history-list">
      <view class="history-card" v-for="h in history" :key="h.id" @click="goDetail(h)">
        <view class="history-img" :style="{ backgroundColor: h.bgColor || '#F5F5F5' }">
          <image v-if="h.image" class="history-photo" :src="h.image" mode="aspectFill" />
          <text v-else class="history-emoji">{{ h.emoji }}</text>
        </view>
        <view class="history-body">
          <text class="history-name">{{ h.name }}</text>
          <text v-if="h.ts" class="history-time">浏览于 {{ formatTime(h.ts) }}</text>
        </view>
        <text class="history-arrow">›</text>
      </view>
      <!-- D5：清空钮（破坏类动作 danger 分轨——P11-16 批B confirmColor 纪律） -->
      <button class="btn-clear" @click="clearHistory">清空足迹</button>
      <text class="keep-note">最多保留最近 20 条</text>
    </view>

    <!-- D6 空态（S2 纪律：storage 读过之后才可断言空） -->
    <view v-else-if="loaded" class="empty-box">
      <text class="empty-emoji">🧭</text>
      <text class="empty-text">看过的商品会出现在这里</text>
      <button class="btn-stroll" @click="goStroll">去逛逛</button>
    </view>
  </view>
</template>

<script>
// 浏览足迹独立页（P11-18）：我的页 cell 入口直达，承载完整 20 条列表+清空管理；
// 首页横滑条只显一屏横滑（P11-14-3），管理面唯一承载点在本页。
// 数据源（D2）：onShow 读 storage browse_history——与首页横滑条同源同形状；本页只读不写，
// 记录仍只在 detail loadDetail 成功路径（recordBrowseHistory 单一写入点纪律）。
export default {
  data() {
    return {
      history: [],
      loaded: false
    }
  },
  onShow() {
    // P11-18（D2）：storage 只读刷新——与首页横滑条同源同形状；脏条目 null 在渲染层跳过
    //（filter 后赋值，与 detail 写入端 it&& 同族）；从详情返回（navigateBack）onShow 必触发即时可见
    try {
      const list = uni.getStorageSync('browse_history')
      this.history = Array.isArray(list) ? list.filter(it => it && it.id != null) : []
    } catch (e) { this.history = [] }
    this.loaded = true
  },
  methods: {
    // P11-18（D3）ts 首次消费：短格式「MM-DD HH:mm」；解析失败/缺 ts 返回 ''（模板 v-if 不渲染该行，不造假时间）
    formatTime(ts) {
      const d = new Date(Number(ts))
      if (!Number.isFinite(d.getTime())) return ''
      const p = n => String(n).padStart(2, '0')
      return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
    },
    // D4：与首页 goHistory 同款形状+守卫
    goDetail(item) {
      if (!item || item.id == null) return
      uni.navigateTo({ url: '/pages/detail/detail?id=' + item.id })
    },
    // D5：清空（破坏类→danger 确认钮，confirmColor 照 main.js showModal 同款形状）
    clearHistory() {
      uni.showModal({
        title: '清空足迹',
        content: '将删除全部浏览记录，不可恢复',
        confirmText: '清空',
        cancelText: '取消',
        confirmColor: '#C81E1E',
        success: (res) => {
          if (!res.confirm) return
          try { uni.removeStorageSync('browse_history') } catch (e) { /* storage 异常静默 */ }
          this.history = []
          uni.showToast({ title: '已清空', icon: 'success', duration: 1500 })
        }
      })
    },
    // D6：去首页逛（reLaunch——项目 pages.json 无 tabBar，switchTab 静默失败，
    // 同 order.vue 缺陷-12 / detail.vue backHome 全仓统一口径）
    goStroll() { uni.reLaunch({ url: '/pages/index/index' }) }
  }
}
</script>

<style scoped>
/* ==================== 浏览足迹页（P11-18：storage 只读列表+清空钮；零 hex 全 var——#FFFFFF 主色底白字走特殊档先例） ==================== */

.page { min-height: 100vh; background-color: var(--c-bg); padding-bottom: 40rpx; }
.page-title { padding: 28rpx 24rpx 8rpx; }
.page-title-text { font-size: 36rpx; font-weight: 700; color: var(--c-text); }
.history-list { padding: 16rpx 24rpx 0; }
.history-card { display: flex; align-items: center; background-color: var(--c-card); border-radius: 16rpx; padding: 20rpx; margin-bottom: 16rpx; }
.history-img { width: 120rpx; height: 120rpx; border-radius: 12rpx; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.history-photo { width: 100%; height: 100%; border-radius: 12rpx; }
.history-emoji { font-size: 52rpx; }
.history-body { flex: 1; min-width: 0; margin-left: 20rpx; }
.history-name { display: block; font-size: 28rpx; color: var(--c-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-time { display: block; font-size: 22rpx; color: var(--c-text-3); padding-top: 8rpx; }
.history-arrow { color: var(--c-text-3); font-size: 32rpx; margin-left: 8rpx; }
.btn-clear { margin: 32rpx 24rpx 0; width: calc(100% - 48rpx); height: 84rpx; line-height: 84rpx; font-size: 28rpx; font-weight: 700; color: var(--c-danger); background-color: var(--c-card); border: 1rpx solid var(--c-danger-hair); border-radius: 16rpx; }
.keep-note { display: block; text-align: center; font-size: 22rpx; color: var(--c-text-3); padding-top: 16rpx; }
.empty-box { display: flex; flex-direction: column; align-items: center; padding: 160rpx 48rpx 0; }
.empty-emoji { font-size: 96rpx; opacity: .4; }
.empty-text { font-size: 26rpx; color: var(--c-text-3); padding-top: 24rpx; }
.btn-stroll { margin-top: 40rpx; width: 320rpx; height: 84rpx; line-height: 84rpx; font-size: 28rpx; font-weight: 700; color: #FFFFFF; background-color: var(--c-primary); border-radius: 44rpx; }
</style>
