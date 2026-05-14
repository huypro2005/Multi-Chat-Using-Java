// ---------------------------------------------------------------------------
// EmojiPicker — wrapper cho @emoji-mart/react
// Lazy-loaded qua dynamic import khi cần (bundle size ~200KB).
// ---------------------------------------------------------------------------

import { lazy, Suspense } from 'react'
import data from '@emoji-mart/data'

// Dynamic import để split bundle
const Picker = lazy(() => import('@emoji-mart/react'))

interface Props {
  onSelect: (emoji: string) => void
  onClose: () => void
}

export function EmojiPicker({ onSelect, onClose }: Props) {
  return (
    <Suspense fallback={<div className="w-64 h-48 bg-white rounded-xl shadow-lg flex items-center justify-center text-sm text-gray-400">Đang tải...</div>}>
      <Picker
        data={data}
        onEmojiSelect={(emoji: { native: string }) => {
          onSelect(emoji.native)
          onClose()
        }}
        theme="auto"
        previewPosition="none"
        searchPosition="sticky"
        maxFrequentRows={1}
        perLine={8}
      />
    </Suspense>
  )
}
