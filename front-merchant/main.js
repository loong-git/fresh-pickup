import { createSSRApp } from 'vue'
import { createStore } from 'vuex'
import App from './App.vue'
// storage 读写与 401 回调注册器都取自 api 层：键名 merchant_token 的唯一实现处在那边，
// 此处只调用不重复实现（用户端 main.js 同款分层——api 层从不反向 import store，避免循环引用）
import {
	setUnauthorizedHandler,
	getStoredToken,
	setStoredToken,
	clearStoredToken
} from './api/index.js'

// ==================== Vuex store（商家端极简态） ====================
// 只放登录态两样：token + me 出参快照。
// 用户端 main.js 的购物车/自提点/字典/邀请/跨标签页同步等 18KB C 端逻辑与本端无关，一律不带。
const store = createStore({
	state: {
		// 登录态：刷新后从独立 storage 键 merchant_token 恢复（键名与 C 端 'user' 不同，两端同域调试不串态）
		token: getStoredToken(),
		// GET /api/merchant/me 出参快照：{role,merchantId,name,contactPhone,contactName,status,profile}
		// null = 本会话尚未成功拉到（登录页/工作台都会以 onShow 重拉覆盖，不做乐观填充）
		merchant: null
	},
	mutations: {
		// 写入 token：登录成功后先落这里，再调 getMerchantMe（请求层从 storage 读 token 注入 Bearer 头）
		setToken(state, token) {
			state.token = token || ''
			setStoredToken(state.token)
		},
		// 清登录态：401（请求层回调）与「换个账号」共用；me 快照一并清掉，防上一个账号的商家名残留渲染
		clearToken(state) {
			state.token = ''
			state.merchant = null
			clearStoredToken()
		},
		// 覆盖 me 快照（onShow 每次重拉都整体替换，不做字段级 merge）
		setMerchant(state, merchant) {
			state.merchant = (merchant && typeof merchant === 'object') ? merchant : null
		}
	}
})

// 401 统一处理：请求层检测到 401 时回调清 Vuex 登录态
// （storage 里的 merchant_token 由请求层自己清；回登录页也在请求层完成——商家端无游客态）
setUnauthorizedHandler(() => {
	store.commit('clearToken')
})

export function createApp() {
	const app = createSSRApp(App)
	app.use(store)
	return {
		app,
		store
	}
}
