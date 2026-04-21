// ---------------------------------------------------------------------------
// SystemMessage — inline centered pill for SYSTEM messages (W7-D4)
// Contract: docs/SOCKET_EVENTS.md §3e.1 SYSTEM Message Rendering
//
// Không có avatar, không có actions, không có timestamp riêng.
// i18n dùng meta.actorId/targetId vs currentUserId để substitute 'Bạn'/'bạn'.
// ---------------------------------------------------------------------------

import { memo } from 'react'
import type { MessageDto, SystemMetadata } from '@/types/message'

// ---------------------------------------------------------------------------
// renderSystemText — derive human-readable string từ systemEventType + metadata
// ---------------------------------------------------------------------------
function renderSystemText(
  type: string,
  meta: SystemMetadata,
  currentUserId: string,
): string {
  const isActor = !!meta.actorId && meta.actorId === currentUserId
  const isTarget = !!meta.targetId && meta.targetId === currentUserId
  const actorName = isActor ? 'Bạn' : (meta.actorName ?? 'Ai đó')
  const targetName = isTarget ? 'bạn' : (meta.targetName ?? '')

  switch (type) {
    case 'GROUP_CREATED':
      return `${actorName} đã tạo nhóm`

    case 'MEMBER_ADDED':
      if (!targetName) {
        // Fallback khi BE thiếu targetName (defensive)
        console.warn('[SystemMessage] MEMBER_ADDED missing targetName', meta)
        return `${actorName} đã thêm một thành viên vào nhóm`
      }
      return `${actorName} đã thêm ${targetName} vào nhóm`

    case 'MEMBER_REMOVED':
      if (!targetName) {
        console.warn('[SystemMessage] MEMBER_REMOVED missing targetName', meta)
        return `${actorName} đã xóa một thành viên khỏi nhóm`
      }
      return `${actorName} đã xóa ${targetName} khỏi nhóm`

    case 'MEMBER_LEFT':
      return `${actorName} đã rời nhóm`

    case 'ROLE_PROMOTED':
      if (!targetName) {
        console.warn('[SystemMessage] ROLE_PROMOTED missing targetName', meta)
        return `${actorName} đã đặt một thành viên làm quản trị viên`
      }
      return `${actorName} đã đặt ${targetName} làm quản trị viên`

    case 'ROLE_DEMOTED':
      if (!targetName) {
        console.warn('[SystemMessage] ROLE_DEMOTED missing targetName', meta)
        return `${actorName} đã gỡ quyền quản trị của một thành viên`
      }
      return `${actorName} đã gỡ quyền quản trị của ${targetName}`

    case 'OWNER_TRANSFERRED': {
      if (meta.autoTransferred) {
        // Auto-transfer từ OWNER leave: "{actorName} đã rời nhóm và chuyển quyền trưởng nhóm cho {targetName}"
        if (!targetName) {
          console.warn('[SystemMessage] OWNER_TRANSFERRED (auto) missing targetName', meta)
          return `${actorName} đã rời nhóm và chuyển quyền trưởng nhóm`
        }
        return `${actorName} đã rời nhóm và chuyển quyền trưởng nhóm cho ${targetName}`
      }
      // Explicit transfer
      if (!targetName) {
        console.warn('[SystemMessage] OWNER_TRANSFERRED missing targetName', meta)
        return `${actorName} đã chuyển quyền trưởng nhóm`
      }
      return `${actorName} đã chuyển quyền trưởng nhóm cho ${targetName}`
    }

    case 'GROUP_RENAMED':
      if (!meta.newValue) {
        console.warn('[SystemMessage] GROUP_RENAMED missing newValue', meta)
        return `${actorName} đã đổi tên nhóm`
      }
      if (meta.oldValue) {
        return `${actorName} đã đổi tên nhóm từ "${meta.oldValue}" thành "${meta.newValue}"`
      }
      return `${actorName} đã đổi tên nhóm thành "${meta.newValue}"`

    default:
      // Unknown event type (FE version cũ hơn BE) — generic fallback, không crash (§3e.1)
      console.warn('[SystemMessage] Unknown systemEventType:', type, meta)
      return '(sự kiện hệ thống)'
  }
}

// ---------------------------------------------------------------------------
// SystemMessage component
// ---------------------------------------------------------------------------
interface Props {
  message: MessageDto
  currentUserId: string
}

export const SystemMessage = memo(function SystemMessage({ message, currentUserId }: Props) {
  // Guard: nếu metadata thiếu hoàn toàn, render generic fallback
  const meta: SystemMetadata = message.systemMetadata ?? {}
  const eventType = message.systemEventType ?? ''

  const text = renderSystemText(eventType, meta, currentUserId)

  return (
    <div className="flex justify-center my-1 px-4" role="status" aria-label={text}>
      <span
        className="text-xs italic text-gray-500 dark:text-gray-400 select-none"
      >
        {text}
      </span>
    </div>
  )
})
