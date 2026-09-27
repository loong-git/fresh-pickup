<template>
  <!-- ==================== 免费领商品引导弹窗（free/index 两页共用，easycom 默认约定自动注册） ====================
       视觉对照参考图：全屏半透明遮罩上直接浮一列内容（无白色大面板）——
       标题（「直接领」金黄高亮）→ 副标题（未达标=再挑选差额 / 达标=已满门槛）→
       商品白卡（真实图优先，加载失败回退 emoji+bgColor 色块）→ 金色「去下单」按钮；
       ✕ 挂屏幕右上角（顶部 safe-area 下方），✕/遮罩点击均只回抛 close（本次不弹，不影响下次触发），
       弹出前提（活动 online 且今日未领取）由父页判定，组件不关心登录态/接口 -->
  <view class="fgg-mask" v-if="visible" @click="onMaskTap">
    <!-- ✕ 关闭：白描边圆圈，屏幕右上角（顶部 safe-area 下方） -->
    <view class="fgg-close" @click="onClose"><text class="fgg-close-x">✕</text></view>
    <!-- 居中内容列（点击不冒泡到遮罩，误触卡片/空白不关闭） -->
    <view class="fgg-body" @click.stop>
      <!-- 标题：实付满{threshold}元，可直接领{商品名}（text 嵌套拼接，「直接领」金黄 #FFE14D 高亮） -->
      <text class="fgg-title">实付满{{ fmtNum(threshold) }}元，可<text class="fgg-title-hl">直接领</text>{{ dishName || '免费商品' }}</text>
      <!-- 副标题：未达标提示差额 / 达标改显已满门槛 -->
      <text class="fgg-sub" v-if="!reached">再挑选{{ fmtNum(remaining) }}元，今天带走免费商品</text>
      <text class="fgg-sub" v-else>已满{{ fmtNum(threshold) }}元，直接领走免费商品</text>
      <!-- 商品白卡：dishImage aspectFill 优先，@error/无图回退 emoji+bgColor 色块 -->
      <view class="fgg-card">
        <image
          v-if="dishImage && !imgFail"
          class="fgg-card-img"
          :src="dishImage"
          mode="aspectFill"
          @error="onImgFail"
        />
        <view v-else class="fgg-card-img fgg-card-fallback" :style="{ backgroundColor: dishBg || '#FFF3E0' }">
          <text class="fgg-card-emoji">{{ dishEmoji || '🎁' }}</text>
        </view>
      </view>
      <!-- 去下单：金色渐变胶囊红字加粗（语义由父页定：free=滚到选购区 / index=跳免费领商品页） -->
      <view class="fgg-cta" @click="onGoOrder"><text class="fgg-cta-text">去下单</text></view>
    </view>
  </view>
</template>

<script>
// 免费领商品引导弹窗（共享组件，easycom 默认约定 components/组件名/组件名.vue 自动注册，两页免 import）：
// 纯展示组件——显隐与业务数据全由父页 props 下发，交互只回抛 close / go-order 两个事件：
//   close：✕ 或遮罩点击（仅本次不弹，父页不写任何持久化门）；
//   go-order：金色「去下单」按钮（父页定义语义：free 页=关弹窗滚到选购区，index 页=关弹窗跳免费领页）。
export default {
  name: 'free-gift-guide',
  props: {
    visible: { type: Boolean, default: false },        // 显隐（父页数据就绪后再置 true，避免闪空）
    dishName: { type: String, default: '' },           // 免费商品名（标题拼接）
    dishImage: { type: String, default: '' },          // 免费商品图（dish.image，aspectFill）
    dishEmoji: { type: String, default: '🎁' },        // 图失败回退 emoji
    dishBg: { type: String, default: '#FFF3E0' },      // 图失败回退色块底色
    threshold: { type: [Number, String], default: 0 }, // 满额门槛（元）
    remaining: { type: [Number, String], default: 0 }, // 距门槛差额（元，后端 remainingToThreshold）
    reached: { type: Boolean, default: false }         // 是否已达门槛（remaining<=0，切换副标题文案）
  },
  emits: ['close', 'go-order'],
  data() {
    return { imgFail: false } // 商品图加载失败标记（该次展示回退 emoji 色块）
  },
  watch: {
    // 换商品（dishImage 变化）重置失败标记，新图可重试渲染
    dishImage() { this.imgFail = false }
  },
  methods: {
    // 金额展示去尾零（50.00→50 / 49.50→49.5，脏数据按 0；与页面 fmtThreshold 同口径）
    fmtNum(v) {
      const n = Number(v)
      return Number.isFinite(n) ? String(+n.toFixed(2)) : '0'
    },
    onImgFail() { this.imgFail = true },
    onClose() { this.$emit('close') },
    onMaskTap() { this.$emit('close') },
    onGoOrder() { this.$emit('go-order') }
  }
}
</script>

