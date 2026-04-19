// ---------------------------------------------------------------------------
// Auth types — generated from docs/API_CONTRACT.md
// Mọi thay đổi contract phải update file này trước.
// ---------------------------------------------------------------------------

export interface RegisterRequest {
  email: string
  username: string
  password: string
  fullName: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface UserDto {
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
  user: UserDto
}

// OAuth response — extends AuthResponse với isNewUser flag
export interface OAuthResponse extends AuthResponse {
  isNewUser: boolean
}

// API error shape từ backend (chuẩn toàn hệ thống)
export interface ApiError {
  error: string
  message: string
  timestamp: string
  details?: {
    fields?: Record<string, string>
    retryAfterSeconds?: number
  }
}
