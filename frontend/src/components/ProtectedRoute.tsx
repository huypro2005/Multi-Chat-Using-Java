import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/stores/authStore'

/**
 * ProtectedRoute — bảo vệ các route yêu cầu đăng nhập.
 *
 * Behavior:
 * - isInitializing (chưa hydrate store): hiện spinner, không redirect sớm
 * - isAuthenticated false: redirect /login với state.from để login có thể quay lại
 * - isAuthenticated true: render <Outlet /> (layout route) hoặc children
 */
export default function ProtectedRoute() {
  const accessToken = useAuthStore((s) => s.accessToken)
  const isHydrated = useAuthStore((s) => s.isHydrated)
  const location = useLocation()

  const isAuthenticated = !!accessToken
  // isInitializing = store chưa hydrate từ localStorage xong
  const isInitializing = !isHydrated

  if (isInitializing) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <svg
          className="animate-spin h-8 w-8 text-indigo-600"
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="0 0 24 24"
          aria-label="Đang tải..."
        >
          <circle
            className="opacity-25"
            cx="12"
            cy="12"
            r="10"
            stroke="currentColor"
            strokeWidth="4"
          />
          <path
            className="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 018-8v8H4z"
          />
        </svg>
      </div>
    )
  }

  if (!isAuthenticated) {
    // Lưu location hiện tại để sau login quay về đúng trang
    return <Navigate to="/login" state={{ from: location }} replace />
  }

  return <Outlet />
}
