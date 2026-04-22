import { Plus, Users } from 'lucide-react'
import { useParams, useNavigate } from 'react-router-dom'
import { useConversations } from '../hooks'
import { useAuthStore } from '@/stores/authStore'
import ConversationListItem from './ConversationListItem'
import { ConversationSkeleton } from '@/features/common/components/Skeleton'
import { EmptyState } from '@/features/common/components/EmptyState'

interface Props {
  onOpenCreateDialog: () => void
  onOpenCreateGroupDialog?: () => void
}

/**
 * ConversationListSidebar — nội dung bên trong sidebar: search bar, list cuộc trò chuyện.
 * Header (logo + user avatar) vẫn giữ ở ConversationsLayout.
 */
export default function ConversationListSidebar({ onOpenCreateDialog, onOpenCreateGroupDialog }: Props) {
  const { id: activeId } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const currentUserId = useAuthStore((s) => s.user?.id ?? '')

  const { data, isLoading, isError, refetch } = useConversations(0, 50)
  const conversations = data?.content ?? []

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="px-4 py-3 border-b border-gray-100 flex items-center justify-between">
        <h2 className="font-semibold text-gray-900">Tin nhắn</h2>
        <div className="flex items-center gap-1">
          {onOpenCreateGroupDialog && (
            <button
              type="button"
              onClick={onOpenCreateGroupDialog}
              aria-label="Tạo nhóm chat"
              title="Tạo nhóm"
              className="w-8 h-8 rounded-lg flex items-center justify-center
                text-gray-500 hover:bg-indigo-50 hover:text-indigo-600 transition-colors"
            >
              <Users size={16} />
            </button>
          )}
          <button
            type="button"
            onClick={onOpenCreateDialog}
            aria-label="Tạo cuộc trò chuyện mới"
            title="Chat 1-1 mới"
            className="w-8 h-8 rounded-lg flex items-center justify-center
              text-gray-500 hover:bg-indigo-50 hover:text-indigo-600 transition-colors"
          >
            <Plus size={18} />
          </button>
        </div>
      </div>

      {/* Search bar placeholder — Ngày 4 wire */}
      <div className="px-3 py-2 border-b border-gray-100">
        <input
          placeholder="Tìm kiếm..."
          disabled
          className="w-full px-3 py-1.5 text-sm bg-gray-100 rounded-lg
            text-gray-400 cursor-not-allowed outline-none"
          aria-label="Tìm kiếm cuộc trò chuyện (sắp có)"
        />
      </div>

      {/* List area */}
      <div className="flex-1 overflow-y-auto">
        {/* Loading state: 5 skeleton items */}
        {isLoading && (
          <div className="flex flex-col gap-0">
            {Array.from({ length: 5 }).map((_, i) => (
              <ConversationSkeleton key={i} />
            ))}
          </div>
        )}

        {/* Error state */}
        {isError && !isLoading && (
          <div className="p-4 text-sm text-red-600">
            Không thể tải danh sách.{' '}
            <button
              type="button"
              onClick={() => void refetch()}
              className="ml-1 underline hover:no-underline"
            >
              Thử lại
            </button>
          </div>
        )}

        {/* Empty state */}
        {!isLoading && !isError && conversations.length === 0 && (
          <EmptyState
            icon="💬"
            title="Chưa có cuộc trò chuyện"
            description="Bắt đầu nhắn tin với bạn bè hoặc tạo nhóm mới."
            action={
              <button
                type="button"
                onClick={onOpenCreateDialog}
                className="text-sm text-indigo-600 hover:underline"
              >
                Tạo chat mới
              </button>
            }
          />
        )}

        {/* Conversation list */}
        {!isLoading && !isError && conversations.length > 0 && (
          <ul>
            {conversations.map((conv) => (
              <li key={conv.id}>
                <ConversationListItem
                  conversation={conv}
                  isActive={conv.id === activeId}
                  onClick={() => navigate(`/conversations/${conv.id}`)}
                  currentUserId={currentUserId}
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
