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
import { toast } from 'sonner'
import { getStompClient, onConnectionStateChange } from '@/lib/stompClient'
import { messageKeys } from '@/features/conversations/queryKeys'
import { timerRegistry } from './timerRegistry'
import { editTimerRegistry } from './editTimerRegistry'
import { deleteTimerRegistry } from './deleteTimerRegistry'
import { replaceTempWithReal, patchMessageByTempId, patchMessageById } from './hooks'
import type { AckEnvelope, DeleteAckMessage, ErrorEnvelope, MessageListResponse } from '@/types/message'

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
// REACT ERROR handler (W8-D1)
// clientId === null cho REACT — không tra registry, chỉ show toast theo code.
// Không rollback optimistic vì V1 không có optimistic react (fire-and-forget).
// ---------------------------------------------------------------------------
function handleReactError(code: string): void {
  switch (code) {
    case 'REACTION_INVALID_EMOJI':
      toast.error('Biểu tượng không hợp lệ')
      break
    case 'REACTION_NOT_ALLOWED_FOR_SYSTEM':
      toast.error('Không thể react tin nhắn hệ thống')
      break
    case 'REACTION_MSG_DELETED':
      toast.error('Tin nhắn đã bị xóa')
      break
    case 'MSG_RATE_LIMITED':
    case 'REACTION_RATE_LIMITED':
      toast.error('React quá nhanh, vui lòng thử lại sau')
      break
    case 'NOT_MEMBER':
      toast.error('Bạn không còn là thành viên cuộc trò chuyện này')
      break
    case 'MSG_NOT_FOUND':
      // Tin nhắn không tồn tại — anti-enum (§3.15) — toast nhẹ
      toast.error('Không thể react tin nhắn này')
      break
    default:
      toast.error('Không thể react tin nhắn')
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

            case 'DELETE': {
              // Tab-awareness: không có entry → ACK của tab khác → ignore
              const deleteEntry = deleteTimerRegistry.get(clientId)
              if (!deleteEntry) return

              deleteTimerRegistry.clear(clientId)

              // DELETE ACK message chỉ có id + conversationId + deletedAt + deletedBy (§3d.3)
              const deleteMsg = message as unknown as DeleteAckMessage

              // Patch message: set deletedAt + deletedBy + strip content
              queryClient.setQueryData(
                messageKeys.all(deleteEntry.convId),
                (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
                  patchMessageById(old, deleteMsg.id, {
                    deletedAt: deleteMsg.deletedAt,
                    deletedBy: deleteMsg.deletedBy,
                    content: null,
                    deleteStatus: undefined,
                  }),
              )

              // Invalidate conversations để sidebar refresh (trường hợp deleted là lastMessage)
              void queryClient.invalidateQueries({ queryKey: ['conversations'] })
              break
            }

            default:
              // REACT và operation khác — chưa implement, ignore
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
          const { operation, error, code } = envelope

          switch (operation) {
            case 'SEND': {
              // clientId là string (non-null) cho SEND
              const clientId = envelope.clientId as string
              // Mark message as failed in cache
              markFailedByTempId(queryClient, clientId, error, code)

              // Attachment-specific error codes
              switch (code) {
                case 'MSG_ATTACHMENT_NOT_FOUND':
                  toast.error('Tệp đính kèm không tồn tại')
                  break
                case 'MSG_ATTACHMENT_NOT_OWNED':
                  toast.error('Lỗi xác thực tệp đính kèm')
                  break
                case 'MSG_ATTACHMENT_ALREADY_USED':
                  toast.error('Tệp đã được sử dụng trong tin nhắn khác')
                  break
                case 'MSG_ATTACHMENTS_MIXED':
                  toast.error('Không thể trộn ảnh với tệp khác hoặc gửi nhiều tệp tài liệu')
                  break
                case 'MSG_ATTACHMENTS_TOO_MANY':
                  toast.error('Vượt giới hạn số tệp đính kèm (tối đa 5)')
                  break
                case 'MSG_ATTACHMENT_EXPIRED':
                  toast.error('Tệp đính kèm đã hết hạn, vui lòng upload lại')
                  break
                case 'MSG_NO_CONTENT':
                  toast.error('Tin nhắn phải có nội dung hoặc tệp đính kèm')
                  break
                case 'MSG_USER_BLOCKED':
                  toast.error('Không thể gửi tin nhắn: đã chặn')
                  break
                case 'MSG_DELETED':
                case 'REACTION_MSG_DELETED':
                  toast.error('Tin nhắn đã bị xóa')
                  break
                case 'FORBIDDEN':
                  toast.error('Bạn không có quyền thực hiện')
                  break
                case 'AUTH_REQUIRED':
                case 'AUTH_TOKEN_EXPIRED':
                  void import('@/services/authService').then(({ authService }) =>
                    authService.refresh().catch(() => {
                      window.location.href = '/login'
                    }),
                  )
                  break
                default:
                  // Other errors already reflected via message status='failed'
                  break
              }
              break
            }

            case 'EDIT': {
              // clientId là string (non-null) cho EDIT
              const clientId = envelope.clientId as string
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

              // SYSTEM message protection: toast + do not mark edit error on UI
              if (code === 'SYSTEM_MESSAGE_NOT_EDITABLE') {
                toast.error('Không thể sửa tin nhắn hệ thống')
                // Clear any edit marker if present (defensive)
                queryClient.setQueryData(
                  messageKeys.all(entry.convId),
                  (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
                    patchMessageById(old, entry.messageId, {
                      failureCode: undefined,
                      failureReason: undefined,
                    }),
                )
                break
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

            case 'DELETE': {
              // clientId là string (non-null) cho DELETE
              const clientId = envelope.clientId as string
              // Tab-awareness: không có entry → ERROR của tab khác → ignore
              const deleteEntry = deleteTimerRegistry.get(clientId)
              if (!deleteEntry) return

              deleteTimerRegistry.clear(clientId)

              // SYSTEM message protection: toast without reverting UI (SYSTEM never had deleteStatus)
              if (code === 'SYSTEM_MESSAGE_NOT_DELETABLE') {
                toast.error('Không thể xóa tin nhắn hệ thống')
                break
              }

              // Revert deleteStatus — xoá thất bại, user có thể thử lại
              queryClient.setQueryData(
                messageKeys.all(deleteEntry.convId),
                (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) =>
                  patchMessageById(old, deleteEntry.messageId, { deleteStatus: undefined }),
              )

              // Toast theo error code
              const deleteErrorMsg =
                code === 'MSG_NOT_FOUND'
                  ? 'Tin nhắn không tồn tại hoặc bạn không có quyền xoá'
                  : code === 'MSG_RATE_LIMITED'
                    ? 'Bạn đang xoá quá nhanh, vui lòng thử lại sau'
                    : code === 'AUTH_REQUIRED'
                      ? 'Phiên đăng nhập hết hạn, vui lòng đăng nhập lại'
                      : error ?? 'Không thể xoá tin nhắn'
              console.error('[WS] DELETE error:', deleteErrorMsg, code)

              if (code === 'AUTH_REQUIRED' || code === 'AUTH_TOKEN_EXPIRED') {
                void import('@/services/authService').then(({ authService }) =>
                  authService.refresh().catch(() => {
                    window.location.href = '/login'
                  }),
                )
              }
              break
            }

            case 'REACT': {
              // CRITICAL (W8-D1): REACT ERROR frame có clientId: null — KHÔNG dùng registry.get(null).
              // Route xử lý bằng switch(operation) — không tìm trong bất kỳ registry nào.
              handleReactError(code)
              break
            }

            case 'PIN': {
              switch (code) {
                case 'PIN_LIMIT_EXCEEDED':
                  toast.error('Đã đạt giới hạn 3 tin ghim')
                  break
                case 'MESSAGE_NOT_PINNED':
                  toast.error('Tin nhắn chưa được ghim')
                  break
                case 'MSG_DELETED':
                  toast.error('Tin nhắn đã bị xóa')
                  break
                case 'FORBIDDEN':
                  toast.error('Bạn không có quyền ghim hoặc bỏ ghim')
                  break
                case 'INVALID_ACTION':
                  toast.error('Hành động ghim không hợp lệ')
                  break
                case 'MSG_RATE_LIMITED':
                  toast.error('Bạn đang thao tác ghim quá nhanh')
                  break
                default:
                  toast.error('Không thể ghim tin nhắn')
                  break
              }
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
