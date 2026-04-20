import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'
import { tokenStorage } from '@/lib/tokenStorage'
import { timerRegistry } from '@/features/messages/timerRegistry'
import { editTimerRegistry } from '@/features/messages/editTimerRegistry'
import { deleteTimerRegistry } from '@/features/messages/deleteTimerRegistry'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
export interface User {
  id: string
  username: string
  email: string
  fullName: string
  avatarUrl: string | null
}

export interface AuthResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: User
}

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: User | null
  /** true sau khi zustand persist đã hydrate xong từ localStorage */
  isHydrated: boolean

  // Actions
  setAuth: (authResponse: AuthResponse) => void
  clearAuth: () => void
  updateUser: (userData: Partial<User>) => void
  setHydrated: () => void
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------
export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      isHydrated: false,

      setAuth: (authResponse: AuthResponse) => {
        // Sync tokenStorage TRƯỚC khi set store để axios interceptor
        // luôn đọc được token mới ngay lập tức (không có async gap)
        tokenStorage.setTokens(authResponse.accessToken, authResponse.refreshToken)
        set({
          accessToken: authResponse.accessToken,
          refreshToken: authResponse.refreshToken,
          user: authResponse.user,
        })
      },

      clearAuth: () => {
        // Clear pending send + edit + delete timers để tránh stale callbacks sau logout
        timerRegistry.clearAll()
        editTimerRegistry.clearAll()
        deleteTimerRegistry.clearAll()
        // Sync tokenStorage cùng lúc để interceptor không dùng token cũ
        tokenStorage.clear()
        set({
          accessToken: null,
          refreshToken: null,
          user: null,
        })
      },

      updateUser: (userData: Partial<User>) =>
        set((state) => ({
          user: state.user ? { ...state.user, ...userData } : null,
        })),

      setHydrated: () => set({ isHydrated: true }),
    }),
    {
      name: 'chat-auth',
      storage: createJSONStorage(() => localStorage),
      // CHỈ persist refreshToken + user.
      // accessToken (15 phút TTL) KHÔNG persist — sẽ refresh lại khi cần.
      partialize: (state) => ({
        refreshToken: state.refreshToken,
        user: state.user,
      }),
      onRehydrateStorage: () => (state) => {
        // Khi hydrate xong, sync refreshToken vào tokenStorage
        // để interceptor có thể dùng ngay nếu cần refresh sớm
        if (state?.refreshToken) {
          tokenStorage.setRefreshToken(state.refreshToken)
        }
        state?.setHydrated()
      },
    }
  )
)

// Computed helper (dùng bên ngoài React component)
export function getIsAuthenticated(): boolean {
  return !!useAuthStore.getState().accessToken
}
