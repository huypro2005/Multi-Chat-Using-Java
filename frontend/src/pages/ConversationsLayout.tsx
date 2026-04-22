import { useState } from 'react'
import { Link, Outlet, useNavigate, useParams } from 'react-router-dom'
import { MessageSquare } from 'lucide-react'
import { useAuthStore } from '@/stores/authStore'
import ConversationListSidebar from '@/features/conversations/components/ConversationListSidebar'
import CreateConversationDialog from '@/features/conversations/components/CreateConversationDialog'
import CreateGroupDialog from '@/features/conversations/components/CreateGroupDialog'

/**
 * ConversationsLayout — layout 2 cột cho trang conversations.
 *
 * Desktop: sidebar 320px bên trái + main area flex-1 bên phải.
 * Mobile: chỉ hiện sidebar khi ở /conversations (index),
 *         chỉ hiện main khi ở /conversations/:id.
 */
export default function ConversationsLayout() {
  const { id } = useParams()
  const navigate = useNavigate()
  const user = useAuthStore((s) => s.user)
  const [createDialogOpen, setCreateDialogOpen] = useState(false)
  const [createGroupDialogOpen, setCreateGroupDialogOpen] = useState(false)

  // Mobile: có :id → ẩn sidebar, hiện main; không có :id → hiện sidebar, ẩn main
  const sidebarMobileClass = id ? 'hidden md:flex' : 'flex md:flex'
  const mainMobileClass = id ? 'flex md:flex' : 'hidden md:flex'

  return (
    <div className="h-screen flex overflow-hidden bg-gray-50">
      {/* ── Sidebar ── */}
      <aside
        className={`${sidebarMobileClass} flex-col w-full md:w-80 flex-shrink-0
          bg-white border-r border-gray-200`}
      >
        {/* Sidebar top header: logo + user avatar */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
          <div className="flex items-center gap-2">
            <MessageSquare className="text-indigo-600" size={20} />
            <h1 className="text-base font-semibold text-gray-900">Chat App</h1>
          </div>

          <div className="flex items-center gap-2">
            <Link to="/profile" className="text-xs text-gray-600 hover:text-indigo-600">
              Hồ sơ
            </Link>
            <Link to="/settings" className="text-xs text-gray-600 hover:text-indigo-600">
              Cài đặt
            </Link>
            <div
              className="w-8 h-8 rounded-full bg-indigo-100 flex items-center justify-center
                text-indigo-600 text-sm font-medium select-none"
              aria-label={user?.fullName ?? 'Người dùng'}
              title={user?.fullName ?? 'Người dùng'}
            >
              {user?.fullName?.charAt(0).toUpperCase() ?? '?'}
            </div>
          </div>
        </div>

        {/* Conversation list sidebar (header + search + list) */}
        <div className="flex-1 overflow-hidden flex flex-col">
          <ConversationListSidebar
            onOpenCreateDialog={() => setCreateDialogOpen(true)}
            onOpenCreateGroupDialog={() => setCreateGroupDialogOpen(true)}
          />
        </div>
      </aside>

      {/* ── Main area ── */}
      <main className={`${mainMobileClass} flex-1 flex-col min-w-0`}>
        <Outlet />
      </main>

      {/* Create 1-1 conversation dialog */}
      <CreateConversationDialog
        open={createDialogOpen}
        onClose={() => setCreateDialogOpen(false)}
        onCreateGroup={() => setCreateGroupDialogOpen(true)}
      />

      {/* Create group dialog */}
      <CreateGroupDialog
        open={createGroupDialogOpen}
        onClose={() => setCreateGroupDialogOpen(false)}
        onCreated={(convId) => navigate(`/conversations/${convId}`)}
      />
    </div>
  )
}
