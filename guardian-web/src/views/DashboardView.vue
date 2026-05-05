<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, shallowRef } from 'vue'
import { useRouter } from 'vue-router'
import { api, type DataLatestResponse } from '@/api/client'
import { wgs84ToGcj02 } from '@/utils/coords'
import { clearAll } from '@/utils/storage'

// 高德地图类型别名（用类型转换代替 declare，避免在 script setup 中使用 ambient declarations）
type AMapWindow = Window & { AMap?: any; [key: string]: any }
const aWindow = () => window as AMapWindow

const router         = useRouter()
const mapEl          = ref<HTMLDivElement | null>(null)
const map            = shallowRef<any>(null)
const marker         = shallowRef<any>(null)
const wardData       = ref<DataLatestResponse | null>(null)
const errorMsg       = ref('')
const isRefreshing   = ref(false)
const isUnbinding    = ref(false)
const lastRefreshed  = ref<Date | null>(null)
const amapReady      = ref(false)

const REFRESH_INTERVAL = 30_000   // 30 秒自动刷新
let   refreshTimer: ReturnType<typeof setInterval> | null = null

// ── 时间格式化 ────────────────────────────────────────────

function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '暂无记录'
  const diffMs  = Date.now() - new Date(iso).getTime()
  const diffMin = Math.round(diffMs / 60_000)
  if (diffMin <    1) return '刚刚'
  if (diffMin <   60) return `${diffMin} 分钟前`
  if (diffMin < 1440) return `${Math.floor(diffMin / 60)} 小时前`
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(new Date(iso))
}

// ── 高德地图 ──────────────────────────────────────────────

function loadAmapScript(): Promise<void> {
  return new Promise((resolve, reject) => {
    if (aWindow().AMap) { resolve(); return }

    const key = import.meta.env.VITE_AMAP_KEY
    if (!key) { resolve(); return }   // 无 key 时跳过地图

    const cbName = '__guardianAmapCb'
    aWindow()[cbName] = () => { resolve() }

    const script = document.createElement('script')
    script.src   = `https://webapi.amap.com/maps?v=2.0&key=${key}&callback=${cbName}`
    script.async = true
    script.onerror = reject
    document.head.appendChild(script)
  })
}

async function initMap() {
  await loadAmapScript()
  const AMap = aWindow().AMap
  if (!AMap || !mapEl.value) return

  map.value = new AMap.Map(mapEl.value, {
    zoom:         12,
    mapStyle:     'amap://styles/normal',
    resizeEnable: true,
    // 不设置初始 center，等待第一次数据加载后再定位到 ward 位置
  })
  amapReady.value = true
}

function updateMarker(lng: number, lat: number) {
  const AMap = aWindow().AMap
  if (!AMap || !map.value) return

  const [gcjLng, gcjLat] = wgs84ToGcj02(lng, lat)
  const pos = [gcjLng, gcjLat]

  if (!marker.value) {
    marker.value = new AMap.Marker({
      position:  pos,
      title:     '被监护者当前位置',
      animation: 'AMAP_ANIMATION_DROP',
      icon:      new AMap.Icon({
        size:    new AMap.Size(36, 36),
        image:   'https://webapi.amap.com/theme/v1.3/markers/n/mark_b.png',
        imageSize: new AMap.Size(36, 36),
      }),
    })
    map.value.add(marker.value)
  } else {
    marker.value.setPosition(pos)
  }
  map.value.setCenter(pos)
}

// ── 数据刷新 ─────────────────────────────────────────────

async function refresh() {
  if (isRefreshing.value) return
  isRefreshing.value = true
  errorMsg.value = ''
  try {
    const { data } = await api.getLatestData()
    wardData.value  = data
    lastRefreshed.value = new Date()
    updateMarker(data.longitude, data.latitude)
  } catch (e: any) {
    const status = e.response?.status
    if      (status === 404) errorMsg.value = '尚未找到被监护者数据，请确认已完成配对'
    else if (status === 403) errorMsg.value = '设备尚未配对'
    else                     errorMsg.value = '获取数据失败，请检查网络连接'
  } finally {
    isRefreshing.value = false
  }
}

function logout() {
  clearAll()
  router.replace('/setup')
}

async function unbind() {
  if (!confirm('确认解除配对？解绑后被监护者需重新生成配对码。')) return
  isUnbinding.value = true
  try {
    await api.unbindPairing()
    // 解绑成功：清除所有本地数据，回到注册页重新配对
    clearAll()
    router.replace('/setup')
  } catch {
    errorMsg.value = '解除配对失败，请重试'
  } finally {
    isUnbinding.value = false
  }
}

