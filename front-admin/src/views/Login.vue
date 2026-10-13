<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1 class="login-title">生鲜运营端</h1>
      <p class="login-sub">运营账号登录</p>

      <el-form class="login-form" @submit.prevent="onSubmit">
        <el-form-item>
          <el-input
            v-model="username"
            placeholder="用户名"
            autocomplete="username"
            :disabled="viewState === 'loading'"
            clearable
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="password"
            type="password"
            placeholder="密码"
            autocomplete="current-password"
            show-password
            :disabled="viewState === 'loading'"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            class="submit"
            type="primary"
            native-type="submit"
            :loading="viewState === 'loading'"
            :disabled="locked"
          >
            登录
          </el-button>
        </el-form-item>
      </el-form>

      <!-- 失败文案只有后端 message 原文这一处来源：三种失败（账号不存在 / 口令错 / 已停用）在
           服务端本来就是同一句「用户名或密码错误」（D-5 防枚举），页面不得自己分支、
           不得推断成「锁定中」「账号不存在」这类可区分信号。
           锁定态唯一的例外是后端在 data 里如实给的 retryAfterSeconds——那是既有载荷的转述，不是推断。 -->
      <p v-if="viewState === 'error' || viewState === 'forbidden'" class="error">{{ errorText }}</p>
      <p v-if="locked" class="lock-tip">请 {{ waitDurationText(lockRemaining) }}后再试</p>
    </div>
  </div>
</template>

<script setup>
// 运营登录页（F-20.11 Step 4）：两字段 + 提交。
// 请求纪律由 src/api/index.js 收敛：本页面**不碰 fetch**、不碰 Authorization 头
// （adminLogin() 内部 loginRequest:true → 强制不带 Authorization）。
// viewState 单值互斥：'ready' | 'loading' | 'forbidden' | 'error'
// （登录页现状读数：后端只会给 200 / 401 / 429，403 走不到，分支保留是为与另两页同一套状态语义，
//  且真出现 403 时它不能被当登录失效处理——见 api 层 401/403 严格分流那条）。
import { computed, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { adminLogin, setStoredToken, waitDurationText } from '../api'

const router = useRouter()

const viewState = ref('ready')
const username = ref('')
const password = ref('')
const errorText = ref('')

// 锁定倒计时：秒数只来自后端 data.retryAfterSeconds（AdminAuthServiceImpl 按 Caffeine 剩余 TTL 推算），
// 页面自己不猜锁定时长、不做「大概锁 10 分钟」这类推断。
const lockRemaining = ref(0)
let lockTimer = null
const locked = computed(() => lockRemaining.value > 0)

function stopLockCountdown() {
  if (lockTimer !== null) {
    clearInterval(lockTimer)
    lockTimer = null
  }
}

function startLockCountdown(seconds) {
  stopLockCountdown()
  lockRemaining.value = seconds
  lockTimer = setInterval(() => {
    lockRemaining.value -= 1
    if (lockRemaining.value <= 0) {
      // 归零自动解禁并收尾定时器（不留一个每秒钟空转的 interval）
      lockRemaining.value = 0
      stopLockCountdown()
    }
  }, 1000)
}

// 组件卸载（含登录成功跳走）必须清定时器：否则 interval 仍在改一个已经没人看的 ref，
// 且在 React/Vue 这类框架里都是典型的泄漏点
onUnmounted(() => {
  stopLockCountdown()
})

async function onSubmit() {
  if (locked.value || viewState.value === 'loading') return
  viewState.value = 'loading'
  errorText.value = ''
  try {
    const envelope = await adminLogin(username.value, password.value)
    const data = envelope.data || {}
    if (!data.token) {
      // 200 但没有 token 是后端契约外的形状：按失败如实报，绝不「当成功推进去」
      viewState.value = 'error'
      errorText.value = '登录返回异常，请重试'
      return
    }
    setStoredToken(data.token)
    // 口令只在本次请求的 body 里，不落脚本变量以外的任何地方：成功即清掉输入
    password.value = ''
    await router.replace('/overview')
  } catch (err) {
    const e = err || {}
    if (e.code === 403) {
      viewState.value = 'forbidden'
    } else {
      viewState.value = 'error'
    }
    errorText.value = e.message || '登录失败，请重试'
    const retryAfter = e.data && Number(e.data.retryAfterSeconds)
    if (retryAfter > 0) {
      startLockCountdown(retryAfter)
    } else {
      stopLockCountdown()
      lockRemaining.value = 0
    }
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-card {
  width: 360px;
  padding: 32px 28px 20px;
  background: #fff;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
}
.login-title {
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 4px;
}
.login-sub {
  font-size: 13px;
  color: #909399;
  margin: 0 0 20px;
}
.login-form :deep(.el-form-item) {
  margin-bottom: 16px;
}
.submit {
  width: 100%;
}
.error {
  color: #f56c6c;
  font-size: 13px;
  margin: 0;
}
.lock-tip {
  color: #e6a23c;
  font-size: 12px;
  margin: 6px 0 0;
}
</style>
