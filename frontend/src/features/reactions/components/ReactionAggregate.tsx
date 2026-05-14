// ---------------------------------------------------------------------------
// ReactionAggregate — hiển thị reaction counts bên dưới message bubble (W8-D1)
//
// Click để toggle (ADDED → REMOVED nếu đã react; REMOVED → ADDED nếu chưa).
// currentUserReacted → highlight màu xanh.
// ---------------------------------------------------------------------------

import type { ReactionAggregateDto } from '@/types/message'

interface Props {
  reactions: ReactionAggregateDto[]
  onToggle: (emoji: string) => void
}

export function ReactionAggregate({ reactions, onToggle }: Props) {
  if (!reactions || reactions.length === 0) return null

  return (
    <div className="flex gap-1 mt-1 flex-wrap">
      {reactions.map((r) => (
        <button
          key={r.emoji}
          type="button"
          onClick={() => onToggle(r.emoji)}
          className={[
            'flex items-center gap-1 px-2 py-0.5 rounded-full text-xs border transition-colors',
            r.currentUserReacted
              ? 'bg-indigo-100 border-indigo-400 text-indigo-700 dark:bg-indigo-900 dark:border-indigo-500 dark:text-indigo-300'
              : 'bg-gray-100 border-gray-300 text-gray-600 hover:bg-gray-200 dark:bg-gray-700 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-600',
          ].join(' ')}
          title={`${r.count} reaction${r.count > 1 ? 's' : ''}`}
          aria-label={`${r.emoji} ${r.count}${r.currentUserReacted ? ' (đã react)' : ''}`}
          aria-pressed={r.currentUserReacted}
        >
          <span aria-hidden="true">{r.emoji}</span>
          <span>{r.count}</span>
        </button>
      ))}
    </div>
  )
}
