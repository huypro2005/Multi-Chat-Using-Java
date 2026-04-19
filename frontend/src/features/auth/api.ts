import api from '@/lib/api'
import type { RegisterRequest, LoginRequest, AuthResponse, OAuthResponse } from '@/types/auth'

export async function registerApi(data: RegisterRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>('/api/auth/register', data)
  return response.data
}

export async function loginApi(data: LoginRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>('/api/auth/login', data)
  return response.data
}

export async function oauthApi(data: { firebaseIdToken: string }): Promise<OAuthResponse> {
  const response = await api.post<OAuthResponse>('/api/auth/oauth', data)
  return response.data
}

export async function logoutApi(data: { refreshToken: string }): Promise<void> {
  await api.post('/api/auth/logout', data)
}
