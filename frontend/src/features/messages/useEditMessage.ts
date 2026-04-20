// ---------------------------------------------------------------------------
// useEditMessage — STOMP-based inline edit với optimistic marker + 10s timeout
//
// Flow:
// 1. Optimistic update: CHỈ patch failureCode='SAVING' làm marker (KHÔNG touch content/editedAt)
// 2. Publish STOMP frame tới /app/conv.{convId}.edit
// 3. 10s timeout timer → nếu hết timeout không nhận ACK/ERROR → mark TIMEOUT error
// 4. ACK: clear timer, patch content + editedAt từ server response (authoritative)
// 5. ERROR: clear timer, patch failureCode/failureReason — content tự động vẫn là bản cũ từ DB
//
// Lý do KHÔNG patch content optimistically:
//   Nếu server trả ERROR hoặc timeout xảy ra, không có cách revert về content cũ
//   vì ta không lưu snapshot. Chỉ patch khi có ACK xác nhận từ server.
//
// Xem SOCKET_EVENTS.md §3c.6 cho state machine đầy đủ.
// ---------------------------------------------------------------------------

import { useCallback } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { getStompClient } from '@/lib/stompClient'
import { messageKeys } from '@/features/conversations/queryKeys'
import { patchMessageById } from './hooks'
import { editTimerRegistry } from './editTimerRegistry'
import type { MessageDto } from '@/types/message'

type InfiniteCache = { pages: { items: MessageDto[]; hasMore: boolean; nextCursor: string | null }[]; pageParams: unknown[] } | undefined

const EDIT_TIMEOUT_MS = 10_000

export function useEditMessage(convId: string) {
  const queryClient = useQueryClient()

  const editMessage = useCallback(
    (messageId: string, newContent: string): { clientEditId: string } => {
      const client = getStompClient()
      if (!client?.connected) {
        throw new Error('STOMP_NOT_CONNECTED')
      }

      const clientEditId = crypto.randomUUID()

      // 1. Không patch content/editedAt optimistically.
      // Lý do: nếu server trả ERROR hoặc timeout, không có snapshot để revert về content cũ.
      // Content sẽ được cập nhật chỉ khi nhận ACK từ server (authoritative).
      // Clear failureCode cũ (nếu đang retry) để UI không còn hiển thị lỗi cũ.
      queryClient.setQueryData(
        messageKeys.all(convId),
        (old: InfiniteCache) =>
          patchMessageById(old, messageId, {
            failureCode: undefined,
            failureReason: undefined,
          }),
      )

      // 2. Publish STOMP frame
      client.publish({
        destination: `/app/conv.${convId}.edit`,
        body: JSON.stringify({ clientEditId, messageId, newContent }),
      })

      // 3. 10s timeout timer
      const timerId = window.setTimeout(() => {
        // Check xem message này có còn 'saving' không (tránh race với ACK muộn)
        const currentData = queryClient.getQueryData<InfiniteCache>(messageKeys.all(convId))
        if (currentData) {
          const msg = currentData.pages.flatMap((p) => p.items).find((m) => m.id === messageId)
          // Chỉ update nếu editTimerRegistry vẫn còn entry này (nghĩa là chưa nhận ACK/ERROR)
          if (msg && editTimerRegistry.get(clientEditId)) {
            // Chỉ patch failure marker — content/editedAt không bị động (vẫn là bản DB cũ)
            queryClient.setQueryData(
              messageKeys.all(convId),
              (old: InfiniteCache) =>
                patchMessageById(old, messageId, {
                  failureCode: 'TIMEOUT',
                  failureReason: 'Server không phản hồi sau 10 giây',
                }),
            )
            toast.error('Sửa thất bại, thử lại')
          }
        }
        editTimerRegistry.clear(clientEditId)
      }, EDIT_TIMEOUT_MS)

      editTimerRegistry.set(clientEditId, { timerId, messageId, convId })

      return { clientEditId }
    },
    [convId, queryClient],
  )

  return { editMessage }
}
