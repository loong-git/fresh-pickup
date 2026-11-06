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
  },
  build: {
    rollupOptions: {
      output: {
        // 【P10-4 C6 构建分包（轻做）】目的：主 chunk 此前 884KB（element-plus 全量占约七成），
        // 单文件串行下载拖慢首屏；拆分后 EP 独立成块与业务主 chunk 并行加载（预期主 chunk 降至 ~200KB 级），
        // 且 EP chunk 内容不随业务迭代变化，可长期命中浏览器缓存。零业务逻辑改动，仅影响产物分块。
        // 写法：函数式 manualChunks（vite 2 起 API，本工程 vite 8.3.4 沿用），
        // 按 rollup 模块 id 的 node_modules 包名精确归组（正则兼容 Windows 反斜杠与 scoped 包）：
        manualChunks(id) {
          // 非第三方依赖（业务源码）不干预，随默认分组进主 chunk
          if (!/[\\/]node_modules[\\/]/.test(id)) return undefined
          // element-plus 本体 + @element-plus/icons-vue 等官方包 → 独立 element-plus chunk（~700KB）
          if (/[\\/]node_modules[\\/](element-plus|@element-plus)[\\/]/.test(id)) {
            return 'element-plus'
          }
          // 其余框架依赖（vue / @vue/* 运行时、vue-router）→ vendor chunk，与 EP 块并行加载
          return 'vendor'
        }
      }
    }
  }
})
