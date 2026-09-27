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
/* 全局样式 */
page {
	background-color: #F5F5F5;
	font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', Helvetica, Segoe UI, Arial, Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* 全局按钮禁用 hover 效果（小程序不支持） */
button::after {
	border: none;
}
</style>
