// ==================== 主理人端环境配置（照 front-merchant/api/config.js 的单点维护纪律） ====================
// 所有环境相关常量（后端地址）统一收敛到本文件，页面与接口模块一律从 config.js 引入，不各自硬编码。
// 用户端 config.js 里的高德 key / 安全密钥属 C 端地图链路，主理人端不带过来：
// 一期不做地图选点（D-24）——门店经纬度由平台维护（store 页只读展示，坐标列不在可改白名单），
// 入驻页的坐标走 uni.getLocation 现取现填（utils/gcj02.js 那条转换链），搬地图块进来只能看不能存。

// dev 后端地址（本地 H5 调试）。
// 必须用 http://localhost:<port> 打开本端页面，不能用 127.0.0.1：
// CorsConfig.java 的 allowedOrigins 只登记 localhost 系 origin，走 127.0.0.1 会让所有 XHR 被 CORS 拦、
// 页面显示「网络异常」而误报缺陷（F-18.15 §1）。
// 端口不保证：三端同跑时谁先起谁占 5173，本端可能顺延到 5175/5176——这两个 origin 已在 CorsConfig 登记。
// 后端 origin 与被访问端口无关（只看请求的 Origin 头），故 BASE_URL 恒指后端 3001，与本端跑在哪个端口无关。
const DEV_BASE_URL = 'http://localhost:3001'
// 生产后端地址占位：上线前替换为生产域名（若走 Nginx 同域反代可改为 '' 走相对路径）
const PROD_BASE_URL = 'https://your-production-domain.example'

// NODE_ENV=production 时指向生产域名，其余（dev）指向 localhost（判定写法与用户端/商家端一致，同源于同一套 HBuilderX 编译链）
export const BASE_URL = process.env.NODE_ENV === 'production' ? PROD_BASE_URL : DEV_BASE_URL
