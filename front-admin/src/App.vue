<template>
  <div class="app-shell">
    <!-- 顶栏只在登录后出现：登录页自己已有标题，未登录时挂一个「退出登录」是死路 -->
    <header v-if="showChrome" class="app-header">
      <div class="brand">
        <span class="brand-name">生鲜运营端</span>
        <el-tag type="warning" effect="dark" size="small">运营端</el-tag>
      </div>
      <nav class="nav">
        <router-link to="/overview" class="nav-link" :class="{ active: isActive('/overview') }">大盘</router-link><!-- F-55：与 Overview.vue 标题同批去掉「当日」，理由见该处注释 -->
        <router-link to="/audit-logs" class="nav-link" :class="{ active: isActive('/audit-logs') }">审计记录</router-link>
        <!-- F-20.22 Step 5b-2：四个运营 P0 页（每页 = 一条 5b-1 只读列表 + 一个 AdminController 旧写端点）。
             FE-G6-10：这四条会改数据，挂橙点区分于前两条只读页，图例跟在最后一条后面。 -->
        <router-link to="/orders" class="nav-link" :class="{ active: isActive('/orders') }" title="全量订单列表 + 代门店核销/取消。主理人端那个按取货码核销的「核销台」不在这个入口。"><span class="nav-dot" title="本页会改数据"></span>订单管理</router-link>
        <router-link to="/dishes" class="nav-link" :class="{ active: isActive('/dishes') }"><span class="nav-dot" title="本页会改数据"></span>商品管理</router-link>
        <!-- F-108 片 2：字典四组（含商品分类 dish_category）的运营维护入口，紧跟商品管理 -->
        <router-link to="/dicts" class="nav-link" :class="{ active: isActive('/dicts') }" title="字典分组与词条的增删改、启停与缓存清除；dish_category 组有商品引用保护。"><span class="nav-dot" title="本页会改数据"></span>字典管理</router-link>
        <!-- F-112：秒杀商品集管理，只写 dish.seckill / seckill_price 两列（进秒杀设价/调价/移出，移出不回清价） -->
        <router-link to="/seckill" class="nav-link" :class="{ active: isActive('/seckill') }" title="秒杀商品集的进秒杀/调价/移出：只改 seckill 与秒杀价两列，移出不回清价；下架品可预配，上架即生效。"><span class="nav-dot" title="本页会改数据"></span>秒杀商品</router-link>
        <!-- F-110：搜索别名（dish_alias）维护，只影响 C 端搜索命中，不碰价格/库存/订单 -->
        <router-link to="/aliases" class="nav-link" :class="{ active: isActive('/aliases') }" title="搜索别名的增删改：别名精确命中商品，只影响搜索，不碰价格/库存/订单；商品下拉只列在售。"><span class="nav-dot" title="本页会改数据"></span>搜索别名</router-link>
        <!-- F-113：券模板（coupon_template）管理，只写一张表：上新/改面额门槛天数限领总量/启停；
             无删除（停用即运营语义）；key 与 issued_count 不可改；已发券核销走券上快照不受改动影响 -->
        <router-link to="/coupon-templates" class="nav-link" :class="{ active: isActive('/coupon-templates') }" title="券模板的上新/改配置/启停：只写 coupon_template 表，无删除；改面额只影响之后的新领取，已发券按快照核销。"><span class="nav-dot" title="本页会改数据"></span>券模板</router-link>
        <!-- F-114：自提点存量（store）管理，白名单八列部分更新：名称/地址/服务/状态/营业时间/日容量/接单；
             无新建无删除（创建只经入驻审核）；contact_phone/photos/经纬度/主理人归属不可改；
             改 status 后 C 端该店即刻置灰/可选，改日容量真实生效到下单拒单 -->
        <router-link to="/stores" class="nav-link" :class="{ active: isActive('/stores') }" title="自提点存量维护：改名称/地址/服务/状态/营业时间/日容量/接单，白名单八列；无新建无删除；改状态 C 端即刻置灰/可选，改日容量生效到下单拒单。"><span class="nav-dot" title="本页会改数据"></span>自提点管理</router-link>
        <!-- F-118：结算中心，写侧只有「生成账期」一个动作（POST generate）；settlement 只增不改（无作废/重开），
             冲销走下一期 refund 列负向计入；对账 ✗ = 数据事故标红；打款通道未接（轨道 B）恒显记账态 -->
        <router-link to="/settlements" class="nav-link" :class="{ active: isActive('/settlements') }" title="结算中心：生成账期（聚合已支付·非缺货·已设供货价的未结算明细）+ 账期列表 + 账单明细。settlement 只增不改，冲销在下一账期负向计入；对账 ✗ 即数据事故。"><span class="nav-dot" title="本页会改数据"></span>结算中心</router-link>
        <!-- F-119：主理人钱包，纯只读（无写操作，与大盘/审计记录同款无橙点）——余额只由结算跑批记账增减，
             人工调额=资损面不设入口；提现通道未接入（轨道 B） -->
        <router-link to="/partner-wallets" class="nav-link" :class="{ active: isActive('/partner-wallets') }" title="主理人钱包（只读）：user（脱敏）/店名/余额/流水数。余额只由结算跑批自动记账，无人工调额入口；提现通道未接入（轨道 B）。">主理人钱包</router-link>
        <!-- F-121：限流观测，纯只读（无写操作，与大盘/审计记录同款无橙点）——命中即异常流量信号（刷接口/恶意重试），
             本页只读，封禁操作见「封禁管理」 -->
        <router-link to="/rate-limit" class="nav-link" :class="{ active: isActive('/rate-limit') }" title="限流观测（只读）：时间档（今天/近 7 天/近 30 天）内限流命中的按路由+维度键聚合。命中即异常流量信号（刷接口/恶意重试）；本页只读，封禁操作见「封禁管理」。">限流观测</router-link>
        <!-- F-122：封禁管理，写侧页（用户/商家各一个钉状态写端点），紧跟限流观测——观测到的异常流量在这里处置。
             user 封禁=登录拒 + 已签发旧 token 立即失效；merchant 封禁复用既有的 suspended（停业整顿），
             C 端商品仍展示、只拦下单，商家端写与订单/履约 403；不自动下架/清秒杀/移出免费领池 -->
        <router-link to="/bans" class="nav-link" :class="{ active: isActive('/bans') }" title="用户/商家封禁与解封：user 封禁即登录拒+旧 token 失效；merchant 封禁复用停业整顿，C 端仅下单拦截、可见性不变。"><span class="nav-dot" title="本页会改数据"></span>封禁管理</router-link>
        <!-- F-109：免费领活动配置（换品/日限量/门槛/上下线）与候选池维护；dailyClaimed 是运行时计数，本页不可改 -->
        <router-link to="/free-activity" class="nav-link" :class="{ active: isActive('/free-activity') }" title="免费领活动配置与候选池维护；dailyClaimed 是运行时计数，本页不可改，改它会超发。"><span class="nav-dot" title="本页会改数据"></span>免费领活动</router-link>
        <router-link to="/reviews" class="nav-link" :class="{ active: isActive('/reviews') }"><span class="nav-dot" title="本页会改数据"></span>评价审核</router-link>
        <router-link to="/store-applies" class="nav-link" :class="{ active: isActive('/store-applies') }"><span class="nav-dot" title="本页会改数据"></span>入驻审核</router-link>
        <!-- F-106 片 C：商家自助入驻申请的审核队列，紧跟门店申请审核（两条入口都读申请、都发审核写）。
             与「入驻审核」（store_apply 门店入驻）不是同一张表，标题里点名区分。 -->
        <router-link to="/merchant-applies" class="nav-link" :class="{ active: isActive('/merchant-applies') }" title="商家自助入驻申请（merchant_apply）审核；门店入驻审核在上一个入口。"><span class="nav-dot" title="本页会改数据"></span>商家入驻</router-link>
        <!-- F-63 到货发车：写侧页（发车建批次→到货中），挂橙点与其余四条写侧入口同列 -->
        <router-link to="/arrivals" class="nav-link" :class="{ active: isActive('/arrivals') }"><span class="nav-dot" title="本页会改数据"></span>到货发车</router-link>
        <span class="nav-legend" title="橙点入口对应的页面会修改数据，操作前有二次确认">● 会改数据（操作前有二次确认）</span>
      </nav>
      <div class="actions">
        <el-button size="small" @click="onLogout">退出登录</el-button>
      </div>
    </header>

    <main class="app-main" :class="{ 'with-header': showChrome }">
      <router-view />
    </main>
  </div>
