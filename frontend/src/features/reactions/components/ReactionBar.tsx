// ---------------------------------------------------------------------------
// ReactionBar — quick-emoji bar hiện khi hover message bubble (W8-D1)
//
// Hiển thị 6 quick emojis + nút "+" mở full picker.
// Chỉ render khi type !== 'SYSTEM' và !message.deletedAt (enforced bởi caller).
// ---------------------------------------------------------------------------

import { useState } from 'react'
import { Plus } from 'lucide-react'
import { EmojiPicker } from './EmojiPicker'

const QUICK_EMOJIS = ['👍', '❤️', '😂', '😮', '😢', '😠']

interface Props {
  onReact: (emoji: string) => void
}

export function ReactionBar({ onReact }: Props) {
  const [showPicker, setShowPicker] = useState(false)

  return (
    <div className="flex items-center gap-0.5 bg-white dark:bg-gray-800 shadow-md border border-gray-100 dark:border-gray-700 rounded-full px-2 py-1">
      {QUICK_EMOJIS.map((e) => (
        <button
          key={e}
          type="button"
          onClick={() => onReact(e)}
          className="hover:scale-125 transition-transform text-base leading-none p-0.5 rounded-full hover:bg-gray-100 dark:hover:bg-gray-700"
          title={e}
          aria-label={`React ${e}`}
        >
          {e}
        </button>
      ))}
      <button
        type="button"
        onClick={() => setShowPicker((v) => !v)}
        className="flex items-center justify-center w-6 h-6 rounded-full text-gray-400 hover:text-gray-600 dark:hover:text-gray-200 hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors"
        title="Thêm reaction"
        aria-label="Mở bộ chọn emoji"
      >
        <Plus size={14} />
      </button>

      {showPicker && (
        <div className="absolute z-50 bottom-full mb-1">
          <EmojiPicker onSelect={onReact} onClose={() => setShowPicker(false)} />
        </div>
      )}
    </div>
  )
}
