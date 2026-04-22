import { useNavigate } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'
import { logoutApi } from '@/features/auth/api'
import { tokenStorage } from '@/lib/tokenStorage'

export function AccountTab() {
  const navigate = useNavigate()
  const clearAuth = useAuthStore((s) => s.clearAuth)

  const logout = async () => {
    const ok = window.confirm('Đăng xuất khỏi tài khoản?')
    if (!ok) return
    const refreshToken = tokenStorage.getRefreshToken()
    try {
      if (refreshToken) {
        await logoutApi({ refreshToken })
      }
    } finally {
      clearAuth()
      navigate('/login')
    }
  }

  return (
    <div className="border border-gray-200 rounded-xl p-4">
      <h3 className="font-semibold text-gray-900">Tài khoản</h3>
      <p className="text-sm text-gray-600 mt-1 mb-3">Bạn sẽ cần đăng nhập lại để tiếp tục sử dụng.</p>
      <button
        type="button"
        onClick={() => void logout()}
        className="bg-red-600 text-white px-4 py-2 rounded-lg text-sm"
      >
        Đăng xuất
      </button>
    </div>
  )
}
