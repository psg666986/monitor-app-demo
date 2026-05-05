<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '@/api/client'
import { getDeviceUuid, getToken, setToken } from '@/utils/storage'

type Step = 'loading' | 'register' | 'pair'

const router  = useRouter()
const step    = ref<Step>('loading')
const code    = ref('')
const error   = ref('')
const loading = ref(false)

// ── 初始化：判断当前应展示哪一步 ─────────────────────────

onMounted(async () => {
  if (!getToken()) {
    step.value = 'register'
    return
  }
  // 有 token → 检查是否已配对
  try {
    const { data } = await api.getPairingStatus()
    if (data.paired) router.replace('/')
    else step.value = 'pair'
  } catch {
    step.value = 'pair'
  }
})

// ── Step 1：注册为监护者 ──────────────────────────────────

async function register() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await api.register(getDeviceUuid(), 'guardian')
    setToken(data.token)
    step.value = 'pair'
  } catch {
    error.value = '注册失败，请检查与后端的网络连接'
  } finally {
    loading.value = false
  }
}

// ── Step 2：输入配对码完成绑定 ───────────────────────────

async function confirmPairing() {
  if (code.value.length !== 6) {
    error.value = '请输入 6 位数字配对码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await api.confirmPairing(code.value)
    router.replace('/')
  } catch (e: any) {
    const status = e.response?.status
    if (status === 422)      error.value = '配对码无效或已过期，请在被监护者手机上重新生成'
    else if (status === 429) error.value = '操作过于频繁，请稍候再试'
    else if (status === 409) error.value = '该设备已与另一位监护者配对'
    else                     error.value = '网络错误，请检查连接后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <!-- Logo / 标题 -->
      <div class="logo">
        <svg width="48" height="48" viewBox="0 0 48 48" fill="none">
          <circle cx="24" cy="24" r="24" fill="#1976d2" />
          <path d="M24 10 L36 17 L36 27 C36 33.6 30.6 39.6 24 42 C17.4 39.6 12 33.6 12 27 L12 17 Z"
                fill="white" fill-opacity="0.9"/>
        </svg>
      </div>
      <h1 class="title">Guardian 守护</h1>
      <p class="subtitle">监护者 Web 控制台</p>

      <!-- 加载中 -->
      <div v-if="step === 'loading'" class="status-text">正在检查登录状态…</div>

      <!-- Step 1：注册 -->
      <template v-else-if="step === 'register'">
        <p class="desc">首次使用，请将本浏览器注册为监护者设备。</p>
        <button class="btn-primary" :disabled="loading" @click="register">
          <span v-if="loading" class="spinner" />
          {{ loading ? '注册中…' : '注册为监护者' }}
        </button>
      </template>

      <!-- Step 2：配对 -->
      <template v-else-if="step === 'pair'">
        <p class="desc">
          在被监护者手机上点击「生成配对码」，将 6 位数字码输入到下方完成配对。
        </p>
        <div class="input-row">
          <input
            v-model="code"
            class="code-input"
            type="text"
            inputmode="numeric"
            maxlength="6"
            placeholder="● ● ● ● ● ●"
            autocomplete="off"
            @keyup.enter="confirmPairing"
          />
        </div>
        <button class="btn-primary" :disabled="loading || code.length !== 6" @click="confirmPairing">
          <span v-if="loading" class="spinner" />
          {{ loading ? '配对中…' : '确认配对' }}
        </button>
      </template>

      <!-- 错误提示 -->
      <p v-if="error" class="error-msg">{{ error }}</p>

      <!-- 步骤指示 -->
      <div v-if="step !== 'loading'" class="steps">
        <span :class="['step-dot', step === 'register' ? 'active' : 'done']" />
        <span class="step-line" />
        <span :class="['step-dot', step === 'pair' ? 'active' : '']" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #e3f2fd 0%, #f0f4f8 100%);
}

.card {
  background: #fff;
  border-radius: 16px;
  padding: 48px 40px 40px;
  width: 380px;
  box-shadow: 0 8px 40px rgba(25, 118, 210, 0.12);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}

.logo { margin-bottom: 4px; }

.title {
  font-size: 26px;
  font-weight: 700;
  color: #0d47a1;
  margin: 0;
}

.subtitle {
  font-size: 13px;
  color: #90a4ae;
  margin: 0;
  letter-spacing: 0.3px;
}

.status-text {
  color: #90a4ae;
  font-size: 14px;
  padding: 12px 0;
}

.desc {
  font-size: 14px;
  color: #546e7a;
  line-height: 1.7;
  text-align: center;
  margin: 4px 0 0;
}

.input-row {
  width: 100%;
}

.code-input {
  width: 100%;
  padding: 14px 16px;
  font-size: 28px;
  letter-spacing: 12px;
  text-align: center;
  border: 2px solid #e0e0e0;
  border-radius: 10px;
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s;
  box-sizing: border-box;
  color: #1a237e;
}

.code-input:focus {
  border-color: #1976d2;
  box-shadow: 0 0 0 3px rgba(25, 118, 210, 0.15);
}

.btn-primary {
  width: 100%;
  padding: 14px;
  background: #1976d2;
  color: #fff;
  border: none;
  border-radius: 10px;
  font-size: 16px;
  font-weight: 600;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition: background 0.2s, transform 0.1s;
}

.btn-primary:hover:not(:disabled) {
  background: #1565c0;
  transform: translateY(-1px);
}

.btn-primary:active:not(:disabled) {
  transform: translateY(0);
}

.btn-primary:disabled {
  background: #90b8e0;
  cursor: not-allowed;
  transform: none;
}

.error-msg {
  color: #e53935;
  font-size: 13px;
  text-align: center;
  margin: 0;
  line-height: 1.5;
}

/* 步骤指示器 */
.steps {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
}

.step-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #e0e0e0;
  transition: background 0.3s;
}

.step-dot.active { background: #1976d2; }
.step-dot.done   { background: #4caf50; }

.step-line {
  width: 32px;
  height: 2px;
  background: #e0e0e0;
}

/* 加载旋转圈 */
.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255,255,255,0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
  flex-shrink: 0;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
