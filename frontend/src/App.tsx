import { useState, useEffect, useRef } from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { Toaster } from 'sonner'
import { authService } from '@/services/authService'
import { useAuthStore } from '@/stores/authStore'
import { connectStomp, disconnectStomp } from '@/lib/stompClient'
import { useAckErrorSubscription } from '@/features/messages/useAckErrorSubscription'
import AppLoadingScreen from '@/components/AppLoadingScreen'
import ProtectedRoute from '@/components/ProtectedRoute'
import ConnectionStatus from '@/components/ConnectionStatus'
import LoginPage from '@/pages/LoginPage'
import RegisterPage from '@/pages/RegisterPage'
import HomePage from '@/pages/HomePage'
import ConversationsLayout from '@/pages/ConversationsLayout'
import ConversationsIndexPage from '@/pages/ConversationsIndexPage'
import ConversationDetailPage from '@/pages/ConversationDetailPage'

// ---------------------------------------------------------------------------
// GlobalSubscriptions — mount 1 lần khi user đã authenticated
// Tách component để hooks chỉ chạy khi isAuthenticated=true
// ---------------------------------------------------------------------------
function GlobalSubscriptions() {
  // Subscribe /user/queue/acks + /user/queue/errors (Path B, ADR-016)
  // Hook này idempotent: tự re-subscribe khi STOMP reconnect
  useAckErrorSubscription()
  return null
}

export default function App() {
  // Gate: không render routes cho đến khi authService.init() hoàn tất.
  // Tránh race condition: routes render trước khi accessToken được restore từ
  // refreshToken → request đầu tiên gặp 401 → interceptor redirect /login sai.
  const [isInitialized, setIsInitialized] = useState(false)

  // Đọc isAuthenticated từ authStore để drive STOMP lifecycle
  const isAuthenticated = useAuthStore((s) => !!s.accessToken)
  const prevAuthRef = useRef(isAuthenticated)

  useEffect(() => {
    // init() luôn resolve (không throw) — finally đảm bảo gate luôn mở
    void authService.init().finally(() => setIsInitialized(true))
  }, [])

  // STOMP lifecycle: connect khi login, disconnect khi logout
  useEffect(() => {
    const wasAuthenticated = prevAuthRef.current
    prevAuthRef.current = isAuthenticated

    if (isAuthenticated && !wasAuthenticated) {
      // Vừa login (hoặc app init với session còn valid)
      void connectStomp()
    } else if (!isAuthenticated && wasAuthenticated) {
      // Vừa logout
      disconnectStomp()
    }
  }, [isAuthenticated])

  if (!isInitialized) {
    return <AppLoadingScreen />
  }

  return (
    <BrowserRouter>
      {/* Global ACK/ERROR subscription — mount khi authenticated, unmount khi logout */}
      {isAuthenticated && <GlobalSubscriptions />}

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
            {/* detail: /conversations/:id */}
            <Route path=":id" element={<ConversationDetailPage />} />
          </Route>
        </Route>

        {/* Fallback */}
        <Route path="*" element={<Navigate to="/" replace />} />
      </Routes>

      {/* Debug indicator — chỉ visible ở DEV hoặc khi có lỗi/disconnect */}
      <ConnectionStatus />

      {/* Toast notifications */}
      <Toaster position="bottom-right" richColors />
    </BrowserRouter>
  )
}
