import { useEffect, useState } from 'react'
import api from '@/lib/api'

type HealthState =
  | { status: 'loading' }
  | { status: 'ok'; data: { status: string; service: string } }
  | { status: 'error'; message: string }

export default function HomePage() {
  const [health, setHealth] = useState<HealthState>({ status: 'loading' })

  useEffect(() => {
    api.get<{ status: string; service: string }>('/api/health')
      .then((res) => setHealth({ status: 'ok', data: res.data }))
      .catch((err) => {
        const message =
          err.code === 'ERR_NETWORK'
            ? 'Không thể kết nối backend (CORS hoặc server chưa chạy)'
            : (err.response?.data?.message as string | undefined) ?? (err.message as string | undefined) ?? 'Lỗi không xác định'
        setHealth({ status: 'error', message })
      })
  }, [])

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="bg-white rounded-xl shadow-sm border border-gray-200 p-8 max-w-md w-full text-center">
        <h1 className="text-2xl font-bold text-gray-900 mb-6">ChatApp</h1>

        {health.status === 'loading' && (
          <div className="flex items-center justify-center gap-2 text-gray-500">
            <svg
              className="animate-spin h-5 w-5"
              xmlns="http://www.w3.org/2000/svg"
              fill="none"
              viewBox="0 0 24 24"
              aria-hidden="true"
            >
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v8H4z" />
            </svg>
            <span>Đang kiểm tra kết nối backend...</span>
          </div>
        )}

        {health.status === 'ok' && (
          <div className="text-green-600">
            <p className="text-lg font-medium">Backend OK</p>
            <p className="text-sm text-gray-500 mt-1">
              service: <span className="font-mono">{health.data.service}</span>
              {' · '}status: <span className="font-mono">{health.data.status}</span>
            </p>
          </div>
        )}

        {health.status === 'error' && (
          <div className="text-red-600">
            <p className="text-lg font-medium">Lỗi kết nối</p>
            <p className="text-sm text-gray-500 mt-1 font-mono break-all">{health.message}</p>
            <p className="text-xs text-gray-400 mt-2">
              Kiểm tra: BE chạy port 8080? CORS config đúng?
            </p>
          </div>
        )}

        <div className="mt-8 flex gap-3 justify-center">
          <a href="/login" className="text-sm text-indigo-600 hover:underline">Đăng nhập</a>
          <span className="text-gray-300">·</span>
          <a href="/register" className="text-sm text-indigo-600 hover:underline">Đăng ký</a>
        </div>
      </div>
    </div>
  )
}
