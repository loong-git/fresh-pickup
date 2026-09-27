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

	<!-- F-06 地图卡（页面层，tab2 专属）：R3-3 根治——原置于 tab2 block 内，activeTab=0 时整个
		分支不渲染导致容器不在 DOM、tryInitMap 静默失败（v-show 被父级 block 短路）；移至页面层后
		容器常驻（v-show 仅切显隐），地图实例页面生命周期内单次初始化（F-06.7 R4：高德 JS API 2.0） -->
	<view class="map-card" v-show="activeTab === 1 && !mapFailed && mappableStores.length">
		<view id="pickup-map" class="map-inner"></view>
		<view class="map-legend">
			<!-- R7 三态语义：营业红 pin（原蓝色圆点时代终结，图例色同步） -->
			<view class="legend-item"><view class="legend-dot" style="background-color: #E02020;"></view><text class="legend-text">营业</text></view>
			<view class="legend-item"><view class="legend-dot" style="background-color: #E02020;"></view><text class="legend-text">选中</text></view>
			<view class="legend-item"><view class="legend-dot" style="background-color: #999999;"></view><text class="legend-text">停业</text></view>
			<!-- F-06.6 R2 修复③：第四项「我所在的位置」——R8 头像化后光标为蓝色头像圆+蓝脉冲，
				图例 dot 颜色同步 #1E6FFF 与光圈一致 -->
			<view class="legend-item"><view class="legend-dot" style="background-color: #1E6FFF;"></view><text class="legend-text">我所在的位置</text></view>
			<!-- F-06.7 R4：切换高德后中文来源尾注同步为高德地图（图面右下自带高德 logo+版权条，此处为图例行） -->
			<text class="legend-source">地图来源 © 高德地图</text>
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
			<!-- R7 定位地址条：左侧 📍 + 逆地理地址（locatedAddress 有值显示，无值回退现有
				坐标/IP 文案 locatedBarText，两行内截断）+ 右侧「◎ 重新定位」按钮；
				定位中防重复由 requestH5Location 的 locating 重入锁保证，按钮仅切换文案并降透明 -->
			<view v-if="located" class="located-bar">
				<text class="located-text">📍 {{ locatedAddress || locatedBarText }}</text>
				<view
					class="relocate-btn"
					:class="{ 'relocate-btn-disabled': locating }"
					@click="requestH5Location"
				>
					<text class="relocate-btn-text">{{ locating ? '定位中…' : '◎ 重新定位' }}</text>
				</view>
			</view>

			<!-- F-06 地图卡已上移至页面层（tab2 block 外）：activeTab=0 时 block 分支不渲染会令容器
				脱离 DOM、tryInitMap 静默失败（R3-3 实录），页面层 v-show 常驻规避 -->
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

			<!-- F-06 未搜索态始终展示门店列表（有地图后空态区已无必要）：已定位按 haversine
				距离升序 + 行内距离徽标，未定位按默认顺序；无坐标门店排尾部，卡片 id 供 marker 联动滚动 -->
			<block v-if="!searched">
				<view
					class="store-card history-card"
					v-for="s in sortedLocatedStores"
					:key="s.id"
					:id="'store-row-' + s.id"
					:class="{ 'card-disabled': isClosed(s) }"
				>
					<view class="store-icon-box">🏪</view>
					<view class="store-info">
						<view class="store-name-row">
							<text class="store-name small">{{ s.name }}</text>
							<text v-if="distText(s)" class="dist-badge">{{ distText(s) }}</text>
						</view>
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

		</block>
	</view>
</template>

<script>
import { mapState } from 'vuex'
import { getStores } from '@/api/index.js'
import { DEFAULT_AVATAR } from '@/utils/avatar.js'
// F-06.7 R4：高德 JS API 2.0 配置与 GCJ-02 转换。F-06.7.2 R6 坐标真源修正后为**不对称转换**：
// 门店坐标（m13 起 DB 存高德 GCJ-02 真源，与底图同源）进图直用不再转换；仅用户浏览器定位
//（WGS84）经 wgs84ToGcj02 转换——进图（addUserMarker）与算距离（computeDistances，R6-2）都转
import { AMAP_KEY, AMAP_SECURITY_CODE } from '@/api/config.js'
import { wgs84ToGcj02 } from '@/utils/gcj02.js'
// R6-1：marker 运行时 DOM 样式走纯 CSS（页面级 SFC 全 style 块被编译器 scope 化，见文件尾注）
import '@/styles/pickup-map.css'

