// ---------------------------------------------------------------------------
// CreateGroupDialog — modal tạo nhóm mới (W7-D3)
//
// Flow:
// 1. User nhập tên nhóm (3-100 chars)
// 2. Chọn avatar (optional) → upload ngay qua POST /api/files/upload
// 3. Tìm và chọn user (min 2)
// 4. Submit → POST /api/conversations với type=GROUP
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import { Camera, Loader2, Upload, X } from 'lucide-react'
import { toast } from 'sonner'
import axios from 'axios'
import api from '@/lib/api'
import { useUserSearch } from '@/features/users/hooks'
import { useCreateConversation } from '../hooks'
import type { UserSearchDto } from '@/types/conversation'
import type { ApiErrorBody } from '@/types/api'
import UserAvatar from '@/components/UserAvatar'

interface Props {
  open: boolean
  onClose: () => void
  onCreated: (convId: string) => void
}

const MAX_AVATAR_SIZE_MB = 20
const ALLOWED_AVATAR_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']

export default function CreateGroupDialog({ open, onClose, onCreated }: Props) {
  const [groupName, setGroupName] = useState('')
  const [avatarFile, setAvatarFile] = useState<File | null>(null)
  const [avatarPreview, setAvatarPreview] = useState<string | null>(null)
  const [avatarUploading, setAvatarUploading] = useState(false)
  const [avatarFileId, setAvatarFileId] = useState<string | null>(null)
  const [selectedUsers, setSelectedUsers] = useState<UserSearchDto[]>([])
  const [searchQuery, setSearchQuery] = useState('')
  const [isSubmitting, setIsSubmitting] = useState(false)
  const [nameError, setNameError] = useState<string | null>(null)

  const avatarPreviewRef = useRef<string | null>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const { data: searchResults } = useUserSearch(searchQuery)
  const { mutateAsync: createConversation } = useCreateConversation()

  // Esc key to close
  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleClose()
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  // Cleanup blob URL on unmount
  useEffect(() => {
    return () => {
      if (avatarPreviewRef.current) {
        URL.revokeObjectURL(avatarPreviewRef.current)
      }
    }
  }, [])

  function handleClose() {
    // Revoke blob URL before closing
    if (avatarPreviewRef.current) {
      URL.revokeObjectURL(avatarPreviewRef.current)
      avatarPreviewRef.current = null
    }
    setGroupName('')
    setAvatarFile(null)
    setAvatarPreview(null)
    setAvatarFileId(null)
    setSelectedUsers([])
    setSearchQuery('')
    setNameError(null)
    setAvatarUploading(false)
    onClose()
  }

  async function handleAvatarChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return

    // Validate type
    if (!ALLOWED_AVATAR_TYPES.includes(file.type)) {
      toast.error('Chỉ chấp nhận ảnh JPG, PNG, WebP, GIF')
      return
    }

    // Validate size
    if (file.size > MAX_AVATAR_SIZE_MB * 1024 * 1024) {
      toast.error(`Ảnh phải nhỏ hơn ${MAX_AVATAR_SIZE_MB}MB`)
      return
    }

    // Revoke previous preview
    if (avatarPreviewRef.current) {
      URL.revokeObjectURL(avatarPreviewRef.current)
    }

    // Create preview blob URL immediately
    const blobUrl = URL.createObjectURL(file)
    avatarPreviewRef.current = blobUrl
    setAvatarFile(file)
    setAvatarPreview(blobUrl)
    setAvatarFileId(null)
    setAvatarUploading(true)

    try {
      const formData = new FormData()
      formData.append('file', file)
      const res = await api.post<{ id: string }>('/api/files/upload?public=true', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      setAvatarFileId(res.data.id)
    } catch {
      toast.error('Upload ảnh thất bại. Thử lại.')
      // Clear avatar on upload fail
      URL.revokeObjectURL(blobUrl)
      avatarPreviewRef.current = null
      setAvatarFile(null)
      setAvatarPreview(null)
    } finally {
      setAvatarUploading(false)
      // Reset input so same file can be reselected
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  function handleRemoveAvatar() {
    if (avatarPreviewRef.current) {
      URL.revokeObjectURL(avatarPreviewRef.current)
      avatarPreviewRef.current = null
    }
    setAvatarFile(null)
    setAvatarPreview(null)
    setAvatarFileId(null)
    setAvatarUploading(false)
  }

  function handleToggleUser(user: UserSearchDto) {
    setSelectedUsers((prev) => {
      const exists = prev.find((u) => u.id === user.id)
      if (exists) return prev.filter((u) => u.id !== user.id)
      return [...prev, user]
    })
  }

  function handleRemoveSelected(userId: string) {
    setSelectedUsers((prev) => prev.filter((u) => u.id !== userId))
  }

  async function handleSubmit() {
    // Validate name
    const trimmed = groupName.trim()
    if (!trimmed || trimmed.length < 3) {
      setNameError('Tên nhóm phải có ít nhất 3 ký tự')
      return
    }
    if (trimmed.length > 100) {
      setNameError('Tên nhóm không được vượt quá 100 ký tự')
      return
    }
    setNameError(null)

    if (selectedUsers.length < 2) return
    if (avatarUploading) return

    setIsSubmitting(true)
    try {
      const result = await createConversation({
        type: 'GROUP',
        name: trimmed,
        avatarFileId: avatarFileId ?? undefined,
        memberIds: selectedUsers.map((u) => u.id),
      })

      if (result.conversation) {
        onCreated(result.conversation.id)
        handleClose()
      }
    } catch (err: unknown) {
      if (axios.isAxiosError(err)) {
        const code = (err.response?.data as ApiErrorBody | undefined)?.error
        if (code === 'VALIDATION_FAILED') {
          setNameError('Tên nhóm không hợp lệ')
        } else if (code === 'MEMBER_LIMIT_EXCEEDED') {
          const details = (err.response?.data as ApiErrorBody | undefined)?.details as
            | { currentCount?: number; limit?: number }
            | undefined
          toast.error(`Vượt giới hạn thành viên (${details?.currentCount ?? '?'}/${details?.limit ?? 50})`)
        } else {
          toast.error('Không thể tạo nhóm. Vui lòng thử lại.')
        }
      } else {
        toast.error('Không thể tạo nhóm. Vui lòng thử lại.')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  const isDisabled =
    !groupName.trim() ||
    groupName.trim().length < 3 ||
    selectedUsers.length < 2 ||
    avatarUploading ||
    isSubmitting

  if (!open) return null

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/50 z-50"
        onClick={handleClose}
        aria-hidden="true"
      />

      {/* Dialog */}
      <div
        className="fixed inset-0 z-50 flex items-center justify-center pointer-events-none"
      >
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Tạo nhóm chat"
          className="bg-white rounded-xl shadow-xl w-full max-w-lg mx-4 pointer-events-auto
            max-h-[90vh] flex flex-col"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200 flex-shrink-0">
            <h3 className="text-base font-semibold text-gray-900">Tạo nhóm chat</h3>
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

          {/* Scrollable body */}
          <div className="flex-1 overflow-y-auto px-6 py-4 space-y-5">
            {/* Row: avatar + name */}
            <div className="flex items-start gap-4">
              {/* Avatar upload */}
              <div className="flex-shrink-0">
                <div className="relative w-16 h-16">
                  {avatarPreview ? (
                    <img
                      src={avatarPreview}
                      alt="Avatar nhóm"
                      className="w-16 h-16 rounded-full object-cover"
                    />
                  ) : (
                    <div className="w-16 h-16 rounded-full bg-indigo-100 flex items-center justify-center">
                      <Camera size={24} className="text-indigo-400" />
                    </div>
                  )}

                  {/* Upload overlay / spinner */}
                  {avatarUploading && (
                    <div className="absolute inset-0 rounded-full bg-black/40 flex items-center justify-center">
                      <Loader2 size={18} className="text-white animate-spin" />
                    </div>
                  )}

                  {/* Remove button */}
                  {avatarPreview && !avatarUploading && (
                    <button
                      type="button"
                      onClick={handleRemoveAvatar}
                      aria-label="Xóa ảnh đại diện"
                      className="absolute -top-1 -right-1 w-5 h-5 rounded-full bg-red-500
                        text-white flex items-center justify-center hover:bg-red-600 transition-colors"
                    >
                      <X size={12} />
                    </button>
                  )}
                </div>

                <button
                  type="button"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={avatarUploading}
                  className="mt-1.5 text-xs text-indigo-600 hover:underline disabled:opacity-50
                    disabled:cursor-not-allowed flex items-center gap-1"
                >
                  <Upload size={12} />
                  {avatarFile ? 'Đổi ảnh' : 'Chọn ảnh'}
                </button>

                <input
                  ref={fileInputRef}
                  type="file"
                  accept={ALLOWED_AVATAR_TYPES.join(',')}
                  className="hidden"
                  onChange={(e) => void handleAvatarChange(e)}
                />
              </div>

              {/* Group name */}
              <div className="flex-1 min-w-0">
                <label htmlFor="groupName" className="block text-sm font-medium text-gray-700 mb-1">
                  Tên nhóm <span className="text-red-500">*</span>
                </label>
                <input
                  id="groupName"
                  autoFocus
                  value={groupName}
                  onChange={(e) => {
                    setGroupName(e.target.value)
                    if (nameError) setNameError(null)
                  }}
                  placeholder="Nhập tên nhóm (3-100 ký tự)"
                  maxLength={100}
                  className={`w-full border rounded-lg px-3 py-2 text-sm
                    focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent
                    ${nameError ? 'border-red-500' : 'border-gray-300'}`}
                />
                {nameError && (
                  <p role="alert" className="mt-1 text-xs text-red-500">{nameError}</p>
                )}
                <p className="mt-1 text-xs text-gray-400">{groupName.length}/100</p>
              </div>
            </div>

            {/* User search */}
            <div>
              <label htmlFor="userSearch" className="block text-sm font-medium text-gray-700 mb-1">
                Thêm thành viên <span className="text-red-500">*</span>
              </label>
              <input
                id="userSearch"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Tìm username hoặc tên..."
                className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm
                  focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
              />

              {searchQuery.length > 0 && searchQuery.length < 2 && (
                <p className="text-xs text-gray-500 mt-1">Nhập ít nhất 2 ký tự</p>
              )}

              {/* Search results */}
              {searchResults && searchResults.length > 0 && (
                <ul className="mt-2 border border-gray-200 rounded-lg divide-y divide-gray-100 max-h-48 overflow-y-auto">
                  {searchResults.map((user) => {
                    const isSelected = selectedUsers.some((u) => u.id === user.id)
                    return (
                      <li key={user.id}>
                        <button
                          type="button"
                          onClick={() => handleToggleUser(user)}
                          className="w-full flex items-center gap-3 px-3 py-2.5 text-left
                            hover:bg-gray-50 transition-colors"
                        >
                          <UserAvatar user={user} size={32} />
                          <div className="flex-1 min-w-0">
                            <p className="text-sm font-medium text-gray-900 truncate">
                              {user.fullName}
                            </p>
                            <p className="text-xs text-gray-500">@{user.username}</p>
                          </div>
                          {/* Checkbox visual */}
                          <div
                            className={`w-5 h-5 rounded-full border-2 flex-shrink-0 flex items-center justify-center
                              ${isSelected ? 'bg-indigo-600 border-indigo-600' : 'border-gray-300'}`}
                          >
                            {isSelected && (
                              <svg
                                viewBox="0 0 12 12"
                                fill="none"
                                className="w-3 h-3 text-white"
                                aria-hidden="true"
                              >
                                <path
                                  d="M2 6l3 3 5-5"
                                  stroke="currentColor"
                                  strokeWidth="2"
                                  strokeLinecap="round"
                                  strokeLinejoin="round"
                                />
                              </svg>
                            )}
                          </div>
                        </button>
                      </li>
                    )
                  })}
                </ul>
              )}

              {!searchResults?.length && searchQuery.length >= 2 && (
                <p className="mt-2 text-sm text-gray-500">Không tìm thấy người dùng</p>
              )}
            </div>

            {/* Selected users chips */}
            {selectedUsers.length > 0 && (
              <div>
                <p className="text-sm font-medium text-gray-700 mb-2">
                  {selectedUsers.length} người đã chọn
                  {selectedUsers.length < 2 && (
                    <span className="ml-1 text-xs text-amber-600">(tối thiểu 2)</span>
                  )}
                </p>
                <div className="flex flex-wrap gap-2 max-h-32 overflow-y-auto">
                  {selectedUsers.map((user) => (
                    <span
                      key={user.id}
                      className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full
                        bg-indigo-50 text-indigo-700 text-sm"
                    >
                      <span className="max-w-[100px] truncate">{user.fullName}</span>
                      <button
                        type="button"
                        onClick={() => handleRemoveSelected(user.id)}
                        aria-label={`Xóa ${user.fullName}`}
                        className="text-indigo-400 hover:text-indigo-700 transition-colors"
                      >
                        <X size={14} />
                      </button>
                    </span>
                  ))}
                </div>
              </div>
            )}

            {selectedUsers.length === 0 && (
              <p className="text-xs text-gray-500">Tối thiểu 2 thành viên (ngoài bạn)</p>
            )}
          </div>

          {/* Footer */}
          <div className="px-6 py-4 border-t border-gray-200 flex justify-end gap-3 flex-shrink-0">
            <button
              type="button"
              onClick={handleClose}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300
                rounded-lg hover:bg-gray-50 transition-colors"
            >
              Hủy
            </button>
            <button
              type="button"
              onClick={() => void handleSubmit()}
              disabled={isDisabled}
              className="px-4 py-2 text-sm font-medium text-white bg-indigo-600 rounded-lg
                hover:bg-indigo-700 transition-colors
                disabled:bg-indigo-400 disabled:cursor-not-allowed
                flex items-center gap-2"
            >
              {isSubmitting && <Loader2 size={14} className="animate-spin" />}
              Tạo nhóm
            </button>
          </div>
        </div>
      </div>
    </>
  )
}