<style scoped>
/* ==================== 免费领商品引导弹窗（对照参考图：元素直接浮在遮罩上，无白色大面板） ==================== */
.fgg-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background-color: rgba(0,0,0,.5);
  z-index: 500; /* 高于两页既有弹层（free 页最高 300 / index 页最高 400），保证引导最上层 */
  display: flex; align-items: center; justify-content: center;
}
/* ✕ 关闭：屏幕右上角（顶部 safe-area 下方），白描边圆圈内含 ✕ */
.fgg-close {
  position: absolute; top: calc(24rpx + env(safe-area-inset-top)); right: 32rpx;
  width: 64rpx; height: 64rpx;
  display: flex; align-items: center; justify-content: center;
  border: 3rpx solid rgba(255,255,255,.9); border-radius: 50%;
}
.fgg-close-x { font-size: 30rpx; font-weight: 600; color: #FFFFFF; line-height: 1; }
/* 居中内容列：标题→副标题→商品白卡→按钮，全部水平居中 */
.fgg-body {
  display: flex; flex-direction: column; align-items: center;
  width: 100%;
  animation: fggPop 0.25s ease;
}
@keyframes fggPop {
  from { transform: scale(0.86); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}
/* 标题：44rpx 加粗白字 + 深色文字阴影（暗遮罩上保证可读） */
.fgg-title {
  width: 640rpx;
  font-size: 44rpx; font-weight: 700; color: #FFFFFF;
  line-height: 1.35; text-align: center;
  text-shadow: 0 4rpx 12rpx rgba(0, 0, 0, 0.55);
}
/* 「直接领」金黄高亮（text 嵌套继承字号/加粗，仅覆写颜色） */
.fgg-title-hl { color: #FFE14D; }
/* 副标题：28rpx 白色 */
.fgg-sub {
  margin-top: 16rpx;
  width: 640rpx;
  font-size: 28rpx; color: #FFFFFF; line-height: 1.4;
  text-align: center;
  text-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.45);
}
/* 商品白卡：宽约 60%、圆角 24rpx、内边距 20rpx，卡内图高 320rpx 宽 100% */
.fgg-card {
  margin-top: 40rpx;
  width: 60%;
  background-color: #FFFFFF;
  border-radius: 24rpx;
  padding: 20rpx;
  box-sizing: border-box;
}
.fgg-card-img { width: 100%; height: 320rpx; display: block; border-radius: 12rpx; }
.fgg-card-fallback { display: flex; align-items: center; justify-content: center; }
.fgg-card-emoji { font-size: 120rpx; line-height: 1; }
/* 去下单：金色渐变胶囊、宽约 60%、高 88rpx、红字加粗、轻投影（view 自绘不经 uni-button 默认样式） */
.fgg-cta {
  margin-top: 44rpx;
  width: 60%; height: 88rpx;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(180deg, #FFEDBE 0%, #FFC55C 100%);
  border-radius: 44rpx;
  box-shadow: 0 8rpx 20rpx rgba(0, 0, 0, 0.25);
}
.fgg-cta-text { font-size: 32rpx; font-weight: 700; color: #E02020; letter-spacing: 4rpx; }
</style>
