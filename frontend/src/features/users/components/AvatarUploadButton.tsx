import { useRef, useState } from 'react'
import { toast } from 'sonner'
import api from '@/lib/api'
import { useUpdateProfile } from '../hooks'

interface UploadResponse {
  id: string
}

export function AvatarUploadButton() {
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const updateProfileMutation = useUpdateProfile()

  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return

    if (!file.type.startsWith('image/')) {
      toast.error('Chỉ hỗ trợ file ảnh')
      return
    }
    if (file.size > 20 * 1024 * 1024) {
      toast.error('File vượt quá 20MB')
      return
    }

    setUploading(true)
    try {
      const formData = new FormData()
      formData.append('file', file)
      const uploadRes = await api.post<UploadResponse>('/api/files/upload?public=true', formData, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      const avatarUrl = `/api/files/${uploadRes.data.id}/public`
      await updateProfileMutation.mutateAsync({ avatarUrl })
      toast.success('Đã cập nhật avatar')
    } catch {
      toast.error('Upload avatar thất bại')
    } finally {
      setUploading(false)
      if (fileInputRef.current) fileInputRef.current.value = ''
    }
  }

  return (
    <>
      <input
        ref={fileInputRef}
        type="file"
        accept="image/*"
        onChange={handleFileChange}
        className="hidden"
      />
      <button
        type="button"
        onClick={() => fileInputRef.current?.click()}
        disabled={uploading}
        className="border border-gray-300 px-4 py-2 rounded-lg text-sm hover:bg-gray-50 disabled:opacity-50"
      >
        {uploading ? 'Đang tải...' : 'Đổi avatar'}
      </button>
    </>
  )
}
