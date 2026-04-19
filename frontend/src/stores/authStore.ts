import { create } from 'zustand'
import { persist, createJSONStorage } from 'zustand/middleware'

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

      setAuth: (authResponse: AuthResponse) =>
        set({
          accessToken: authResponse.accessToken,
          refreshToken: authResponse.refreshToken,
          user: authResponse.user,
        }),

      clearAuth: () =>
        set({
          accessToken: null,
          refreshToken: null,
          user: null,
        }),

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
        state?.setHydrated()
      },
    }
  )
)

// ---------------------------------------------------------------------------
// Wire vào globalThis để api.ts có thể đọc store mà không bị circular dep
// ---------------------------------------------------------------------------
// Đặt ngay khi module load. api.ts đọc qua __authStoreGetState thay vì
// import trực tiếp, phá vỡ vòng circular: api → authStore → api.
// eslint-disable-next-line @typescript-eslint/no-explicit-any
;(globalThis as any).__authStoreGetState = useAuthStore.getState.bind(useAuthStore)

// Computed helper (dùng bên ngoài React component)
export function getIsAuthenticated(): boolean {
  return !!useAuthStore.getState().accessToken
}
