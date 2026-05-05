/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 高德地图 JS API Key */
  readonly VITE_AMAP_KEY: string
  /** 后端基础 URL（生产环境使用），开发环境由 vite proxy 处理 */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
