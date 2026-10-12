// 路由（F-20.11 Step 4）：/login、/overview、/audit-logs 三页 + 一条全局前置守卫。
// 依赖方向是单向的：router → api，api 绝不 import router（否则成 api → router → views → api 的静态循环，
// 与本仓三端 main.js ↔ api 的做法同构）——api 层 401 要跳 /login，靠的是本模块末尾注册的回调。
// 三页一律动态 import（懒加载），首屏包只装壳。
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
  // 未知路径统一改道大盘：运营端只有两页，写死第四种落点不如回到已知态
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
