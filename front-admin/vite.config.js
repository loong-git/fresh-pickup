// Vite 配置（F-20.11 / F-20.2 D-1）
// 端口钉死 5177 + strictPort：5173-5176 已被三端（C 端 / 商家端 / 主理人端 + 顺延位）占用，
// 同跑时谁先起谁占前面的口，本端必须钉死 5177 才不会顺延到未登记的 origin 上被 CORS 拦成「网络异常」。
// 5177 已登记进 backEnd/src/main/java/com/fresh/config/CorsConfig.java 的 allowedOrigins。
//
// 刻意 **不配 server.proxy**：后端 CORS 已放行 http://localhost:5177，前端直连 BASE_URL（见 src/api/index.js）。
// 配了 proxy 就等于同一份后端地址存在两处真值（vite.config.js 与 src/api/index.js），
// 多一种通道就多一份漂移面——故本工程的基址只有 src/api/index.js 里那一个常量。
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5177,
    strictPort: true
  }
})
