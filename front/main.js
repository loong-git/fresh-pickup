import { createSSRApp } from 'vue'
import { createStore } from 'vuex'
import App from './App.vue'
import { setUnauthorizedHandler, getStores } from './api/index.js'

// ===== 存储安全兜底（M3-T-06）=====
// uni.setStorageSync 在隐私模式/存储超额/序列化失败等场景会抛异常导致页面中断，
// 全部存储写入统一收口到 safeSetStorage：失败时静默降级写入内存 Map，本次会话内功能不中断
const memoryStorage = new Map()

// 安全写：storage 写入失败（隐私模式/超额）时降级到内存 Map，静默不打断业务
export function safeSetStorage(key, value) {
	try {
		uni.setStorageSync(key, value)
	} catch (e) {
		memoryStorage.set(key, value)
	}
}

// 安全读：优先读本地存储；storage 不可用/键值为空且内存里有降级值时返回内存值，否则 undefined
export function safeGetStorage(key) {
	try {
		const v = uni.getStorageSync(key)
		// H5/小程序对不存在的 key 通常返回空串：若内存中有降级值则优先返回内存值
		if ((v === '' || v === null || v === undefined) && memoryStorage.has(key)) {
			return memoryStorage.get(key)
		}
		return v
	} catch (e) {
		return memoryStorage.has(key) ? memoryStorage.get(key) : undefined
	}
}

// ===== 金额格式化（M3-T-07）=====
// 全站统一两位小数：null/undefined/非数字等脏数据一律按 0 兜底，页面展示不崩溃；
// 同时注册为全局属性 $fmtMoney（见 createApp），模板里可直接 {{ $fmtMoney(v) }}
export function formatMoney(v) {
	const n = Number(v)
	return (Number.isFinite(n) ? n : 0).toFixed(2)
}

// 自提点默认信息（社区团购自提模式）
const DEFAULT_PICKUP_POINT = {
	name: '高黎柱发士多店(请自备袋子谢谢)',
	service: '支持冷冻/冷藏',
	timeText: '明天16:00自提',
	address: '广东省佛山市顺德区容桂高黎环涌西路桥灵坊23号'
}

// 自提人默认信息（M3-T-10 演示数据清除：旧默认演示数据，改虚拟数据）
const DEFAULT_PICKUP_PERSON = {
	name: '张三',
	phone: '13800138000'
}

// 购物车项兜底 normalize：兼容旧版购物车数据（无 checked/originalPrice/unitSave 等字段），
// 补齐契约要求的新结构字段，保证页面渲染与金额计算不报错
function normalizeCartItem(item) {
	if (!item || typeof item !== 'object') return null
	const price = Number(item.price) || 0
	const quantity = Number(item.quantity) > 0 ? Math.floor(Number(item.quantity)) : 1
	const stockNum = Number(item.stock)
	return {
		id: item.id,
		name: item.name,
		price,
		unit: item.unit || '份',
		emoji: item.emoji || '🛒',
		stock: Number.isFinite(stockNum) ? stockNum : 99,
		quantity,
		// 旧数据无 checked 字段时默认勾选
		checked: item.checked !== false,
		// 旧数据无原价字段时按现价 1.5 倍兜底
		originalPrice: typeof item.originalPrice === 'number' && isFinite(item.originalPrice)
			? item.originalPrice
			: +(price * 1.5).toFixed(2),
		// 旧数据无单件节省字段时按 1 元兜底
		unitSave: typeof item.unitSave === 'number' && isFinite(item.unitSave) ? item.unitSave : 1,
		// 秒杀标记：旧数据无此字段时按节省额是否大于 1 元推断
		seckill: item.seckill === true || (item.seckill === undefined && item.unitSave > 1)
	}
}

// 登录态默认信息（M2-T-04：token 为空即游客，浏览/加购/切换自提点均免登录）
const DEFAULT_USER = { token: '', id: null, phone: '' }

// 从本地存储恢复登录态，存储值可能是字符串或对象，异常/结构非法时回退空（游客模式）
function loadUserFromStorage() {
	try {
		const cached = safeGetStorage('user')
		const value = typeof cached === 'string' ? JSON.parse(cached) : cached
		if (value && typeof value === 'object' && typeof value.token === 'string') {
			return { ...DEFAULT_USER, ...value }
		}
		return { ...DEFAULT_USER }
	} catch (e) {
		return { ...DEFAULT_USER }
	}
}

