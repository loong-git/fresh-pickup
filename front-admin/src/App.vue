<template>
  <div class="app-shell">
    <!-- 顶栏只在登录后出现：登录页自己已有标题，未登录时挂一个「退出登录」是死路 -->
    <header v-if="showChrome" class="app-header">
      <div class="brand">
        <span class="brand-name">生鲜运营端</span>
        <el-tag type="warning" effect="dark" size="small">运营端</el-tag>
      </div>
      <nav class="nav">
        <router-link to="/overview" class="nav-link" :class="{ active: isActive('/overview') }">当日大盘</router-link>
        <router-link to="/audit-logs" class="nav-link" :class="{ active: isActive('/audit-logs') }">审计记录</router-link>
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
// 外壳（F-20.11 三页共同要求）：右上角标明当前是「运营端」（本仓第四端，与 C 端/商家端/主理人端区分），
// 并给退出登录——本期后端无登出端点（JWT 无状态），退出 = 清 admin_token + 回 /login，
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
  height: 56px;
  padding: 0 24px;
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
  gap: 16px;
  flex: 1;
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
</style>
