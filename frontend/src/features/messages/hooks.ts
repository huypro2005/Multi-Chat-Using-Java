import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { useCallback } from 'react'
import { toast } from 'sonner'
import { messageKeys } from '../conversations/queryKeys'
import { getMessages } from './api'
import { timerRegistry } from './timerRegistry'
import type { MessageDto, MessageListResponse } from '@/types/message'
import { useAuthStore } from '@/stores/authStore'
import { publishConversationMessage } from '@/lib/stompClient'
import { getStompClient } from '@/lib/stompClient'

// ---------------------------------------------------------------------------
// useMessages — cursor-based infinite query (oldest first, scroll-up to load more)
// ---------------------------------------------------------------------------
export function useMessages(convId: string) {
  return useInfiniteQuery({
    queryKey: messageKeys.all(convId),
    queryFn: ({ pageParam }) => getMessages(convId, pageParam as string | undefined),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (lastPage: MessageListResponse) =>
      lastPage.hasMore ? (lastPage.nextCursor ?? undefined) : undefined,
    enabled: !!convId,
    staleTime: 10_000, // 10 giây
  })
}

// ---------------------------------------------------------------------------
// Cache helpers
// ---------------------------------------------------------------------------

type InfiniteCache = { pages: MessageListResponse[]; pageParams: unknown[] } | undefined

/**
 * Append message vào page cuối cùng của infinite cache.
 * Tương tự appendToCache trong useConvSubscription nhưng không dedupe bằng id
 * (optimistic message chưa có real id).
 */
function appendToInfiniteCache(old: InfiniteCache, msg: MessageDto): InfiniteCache {
  if (!old) return old
  const pages = [...old.pages]
  if (pages.length === 0) return old
  // Append vào page đầu (newest window) — nhất quán với pattern cũ
  pages[0] = {
    ...pages[0],
    items: [...pages[0].items, msg],
  }
  return { ...old, pages }
}

/**
 * Patch message có id === messageId (real server id) với các fields mới.
 * Dùng cho edit operation — khác patchMessageByTempId dùng clientTempId.
 */
export function patchMessageById(
  old: InfiniteCache,
  messageId: string,
  patch: Partial<MessageDto>,
): InfiniteCache {
  if (!old) return old
  const pages = old.pages.map((page) => {
    const idx = page.items.findIndex((m) => m.id === messageId)
    if (idx === -1) return page
    const nextItems = [...page.items]
    nextItems[idx] = { ...nextItems[idx], ...patch }
    return { ...page, items: nextItems }
  })
  return { ...old, pages }
}

/**
 * Patch message có clientTempId === tempId với các fields mới.
 * Duyệt tất cả pages để find + patch.
 */
export function patchMessageByTempId(
  old: InfiniteCache,
  tempId: string,
  patch: Partial<MessageDto>,
): InfiniteCache {
  if (!old) return old
  const pages = old.pages.map((page) => {
    const idx = page.items.findIndex((m) => m.clientTempId === tempId)
    if (idx === -1) return page
    const nextItems = [...page.items]
    nextItems[idx] = { ...nextItems[idx], ...patch }
    return { ...page, items: nextItems }
  })
  return { ...old, pages }
}

/**
 * Replace message có clientTempId === tempId bằng real message từ ACK.
 * Xoá clientTempId + đặt status='sent'.
 */
export function replaceTempWithReal(
  old: InfiniteCache,
  tempId: string,
  realMsg: MessageDto,
): InfiniteCache {
  if (!old) return old
  const pages = old.pages.map((page) => {
    const idx = page.items.findIndex((m) => m.clientTempId === tempId)
    if (idx === -1) return page
    const nextItems = [...page.items]
    // Replace toàn bộ bằng real message, thêm status='sent', xoá clientTempId
    nextItems[idx] = { ...realMsg, status: 'sent', clientTempId: undefined }
    return { ...page, items: nextItems }
  })
  return { ...old, pages }
}

// ---------------------------------------------------------------------------
// SEND_TIMEOUT_MS — 10 giây (khớp contract mục 3b.4)
// ---------------------------------------------------------------------------
const SEND_TIMEOUT_MS = 10_000

// ---------------------------------------------------------------------------
// useSendMessage — Path B (ADR-016): gửi qua STOMP với tempId + optimistic update
// ---------------------------------------------------------------------------
export function useSendMessage(convId: string) {
  const queryClient = useQueryClient()
  const user = useAuthStore((s) => s.user)

  const sendMessage = useCallback(
    (content: string, replyToMessageId?: string, attachmentIds?: string[]): { tempId: string } => {
      const client = getStompClient()
      if (!client?.connected) {
        throw new Error('STOMP_NOT_CONNECTED')
      }

      // 1. Generate tempId (UUID v4)
      const tempId = crypto.randomUUID()

      // 2. Tạo optimistic message với status='sending'
      const optimisticMsg: MessageDto = {
        id: tempId, // tạm dùng tempId làm id để MessageItem render key
        clientTempId: tempId,
        conversationId: convId,
        sender: {
          id: user?.id ?? '',
          username: user?.username ?? '',
          fullName: user?.fullName ?? 'Bạn',
          avatarUrl: user?.avatarUrl ?? null,
        },
        type: 'TEXT',
        content,
        attachments: [], // empty optimistic; ACK replaces with real AttachmentDto[]
        replyToMessage: null, // optimistic không có preview, server ACK sẽ có
        editedAt: null,
        deletedAt: null,
        deletedBy: null,
        createdAt: new Date().toISOString(),
        status: 'sending',
      }

      // 3. Cancel in-flight refetches để tránh race với optimistic
      void queryClient.cancelQueries({ queryKey: messageKeys.all(convId) })

      // 4. Append vào cache
      queryClient.setQueryData(messageKeys.all(convId), (old: InfiniteCache) =>
        appendToInfiniteCache(old, optimisticMsg),
      )

      // 5. Publish STOMP frame
      publishConversationMessage(convId, {
        tempId,
        content,
        type: 'TEXT',
        replyToMessageId: replyToMessageId ?? null,
        attachmentIds: attachmentIds ?? [],
      })

      // 6. Start 10s timeout timer
      const timerId = window.setTimeout(() => {
        // Chỉ mark failed nếu vẫn còn 'sending' (tránh race với ACK muộn)
        queryClient.setQueryData(messageKeys.all(convId), (old: InfiniteCache) => {
          if (!old) return old
          // Check xem message này có còn 'sending' không
          const stillSending = old.pages.some((p) =>
            p.items.some((m) => m.clientTempId === tempId && m.status === 'sending'),
          )
          if (!stillSending) return old
          return patchMessageByTempId(old, tempId, {
            status: 'failed',
            failureCode: 'TIMEOUT',
            failureReason: 'Server không phản hồi sau 10 giây',
          })
        })
        toast.error('Gửi thất bại, thử lại')
        timerRegistry.clear(tempId) // xoá entry sau khi timer fires
      }, SEND_TIMEOUT_MS)

      // 7. Lưu timer + convId vào registry
      timerRegistry.set(tempId, { timerId, convId })

      return { tempId }
    },
    [convId, queryClient, user],
  )

  return sendMessage
}
