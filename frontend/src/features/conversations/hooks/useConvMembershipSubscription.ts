// ---------------------------------------------------------------------------
// useConvMembershipSubscription — subscribe user-specific queues (W7-D3)
//
// Mount 1 lần tại GlobalSubscriptions (App.tsx).
// Subscribe:
//   /user/queue/conv-added  → thêm conv vào sidebar cache + toast
//   /user/queue/conv-removed → xóa conv khỏi sidebar cache + toast (nếu KICKED)
//
// Idempotent: remove conv đã remove → no-op.
// ---------------------------------------------------------------------------

import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { getStompClient, onConnectionStateChange } from '@/lib/stompClient'
import { conversationKeys } from '../queryKeys'
import type { ConversationSummaryDto } from '@/types/conversation'

interface ConvRemovedPayload {
  conversationId: string
  reason: 'KICKED' | 'LEFT' | 'DELETED'
  groupName?: string
}

export function useConvMembershipSubscription(): void {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  // useNavigate được gọi một lần trong hook, capture vào ref để dùng trong callback
  const navigateRef = useRef(navigate)

  useEffect(() => {
    navigateRef.current = navigate
  })

  useEffect(() => {
    let cleanupAdded: (() => void) | null = null
    let cleanupRemoved: (() => void) | null = null

    function subscribe(): void {
      const client = getStompClient()
      if (!client?.connected) return

      // Subscribe /user/queue/conv-added
      const subAdded = client.subscribe('/user/queue/conv-added', (frame) => {
        try {
          const conv = JSON.parse(frame.body) as ConversationSummaryDto
          // Add to conversations cache (dedupe)
          queryClient.setQueryData(
            conversationKeys.lists(),
            (old: { content?: ConversationSummaryDto[]; page?: number; size?: number; totalElements?: number; totalPages?: number } | undefined) => {
              if (!old) return old
              const exists = (old.content ?? []).some((c) => c.id === conv.id)
              if (exists) return old
              return {
                ...old,
                content: [conv, ...(old.content ?? [])],
                totalElements: (old.totalElements ?? 0) + 1,
              }
            },
          )
          // Also invalidate to ensure fresh data
          void queryClient.invalidateQueries({ queryKey: conversationKeys.all })
          toast.info(`Bạn đã được thêm vào nhóm "${conv.displayName ?? conv.name ?? 'mới'}"`)
        } catch (e) {
          console.error('[WS] Failed to parse conv-added frame', e)
        }
      })

      // Subscribe /user/queue/conv-removed
      const subRemoved = client.subscribe('/user/queue/conv-removed', (frame) => {
        try {
          const payload = JSON.parse(frame.body) as ConvRemovedPayload
          const { conversationId, reason } = payload

          // Remove from conversations cache (idempotent)
          queryClient.setQueryData(
            conversationKeys.lists(),
            (old: { content?: ConversationSummaryDto[] } | undefined) => {
              if (!old) return old
              const newContent = (old.content ?? []).filter((c) => c.id !== conversationId)
              if (newContent.length === (old.content ?? []).length) return old // already removed
              return { ...old, content: newContent }
            },
          )

          // Remove detail cache
          queryClient.removeQueries({ queryKey: conversationKeys.detail(conversationId) })

          // Navigate away if currently viewing this conv
          if (window.location.pathname.includes(conversationId)) {
            navigateRef.current('/')
          }

          if (reason === 'KICKED') {
            const name = payload.groupName ?? 'nhóm'
            toast.warning(`Bạn đã bị xóa khỏi nhóm "${name}"`)
          }
        } catch (e) {
          console.error('[WS] Failed to parse conv-removed frame', e)
        }
      })

      cleanupAdded = () => subAdded.unsubscribe()
      cleanupRemoved = () => subRemoved.unsubscribe()
    }

    // Subscribe ngay nếu đã connected
    subscribe()

    // Re-subscribe khi reconnect
    const unsubState = onConnectionStateChange((state) => {
      if (state === 'DISCONNECTED' || state === 'ERROR') {
        cleanupAdded?.()
        cleanupAdded = null
        cleanupRemoved?.()
        cleanupRemoved = null
      } else if (state === 'CONNECTED') {
        cleanupAdded?.()
        cleanupAdded = null
        cleanupRemoved?.()
        cleanupRemoved = null
        subscribe()
      }
    })

    return () => {
      cleanupAdded?.()
      cleanupRemoved?.()
      unsubState()
    }
  }, [queryClient])
}
