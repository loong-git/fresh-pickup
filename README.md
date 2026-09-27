# 🥬 fresh-pickup 生鲜配送

> 多多买菜风格的社区团购生鲜配送应用 · uniapp Vue3 H5 + Spring Boot 3 + MySQL
> 自提模式 · 限时秒杀 · 优惠券核销 · 多门店 · 手机号验证码登录

![技术栈](https://img.shields.io/badge/uniapp-Vue3-2B9939) ![后端](https://img.shields.io/badge/Spring%20Boot-3.x-6DB33F) ![数据库](https://img.shields.io/badge/MySQL-8-4479A1) ![鉴权](https://img.shields.io/badge/JWT-HS256-orange)

## ✨ 功能特性

**用户端**
- 🛒 多多买菜风格首页：金刚区 / 超级秒杀（服务端时钟倒计时）/ 优惠券三卡 / 全部分类弹层
- 🔍 独立搜索页：最近搜索（居中确认框清空）/ 搜索发现（换一换）/ 地区热卖榜单横滑
- 📦 购物车全局：任意页面加购 → 底栏联动 → 弹层勾选/步进器/删除/凑单助手
- 🏪 自提模式：自提点切换（停业置灰）/ 提货人信息 / 订单门店快照
- 💳 交易闭环：下单幂等（clientRequestId）/ 库存原子扣减防超卖 / 限购后端校验 / 模拟收银台
- ⭐ 评价系统：评分 / 敏感词 DFA 过滤（硬词拦截、软词待审）
- 🖼 截单服务端裁决：23:00 前下单次日自提（pickup_date 服务端裁决，改本机时钟无效）

**管理与安全**
- 🔐 手机号验证码登录（JWT 7 天）/ 权限矩阵（匿名读、登录写）/ Bucket4j 限流 429
- 🛡 服务端计价（改价攻击无效）/ 统一异常脱敏 / 评价 XSS 入库转义 / CORS 白名单
- 🧰 管理薄版：X-Admin-Key 鉴权的订单核销 / 商品改价 / 补库存接口 + SQL 运营手册
- 📗 外部字典系统对接：分类/券模板/金刚区/服务标签 4 组枚举走字典代理（Caffeine 缓存 + 断网降级）

## 🏗 技术架构

| 层级 | 技术 |
|------|------|
| 前端 | uniapp (Vue3) + Vuex + Vite，H5 端（小程序条件编译已预留） |
| 后端 | Spring Boot 3 + MyBatis-Plus + spring-boot-starter-validation |
| 安全 | JWT（jjwt）+ AuthInterceptor 权限矩阵 + Bucket4j 限流 + CORS 白名单 |
| 数据 | MySQL 8（7 张表）+ 逻辑删除 |
| 外部依赖 | 字典系统（枚举托管，可替换为任意配置源，FALLBACK 内置兜底） |

## 📁 目录结构

```
├── front/                       # uniapp 前端
│   ├── pages/                   # index(首页) search(搜索) detail(详情)
│   │                            # order(订单) review(评价) pickup(自提点) qualification(资质)
│   ├── api/                     # request 封装 + 接口 + 字典对接 + config
│   ├── utils/time.js            # 截单/倒计时共用工具
│   └── static/images/           # 商品占位图（SVG，可替换真实图片）
├── backEnd/                     # Spring Boot 后端
│   └── src/main/java/com/fresh/
│       ├── controller/          # 10 个控制器（含 Admin 管理薄版）
│       ├── service/             # 业务（库存原子扣减/幂等/计价/敏感词/字典代理）
│       ├── pay/                 # PayService 抽象（Mock 实现 / 微信骨架）
│       ├── interceptor/         # JWT 鉴权 + 限流
│       └── dto/ common/         # 入参白名单 + 统一信封 + 全局异常
├── deploy/                      # 部署配置（Nginx / backup.sh / systemd）
└── scripts/smoke.sh             # 一键冒烟（登录→下单→查单→取消）
```

## 🚀 快速开始

### 环境要求
- JDK 17+、Maven 3.6+、MySQL 8、HBuilderX（前端运行）

### 1. 数据库
```sql
CREATE DATABASE fresh_db DEFAULT CHARACTER SET utf8mb4;
-- 执行 backEnd/sql/init.sql（建表+种子数据）
```

### 2. 后端配置
```bash
# backEnd/src/main/resources/application-dev.yml 填入你的数据库密码
spring.datasource.password: your-db-password
```

### 3. 启动
```bash
# 后端（3001 端口）
cd backEnd && mvn spring-boot:run

# 前端：HBuilderX 导入 front/ 目录 → 运行到浏览器（5173 端口）
```

### 4. 冒烟验证
```bash
bash scripts/smoke.sh   # 登录→下单→查单→取消 全链路断言
```

## 🔑 默认配置（dev）

| 项 | 值 | 说明 |
|----|-----|------|
| 登录验证码 | `123456` | dev 固定验证码（auth.dev-code），生产请接入云短信 |
| 管理密钥 | `admin-dev-key` | X-Admin-Key 头（环境变量 ADMIN_KEY） |
| 截单时间 | 每日 23:00 | 23:00 前下单次日自提 |

## 📄 License

MIT
