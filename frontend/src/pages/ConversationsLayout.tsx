import { Outlet, useParams } from 'react-router-dom'
import { MessageSquare } from 'lucide-react'
import { useAuthStore } from '@/stores/authStore'

/**
 * ConversationsLayout — layout 2 cột cho trang conversations.
 *
 * Desktop: sidebar 320px bên trái + main area flex-1 bên phải.
 * Mobile: chỉ hiện sidebar khi ở /conversations (index),
 *         chỉ hiện main khi ở /conversations/:id.
 */
export default function ConversationsLayout() {
  const { id } = useParams()
  const user = useAuthStore((s) => s.user)

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
        {/* Sidebar header */}
        <div className="flex items-center justify-between px-4 py-3 border-b border-gray-100">
          <div className="flex items-center gap-2">
            <MessageSquare className="text-indigo-600" size={20} />
            <h1 className="text-base font-semibold text-gray-900">Tin nhắn</h1>
          </div>

          {/* Avatar placeholder */}
          <div
            className="w-8 h-8 rounded-full bg-indigo-100 flex items-center justify-center
              text-indigo-600 text-sm font-medium select-none"
            aria-label={user?.fullName ?? 'Người dùng'}
            title={user?.fullName ?? 'Người dùng'}
          >
            {user?.fullName?.charAt(0).toUpperCase() ?? '?'}
          </div>
        </div>

        {/* Conversation list placeholder — sẽ implement Ngày 3 */}
        <div className="flex-1 flex flex-col items-center justify-center gap-2 text-gray-400 p-6">
          <MessageSquare size={40} className="opacity-30" />
          <p className="text-sm text-center">Danh sách cuộc trò chuyện</p>
          <p className="text-xs text-center opacity-70">(sẽ có ở Ngày 3)</p>
        </div>
      </aside>

      {/* ── Main area ── */}
      <main className={`${mainMobileClass} flex-1 flex-col min-w-0`}>
        <Outlet />
      </main>
    </div>
  )
}
