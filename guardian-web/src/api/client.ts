import axios from 'axios'
import { getToken, clearAuth, isTokenExpired } from '@/utils/storage'
import router from '@/router'

// 开发环境：baseURL = '/api'，由 vite.config.ts proxy 转发到后端
// 生产环境：设置 VITE_API_BASE_URL 指向后端完整地址
const client = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 15_000,
})

// 请求拦截：自动附加 Bearer token；发请求前检测 token 是否已过期
client.interceptors.request.use((config) => {
  const token = getToken()
  if (!token) return config
  if (isTokenExpired()) {
    // token 已过期，清除并跳转重新注册（不发出请求）
    clearAuth()
    router.replace('/setup')
    return Promise.reject(new Error('token expired'))
  }
  config.headers.Authorization = `Bearer ${token}`
  return config
})

// 响应拦截：服务端 401 时自动清除并跳转
client.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      clearAuth()
      router.replace('/setup')
    }
    return Promise.reject(err)
  },
)

// ── 类型定义 ──────────────────────────────────────────────

export interface DeviceResponse {
  uuid: string
  role: string
  latitude: number
  longitude: number
  last_seen: string
  last_used_at: string | null
}

export interface RegisterResponse {
  device: DeviceResponse
  token: string
}

export interface BindingResponse {
  guardian_id: string
  ward_id: string
  created_at: string
}

export interface PairingStatusResponse {
  paired: boolean
  binding: BindingResponse | null
}

export interface DataLatestResponse {
  ward_uuid: string
  latitude: number
  longitude: number
  last_seen: string
  last_used_at: string | null
}

export interface MessageResponse {
  message: string
}

// ── API 调用 ──────────────────────────────────────────────

export const api = {
  /** 注册设备，返回 JWT token */
  register(uuid: string, role: 'guardian' | 'ward') {
    return client.post<RegisterResponse>('/devices/register', { uuid, role })
  },

  /** 监护者输入配对码完成绑定 */
  confirmPairing(pairingCode: string) {
    return client.post<BindingResponse>('/pair/confirm', { pairing_code: pairingCode })
  },

  /** 查询当前设备的配对状态 */
  getPairingStatus() {
    return client.get<PairingStatusResponse>('/pair/status')
  },

  /** 获取被监护者最新位置和使用状态 */
  getLatestData() {
    return client.get<DataLatestResponse>('/data/latest')
  },

  /** 解除配对关系（任意一方均可调用，幂等） */
  unbindPairing() {
    return client.delete<MessageResponse>('/pair/binding')
  },
}

export default client
