import api from '@/lib/api'
import type { MessageDto, MessageListResponse, SendMessageRequest } from '@/types/message'

export async function sendMessage(
  convId: string,
  data: SendMessageRequest,
): Promise<MessageDto> {
  const res = await api.post<MessageDto>(`/api/conversations/${convId}/messages`, data)
  return res.data
}

export async function getMessages(
  convId: string,
  cursor?: string,
  limit = 50,
): Promise<MessageListResponse> {
  const res = await api.get<MessageListResponse>(`/api/conversations/${convId}/messages`, {
    params: { ...(cursor ? { cursor } : {}), limit },
  })
  return res.data
}
