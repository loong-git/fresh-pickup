import { createSSRApp } from 'vue'
import { createStore } from 'vuex'
import App from './App.vue'
// storage 读写与 401 回调注册器都取自 api 层：键名 partner_token 的唯一实现处在那边，
// 此处只调用不重复实现（用户端 / 商家端 main.js 同款分层——api 层从不反向 import store，避免循环引用）
import {
	setUnauthorizedHandler,
	getStoredToken,
	setStoredToken,
	clearStoredToken,
	getStoredLoginPhone,
	setStoredLoginPhone,
	clearStoredLoginPhone
} from './api/index.js'

// ==================== Vuex store（主理人端极简态） ====================
// 只放登录态两样：token + 登录身份手机号。
// 与商家端 main.js 的差异（F-18.15 C-7）：不带 `merchant` 出参快照位。
// 理由不是「省一行」，而是主理人端没有对等的 me 端点可灌它——身份事实是 GET /api/partner/stores 的
// 门店数组 + GET /api/partner/store 的明细，二者都由工作台页自持一份渲染态（照 workspace.vue 的
// 「本页自持一份，渲染不依赖 store」纪律）。留着这个字段就是一个永不更新的假快照，比没有更危险。
// C 端 main.js 的购物车 / 自提点 / 字典 / 邀请 / syncPickupFromServer 等启动链一律不引进来
// （主理人端无「默认自提点」概念，C-7 明令）。
const store = createStore({
	state: {
		// 登录态：刷新后从独立 storage 键 partner_token 恢复（键名与 C 端 'user'、商家端那把登录键都不同，
		// 三端同域调试互不串态——这是 C-4 改名的唯一动机）
		token: getStoredToken(),
		// 登录身份手机号：403 引导文案的指代来源，与 token 同写同清（老状态无此键时为空串，文案自适配）
		loginPhone: getStoredLoginPhone()
	},
	mutations: {
		// 写入 token：登录成功后先落这里，再做身份判定（请求层从 storage 读 token 注入 Bearer 头）
		setToken(state, token) {
			state.token = token || ''
			setStoredToken(state.token)
		},
		// 写入登录身份：与 setToken 同点提交（login.vue afterToken，登录成功那一刻）
		setLoginPhone(state, phone) {
			state.loginPhone = phone || ''
			setStoredLoginPhone(state.loginPhone)
		},
		// 清登录态：401（请求层回调）与「换个账号」共用；登录身份一并清掉，
		// 防新账号的 403 文案指向上一账号的手机号
		clearToken(state) {
			state.token = ''
			state.loginPhone = ''
			clearStoredToken()
			clearStoredLoginPhone()
		}
	}
})

// 401 统一处理：请求层检测到 401 时回调清 Vuex 登录态
// （storage 里的 partner_token 由请求层自己清；回登录页也在请求层完成——主理人端同样无游客态）
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
