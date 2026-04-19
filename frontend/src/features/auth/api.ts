import api from '@/lib/api'
import type { RegisterRequest, LoginRequest, AuthResponse } from '@/types/auth'

export async function registerApi(data: RegisterRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>('/api/auth/register', data)
  return response.data
}

export async function loginApi(data: LoginRequest): Promise<AuthResponse> {
  const response = await api.post<AuthResponse>('/api/auth/login', data)
  return response.data
}
