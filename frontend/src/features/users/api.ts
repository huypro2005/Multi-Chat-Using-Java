import api from '@/lib/api'
import type { UserSearchDto } from '@/types/conversation'

// ---------------------------------------------------------------------------
// GET /api/users/search?q=&limit=
// ---------------------------------------------------------------------------
export async function searchUsers(q: string, limit = 20): Promise<UserSearchDto[]> {
  const res = await api.get<UserSearchDto[]>('/api/users/search', {
    params: { q, limit },
  })
  return res.data
}
