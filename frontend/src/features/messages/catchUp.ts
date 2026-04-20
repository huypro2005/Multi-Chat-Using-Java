// ---------------------------------------------------------------------------
// catchUpMissedMessages — gọi REST để lấy messages bị miss khi offline/reconnect
//
// Được gọi từ useConvSubscription khi STOMP reconnect (không phải lần connect đầu).
// Strategy: lấy timestamp mới nhất trong cache, GET /messages?after=<ts>&limit=100,
// merge vào cache (dedup theo id), invalidate conversations sidebar.
//
// Fallback: nếu REST fail → invalidateQueries để React Query refetch toàn bộ.
// ---------------------------------------------------------------------------

import api from '@/lib/api'
import type { QueryClient } from '@tanstack/react-query'
import { messageKeys } from '@/features/conversations/queryKeys'
import type { MessageListResponse } from '@/types/message'

export async function catchUpMissedMessages(
  queryClient: QueryClient,
  conversationId: string,
): Promise<void> {
  const queryKey = messageKeys.all(conversationId)
  const cached = queryClient.getQueryData<{
    pages: MessageListResponse[]
    pageParams: unknown[]
  }>(queryKey)

  if (!cached) return

  const allMessages = cached.pages.flatMap((p) => p.items)
  if (allMessages.length === 0) return

  // Lấy timestamp của message mới nhất trong cache
  const newestTs = Math.max(...allMessages.map((m) => new Date(m.createdAt).getTime()))
  const afterIso = new Date(newestTs).toISOString()

  try {
    const response = await api.get<MessageListResponse>(
      `/api/conversations/${conversationId}/messages`,
      { params: { after: afterIso, limit: 100 } },
    )

    const incoming = response.data.items
    if (incoming.length === 0) return

    queryClient.setQueryData(queryKey, (old: typeof cached | undefined) => {
      if (!old) return old
      const existingIds = new Set(old.pages.flatMap((p) => p.items).map((m) => m.id))
      const newOnes = incoming.filter((m) => !existingIds.has(m.id))
      if (newOnes.length === 0) return old

      // Append vào page đầu (newest window)
      const pages = [...old.pages]
      pages[0] = { ...pages[0], items: [...pages[0].items, ...newOnes] }
      return { ...old, pages }
    })

    void queryClient.invalidateQueries({ queryKey: ['conversations'] })
  } catch (err) {
    console.error('[Catch-up] failed, falling back to invalidate:', err)
    void queryClient.invalidateQueries({ queryKey })
  }
}
