import api from '@/lib/api'
import type { ApiErrorBody } from '@/types/api'
import type {
  AddMembersRequest,
  AddMembersResponse,
  ChangeRoleRequest,
  ConversationDto,
  ConversationSummaryDto,
  CreateConversationRequest,
  PageResponse,
  TransferOwnerRequest,
  UpdateGroupRequest,
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

// ---------------------------------------------------------------------------
// PATCH /api/conversations/{id} — update group name/avatar
// ---------------------------------------------------------------------------
export async function updateGroup(id: string, data: UpdateGroupRequest): Promise<ConversationDto> {
  const res = await api.patch<ConversationDto>(`/api/conversations/${id}`, data)
  return res.data
}

// ---------------------------------------------------------------------------
// DELETE /api/conversations/{id} — delete group (OWNER only)
// ---------------------------------------------------------------------------
export async function deleteGroup(id: string): Promise<void> {
  await api.delete(`/api/conversations/${id}`)
}

// ---------------------------------------------------------------------------
// POST /api/conversations/{id}/members — add members (batch)
// ---------------------------------------------------------------------------
export async function addMembers(id: string, data: AddMembersRequest): Promise<AddMembersResponse> {
  const res = await api.post<AddMembersResponse>(`/api/conversations/${id}/members`, data)
  return res.data
}

// ---------------------------------------------------------------------------
// DELETE /api/conversations/{id}/members/{userId} — kick member
// ---------------------------------------------------------------------------
export async function removeMember(convId: string, userId: string): Promise<void> {
  await api.delete(`/api/conversations/${convId}/members/${userId}`)
}

// ---------------------------------------------------------------------------
// POST /api/conversations/{id}/leave — leave group
// ---------------------------------------------------------------------------
export async function leaveGroup(id: string): Promise<void> {
  await api.post(`/api/conversations/${id}/leave`)
}

// ---------------------------------------------------------------------------
// PATCH /api/conversations/{id}/members/{userId}/role — change member role
// ---------------------------------------------------------------------------
export async function changeMemberRole(
  convId: string,
  userId: string,
  data: ChangeRoleRequest,
): Promise<void> {
  await api.patch(`/api/conversations/${convId}/members/${userId}/role`, data)
}

// ---------------------------------------------------------------------------
// POST /api/conversations/{id}/transfer-owner — transfer ownership
// ---------------------------------------------------------------------------
export async function transferOwner(convId: string, data: TransferOwnerRequest): Promise<void> {
  await api.post(`/api/conversations/${convId}/transfer-owner`, data)
}