// ── 计算属性 ──────────────────────────────────────────────

const lastRefreshedText = computed(() =>
  lastRefreshed.value ? relativeTime(lastRefreshed.value.toISOString()) : '—',
)

const hasMapKey = computed(() => !!import.meta.env.VITE_AMAP_KEY)

// ── 生命周期 ─────────────────────────────────────────────

onMounted(async () => {
  await initMap()
  await refresh()
  refreshTimer = setInterval(refresh, REFRESH_INTERVAL)
})

onUnmounted(() => {
  if (refreshTimer) clearInterval(refreshTimer)
  map.value?.destroy()
})

// ── 内联子组件 InfoRow ───────────────────────────────────

import { defineComponent, h } from 'vue'

const InfoRow = defineComponent({
  name: 'InfoRow',
  props: {
    label: { type: String, required: true },
    value: { type: String, required: true },
    mono:  { type: Boolean, default: false },
  },
  setup(props) {
    return () => h('div', { class: 'info-row' }, [
      h('span', { class: 'info-label' }, props.label),
      h('span', { class: ['info-value', props.mono ? 'mono' : ''] }, props.value),
    ])
  },
})

// 根据 last_seen 时间判断在线状态
function statusClass(lastSeen: string): string {
  const diffMin = (Date.now() - new Date(lastSeen).getTime()) / 60_000
  if (diffMin <  30) return 'status-online'
  if (diffMin < 120) return 'status-idle'
  return 'status-offline'
}

function statusText(lastSeen: string): string {
  const diffMin = (Date.now() - new Date(lastSeen).getTime()) / 60_000
  if (diffMin <  30) return '在线（30 分钟内有数据）'
  if (diffMin < 120) return '空闲（超过 30 分钟未同步）'
  return '离线（超过 2 小时无数据）'
}
</script>

<template>
  <div class="dashboard">
    <!-- ── Header ── -->
    <header class="header">
      <div class="header-left">
        <svg width="28" height="28" viewBox="0 0 48 48" style="margin-right:10px;flex-shrink:0">
          <circle cx="24" cy="24" r="24" fill="rgba(255,255,255,0.2)" />
          <path d="M24 10 L36 17 L36 27 C36 33.6 30.6 39.6 24 42 C17.4 39.6 12 33.6 12 27 L12 17 Z"
                fill="white" fill-opacity="0.9"/>
        </svg>
        <span class="header-title">Guardian · 监护者控制台</span>
      </div>
      <div class="header-right">
        <span class="refresh-hint">{{ lastRefreshedText }} 更新</span>
        <button class="btn-header" :disabled="isRefreshing" @click="refresh">
          <span v-if="isRefreshing" class="spin-sm" />
          <template v-else>↻</template>
          {{ isRefreshing ? '刷新中' : '刷新' }}
        </button>
        <button class="btn-header btn-unbind" :disabled="isUnbinding" @click="unbind">
          {{ isUnbinding ? '解绑中…' : '解除配对' }}
        </button>
        <button class="btn-header btn-logout" @click="logout">退出</button>
      </div>
    </header>

    <!-- ── 主体 ── -->
    <div class="body">
      <!-- 左侧信息面板 -->
      <aside class="panel">
        <h2 class="panel-heading">被监护者状态</h2>

        <!-- 有数据 -->
        <template v-if="wardData">
          <div class="info-card">
            <InfoRow label="设备 ID" :value="wardData.ward_uuid.slice(0, 8) + '…'" mono />
            <InfoRow label="最后使用手机" :value="relativeTime(wardData.last_used_at)" />
            <InfoRow label="数据同步时间" :value="relativeTime(wardData.last_seen)" />
            <InfoRow
              label="坐标（WGS-84）"
              :value="`${wardData.latitude.toFixed(5)}, ${wardData.longitude.toFixed(5)}`"
              mono
            />
          </div>

          <!-- 在线状态指示 -->
          <div class="status-bar" :class="statusClass(wardData.last_seen)">
            <span class="status-dot" />
            {{ statusText(wardData.last_seen) }}
          </div>
        </template>

        <!-- 加载中 -->
        <div v-else-if="isRefreshing" class="empty-state">
          <span class="spin-lg" />
          <p>正在获取数据…</p>
        </div>

        <!-- 无数据 / 错误 -->
        <div v-else class="empty-state">
          <p class="empty-icon">📡</p>
          <p>{{ errorMsg || '暂无数据' }}</p>
        </div>

        <!-- 错误横幅 -->
        <div v-if="errorMsg && wardData" class="error-banner">⚠ {{ errorMsg }}</div>

        <!-- 自动刷新说明 -->
        <p class="footer-hint">每 30 秒自动刷新 · 上次：{{ lastRefreshedText }}</p>
      </aside>

      <!-- 高德地图 -->
      <main class="map-wrapper">
        <div ref="mapEl" class="map" />

        <!-- 无 API Key 时的占位提示 -->
        <div v-if="!hasMapKey" class="map-placeholder">
          <p class="placeholder-icon">🗺️</p>
          <p class="placeholder-title">地图未启用</p>
          <p class="placeholder-hint">
            复制 <code>.env.example</code> 为 <code>.env.local</code>，<br />
            填入高德地图 API Key 后重启 dev server
          </p>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* ── 整体布局 ── */
