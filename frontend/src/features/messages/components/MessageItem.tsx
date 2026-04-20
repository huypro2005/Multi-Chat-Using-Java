import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { format } from 'date-fns'
import { AlertCircle, Check, Loader2, Pencil, RotateCcw, X } from 'lucide-react'
import UserAvatar from '@/components/UserAvatar'
import type { MessageDto, MessageListResponse } from '@/types/message'
import { useQueryClient } from '@tanstack/react-query'
import { messageKeys } from '@/features/conversations/queryKeys'
import { useSendMessage } from '../hooks'
import { useEditMessage } from '../useEditMessage'

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
/** Edit window = 5 phút, nhưng FE disable sớm 10s để tránh clock skew (§8 Limitations) */
const EDIT_WINDOW_MS = 290_000 // 4:50

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
// RetryButton — hiện khi status='failed', chỉ dành cho tin của chính mình
// ---------------------------------------------------------------------------
interface RetryButtonProps {
  message: MessageDto
}

function RetryButton({ message }: RetryButtonProps) {
  const queryClient = useQueryClient()
  const convId = message.conversationId
  const sendMessage = useSendMessage(convId)

  const handleRetry = useCallback(() => {
    // 1. Xoá message failed khỏi cache trước khi gửi lại
    queryClient.setQueryData(
      messageKeys.all(convId),
      (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
        if (!old) return old
        const pages = old.pages.map((page) => ({
          ...page,
          items: page.items.filter((m) => m.clientTempId !== message.clientTempId),
        }))
        return { ...old, pages }
      },
    )

    // 2. Gửi lại với tempId MỚI (không reuse tempId cũ — contract mục 3b.4)
    try {
      sendMessage(message.content)
    } catch {
      // Nếu STOMP vẫn chưa connect → thông báo ngầm (không crash UI)
      // MessageInput sẽ disable send button khi mất kết nối
    }
  }, [convId, message, queryClient, sendMessage])

  return (
    <div className="flex items-center gap-1.5 mt-1 justify-end">
      <span className="text-xs text-red-400">
        {message.failureCode === 'TIMEOUT'
          ? 'Không phản hồi'
          : message.failureReason ?? 'Gửi thất bại'}
      </span>
      <button
        type="button"
        onClick={handleRetry}
        aria-label="Thử lại gửi tin nhắn"
        className="flex items-center gap-1 text-xs text-red-500 hover:text-red-700
          font-medium transition-colors"
      >
        <RotateCcw size={11} />
        Thử lại
      </button>
    </div>
  )
}

// ---------------------------------------------------------------------------
// InlineEditArea — textarea nội tuyến để sửa tin nhắn
// ---------------------------------------------------------------------------
interface InlineEditAreaProps {
  initialContent: string
  messageId: string
  convId: string
  failureCode?: string
  failureReason?: string
  onClose: () => void
}

function InlineEditArea({
  initialContent,
  messageId,
  convId,
  failureCode,
  failureReason,
  onClose,
}: InlineEditAreaProps) {
  const [draft, setDraft] = useState(initialContent)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const { editMessage } = useEditMessage(convId)

  // Auto-focus khi mở
  useEffect(() => {
    textareaRef.current?.focus()
    // Đặt cursor cuối dòng
    const el = textareaRef.current
    if (el) {
      el.setSelectionRange(el.value.length, el.value.length)
    }
  }, [])

  // Auto-resize
  const autoResize = useCallback(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 5 * 24) + 'px'
  }, [])

  const handleSave = useCallback(() => {
    const trimmed = draft.trim()
    if (!trimmed) return
    try {
      editMessage(messageId, trimmed)
      onClose()
    } catch {
      // STOMP not connected — UI không crash, show implicit error
    }
  }, [draft, editMessage, messageId, onClose])

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSave()
    }
    if (e.key === 'Escape') {
      onClose()
    }
  }

  const isSaving = !!failureCode === false && draft !== initialContent

  return (
    <div className="flex flex-col gap-1 w-full max-w-xs sm:max-w-sm md:max-w-md">
      <textarea
        ref={textareaRef}
        value={draft}
        onChange={(e) => {
          setDraft(e.target.value)
          autoResize()
        }}
        onKeyDown={handleKeyDown}
        rows={1}
        aria-label="Sửa nội dung tin nhắn"
        className="resize-none rounded-xl border border-indigo-300 bg-white px-3 py-2 text-sm
          text-gray-800 focus:outline-none focus:ring-2 focus:ring-indigo-500
          focus:border-transparent overflow-y-auto"
      />

      {/* Error display */}
      {failureCode && (
        <p className="text-xs text-red-500" role="alert">
          {failureCode === 'TIMEOUT'
            ? 'Server không phản hồi, thử lại'
            : failureCode === 'MSG_EDIT_WINDOW_EXPIRED'
              ? 'Đã hết thời gian sửa (5 phút)'
              : failureCode === 'MSG_NOT_FOUND'
                ? 'Tin nhắn không tồn tại hoặc không thể sửa'
                : failureCode === 'MSG_CONTENT_TOO_LONG'
                  ? 'Nội dung quá dài (tối đa 5000 ký tự)'
                  : failureReason ?? 'Không thể sửa tin nhắn'}
        </p>
      )}

      {/* Action buttons */}
      <div className="flex items-center gap-1.5 justify-end">
        <span className="text-xs text-gray-400">Enter lưu · Esc huỷ</span>
        <button
          type="button"
          onClick={onClose}
          aria-label="Huỷ sửa"
          className="p-1 rounded text-gray-400 hover:text-gray-600 hover:bg-gray-100
            transition-colors"
        >
          <X size={13} />
        </button>
        <button
          type="button"
          onClick={handleSave}
          disabled={!draft.trim() || isSaving}
          aria-label="Lưu chỉnh sửa"
          className="p-1 rounded text-indigo-600 hover:text-indigo-800 hover:bg-indigo-50
            disabled:opacity-40 transition-colors"
        >
          <Check size={13} />
        </button>
      </div>
    </div>
  )
}

