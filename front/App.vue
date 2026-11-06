<script>
// 全局网络状态提示（M3-T-05 部分）：断网时顶部固定红条"网络已断开"，恢复联网自动消失。
// 说明：本项目 H5 与小程序端 App.vue 模板均不参与页面渲染，故 H5 端用 body 直挂 DOM 实现
// 全局提示条（条件编译 #ifdef H5 隔离，小程序端不会执行 document 相关代码）；
// netOffline 保留为响应式状态标记，便于调试与后续页面级消费。
export default {
	data() {
		return {
			// 断网标记：true = 当前无网络连接
			netOffline: false
		}
	},
	onLaunch: function() {
		console.log('App Launch')
	},
	onShow: function() {
		// 全局网络监听只挂一次（_netWatcherBound 为实例级防重标记，多次前后台切换不会重复注册）
		if (!this._netWatcherBound) {
			this._netWatcherBound = true
			uni.onNetworkStatusChange((res) => {
				this.applyNetworkState(!!(res && res.isConnected))
			})
		}
		// 每次回前台主动探测一次当前网络，校准断网标记时效性（后台期间网络可能已变化）
		this.detectNetworkOnce()
	},
	onHide: function() {
		console.log('App Hide')
	},
	methods: {
		// 应用网络状态：同步断网标记 + 切换全局提示条显隐
		applyNetworkState(online) {
			this.netOffline = !online
			// #ifdef H5
			this.toggleNetworkBarH5(!online)
			// #endif
		},
		// 主动探测一次当前网络状态（首次进入/回前台时校准）
		detectNetworkOnce() {
			uni.getNetworkType({
				success: (res) => {
					this.applyNetworkState(!!(res && res.networkType && res.networkType !== 'none'))
				}
			})
		}
		// #ifdef H5
		,
		// H5 端确保全局提示条 DOM 已挂载（fixed 顶部红条，不拦截点击）
		ensureNetworkBarH5() {
			if (typeof document === 'undefined' || document.getElementById('global-network-bar')) return
			const bar = document.createElement('div')
			bar.id = 'global-network-bar'
			bar.textContent = '网络已断开，请检查网络连接'
			// 内联样式：避免依赖全局 CSS 加载时序；z-index 压过所有页面与弹层
			bar.setAttribute('style', [
				'display:none',
				'position:fixed',
				'top:0',
				'left:0',
				'right:0',
				'z-index:99999',
				'background-color:#E02020',
				'color:#FFFFFF',
				'font-size:13px',
				'line-height:32px',
				'text-align:center',
				'pointer-events:none'
			].join(';'))
			document.body.appendChild(bar)
		},
		// 切换提示条显隐：断网显示 / 恢复隐藏
		toggleNetworkBarH5(show) {
			this.ensureNetworkBarH5()
			if (typeof document === 'undefined') return
			const bar = document.getElementById('global-network-bar')
			if (bar) bar.style.display = show ? 'block' : 'none'
		}
		// #endif
	}
}
</script>

<style>
/* ---- P10-4 单点真源：C 端全局 CSS 变量（单主题，无 dark 组）----
   照 front-merchant/App.vue :root 浅色组逐字抄入，语义对齐商家端 tokens，页面色值由 C7 逐页转 var(--c-*)。
   披露：gold-hair 商家端 :root 未定义，浅值 #FAECD8 抄自主理人端 front-partner/App.vue :root
   （P7 commission .balance-tag 描边实值，docs/十期规划/tokens-对照表.md 金胶囊行同值）。
   另追加 3 枚：scroll-thumb / scroll-thumb-hover = A3 滚动条（C 端底色 #F5F5F5 偏冷，用 zinc 系）；
   warn-bright = C7 促销价签橙渐变第二端点（价格不是危险，不占 danger 轨，与 --c-warn #B45309 组渐变） */
:root {
	--c-bg: #F7F6F3;
	--c-card: #FFFFFF;
	--c-topbar: #0B3D26;
	--c-border: #E7E5E0;
	--c-text: #1C1917;
	--c-text-2: #57534E;
	--c-text-3: #A8A29E;
	--c-primary: #0F7B4A;
	--c-primary-soft: #E8F5EE;
	--c-primary-hair: #C8E6D5;
	--c-primary-disabled: #A7D3BC;
	--c-ok: #0F7B4A;
	--c-warn: #B45309;
	--c-warn-soft: #FFF7E6;
	--c-danger: #DC2626;
	--c-danger-soft: #FEF2F2;
	--c-text-4: #BBBBBB;
	--c-fill: #F5F5F5;
	--c-disabled: #CCCCCC;
	--c-gold: #B45309;
	--c-gold-hair: #FAECD8;
	--c-shadow: rgba(28, 25, 23, 0.06);
	--c-warn-hair: #FFD591;
	--c-danger-hair: #FECACA;
	/* A3 滚动条变量：thumb 常态色 / hover 提亮色，全局规则块见本 style 段末尾 */
	--c-scroll-thumb: #CDD0D6;
	--c-scroll-thumb-hover: #A8ABB2;
	/* C7 促销渐变第二端点 */
	--c-warn-bright: #D97706;
}

/* 全局样式 */
page {
	background-color: #F5F5F5;
	font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', Helvetica, Segoe UI, Arial, Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* 全局按钮禁用 hover 效果（小程序不支持） */
button::after {
	border: none;
}

/* ---- P10-4 A3 全局滚动条美化（与商家端 P10-4 A2 / 运营端 P9-fix2 同构；本组件 <style> 无 scoped，天然全局）----
   颜色随 --c-scroll-thumb(-hover) 变量走（#CDD0D6 → hover #A8ABB2，zinc 系）：
   标准轨（Firefox 与新 Chrome）scrollbar-width thin + 轨透明，页面与弹层滚动条一并不漂；
   webkit 兜底（旧 Chrome/Edge/Safari）6px 细条 + 3px 圆角 thumb，hover 提亮，corner 透明。
   小程序端 wxss 无滚动条渲染概念，本块不生效亦无害 */
* {
	scrollbar-width: thin;
	scrollbar-color: var(--c-scroll-thumb) transparent;
}
::-webkit-scrollbar {
	width: 6px;
	height: 6px;
}
::-webkit-scrollbar-track {
	background: transparent;
}
::-webkit-scrollbar-thumb {
	background: var(--c-scroll-thumb);
	border-radius: 3px;
}
::-webkit-scrollbar-thumb:hover {
	background: var(--c-scroll-thumb-hover);
}
::-webkit-scrollbar-corner {
	background: transparent;
}
</style>
