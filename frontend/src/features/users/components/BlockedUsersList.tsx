import UserAvatar from '@/components/UserAvatar'
import { useBlockedUsers, useUnblockUser } from '../hooks'
import type { UserDto } from '@/types/auth'

function BlockedUserRow({ user }: { user: UserDto }) {
  const unblock = useUnblockUser(user.id)

  return (
    <div className="flex items-center justify-between rounded-lg border border-gray-200 px-3 py-2">
      <div className="flex min-w-0 items-center gap-2">
        <UserAvatar user={{ fullName: user.fullName, avatarUrl: user.avatarUrl }} size={32} />
        <div className="min-w-0">
          <p className="truncate text-sm font-medium text-gray-900">{user.fullName}</p>
          <p className="truncate text-xs text-gray-500">@{user.username}</p>
        </div>
      </div>
      <button
        type="button"
        onClick={() => unblock.mutate()}
        disabled={unblock.isPending}
        className="text-xs font-medium text-indigo-600 transition-colors hover:text-indigo-700 disabled:opacity-50"
      >
        Bỏ chặn
      </button>
    </div>
  )
}

export function BlockedUsersList() {
  const { data, isLoading } = useBlockedUsers()

  if (isLoading) return <p className="py-3 text-sm text-gray-500">Đang tải danh sách...</p>
  if (!data || data.length === 0) {
    return <p className="py-3 text-sm text-gray-500">Bạn chưa chặn người dùng nào.</p>
  }

  return (
    <div className="space-y-2">
      <p className="text-sm font-semibold text-gray-900">Người dùng đã chặn ({data.length})</p>
      {data.map((user) => (
        <BlockedUserRow key={user.id} user={user} />
      ))}
    </div>
  )
}
