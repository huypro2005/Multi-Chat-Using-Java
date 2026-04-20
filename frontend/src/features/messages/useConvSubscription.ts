// ---------------------------------------------------------------------------
// useConvSubscription — subscribe /topic/conv.{id} và merge message realtime
// vào React Query cache với dedupe.
//
// Thiết kế:
// - Subscribe ngay nếu STOMP đã CONNECTED.
// - Dùng onConnectionStateChange để re-subscribe khi state chuyển CONNECTED
//   (sau reconnect, sau auth refresh).
// - Cleanup: unsubscribe + remove state listener trong useEffect cleanup.
// - Dedupe: kiểm tra id trước khi append — tránh duplicate khi sender nhận
//   lại broadcast của chính mình sau onSuccess (xem SOCKET_EVENTS.md mục 3.1).
// - Append vào pages[lastIdx].items (last page = newest messages, sorted ASC).
// - Invalidate ['conversations'] để sidebar refresh lastMessageAt.
// ---------------------------------------------------------------------------

import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { getStompClient, onConnectionStateChange } from '@/lib/stompClient'
import { messageKeys } from '@/features/conversations/queryKeys'
import { useAuthStore } from '@/stores/authStore'
import type { MessageDto, MessageListResponse } from '@/types/message'

interface WsEvent {
  type: string
  payload: unknown
}

// Payload của MESSAGE_UPDATED — minimal, chỉ fields thay đổi (§3.2 SOCKET_EVENTS.md)
interface MessageUpdatedPayload {
  id: string
  conversationId: string
  content: string
  editedAt: string
}

// Payload của MESSAGE_DELETED (§3.3 SOCKET_EVENTS.md)
interface MessageDeletedPayload {
  id: string
  conversationId: string
  deletedAt: string
  deletedBy: string
}

function isLikelyMatchOptimistic(tempMsg: MessageDto, incoming: MessageDto): boolean {
  if (!tempMsg.clientTempId) return false
  if (tempMsg.type !== incoming.type) return false
  if (tempMsg.content !== incoming.content) return false

  const tempTs = Date.parse(tempMsg.createdAt)
  const incomingTs = Date.parse(incoming.createdAt)
  if (Number.isNaN(tempTs) || Number.isNaN(incomingTs)) return true

  // optimistic timestamp và server timestamp thường rất gần nhau
  return Math.abs(incomingTs - tempTs) <= 30_000
}

export function useConvSubscription(conversationId: string | undefined): void {
  const queryClient = useQueryClient()
  const currentUserId = useAuthStore((s) => s.user?.id ?? null)

  useEffect(() => {
    if (!conversationId) return

    // cleanup ref — lưu unsubscribe fn của subscription STOMP hiện tại
    let cleanup: (() => void) | null = null

    function subscribe(): void {
      const client = getStompClient()
      if (!client?.connected) return

      const sub = client.subscribe(`/topic/conv.${conversationId}`, (frame) => {
        try {
          const event = JSON.parse(frame.body) as WsEvent
          if (event.type === 'MESSAGE_CREATED') {
            appendToCache(queryClient, conversationId!, event.payload as MessageDto, currentUserId)
          } else if (event.type === 'MESSAGE_UPDATED') {
            handleMessageUpdated(
              queryClient,
              conversationId!,
              event.payload as MessageUpdatedPayload,
            )
          } else if (event.type === 'MESSAGE_DELETED') {
            handleMessageDeleted(
              queryClient,
              conversationId!,
              event.payload as MessageDeletedPayload,
            )
          }
        } catch (e) {
          console.error('[WS] Failed to parse message frame', e)
        }
      })

      cleanup = () => sub.unsubscribe()
    }

    // Subscribe ngay nếu đã connected
    subscribe()

    // Re-subscribe khi connection state thay đổi
    const unsubState = onConnectionStateChange((state) => {
      if (state === 'CONNECTED') {
        // Unsubscribe cũ trước để tránh duplicate handlers
        cleanup?.()
        cleanup = null
        subscribe()
      } else if (state === 'DISCONNECTED' || state === 'ERROR') {
        cleanup?.()
        cleanup = null
      }
    })

    return () => {
      cleanup?.()
      cleanup = null
      unsubState()
    }
  }, [conversationId, currentUserId, queryClient])
}

