import { useState } from 'react'
import UserAvatar from '@/components/UserAvatar'
import { useCurrentUser, useUpdateProfile } from '../hooks'
import { AvatarUploadButton } from './AvatarUploadButton'
import { EmptyState } from '@/features/common/components/EmptyState'

export function ProfileInfoTab() {
  const { data: user, isLoading } = useCurrentUser()
  const updateProfileMutation = useUpdateProfile()
  const [editing, setEditing] = useState(false)
  const [fullName, setFullName] = useState('')

  if (isLoading) return <p className="text-sm text-gray-500">Đang tải...</p>
  if (!user) return <EmptyState title="Không tải được thông tin hồ sơ" icon="⚠️" />

  const startEdit = () => {
    setFullName(user.fullName)
    setEditing(true)
  }

  const save = async () => {
    const trimmed = fullName.trim()
    if (trimmed.length < 2 || trimmed.length > 100) return
    await updateProfileMutation.mutateAsync({ fullName: trimmed })
    setEditing(false)
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <UserAvatar user={user} size={80} />
        <div className="flex gap-2">
          <AvatarUploadButton />
          <button
            type="button"
            onClick={() => updateProfileMutation.mutate({ avatarUrl: null })}
            className="border border-gray-300 px-4 py-2 rounded-lg text-sm hover:bg-gray-50"
          >
            Xóa avatar
          </button>
        </div>
      </div>

      <div className="space-y-4">
        <Field label="Email" value={user.email} />
        <Field label="Họ tên">
          {editing ? (
            <input
              type="text"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              maxLength={100}
              className="w-full border border-gray-300 rounded-lg px-3 py-2"
            />
          ) : (
            <span>{user.fullName}</span>
          )}
        </Field>
      </div>

      <div className="flex gap-2">
        {editing ? (
          <>
            <button
              type="button"
              onClick={() => void save()}
              disabled={updateProfileMutation.isPending}
              className="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm disabled:opacity-50"
            >
              Lưu
            </button>
            <button
              type="button"
              onClick={() => setEditing(false)}
              className="border border-gray-300 px-4 py-2 rounded-lg text-sm"
            >
              Hủy
            </button>
          </>
        ) : (
          <button
            type="button"
            onClick={startEdit}
            className="bg-indigo-600 text-white px-4 py-2 rounded-lg text-sm"
          >
            Chỉnh sửa
          </button>
        )}
      </div>
    </div>
  )
}

function Field({
  label,
  value,
  children,
}: {
  label: string
  value?: string
  children?: React.ReactNode
}) {
  return (
    <div>
      <p className="text-sm font-medium text-gray-700 mb-1">{label}</p>
      {children ?? <p className="text-gray-700">{value}</p>}
    </div>
  )
}
