import { useQuery } from '@tanstack/react-query'
import { userKeys } from '../conversations/queryKeys'
import { searchUsers } from './api'
import { useDebounce } from '@/hooks/useDebounce'

// ---------------------------------------------------------------------------
// useUserSearch — GET /api/users/search?q=&limit=
// Debounce 300ms, chỉ fire khi query >= 2 ký tự (khớp contract min 2 chars).
// ---------------------------------------------------------------------------
export function useUserSearch(query: string, limit = 20) {
  const debouncedQuery = useDebounce(query, 300)

  return useQuery({
    queryKey: userKeys.search(debouncedQuery),
    queryFn: () => searchUsers(debouncedQuery, limit),
    enabled: debouncedQuery.trim().length >= 2,
  })
}
