// 运营端入口（F-20.11 Step 4）
// Element Plus 全量注册（D-1 的 spike 实测口径：main.js 里真 .use(ElementPlus) 才能验出产物体积，
// 本工程页面直接用 <el-*> 组件，不引额外图标库）。
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(ElementPlus, { locale: zhCn })
app.use(router)
app.mount('#app')
