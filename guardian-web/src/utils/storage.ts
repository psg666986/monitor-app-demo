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

/** 清除认证状态（退出登录 / token 失效） */
export function clearAuth(): void {
  localStorage.removeItem(KEY_TOKEN)
}
