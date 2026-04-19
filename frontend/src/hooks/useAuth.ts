import { useAuthStore } from '@/stores/authStore'
import type { User } from '@/stores/authStore'

interface UseAuthReturn {
  user: User | null
  isAuthenticated: boolean
  isHydrated: boolean
  logout: () => void
}

/**
 * Hook truy cập auth state và actions.
 *
 * - `isHydrated`: true khi persist middleware đã load xong từ localStorage.
 *   Dùng để tránh flash redirect trước khi biết user đã login hay chưa.
 * - `logout`: clear store (+ Tuần 2 sẽ call /api/auth/logout thật).
 * - `login` / `register` → sẽ implement Tuần 2 khi API sẵn sàng.
 */
export function useAuth(): UseAuthReturn {
  const user = useAuthStore((s) => s.user)
  const accessToken = useAuthStore((s) => s.accessToken)
  const isHydrated = useAuthStore((s) => s.isHydrated)
  const clearAuth = useAuthStore((s) => s.clearAuth)

  const isAuthenticated = !!accessToken

  const logout = (): void => {
    clearAuth()
    // TODO Tuần 2: call /api/auth/logout (blacklist refreshToken ở BE)
    // await api.post('/api/auth/logout')
  }

  return {
    user,
    isAuthenticated,
    isHydrated,
    logout,
  }
}
