// ---------------------------------------------------------------------------
// useDeleteMessage — gửi STOMP delete request với timeout + registry pattern
//
// Pattern:
//   1. User confirm → publish /app/conv.{convId}.delete với clientDeleteId
//   2. Mark message với deleteStatus='deleting' (disable actions, không optimistic hide)
//   3. Chờ ACK qua /user/queue/acks (operation='DELETE')
//      → useAckErrorSubscription sẽ patch deletedAt + deletedBy + content=null
//   4. Hoặc ERROR qua /user/queue/errors (operation='DELETE')
//      → useAckErrorSubscription sẽ revert deleteStatus, toast error
//   5. Timeout 10s → revert deleteStatus, toast error, clear registry
//
// Tab-awareness: deleteTimerRegistry.get(clientDeleteId) undefined nếu tab khác
//   → useAckErrorSubscription ignore ACK/ERROR không phải tab này sinh ra.
// ---------------------------------------------------------------------------

import { useCallback } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { getStompClient } from '@/lib/stompClient'
import { messageKeys } from '@/features/conversations/queryKeys'
import { patchMessageById } from './hooks'
import { deleteTimerRegistry } from './deleteTimerRegistry'
import type { MessageListResponse } from '@/types/message'

type InfiniteCache = { pages: MessageListResponse[]; pageParams: unknown[] } | undefined

const DELETE_TIMEOUT_MS = 10_000

export function useDeleteMessage(convId: string) {
  const queryClient = useQueryClient()

  const deleteMessage = useCallback(
    (messageId: string): void => {
      if (!window.confirm('Xóa tin nhắn này? Hành động này không thể hoàn tác.')) return

      const client = getStompClient()
      if (!client?.connected) {
        console.error('[useDeleteMessage] STOMP not connected')
        return
      }

      const clientDeleteId = crypto.randomUUID()

      // Mark message đang ở trạng thái xoá — disable actions, không hide bubble
      queryClient.setQueryData(messageKeys.all(convId), (old: InfiniteCache) =>
        patchMessageById(old, messageId, { deleteStatus: 'deleting' }),
      )

      // Publish STOMP frame tới /app/conv.{convId}.delete
      client.publish({
        destination: `/app/conv.${convId}.delete`,
        body: JSON.stringify({ clientDeleteId, messageId }),
      })

      // Start 10s timeout timer
      const timerId = window.setTimeout(() => {
        // Revert deleteStatus — server không phản hồi
        queryClient.setQueryData(messageKeys.all(convId), (old: InfiniteCache) =>
          patchMessageById(old, messageId, { deleteStatus: undefined }),
        )
        deleteTimerRegistry.clear(clientDeleteId)

        toast.error('Xóa thất bại, thử lại')
        console.warn('[useDeleteMessage] Delete timeout for', messageId)
      }, DELETE_TIMEOUT_MS)

      // Lưu vào registry để ACK handler tìm được entry
      deleteTimerRegistry.set(clientDeleteId, { timerId, messageId, convId })
    },
    [convId, queryClient],
  )

  return { deleteMessage }
}