// ---------------------------------------------------------------------------
// MessageItem — bọc React.memo vì list có thể 100+ items
// ---------------------------------------------------------------------------
const MessageItem = memo(function MessageItem({ message, isOwn, showAvatar }: Props) {
  const [isEditing, setIsEditing] = useState(false)

  // Dùng clientTempId để detect optimistic; nếu không có → dùng heuristic id
  const isSending = message.status === 'sending'
  const isFailed = message.status === 'failed'
  const isTemp = isSending || isFailed || message.id.startsWith('temp-')
  const timeLabel = format(new Date(message.createdAt), 'HH:mm')

  // Edit eligibility — tính từ createdAt timestamp, không dùng Date.now() trong render.
  // Dùng useMemo với messageAge (số giây) thay vì gọi Date.now() trực tiếp.
  const messageAgeMs = useMemo(
    () => new Date().getTime() - new Date(message.createdAt).getTime(),
    [message.createdAt], // chỉ recompute khi createdAt đổi (message mới)
  )
  const canEdit =
    isOwn &&
    !message.clientTempId && // chưa confirmed (optimistic)
    !message.failureCode && // message đang có lỗi send
    messageAgeMs < EDIT_WINDOW_MS

  const handleOpenEdit = useCallback(() => {
    setIsEditing(true)
  }, [])

  const handleCloseEdit = useCallback(() => {
    setIsEditing(false)
  }, [])

  // --- Bubble sent by current user ---
  if (isOwn) {
    return (
      <div className="flex flex-col items-end gap-0.5 group">
        <div className="flex justify-end items-end gap-1.5">
          {/* Timestamp — hiện khi hover */}
          <span
            className="text-xs text-gray-400 opacity-0 group-hover:opacity-100
              transition-opacity duration-150 self-end mb-1 select-none"
            aria-hidden="true"
          >
            {timeLabel}
          </span>

          <div className={`max-w-xs sm:max-w-sm md:max-w-md ${isFailed ? 'opacity-60' : ''}`}>
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

            {/* Inline edit mode */}
            {isEditing ? (
              <InlineEditArea
                initialContent={message.content}
                messageId={message.id}
                convId={message.conversationId}
                failureCode={message.failureCode}
                failureReason={message.failureReason}
                onClose={handleCloseEdit}
              />
            ) : (
              <div
                className={`rounded-2xl rounded-br-sm px-4 py-2 text-sm whitespace-pre-wrap break-words
                  ${isFailed ? 'bg-red-100 text-red-800 border border-red-200' : 'bg-indigo-600 text-white'}`}
              >
                {message.content}
                {/* "(đã chỉnh sửa)" nhỏ khi editedAt có giá trị */}
                {message.editedAt && (
                  <span className="text-indigo-200 text-xs ml-1.5 opacity-75">(đã chỉnh sửa)</span>
                )}
              </div>
            )}
          </div>

          {/* Status icon */}
          <div
            className="self-end mb-1 flex-shrink-0"
            aria-label={isSending ? 'Đang gửi' : isFailed ? 'Gửi thất bại' : 'Đã gửi'}
          >
            {isSending ? (
              <Loader2 size={12} className="text-indigo-400 animate-spin" />
            ) : isFailed ? (
              <AlertCircle size={12} className="text-red-400" />
            ) : (
              <span className="text-indigo-400 text-xs leading-none">✓</span>
            )}
          </div>
        </div>

        {/* Edit button — chỉ hiện khi hover + canEdit + không đang editing */}
        {canEdit && !isEditing && !isTemp && (
          <button
            type="button"
            onClick={handleOpenEdit}
            aria-label="Sửa tin nhắn"
            className="opacity-0 group-hover:opacity-100 transition-opacity
              text-xs text-gray-400 hover:text-indigo-600 flex items-center gap-0.5
              mr-9 -mt-0.5"
          >
            <Pencil size={10} />
            Sửa
          </button>
        )}

        {/* Retry row — chỉ hiện khi failed (send failure, không phải edit failure) */}
        {isFailed && isTemp && (
          <RetryButton message={message} />
        )}
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
          {/* "(đã chỉnh sửa)" nhỏ khi editedAt có giá trị */}
          {message.editedAt && (
            <span className="text-gray-400 text-xs ml-1.5 opacity-75">(đã chỉnh sửa)</span>
          )}
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