// 从本地存储恢复购物车，存储值可能是字符串或数组，异常/空时回退为空数组
function loadCartFromStorage() {
	try {
		const cached = safeGetStorage('cart')
		const list = typeof cached === 'string' ? JSON.parse(cached) : cached
		// 逐项 normalize 兜底，过滤无效项
		return Array.isArray(list) ? list.map(normalizeCartItem).filter(Boolean) : []
	} catch (e) {
		return []
	}
}

// 从本地存储恢复对象信息（自提点/自提人），存储值可能是字符串或对象，
// 缺失字段用默认值补齐，异常/空时回退默认值
function loadPickupFromStorage(key, defaultValue) {
	try {
		const cached = safeGetStorage(key)
		const value = typeof cached === 'string' ? JSON.parse(cached) : cached
		if (value && typeof value === 'object') {
			const merged = { ...defaultValue, ...value }
			// M3-T-10 演示数据清除：历史版本曾内置真实个人信息（loong/13800138000）作默认自提人，
			// 本地残留的旧演示数据直接覆盖为虚拟默认值（张三/13800138000），真实信息不再入库/展示
			if (key === 'pickupPerson' && merged.name === 'loong' && merged.phone === '13800138000') {
				return { ...defaultValue }
			}
			return merged
		}
		return { ...defaultValue }
	} catch (e) {
		return { ...defaultValue }
	}
}

