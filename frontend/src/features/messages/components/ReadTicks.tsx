// ---------------------------------------------------------------------------
// ReadTicks — ✓ / ✓✓ read receipt indicator (W7-D5)
//
// Hiển thị cho messages mình gửi (isOwn).
// V1 approximation: member có lastReadMessageId !== null → coi là đã đọc conv.
// Exact per-message check cần compare createdAt — phức tạp hơn cần thiết cho V1.
// ---------------------------------------------------------------------------

import { memo } from 'react'
import type { MemberDto } from '@/types/conversation'
import type { MessageDto } from '@/types/message'

interface Props {
  message: MessageDto
  members: MemberDto[]
  currentUserId: string
}

export const ReadTicks = memo(function ReadTicks({ message, members, currentUserId }: Props) {
  // Chỉ hiển thị cho messages của chính mình
  if (message.sender?.id !== currentUserId) return null

  // Ẩn khi message đang gửi hoặc thất bại (chưa có real id)
  if (message.status === 'sending' || message.status === 'failed') return null

  const otherMembers = members.filter((m) => m.userId !== currentUserId)
  const readers = otherMembers.filter((m) => m.lastReadMessageId != null)
  const readCount = readers.length

  if (readCount === 0) {
    // Sent — single tick (xám)
    return (
      <span
        className="text-gray-400 text-xs select-none leading-none"
        title="Đã gửi"
        aria-label="Đã gửi"
      >
        ✓
      </span>
    )
  }

  // Read by at least one other member — double tick (xanh)
  const names = readers.map((r) => r.fullName).join(', ')
  return (
    <span
      className="text-blue-500 text-xs select-none cursor-default leading-none"
      title={`Đã đọc: ${names}`}
      aria-label={`Đã đọc bởi: ${names}`}
    >
      ✓✓
    </span>
  )
})
