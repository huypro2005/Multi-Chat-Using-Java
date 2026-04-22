import { useState } from 'react'
import { toast } from 'sonner'
import axios from 'axios'
import type { ApiErrorBody } from '@/types/api'
import { useChangePassword } from '../hooks'

export function ChangePasswordTab() {
  const [form, setForm] = useState({
    currentPassword: '',
    newPassword: '',
    confirmPassword: '',
  })
  const mutation = useChangePassword()

  const submit = (e: React.FormEvent) => {
    e.preventDefault()
    if (form.newPassword !== form.confirmPassword) {
      toast.error('Mật khẩu xác nhận không khớp')
      return
    }
    mutation.mutate(form, {
      onSuccess: () => {
        toast.success('Đổi mật khẩu thành công')
        setForm({ currentPassword: '', newPassword: '', confirmPassword: '' })
      },
      onError: (err) => {
        if (axios.isAxiosError(err)) {
          const code = (err.response?.data as ApiErrorBody | undefined)?.error
          const map: Record<string, string> = {
            INVALID_CURRENT_PASSWORD: 'Mật khẩu hiện tại không đúng',
            PASSWORD_TOO_SHORT: 'Mật khẩu mới tối thiểu 8 ký tự',
            PASSWORD_MISMATCH: 'Mật khẩu xác nhận không khớp',
            SAME_AS_CURRENT: 'Mật khẩu mới không được trùng mật khẩu hiện tại',
          }
          toast.error(map[code ?? ''] ?? 'Đổi mật khẩu thất bại')
          return
        }
        toast.error('Đổi mật khẩu thất bại')
      },
    })
  }

  return (
    <form onSubmit={submit} className="space-y-4 max-w-md">
      <Input
        label="Mật khẩu hiện tại"
        value={form.currentPassword}
        onChange={(v) => setForm((s) => ({ ...s, currentPassword: v }))}
      />
      <Input
        label="Mật khẩu mới"
        value={form.newPassword}
        onChange={(v) => setForm((s) => ({ ...s, newPassword: v }))}
      />
      <Input
        label="Xác nhận mật khẩu mới"
        value={form.confirmPassword}
        onChange={(v) => setForm((s) => ({ ...s, confirmPassword: v }))}
      />
      <button
        type="submit"
        disabled={mutation.isPending}
        className="bg-indigo-600 text-white px-5 py-2 rounded-lg text-sm disabled:opacity-50"
      >
        {mutation.isPending ? 'Đang xử lý...' : 'Đổi mật khẩu'}
      </button>
    </form>
  )
}

function Input({
  label,
  value,
  onChange,
}: {
  label: string
  value: string
  onChange: (value: string) => void
}) {
  return (
    <div>
      <label className="block text-sm font-medium mb-1">{label}</label>
      <input
        type="password"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="w-full border border-gray-300 rounded-lg px-3 py-2"
        required
      />
    </div>
  )
}
