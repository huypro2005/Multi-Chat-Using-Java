import axios from 'axios'
import { tokenStorage } from '@/lib/tokenStorage'
import { useAuthStore } from '@/stores/authStore'
import type { AuthResponse } from '@/stores/authStore'

// ---------------------------------------------------------------------------
// rawAxios — instance riêng, KHÔNG có interceptors từ api.ts
// Lý do: api.ts interceptor catch 401 → gọi /refresh → nếu init() cũng dùng
// api instance thì tạo loop (init → refresh 401 → interceptor refresh lại → ...)
// ---------------------------------------------------------------------------
const rawAxios = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 10_000,
  headers: { 'Content-Type': 'application/json' },
})

export const authService = {
  /**
   * Gọi khi app khởi động (App.tsx useEffect), TRƯỚC khi routes render.
   *
   * Logic:
   *  - Không có refreshToken → user chưa login → trả { isAuthenticated: false }
   *  - Có accessToken (in-memory) → session còn valid → trả { isAuthenticated: true }
   *  - Có refreshToken nhưng không có accessToken (tình huống sau reload) →
   *    call /api/auth/refresh để lấy accessToken mới → trả { isAuthenticated: true }
   *  - Refresh fail (token expired, revoked, network) → clearAuth() → trả { isAuthenticated: false }
   */
  async init(): Promise<{ isAuthenticated: boolean }> {
    const refreshToken = tokenStorage.getRefreshToken()
    const accessToken = tokenStorage.getAccessToken()

    // Case 1: không có refreshToken → user chưa login
    if (!refreshToken) {
      return { isAuthenticated: false }
    }

    // Case 2: có cả 2 token trong memory → session đã sẵn sàng
    if (accessToken) {
      return { isAuthenticated: true }
    }

    // Case 3: có refreshToken nhưng accessToken = null (sau reload)
    // → gọi /refresh để restore accessToken vào memory
    try {
      const { data } = await rawAxios.post<AuthResponse>('/api/auth/refresh', {
        refreshToken,
      })

      // setAuth() sync cả authStore lẫn tokenStorage (theo ADR-003)
      useAuthStore.getState().setAuth(data)

      return { isAuthenticated: true }
    } catch (err) {
      // Refresh fail: expired token, revoked, server down, network error
      // → clear auth state, user sẽ phải login lại
      console.warn('[authService.init] Refresh failed:', err)
      useAuthStore.getState().clearAuth()
      return { isAuthenticated: false }
    }
  },
}
