import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { X } from 'lucide-react'
import { useUserSearch } from '@/features/users/hooks'
import { useCreateConversation } from '../hooks'
import { ConversationType } from '@/types/conversation'
import type { UserSearchDto } from '@/types/conversation'
import type { ApiErrorBody } from '@/types/api'
import UserAvatar from '@/components/UserAvatar'

interface Props {
  open: boolean
  onClose: () => void
}

/**
 * CreateConversationDialog — modal tạo conversation mới.
 * Tab "Chat 1-1" active; "Tạo nhóm" disabled (sắp có).
 * Tìm user → click → tạo/redirect conversation.
 * 409 CONV_ONE_ON_ONE_EXISTS → redirect sang conv cũ (không show error).
 */
export default function CreateConversationDialog({ open, onClose }: Props) {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const { data: results, isFetching: isSearching } = useUserSearch(query)
  const { mutateAsync: createConversation, isPending: isCreating } = useCreateConversation()

  // Esc key để đóng dialog
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleClose()
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  function handleClose() {
    setQuery('')
    setError(null)
    onClose()
  }

  async function handleSelectUser(user: UserSearchDto) {
    setError(null)
    try {
      const result = await createConversation({
        type: ConversationType.ONE_ON_ONE,
        memberIds: [user.id],
      })

      if (result.conversation) {
        navigate(`/conversations/${result.conversation.id}`)
      } else if (result.existingConversationId) {
        // 409 UX pattern: redirect sang conv cũ, không show error
        navigate(`/conversations/${result.existingConversationId}`)
      }
      handleClose()
    } catch (err: unknown) {
      const apiErr = err as { response?: { data?: ApiErrorBody } }
      if (apiErr.response?.data?.error === 'RATE_LIMITED') {
        setError('Bạn đang tạo quá nhiều cuộc trò chuyện. Vui lòng thử lại sau.')
      } else {
        setError('Không thể tạo cuộc trò chuyện. Vui lòng thử lại.')
      }
    }
  }

  if (!open) return null

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/50 z-50 flex items-center justify-center"
        onClick={handleClose}
        aria-hidden="true"
      />

      {/* Dialog */}
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Tạo cuộc trò chuyện mới"
        className="fixed z-50 inset-0 flex items-center justify-center pointer-events-none"
      >
        <div
          className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 p-6 pointer-events-auto"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Dialog header */}
          <div className="flex items-center justify-between mb-4">
            <h3 className="text-base font-semibold text-gray-900">
              Cuộc trò chuyện mới
            </h3>
            <button
              type="button"
              onClick={handleClose}
              aria-label="Đóng dialog"
              className="w-8 h-8 rounded-lg flex items-center justify-center
                text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors"
            >
              <X size={18} />
            </button>
          </div>

          {/* Tabs */}
          <div className="flex gap-2 mb-4">
            <button
              type="button"
              className="px-4 py-2 rounded-lg bg-indigo-600 text-white text-sm font-medium"
            >
              Chat 1-1
            </button>
            <button
              type="button"
              disabled
              className="px-4 py-2 rounded-lg bg-gray-100 text-gray-400 text-sm font-medium cursor-not-allowed"
            >
              Tạo nhóm (sắp có)
            </button>
          </div>

          {/* Search input */}
          <div>
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Tìm username hoặc tên..."
              disabled={isCreating}
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm
                focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent
                disabled:bg-gray-50 disabled:text-gray-400"
            />

            {/* Error state (e.g. 429 RATE_LIMITED) */}
            {error && (
              <p role="alert" className="mt-2 text-sm text-red-600">{error}</p>
            )}

            {/* Hint khi < 2 chars */}
            {query.length > 0 && query.length < 2 && (
              <p className="text-xs text-gray-500 mt-1">
                Nhập ít nhất 2 ký tự
              </p>
            )}

            {/* Searching indicator */}
            {isSearching && (
              <div className="mt-2 text-sm text-gray-500">Đang tìm...</div>
            )}

            {/* No results */}
            {!isSearching && query.length >= 2 && results?.length === 0 && (
              <p className="mt-2 text-sm text-gray-500">
                Không tìm thấy người dùng
              </p>
            )}

            {/* Results list */}
            {results && results.length > 0 && (
              <ul className="mt-2 border border-gray-200 rounded-lg divide-y divide-gray-100 max-h-64 overflow-y-auto">
                {results.map((user) => (
                  <li key={user.id}>
                    <button
                      type="button"
                      onClick={() => void handleSelectUser(user)}
                      disabled={isCreating}
                      className="w-full flex items-center gap-3 px-3 py-2.5
                        hover:bg-gray-50 text-left transition-colors
                        disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                      <UserAvatar user={user} size={36} />
                      <div className="min-w-0">
                        <p className="text-sm font-medium text-gray-900 truncate">
                          {user.fullName}
                        </p>
                        <p className="text-xs text-gray-500">
                          @{user.username}
                        </p>
                      </div>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>
    </>
  )
}