// 创建 Vuex store
const store = createStore({
	state: {
		// 购物车列表（刷新后从本地存储恢复，旧数据读取时 normalize 兜底）
		cart: loadCartFromStorage(),
		// 当前分类: 'meat' | 'vegetable'
		currentCategory: 'meat',
		// 配送地址
		address: '',
		// 手机号
		phone: '',
		// 自提点信息（初始化时从本地存储恢复，异常回退默认值）
		pickupPoint: loadPickupFromStorage('pickupPoint', DEFAULT_PICKUP_POINT),
		// 自提人信息（初始化时从本地存储恢复，异常回退默认值）
		pickupPerson: loadPickupFromStorage('pickupPerson', DEFAULT_PICKUP_PERSON),
		// 登录态（M2-T-04：初始化时从本地存储恢复，异常回退游客空态）
		user: loadUserFromStorage()
	},
	mutations: {
		// 添加到购物车
		addToCart(state, dish) {
			const existItem = state.cart.find(item => item.id === dish.id)
			if (existItem) {
				// 已有同 id 商品只加数量
				existItem.quantity++
			} else {
				// 按契约 normalize：存在秒杀价时以秒杀价入库并记录直降信息，否则按现价 1.5 倍记原价
				const hasSeckill = dish.seckillPrice != null
				state.cart.push({
					id: dish.id,
					name: dish.name,
					price: hasSeckill ? dish.seckillPrice : dish.price,
					unit: dish.unit,
					emoji: dish.emoji,
					stock: dish.stock,
					quantity: 1,
					// 默认勾选
					checked: true,
					originalPrice: hasSeckill ? dish.price : +(dish.price * 1.5).toFixed(2),
					unitSave: hasSeckill ? +(dish.price - dish.seckillPrice).toFixed(2) : 1,
					// 秒杀标记：购物车折扣标签等 UI 依赖它区分秒杀件
					seckill: hasSeckill
				})
			}
			// 同步到本地存储，刷新后购物车不丢失
			safeSetStorage('cart', state.cart)
			uni.showToast({
				title: '已加入购物车',
				icon: 'success',
				duration: 1500
			})
		},
		// 从购物车移除
		removeFromCart(state, dishId) {
			const index = state.cart.findIndex(item => item.id === dishId)
			if (index > -1) {
				state.cart.splice(index, 1)
			}
			// 同步到本地存储，刷新后购物车不丢失
			safeSetStorage('cart', state.cart)
		},
		// 修改商品数量
		updateQuantity(state, { dishId, quantity }) {
			const item = state.cart.find(item => item.id === dishId)
			if (item) {
				if (quantity <= 0) {
					state.cart = state.cart.filter(i => i.id !== dishId)
				} else {
					item.quantity = quantity
				}
			}
			// 同步到本地存储，刷新后购物车不丢失
			safeSetStorage('cart', state.cart)
		},
		// 单个商品勾选切换
		toggleChecked(state, dishId) {
			const item = state.cart.find(item => item.id === dishId)
			if (item) {
				item.checked = !item.checked
			}
			// 同步到本地存储，刷新后勾选状态不丢失
			safeSetStorage('cart', state.cart)
		},
		// 全选/反选：已全部勾选时全部取消，否则全部勾选
		toggleAllChecked(state) {
			const allChecked = state.cart.length > 0 && state.cart.every(item => item.checked)
			state.cart.forEach(item => {
				item.checked = !allChecked
			})
			// 同步到本地存储，刷新后勾选状态不丢失
			safeSetStorage('cart', state.cart)
		},
		// 清空购物车
		clearCart(state) {
			state.cart = []
			// 同步到本地存储，刷新后购物车不丢失
			safeSetStorage('cart', state.cart)
		},
		// 整体替换购物车（仅跨标签页 storage 同步链路使用）：入参为已 normalize 的列表；
		// 不回写存储——数据本就来自其他标签页落盘的 localStorage，回写会让两页互相触发 storage 事件形成同步环
		replaceCart(state, list) {
			state.cart = Array.isArray(list) ? list : []
		},
		// 切换分类
		setCategory(state, category) {
			state.currentCategory = category
		},
		// 设置配送信息
		setDeliveryInfo(state, { address, phone }) {
			state.address = address
			state.phone = phone
		},
		// 设置自提点信息（支持局部更新）并同步本地存储
		setPickupPoint(state, point) {
			state.pickupPoint = { ...state.pickupPoint, ...(point || {}) }
			safeSetStorage('pickupPoint', state.pickupPoint)
		},
		// 设置自提人信息（支持局部更新）并同步本地存储
		setPickupPerson(state, person) {
			state.pickupPerson = { ...state.pickupPerson, ...(person || {}) }
			safeSetStorage('pickupPerson', state.pickupPerson)
		},
		// 设置登录态（M2-T-04：登录成功写入 {token,id,phone}）并同步本地存储
		setUser(state, user) {
			state.user = { ...DEFAULT_USER, ...(user || {}) }
			// M3-T-06：走 safeSetStorage 收口，存储失败（隐私模式/超额）静默降级内存，本次会话登录态仍有效
			safeSetStorage('user', state.user)
		},
		// 清空登录态（M2-T-04：401 无/伪造/过期 token 时触发）并同步删除本地存储
		setClearUser(state) {
			state.user = { ...DEFAULT_USER }
			try {
				uni.removeStorageSync('user')
			} catch (e) {
				// 删除失败静默
			}
			// 同步清理 safeSetStorage 的内存兜底 Map 中的 user 键：隐私模式/超额写失败的设备上
			// 登录态降级存于内存 Map，退出/401 清态后刷新会从内存兜底复活登录态，必须一并删除
			// （退出登录与 401 自动清态链路同样受益；Map.delete 对不存在的键安全无副作用）
			memoryStorage.delete('user')
		}
	},
	getters: {
		// 购物车总数量
		cartCount(state) {
			return state.cart.reduce((total, item) => total + item.quantity, 0)
		},
		// 购物车总价（全部商品现价合计）
		cartTotalPrice(state) {
			return state.cart.reduce((total, item) => total + item.price * item.quantity, 0)
		},
		// 购物车是否为空
		isCartEmpty(state) {
			return state.cart.length === 0
		},
		// 勾选商品合计可省金额（Σ unitSave*quantity，保留两位小数）
		cartSaving(state) {
			return +(state.cart.reduce((total, item) => total + (item.unitSave || 0) * item.quantity, 0)).toFixed(2)
		},
		// 勾选商品件数（Σ quantity of checked）
		checkedCount(state) {
			return state.cart
				.filter(item => item.checked)
				.reduce((total, item) => total + item.quantity, 0)
		},
		// 勾选商品现价合计（保留两位小数）
		checkedTotalPrice(state) {
			return +(state.cart
				.filter(item => item.checked)
				.reduce((total, item) => total + item.price * item.quantity, 0)).toFixed(2)
		}
	}
})

// 401 统一处理（M2-T-04）：request 层检测到 401 时回调，同步清空 Vuex 登录态
// （本地存储 'user' 由 request 层清理；此处注册避免 api 层反向依赖 store 造成循环引用）
setUnauthorizedHandler(() => {
	store.commit('setClearUser')
})

