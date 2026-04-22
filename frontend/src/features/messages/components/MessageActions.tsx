// ---------------------------------------------------------------------------
// MessageActions — Facebook Messenger style hover action bar
//
// Hiện khi hover bubble (parent cần class `group` từ Tailwind).
// Hiển thị: Reply (D4 stub) + More menu (Copy / Edit / Delete).
// Edit chỉ hiện cho isOwn=true; bị disabled nếu canEdit=false.
// Delete chỉ hiện cho isOwn=true.
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import type { MessageDto } from '@/types/message'

interface Props {
  message: MessageDto
  isOwn: boolean
  /** false nếu đã quá 290s kể từ createdAt */
  canEdit: boolean
  onEdit: () => void
  onDelete: () => void
  onReply: () => void
  onCopy: () => void
  onReact: (emoji: string) => void
  canPin?: boolean
  onTogglePin?: () => void
}

const QUICK_REACTIONS = ['👍', '❤️', '😂', '😮', '😢', '😡'] as const

export function MessageActions({
  message,
  isOwn,
  canEdit,
  onEdit,
  onDelete,
  onReply,
  onCopy,
  onReact,
  canPin = false,
  onTogglePin,
}: Props) {
  const [menuOpen, setMenuOpen] = useState(false)
  const [reactionOpen, setReactionOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)
  const reactionRef = useRef<HTMLDivElement>(null)

  // Click outside đóng menu
  useEffect(() => {
    if (!menuOpen) return
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [menuOpen])

  useEffect(() => {
    if (!reactionOpen) return
    const handler = (e: MouseEvent) => {
      if (reactionRef.current && !reactionRef.current.contains(e.target as Node)) {
        setReactionOpen(false)
      }
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [reactionOpen])

  // Escape key đóng menu
  useEffect(() => {
    if (!menuOpen) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenuOpen(false)
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [menuOpen])

  return (
    <div className="flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
      {/* Reply button — disable khi message đã bị xoá hoặc còn optimistic */}
      <button
        type="button"
        onClick={onReply}
        disabled={!!message.deletedAt || !!message.clientTempId}
        aria-label="Trả lời tin nhắn"
        className="p-1 rounded-full text-gray-400 hover:text-gray-600 hover:bg-gray-200
          transition-colors text-base leading-none disabled:opacity-40 disabled:cursor-not-allowed"
        title="Trả lời"
      >
        ↶
      </button>

      {/* Reaction picker */}
      <div className="relative" ref={reactionRef}>
        <button
          type="button"
          onClick={() => setReactionOpen((v) => !v)}
          disabled={!!message.deletedAt || !!message.clientTempId || message.type === 'SYSTEM'}
          aria-label="Thả cảm xúc"
          aria-expanded={reactionOpen}
          className="p-1 rounded-full text-gray-400 hover:text-gray-600 hover:bg-gray-200
            transition-colors text-base leading-none disabled:opacity-40 disabled:cursor-not-allowed"
          title="Thả cảm xúc"
          style={{ fontSize: '1.5rem' }}
        >
          {/* <img src={reactionTriggerIcon} alt="" className="h-5 w-5" /> */}
          ☺
        </button>
        {reactionOpen && (
          <div
            className="absolute z-50 bottom-full mb-1 bg-white shadow-lg rounded-full border border-gray-100
              px-2 py-1 flex items-center gap-0.5"
            style={{ [isOwn ? 'right' : 'left']: 0 }}
          >
            {QUICK_REACTIONS.map((emoji) => (
              <button
                key={emoji}
                type="button"
                onClick={() => {
                  onReact(emoji)
                  setReactionOpen(false)
                }}
                className="hover:scale-125 transition-transform text-base leading-none p-0.5 rounded-full hover:bg-gray-100"
                aria-label={`React ${emoji}`}
                title={emoji}
              >
                {emoji}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* More actions dropdown */}
      <div className="relative" ref={menuRef}>
        <button
          type="button"
          onClick={() => setMenuOpen((v) => !v)}
          aria-label="Thêm hành động"
          aria-expanded={menuOpen}
          className="p-1 rounded-full text-gray-400 hover:text-gray-600 hover:bg-gray-200
            transition-colors text-base leading-none"
          title="Thêm"
        >
          ⋯
        </button>

        {menuOpen && (
          <div
            className="absolute z-50 bg-white shadow-lg rounded-lg py-1 w-44 text-sm
              border border-gray-100"
            style={{ [isOwn ? 'right' : 'left']: 0, bottom: '100%' }}
          >
            {/* Copy */}
            <button
              type="button"
              onClick={() => {
                onCopy()
                setMenuOpen(false)
              }}
              className="w-full text-left px-4 py-2 hover:bg-gray-100 transition-colors"
            >
              📋 Sao chép
            </button>

            {/* Edit — chỉ cho tin của mình */}
            {isOwn && (
              <button
                type="button"
                onClick={() => {
                  onEdit()
                  setMenuOpen(false)
                }}
                disabled={!canEdit}
                className="w-full text-left px-4 py-2 hover:bg-gray-100 transition-colors
                  disabled:opacity-40 disabled:cursor-not-allowed"
              >
                ✏ Sửa
              </button>
            )}

            {/* Delete — chỉ cho tin của mình */}
            {isOwn && (
              <button
                type="button"
                onClick={() => {
                  onDelete()
                  setMenuOpen(false)
                }}
                className="w-full text-left px-4 py-2 hover:bg-gray-100 transition-colors
                  text-red-500"
              >
                🗑 Xóa
              </button>
            )}

            {canPin && onTogglePin && (
              <button
                type="button"
                onClick={() => {
                  onTogglePin()
                  setMenuOpen(false)
                }}
                className="w-full text-left px-4 py-2 hover:bg-gray-100 transition-colors"
              >
                {message.pinnedAt ? '📌 Bỏ ghim' : '📌 Ghim tin nhắn'}
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
