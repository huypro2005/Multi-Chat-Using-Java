import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { messageKeys } from '../conversations/queryKeys'
import { getMessages, sendMessage } from './api'
import type { MessageDto, MessageListResponse, SendMessageRequest } from '@/types/message'

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
// useSendMessage — mutation với optimistic update
// ---------------------------------------------------------------------------
export function useSendMessage(convId: string) {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (data: SendMessageRequest) => sendMessage(convId, data),

    onMutate: async (variables) => {
      // Cancel in-flight refetches để tránh optimistic bị overwrite
      await queryClient.cancelQueries({ queryKey: messageKeys.all(convId) })

      // Snapshot current cache để rollback nếu fail
      const snapshot = queryClient.getQueryData(messageKeys.all(convId))

      // Tạo tempId và optimistic message
      const tempId = `temp-${Date.now()}-${Math.random().toString(36).slice(2)}`
      const optimisticMsg: MessageDto = {
        id: tempId,
        conversationId: convId,
        sender: { id: '', username: '', fullName: 'Bạn', avatarUrl: null },
        type: variables.type ?? 'TEXT',
        content: variables.content,
        replyToMessage: null,
        editedAt: null,
        createdAt: new Date().toISOString(),
      }

      // Append vào last page của infinite query
      queryClient.setQueryData(
        messageKeys.all(convId),
        (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
          if (!old) return old
          const pages = [...old.pages]
          const lastIdx = pages.length - 1
          if (lastIdx < 0) return old
          pages[lastIdx] = {
            ...pages[lastIdx],
            items: [...pages[lastIdx].items, optimisticMsg],
          }
          return { ...old, pages }
        },
      )

      return { snapshot, tempId }
    },

    onError: (_err, _vars, context) => {
      // Rollback về snapshot trước khi optimistic update
      if (context?.snapshot !== undefined) {
        queryClient.setQueryData(messageKeys.all(convId), context.snapshot)
      }
    },

    onSuccess: (realMsg, _vars, context) => {
      // Thay thế optimistic message (id = tempId) bằng message thật từ BE
      queryClient.setQueryData(
        messageKeys.all(convId),
        (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
          if (!old) return old
          const pages = old.pages.map((page) => ({
            ...page,
            items: page.items.map((item) =>
              item.id === context?.tempId ? realMsg : item,
            ),
          }))
          return { ...old, pages }
        },
      )
    },

    onSettled: () => {
      // Invalidate conversations list để sidebar cập nhật lastMessageAt
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
    },
  })
}
