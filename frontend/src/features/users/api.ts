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
