import { Pin } from 'lucide-react'
import type { MessageDto } from '@/types/message'

interface Props {
  pinnedMessages: MessageDto[]
  onScrollTo: (messageId: string) => void
}

function previewText(msg: MessageDto): string {
  const text = msg.content?.trim()
  if (text) return text
  if (msg.attachments && msg.attachments.length > 0) return '(Tệp đính kèm)'
  return '(Tin nhắn)'
}

export function PinnedMessagesBanner({ pinnedMessages, onScrollTo }: Props) {
  if (pinnedMessages.length === 0) return null

  return (
    <div
      className="flex-shrink-0 border-b border-gray-200 bg-white px-3 py-1.5 shadow-sm"
      aria-label={`${pinnedMessages.length} tin nhắn đã ghim`}
    >
      {pinnedMessages.map((msg, index) => (
        <button
          key={msg.id}
          type="button"
          onClick={() => onScrollTo(msg.id)}
          className={`flex w-full items-center gap-2 rounded-md px-2 py-2 text-left transition-colors
            hover:bg-gray-100 active:bg-gray-200
            ${index > 0 ? 'border-t border-gray-100' : ''}`}
        >
          <Pin
            size={16}
            className="flex-shrink-0 text-indigo-500"
            aria-hidden="true"
          />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-gray-900">
              {previewText(msg)}
            </p>
            {msg.pinnedBy && (
              <p className="truncate text-xs text-gray-500">
                Ghim bởi {msg.pinnedBy.userName}
              </p>
            )}
          </div>
        </button>
      ))}
    </div>
  )
}
