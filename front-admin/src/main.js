// 运营端入口（F-20.11 Step 4）
// Element Plus 全量注册（D-1 的 spike 实测口径：main.js 里真 .use(ElementPlus) 才能验出产物体积，
// 本工程页面直接用 <el-*> 组件，不引额外图标库）。
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// P8-1 暗色基座：EP 官方暗色 vars（html.dark class 生效；品牌蓝覆盖在 App.vue 的 html.dark 块里
// 后置压过这里的默认值）。八期起运营端恒为暗色，不做切换开关（八期 §1-5）。
import 'element-plus/theme-chalk/dark/css-vars.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'

// 挂载即挂 dark class（纯 UI 状态，八期 §1-2 放宽条款；无任何业务含义）
document.documentElement.classList.add('dark')

const app = createApp(App)
app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