// ---------------------------------------------------------------------------
// Helper: handle MESSAGE_UPDATED broadcast — cập nhật content + editedAt
// ---------------------------------------------------------------------------
function handleMessageUpdated(
  queryClient: QueryClient,
  conversationId: string,
  updated: MessageUpdatedPayload,
): void {
  queryClient.setQueryData(
    messageKeys.all(conversationId),
    (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
      if (!old) return old

      const pages = old.pages.map((page) => {
        const idx = page.items.findIndex((m) => m.id === updated.id)
        if (idx === -1) return page

        const existing = page.items[idx]

        // Dedupe theo editedAt (§3.2): nếu broadcast cũ hơn hoặc bằng cache → skip
        // So sánh lexicographic ISO8601 UTC (Z format đồng nhất) hoặc parse Date
        if (existing.editedAt && existing.editedAt >= updated.editedAt) return page

        const nextItems = [...page.items]
        nextItems[idx] = {
          ...existing,
          content: updated.content,
          editedAt: updated.editedAt,
          // Clear failure state nếu có (ACK về qua broadcast thay vì user queue)
          failureCode: undefined,
          failureReason: undefined,
        }
        return { ...page, items: nextItems }
      })

      return { ...old, pages }
    },
  )

  // V1 đơn giản: luôn invalidate conversations để sidebar refresh lastMessagePreview
  void queryClient.invalidateQueries({ queryKey: ['conversations'] })
}

// ---------------------------------------------------------------------------
// Helper: handle MESSAGE_DELETED broadcast — soft delete (§3.3 SOCKET_EVENTS.md)
// ---------------------------------------------------------------------------
function handleMessageDeleted(
  queryClient: QueryClient,
  conversationId: string,
  deleted: MessageDeletedPayload,
): void {
  queryClient.setQueryData(
    messageKeys.all(conversationId),
    (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
      if (!old) return old

      const pages = old.pages.map((page) => {
        const idx = page.items.findIndex((m) => m.id === deleted.id)
        if (idx === -1) return page

        const nextItems = [...page.items]
        nextItems[idx] = {
          ...nextItems[idx],
          content: null,
          deletedAt: deleted.deletedAt,
          deletedBy: deleted.deletedBy,
          // Clear deleteStatus nếu sender tab này đang chờ ACK từ trước
          deleteStatus: undefined,
        }
        return { ...page, items: nextItems }
      })

      return { ...old, pages }
    },
  )

  // Invalidate conversations để sidebar refresh (message bị xoá có thể là lastMessage)
  void queryClient.invalidateQueries({ queryKey: ['conversations'] })
}

// ---------------------------------------------------------------------------
// Helper: append message vào React Query infinite cache với dedupe
// ---------------------------------------------------------------------------
function appendToCache(
  queryClient: QueryClient,
  conversationId: string,
  newMsg: MessageDto,
  currentUserId: string | null,
): void {
  queryClient.setQueryData(
    messageKeys.all(conversationId),
    (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
      if (!old) return old

      // DEDUPE theo real id (cross-all-pages)
      const exists = old.pages.some((page) => page.items.some((m) => m.id === newMsg.id))
      if (exists) return old

      // Nếu là tin của chính mình, ưu tiên replace optimistic temp gần nhất thay vì append mới.
      // Tránh case UI hiện 2 bubble: 1 cái thành công + 1 cái vẫn loading.
      if (currentUserId && newMsg.sender.id === currentUserId) {
        let replaced = false
        const pages = old.pages.map((page) => {
          if (replaced) return page

          let matchIdx = -1
          for (let i = page.items.length - 1; i >= 0; i -= 1) {
            if (isLikelyMatchOptimistic(page.items[i], newMsg)) {
              matchIdx = i
              break
            }
          }

          if (matchIdx === -1) return page
          replaced = true

          const nextItems = [...page.items]
          nextItems[matchIdx] = newMsg
          return { ...page, items: nextItems }
        })

        if (replaced) return { ...old, pages }
      }

      // Append vào page ĐẦU (index 0 = newest window trong infinite query cache)
      const pages = [...old.pages]
      if (pages.length === 0) return old

      pages[0] = {
        ...pages[0],
        items: [...pages[0].items, newMsg],
      }

      return { ...old, pages }
    },
  )

  // Invalidate conversations list để sidebar cập nhật lastMessageAt + re-sort
  void queryClient.invalidateQueries({ queryKey: ['conversations'] })
}
