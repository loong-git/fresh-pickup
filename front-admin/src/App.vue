<template>
  <div class="console-shell">
    <!-- 侧栏+顶栏只在登录后出现：登录页自己已有标题，未登录时挂一个「退出登录」是死路
         （P8-1 壳重构：布局由「顶栏横排」拆为「左深黑侧边栏 + 顶栏工具条 + 内容区」，绑定与处理函数照搬零改写） -->
    <aside v-if="showChrome" class="console-side" :class="{ 'side-collapsed': sideCollapsed }">
      <div class="side-brand">
        <div class="brand-mark">鲜</div>
        <div class="side-brand-text side-label">
          <span class="brand-name">生鲜运营端</span><span class="brand-fresh">·鲜</span>
        </div>
      </div>
      <nav class="side-nav">
        <router-link to="/overview" class="nav-link" :class="{ active: isActive('/overview') }"><span class="side-ico">▦</span><span class="side-label">大盘</span></router-link><!-- F-55：与 Overview.vue 标题同批去掉「当日」，理由见该处注释 -->
        <router-link to="/audit-logs" class="nav-link" :class="{ active: isActive('/audit-logs') }"><span class="side-ico">☰</span><span class="side-label">审计记录</span></router-link>
        <!-- F-20.22 Step 5b-2：四个运营 P0 页（每页 = 一条 5b-1 只读列表 + 一个 AdminController 旧写端点）。
             FE-G6-10：这四条会改数据，挂橙点区分于前两条只读页，图例跟在最后一条后面。 -->
        <router-link to="/orders" class="nav-link" :class="{ active: isActive('/orders') }" title="全量订单列表 + 代门店核销/取消。主理人端那个按取货码核销的「核销台」不在这个入口。"><span class="side-ico">▤</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">订单管理</span></router-link>
        <router-link to="/dishes" class="nav-link" :class="{ active: isActive('/dishes') }"><span class="side-ico">❖</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">商品管理</span></router-link>
        <!-- F-108 片 2：字典四组（含商品分类 dish_category）的运营维护入口，紧跟商品管理 -->
        <router-link to="/dicts" class="nav-link" :class="{ active: isActive('/dicts') }" title="字典分组与词条的增删改、启停与缓存清除；dish_category 组有商品引用保护。"><span class="side-ico">⊞</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">字典管理</span></router-link>
        <!-- F-112：秒杀商品集管理，只写 dish.seckill / seckill_price 两列（进秒杀设价/调价/移出，移出不回清价） -->
        <router-link to="/seckill" class="nav-link" :class="{ active: isActive('/seckill') }" title="秒杀商品集的进秒杀/调价/移出：只改 seckill 与秒杀价两列，移出不回清价；下架品可预配，上架即生效。"><span class="side-ico">◈</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">秒杀商品</span></router-link>
        <!-- F-110：搜索别名（dish_alias）维护，只影响 C 端搜索命中，不碰价格/库存/订单 -->
        <router-link to="/aliases" class="nav-link" :class="{ active: isActive('/aliases') }" title="搜索别名的增删改：别名精确命中商品，只影响搜索，不碰价格/库存/订单；商品下拉只列在售。"><span class="side-ico">✦</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">搜索别名</span></router-link>
        <!-- F-113：券模板（coupon_template）管理，只写一张表：上新/改面额门槛天数限领总量/启停；
             无删除（停用即运营语义）；key 与 issued_count 不可改；已发券核销走券上快照不受改动影响 -->
        <router-link to="/coupon-templates" class="nav-link" :class="{ active: isActive('/coupon-templates') }" title="券模板的上新/改配置/启停：只写 coupon_template 表，无删除；改面额只影响之后的新领取，已发券按快照核销。"><span class="side-ico">▢</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">券模板</span></router-link>
        <!-- F-114：自提点存量（store）管理，白名单八列部分更新：名称/地址/服务/状态/营业时间/日容量/接单；
             无新建无删除（创建只经入驻审核）；contact_phone/photos/经纬度/主理人归属不可改；
             改 status 后 C 端该店即刻置灰/可选，改日容量真实生效到下单拒单 -->
        <router-link to="/stores" class="nav-link" :class="{ active: isActive('/stores') }" title="自提点存量维护：改名称/地址/服务/状态/营业时间/日容量/接单，白名单八列；无新建无删除；改状态 C 端即刻置灰/可选，改日容量生效到下单拒单。"><span class="side-ico">⌂</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">自提点管理</span></router-link>
        <!-- F-118：结算中心，写侧只有「生成账期」一个动作（POST generate）；settlement 只增不改（无作废/重开），
             冲销走下一期 refund 列负向计入；对账 ✗ = 数据事故标红；打款通道未接（轨道 B）恒显记账态 -->
        <router-link to="/settlements" class="nav-link" :class="{ active: isActive('/settlements') }" title="结算中心：生成账期（聚合已支付·非缺货·已设供货价的未结算明细）+ 账期列表 + 账单明细。settlement 只增不改，冲销在下一账期负向计入；对账 ✗ 即数据事故。"><span class="side-ico">◫</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">结算中心</span></router-link>
        <!-- F-119：主理人钱包，纯只读（无写操作，与大盘/审计记录同款无橙点）——余额只由结算跑批记账增减，
             人工调额=资损面不设入口；提现通道未接入（轨道 B） -->
        <router-link to="/partner-wallets" class="nav-link" :class="{ active: isActive('/partner-wallets') }" title="主理人钱包（只读）：user（脱敏）/店名/余额/流水数。余额只由结算跑批自动记账，无人工调额入口；提现通道未接入（轨道 B）。"><span class="side-ico">◎</span><span class="side-label">主理人钱包</span></router-link>
        <!-- F-121：限流观测，纯只读（无写操作，与大盘/审计记录同款无橙点）——命中即异常流量信号（刷接口/恶意重试），
             本页只读，封禁操作见「封禁管理」 -->
        <router-link to="/rate-limit" class="nav-link" :class="{ active: isActive('/rate-limit') }" title="限流观测（只读）：时间档（今天/近 7 天/近 30 天）内限流命中的按路由+维度键聚合。命中即异常流量信号（刷接口/恶意重试）；本页只读，封禁操作见「封禁管理」。"><span class="side-ico">◇</span><span class="side-label">限流观测</span></router-link>
        <!-- F-122：封禁管理，写侧页（用户/商家各一个钉状态写端点），紧跟限流观测——观测到的异常流量在这里处置。
             user 封禁=登录拒 + 已签发旧 token 立即失效；merchant 封禁复用既有的 suspended（停业整顿），
             C 端商品仍展示、只拦下单，商家端写与订单/履约 403；不自动下架/清秒杀/移出免费领池 -->
        <router-link to="/bans" class="nav-link" :class="{ active: isActive('/bans') }" title="用户/商家封禁与解封：user 封禁即登录拒+旧 token 失效；merchant 封禁复用停业整顿，C 端仅下单拦截、可见性不变。"><span class="side-ico">⊘</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">封禁管理</span></router-link>
        <!-- F-109：免费领活动配置（换品/日限量/门槛/上下线）与候选池维护；dailyClaimed 是运行时计数，本页不可改 -->
        <router-link to="/free-activity" class="nav-link" :class="{ active: isActive('/free-activity') }" title="免费领活动配置与候选池维护；dailyClaimed 是运行时计数，本页不可改，改它会超发。"><span class="side-ico">♦</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">免费领活动</span></router-link>
        <router-link to="/reviews" class="nav-link" :class="{ active: isActive('/reviews') }"><span class="side-ico">☆</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">评价审核</span></router-link>
        <router-link to="/store-applies" class="nav-link" :class="{ active: isActive('/store-applies') }"><span class="side-ico">⊕</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">入驻审核</span></router-link>
        <!-- F-106 片 C：商家自助入驻申请的审核队列，紧跟门店申请审核（两条入口都读申请、都发审核写）。
             与「入驻审核」（store_apply 门店入驻）不是同一张表，标题里点名区分。 -->
        <router-link to="/merchant-applies" class="nav-link" :class="{ active: isActive('/merchant-applies') }" title="商家自助入驻申请（merchant_apply）审核；门店入驻审核在上一个入口。"><span class="side-ico">⌗</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">商家入驻</span></router-link>
        <!-- F-63 到货发车：写侧页（发车建批次→到货中），挂橙点与其余四条写侧入口同列 -->
        <router-link to="/arrivals" class="nav-link" :class="{ active: isActive('/arrivals') }"><span class="side-ico">➤</span><span class="nav-dot" title="本页会改数据"></span><span class="side-label">到货发车</span></router-link>
        <span class="nav-legend" title="橙点入口对应的页面会修改数据，操作前有二次确认">● 会改数据（操作前有二次确认）</span>
      </nav>
      <div class="side-user">
        <div class="side-avatar">鲜</div>
        <div class="side-user-meta side-label">
          <span class="side-user-name">运营管理员</span>
        </div>
        <el-button size="small" @click="onLogout">退出登录</el-button>
      </div>
    </aside>
    <div class="console-main">
      <header v-if="showChrome" class="console-topbar">
        <button type="button" class="side-toggle" :title="sideCollapsed ? '展开侧栏' : '收起侧栏'" @click="toggleSide">☰</button>
        <div class="topbar-capsules">
          <el-tag type="warning" effect="dark" size="small" class="tag-gold">运营端</el-tag>
        </div>
      </header>
      <main class="console-content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