</template>

<script setup>
// 外壳（F-20.11 三页共同要求 + F-20.22 四页扩到六条入口；F-63/F-106/F-108/F-109/F-110/F-112/F-113/F-114/F-118/F-119/F-121/F-122
// 又各追加一页，现为十八条业务入口）：右上角标明当前是「运营端」（本仓第四端，与 C 端/商家端/主理人端区分），
// 并给退出登录——本期后端无登出端点（JWT 无状态），退出 = 清 admin_token + 回 /login，
// 清态与跳转都走 api 层的 logout()，本组件不各自读 storage（键名真源只有 TOKEN_STORAGE_KEY 一处）。
import { computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { logout, setForbiddenHandler } from './api'

const route = useRoute()
const showChrome = computed(() => route.path !== '/login')
const isActive = (path) => route.path === path
const onLogout = () => logout()

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
:root {
  --admin-bg: #f5f6f8;
  --admin-border: #e6e7eb;
}
* {
  box-sizing: border-box;
}
body {
  margin: 0;
  background: var(--admin-bg);
  font-family: 'Helvetica Neue', Helvetica, Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #303133;
}
.app-shell {
  min-height: 100vh;
}
.app-header {
  display: flex;
  align-items: center;
  gap: 24px;
  /* F-20.22 顶栏从两条变六条：定高 56px 会在窄屏把导航挤出去，改成 min-height + 可换行，
     宽屏读数与改动前一致（单行 56px），窄屏则整条 header 自然加高。 */
  min-height: 56px;
  padding: 8px 24px;
  flex-wrap: wrap;
  background: #fff;
  border-bottom: 1px solid var(--admin-border);
  position: sticky;
  top: 0;
  z-index: 10;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
}
.brand-name {
  font-size: 16px;
  font-weight: 600;
}
.nav {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  flex: 1;
  min-width: 0;
}
.nav-link {
  color: #606266;
  text-decoration: none;
  font-size: 14px;
  padding: 4px 2px;
  border-bottom: 2px solid transparent;
}
.nav-link.active {
  color: #303133;
  font-weight: 600;
  border-bottom-color: #e6a23c;
}
.actions {
  margin-left: auto;
}
.app-main {
  padding: 24px;
}
.app-main.with-header {
  padding-top: 24px;
}
.page {
  max-width: 1280px;
  margin: 0 auto;
}
.page-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 4px;
}
/* FE-G6-10（读写可辨）：页眉行（标题 + 读/写徽标）。徽标本体是 el-tag，这里只管排布；
   写页挂 danger「本页会改数据」、只读页挂 info「本页只读」，常驻 ready 态（原先这类话只在出错态出现）。 */
