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
}

export function MessageActions({ message, isOwn, canEdit, onEdit, onDelete, onReply, onCopy }: Props) {
  const [menuOpen, setMenuOpen] = useState(false)
  const menuRef = useRef<HTMLDivElement>(null)

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
        ↺
      </button>

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
          </div>
        )}
      </div>
    </div>
  )
}
