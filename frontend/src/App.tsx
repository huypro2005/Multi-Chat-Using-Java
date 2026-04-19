import { useState, useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { authService } from '@/services/authService'
import AppLoadingScreen from '@/components/AppLoadingScreen'
import ProtectedRoute from '@/components/ProtectedRoute'
import LoginPage from '@/pages/LoginPage'
import RegisterPage from '@/pages/RegisterPage'
import HomePage from '@/pages/HomePage'
import ConversationsLayout from '@/pages/ConversationsLayout'
import ConversationsIndexPage from '@/pages/ConversationsIndexPage'

export default function App() {
  // Gate: không render routes cho đến khi authService.init() hoàn tất.
  // Tránh race condition: routes render trước khi accessToken được restore từ
  // refreshToken → request đầu tiên gặp 401 → interceptor redirect /login sai.
  const [isInitialized, setIsInitialized] = useState(false)

  useEffect(() => {
    // init() luôn resolve (không throw) — finally đảm bảo gate luôn mở
    void authService.init().finally(() => setIsInitialized(true))
  }, [])

  if (!isInitialized) {
    return <AppLoadingScreen />
  }

  return (
    <BrowserRouter>
      <Routes>
        {/* Public routes */}
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/" element={<HomePage />} />

        {/* Protected routes — yêu cầu đăng nhập */}
        <Route element={<ProtectedRoute />}>
          <Route path="/conversations" element={<ConversationsLayout />}>
            {/* index: /conversations (chưa chọn conversation) */}
            <Route index element={<ConversationsIndexPage />} />
            {/* detail: /conversations/:id — placeholder, Ngày 4 implement */}
            <Route
              path=":id"
              element={
                <div className="flex-1 flex items-center justify-center p-8 text-gray-400 bg-gray-50">
                  <p className="text-sm">Detail tuần 4</p>
                </div>
              }
            />
          </Route>
        </Route>

        {/* Fallback */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
