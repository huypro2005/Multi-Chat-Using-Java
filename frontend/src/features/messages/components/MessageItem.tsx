import { memo } from 'react'
import { format } from 'date-fns'
import { Loader2 } from 'lucide-react'
import UserAvatar from '@/components/UserAvatar'
import type { MessageDto } from '@/types/message'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
interface Props {
  message: MessageDto
  isOwn: boolean
  /** false khi cùng sender và gap < 1 phút với message trước */
  showAvatar: boolean
}

// ---------------------------------------------------------------------------
// MessageItem — bọc React.memo vì list có thể 100+ items
// ---------------------------------------------------------------------------
const MessageItem = memo(function MessageItem({ message, isOwn, showAvatar }: Props) {
  const isTemp = message.id.startsWith('temp-')
  const timeLabel = format(new Date(message.createdAt), 'HH:mm')

  // --- Bubble sent by current user ---
  if (isOwn) {
    return (
      <div className="flex justify-end items-end gap-1.5 group">
        {/* Timestamp — hiện khi hover */}
        <span
          className="text-xs text-gray-400 opacity-0 group-hover:opacity-100
            transition-opacity duration-150 self-end mb-1 select-none"
          aria-hidden="true"
        >
          {timeLabel}
        </span>

        <div className="max-w-xs sm:max-w-sm md:max-w-md">
          {/* Reply preview */}
          {message.replyToMessage && (
            <div
              className="mb-1 ml-auto max-w-full border-l-2 border-indigo-300 bg-indigo-50
                pl-2 pr-3 py-1 rounded text-xs text-gray-500 italic truncate opacity-80"
            >
              <span className="font-medium not-italic">{message.replyToMessage.senderName}: </span>
              {message.replyToMessage.contentPreview}
            </div>
          )}

          {/* Bubble */}
          <div
            className="bg-indigo-600 text-white rounded-2xl rounded-br-sm
              px-4 py-2 text-sm whitespace-pre-wrap break-words"
          >
            {message.content}
          </div>
        </div>

        {/* Status icon */}
        <div className="self-end mb-1 flex-shrink-0" aria-label={isTemp ? 'Đang gửi' : 'Đã gửi'}>
          {isTemp ? (
            <Loader2 size={12} className="text-indigo-400 animate-spin" />
          ) : (
            <span className="text-indigo-400 text-xs leading-none">✓</span>
          )}
        </div>
      </div>
    )
  }

  // --- Bubble from other user ---
  return (
    <div className="flex justify-start items-end gap-1.5 group">
      {/* Avatar — chỉ hiện khi showAvatar=true, giữ chỗ khi false */}
      <div className="flex-shrink-0 self-end" style={{ width: 28, height: 28 }}>
        {showAvatar ? (
          <UserAvatar user={message.sender} size={28} />
        ) : null}
      </div>

      <div className="max-w-xs sm:max-w-sm md:max-w-md">
        {/* Sender name — chỉ hiện khi showAvatar=true */}
        {showAvatar && (
          <p className="text-xs text-gray-500 mb-0.5 ml-1">{message.sender.fullName}</p>
        )}

        {/* Reply preview */}
        {message.replyToMessage && (
          <div
            className="mb-1 max-w-full border-l-2 border-indigo-400 bg-gray-100
              pl-2 pr-3 py-1 rounded text-xs text-gray-500 italic truncate opacity-80"
          >
            <span className="font-medium not-italic">{message.replyToMessage.senderName}: </span>
            {message.replyToMessage.contentPreview}
          </div>
        )}

        {/* Bubble */}
        <div
          className="bg-white border border-gray-200 rounded-2xl rounded-bl-sm
            px-4 py-2 text-sm text-gray-800 whitespace-pre-wrap break-words"
        >
          {message.content}
        </div>
      </div>

      {/* Timestamp — hiện khi hover */}
      <span
        className="text-xs text-gray-400 opacity-0 group-hover:opacity-100
          transition-opacity duration-150 self-end mb-1 select-none"
        aria-hidden="true"
      >
        {timeLabel}
      </span>
    </div>
  )
})

export default MessageItem
