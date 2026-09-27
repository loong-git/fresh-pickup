<template>
	<view class="qual-page">
		<!-- 竖版营业执照卡片：纯 CSS + 文字绘制，不使用任何图片资源 -->
		<view class="license-card">
			<!-- 内层细线边框，模拟证照双线框 -->
			<view class="license-body">
				<!-- 顶部左侧红色圆形印章占位：红圈 + 红色五角星 -->
				<view class="seal-top">
					<text class="seal-top-star">★</text>
				</view>

				<!-- 标题：黑色大字 + 小字（副本） -->
				<text class="license-title">营业执照</text>
				<text class="license-sub">（副本）</text>

				<!-- 信息区上方：CSS 二维码占位（9x9 黑白格子） -->
				<view class="qr-box">
					<view class="qr-grid">
						<view class="qr-row" v-for="(row, ri) in qrRows" :key="ri">
							<view
								class="qr-cell"
								:class="ch === '1' ? 'qr-on' : 'qr-off'"
								v-for="(ch, ci) in row"
								:key="ci"
							></view>
						</view>
					</view>
				</view>

				<!-- 信息行：左标签右值 -->
				<view class="info-rows">
					<view class="info-row" v-for="(item, idx) in infoRows" :key="idx">
						<text class="info-label">{{ item.label }}</text>
						<text class="info-value">{{ item.value }}</text>
					</view>
				</view>

				<!-- 右下角红色圆形公章：红圆环 + 五角星，轻微旋转模拟盖章 -->
				<view class="seal-bottom">
					<text class="seal-bottom-star">⭐</text>
				</view>

				<!-- 卡片底部灰色小字编号 -->
				<text class="license-no">编号 1100001234567</text>
			</view>
		</view>
	</view>
</template>

<script>
export default {
	data() {
		return {
			// 二维码占位图案：9x9 黑白格子（1 黑 0 白，三角定位块 + 散点），写死
			qrRows: [
				'111001111',
				'101110101',
				'111010111',
				'010100011',
				'001011001',
				'110100110',
				'111010111',
				'101101001',
				'111010011'
			],
			// 营业执照信息（全部写死）
			infoRows: [
				{ label: '统一社会信用代码', value: '91440606MA51C8H6XJ' },
				{ label: '名　　称', value: '佛山市顺德区高黎柱发生鲜配送有限公司' },
				{ label: '类　　型', value: '有限责任公司（自然人独资）' },
				{ label: '住　　所', value: '广东省佛山市顺德区容桂街道高黎环涌西路桥灵坊23号' },
				{ label: '法定代表人', value: '高黎柱' },
				{ label: '注册资本', value: '人民币伍拾万元' },
				{ label: '成立日期', value: '2023年07月09日' }
			]
		}
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
		// ==================== H5 页面滚轮兜底 ====================
		// #ifdef H5
		// 页面级滚轮兜底：手动驱动 body 滚动（照搜索页同款方案）；本页无弹层，无需弹层放行守卫
		onPageWheel(e) {
			e.preventDefault()
			document.body.scrollTop += e.deltaY
		}
		// #endif
	}
}
</script>

<style>
/* 页面：白底，内容水平垂直居中 */
.qual-page {
	min-height: 100vh;
	background-color: #ffffff;
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 40rpx 0;
	box-sizing: border-box;
}

/* 竖版执照卡片：宽 560rpx，约 3:4.2 比例（高 784rpx），白底浅灰描边 */
.license-card {
	width: 560rpx;
	height: 784rpx;
	background-color: #ffffff;
	border: 2rpx solid #e8e8e8;
	border-radius: 12rpx;
	padding: 14rpx;
	box-sizing: border-box;
	box-shadow: 0 4rpx 16rpx rgba(0, 0, 0, 0.04);
}

/* 内层细线框，模拟证照双线边框 */
.license-body {
	position: relative;
	height: 100%;
	border: 1rpx solid #f2f2f2;
	padding: 32rpx 30rpx 24rpx;
	box-sizing: border-box;
	display: flex;
	flex-direction: column;
}

/* 顶部左侧红色圆形印章占位：红圈 + 红★ */
.seal-top {
	position: absolute;
	top: 28rpx;
	left: 28rpx;
	width: 64rpx;
	height: 64rpx;
	border: 4rpx solid #e02020;
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
}
.seal-top-star {
	color: #e02020;
	font-size: 26rpx;
	line-height: 1;
}

/* 标题：黑色大字居中 + 小字（副本） */
.license-title {
	text-align: center;
	font-size: 46rpx;
	font-weight: bold;
	color: #1a1a1a;
	letter-spacing: 6rpx;
	line-height: 1.2;
}
.license-sub {
	text-align: center;
	font-size: 24rpx;
	color: #1a1a1a;
	margin-top: 6rpx;
	line-height: 1.4;
}

/* 二维码占位：白底浅灰描边 + 9x9 黑白格子 */
.qr-box {
	margin: 22rpx auto 0;
	padding: 8rpx;
	border: 1rpx solid #eeeeee;
	background-color: #ffffff;
}
.qr-grid {
	display: flex;
	flex-direction: column;
}
.qr-row {
	display: flex;
}
.qr-cell {
	width: 12rpx;
	height: 12rpx;
}
.qr-on {
	background-color: #000000;
}
.qr-off {
	background-color: #ffffff;
}

/* 信息行：左标签右值，黑色小字 */
.info-rows {
	margin-top: 26rpx;
}
.info-row {
	display: flex;
	margin-bottom: 14rpx;
}
.info-label {
	width: 190rpx;
	flex-shrink: 0;
	font-size: 22rpx;
	color: #1a1a1a;
	line-height: 1.5;
}
.info-value {
	flex: 1;
	font-size: 22rpx;
	color: #1a1a1a;
	line-height: 1.5;
}

/* 右下角红色圆形公章：红圆环 + ⭐，轻微旋转 + 半透明模拟盖章 */
.seal-bottom {
	position: absolute;
	right: 36rpx;
	bottom: 64rpx;
	width: 150rpx;
	height: 150rpx;
	border: 6rpx solid rgba(224, 32, 32, 0.85);
	border-radius: 50%;
	display: flex;
	align-items: center;
	justify-content: center;
	transform: rotate(-12deg);
	opacity: 0.9;
}
.seal-bottom-star {
	font-size: 72rpx;
	line-height: 1;
}

/* 卡片底部灰色小字编号，推到底部居中 */
.license-no {
	margin-top: auto;
	text-align: center;
	font-size: 20rpx;
	color: #999999;
	line-height: 1.4;
}
</style>