// ==================== 跨标签页购物车同步（H5） ====================
// H5 多标签页同开时各自持有独立 Vuex 实例，cart mutation 只更新本实例并落盘 localStorage，
// 其他标签页无感知，表现为「免费页与首页购物车弹层内容不一致」（各页启动时恢复的快照不同）。
// storage 事件恰好在「其他同源标签页修改 localStorage」时触发（本页写入不触发本页监听），
// 借此把最新 cart 快照 normalize 后整体换入本实例；replaceCart 不回写存储，两页不会互触成环。
// 小程序/App 端无 window（也无多标签页概念），typeof 守卫天然跳过，行为与现状一致
if (typeof window !== 'undefined' && typeof window.addEventListener === 'function') {
	window.addEventListener('storage', (e) => {
		if (!e || e.key !== 'cart' || e.newValue == null) return
		try {
			let parsed = typeof e.newValue === 'string' ? JSON.parse(e.newValue) : e.newValue
			// uniapp H5 setStorageSync 对非字符串值落盘为 {"type":"object","data":...} 包装串（对照上方
			// loadCartFromStorage：getStorageSync 读取时框架自动解包，而 storage 事件拿到的是落盘原始串），
			// 只按裸数组解析会让包装格式静默失效：包装格式解包取 data，裸数组直接用，两者皆非（脏数据）忽略
			if (parsed && typeof parsed === 'object' && !Array.isArray(parsed) && Array.isArray(parsed.data)) {
				parsed = parsed.data
			}
			if (!Array.isArray(parsed)) return
			const next = parsed.map(normalizeCartItem).filter(Boolean)
			// 内容与当前一致时跳过（双页同值写入场景），避免无意义重渲染
			if (JSON.stringify(next) === JSON.stringify(store.state.cart)) return
			store.commit('replaceCart', next)
		} catch (err) {
			// 脏数据/解析失败静默：维持本实例现状，等待下一次同步
		}
	})
}

// ==================== 自提点接口优先（M4-T-01） ====================
// DEFAULT_PICKUP_POINT 降级为兜底：启动时拉 GET /api/stores（匿名），
// - 本地 pickupPoint 无 storeId（默认值/旧数据）：名称与接口门店一致则补 storeId（不强改用户已选门店），
//   否则采纳第一家营业门店（含 storeId/status 快照）
// - 已有 storeId：以服务端数据刷新 name/address/service/status 快照（门店改名/停业及时同步）
// 接口失败/空列表静默保留本地兜底，不阻塞启动
function syncPickupFromServer() {
	getStores()
		.then((res) => {
			const list = Array.isArray(res && res.data) ? res.data : []
			if (!list.length) return
			const current = store.state.pickupPoint || {}
			const matched = current.storeId != null
				? list.find((s) => String(s.id) === String(current.storeId))
				: null
			if (matched) {
				store.commit('setPickupPoint', {
					storeId: matched.id,
					name: matched.name,
					address: matched.address,
					service: matched.service || '',
					status: matched.status || 'open'
				})
				return
			}
			// 旧数据无 storeId 但名称恰与接口门店一致：仅补 storeId 等真实字段
			const sameName = !current.storeId && current.name && list.find((s) => s.name === current.name)
			// 否则采纳第一家营业门店（全停业时保持本地兜底不动）
			const firstOpen = list.find((s) => (s.status || 'open') === 'open')
			const target = sameName || firstOpen
			if (!target) return
			store.commit('setPickupPoint', {
				storeId: target.id,
				name: target.name,
				address: target.address,
				service: target.service || '',
				status: target.status || 'open'
			})
		})
		.catch(() => {
			// 接口失败（后端未上线/网络异常）：保持本地兜底，静默不打扰
		})
}

export function createApp() {
	const app = createSSRApp(App)
	app.use(store)
	// 全局金额格式化（M3-T-07）：各页模板可直接 {{ $fmtMoney(v) }}，两位小数 + 脏数据按 0 兜底
	app.config.globalProperties.$fmtMoney = formatMoney
	// M4-T-01：自提点接口优先（异步同步，失败不影响启动）
	syncPickupFromServer()
	return {
		app,
		store
	}
}