// 外壳（F-20.11 三页共同要求 + F-20.22 四页扩到六条入口；F-63/F-106/F-108/F-109/F-110/F-112/F-113/F-114/F-118/F-119/F-121/F-122
// 又各追加一页，现为十八条业务入口）：右上角标明当前是「运营端」（本仓第四端，与 C 端/商家端/主理人端区分），
// 并给退出登录——本期后端无登出端点（JWT 无状态），退出 = 清 admin_token + 回 /login，
// 清态与跳转都走 api 层的 logout()，本组件不各自读 storage（键名真源只有 TOKEN_STORAGE_KEY 一处）。
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { logout, setForbiddenHandler } from './api'

const route = useRoute()
const showChrome = computed(() => route.path !== '/login')
const isActive = (path) => route.path === path
const onLogout = () => logout()

// —— P8-1 纯 UI 状态（八期 §1-2 放宽条款）：侧栏折叠。只切壳布局，不触碰任何业务逻辑 ——
const sideCollapsed = ref(false)
const toggleSide = () => {
  sideCollapsed.value = !sideCollapsed.value
}

// P4-7 §6（D6）：403 的全局出口。api 层 handleEnvelope 在 403 分支先把信封通知到这里，
// 再原样 reject 给页面（页面既有的 forbidden 态 / 结果条不受影响）——本组件只负责这一次性的
// 「换账号或留在本页」出口。403 不是 401：不清 token、不自动跳登录，只有用户点「退出登录」才走既有 logout()。
// 两个模块级普通变量（非 ref，不参与渲染）：
//   forbiddenPrompting —— 弹框在飞期间后续 403 一律忽略（一屏多请求同时 403 只弹一个框）；
//   forbiddenPromptedPath —— 同一路由只提示一次，换页（下面的 watch）后允许再提示。
let forbiddenPrompting = false
let forbiddenPromptedPath = ''

