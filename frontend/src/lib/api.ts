import axios from 'axios'
import type { AxiosInstance, AxiosRequestConfig, InternalAxiosRequestConfig } from 'axios'

// ---------------------------------------------------------------------------
// Refresh queue — tránh race condition khi nhiều request cùng nhận 401
// ---------------------------------------------------------------------------
let isRefreshing = false
let failedQueue: Array<{
  resolve: (token: string) => void
  reject: (error: unknown) => void
}> = []

function processQueue(error: unknown, token: string | null = null): void {
  failedQueue.forEach(({ resolve, reject }) => {
    if (error) {
      reject(error)
    } else {
      resolve(token!)
    }
  })
  failedQueue = []
}

// ---------------------------------------------------------------------------
// Axios singleton instance
// ---------------------------------------------------------------------------
const api: AxiosInstance = axios.create({
  // VITE_API_BASE_URL nếu set thì dùng (ví dụ staging/prod).
  // Nếu không set → dùng '' (empty string) để tận dụng Vite proxy /api → localhost:8080
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
})

// ---------------------------------------------------------------------------
// Request interceptor — attach access token từ authStore
// ---------------------------------------------------------------------------
api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  // Import lazy để tránh circular dependency khi authStore cũng import api.
  // useAuthStore.getState() là cách đúng trong non-React context.
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const getState = (globalThis as any).__authStoreGetState as (() => { accessToken: string | null }) | undefined
  const token = getState?.()?.accessToken ?? null

  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// ---------------------------------------------------------------------------
// Response interceptor — xử lý 401 với 2 error code khác nhau
// ---------------------------------------------------------------------------
api.interceptors.response.use(
  (response) => response,
  async (error: unknown) => {
    if (!axios.isAxiosError(error)) {
      return Promise.reject(error)
    }

    const originalRequest = error.config as AxiosRequestConfig & { _retry?: boolean }
    const status = error.response?.status
    const errorCode = (error.response?.data as { error?: string } | undefined)?.error

    if (status === 401) {
      // -----------------------------------------------------------------------
      // CASE 1: Token hết hạn → thử refresh, rồi retry request gốc
      // -----------------------------------------------------------------------
      if (errorCode === 'AUTH_TOKEN_EXPIRED' && !originalRequest._retry) {
        if (isRefreshing) {
          // Đang có refresh chạy → queue request này, chờ refresh xong
          return new Promise<unknown>((resolve, reject) => {
            failedQueue.push({ resolve, reject })
          }).then((newToken) => {
            if (originalRequest.headers) {
              originalRequest.headers['Authorization'] = `Bearer ${newToken as string}`
            } else {
              originalRequest.headers = { Authorization: `Bearer ${newToken as string}` }
            }
            return api(originalRequest)
          })
        }

        originalRequest._retry = true
        isRefreshing = true

        try {
          // Lấy refreshToken từ store (non-React context)
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const getState = (globalThis as any).__authStoreGetState as
            | (() => { refreshToken: string | null; setAuth: (r: unknown) => void; clearAuth: () => void })
            | undefined

          const storeState = getState?.()
          const refreshToken = storeState?.refreshToken ?? null

          if (!refreshToken) {
            throw new Error('No refresh token available')
          }

          // Dùng axios thuần (KHÔNG dùng api instance) để tránh interceptor loop
          const { data } = await axios.post<{
            accessToken: string
            refreshToken: string
            tokenType: string
            expiresIn: number
            user: unknown
          }>('/api/auth/refresh', { refreshToken })

          // Cập nhật store với token mới
          storeState?.setAuth(data)

          processQueue(null, data.accessToken)

          // Retry request gốc với token mới
          if (originalRequest.headers) {
            originalRequest.headers['Authorization'] = `Bearer ${data.accessToken}`
          } else {
            originalRequest.headers = { Authorization: `Bearer ${data.accessToken}` }
          }
          return api(originalRequest)
        } catch (refreshError) {
          processQueue(refreshError, null)

          // Refresh fail → clear auth, redirect login
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const getState = (globalThis as any).__authStoreGetState as
            | (() => { clearAuth: () => void })
            | undefined
          getState?.()?.clearAuth()
          window.location.href = '/login'

          return Promise.reject(refreshError)
        } finally {
          isRefreshing = false
        }
      }

      // -----------------------------------------------------------------------
      // CASE 2: Không có token / token invalid → clear, redirect login
      // -----------------------------------------------------------------------
      if (errorCode === 'AUTH_REQUIRED') {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const getState = (globalThis as any).__authStoreGetState as
          | (() => { clearAuth: () => void })
          | undefined
        getState?.()?.clearAuth()
        window.location.href = '/login'
      }
    }

    return Promise.reject(error)
  }
)

export default api
