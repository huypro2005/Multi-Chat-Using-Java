import { ArrowLeft, Info, MoreHorizontal } from 'lucide-react'
import UserAvatar from '@/components/UserAvatar'
import { useAuthStore } from '@/stores/authStore'
import { getOtherMember } from '@/features/conversations/utils'
import { useBlockedUsers, useBlockUser, useUnblockUser } from '@/features/users/hooks'
import {
  getConversationDisplayName,
  ConversationType,
} from '@/types/conversation'
import type { ConversationDto } from '@/types/conversation'

interface Props {
  conversation: ConversationDto
  onToggleInfo: () => void
  onBack?: () => void // mobile only
}

export default function ConversationHeader({ conversation, onToggleInfo, onBack }: Props) {
  const currentUserId = useAuthStore((s) => s.user?.id ?? '')

  const displayName = getConversationDisplayName(conversation, currentUserId)
  const otherMember =
    conversation.type === ConversationType.ONE_ON_ONE || conversation.type === ConversationType.DIRECT
      ? getOtherMember(conversation, currentUserId)
      : null
  const blockUser = useBlockUser(otherMember?.userId ?? '')
  const unblockUser = useUnblockUser(otherMember?.userId ?? '')
  const { data: blockedUsers = [] } = useBlockedUsers()

  // Avatar data
  const avatarUser =
    conversation.type === ConversationType.ONE_ON_ONE || conversation.type === ConversationType.DIRECT
      ? (() => {
          const other = otherMember
          return {
            fullName: other?.fullName ?? displayName,
            avatarUrl: other?.avatarUrl ?? null,
          }
        })()
      : {
          fullName: conversation.name ?? 'Nhóm',
          avatarUrl: conversation.avatarUrl,
        }

  // Sub-text
  const subText =
    conversation.type === ConversationType.ONE_ON_ONE || conversation.type === ConversationType.DIRECT
      ? (() => {
          const other = otherMember
          return other ? `@${other.username}` : ''
        })()
      : `${conversation.members.length} thành viên`

  const isDirect =
    conversation.type === ConversationType.ONE_ON_ONE || conversation.type === ConversationType.DIRECT
  const isBlockedByMe = !!otherMember && blockedUsers.some((u) => u.id === otherMember.userId)

  const handleToggleBlock = () => {
    if (!otherMember) return
    if (isBlockedByMe) {
      unblockUser.mutate()
      return
    }
    const ok = window.confirm(
      `Chặn ${otherMember.fullName}?\nBạn và người này sẽ không gửi tin nhắn trực tiếp được cho nhau.`,
    )
    if (ok) {
      blockUser.mutate()
    }
  }

  return (
    <div className="h-16 px-4 border-b bg-white flex items-center gap-3 flex-shrink-0">
      {/* Back button — mobile only */}
      {onBack && (
        <button
          onClick={onBack}
          aria-label="Quay lại"
          className="md:hidden text-gray-500 hover:text-gray-700 p-1 -ml-1 rounded-lg
            hover:bg-gray-100 transition-colors"
        >
          <ArrowLeft size={20} />
        </button>
      )}

      {/* Avatar */}
      <UserAvatar user={avatarUser} size={40} />

      {/* Name + sub-text */}
      <div className="flex-1 min-w-0">
        <p className="text-[15px] font-semibold text-gray-900 truncate">{displayName}</p>
        {subText && (
          <p className="text-xs text-gray-500 truncate">{subText}</p>
        )}
      </div>

      {/* Right actions */}
      <div className="flex items-center gap-1 flex-shrink-0">
        <button
          onClick={onToggleInfo}
          aria-label="Thông tin cuộc trò chuyện"
          className="p-2 text-gray-500 hover:text-indigo-600 hover:bg-indigo-50
            rounded-lg transition-colors"
        >
          <Info size={20} />
        </button>
        {isDirect && otherMember && (
          <button
            type="button"
            onClick={handleToggleBlock}
            disabled={blockUser.isPending || unblockUser.isPending}
            className={`px-2 py-1 text-xs font-medium rounded-md border transition-colors ${
              isBlockedByMe
                ? 'border-indigo-200 text-indigo-700 hover:bg-indigo-50'
                : 'border-red-200 text-red-600 hover:bg-red-50'
            } disabled:opacity-50`}
          >
            {isBlockedByMe ? 'Bỏ chặn' : 'Chặn'}
          </button>
        )}
        <button
          disabled
          aria-label="Thêm tùy chọn"
          className="p-2 text-gray-400 opacity-50 rounded-lg cursor-not-allowed"
        >
          <MoreHorizontal size={20} />
        </button>
      </div>
    </div>
  )
}
