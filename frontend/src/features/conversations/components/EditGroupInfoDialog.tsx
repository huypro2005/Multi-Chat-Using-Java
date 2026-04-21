// ---------------------------------------------------------------------------
// EditGroupInfoDialog — sửa tên nhóm và/hoặc avatar (W7-D3)
//
// Tristate PATCH: chỉ gửi fields thực sự thay đổi.
// avatarFileId: new UUID (đổi), null (xóa), không gửi (không đổi).
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import { Camera, Loader2, Upload, X } from 'lucide-react'
import { toast } from 'sonner'
import axios from 'axios'
import api from '@/lib/api'
import { useQueryClient } from '@tanstack/react-query'
import { conversationKeys } from '../queryKeys'
import { updateGroup } from '../api'
import type { ApiErrorBody } from '@/types/api'
import { useProtectedObjectUrl } from '@/features/files/hooks/useProtectedObjectUrl'

interface Props {
  conversationId: string
  open: boolean
  onClose: () => void
  currentName: string
  currentAvatarFileId: string | null
}

const MAX_AVATAR_SIZE_MB = 20
const ALLOWED_AVATAR_TYPES = ['image/jpeg', 'image/png', 'image/webp', 'image/gif']

export default function EditGroupInfoDialog({
  conversationId,
  open,
  onClose,
  currentName,
  currentAvatarFileId,
}: Props) {
  const queryClient = useQueryClient()
  // Avatar states
  // 'unchanged': user không đổi avatar
  // 'changed': user đã upload avatar mới → avatarNewFileId có giá trị
  // 'removed': user xóa avatar → gửi avatarFileId: null
  type AvatarChangeMode = 'unchanged' | 'changed' | 'removed'

  // Dùng pattern { value, scopeId } để tránh set-state-in-effect khi reset khi dialog reopen
  // scopeId = `${open}-${currentName}` → derive current value ở render time
  type ScopedState<T> = { value: T; scope: string } | null

  const scope = `${String(open)}-${currentName}`
  const [nameState, setNameState] = useState<ScopedState<string>>(null)
  const [nameErrorState, setNameErrorState] = useState<ScopedState<string | null>>(null)
  const [avatarModeState, setAvatarModeState] = useState<ScopedState<AvatarChangeMode>>(null)
  const [avatarNewFileIdState, setAvatarNewFileIdState] = useState<ScopedState<string | null>>(null)
  const [avatarPreviewState, setAvatarPreviewState] = useState<ScopedState<string | null>>(null)

  // Derive current values
  const name = nameState?.scope === scope ? nameState.value : currentName
  const nameError = nameErrorState?.scope === scope ? nameErrorState.value : null
  const avatarMode: AvatarChangeMode =
    avatarModeState?.scope === scope ? avatarModeState.value : 'unchanged'
  const avatarNewFileId = avatarNewFileIdState?.scope === scope ? avatarNewFileIdState.value : null
  const avatarPreview = avatarPreviewState?.scope === scope ? avatarPreviewState.value : null

  const [avatarUploading, setAvatarUploading] = useState(false)
  const [isSubmitting, setIsSubmitting] = useState(false)

  function setName(v: string) { setNameState({ value: v, scope }) }
  function setNameError(v: string | null) { setNameErrorState({ value: v, scope }) }
  function setAvatarMode(v: AvatarChangeMode) { setAvatarModeState({ value: v, scope }) }
  function setAvatarNewFileId(v: string | null) { setAvatarNewFileIdState({ value: v, scope }) }
  function setAvatarPreview(v: string | null) { setAvatarPreviewState({ value: v, scope }) }

  const fileInputRef = useRef<HTMLInputElement>(null)
  const blobUrlRef = useRef<string | null>(null)

  // Load current avatar via protected URL (if exists and not changed)
  const currentAvatarPath = currentAvatarFileId ? `/api/files/${currentAvatarFileId}` : null
  const currentAvatarObjectUrl = useProtectedObjectUrl(
    avatarMode === 'unchanged' ? currentAvatarPath : null,
  )

  // Cleanup blob URL on unmount
  useEffect(() => {
    return () => {
      if (blobUrlRef.current) {
        URL.revokeObjectURL(blobUrlRef.current)
      }
    }
  }, [])

  // Esc to close
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
    if (blobUrlRef.current) {
      URL.revokeObjectURL(blobUrlRef.current)
      blobUrlRef.current = null
    }
    // State sẽ tự reset vì scope thay đổi khi open=false → derive trả về default values
    onClose()
  }

  async function handleAvatarChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0]
    if (!file) return

    if (!ALLOWED_AVATAR_TYPES.includes(file.type)) {
      toast.error('Chỉ chấp nhận ảnh JPG, PNG, WebP, GIF')
      return
    }
    if (file.size > MAX_AVATAR_SIZE_MB * 1024 * 1024) {
      toast.error(`Ảnh phải nhỏ hơn ${MAX_AVATAR_SIZE_MB}MB`)
      return
    }

    // Revoke previous blob
    if (blobUrlRef.current) {
      URL.revokeObjectURL(blobUrlRef.current)
    }
    const blobUrl = URL.createObjectURL(file)
    blobUrlRef.current = blobUrl
    setAvatarPreview(blobUrl)
    setAvatarMode('changed')
    setAvatarNewFileId(null)
    setAvatarUploading(true)

    try {
      const formData = new FormData()
      formData.append('file', file)
      const res = await api.post<{ id: string }>('/api/files/upload', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      setAvatarNewFileId(res.data.id)
    } catch {
      toast.error('Upload ảnh thất bại. Thử lại.')
      URL.revokeObjectURL(blobUrl)
      blobUrlRef.current = null
      setAvatarPreview(null)
      setAvatarMode('unchanged')
    } finally {
      setAvatarUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  function handleRemoveAvatar() {
    if (blobUrlRef.current) {
      URL.revokeObjectURL(blobUrlRef.current)
      blobUrlRef.current = null
    }
    setAvatarPreview(null)
    setAvatarNewFileId(null)
    setAvatarMode('removed')
  }

  async function handleSubmit() {
    const trimmed = name.trim()
    if (!trimmed || trimmed.length < 3) {
      setNameError('Tên nhóm phải có ít nhất 3 ký tự')
      return
    }
    if (trimmed.length > 100) {
      setNameError('Tên nhóm không được vượt quá 100 ký tự')
      return
    }
    setNameError(null)

    // Build tristate patch body — only include changed fields
    const body: Record<string, unknown> = {}
    if (trimmed !== currentName) {
      body.name = trimmed
    }
    if (avatarMode === 'changed' && avatarNewFileId) {
      body.avatarFileId = avatarNewFileId
    } else if (avatarMode === 'removed') {
      body.avatarFileId = null
    }

    // Nothing changed
    if (Object.keys(body).length === 0) {
      handleClose()
      return
    }

    setIsSubmitting(true)
    try {
      await updateGroup(conversationId, body)
      void queryClient.invalidateQueries({ queryKey: conversationKeys.detail(conversationId) })
      void queryClient.invalidateQueries({ queryKey: conversationKeys.lists() })
      toast.success('Đã cập nhật thông tin nhóm')
      handleClose()
    } catch (err: unknown) {
      if (axios.isAxiosError(err)) {
        const code = (err.response?.data as ApiErrorBody | undefined)?.error
        if (code === 'VALIDATION_FAILED') {
          setNameError('Tên nhóm không hợp lệ')
        } else if (code === 'GROUP_AVATAR_NOT_OWNED' || code === 'GROUP_AVATAR_NOT_IMAGE') {
          toast.error('Ảnh đại diện không hợp lệ')
        } else {
          toast.error('Không thể cập nhật thông tin nhóm')
        }
      } else {
        toast.error('Không thể cập nhật thông tin nhóm')
      }
    } finally {
      setIsSubmitting(false)
    }
  }

  if (!open) return null

  // Determine displayed avatar
  const displayedAvatar =
    avatarMode === 'changed' ? avatarPreview : avatarMode === 'unchanged' ? currentAvatarObjectUrl : null

  const isDisabled = avatarUploading || isSubmitting

  return (
    <>
      <div
        className="fixed inset-0 bg-black/50 z-50"
        onClick={handleClose}
        aria-hidden="true"
      />
      <div className="fixed inset-0 z-50 flex items-center justify-center pointer-events-none">
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Chỉnh sửa thông tin nhóm"
          className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 pointer-events-auto"
          onClick={(e) => e.stopPropagation()}
        >
          {/* Header */}
          <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
            <h3 className="text-base font-semibold text-gray-900">Chỉnh sửa nhóm</h3>
            <button
              type="button"
              onClick={handleClose}
              aria-label="Đóng"
              className="w-8 h-8 rounded-lg flex items-center justify-center
                text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors"
            >
              <X size={18} />
            </button>
          </div>

          {/* Body */}
          <div className="px-6 py-5 space-y-5">
            {/* Avatar */}
            <div className="flex flex-col items-center gap-3">
              <div className="relative w-20 h-20">
                {displayedAvatar ? (
                  <img
                    src={displayedAvatar}
                    alt="Avatar nhóm"
                    className="w-20 h-20 rounded-full object-cover"
                  />
                ) : (
                  <div className="w-20 h-20 rounded-full bg-indigo-100 flex items-center justify-center">
                    <Camera size={28} className="text-indigo-400" />
                  </div>
                )}

                {avatarUploading && (
                  <div className="absolute inset-0 rounded-full bg-black/40 flex items-center justify-center">
                    <Loader2 size={20} className="text-white animate-spin" />
                  </div>
                )}

                {(displayedAvatar || currentAvatarFileId) && !avatarUploading && (
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
                className="text-sm text-indigo-600 hover:underline flex items-center gap-1
                  disabled:opacity-50 disabled:cursor-not-allowed"
              >
                <Upload size={14} />
                {currentAvatarFileId || avatarMode === 'changed' ? 'Đổi ảnh đại diện' : 'Thêm ảnh đại diện'}
              </button>

              <input
                ref={fileInputRef}
                type="file"
                accept={ALLOWED_AVATAR_TYPES.join(',')}
                className="hidden"
                onChange={(e) => void handleAvatarChange(e)}
              />
            </div>

            {/* Name */}
            <div>
              <label htmlFor="editGroupName" className="block text-sm font-medium text-gray-700 mb-1">
                Tên nhóm
              </label>
              <input
                id="editGroupName"
                value={name}
                onChange={(e) => {
                  setName(e.target.value)
                  if (nameError) setNameError(null)
                }}
                maxLength={100}
                className={`w-full border rounded-lg px-3 py-2 text-sm
                  focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent
                  ${nameError ? 'border-red-500' : 'border-gray-300'}`}
              />
              {nameError && (
                <p role="alert" className="mt-1 text-xs text-red-500">{nameError}</p>
              )}
            </div>
          </div>

          {/* Footer */}
          <div className="px-6 py-4 border-t border-gray-200 flex justify-end gap-3">
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
                hover:bg-indigo-700 transition-colors disabled:bg-indigo-400 disabled:cursor-not-allowed
                flex items-center gap-2"
            >
              {isSubmitting && <Loader2 size={14} className="animate-spin" />}
              Lưu thay đổi
            </button>
          </div>
        </div>
      </div>
    </>
  )
}
