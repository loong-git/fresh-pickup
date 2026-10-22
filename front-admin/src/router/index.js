// 路由（F-20.11 Step 4 的三页 + F-20.22 Step 5b-2 的四页 = /login 与六个业务页；F-63 又加 arrivals 一页，
// F-106 片 C 再加 merchant-applies 一页，现共 1 + 8）+ 一条全局前置守卫。
// 依赖方向是单向的：router → api，api 绝不 import router（否则成 api → router → views → api 的静态循环，
// 与本仓三端 main.js ↔ api 的做法同构）——api 层 401 要跳 /login，靠的是本模块末尾注册的回调。
// 三页一律动态 import（懒加载），首屏包只装壳——F-97 实测：现在 9 个路由组件**全部**是动态 import，不止那三页。
// history 模式用 createWebHistory：dev 由 Vite 的 SPA fallback 兜住根路径刷新。
import { createRouter, createWebHistory } from 'vue-router'
import { getStoredToken, setUnauthorizedHandler } from '../api'

const routes = [
  // 根路径不做「无条件跳大盘」：登录态是否存在由守卫判（无 token 时下面这条会立刻被守卫改道 /login）
  { path: '/', redirect: '/overview' },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/Login.vue'),
    meta: { requiresAuth: false }
  },
  {
    path: '/overview',
    name: 'overview',
    component: () => import('../views/Overview.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/audit-logs',
    name: 'audit-logs',
    component: () => import('../views/AuditLogs.vue'),
    meta: { requiresAuth: true }
  },
  // F-20.22 Step 5b-2：四个运营 P0 页（每页 = 一条 5b-1 的 v2 只读列表 + 一个 AdminController 旧写端点）。
  // 与既有两页同一套 requiresAuth: true —— 守卫读的是同一个 getStoredToken()，四页新页不必各写一条守卫。
  // 四页一律动态 import：订单管理/商品管理/评价审核/入驻审核各自的表与写侧文案不进首屏包。
  {
    path: '/orders',
    name: 'orders',
    component: () => import('../views/Orders.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/dishes',
    name: 'dishes',
    component: () => import('../views/Dishes.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/reviews',
    name: 'reviews',
    component: () => import('../views/Reviews.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/store-applies',
    name: 'store-applies',
    component: () => import('../views/StoreApplies.vue'),
    meta: { requiresAuth: true }
  },
  // F-106 片 C 商家入驻审核：商家自助入驻申请（merchant_apply）的审核队列，挨着门店申请审核同一类页面。
  // 与上面几页同一套 requiresAuth 守卫，动态 import 不进首屏包。
  {
    path: '/merchant-applies',
    name: 'merchant-applies',
    component: () => import('../views/MerchantApplies.vue'),
    meta: { requiresAuth: true }
  },
  // F-63 到货发车：运营端批次创建方（preview 只读 + depart 写）。同一套 requiresAuth 守卫，动态 import 不进首屏包。
  {
    path: '/arrivals',
    name: 'arrivals',
    component: () => import('../views/Arrivals.vue'),
    meta: { requiresAuth: true }
  },
  // 未知路径统一改道大盘：运营端没有为第四种落点写死文案，回到已知态更实在
  { path: '/:pathMatch(.*)*', redirect: '/overview' }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局前置守卫（F-20.11 三页共同要求）：无 admin_token 且目标不是 /login → 跳登录。
// 这一条是「页面还没发请求就知道没登录」的快路径；请求层的 401（token 过期/被吊销）
// 是第二条兜底，两处判定读的是同一个 getStoredToken()（键名真源见 api/index.js 的 TOKEN_STORAGE_KEY）。
router.beforeEach((to) => {
  const hasToken = !!getStoredToken()
  if (!hasToken && to.path !== '/login') {
    return { path: '/login' }
  }
  // 已登录还去登录页没有意义（登录成功本身由 Login.vue 主动 replace 到 /overview）
  if (hasToken && to.path === '/login') {
    return { path: '/overview' }
  }
  return true
})

export default router

// 401 → 回登录页（api 层清完 token 后调用，见 api/index.js 的 goToLogin）。
// 注册发生在本模块求值时，而任何请求都要等 main.js 的 app.mount 之后才发得出去，
// 所以不存在「401 先于注册到达」的窗口。
// 用 replace 而非 push：登录失效前的那一页不该留在历史里，否则浏览器后退又会撞回 401。
setUnauthorizedHandler(() => {
  if (router.currentRoute.value.path !== '/login') {
    router.replace('/login')
  }
})
