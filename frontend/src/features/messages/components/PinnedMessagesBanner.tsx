import { useMemo, useState } from 'react'
import type { MessageDto } from '@/types/message'

interface Props {
  pinnedMessages: MessageDto[]
  onScrollTo: (messageId: string) => void
}

export function PinnedMessagesBanner({ pinnedMessages, onScrollTo }: Props) {
  const [expanded, setExpanded] = useState(false)

  const visibleItems = useMemo(
    () => (expanded ? pinnedMessages : pinnedMessages.slice(0, 1)),
    [expanded, pinnedMessages],
  )

  if (pinnedMessages.length === 0) return null

  return (
    <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 dark:border-amber-800 dark:bg-amber-900/20">
      <div className="mb-1 flex items-center justify-between">
        <p className="text-xs font-semibold text-amber-800 dark:text-amber-200">
          📌 {pinnedMessages.length} tin nhắn đã ghim
        </p>
        {pinnedMessages.length > 1 && (
          <button
            type="button"
            onClick={() => setExpanded((prev) => !prev)}
            className="text-xs text-amber-700 transition-colors hover:text-amber-900 hover:underline dark:text-amber-300 dark:hover:text-amber-100"
          >
            {expanded ? 'Thu gọn' : `Xem thêm ${pinnedMessages.length - 1}`}
          </button>
        )}
      </div>

      <div className="space-y-1">
        {visibleItems.map((msg) => (
          <button
            key={msg.id}
            type="button"
            onClick={() => onScrollTo(msg.id)}
            className="w-full rounded px-2 py-1 text-left transition-colors hover:bg-amber-100 dark:hover:bg-amber-800/30"
          >
            <p className="truncate text-sm text-gray-800 dark:text-gray-100">
              {msg.content?.trim() ? msg.content : '(Tệp đính kèm)'}
            </p>
            {msg.pinnedBy && (
              <p className="text-xs text-gray-500 dark:text-gray-400">
                Ghim bởi {msg.pinnedBy.userName}
              </p>
            )}
          </button>
        ))}
      </div>
    </div>
  )
}
