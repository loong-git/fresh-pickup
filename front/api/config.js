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

// ==================== 高德地图 JS API 2.0（F-06.7 R4 / F-06.7.1 / F-06.7.2） ====================
// 底图走 JS API 通道不校验安全密钥；但 Geocoder/PlaceSearch/Geolocation 等服务端伴生 API
// 必须带 jscode（实测报 INVALID_USER_SCODE，F-06.7.2）。AMAP_SECURITY_CODE 为用户提供的
// 安全密钥，loadAMap 在 JS API 加载前前置 window._AMapSecurityConfig。
// H5 key 属公开物（设计内），防滥用靠控制台域名白名单；
// 商用上线 Checklist：jscode 转 nginx 代理 + 控制台域名白名单收紧（上线任务文档 §9 登记）。
// 2026-10-14 换发：控制台里 2026-10-05 那一条（key 86dfeb…、jscode 39b41e…）已删除作废，
// 原因不是泄露事件，而是那一对随根提交进了公开仓库、历史无法回收。**本对值同样会随提交公开**，
// 所以「域名白名单」是唯一实际防线——绑定服务列显示为 Web 端，请在控制台「设置」里把正式域名填进去。
export const AMAP_KEY = '[REDACTED_AMAP_KEY]'
export const AMAP_SECURITY_CODE = '[REDACTED_AMAP_SECURITY_CODE]'
