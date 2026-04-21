import { memo } from 'react'
import type { ConversationSummaryDto } from '@/types/conversation'
import { ConversationType } from '@/types/conversation'
import { formatLastMessageTime } from '../utils'

interface Props {
  conversation: ConversationSummaryDto
  isActive: boolean
  onClick: () => void
  currentUserId: string
}

/**
 * ConversationListItem — một row trong sidebar conversation list.
 * Dùng ConversationSummaryDto (list API) — displayName và displayAvatarUrl đã server-computed.
 * React.memo để tránh re-render khi conversation không thay đổi.
 */
const ConversationListItem = memo(function ConversationListItem({
  conversation,
  isActive,
  onClick,
}: Props) {
  const initial = conversation.displayName.charAt(0).toUpperCase()

  return (
    <button
      type="button"
      onClick={onClick}
      className={`w-full flex items-center gap-3 px-3 py-3 text-left transition-colors
        ${
          isActive
            ? 'bg-indigo-50 border-l-4 border-l-indigo-600'
            : 'border-l-4 border-l-transparent hover:bg-gray-50'
        }`}
      aria-current={isActive ? 'page' : undefined}
    >
      {/* Avatar with optional group badge */}
      <div className="flex-shrink-0 relative">
        {conversation.displayAvatarUrl ? (
          <img
            src={conversation.displayAvatarUrl}
            alt={conversation.displayName}
            width={48}
            height={48}
            className="rounded-full object-cover"
            style={{ width: 48, height: 48 }}
          />
        ) : (
          <div
            className={`w-12 h-12 rounded-full flex items-center justify-center text-lg font-medium select-none
              ${conversation.type === ConversationType.GROUP
                ? 'bg-purple-100 text-purple-700'
                : 'bg-indigo-100 text-indigo-700'}`}
            aria-hidden="true"
          >
            {initial}
          </div>
        )}
        {/* Group badge — small indicator at bottom-right of avatar */}
        {conversation.type === ConversationType.GROUP && (
          <span
            className="absolute bottom-0 right-0 w-4 h-4 rounded-full bg-purple-500
              flex items-center justify-center text-white"
            aria-label="Nhóm"
            title="Nhóm chat"
          >
            <svg viewBox="0 0 12 12" fill="currentColor" className="w-2.5 h-2.5" aria-hidden="true">
              <path d="M6 6a2.5 2.5 0 100-5 2.5 2.5 0 000 5zm-4 4a4 4 0 018 0H2z" />
            </svg>
          </span>
        )}
      </div>

      {/* Content */}
      <div className="flex-1 min-w-0">
        <div className="flex items-center justify-between gap-1">
          <p className="text-sm font-medium text-gray-900 truncate">
            {conversation.displayName}
          </p>
          <span className="text-xs text-gray-400 flex-shrink-0">
            {formatLastMessageTime(conversation.lastMessageAt)}
          </span>
        </div>
        <p className="text-xs text-gray-500 truncate mt-0.5">
          Bắt đầu trò chuyện
        </p>
      </div>

      {/* Unread badge (V1 always 0, render ngay khi > 0 để sẵn sàng) */}
      {conversation.unreadCount > 0 && (
        <span
          className="flex-shrink-0 min-w-[20px] h-5 px-1 rounded-full
            bg-indigo-600 text-white text-xs font-medium flex items-center justify-center"
        >
          {conversation.unreadCount > 99 ? '99+' : conversation.unreadCount}
        </span>
      )}
    </button>
  )
})

export default ConversationListItem
