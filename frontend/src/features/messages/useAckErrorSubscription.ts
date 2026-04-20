// ---------------------------------------------------------------------------
// useAckErrorSubscription — global ACK/ERROR handler (ADR-016 + ADR-017)
//
// Mount 1 lần ở App root (sau khi user đã authenticated + STOMP connected).
// Subscribe 2 user queues:
//   /user/queue/acks   → unified ACK handler với operation discriminator
//   /user/queue/errors → unified ERROR handler với operation discriminator
//
// ADR-017 (W5-D2): Breaking change — shape cũ {tempId, message} đổi sang
//   {operation: "SEND"|"EDIT"|"DELETE", clientId, message}
//   FE route handler qua switch(operation).
//
// Re-subscribe khi STOMP reconnect (giống pattern useConvSubscription).
// ---------------------------------------------------------------------------

import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import type { StompSubscription } from '@stomp/stompjs'
import { getStompClient, onConnectionStateChange } from '@/lib/stompClient'
import { messageKeys } from '@/features/conversations/queryKeys'
import { timerRegistry } from './timerRegistry'
import { editTimerRegistry } from './editTimerRegistry'
import { replaceTempWithReal, patchMessageByTempId, patchMessageById } from './hooks'
import type { AckEnvelope, ErrorEnvelope, MessageListResponse } from '@/types/message'

