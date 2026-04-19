/**
 * AppLoadingScreen — hiển thị trong khi authService.init() đang chạy.
 *
 * Mục đích: tránh flash redirect (routes chưa biết auth state đã render → interceptor
 * gặp 401 → redirect /login dù session còn valid).
 * Chỉ render khi isInitialized = false ở App.tsx.
 */
export default function AppLoadingScreen() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="flex flex-col items-center gap-3 text-gray-500">
        <svg
          className="animate-spin h-8 w-8 text-indigo-600"
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="0 0 24 24"
          aria-label="Đang tải"
          role="img"
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
        <span className="text-sm">Đang khởi động...</span>
      </div>
    </div>
  )
}
