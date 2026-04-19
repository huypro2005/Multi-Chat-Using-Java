import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { conversationKeys } from './queryKeys'
import { createConversation, listConversations, getConversation } from './api'
import type { CreateConversationRequest } from '@/types/conversation'

// ---------------------------------------------------------------------------
// useConversations — GET /api/conversations (paginated)
// ---------------------------------------------------------------------------
export function useConversations(page = 0, size = 20) {
  return useQuery({
    queryKey: conversationKeys.list(page, size),
    queryFn: () => listConversations(page, size),
  })
}

// ---------------------------------------------------------------------------
// useConversation — GET /api/conversations/{id}
// ---------------------------------------------------------------------------
export function useConversation(id: string) {
  return useQuery({
    queryKey: conversationKeys.detail(id),
    queryFn: () => getConversation(id),
    enabled: !!id,
  })
}

// ---------------------------------------------------------------------------
// useCreateConversation — POST /api/conversations
// Caller phải handle existingConversationId (redirect sang conv cũ).
// ---------------------------------------------------------------------------
export function useCreateConversation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (data: CreateConversationRequest) => createConversation(data),
    onSuccess: (result) => {
      if (result.conversation) {
        // Invalidate list để refresh danh sách conversations
        void queryClient.invalidateQueries({ queryKey: conversationKeys.lists() })
        // Seed detail cache ngay — tránh round-trip khi navigate sang detail page
        queryClient.setQueryData(conversationKeys.detail(result.conversation.id), result.conversation)
      }
      // Nếu result.existingConversationId: caller tự handle redirect sang conv cũ
    },
  })
}
