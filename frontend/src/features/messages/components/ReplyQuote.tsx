// ---------------------------------------------------------------------------
// ReplyQuote — hiển thị preview của message được reply bên trong bubble
// ---------------------------------------------------------------------------

import type { ReplyPreviewDto } from '@/types/message'

interface Props {
  replyTo: ReplyPreviewDto
}

export function ReplyQuote({ replyTo }: Props) {
  const isDeleted = !!replyTo.deletedAt

  return (
    <div className="border-l-4 border-blue-400 bg-gray-50 dark:bg-gray-800 rounded px-3 py-1.5 mb-1 text-sm">
      <div className="font-semibold text-blue-600 text-xs mb-0.5">{replyTo.senderName}</div>
      <div className="text-gray-500 italic truncate">
        {isDeleted ? 'Tin nhắn đã bị xóa' : (replyTo.contentPreview ?? '')}
      </div>
    </div>
  )
}
