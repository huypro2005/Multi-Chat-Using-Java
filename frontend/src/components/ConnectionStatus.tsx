import { useState, useEffect } from 'react'
import { getConnectionState, onConnectionStateChange } from '@/lib/stompClient'
import type { ConnectionState } from '@/lib/stompClient'

const CONFIG: Record<ConnectionState, { dot: string; label: string }> = {
  CONNECTED: { dot: 'bg-green-500', label: 'Connected' },
  CONNECTING: { dot: 'bg-yellow-500 animate-pulse', label: 'Connecting...' },
  DISCONNECTED: { dot: 'bg-gray-400', label: 'Disconnected' },
  ERROR: { dot: 'bg-red-500', label: 'Connection error' },
}

/**
 * Debug indicator — hiển thị STOMP connection state.
 * Ở production: ẩn khi CONNECTED (chỉ hiện khi có vấn đề).
 * Ở development: luôn hiện để dễ debug.
 */
export default function ConnectionStatus() {
  const [state, setState] = useState<ConnectionState>(getConnectionState)

  useEffect(() => {
    // Subscribe vào state changes, trả về unsubscribe để cleanup
    return onConnectionStateChange(setState)
  }, [])

  // Ở production, ẩn khi kết nối tốt
  if (!import.meta.env.DEV && state === 'CONNECTED') return null

  // Ẩn hoàn toàn khi DISCONNECTED + production (user chưa login)
  if (!import.meta.env.DEV && state === 'DISCONNECTED') return null

  const { dot, label } = CONFIG[state]

  return (
    <div
      className="fixed bottom-3 right-3 z-50 flex items-center gap-1.5 rounded-full bg-white px-2.5 py-1 text-xs shadow-md ring-1 ring-gray-200"
      role="status"
      aria-label={`WebSocket: ${label}`}
    >
      <div className={`h-2 w-2 rounded-full ${dot}`} aria-hidden="true" />
      <span className="text-gray-600">{label}</span>
    </div>
  )
}
