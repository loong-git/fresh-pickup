<template>
	<view class="pickup-page">
		<!-- 顶部 tab：我的自提点 / 选择其他自提点，红色下划线指示 -->
		<view class="tab-bar">
			<view
				class="tab-item"
				:class="{ 'tab-active': activeTab === 0 }"
				@click="switchTab(0)"
			>
				<text class="tab-text">我的自提点</text>
				<view class="tab-line" v-if="activeTab === 0"></view>
			</view>
			<view
				class="tab-item"
				:class="{ 'tab-active': activeTab === 1 }"
				@click="switchTab(1)"
			>
				<text class="tab-text">选择其他自提点</text>
				<view class="tab-line" v-if="activeTab === 1"></view>
			</view>
		</view>

		<!-- ============ tab1：我的自提点 ============ -->
		<block v-if="activeTab === 0">
			<!-- 分组：当前自提点 -->
			<view class="group-title">当前自提点</view>
			<view class="store-card current-card">
				<view class="store-icon-box big">🏪</view>
				<view class="store-info">
					<text class="store-name">{{ pickupPoint.name }}</text>
					<text class="store-address">{{ pickupPoint.address }}</text>
					<view class="tag-row">
						<text class="service-tag">{{ pickupPoint.service || '冷冻/冷藏' }}</text>
					</view>
					<!-- M4-T-01：当前门店停业提示（切换后自动消除） -->
					<text v-if="pickupClosed" class="closed-warn">⚠ 该门店暂停营业，请切换其他自提点</text>
				</view>
			</view>

			<!-- 分组：全部门店（M4-T-01：GET /api/stores 拉取，失败回退本地兜底）+ 右侧管理入口 -->
			<view class="group-title-row">
				<text class="group-title">全部门店</text>
				<text class="manage-text" @click="onManageTap">管理</text>
			</view>
			<view
				class="store-card history-card"
				v-for="s in stores"
				:key="s.id"
				:class="{ 'card-disabled': isClosed(s) }"
			>
				<view class="store-icon-box">🏪</view>
				<view class="store-info">
					<text class="store-name small">{{ s.name }}</text>
					<text class="store-address">{{ s.address }}</text>
					<text v-if="s.openTime" class="store-open-time">营业时间 {{ s.openTime }}</text>
					<view class="tag-row">
						<text class="service-tag plain">{{ s.service || '冷冻/冷藏' }}</text>
					</view>
				</view>
				<!-- 营业中：红色选择按钮；暂停营业：置灰无按钮 -->
				<button
					v-if="!isClosed(s)"
					class="select-btn"
					@click="chooseStore(s)"
				>选择</button>
				<text v-else class="closed-text">暂停营业</text>
			</view>
			<!-- 门店列表为空（接口空数据且无兜底异常）：轻提示 -->
			<view v-if="stores.length === 0" class="stores-empty">
				<text class="stores-empty-text">暂无自提点信息</text>
			</view>
		</block>

		<!-- ============ tab2：选择其他自提点 ============ -->
		<block v-else>
			<!-- H5 定位成功信息条（兜底分支）：门店数据无经纬度字段，仅展示坐标、
				下方列表按默认顺序展示，不做距离排序（能力边界如实标注） -->
			<view v-if="located" class="located-bar">
				<text class="located-text">已定位：您附近的自提点如下（{{ locatedText }}）</text>
			</view>

			<!-- 灰色搜索框：输入地址查找附近自提点 -->
			<view class="search-box">
				<text class="search-icon">🔍</text>
				<input
					class="search-input"
					v-model="keyword"
					:focus="searchFocus"
					placeholder="输入地址，查找附近自提点"
					placeholder-class="search-placeholder"
					confirm-type="search"
					@confirm="onSearchConfirm"
					@blur="onSearchBlur"
				/>
			</view>

			<!-- 搜索结果区：confirm 后按关键字本地过滤已拉取 stores，复用 store-card 与选店逻辑；
				不发新请求，真实定位/地理编码依赖系统权限属外部能力不在本页做 -->
			<block v-if="searched">
				<view class="group-title">搜索结果</view>
				<view
					class="store-card history-card"
					v-for="s in filteredStores"
					:key="s.id"
					:class="{ 'card-disabled': isClosed(s) }"
				>
					<view class="store-icon-box">🏪</view>
					<view class="store-info">
						<text class="store-name small">{{ s.name }}</text>
						<text class="store-address">{{ s.address }}</text>
						<text v-if="s.openTime" class="store-open-time">营业时间 {{ s.openTime }}</text>
						<view class="tag-row">
							<text class="service-tag plain">{{ s.service || '冷冻/冷藏' }}</text>
						</view>
					</view>
					<!-- 营业中可选；停业卡置灰，与 tab1 同口径 -->
					<button
						v-if="!isClosed(s)"
						class="select-btn"
						@click="chooseStore(s)"
					>选择</button>
					<text v-else class="closed-text">暂停营业</text>
				</view>
				<!-- 无命中轻提示 -->
				<view v-if="filteredStores.length === 0" class="stores-empty">
					<text class="stores-empty-text">未找到与「{{ keyword }}」相关的自提点</text>
				</view>
			</block>

			<!-- 定位入口行 -->
			<view class="locate-row" @click="onLocateTap">
				<text class="locate-text">📍点击定位我当前的位置</text>
			</view>

			<!-- H5 已定位（未搜索态）：门店列表按默认顺序展示（数据同 tab1 接口源，
				无坐标不做距离排序，能力边界见 requestH5Location 注释） -->
			<block v-if="!searched && located">
				<view
					class="store-card history-card"
					v-for="s in stores"
					:key="s.id"
					:class="{ 'card-disabled': isClosed(s) }"
				>
					<view class="store-icon-box">🏪</view>
					<view class="store-info">
						<text class="store-name small">{{ s.name }}</text>
						<text class="store-address">{{ s.address }}</text>
						<text v-if="s.openTime" class="store-open-time">营业时间 {{ s.openTime }}</text>
						<view class="tag-row">
							<text class="service-tag plain">{{ s.service || '冷冻/冷藏' }}</text>
						</view>
					</view>
					<!-- 营业中可选；停业卡置灰，与 tab1 同口径 -->
					<button
						v-if="!isClosed(s)"
						class="select-btn"
						@click="chooseStore(s)"
					>选择</button>
					<text v-else class="closed-text">暂停营业</text>
				</view>
			</block>

			<!-- 静态占位区：未搜索且未定位时展示（搜索态由结果区替代） -->
			<view v-if="!searched && !located" class="empty-area">
				<text class="empty-icon">🏪</text>
				<text class="empty-text">未开启位置权限，无法获得附近自提点信息</text>
				<view class="btn-row">
					<button class="btn-primary" @click="onOtherAddressTap">选择其他地址</button>
					<button class="btn-ghost" @click="onOpenLocateTap">{{ located ? '已开启定位' : '开启定位' }}</button>
				</view>
			</view>
		</block>
	</view>
