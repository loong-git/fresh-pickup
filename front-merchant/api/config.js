// ==================== 商家端环境配置（照 front/api/config.js 的单点维护纪律） ====================
// 所有环境相关常量（后端地址）统一收敛到本文件，页面与接口模块一律从 config.js 引入，不各自硬编码。
// 用户端 config.js 里的高德 key / 安全密钥属 C 端地图链路，商家端一期无地图，不带过来。

// dev 后端地址（本地 H5 调试；商家端跑 5174，与用户端 5173 错开，两端 origin 均已在后端 CorsConfig 白名单）
const DEV_BASE_URL = 'http://localhost:3001'
// 生产后端地址占位：上线前替换为生产域名（若走 Nginx 同域反代可改为 '' 走相对路径）
const PROD_BASE_URL = 'https://your-production-domain.example'

// NODE_ENV=production 时指向生产域名，其余（dev）指向 localhost（判定写法与用户端一致，同源于同一套 HBuilderX 编译链）
export const BASE_URL = process.env.NODE_ENV === 'production' ? PROD_BASE_URL : DEV_BASE_URL