watch(() => route.path, () => {
  forbiddenPromptedPath = ''
})

setForbiddenHandler((payload) => {
  const path = route.path
  if (forbiddenPrompting || forbiddenPromptedPath === path) return
  forbiddenPrompting = true
  forbiddenPromptedPath = path
  ElMessageBox.confirm(
    '后端原文：' + ((payload && payload.message) || '无权限访问') + '\n\n'
      + '当前运营账号没有该功能的访问权限。可以退出登录后换用有权限的账号，或联系管理员确认账号授权。',
    '没有访问权限',
    {
      type: 'warning',
      confirmButtonText: '退出登录',
      cancelButtonText: '留在本页',
      closeOnClickModal: false,
      distinguishCancelAndClose: true,
      customClass: 'admin-write-confirm'
    }
  ).then(() => { logout() }).catch(() => {}).finally(() => { forbiddenPrompting = false })
})
</script>

<style>
/* ===== 八期 P8-1「暗色控制台」主题层（docs/八期规划/00-总纲.md §0 tokens） =====
   官方暗色 vars（main.js 已引 dark/css-vars.css + 挂 html.dark）之上覆盖一次品牌变量：
   EP 组件与全 19 页自动生效，各页 scoped 不再逐页碰 EP 变量。同选择器写 html.dark 以在
   同特异性下后置压过 EP 官方暗色默认（本组件样式在 EP css 之后入束）。 */