// ---------------------------------------------------------------------------
// ERROR handler — SEND operation
// Scan TẤT CẢ cached message queries để find + patch bằng clientTempId.
// Cần scan vì error payload không chứa convId — dùng timerRegistry để tra O(1).
// ---------------------------------------------------------------------------
function markFailedByTempId(
  queryClient: QueryClient,
  tempId: string,
  error: string,
  code: string,
): void {
  // Ưu tiên tra convId từ timerRegistry (O(1)) trước khi timer bị clear
  const entry = timerRegistry.get(tempId)
  const knownConvId = entry?.convId

  // Clear timer (trước hoặc sau lấy convId đều OK vì ta đã đọc convId rồi)
  timerRegistry.clear(tempId)

  if (knownConvId) {
    // Fast path: biết convId → patch trực tiếp
    queryClient.setQueryData(
      messageKeys.all(knownConvId),
      (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
        patchMessageByTempId(old, tempId, {
          status: 'failed',
          failureCode: code,
          failureReason: error,
        }),
    )
    return
  }

  // Slow path: không có entry trong registry (hiếm — timer đã expire trước ERROR)
  // Duyệt tất cả queries có key bắt đầu bằng 'messages'
  const queries = queryClient.getQueriesData<{
    pages: MessageListResponse[]
    pageParams: unknown[]
  }>({ queryKey: ['messages'] })

  for (const [queryKey, data] of queries) {
    if (!data) continue
    const found = data.pages.some((p) => p.items.some((m) => m.clientTempId === tempId))
    if (found) {
      queryClient.setQueryData(
        queryKey,
        (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
          patchMessageByTempId(old, tempId, {
            status: 'failed',
            failureCode: code,
            failureReason: error,
          }),
      )
      break
    }
  }
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------
export function useAckErrorSubscription(): void {
  const queryClient = useQueryClient()

  useEffect(() => {
    let ackSub: StompSubscription | null = null
    let errSub: StompSubscription | null = null

    function subscribe(): void {
      const client = getStompClient()
      if (!client?.connected) return

      // -------------------------------------------------------------------
      // ACK handler — unified với operation discriminator (ADR-017)
      // -------------------------------------------------------------------
      ackSub = client.subscribe('/user/queue/acks', (frame) => {
        try {
          const envelope = JSON.parse(frame.body) as AckEnvelope
          const { operation, clientId, message } = envelope

          switch (operation) {
            case 'SEND': {
              // Clear timeout timer
              timerRegistry.clear(clientId)

              // Replace optimistic với real message trong cache
              queryClient.setQueryData(
                messageKeys.all(message.conversationId),
                (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
                  replaceTempWithReal(old, clientId, message),
              )

              // Invalidate conversations list để sidebar cập nhật lastMessageAt
              void queryClient.invalidateQueries({ queryKey: ['conversations'] })
              break
            }

            case 'EDIT': {
              // Tab-awareness: nếu tab này không có clientEditId trong registry
              // → ACK đến từ operation của tab khác → ignore
              const entry = editTimerRegistry.get(clientId)
              if (!entry) return

              editTimerRegistry.clear(clientId)

              // Update message fields từ server response
              queryClient.setQueryData(
                messageKeys.all(entry.convId),
                (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
                  patchMessageById(old, message.id, {
                    content: message.content,
                    editedAt: message.editedAt,
                    // Clear failure state nếu có
                    failureCode: undefined,
                    failureReason: undefined,
                  }),
              )
              break
            }

            default:
              // DELETE / REACT — chưa implement, ignore
              break
          }
        } catch (e) {
          console.error('[WS] Failed to parse ACK frame', e)
        }
      })

      // -------------------------------------------------------------------
      // ERROR handler — unified với operation discriminator (ADR-017)
      // -------------------------------------------------------------------
      errSub = client.subscribe('/user/queue/errors', (frame) => {
        try {
          const envelope = JSON.parse(frame.body) as ErrorEnvelope
          const { operation, clientId, error, code } = envelope

          switch (operation) {
            case 'SEND': {
              // Logic cũ: mark failed + handle auth errors
              markFailedByTempId(queryClient, clientId, error, code)

              if (code === 'AUTH_REQUIRED' || code === 'AUTH_TOKEN_EXPIRED') {
                void import('@/services/authService').then(({ authService }) =>
                  authService.refresh().catch(() => {
                    window.location.href = '/login'
                  }),
                )
              }
              break
            }

            case 'EDIT': {
              // Tab-awareness: không có entry → ACK của tab khác → ignore
              const entry = editTimerRegistry.get(clientId)
              if (!entry) return

              editTimerRegistry.clear(clientId)

              if (code === 'MSG_NO_CHANGE') {
                // Silent: không phải lỗi nghiêm trọng — chỉ clear SAVING marker.
                // content/editedAt không bị động vì ta không patch chúng optimistically.
                queryClient.setQueryData(
                  messageKeys.all(entry.convId),
                  (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
                    patchMessageById(old, entry.messageId, {
                      failureCode: undefined,
                      failureReason: undefined,
                    }),
                )
                return
              }

              // Mark edit error — content vẫn là bản DB cũ (không bị lệch) vì
              // ta không patch content trong optimistic step.
              queryClient.setQueryData(
                messageKeys.all(entry.convId),
                (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
                  patchMessageById(old, entry.messageId, {
                    failureCode: code,
                    failureReason: error,
                  }),
              )
              break
            }

            default:
              break
          }
        } catch (e) {
          console.error('[WS] Failed to parse ERROR frame', e)
        }
      })
    }

    // Subscribe ngay nếu STOMP đã connected
    const client = getStompClient()
    if (client?.connected) {
      subscribe()
    }

    // Re-subscribe khi reconnect (pattern giống useConvSubscription)
    const unsubState = onConnectionStateChange((state) => {
      if (state === 'CONNECTED') {
        // Unsubscribe cũ để tránh duplicate handlers
        ackSub?.unsubscribe()
        errSub?.unsubscribe()
        ackSub = null
        errSub = null
        subscribe()
      } else if (state === 'DISCONNECTED' || state === 'ERROR') {
        ackSub?.unsubscribe()
        errSub?.unsubscribe()
        ackSub = null
        errSub = null
      }
    })

    return () => {
      ackSub?.unsubscribe()
      errSub?.unsubscribe()
      ackSub = null
      errSub = null
      unsubState()
    }
  }, [queryClient])
  // queryClient stable across renders — effect chỉ chạy 1 lần khi mount
}
