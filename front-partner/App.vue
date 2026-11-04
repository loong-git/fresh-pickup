<script>
// 全局网络状态提示（照用户端 front/App.vue 的断网提示条纪律）：断网时顶部固定红条「网络已断开」，恢复联网自动消失。
// 本项目 H5 与小程序端 App.vue 模板均不参与页面渲染，故 H5 端用 body 直挂 DOM 实现全局提示条
// （条件编译 #ifdef H5 隔离，小程序端不会执行 document 相关代码）；
// netOffline 保留为响应式状态标记，便于调试与后续页面级消费。
// 用户端的其余全局逻辑（购物车/自提点/字典等）与本端无关，不带过来。
export default {
	data() {
		return {
			// 断网标记：true = 当前无网络连接
			netOffline: false
		}
	},
	onLaunch: function() {
		console.log('Partner App Launch')
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
		console.log('Partner App Hide')
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
			if (typeof document === 'undefined' || document.getElementById('partner-global-network-bar')) return
			const bar = document.createElement('div')
			bar.id = 'partner-global-network-bar'
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
			const bar = document.getElementById('partner-global-network-bar')
			if (bar) bar.style.display = show ? 'block' : 'none'
		}
		// #endif
	}
}
</script>

<style>
/* 全局样式（P9-1/P9-2 契约 §0：双主题 CSS 变量单点真源，16 个 --c-* 逐字照
   docs/九期规划/P9-1-P9-2-双主题与排版精修契约.md §0 变量表）：
   :root = 浅色组（七期「鲜蔬集市」绿系暖底白卡，默认）；:root.dark = 深色组（八期「暗色控制台」蓝系黑底暗卡）。
   主题切换只有一种动作：main.js 给 html 根挂/摘 .dark 类（storage 键 app_theme），两组值整体生效、页面零刷新。
   页面 scoped 与 styles/common.css 的色值全部引用 var(--c-*)，不再各自持字面值 */
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
	/* 补充变量（契约 §1.4「八期新增暗色 tint……也按语义入表或补充变量」授权；浅值取七期同位点实值，
	   深值 = 八期现值，深色渲染不变）：text-4 弱提示文字 / fill 卡内嵌入面（输入框·胶囊·灰徽标底·行间弱分隔）/
	   disabled 禁用档 / gold 金胶囊字 / gold-hair 金胶囊描边（浅 #FAECD8=P7 commission .balance-tag 描边实值、
	   深 #8A6D1D=P8 verify .alert-card/.alert-close 与 commission .balance-tag 描边现值）/ shadow 卡影 / warn-hair·danger-hair 警示与危险横幅描边 */
	--c-text-4: #BBBBBB;
	--c-fill: #F5F5F5;
	--c-disabled: #CCCCCC;
	--c-gold: #B45309;
	--c-gold-hair: #FAECD8;
	--c-shadow: rgba(28, 25, 23, 0.06);
	--c-warn-hair: #FFD591;
	--c-danger-hair: #FECACA;
}
:root.dark {
	--c-bg: #0A0A0B;
	--c-card: #1B1B1D;
	--c-topbar: #111113;
	--c-border: #2A2A2D;
	--c-text: #F2F2F2;
	--c-text-2: #A6A6AA;
	--c-text-3: #6F6F76;
	--c-primary: #3B82F6;
	--c-primary-soft: #142743;
	--c-primary-hair: #26364D;
	--c-primary-disabled: #1E3A8A;
	--c-ok: #22C55E;
	--c-warn: #D97706;
	--c-warn-soft: #3A2F10;
	--c-danger: #EF4444;
	--c-danger-soft: #2C1517;
	--c-text-4: #5A5A60;
	--c-fill: #232326;
	--c-disabled: #45454A;
	--c-gold: #E7C868;
	--c-gold-hair: #8A6D1D;
	--c-shadow: rgba(0, 0, 0, 0.4);
	--c-warn-hair: #4A3B14;
	--c-danger-hair: #4A2224;
}

/* #ifndef H5 */
/* 小程序端兜底（切主题机制只在 H5，小程序恒浅色）：:root 在 wxss 不生效，给 page 根声明同一套浅色组，
   让页面里的 var() 引用取到浅值而不是失效；H5 构建排除本块（uni-page-body 若再声明浅值会压过 :root.dark 的继承） */
page {
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
}
/* #endif */

/* 页面底随主题（P9 契约 §1.3：原单值 #F5F5F5 改 var，浅 #F7F6F3 / 深 #0A0A0B） */
page {
	background-color: var(--c-bg);
	font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', Helvetica, Segoe UI, Arial, Roboto, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* #ifdef H5 */
/* H5 端 body 底同随主题：page 选择器编译到 uni-page-body，overscroll/短页露出的 body 也要同色 */
body {
	background-color: var(--c-bg);
}
/* P9 契约外最小配套（显式披露）：pages.json 的页头色是静态单值（#111113）无法表达双主题，
   H5 端页头改随 --c-topbar（浅 #0B3D26 深绿 / 深 #111113），白字两种主题都对；
   小程序端无 uni-page-head 元素、且本块 H5 条件编译隔离，不受影响 */
uni-page-head {
	background-color: var(--c-topbar) !important;
}
/* #endif */

/* 全局按钮禁用 hover 效果（小程序不支持） */
button::after {
	border: none;
}
</style>
