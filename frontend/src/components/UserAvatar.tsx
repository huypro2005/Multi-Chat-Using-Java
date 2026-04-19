interface Props {
  user: { fullName?: string; username?: string; avatarUrl?: string | null }
  size?: number // default 40
}

/**
 * UserAvatar — shared component dùng cho search results, conversation list, v.v.
 * - Có avatarUrl → <img>
 * - Không có avatarUrl → div với initial letter, bg-indigo-100 text-indigo-700
 */
export default function UserAvatar({ user, size = 40 }: Props) {
  const initial = (user.fullName ?? user.username ?? '?').charAt(0).toUpperCase()

  if (user.avatarUrl) {
    return (
      <img
        src={user.avatarUrl}
        alt={user.fullName ?? user.username ?? 'Avatar'}
        width={size}
        height={size}
        className="rounded-full object-cover flex-shrink-0"
        style={{ width: size, height: size }}
      />
    )
  }

  return (
    <div
      className="rounded-full bg-indigo-100 text-indigo-700 font-medium
        flex items-center justify-center flex-shrink-0 select-none"
      style={{ width: size, height: size, fontSize: size * 0.4 }}
      aria-hidden="true"
    >
      {initial}
    </div>
  )
}