// 本地兜底门店（M4-T-01：仅 GET /api/stores 请求失败时回退使用，与 M4 后端种子数据一致；
// 页面正常运行数据一律来自接口，此处不是写死数据源）；FALLBACK 无 lng/lat 字段，
// 走 F-06 降级链：hasCoord 豁免 → 不进地图/不参与距离排序，列表功能完整
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
			// R6-3：ipLocated 定位来源标记——true 表示坐标来自高德 IP 粗定位兜底（城市级精度，
			// 浏览器定位被拒/不可用时降级），信息条文案切换为 IP 粗定位提示；浏览器定位成功时复位
			ipLocated: false,
			// 用户坐标（H5 浏览器定位，WGS84 原值）：locatedText 信息条展示原值；
			// R6-2 距离/进图转换只在 computeDistances/addUserMarker 内局部发生，本字段不写转换结果
			userLat: null,
			userLng: null,
			// 轮1 找茬修复（R6 坐标双重转换）：用户坐标是否已是 GCJ-02。浏览器定位（navigator.geolocation）
			// 返回 WGS84 须 wgs84ToGcj02 转换（false）；高德 IP 兜底（AMap.Geolocation noGeoLocation:3）
			// 返回的坐标本就是高德 GCJ-02，再转一次引入 ~500m 级双重偏移——置 true 后所有用户侧
			// 使用点（computeDistances/computeWalkDistances/reverseGeocode/addUserMarker）按本标记
			// 跳过转换；门店侧（DB GCJ-02 真源）口径不变
			userCoordIsGcj: false,
			// 搜索框聚焦标记：onOtherAddressTap 置 true 触发聚焦，blur 后复位以便再次触发
			searchFocus: false,
			// F-06 地图状态：mapReady 地图已初始化（渲染地图卡）/ mapFailed 降级标记（高德 JS 加载或渲染失败，隐地图保列表）
			mapReady: false,
			mapFailed: false,
			// F-06 选中的门店 id（marker 点击联动列表）
			selectedStoreId: null,
			// F-06 距离表：门店 id → 距用户米数（定位成功后计算；非可地图门店无条目）
			distMap: {},
			// R7 步行距离表：门店 id → AMap.Walking 步行路径米数（定位成功后异步计算）；
			// 仅 status==='complete' 且 routes[0].distance 为数值才写入，任何失败不写（保留直线距离回退）
			walkDistMap: {},
			// R7 逆地理编码地址：定位成功后 AMap.Geocoder.getAddress(用户GCJ) 的 formattedAddress；
			// 失败清空回退 locatedBarText（坐标/IP 文案）
			locatedAddress: ''
		}
	},
	computed: {
		// R8：登录态与用户资料（addUserMarker 判断头像来源用）
		...mapState(['user']),
		hasToken() { return !!(this.user && this.user.token) },
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
		// 定位信息条坐标文案：保留 4 位小数（约 11m 精度，展示足够）；
		// R6-2 口径：展示浏览器原始 WGS84 值，不展示转换后的 GCJ-02 值
		locatedText() {
			if (this.userLat === null || this.userLng === null) return ''
			return `${this.userLat.toFixed(4)}, ${this.userLng.toFixed(4)}`
		},
		// R6-3 定位信息条文案：IP 粗定位（浏览器定位失败后的高德 IP 兜底，城市级精度）与
		// 浏览器精确定位用不同文案，诚实区分精度来源
		locatedBarText() {
			if (this.ipLocated) return '已按 IP 粗定位（城市级，仅供附近门店参考）'
			return `已定位：您附近的自提点如下（${this.locatedText}）`
		},
		// F-06 可地图门店：lng/lat 均为数值才进地图与距离计算（历史数据/FALLBACK 兜底无坐标自动豁免）
		mappableStores() {
			return (this.stores || []).filter(s => this.hasCoord(s))
		},
		// F-06 已定位未搜索态列表：按距离升序（distMap 无条目排尾部保持原相对顺序）
		sortedLocatedStores() {
			if (!(this.located && this.userLat !== null && this.userLng !== null)) return this.stores
			const d = this.distMap || {}
			return [...this.stores].sort((a, b) => {
				const da = typeof d[a.id] === 'number' ? d[a.id] : Number.MAX_VALUE
				const db = typeof d[b.id] === 'number' ? d[b.id] : Number.MAX_VALUE
				return da - db
			})
		}
	},
	// F-06 响应式初始化触发（R3-4 根修）：onShow/onReady 均可能早于/晚于门店数据就绪，
	// 任何 fire-and-forget 调用都会踩「mappableStores=0 守卫静默退出且无重试」的坑；
	// watch 可地图门店列表——数据就绪即尝试初始化（_initingMap 锁 + mapFailed 降级标记防重入）
	watch: {
		mappableStores() {
			// #ifdef H5
			if (this.mappableStores.length > 0) {
				this.tryInitMap()
			}
			// #endif
		}
	},
	onShow() {
		// U8（轮2 找茬）：换头像后回到本页，地图光标停留旧头像——onShow 补刷用户 marker
		//（addUserMarker 内部先移除旧 marker 再重建，幂等；地图未就绪/未定位时守卫短路，
		// 由 _pendingUserMarker 待补链兜底，不重复置位）
		// #ifdef H5
		if (this.mapReady && this._userMarker) this.addUserMarker()
		// #endif
		// 每次进入页面重新拉取（门店新增/停业状态变化及时反映）；拉完尝试初始化地图（F-06）
		// 轮1 找茬修复：then 回调加页面存活守卫——快速进出页面时旧（已 onUnload）实例的
		// loadStores().then 仍会触发，经 document.getElementById('pickup-map') 命中新实例的容器
		// 叠初始化泄漏（_destroyed 由 onUnload 置位，tryInitMap 首行同样守卫）
		this.loadStores().then(() => {
			// #ifdef H5
			if (this._destroyed) return
			this.tryInitMap()
			// #endif
		})
	},
	// #ifdef H5
	onReady() {
		// 页面级滚轮兜底：index.html 锁 html,body overflow:hidden 后 PC 滚轮默认滚动失效，手动接管页面滚动（照搜索页同款方案）
		this._pageWheelHandler = (e) => this.onPageWheel(e)
		document.addEventListener('wheel', this._pageWheelHandler, { passive: false })
		// R3-4 onShow 先于 DOM 挂载触发：onShow 里的 tryInitMap 会因容器未挂载静默返回且无重试
		// （实测探针：tryInitMap 从未被调用）——onReady 是挂载后的确定性时机，在此补一次初始化；
		// _initingMap 锁保证与 onShow 路径并发安全
		this.tryInitMap()
	},
	onUnload() {
		// 轮1 找茬修复：页面销毁标记——onShow 的 loadStores().then 与 tryInitMap 轮询/异步链
		// 可能在 onUnload 后仍触发，检测本标记直接 return，防旧实例叠初始化泄漏
		this._destroyed = true
		if (this._pageWheelHandler) {
			document.removeEventListener('wheel', this._pageWheelHandler)
			this._pageWheelHandler = null
		}
		this.destroyMap()
	},
	// #endif
	methods: {
		// 切换顶部 tab：R3-3 容器 v-show 常驻后切 tab 不再销毁地图，显示时仅需重算尺寸
		// （display:none 期间高德容器尺寸失效，回显必须 resize 恢复尺寸 + setFitView 恢复视野；
		// F-06.7 R4：setFitView 替代 fitBounds，无参自适应全部覆盖物，padding [60,60,60,60]）
		switchTab(index) {
			this.activeTab = index
			// #ifdef H5
			if (index === 1) {
				this.$nextTick(() => {
					const map = this._amapMap
					if (map) {
						// 防御式 resize（typeof 判断）：容器刚从 display:none 恢复，强制重算内部尺寸
						if (typeof map.resize === 'function') map.resize()
						// U3（轮2 找茬）：用户手动缩放/拖动过地图（_userAdjustedView）则只恢复尺寸不再
						// setFitView 抢回用户视野；未动过视野才维持自适应回显（旧口径无条件 refit 丢手动视野）
						if (!this._userAdjustedView && this.mappableStores.length && typeof map.setFitView === 'function') {
							map.setFitView(null, false, [60, 60, 60, 60])
						}
					} else if (!this.mapFailed) {
						// R5-2 补初始化（浏览器实测衍生缺陷）：onShow/onReady/watch 时机若因容器
						// 未挂载等静默早退则无重试兜底；切 tab 后容器已可见，对「未降级且无实例」
						// 的态补一次确定性尝试。mapFailed=true 仍按降级链设计不重试（永隐地图保列表）
						this.tryInitMap()
					}
				})
			}
			// #endif
		},
		// 停业判定（M4 契约 status:'open'|'closed'；兼容旧数据 closed:boolean 兜底）
		isClosed(s) {
			if (!s) return false
			return s.status === 'closed' || s.closed === true
		},
		// F-06 坐标有效性：lng/lat 均为有限数值且在地理合法范围内才可进地图/参与距离计算
		// （R1-2：错误坐标如 lat=999 会让 fitBounds 飞出地球，纬度 [-90,90] 经度 [-180,180] 硬校验）
		hasCoord(s) {
			if (!s) return false
			if (typeof s.lng !== 'number' || !isFinite(s.lng) || typeof s.lat !== 'number' || !isFinite(s.lat)) return false
			return s.lat >= -90 && s.lat <= 90 && s.lng >= -180 && s.lng <= 180
		},
		// F-06/R7 距离徽标文案：优先步行距离（walkDistMap 有值显示「步行XX米」/「步行X.Xkm」），
		// 无步行值回退现有直线距离文案（「XXm」/「X.Xkm」，原口径不变）；均无条目返回空（不渲染徽标）
		distText(s) {
			const w = (this.walkDistMap || {})[s && s.id]
			if (typeof w === 'number') {
				return w < 1000 ? `步行${Math.round(w)}米` : `步行${(w / 1000).toFixed(1)}km`
			}
			const m = (this.distMap || {})[s && s.id]
			if (typeof m !== 'number') return ''
			return m < 1000 ? `${Math.round(m)}m` : `${(m / 1000).toFixed(1)}km`
		},
		// R7 气泡文案（marker 点击名签）：「{门店名} ｜ 距您步行XX米」，有步行值用步行值，
		// 无步行值回退直线距离（「距您XX米」/「距您X.Xkm」），两者皆无只显门店名；
		// 调用方（renderMarkers）经 textContent 注入，防注入惯例保留
		bubbleText(s) {
			if (!s) return ''
			const w = (this.walkDistMap || {})[s.id]
			const m = (this.distMap || {})[s.id]
			let distPart = ''
			if (typeof w === 'number') {
				distPart = `距您步行${w < 1000 ? Math.round(w) + '米' : (w / 1000).toFixed(1) + 'km'}`
			} else if (typeof m === 'number') {
				distPart = `距您${m < 1000 ? Math.round(m) + '米' : (m / 1000).toFixed(1) + 'km'}`
			}
			return distPart ? `${s.name} ｜ ${distPart}` : s.name
		},
		// F-06 haversine 球面距离（米）：地球半径 6371008.8m（IUGG 平均半径），两点大圆劣弧长度
		haversineMeters(lat1, lng1, lat2, lng2) {
			const R = 6371008.8
			const rad = Math.PI / 180
			const dLat = (lat2 - lat1) * rad
			const dLng = (lng2 - lng1) * rad
			const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLng / 2) ** 2
			return 2 * R * Math.asin(Math.sqrt(a))
		},
		// F-06 定位成功后计算各可地图门店距离（仅数值化，排序交给 computed sortedLocatedStores）
		// R6-2 距离口径：m13 后门店坐标已是高德 GCJ-02 真源，用户 WGS84 原值与之混算 haversine
		// 会引入 ~500m 系统性偏移——先把用户坐标 wgs84ToGcj02 转 GCJ-02，再与门店同系计算；
		// 转换仅发生在本函数局部，locatedText/userLat/userLng 仍保持定位原始值。
		// 轮1 找茬修复：userCoordIsGcj=true（高德 IP 兜底坐标本就是 GCJ-02）时跳过转换，
		// 防双重转换引入反向偏移；门店侧 s.lng/s.lat 口径不变
		computeDistances() {
			if (this.userLat === null || this.userLng === null) return
			const u = this.userCoordIsGcj
				? { lng: this.userLng, lat: this.userLat }
				: wgs84ToGcj02(this.userLng, this.userLat)
			const d = {}
			for (const s of this.mappableStores) {
				d[s.id] = this.haversineMeters(u.lat, u.lng, s.lat, s.lng)
			}
			this.distMap = d
			// R7：直线距离落表后异步触发步行距离计算与逆地理地址（浏览器定位/IP 兜底两条
			// 成功路径都经此处，单一触发点不漏）；两方法内部任何失败静默保留回退，不影响本流程
			this.computeWalkDistances()
			this.reverseGeocode()
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
		// H5 浏览器定位（纯 navigator.geolocation）。F-06 升级：定位成功后计算各可地图门店
		// haversine 距离（列表按距离升序 + 行内徽标），并在地图上补用户位置标记。
		// R6-3 失败链增强：①失败文案按真实原因细分（权限被拒/非安全上下文/无定位源/超时，
		// 不再把所有失败都归为「未授权」）；②浏览器定位失败后自动降级高德 IP 粗定位兜底
		//（AMap.Geolocation noGeoLocation:3 纯 IP 模式，城市级精度，信息条诚实标注来源）
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
			// 非安全上下文（非 localhost/HTTPS）：浏览器直接禁用 geolocation，提前给出对症指引
			if (typeof window !== 'undefined' && window.isSecureContext === false) {
				uni.showToast({
					title: '定位需用 localhost 或 HTTPS 访问，可手动搜索门店',
					icon: 'none',
					duration: 2500
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
						this.tryAmapIpFallback('定位失败，可手动搜索门店')
						return
					}
					this.ipLocated = false
					// 浏览器 geolocation 返回 WGS84：标记坐标需转换（userCoordIsGcj=false），先置标记
					// 再进 computeDistances/addUserMarker（IP 兜底路径返回 GCJ-02 置 true，见 tryAmapIpFallback）
					this.userCoordIsGcj = false
					this.located = true
					this.computeDistances()
					this.addUserMarker()
					uni.showToast({
						title: '已获取您的位置',
						icon: 'none',
						duration: 1500
					})
				},
				(err) => {
					// 按错误码细分文案：1=权限被拒 2=定位源不可用 3=超时（此前统一「未授权」吞掉了真实原因）
					this.locating = false
					const code = err && typeof err.code === 'number' ? err.code : -1
					let tip = '定位失败，可手动搜索门店'
					if (code === 1) {
						tip = '定位权限被拒绝：点地址栏左侧图标允许位置访问'
					} else if (code === 2) {
						tip = '当前环境无定位能力，可手动搜索门店'
					} else if (code === 3) {
						tip = '定位超时，请重试'
					}
					this.tryAmapIpFallback(tip)
				},
				{ enableHighAccuracy: false, timeout: 10000, maximumAge: 60000 }
			)
		},
		// R6-3 高德 IP 粗定位兜底：浏览器定位失败后自动尝试（AMap.Geolocation 纯 IP 模式，
		// noGeoLocation:3 禁用浏览器定位通道避免二次失败）；成功则降级出光标+距离（城市级精度，
		// ipLocated=true 信息条切换为 IP 粗定位文案），失败保持浏览器定位的原失败提示不覆盖。
		// 轮1 找茬修复：①AMap JS 未加载时不再静默 return（browserFailTip 永不提示）——直接透传提示；
		// ②AMap.plugin 回调不触发时 ipFallbacking 永久卡 true——8s setTimeout 超时复位重入锁并提示，
		// 局部 done 标志保证超时与回调竞态时复位/提示只发生一次（幂等防双 toast）
		tryAmapIpFallback(browserFailTip) {
			const AMap = window.AMap
			if (!AMap) {
				// 高德 JS 未加载（加载失败/网络异常/8s 兜底已过）：IP 兜底无法进行，
				// 直接透传浏览器定位的失败提示，不再静默吞掉
				uni.showToast({
					title: browserFailTip,
					icon: 'none',
					duration: 2500
				})
				return
			}
			if (this.ipFallbacking) return
			this.ipFallbacking = true
			// done 幂等标志：getCurrentPosition 回调与下方 8s 超时竞态时，复位锁与失败提示只走一次
			let done = false
			let guardTimer = 0
			const finish = (tip) => {
				if (done) return
				done = true
				clearTimeout(guardTimer)
				this.ipFallbacking = false
				if (tip) {
					uni.showToast({
						title: tip,
						icon: 'none',
						duration: 2500
					})
				}
			}
			// 8s 兜底：plugin 加载/定位回调不触发（插件加载卡死等）时复位重入锁并提示失败，
			// 防用户此后点「重新定位」被永久拒之门外
			guardTimer = setTimeout(() => finish(browserFailTip), 8000)
			AMap.plugin('AMap.Geolocation', () => {
				try {
					const geo = new AMap.Geolocation({ enableHighAccuracy: false, timeout: 8000, noIpLocate: 0, noGeoLocation: 3 })
					geo.getCurrentPosition((status, result) => {
						if (status === 'complete' && result && result.position && typeof result.position.lng === 'number') {
							// IP 兜底返回的坐标本就是高德 GCJ-02：先置标记再算距离/进图，
							// 用户侧四处使用点按 userCoordIsGcj 跳过 wgs84ToGcj02（防双重转换偏移）
							this.userCoordIsGcj = true
							this.ipLocated = true
							this.userLat = result.position.lat
							this.userLng = result.position.lng
							this.located = true
							finish() // 仅复位锁+停超时（成功提示走下方 toast，无失败文案）
							this.computeDistances()
							this.addUserMarker()
							uni.showToast({
								title: '已按 IP 粗定位（城市级）',
								icon: 'none',
								duration: 2000
							})
						} else {
							finish(browserFailTip)
						}
					})
				} catch (e) {
					finish(browserFailTip)
				}
			})
		},
		// ==================== F-06 高德地图（AMap JS API 2.0，F-06.7 R4） ====================
		// 动态注入高德 JS API 2.0（webapi.amap.com，key 见 api/config.js）：任一环境只注入一次，幂等
		loadAMap() {
			return new Promise((resolve) => {
				if (typeof window === 'undefined') { resolve(false); return }
				if (window.AMap) { resolve(true); return }
				try {
					// F-06.7.1：安全密钥仅在非空时前置（实测该 key 鉴权通过无需 jscode，
					// 配置点保留，商用上线切 nginx 代理方案时在此设 window._AMapSecurityConfig）
					if (AMAP_SECURITY_CODE && !window._AMapSecurityConfig) {
						window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY_CODE }
					}
					if (!document.getElementById('amap-js-vendor')) {
						const script = document.createElement('script')
						script.id = 'amap-js-vendor'
						script.src = 'https://webapi.amap.com/maps?v=2.0&key=' + AMAP_KEY
						script.onload = () => resolve(!!window.AMap)
						script.onerror = () => resolve(false)
						document.head.appendChild(script)
					}
					// 8s 兜底：网络异常下 script 未回调也放行失败分支（降级链）
					setTimeout(() => resolve(!!window.AMap), 8000)
				} catch (e) {
					resolve(false)
				}
			})
		},
		// 尝试初始化地图：高德 JS 就绪 + 存在可地图门店 + 容器已挂载才执行；任一条件不满足静默降级
		async tryInitMap() {
			// 轮1 找茬修复：页面已销毁（onUnload 置 _destroyed）直接 return——快速进出页面时
			// 旧实例的轮询重试/异步链不会再经 document.getElementById 命中新实例容器叠初始化
			if (this._destroyed) return
			if (this.mapFailed) return
			if (this._amapMap) return
			// R3-4 数据未就绪轮询重试（根修静默返回坑）：onShow/onReady/watch 都可能早于或晚于
			// 门店数据到达，fire-and-forget 调用在「mappableStores=0」守卫处静默退出且无重试
			// （探针实录：tryInitMap 从未被调用）——改为 500ms 轮询直至数据就绪，20 次（10s）封顶
			if (!this.mappableStores.length) {
				if ((this._mapRetryCount || 0) < 20) {
					this._mapRetryCount = (this._mapRetryCount || 0) + 1
					setTimeout(() => this.tryInitMap(), 500)
				}
				return
			}
			// R2-2 初始化锁：tryInitMap 为 async，onShow 与 switchTab 并发触发时两次调用都会
			// 通过上方 _amapMap 判空（都在 await 之前）→ 双实例叠初始化泄漏，须互斥；
			// finally 保证任何早退/异常路径都释放锁
			if (this._initingMap) return
			this._initingMap = true
			try {
				const ok = await this.loadAMap()
				// F-06.7.1 §2 降级判据①：script 失败/window.AMap 不存在 → 立即降级（不依赖 complete）
				if (!ok || !window.AMap) {
					this.mapFailed = true
					try { window.__mapDowngradeReason = 'amap-load-failed@' + new Date().toISOString() } catch (e0) {}
					return
				}
				this.$nextTick(() => {
					const container = document.getElementById('pickup-map')
					// _destroyed 深守卫：await loadAMap 期间页面可能已 onUnload，容器此时命中的
					// 可能是新实例的容器——销毁后不再叠初始化
					if (!container || this._amapMap || this._destroyed) return
					try {
						// F-06.7 R4：高德 JS API 2.0，2D 视图；GCJ-02 为高德原生坐标系。
						// F-06.7.2 R6 坐标真源修正：门店坐标（DB=高德 GCJ-02 真源）与底图同源直用，
						// 仅用户 WGS84 定位坐标经 wgs84ToGcj02 转换后进图（不对称转换口径）
						const AMap = window.AMap
						// R5-1 关键修复（浏览器实测）：uniapp H5 将 <view id="pickup-map"> 编译为
						// <uni-view> 自定义元素，不满足 AMap 2.0 构造函数对容器的 HTMLDivElement
						// instanceof 判定（内部回退按 id 解析元素对象得 null）→ 抛
						// 'Map container div not exist' → catch 置 mapFailed 全量降级（__mapDebug=null）。
						// 改传 id 字符串由 AMap 自行 getElementById 解析——v-show 容器常驻 DOM，
						// display:none 不影响解析（已实测验证）；container 变量仅保留用于
						// 挂载守卫与 8s 超时的渲染层探查（querySelector canvas.amap-layer）
						// R9 配套：scrollWheel:true 显式开启滚轮缩放——AMap 2.0 默认 false（防页面滚动冲突），
						// 不开的话 onPageWheel 对地图区域的放行守卫只是"页面不滚"，地图也不会缩放；
						// 开启后地图内滚轮由 AMap 原生缩放接管（地图外仍由 onPageWheel 驱动页面滚动）
						const map = new AMap.Map('pickup-map', { viewMode: '2D', scrollWheel: true })
						this._amapMap = map
						this._markers = {}
						// U3（轮2 找茬）：视野记忆——用户手动缩放（zoomend）/拖动（dragend）即置一次性
						// 标记，此后 switchTab 回显不再 setFitView 抢回手动视野；标记随 destroyMap 复位
						const markUserAdjustedView = () => { this._userAdjustedView = true }
						map.on('zoomend', markUserAdjustedView)
						map.on('dragend', markUserAdjustedView)
						this.renderMarkers()
						this.mapReady = true
						// 轮1 找茬修复（_pendingUserMarker 语义闭环）：定位成功早于地图就绪时
						// addUserMarker 曾早退并置待补标记，此处地图就绪后补调，头像光标不丢
						if (this._pendingUserMarker) this.addUserMarker()
						try { window.__mapDebug = { initAt: new Date().toISOString(), markers: this.mappableStores.length, engine: 'AMap-2.0' } } catch (e3) {}
						// F-06.7.1 §2 降级判据③（仅加分确认，不作降级依据）：complete 触发刷新探针。
						// 实测注入场景 complete 可能 10s 内不触发（矢量数据走 WebSocket 通道 performance
						// 不可见），故降级不得唯一依赖本事件
						map.on('complete', () => {
							if (this._amapMap !== map) return
							try {
								window.__mapDebug = Object.assign({}, window.__mapDebug, { completeAt: new Date().toISOString() })
							} catch (e4) {}
						})
						// R3-2 沉淀：8s 定时器**实例闭包化**——触发时校验 this._amapMap === map
						//（本实例仍是现行实例才允许降级）；F-06.7.1 §2 降级判据②：不唯一依赖 complete，
						// 检查渲染层实际状态——canvas.amap-layer 已存在 = 矢量层在渲染，不降级；
						// 零渲染才降级（隐地图保列表，原降级链保留）
						setTimeout(() => {
							if (this._amapMap !== map) return
							let rendered = false
							try { rendered = !!container.querySelector('canvas.amap-layer') } catch (e5) {}
							if (!rendered) {
								this.mapReady = false
								this.mapFailed = true
								this.destroyMap()
								try { window.__mapDowngradeReason = 'amap-render-timeout@' + new Date().toISOString() } catch (e6) {}
							}
						}, 8000)
						// DOM 挂载后重算尺寸，setFitView 无参自适应包围全部覆盖物（padding [60,60,60,60]）
						setTimeout(() => {
							if (this._amapMap === map && this.mappableStores.length && typeof map.setFitView === 'function') {
								if (typeof map.resize === 'function') map.resize()
								map.setFitView(null, false, [60, 60, 60, 60])
							}
						}, 120)
					} catch (e) {
						// F-06.7.1 §2 降级判据①：初始化抛异常 → 立即降级
						this.mapReady = false
						this.mapFailed = true
						this.destroyMap()
						try { window.__mapDowngradeReason = 'amap-init-exception@' + new Date().toISOString() } catch (e7) {}
					}
				})
			} finally {
				this._initingMap = false
			}
		},
		// R7 步行距离：AMap.Walking 插件（JS API 通道 + jscode，非 REST）逐店步行路径规划，
		// 取 routes[0].distance（米）写 walkDistMap。口径同 R6-2：用户 WGS84 先 wgs84ToGcj02
		//（userCoordIsGcj=true 时跳过——IP 兜底坐标已是 GCJ-02，同 computeDistances 找茬修复），
		// 门店 s.lng/s.lat 本就是 GCJ-02 真源直用。仅 status==='complete' 且 routes[0].distance
		// 为数值才写入——配额/网络/无路线任何失败不写（列表徽标与气泡回退直线距离文案）；
		// _walkSeq 代际标记丢弃重定位前的过期结果（重定位重算语义）
		// R7-2 强化审查①：本函数由 computeDistances（定位成功主链）调用，最外层 try/catch 兜底
		// AMap.plugin 本身的异常——插件任何失败只静默放弃步行值，绝不阻塞 addUserMarker/toast 主流程
		computeWalkDistances() {
			try {
				// R7-2 强化审查②：重算先清空旧表——重定位后旧位置步行值已失效，新一轮 search
				// 失败（无路线/配额）时回退语义是「本次无值用直线」，禁止残留展示上次定位的旧值；
				// 清空即同步刷新选中气泡，气泡立即回退直线文案防旧步行值残留（等待期徽标短暂
				// 从步行值闪回直线文案属预期诚实行为）
				this.walkDistMap = {}
				this.updateSelectedBubble()
				const AMap = window.AMap
				if (!AMap || this.userLat === null || this.userLng === null || !this.mappableStores.length) return
				const u = this.userCoordIsGcj
					? { lng: this.userLng, lat: this.userLat }
					: wgs84ToGcj02(this.userLng, this.userLat)
				const seq = (this._walkSeq = (this._walkSeq || 0) + 1)
				AMap.plugin('AMap.Walking', () => {
					try {
						// 不传 map 选项：纯算路不渲染规划路线到图面（仅取距离）；实例缓存复用
						if (!this._walking) this._walking = new AMap.Walking({ hideMarkers: true })
						for (const s of this.mappableStores) {
							this._walking.search([u.lng, u.lat], [s.lng, s.lat], (status, result) => {
								// 过期代际（期间已重新定位）结果丢弃，防旧坐标距离覆盖新数据
								if (seq !== this._walkSeq) return
								if (status === 'complete' && result && result.routes && result.routes.length
									&& typeof result.routes[0].distance === 'number') {
									this.walkDistMap = Object.assign({}, this.walkDistMap, {
										[s.id]: result.routes[0].distance
									})
									// R7-2 强化审查④：气泡文案是 renderMarkers 时刻注入的快照，早于本
									// 异步回调——新值到达即刷新选中店气泡，保证其步行距离与最新表一致
									this.updateSelectedBubble()
								}
							})
						}
					} catch (e) {
						// 插件构造/循环异常：静默保留直线距离回退
					}
				})
			} catch (e) {
				// 最外层兜底：plugin 加载同步抛异常等一切来源，均不向上传播阻塞定位主链
			}
		},
		// R7-2 强化审查④配套：按最新 walkDistMap/distMap 刷新当前选中店气泡的 textContent
		//（气泡 DOM 引用由 renderMarkers 维护于 _bubbles）；无选中/无气泡/门店已不可地图时静默
		updateSelectedBubble() {
			if (this.selectedStoreId === null || this.selectedStoreId === undefined) return
			const bubble = (this._bubbles || {})[this.selectedStoreId]
			if (!bubble) return
			const s = this.mappableStores.find(x => x && x.id === this.selectedStoreId)
			if (s) bubble.textContent = this.bubbleText(s)
		},
		// R7 逆地理编码：AMap.Geocoder（jscode 已配）getAddress([用户GCJ.lng, 用户GCJ.lat])
		// → regeocode.formattedAddress 写 locatedAddress（地址条展示）；失败/异常清空回退
		// locatedBarText（坐标/IP 文案）；_geoSeq 代际标记同上防过期结果回写。
		// 轮1 找茬修复：userCoordIsGcj=true（IP 兜底坐标已是 GCJ-02）时跳过 wgs84ToGcj02
		// R7-2 强化审查①：同 computeWalkDistances——最外层 try/catch 兜底 AMap.plugin 同步异常，
		// 插件失败只放弃逆地理（地址条回退坐标/IP 文案），绝不阻塞定位成功主链
		reverseGeocode() {
			try {
				// R7-2 强化审查③：重算先清空旧地址——重定位后旧位置地址失效，新一轮 getAddress
				// 未返回/失败期间地址条短暂回退坐标/IP 文案属预期诚实行为，禁止残留展示旧地址
				this.locatedAddress = ''
				const AMap = window.AMap
				if (!AMap || this.userLat === null || this.userLng === null) return
				const u = this.userCoordIsGcj
					? { lng: this.userLng, lat: this.userLat }
					: wgs84ToGcj02(this.userLng, this.userLat)
				const seq = (this._geoSeq = (this._geoSeq || 0) + 1)
				AMap.plugin('AMap.Geocoder', () => {
					try {
						if (!this._geocoder) this._geocoder = new AMap.Geocoder({})
						this._geocoder.getAddress([u.lng, u.lat], (status, result) => {
							if (seq !== this._geoSeq) return
							this.locatedAddress = (status === 'complete' && result && result.regeocode
								&& result.regeocode.formattedAddress) ? result.regeocode.formattedAddress : ''
						})
					} catch (e) {
						this.locatedAddress = ''
					}
				})
			} catch (e) {
				// 最外层兜底：同 computeWalkDistances，异常不出本函数
				this.locatedAddress = ''
			}
		},
		// 渲染/刷新门店 marker（F-06.7.3 R7 对齐参考稿）：content 重写为 CSS teardrop 红色水滴 pin
		//（.amap-store-pin 34px rotate(-45deg) 尖端朝下 + 内部 .amap-store-pin-icon 反旋转白色店铺图标），
		// 三态语义保留：营业红 pin / 停业灰 pin（.amap-store-pin-closed）/ 选中放大 1.25 倍红 pin
		//（.amap-store-pin-selected + zIndex 130）；anchor 改 'bottom-center'——pin 尖端对准门店坐标；
		// content 内附 .amap-bubble 点击名签气泡（默认隐藏，选中店加 .amap-bubble-visible 显示，
		// 文案 bubbleText 经 textContent 注入防注入）；PC hover 名签 .amap-store-name 保留兼容；
		// 点击联动列表选中逻辑不变
		renderMarkers() {
			const AMap = window.AMap
			const map = this._amapMap
			if (!AMap || !map) return
			// R5-3 强化审查（防泄漏）：stores 重拉后某门店失去坐标/被删时，其旧 marker 不在
			// 本轮循环内不会被 remove → 实例残留泄漏；渲染前先清理「已不在可地图集合」的旧 marker
			const keepIds = {}
			for (const s of this.mappableStores) { keepIds[String(s.id)] = true }
			for (const id of Object.keys(this._markers || {})) {
				if (!keepIds[id]) { map.remove(this._markers[id]); delete this._markers[id] }
			}
			// R7-2 强化审查④：气泡 DOM 引用表随重渲染整体重建（旧引用随旧 content DOM 销毁，
			// 不清理会悬空——updateSelectedBubble 将写到脱离文档的节点上）
			this._bubbles = {}
			for (const s of this.mappableStores) {
				if (this._markers[s.id]) { map.remove(this._markers[s.id]) }
				const selected = this.selectedStoreId === s.id
				const closed = this.isClosed(s)
				// R1-1 深度防御沉淀：门店名经 document.createElement + textContent 注入，
				// 不在 content 里用 innerHTML 字符串拼接门店名（门店名将来经管理通道变更时可被注入脚本），
				// HTMLElement 承载天然转义
				const pinEl = document.createElement('div')
				pinEl.className = 'amap-store-pin'
					+ (selected ? ' amap-store-pin-selected' : '')
					+ (closed ? ' amap-store-pin-closed' : '')
				const iconEl = document.createElement('div')
				iconEl.className = 'amap-store-pin-icon'
				iconEl.textContent = '🏪'
				pinEl.appendChild(iconEl)
				const nameEl = document.createElement('div')
				nameEl.className = 'amap-store-name'
				nameEl.textContent = s.name
				const bubble = document.createElement('div')
				// 模板渲染时按 selectedStoreId 决定：选中店出气泡，重渲染天然隐藏其他店气泡
				bubble.className = selected ? 'amap-bubble amap-bubble-visible' : 'amap-bubble'
				bubble.textContent = this.bubbleText(s)
				this._bubbles[s.id] = bubble
				const content = document.createElement('div')
				content.className = 'amap-store-marker'
				content.appendChild(pinEl)
				content.appendChild(nameEl)
				content.appendChild(bubble)
				// F-06.7.2 R6 坐标真源：m13 后 DB 的 s.lng/s.lat 即高德 GCJ-02 真源（高德 Geocoder/
				// PlaceSearch 双源实测定案），与高德底图同源零偏移，直用不再 wgs84ToGcj02（m12 估算值
				// 时代的转换包装已删，双转反而引入偏移）；仅用户 WGS84 定位坐标需转换
				//（addUserMarker 进图 / computeDistances、computeWalkDistances、reverseGeocode 算距离/取地址）
				// R7：anchor 'bottom-center'——content 根节点 34×41（见 pickup-map.css），底部中心
				// 恰为 pin 旋转后尖端落点，坐标点对准 pin 尖端而非几何中心
				const m = new AMap.Marker({
					position: [s.lng, s.lat],
					content: content,
					anchor: 'bottom-center',
					zIndex: selected ? 130 : 100
				})
				m.on('click', () => this.onMarkerTap(s.id))
				map.add(m)
				this._markers[s.id] = m
			}
		},
		// marker 点击：选中门店（重刷 marker 色）+ 列表滚动到对应行（tab2 已挂载才滚）
		onMarkerTap(storeId) {
			this.selectedStoreId = storeId
			this.renderMarkers()
			this.$nextTick(() => {
				const row = document.getElementById('store-row-' + storeId)
				if (row && row.scrollIntoView) {
					row.scrollIntoView({ behavior: 'smooth', block: 'center' })
				}
			})
		},
		// 地图上补用户位置标记（F-06.6 R2 沉淀 → F-06.7 R4 迁移 AMap.Marker → F-06.7.4 R8 头像化）：
		// content = .user-avatar-marker（44px 圆头像，3px 白边+阴影）内 img.src=登录头像||DEFAULT_AVATAR，
		// 蓝色脉冲外圈（与红色门店 pin 强区分，一眼定位自己）；anchor 'center'，zIndex 200 高于门店 pin
		//（130）保证用户始终在最上层；移除「我所在的位置」文字标签（头像+蓝光圈即语义，对齐主流 App）；
		// 重复定位先移除旧 marker
		addUserMarker() {
			const AMap = window.AMap
			const map = this._amapMap
			if (!AMap || !map || !this.mapReady || this.userLat === null || this.userLng === null) {
				// 轮1 找茬修复（_pendingUserMarker 语义）：定位成功早于地图就绪（首次进页 AMap
				// 加载数秒）时早退不再静默丢光标——坐标已有而地图未就绪，置待补标记，
				// tryInitMap 置 mapReady=true 后检查补调；从未定位（坐标 null）不置标记
				if (this.userLat !== null && this.userLng !== null) this._pendingUserMarker = true
				return
			}
			if (this._userMarker) { map.remove(this._userMarker); this._userMarker = null }
			const box = document.createElement('div')
			box.className = 'user-avatar-marker'
			const img = document.createElement('img')
			img.className = 'user-avatar-img'
			// 登录用户已设置头像优先；未登录/未设置兜默认品牌 SVG（utils/avatar.js）
			img.src = (this.hasToken && this.user && this.user.avatar) || DEFAULT_AVATAR
			img.alt = '我的位置'
			box.appendChild(img)
			const wrap = document.createElement('div')
			wrap.className = 'user-loc-marker'
			wrap.appendChild(box)
			// 不对称转换口径（F-06.7.2 R6）：浏览器定位是 WGS84，与高德底图（GCJ-02）不同源，
			// 必须 wgs84ToGcj02 转换后进图——本转换保留不动；门店坐标则是 DB 存的 GCJ-02 真源
			// 直用不转（见 renderMarkers），两侧口径不对称是有意为之。
			// 轮1 找茬修复：userCoordIsGcj=true（高德 IP 兜底坐标本就是 GCJ-02）时跳过转换，
			// 防双重转换把光标打偏
			const gcj = this.userCoordIsGcj
				? { lng: this.userLng, lat: this.userLat }
				: wgs84ToGcj02(this.userLng, this.userLat)
			this._userMarker = new AMap.Marker({
				position: [gcj.lng, gcj.lat],
				content: wrap,
				anchor: 'center',
				zIndex: 200
			})
			map.add(this._userMarker)
			// 光标已落图：清除待补标记（定位成功早于地图就绪的场景在此闭环）
			this._pendingUserMarker = false
			// R8 突出定位：视野自适应把用户与全部门店一起纳入（无参覆盖全部覆盖物带 padding），
			// 避免 fitView 初次视野不含用户点、光标在视野外
			try { map.setFitView(null, false, [80, 80, 80, 80]) } catch (e) {}
		},
		// 销毁地图实例（onUnload / 降级切换）：防实例与监听泄漏
		destroyMap() {
			if (this._amapMap) {
				try { this._amapMap.destroy() } catch (e) {}
				this._amapMap = null
			}
			this._markers = {}
			this._bubbles = {}
			this._userMarker = null
			this._pendingUserMarker = false
			// U3：视野记忆标记复位——重新初始化后回显恢复默认自适应行为
			this._userAdjustedView = false
			this.mapReady = false
		},
		// #endif
		// ==================== H5 页面滚轮兜底 ====================
	// #ifdef H5
	// 页面级滚轮兜底：手动驱动 body 滚动（照搜索页同款方案）。
	// R9 分离守卫：事件目标在高德地图容器（#pickup-map）内时直接放行——不 preventDefault、不滚
	// body，交 AMap 自身处理缩放（此前无差别 preventDefault 导致「地图缩放+页面滚动」同时动，
	// 用户反馈：鼠标在地图区域滚动就只缩放大小）；文本节点无 closest，向上借父元素判一次
	onPageWheel(e) {
		const t = e.target
		if (t && t.nodeType === 1 && typeof t.closest === 'function' && t.closest('#pickup-map')) {
			return
		}
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
	/* R7 定位地址条样式已迁至 front/styles/pickup-map.css（与 marker 运行时样式同文件统一管理） */
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

/* ============ F-06 地图卡 ============ */
/* 白卡包裹高德地图容器：560rpx 高，圆角与页内卡片一致；地图实例自管理内部渲染层（canvas.amap-layer） */
.map-card {
	margin: 20rpx 24rpx 0;
	background-color: #ffffff;
	border-radius: 12rpx;
	box-shadow: 0 2rpx 12rpx rgba(0, 0, 0, 0.06);
	overflow: hidden;
}
.map-inner {
	width: 100%;
	height: 560rpx;
	background-color: #eaeaea;
	z-index: 1;
}
/* 图例条：三种 marker 颜色语义 */
/* U7（轮2 找茬）：图例内容 594rpx > 可用 542rpx——flex-wrap 允许整体换行不压缩，
   row-gap 只管换行后的行距（8rpx，列距维持 gap 的 28rpx） */
.map-legend {
	display: flex;
	flex-direction: row;
	align-items: center;
	gap: 28rpx;
	row-gap: 8rpx;
	flex-wrap: wrap;
	padding: 14rpx 24rpx;
	background-color: #fafafa;
	border-top: 1rpx solid #f0f0f0;
}
.legend-item {
	display: flex;
	flex-direction: row;
	align-items: center;
	/* U7：图例项禁止压缩——换行由容器 flex-wrap 承担，项内内容保持原始宽 */
	flex-shrink: 0;
}
.legend-dot {
	width: 14rpx;
	height: 14rpx;
	border-radius: 50%;
	margin-right: 8rpx;
}
.legend-text {
	font-size: 22rpx;
	color: #666666;
}
/* F-06.7 R4：中文地图来源尾注（来源 © 高德地图，图面右下另有高德自带 logo+版权条）；
   字号复用 .legend-text 的 22rpx，颜色降为 #999 弱化；margin-left: auto 推到图例条尾部与图例项分离 */
.legend-source {
	margin-left: auto;
	font-size: 22rpx;
	color: #999999;
	/* U7：尾注不内部折行，整项参与换行布局（放不下时整体落到下一行行尾） */
	white-space: nowrap;
}

/* 距离徽标：浅红底红字，跟随门店名同行右侧 */
.store-name-row {
	display: flex;
	flex-direction: row;
	align-items: center;
	width: 100%;
	margin-bottom: 8rpx;
}
.store-name-row .store-name {
	margin-bottom: 0;
}
.dist-badge {
	flex-shrink: 0;
	margin-left: 12rpx;
	font-size: 22rpx;
	font-weight: 600;
	color: #e02020;
	background-color: #fdecec;
	border-radius: 8rpx;
	padding: 4rpx 12rpx;
}
</style>

<!-- 用户位置光标与门店 marker 样式已迁出至 front/styles/pickup-map.css（R6-1）：
	本 uniapp 编译器对页面级 SFC 的全部 <style> 块（含非 scoped）统一注入 scoped= 参数，
	产物选择器带 [data-v-xxx]，AMap.Marker content 运行时 DOM 永不命中——
	纯 CSS 文件经 JS import 引入不走 SFC 编译，天然免疫 scope 化（App.vue 对照实证） -->

