import type { ConversationDto, MemberDto } from '../../types/conversation'
import { ConversationType } from '../../types/conversation'

export function getOtherMember(
  conversation: ConversationDto,
  currentUserId: string,
): MemberDto | null {
  if (conversation.type !== ConversationType.ONE_ON_ONE) return null
  return conversation.members.find((m) => m.userId !== currentUserId) ?? null
}

export function formatLastMessageTime(dateStr: string | null): string {
  if (!dateStr) return 'Mới tạo'
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMins / 60)
  const diffDays = Math.floor(diffHours / 24)

  if (diffMins < 1) return 'Vừa xong'
  if (diffMins < 60) return `${diffMins} phút`
  if (diffHours < 24) return `${diffHours} giờ`
  if (diffDays < 7) return `${diffDays} ngày`
  return date.toLocaleDateString('vi-VN', { day: '2-digit', month: '2-digit' })
}
