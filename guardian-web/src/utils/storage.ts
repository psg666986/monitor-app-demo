const KEY_UUID  = 'guardian_uuid'
const KEY_TOKEN = 'guardian_token'

/**
 * 获取（或懒生成）本机设备 UUID，持久化到 localStorage。
 * 使用浏览器原生 crypto.randomUUID()，保证唯一性。
 */
export function getDeviceUuid(): string {
  let uuid = localStorage.getItem(KEY_UUID)
  if (!uuid) {
    uuid = crypto.randomUUID()
    localStorage.setItem(KEY_UUID, uuid)
  }
  return uuid
}

/** 读取 JWT token */
export function getToken(): string | null {
  return localStorage.getItem(KEY_TOKEN)
}

/** 保存 JWT token */
export function setToken(token: string): void {
  localStorage.setItem(KEY_TOKEN, token)
}

/**
 * 清除 token（保留 UUID）。
 * 用于 401 自动跳转场景：设备身份保留，重新走注册流程即可续期。
 */
export function clearAuth(): void {
  localStorage.removeItem(KEY_TOKEN)
}

/**
 * 完整重置：同时清除 token 和 UUID。
 * 用于用户主动解除配对 / 退出登录场景；下次进入将重新生成设备身份。
 */
export function clearAll(): void {
  localStorage.removeItem(KEY_TOKEN)
  localStorage.removeItem(KEY_UUID)
}

/**
 * 解码 JWT payload，检查 `exp` 字段是否已过期。
 * 无 token 或解码失败视为已过期。
 */
export function isTokenExpired(): boolean {
  const token = getToken()
  if (!token) return true
  try {
    const parts = token.split('.')
    if (parts.length < 2) return true
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')))
    return Math.floor(Date.now() / 1000) >= payload.exp
  } catch {
    return true
  }
}