.dashboard {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f0f4f8;
}

/* ── Header ── */
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 54px;
  background: linear-gradient(90deg, #1565c0, #1976d2);
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 2px 8px rgba(21, 101, 192, 0.3);
}

.header-left  { display: flex; align-items: center; }
.header-title { font-size: 15px; font-weight: 600; }

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.refresh-hint {
  font-size: 12px;
  color: rgba(255,255,255,0.6);
}

.btn-header {
  padding: 5px 12px;
  background: rgba(255,255,255,0.15);
  color: #fff;
  border: 1px solid rgba(255,255,255,0.25);
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 5px;
  transition: background 0.15s;
}

.btn-header:hover:not(:disabled) { background: rgba(255,255,255,0.25); }
.btn-header:disabled { opacity: 0.55; cursor: not-allowed; }
.btn-unbind { background: rgba(245,124,0,0.25); border-color: rgba(245,124,0,0.4); }
.btn-unbind:hover:not(:disabled) { background: rgba(245,124,0,0.45) !important; }
.btn-logout { background: rgba(229,57,53,0.3); border-color: rgba(229,57,53,0.4); }
.btn-logout:hover { background: rgba(229,57,53,0.5) !important; }

/* ── 主体 ── */
.body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

/* ── 左侧面板 ── */
.panel {
  width: 290px;
  flex-shrink: 0;
  background: #fff;
  border-right: 1px solid #e3e8ef;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.panel-heading {
  font-size: 13px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  color: #90a4ae;
  margin: 0;
}

.info-card {
  background: #f8fafc;
  border: 1px solid #e3e8ef;
  border-radius: 10px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

:deep(.info-row) {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

:deep(.info-label) {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.6px;
  color: #b0bec5;
}

:deep(.info-value) {
  font-size: 14px;
  color: #263238;
  word-break: break-all;
}

:deep(.mono) {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 12px;
}

/* 状态指示条 */
.status-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.status-online  { background: #e8f5e9; color: #2e7d32; }
.status-online  .status-dot { background: #4caf50; animation: pulse 2s infinite; }
.status-idle    { background: #fff8e1; color: #f57f17; }
.status-idle    .status-dot { background: #ffb300; }
.status-offline { background: #ffebee; color: #c62828; }
.status-offline .status-dot { background: #ef5350; }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50%       { opacity: 0.4; }
}

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px 0;
  color: #90a4ae;
  font-size: 13px;
  text-align: center;
}

.empty-state p { margin: 0; }
.empty-icon { font-size: 32px; }

/* 错误横幅 */
.error-banner {
  background: #fff3f3;
  color: #c62828;
  font-size: 12px;
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid #ffcdd2;
  line-height: 1.5;
}

.footer-hint {
  margin-top: auto;
  font-size: 11px;
  color: #cfd8dc;
  line-height: 1.6;
}

/* ── 地图区域 ── */
.map-wrapper {
  flex: 1;
  position: relative;
  overflow: hidden;
}

.map {
  width: 100%;
  height: 100%;
}

.map-placeholder {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: #e8ecef;
  gap: 8px;
}

.placeholder-icon  { font-size: 48px; margin: 0; }
.placeholder-title { font-size: 18px; font-weight: 600; color: #546e7a; margin: 0; }
.placeholder-hint  { font-size: 13px; color: #78909c; text-align: center; margin: 0; line-height: 1.7; }
.placeholder-hint code {
  background: #cfd8dc;
  padding: 1px 6px;
  border-radius: 4px;
  font-family: monospace;
}

/* ── 加载动画 ── */
.spin-sm, .spin-lg {
  display: inline-block;
  border: 2px solid rgba(255,255,255,0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
.spin-sm { width: 12px; height: 12px; }
.spin-lg {
  width: 28px;
  height: 28px;
  border-color: #e0e0e0;
  border-top-color: #1976d2;
}

@keyframes spin { to { transform: rotate(360deg); } }
</style>
