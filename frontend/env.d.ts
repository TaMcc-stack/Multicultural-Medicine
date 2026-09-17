/// <reference types="vite/client" />

// 自定义环境变量类型声明（供 Netlify 上配置 VITE_API_BASE 指向已部署后端）
interface ImportMetaEnv {
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