</template>

<script>
import { getStores } from '@/api/index.js'

// 本地兜底门店（M4-T-01：仅 GET /api/stores 请求失败时回退使用，与 M4 后端种子数据一致；
// 页面正常运行数据一律来自接口，此处不是写死数据源）
const FALLBACK_STORES = [
	{
		id: 1,
		name: '高黎柱发士多店(请自备袋子谢谢)',
		address: '广东省佛山市顺德区容桂高黎环涌西路桥灵坊23号',
		service: '冷冻/冷藏',
		status: 'open'
	},
	{
		id: 2,
		name: '高黎惠民百货店',
		address: '广东省佛山市顺德区容桂高黎惠民路12号',
		service: '冷冻/冷藏',
		status: 'open'
	},
	{
		id: 3,
		name: '容桂天佑城自提点',
		address: '广东省佛山市顺德区容桂街道天佑城B座1层',
		service: '冷冻/冷藏',
		status: 'open'
	},
	{
		id: 4,
		name: '朝阳社区便利店',
		address: '广东省佛山市顺德区容桂朝阳路38号',
		service: '冷冻/冷藏',
		status: 'closed'
	}
]

export default {
	data() {
		return {
			// 当前激活 tab：0 我的自提点 / 1 选择其他自提点
			activeTab: 0,
			// 搜索关键字
			keyword: '',
			// tab2 搜索态：confirm 后置 true 展示结果区；关键字清空回车回到占位态
			searched: false,
			// 门店列表（M4-T-01：GET /api/stores 拉取；请求失败回退 FALLBACK_STORES 兜底）
			stores: [],
			// H5 定位状态：located 定位成功（展示坐标信息条）/ locating 定位进行中（防重复触发）
			located: false,
			locating: false,
			// 用户坐标（H5 浏览器定位）：仅信息条展示用，门店无坐标不参与距离计算
			userLat: null,
			userLng: null,
			// 搜索框聚焦标记：onOtherAddressTap 置 true 触发聚焦，blur 后复位以便再次触发
			searchFocus: false
		}
	},
	computed: {
		// 当前自提点（store 恢复/切换后自动更新）
		pickupPoint() {
			return this.$store.state.pickupPoint
		},
		// 当前门店是否停业（pickupPoint.status 快照由接口同步/选店时写入）
		pickupClosed() {
			return !!(this.pickupPoint && this.pickupPoint.status === 'closed')
		},
		// tab2 搜索结果：对已拉取 stores 做名称/地址包含匹配（不区分大小写），
		// 仅消费 loadStores 的接口数据或其兜底，不发新请求
		filteredStores() {
			if (!this.searched) return []
			const kw = (this.keyword || '').trim().toLowerCase()
			if (!kw) return []
			return this.stores.filter(s => {
				const name = s && s.name ? String(s.name).toLowerCase() : ''
				const address = s && s.address ? String(s.address).toLowerCase() : ''
				return name.indexOf(kw) !== -1 || address.indexOf(kw) !== -1
			})
		},
		// 定位信息条坐标文案：保留 4 位小数（约 11m 精度，展示足够）
		locatedText() {
			if (this.userLat === null || this.userLng === null) return ''
			return `${this.userLat.toFixed(4)}, ${this.userLng.toFixed(4)}`
		}
	},
	onShow() {
		// 每次进入页面重新拉取（门店新增/停业状态变化及时反映）
		this.loadStores()
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
		// 切换顶部 tab
		switchTab(index) {
			this.activeTab = index
		},
		// 停业判定（M4 契约 status:'open'|'closed'；兼容旧数据 closed:boolean 兜底）
		isClosed(s) {
			if (!s) return false
			return s.status === 'closed' || s.closed === true
		},
		// 拉取门店列表（M4-T-01）：成功用接口数据，失败回退本地兜底并提示
		async loadStores() {
			try {
				const res = await getStores()
				const list = Array.isArray(res && res.data) ? res.data : []
				// 接口成功但为空列表时同样回退兜底，避免整页空白
				this.stores = list.length > 0 ? list : FALLBACK_STORES
			} catch (e) {
				this.stores = FALLBACK_STORES
				uni.showToast({
					title: '门店加载失败，已展示兜底数据',
					icon: 'none',
					duration: 2000
				})
			}
		},
		// 选择门店（仅营业门店可点）：按 M4 契约写入 storeId/status 快照，
		// timeText 保留现有时段文案（setPickupPoint 局部合并不会覆盖）
		chooseStore(store) {
			if (this.isClosed(store)) return
			this.$store.commit('setPickupPoint', {
				storeId: store.id,
				name: store.name,
				service: store.service || '支持冷冻/冷藏',
				timeText: '明天16:00自提',
				address: store.address,
				status: store.status || 'open'
			})
			uni.showToast({
				title: '切换成功',
				icon: 'success',
				duration: 1500
			})
			// 稍作停留让 toast 可见，再返回上一页
			setTimeout(() => {
				uni.navigateBack()
			}, 600)
		},
		// 门店管理入口（占位）：管理后台暂未开放
		onManageTap() {
			uni.showToast({
				title: '暂未开放',
				icon: 'none',
				duration: 1500
			})
		},
		// tab2 搜索确认：confirm 才过滤，避免输入过程抖动；
		// 仅本地过滤已拉取 stores，关键字为空时回到占位态
		onSearchConfirm() {
			if (!(this.keyword || '').trim()) {
				this.searched = false
				return
			}
			this.searched = true
		},
		// 点击定位当前位置：H5 走浏览器定位流程；小程序端保持引导 toast 不动（方案约束）
		onLocateTap() {
			// #ifdef H5
			this.requestH5Location()
			// #endif
			// #ifndef H5
			uni.showToast({
				title: '未开启位置权限',
				icon: 'none',
				duration: 1500
			})
			// #endif
		},
		// 选择其他地址：H5 聚焦搜索框引导输入（浏览器聚焦自动滚到搜索区）；小程序端保持引导 toast
		onOtherAddressTap() {
			// #ifdef H5
			this.searchFocus = true
			// #endif
			// #ifndef H5
			uni.showToast({
				title: '请输入地址搜索附近自提点',
				icon: 'none',
				duration: 1500
			})
			// #endif
		},
		// 开启定位：与 onLocateTap 合并语义（同走定位流程）；小程序端保持引导 toast
		onOpenLocateTap() {
			// #ifdef H5
			this.requestH5Location()
			// #endif
			// #ifndef H5
			uni.showToast({
				title: '未开启位置权限',
				icon: 'none',
				duration: 1500
			})
			// #endif
		},
		// 搜索框失焦复位聚焦标记：focus 值变化才会重新拉起聚焦，保证可反复触发
		onSearchBlur() {
			this.searchFocus = false
		},
		// ==================== H5 定位选店 ====================
		// #ifdef H5
		// H5 浏览器定位（无地图 API，纯 navigator.geolocation）。
		// 能力边界：GET /api/stores 返回仅 id/name/address/service/status/createdAt，
		// 无经纬度字段，故不做 Haversine 距离计算与排序；定位成功仅展示坐标信息条，
		// 门店列表保持默认顺序（后端补坐标后可在此升级为距离排序）
		requestH5Location() {
			// 定位进行中忽略重复点击
			if (this.locating) return
			const geo = typeof navigator !== 'undefined' ? navigator.geolocation : null
			if (!geo) {
				uni.showToast({
					title: '当前环境不支持定位，可手动搜索门店',
					icon: 'none',
					duration: 2000
				})
				return
			}
			this.locating = true
			geo.getCurrentPosition(
				(pos) => {
					this.locating = false
					const c = pos && pos.coords ? pos.coords : {}
					this.userLat = typeof c.latitude === 'number' ? c.latitude : null
					this.userLng = typeof c.longitude === 'number' ? c.longitude : null
					if (this.userLat === null || this.userLng === null) {
						uni.showToast({
							title: '定位失败，可手动搜索门店',
							icon: 'none',
							duration: 2000
						})
						return
					}
					this.located = true
					uni.showToast({
						title: '已获取您的位置',
						icon: 'none',
						duration: 1500
					})
				},
				() => {
					// 用户拒绝授权/超时等失败：保持搜索引导，不报错、不阻塞主流程
					this.locating = false
					uni.showToast({
						title: '未授权定位，可手动搜索门店',
						icon: 'none',
						duration: 2000
					})
				},
				{ enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
			)
		},
		// #endif
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

<style scoped>
/* 页面整体白底 */
page {
	background-color: #ffffff;
}
.pickup-page {
	min-height: 100vh;
	background-color: #ffffff;
	padding-bottom: 40rpx;
}

/* ============ 顶部 tab ============ */
.tab-bar {
	display: flex;
	flex-direction: row;
	align-items: center;
	background-color: #ffffff;
	height: 88rpx;
	border-bottom: 1rpx solid #f0f0f0;
}
.tab-item {
	flex: 1;
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	height: 88rpx;
	position: relative;
}
.tab-text {
	font-size: 30rpx;
	color: #333333;
}
.tab-active .tab-text {
	color: #e02020;
	font-weight: bold;
}
/* 红色下划线指示 */
.tab-line {
	position: absolute;
	bottom: 0;
	left: 50%;
	transform: translateX(-50%);
	width: 56rpx;
	height: 6rpx;
	border-radius: 3rpx;
	background-color: #e02020;
}

/* ============ 分组标题 ============ */
.group-title {
	font-size: 26rpx;
	color: #999999;
	padding: 28rpx 24rpx 16rpx;
}
.group-title-row {
	display: flex;
	flex-direction: row;
	align-items: center;
	justify-content: space-between;
	padding: 28rpx 24rpx 16rpx;
}
.group-title-row .group-title {
	padding: 0;
}
.manage-text {
	font-size: 26rpx;
	color: #666666;
}

/* ============ 门店卡片（白卡 12rpx 圆角） ============ */
.store-card {
	display: flex;
	flex-direction: row;
	align-items: flex-start;
	background-color: #ffffff;
	border-radius: 12rpx;
	margin: 0 24rpx 20rpx;
	padding: 24rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.06);
}
/* 当前自提点大卡 */
.current-card {
	background-color: #ffffff;
	border: 1rpx solid #ffe3e3;
}
/* 停业卡置灰 */
.card-disabled {
	opacity: 0.55;
}
.card-disabled .store-name {
	color: #999999;
}

/* 门店 emoji 图标块 */
.store-icon-box {
	width: 96rpx;
	height: 96rpx;
	flex-shrink: 0;
	display: flex;
	align-items: center;
	justify-content: center;
	font-size: 52rpx;
	background-color: #fdecec;
	border-radius: 12rpx;
	margin-right: 20rpx;
}
.store-icon-box.big {
	width: 120rpx;
	height: 120rpx;
	font-size: 64rpx;
}

/* 门店信息 */
.store-info {
	flex: 1;
	display: flex;
	flex-direction: column;
	align-items: flex-start;
	overflow: hidden;
}
.store-name {
	font-size: 32rpx;
	font-weight: bold;
	color: #222222;
	margin-bottom: 8rpx;
}
.store-name.small {
	font-size: 28rpx;
}
.store-address {
	font-size: 24rpx;
	color: #999999;
	line-height: 1.5;
	margin-bottom: 8rpx;
}
.store-open-time {
	font-size: 24rpx;
	color: #999999;
	margin-bottom: 8rpx;
}
.tag-row {
	display: flex;
	flex-direction: row;
	margin-top: 4rpx;
}
/* 冷冻/冷藏服务标签：浅红底红字圆角 */
.service-tag {
	font-size: 22rpx;
	color: #e02020;
	background-color: #fdecec;
	border-radius: 8rpx;
	padding: 4rpx 14rpx;
}
.service-tag.plain {
	color: #666666;
	background-color: #f5f5f5;
}

/* 选择按钮：红底白字（显式覆盖 uni-button 默认圆角与行高） */
.select-btn {
	display: flex;
	align-items: center;
	justify-content: center;
	flex-shrink: 0;
	align-self: center;
	min-width: 120rpx;
	height: 60rpx;
	padding: 0 28rpx;
	margin: 0;
	background-color: #e02020;
	color: #ffffff;
	font-size: 26rpx;
	line-height: 1;
	border-radius: 32rpx;
}
.select-btn::after {
	border: none;
}

/* 暂停营业文案 */
.closed-text {
	flex-shrink: 0;
	align-self: center;
	font-size: 26rpx;
	color: #999999;
}

/* 当前门店停业警示（M4-T-01）：红字醒目 */
.closed-warn {
	font-size: 24rpx;
	color: #e02020;
	font-weight: 600;
	margin-top: 10rpx;
}

/* 门店列表空态 */
.stores-empty {
	display: flex;
	align-items: center;
	justify-content: center;
	padding: 80rpx 0;
}
.stores-empty-text {
	font-size: 26rpx;
	color: #bbbbbb;
}

/* ============ 选择其他自提点 ============ */
/* H5 定位成功信息条：浅红底红字，坐标如实展示 */
.located-bar {
	margin: 20rpx 24rpx 0;
	padding: 16rpx 24rpx;
	background-color: #fdecec;
	border-radius: 12rpx;
}
.located-text {
	font-size: 24rpx;
	color: #e02020;
	line-height: 1.5;
}
/* 灰色搜索框 */
.search-box {
	display: flex;
	flex-direction: row;
	align-items: center;
	margin: 24rpx;
	padding: 0 24rpx;
	height: 72rpx;
	background-color: #f5f5f5;
	border-radius: 36rpx;
}
.search-icon {
	font-size: 28rpx;
	margin-right: 12rpx;
}
.search-input {
	flex: 1;
	height: 72rpx;
	font-size: 26rpx;
	color: #333333;
}
.search-placeholder {
	color: #bbbbbb;
	font-size: 26rpx;
}

/* 定位入口行 */
.locate-row {
	display: flex;
	flex-direction: row;
	align-items: center;
	padding: 24rpx;
}
.locate-text {
	font-size: 28rpx;
	color: #e02020;
}

/* 静态占位区 */
.empty-area {
	display: flex;
	flex-direction: column;
	align-items: center;
	justify-content: center;
	padding: 120rpx 40rpx 160rpx;
}
.empty-icon {
	font-size: 110rpx;
	margin-bottom: 32rpx;
}
.empty-text {
	font-size: 26rpx;
	color: #999999;
	margin-bottom: 48rpx;
}
.btn-row {
	display: flex;
	flex-direction: row;
	align-items: center;
}
/* 红底白字按钮（显式覆盖 uni-button 默认样式） */
.btn-primary {
	display: flex;
	align-items: center;
	justify-content: center;
	width: 264rpx;
	height: 72rpx;
	padding: 0;
	margin: 0 20rpx 0 0;
	background-color: #e02020;
	color: #ffffff;
	font-size: 28rpx;
	line-height: 1;
	border-radius: 36rpx;
}
.btn-primary::after {
	border: none;
}
/* 白底红边按钮 */
.btn-ghost {
	display: flex;
	align-items: center;
	justify-content: center;
	width: 264rpx;
	height: 72rpx;
	padding: 0;
	margin: 0;
	background-color: #ffffff;
	color: #e02020;
	font-size: 28rpx;
	line-height: 1;
	border: 1rpx solid #e02020;
	border-radius: 36rpx;
}
.btn-ghost::after {
	border: none;
}
</style>
