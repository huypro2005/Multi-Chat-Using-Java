import { Link } from 'react-router-dom'

export function NotFoundPage() {
  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50">
      <div className="text-center p-8">
        <div className="text-7xl mb-3">🔍</div>
        <h1 className="text-4xl font-bold text-gray-800 mb-1">404</h1>
        <p className="text-gray-600 mb-5">Trang bạn tìm không tồn tại</p>
        <Link to="/" className="inline-block bg-indigo-600 text-white px-6 py-2 rounded-lg hover:bg-indigo-700">
          Về trang chủ
        </Link>
      </div>
    </div>
  )
}
