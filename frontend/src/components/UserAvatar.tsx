import { useProtectedObjectUrl } from '@/features/files/hooks/useProtectedObjectUrl'

interface Props {
  user: { fullName?: string; username?: string; avatarUrl?: string | null }
  size?: number // default 40
}

/**
 * UserAvatar — shared component dùng cho search results, conversation list, v.v.
 * - Public URL (/public suffix, ADR-021): native <img src> — no auth needed.
 * - Private URL (/api/files/{id} without /public): useProtectedObjectUrl hook.
 * - Không có avatarUrl → div với initial letter.
 */
export default function UserAvatar({ user, size = 40 }: Props) {
  const initial = (user.fullName ?? user.username ?? '?').charAt(0).toUpperCase()
  const isPublicUrl = !!user.avatarUrl && user.avatarUrl.endsWith('/public')
  const isPrivateUrl = !!user.avatarUrl && user.avatarUrl.startsWith('/api/files/') && !isPublicUrl

  const protectedUrl = useProtectedObjectUrl(isPrivateUrl ? user.avatarUrl : null)
  const avatarSrc = isPublicUrl ? user.avatarUrl : isPrivateUrl ? protectedUrl : user.avatarUrl

  if (avatarSrc) {
    return (
      <img
        src={avatarSrc}
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
