// ---------------------------------------------------------------------------
// tokenStorage — module trung gian phá circular dependency api.ts ↔ authStore.ts
//
// Quy tắc cứng: KHÔNG import api.ts ở đây (sẽ tạo lại circular dep).
// Module này là in-memory cache cho token, không persist gì ra localStorage.
//
// Sync flow:
//   authStore.setAuth()   → tokenStorage.setTokens()   (store → cache)
//   authStore.clearAuth() → tokenStorage.clear()        (store → cache)
//   api.ts interceptor    → tokenStorage.getAccessToken() (cache → axios header)
//   api.ts refresh        → tokenStorage.setTokens()    (server → cache, sau đó store.setAuth sẽ ghi đè)
// ---------------------------------------------------------------------------

let accessToken: string | null = null
let refreshToken: string | null = null

export const tokenStorage = {
  getAccessToken: (): string | null => accessToken,
  setAccessToken: (token: string | null): void => {
    accessToken = token
  },

  getRefreshToken: (): string | null => refreshToken,
  setRefreshToken: (token: string | null): void => {
    refreshToken = token
  },

  /** Ghi cả 2 token cùng lúc — dùng trong setAuth để tránh async gap */
  setTokens: (access: string, refresh: string): void => {
    accessToken = access
    refreshToken = refresh
  },

  clear: (): void => {
    accessToken = null
    refreshToken = null
  },
}