.page-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
/* 写侧入口的橙点与图例：入口平铺到第十二个（F-106 加「商家入驻」、F-108 加「字典管理」、
   F-109 加「免费领活动」、F-110 加「搜索别名」、F-112 加「秒杀商品」）后，
   运营不看说明文更读不出「点了就改库」，写侧链接挂橙点 + 一条常驻图例，与各页页眉的「本页会改数据」互相印证。 */
.nav-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #e6a23c;
  margin-right: 4px;
  vertical-align: middle;
}
.nav-legend {
  font-size: 11px;
  color: #909399;
  white-space: nowrap;
  margin-left: 4px;
}
.page-note {
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
.state-box {
  padding: 40px;
  text-align: center;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.state-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 6px;
}
.state-desc {
  font-size: 13px;
  color: #606266;
}
/* F-20.22 四个 P0 页写动作的二次确认框（ElMessageBox 挂在 body 下，页面 scoped 样式作用不到，
   所以这一条只有全局一份；四个页面 customClass 传的是同一个串 'admin-write-confirm'）。
   为什么要改 white-space：确认文案是「把后果写全」的多行清单（核销/取消/删除/发布门店各自的副作用），
   默认渲染会把 \n 折成空格、糊成一小块 420px 的单行——运营读不到重点，纪律 2 就只剩形式。
   这里让换行真的换行、并把框加宽到能读完一行。 */
.admin-write-confirm {
  width: 640px;
  max-width: 92vw;
}
.admin-write-confirm .el-message-box__message {
  white-space: pre-line;
  line-height: 1.75;
  font-size: 13px;
  color: #303133;
}
</style>
