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
// 外壳（F-20.11 三页共同要求 + F-20.22 四页扩到六条入口）：右上角标明当前是「运营端」（本仓第四端，
// 与 C 端/商家端/主理人端区分），并给退出登录——本期后端无登出端点（JWT 无状态），退出 = 清 admin_token + 回 /login，
// 清态与跳转都走 api 层的 logout()，本组件不各自读 storage（键名真源只有 TOKEN_STORAGE_KEY 一处）。
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { logout } from './api'

const route = useRoute()
const showChrome = computed(() => route.path !== '/login')
const isActive = (path) => route.path === path
const onLogout = () => logout()
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
/* 写侧入口的橙点与图例：入口平铺到第九个（F-106 加「商家入驻」、F-108 加「字典管理」）后，运营不看说明文更读不出「点了就改库」，
   写侧链接挂橙点 + 一条常驻图例，与各页页眉的「本页会改数据」互相印证。 */
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
