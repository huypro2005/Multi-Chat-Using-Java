import api from '@/lib/api'
import type { ApiErrorBody } from '@/types/api'
import type {
  ConversationDto,
  ConversationSummaryDto,
  CreateConversationRequest,
  PageResponse,
} from '@/types/conversation'
import axios from 'axios'

// ---------------------------------------------------------------------------
// Result type cho createConversation — xử lý cả 201 lẫn 409 idempotency
// ---------------------------------------------------------------------------
export interface CreateConversationResult {
  conversation?: ConversationDto
  existingConversationId?: string // khi 409 CONV_ONE_ON_ONE_EXISTS
}

// ---------------------------------------------------------------------------
// POST /api/conversations
// ---------------------------------------------------------------------------
export async function createConversation(
  data: CreateConversationRequest,
): Promise<CreateConversationResult> {
  try {
    const res = await api.post<ConversationDto>('/api/conversations', data)
    return { conversation: res.data }
  } catch (err: unknown) {
    if (
      axios.isAxiosError(err) &&
      err.response?.status === 409 &&
      (err.response.data as ApiErrorBody | undefined)?.error === 'CONV_ONE_ON_ONE_EXISTS'
    ) {
      const details = (err.response.data as ApiErrorBody | undefined)?.details
      const conversationId = (details as { conversationId?: string } | undefined)?.conversationId
      return { existingConversationId: conversationId }
    }
    throw err
  }
}

// ---------------------------------------------------------------------------
// GET /api/conversations?page=&size=
// ---------------------------------------------------------------------------
export async function listConversations(
  page = 0,
  size = 20,
): Promise<PageResponse<ConversationSummaryDto>> {
  const res = await api.get<PageResponse<ConversationSummaryDto>>('/api/conversations', {
    params: { page, size },
  })
  return res.data
}

// ---------------------------------------------------------------------------
// GET /api/conversations/{id}
// ---------------------------------------------------------------------------
export async function getConversation(id: string): Promise<ConversationDto> {
  const res = await api.get<ConversationDto>(`/api/conversations/${id}`)
  return res.data
}
