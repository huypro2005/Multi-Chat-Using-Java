import { useState, useEffect } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { authService } from '@/services/authService'
import AppLoadingScreen from '@/components/AppLoadingScreen'
import LoginPage from '@/pages/LoginPage'
import RegisterPage from '@/pages/RegisterPage'
import HomePage from '@/pages/HomePage'

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
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/" element={<HomePage />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>
    </BrowserRouter>
  )
}