:root,
html.dark {
  /* 主色：强调蓝 + light 梯度（§0 强调蓝 #3B82F6；light-8 取 light-7/9 之间插值） */
  --el-color-primary: #3B82F6;
  --el-color-primary-light-3: #6EA8FE;
  --el-color-primary-light-5: #9DC0FB;
  --el-color-primary-light-7: #CEDDFF;
  --el-color-primary-light-8: #DAE5FF;
  --el-color-primary-light-9: #E7EEFF;
  --el-color-primary-dark-2: #2563EB;
  /* 开关/成功绿（§0） */
  --el-color-success: #22C55E;
  --el-color-success-light-3: #64D68E;
  --el-color-success-light-5: #91E2AF;
  --el-color-success-light-7: #BDEECF;
  --el-color-success-light-8: #D3F3DF;
  --el-color-success-light-9: #E9F9EF;
  --el-color-success-dark-2: #1B9E4B;
  /* warning：强调橙（light 梯度沿用七期白混口径） */
  --el-color-warning: #D97706;
  --el-color-warning-light-3: #E4A051;
  --el-color-warning-light-5: #ECBB83;
  --el-color-warning-light-7: #F4D6B4;
  --el-color-warning-light-8: #F7E4CD;
  --el-color-warning-light-9: #FBF1E6;
  --el-color-warning-dark-2: #B45309;
  /* danger：危险红（§0 #EF4444，light 梯度按 EP 白混口径推导） */
  --el-color-danger: #EF4444;
  --el-color-danger-light-3: #F47C7C;
  --el-color-danger-light-5: #F7A2A2;
  --el-color-danger-light-7: #FAC7C7;
  --el-color-danger-light-8: #FCDADA;
  --el-color-danger-light-9: #FDECEC;
  --el-color-danger-dark-2: #BF3636;
  /* error = danger 同族（EP 暗色对 error 单独定义，需同值覆盖） */
  --el-color-error: #EF4444;
  --el-color-error-light-3: #F47C7C;
  --el-color-error-light-5: #F7A2A2;
  --el-color-error-light-7: #FAC7C7;
  --el-color-error-light-8: #FCDADA;
  --el-color-error-light-9: #FDECEC;
  --el-color-error-dark-2: #BF3636;
  /* info：中性灰阶（§0 未单列，按暗色灰阶推导） */
  --el-color-info: #6F6F76;
  --el-color-info-light-3: #98989E;
  --el-color-info-light-5: #B4B4BA;
  --el-color-info-light-7: #D0D0D6;
  --el-color-info-light-8: #E0E0E4;
  --el-color-info-light-9: #ECECF0;
  --el-color-info-dark-2: #57575E;
  /* 面：卡片底 / 弹层底 / 页面底（§0） */
  --el-bg-color: #1B1B1D;
  --el-bg-color-overlay: #202023;
  --el-bg-color-page: #0A0A0B;
  /* 边框（§0 卡片边框 #2A2A2D；light/lighter 为任务指定，extra/dark/darker 按暗色阶推导） */
  --el-border-color: #2A2A2D;
  --el-border-color-light: #262629;
  --el-border-color-lighter: #232326;
  --el-border-color-extra-light: #1F1F22;
  --el-border-color-dark: #34343A;
  --el-border-color-darker: #3C3C42;
  /* 三级文字（§0） */
  --el-text-color-primary: #F2F2F2;
  --el-text-color-regular: #D6D6D9;
  --el-text-color-secondary: #A6A6AA;
  --el-text-color-placeholder: #6F6F76;
  --el-text-color-disabled: #6F6F76;
  /* 填充（§0 fill 三档 #262629/#202023/#0A0A0B 映进 EP 暗色阶：base 最亮、逐级向页面底逼近） */
  --el-fill-color: #262629;
  --el-fill-color-light: #202023;
  --el-fill-color-lighter: #1B1B1D;
  --el-fill-color-extra-light: #0A0A0B;
  --el-fill-color-dark: #262629;
  --el-fill-color-darker: #2A2A2D;
  --el-fill-color-blank: #1B1B1D;
  /* 圆角：控件 8px（§0 按钮 10px 由下方 .el-button 单独给，卡 14px 由各页 scoped 给） */
  --el-border-radius-base: 8px;
  --el-border-radius-small: 6px;
  /* 表格面：表头深一档 / 行 hover 微亮（§0 阴影近零、靠色阶分层） */
  --el-table-header-bg-color: #202023;
  --el-table-header-text-color: #A6A6AA;
  --el-table-border-color: #2A2A2D;
  --el-table-row-hover-bg-color: #262629;
  /* 壳层两枚旧变量原位重定值：各页 scoped 的 var(--admin-*) 引用零改动即换肤 */
  --admin-bg: #0A0A0B;
  --admin-border: #2A2A2D;
}
/* §0 开关绿：EP 的 switch-on 色定义在组件根上，需在组件选择器上覆盖 */
.el-switch.el-switch {
  --el-switch-on-color: #22C55E;
}
* {
  box-sizing: border-box;
}
body {
  margin: 0;
  background: var(--admin-bg);
  font-family: 'Helvetica Neue', Helvetica, Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #F2F2F2;
}
/* ---- 壳：左深黑侧边栏 + 右列（顶栏工具条 + 内容区） ---- */
.console-shell {
  display: flex;
  height: 100vh;
  background: var(--admin-bg);
}
.console-side {
  width: 240px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  background: #111113;
  border-right: 1px solid #232326;
  overflow: hidden;
}
/* 品牌区：40px 圆角方块「鲜」+ 白字 + 蓝字缀 */
.side-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 14px;
  flex-shrink: 0;
}
.brand-mark {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: #3B82F6;
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.side-brand-text {
  display: flex;
  align-items: baseline;
  min-width: 0;
}
.brand-name {
  font-size: 15px;
  font-weight: 700;
  color: #F2F2F2;
  letter-spacing: 0.5px;
  white-space: nowrap;
}
/* 「·鲜」品牌字缀（纯装饰） */
.brand-fresh {
  font-size: 14px;
  font-weight: 800;
  color: #3B82F6;
  letter-spacing: 1px;
  white-space: nowrap;
}
/* 中部菜单：纵向列表，active 蓝底白字圆角 10px，hover #202023 */
.side-nav {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 10px;
}
.nav-link {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #A6A6AA;
  text-decoration: none;
  font-size: 13px;
  line-height: 1;
  padding: 10px 12px;
  border-radius: 10px;
  white-space: nowrap;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.nav-link:hover {
  color: #F2F2F2;
  background: #202023;
}
/* active = 蓝底白字（壳的记忆点），橙点在蓝底上依旧醒目 */
.nav-link.active {
  color: #fff;
  font-weight: 600;
  background: #3B82F6;
}
/* 菜单项装饰符号（纯装饰，不承载语义） */
.side-ico {
  width: 16px;
  flex-shrink: 0;
  text-align: center;
  font-size: 14px;
  line-height: 1;
}
/* 会改数据橙点（语义原样）：强调橙 + 一圈淡橙晕 */
.nav-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #D97706;
  box-shadow: 0 0 0 3px rgba(217, 119, 6, 0.22);
  margin-right: 5px;
  flex-shrink: 0;
}
.nav-legend {
  font-size: 11px;
  color: #6F6F76;
  white-space: nowrap;
  padding: 8px 12px 4px;
}
/* 底部用户卡 */
.side-user {
  margin: 10px;
  padding: 12px;
  background: #1B1B1D;
  border: 1px solid #2A2A2D;
  border-radius: 12px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.side-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: #202023;
  border: 1px solid #2A2A2D;
  color: #F2F2F2;
  font-size: 14px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.side-user-meta {
  flex: 1;
  min-width: 0;
}
.side-user-name {
  display: block;
  font-size: 12px;
  color: #A6A6AA;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.side-user .el-button {
  flex-shrink: 0;
}
/* 折叠态：240px → 64px，只显符号 */
.side-collapsed {
  width: 64px;
}
.side-collapsed .side-label,
.side-collapsed .nav-legend {
  display: none;
}
.side-collapsed .side-brand {
  justify-content: center;
  padding-left: 0;
  padding-right: 0;
}
.side-collapsed .nav-link {
  justify-content: center;
  padding-left: 0;
  padding-right: 0;
}
.side-collapsed .nav-dot {
  margin-right: 0;
}
.side-collapsed .side-user {
  flex-direction: column;
  align-items: center;
  padding: 10px 4px;
}
.side-collapsed .side-user .el-button {
  width: 100%;
  padding: 5px 0;
  font-size: 11px;
}
/* 右列：顶栏工具条 + 内容区 */
.console-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.console-topbar {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  background: #111113;
  border-bottom: 1px solid #232326;
}
/* 折叠按钮（纯 UI） */
.side-toggle {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  border: 1px solid #2A2A2D;
  background: #1B1B1D;
  color: #A6A6AA;
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}
.side-toggle:hover {
  color: #F2F2F2;
  background: #202023;
}
.topbar-capsules {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 「运营端」金胶囊（§0：描边 #8A6D1D 底 #3A2F10 文字 #E7C868） */
.console-topbar .tag-gold.el-tag {
  --el-tag-bg-color: #3A2F10;
  --el-tag-border-color: #8A6D1D;
  --el-tag-text-color: #E7C868;
  border-radius: 999px;
}
/* 内容区 */
.console-content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 20px;
  background: #0A0A0B;
}
.page {
  max-width: 1280px;
  margin: 0 auto;
}
/* 页标题 20px/700 白字与 12px 说明文字拉开层级 */
.page-title {
  font-size: 20px;
  font-weight: 700;
  color: #F2F2F2;
  margin: 0 0 4px;
  letter-spacing: 0.2px;
}
.page-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.page-note {
  font-size: 12px;
  color: #A6A6AA;
  line-height: 1.7;
}
/* ---- 表格面：14px 圆角裁切容器 + 行高 +4px + 金额/数字列等宽（§0 密度） ---- */
.el-table {
  border-radius: 14px;
  overflow: hidden;
  font-variant-numeric: tabular-nums;
}
.el-table .el-table__inner-wrapper {
  border-radius: 14px;
}
.el-table th.el-table__cell {
  font-weight: 600;
}
.el-table .el-table__cell {
  padding-top: 10px;
  padding-bottom: 10px;
}
/* §0 按钮 10px 圆角 */
.el-button {
  border-radius: 10px;
}
/* 弹层圆角随 tokens（卡 14px） */
.el-dialog {
  border-radius: 14px;
}
.el-message-box {
  border-radius: 14px;
}
.state-box {
  padding: 40px;
  text-align: center;
  background: #1B1B1D;
  border: 1px solid var(--admin-border);
  border-radius: 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}
.state-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
  color: #F2F2F2;
}
.state-desc {
  font-size: 13px;
  color: #A6A6AA;
}
/* 写动作二次确认框：多行后果清单要真的换行、加宽到能读完一行 */
.admin-write-confirm {
  width: 640px;
  max-width: 92vw;
}
.admin-write-confirm .el-message-box__message {
  white-space: pre-line;
  line-height: 1.75;
  font-size: 13px;
  color: #D6D6D9;
}
</style>
