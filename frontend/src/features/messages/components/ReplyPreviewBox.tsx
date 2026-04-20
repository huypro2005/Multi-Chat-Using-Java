// ---------------------------------------------------------------------------
// ReplyPreviewBox — box hiển thị trên MessageInput khi user chọn reply
// Escape key để cancel
// ---------------------------------------------------------------------------

import { useEffect } from 'react'
import type { MessageDto } from '@/types/message'

interface Props {
  replyingTo: MessageDto | null
  onCancel: () => void
}

export function ReplyPreviewBox({ replyingTo, onCancel }: Props) {
  // Escape key cancel
  useEffect(() => {
    if (!replyingTo) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [replyingTo, onCancel])

  if (!replyingTo) return null

  return (
    <div
      className="border-l-4 border-blue-400 bg-gray-50 dark:bg-gray-800
        px-3 py-2 flex justify-between items-start mx-4 mb-1 rounded"
    >
      <div className="min-w-0 flex-1">
        <div className="text-xs font-bold text-blue-600">
          Trả lời {replyingTo.sender.fullName}
        </div>
        <div className="text-sm text-gray-500 italic truncate max-w-xs">
          {replyingTo.deletedAt
            ? 'Tin nhắn đã bị xóa'
            : (replyingTo.content ?? '').slice(0, 100)}
        </div>
      </div>
      <button
        type="button"
        onClick={onCancel}
        aria-label="Hủy trả lời"
        className="text-gray-400 hover:text-gray-600 ml-2 flex-shrink-0 transition-colors"
      >
        ✕
      </button>
    </div>
  )
}
