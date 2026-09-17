import axios from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'

/**
 * axios 实例：
 * - baseURL 默认 /api（开发环境按路径分流：/api/auth、/api/conversations、/api/qa、/api/kb → Spring Boot 8080；其余 /api → FastAPI 8000）
 * - 若配置了 VITE_API_BASE（如 Netlify 上指向已部署后端），则 /api/* 转发到该地址
 * - 请求自动携带 Authorization: Bearer <token>
 * - 响应解包规则：
 *    1) 形如 { code, message, data } 的 Spring Boot 信封 → 校验 code===0，成功返回 data，失败提示 message 并 reject
 *    2) 其他（FastAPI 直接返回业务数据 / 数组 / 裸对象）→ 原样透出
 * - 出错时优先取 message，其次 detail，统一提示并 reject
 * - 401 时清除本地登录态并跳转登录页
 */
const instance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api',
  // 大模型（Qwen3-14B）生成回答较慢，需放宽超时，避免 15s 就被中断导致「生成失败」
  timeout: 120000,
})

instance.interceptors.request.use((config) => {
  const token = useUserStore().token
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function detailMessage(data: unknown): string {
  if (data && typeof data === 'object') {
    const obj = data as Record<string, unknown>
    // FastAPI 用 detail；Spring Boot 用 message
    const detail = obj.detail ?? obj.message
    if (typeof detail === 'string') return detail
  }
  return '网络异常，请稍后重试'
}

function isApiEnvelope(body: unknown): body is { code: number; message: string; data: unknown } {
  return (
    !!body &&
    typeof body === 'object' &&
    !Array.isArray(body) &&
    typeof (body as Record<string, unknown>).code === 'number' &&
    'message' in body &&
    'data' in body
  )
}

instance.interceptors.response.use(
  (response) => {
    const body = response.data
    // Spring Boot 业务信封 {code,message,data}：解包 data；code!=0 视为失败
    if (isApiEnvelope(body)) {
      if (body.code === 0) return body.data
      const msg = body.message || '请求失败'
      ElMessage.error(msg)
      return Promise.reject(new Error(msg))
    }
    // 其他（FastAPI 直接返回业务数据 / 数组 / 裸对象）原样透出
    return body
  },
  (error) => {
    const status = error.response?.status
    const message = detailMessage(error.response?.data)
    if (status === 401) {
      handleUnauthorized()
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(new Error(message))
  },
)

function handleUnauthorized() {
  const userStore = useUserStore()
  if (userStore.token) {
    ElMessage.warning('登录状态已失效，请重新登录')
  }
  userStore.clearLocal()
  router.push({ name: 'login' })
}

/** 拦截器已解包出响应体，这里对调用侧暴露解包后的泛型类型 */
const request = {
  get<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.get(url, config) as unknown as Promise<T>
  },
  post<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.post(url, data, config) as unknown as Promise<T>
  },
  put<T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return instance.put(url, data, config) as unknown as Promise<T>
  },
  delete<T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T> {
    return instance.delete(url, config) as unknown as Promise<T>
  },
}

export default request
