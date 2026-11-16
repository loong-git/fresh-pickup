// 运营端入口（F-20.11 Step 4）
// Element Plus 全量注册（D-1 的 spike 实测口径：main.js 里真 .use(ElementPlus) 才能验出产物体积，
// 本工程页面直接用 <el-*> 组件，不引额外图标库）。
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// P9-fix 双主题基座：浅色（默认档）= EP dist/index.css 官方亮色真源；深色 = dark/css-vars.css
// （html.dark class 生效）。品牌色与 --admin-* 壳层族的双组覆盖在 App.vue 的 :root / html.dark
// 里后置压过这里的默认值；深色组 = 八期暗色控制台视觉逐字保留（P9-fix 契约 §2.1）。
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'

// —— P9-fix 双主题（纯 UI 状态，照 P8-1 八期 §1-2 放宽条款先例显式披露）：挂载前读档挂 class ——
// 模块执行期与上面静态 import 同束，首帧前 class 已就位，无 FOUC。档位只认 'dark'，非 'dark'
// 一律回落 'light'（默认浅色档）；深色 = 八期暗色视觉逐字保留。
// 切换唯一入口 window.__setAdminTheme（App.vue topbar 切换钮调用）：写档 + toggle class 一步完成，
// 返回最终档位供屏上读数同源。
const ADMIN_THEME_KEY = 'admin_theme'
// P3-1（同 api 层 getStoredToken/setStoredToken 的守卫纪律，防禁存储环境白屏）：隐私模式/存储配额异常下
// 裸读写会在本模块执行期直接抛异常——main.js 是入口，模块期抛异常 = 整站白屏（api 层同纪律已守卫，壳层此前漏网）。
// 读失败回落 'light'（默认浅色档，语义与「没存过」一致）；写失败静默（只丢主题记忆，不影响本会话的 class 切换）。
let initialTheme = 'light'
try {
  initialTheme = localStorage.getItem(ADMIN_THEME_KEY) === 'dark' ? 'dark' : 'light'
} catch (e) {
  // 禁存储环境：按默认浅色档渲染，页面照常可用
}
document.documentElement.classList.toggle('dark', initialTheme === 'dark')
window.__setAdminTheme = (theme) => {
  const next = theme === 'dark' ? 'dark' : 'light'
  try {
    localStorage.setItem(ADMIN_THEME_KEY, next)
  } catch (e) {
    // 落盘失败只影响「刷新后是否记住主题」，本会话的切换照常生效
  }
  document.documentElement.classList.toggle('dark', next === 'dark')
  return next
}

const app = createApp(App)
app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
