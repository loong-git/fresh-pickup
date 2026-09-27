// ==================== 前端环境配置（单点维护，T-M1-11） ====================
// 所有环境相关常量（后端地址）统一收敛到本文件，
// 页面与接口模块一律从 config.js 引入，不再各自硬编码。

// dev 后端地址（本地 Vite dev / H5 调试）
const DEV_BASE_URL = 'http://localhost:3001'
// 生产后端地址占位：上线前替换为生产域名（若走 Nginx 同域反代可改为 '' 走相对路径）
const PROD_BASE_URL = 'https://your-production-domain.example'

// NODE_ENV=production 时指向生产域名，其余（dev）指向 localhost（T-M1-11 验收④）
export const BASE_URL = process.env.NODE_ENV === 'production' ? PROD_BASE_URL : DEV_BASE_URL

// 字典改为走自家后端代理 GET /api/dict?group=（T-M2-07）：
// 分组名→categoryId 映射与上游鉴权 Key（dict.api-key）全部移入后端持有，前端不再暴露
// （原 DICT_BASE_URL / DICT_API_KEY 两个常量已删除）
