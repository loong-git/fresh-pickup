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
// 必须带 jscode（实测报 INVALID_USER_SCODE，F-06.7.2）。loadAMap 在 JS API 加载前前置
// window._AMapSecurityConfig。
// 值不再写在本文件里（F-103）：两把凭据由 vite 的 .env.local 提供，该文件已进 .gitignore，
// 仓库内只提交占位 front/.env.example。取不到值时 AMAP_KEY 为空串，底图请求会带 key= 失败——
// 这是有意的：宁可当场报错，也不要把凭据退回源码。消费方只有 pages/pickup/pickup.vue。
// 2026-10-14 换发：控制台里 2026-10-05 那一条（key 86dfeb…、jscode 39b41e…）已删除作废，
// 原因不是泄露事件，而是那一对随根提交进了公开仓库、历史无法回收。
// ⚠ 现役这一对（30ffe8…／81c31a…）自 b1de89d（2026-10-14）起同样躺在公开历史里，
// 移入 .env.local 只堵住「今后」的入库面，堵不住已发布的历史；商用上线前必须先去控制台
// 轮换，并把控制台域名白名单收紧（jscode 转 nginx 代理见上线任务文档 §9 登记）。
export const AMAP_KEY = import.meta.env.VITE_AMAP_KEY || ''
export const AMAP_SECURITY_CODE = import.meta.env.VITE_AMAP_SECURITY_CODE || ''
