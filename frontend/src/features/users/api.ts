import api from '@/lib/api'
import type { UserSearchDto } from '@/types/conversation'
import type { UserDto } from '@/types/auth'

// ---------------------------------------------------------------------------
// GET /api/users/search?q=&limit=
// ---------------------------------------------------------------------------
export async function searchUsers(q: string, limit = 20): Promise<UserSearchDto[]> {
  const res = await api.get<UserSearchDto[]>('/api/users/search', {
    params: { q, limit },
  })
  return res.data
}

export async function blockUser(userId: string): Promise<void> {
  await api.post(`/api/users/${userId}/block`)
}

export async function unblockUser(userId: string): Promise<void> {
  await api.delete(`/api/users/${userId}/block`)
}

export async function getBlockedUsers(): Promise<UserDto[]> {
  const res = await api.get<{ items: UserDto[] }>('/api/users/blocked')
  return res.data.items
}

export interface UpdateProfilePayload {
  fullName?: string
  avatarUrl?: string | null
}

export interface ChangePasswordPayload {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export async function getCurrentUser(): Promise<UserDto> {
  const res = await api.get<UserDto>('/api/users/me')
  return res.data
}

export async function updateProfile(data: UpdateProfilePayload): Promise<UserDto> {
  const res = await api.patch<UserDto>('/api/users/me', data)
  return res.data
}

export async function changePassword(data: ChangePasswordPayload): Promise<void> {
  await api.post('/api/auth/change-password', data)
}
