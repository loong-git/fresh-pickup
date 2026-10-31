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
import { ElMessage } from 'element-plus'
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
  // P4-12 D4：空表单不发请求（白耗 5次/60s 的登录桶），也不改 viewState（保持 ready、不动锁定倒计时）。
  const nameText = String(username.value == null ? '' : username.value).trim()
  const passText = String(password.value == null ? '' : password.value).trim()
  if (!nameText || !passText) {
    ElMessage.error('请输入用户名和密码')
    return
  }
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
/* 「暗色控制台」登录页（八期 P8-1，docs/八期规划/00-总纲.md §0 tokens）：近黑蓝渐变整幅 + 暗卡浮起 + 蓝色记忆点。
   .console-content 固定 20px 内边距，负外边距把渐变拉成满幅；多背景一层蓝光晕、一层金色微光点题。 */
.login-wrap {
  margin: -20px;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(880px 460px at 78% 16%, rgba(59, 130, 246, 0.16), transparent 62%),
    radial-gradient(640px 380px at 12% 88%, rgba(231, 200, 104, 0.08), transparent 58%),
    linear-gradient(150deg, #05070D 0%, #0E1B33 42%, #16305C 100%);
}
.login-card {
  position: relative;
  width: 380px;
  padding: 36px 32px 24px;
  background: #1B1B1D;
  border: 1px solid #2A2A2D;
  border-radius: 12px;
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.5), 0 2px 8px rgba(0, 0, 0, 0.35);
  overflow: hidden;
}
/* 卡顶一道品牌色条（纯装饰） */
.login-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: linear-gradient(90deg, #3B82F6, #6EA8FE);
}
.login-title {
  display: flex;
  align-items: center;
  font-size: 20px;
  font-weight: 700;
  color: #F2F2F2;
  margin: 0 0 4px;
}
/* 标题后的蓝色圆点：呼应品牌「·鲜」蓝字缀（纯装饰，无文字内容） */
.login-title::after {
  content: '';
  width: 8px;
  height: 8px;
  margin-left: 8px;
  border-radius: 50%;
  background: #3B82F6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.25);
}
.login-sub {
  font-size: 13px;
  color: #A6A6AA;
  margin: 0 0 20px;
}
.login-form :deep(.el-form-item) {
  margin-bottom: 16px;
}
.submit {
  width: 100%;
  height: 40px;
  font-weight: 600;
  letter-spacing: 4px;
}
.error {
  color: #EF4444;
  font-size: 13px;
  margin: 0;
}
.lock-tip {
  color: #D97706;
  font-size: 12px;
  margin: 6px 0 0;
}
</style>
