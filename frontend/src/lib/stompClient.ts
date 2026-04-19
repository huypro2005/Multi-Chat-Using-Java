// ---------------------------------------------------------------------------
// stompClient.ts — STOMP singleton
//
// Quy tắc:
// - Một Client instance duy nhất cho toàn app.
// - Manage reconnect tự tay (reconnectDelay: 0) với exponential backoff.
// - connectStomp() chỉ gọi khi đã có accessToken.
// - disconnectStomp() dùng khi logout — set MAX_RECONNECT để chặn auto-reconnect.
// - getStompClient() export để subscription hooks (Ngày 4) dùng.
// - onConnectionStateChange() để component (ConnectionStatus) react theo state.
// ---------------------------------------------------------------------------

import { Client } from '@stomp/stompjs'
import type { Frame } from '@stomp/stompjs' // Frame used in onStompError
import SockJS from 'sockjs-client'
import { tokenStorage } from './tokenStorage'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
export type ConnectionState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED' | 'ERROR'

// ---------------------------------------------------------------------------
// Module-level state (không dùng React state vì cần tồn tại ngoài component tree)
// ---------------------------------------------------------------------------
let _state: ConnectionState = 'DISCONNECTED'
let _client: Client | null = null
let _reconnectAttempts = 0
const MAX_RECONNECT = 10

type StateListener = (state: ConnectionState) => void
const _stateListeners = new Set<StateListener>()

// ---------------------------------------------------------------------------
// State helpers
// ---------------------------------------------------------------------------
function _setState(s: ConnectionState): void {
  _state = s
  _stateListeners.forEach((cb) => cb(s))
}

export function getConnectionState(): ConnectionState {
  return _state
}

/**
 * Subscribe vào connection state changes.
 * Returns unsubscribe function — dùng làm cleanup trong useEffect.
 */
export function onConnectionStateChange(cb: StateListener): () => void {
  _stateListeners.add(cb)
  return () => {
    _stateListeners.delete(cb)
  }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------
function _getWsUrl(): string {
  // VITE_WS_URL dùng HTTP (http://), không ws://.
  // SockJS tự upgrade sang WebSocket; nếu không được thì fallback HTTP polling.
  return import.meta.env.VITE_WS_URL ?? 'http://localhost:8080/ws'
}

async function _handleTokenExpired(): Promise<void> {
  try {
    // Import động để tránh circular dependency:
    // stompClient → authService → (không import stompClient)
    const { authService } = await import('@/services/authService')
    await authService.refresh()
    // Reconnect với token mới (resetAttempts vì đây không phải network error)
    _reconnectAttempts = 0
    await connectStomp()
  } catch {
    // Refresh fail → logout
    console.error('[STOMP] Token refresh failed, redirecting to login')
    _setState('ERROR')
    // Dùng window.location thay vì navigate vì đây nằm ngoài React tree
    window.location.href = '/login'
  }
}

function _scheduleReconnect(): void {
  if (_reconnectAttempts >= MAX_RECONNECT) {
    console.error('[STOMP] Max reconnect attempts reached. Giving up.')
    _setState('ERROR')
    return
  }
  // Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s (cap)
  const delay = Math.min(1000 * Math.pow(2, _reconnectAttempts), 30_000)
  _reconnectAttempts++
  console.info(`[STOMP] Reconnecting in ${delay}ms (attempt ${_reconnectAttempts}/${MAX_RECONNECT})`)
  setTimeout(() => {
    void connectStomp()
  }, delay)
}

function _createClient(): Client {
  const wsUrl = _getWsUrl()
  const token = tokenStorage.getAccessToken()

  return new Client({
    // SockJS factory — phải là factory function, không phải instance.
    // Lý do: Client sẽ gọi lại factory mỗi lần reconnect để tạo socket mới.
    webSocketFactory: () => new SockJS(wsUrl) as WebSocket,

    connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},

    // Tắt built-in reconnect của stompjs — tự manage để có exponential backoff
    reconnectDelay: 0,

    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,

    // Debug log chỉ bật ở development
    debug: import.meta.env.DEV ? (msg: string) => console.debug('[STOMP]', msg) : () => { /* no-op */ },

    onConnect: () => {
      console.info('[STOMP] Connected')
      _setState('CONNECTED')
      _reconnectAttempts = 0
    },

    onDisconnect: () => {
      console.info('[STOMP] Disconnected')
      _setState('DISCONNECTED')
    },

    onStompError: (frame: Frame) => {
      const errorCode = frame.headers['message'] ?? 'UNKNOWN'
      console.error('[STOMP] STOMP error:', errorCode)

      if (errorCode === 'AUTH_TOKEN_EXPIRED') {
        // Refresh flow — không reconnect ngay, chờ token mới
        void _handleTokenExpired()
      } else if (errorCode === 'AUTH_REQUIRED') {
        // Token missing/invalid — logout
        _setState('ERROR')
        window.location.href = '/login'
      } else {
        // Lỗi khác (SERVER_ERROR, FORBIDDEN,...) — schedule reconnect
        _setState('ERROR')
        _scheduleReconnect()
      }
    },

    onWebSocketError: () => {
      console.error('[STOMP] WebSocket error')
      // Không set ERROR ngay — onWebSocketClose sẽ được gọi tiếp theo,
      // state sẽ chuyển DISCONNECTED và schedule reconnect ở đó.
    },

    onWebSocketClose: () => {
      console.info('[STOMP] WebSocket closed')
      // Chỉ schedule reconnect nếu chúng ta chưa intentionally disconnect
      // (_reconnectAttempts < MAX_RECONNECT check nằm trong _scheduleReconnect)
      if (_state !== 'DISCONNECTED') {
        _setState('DISCONNECTED')
        _scheduleReconnect()
      }
    },
  })
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Kết nối STOMP. Idempotent — gọi nhiều lần không sao.
 * Skip nếu đang CONNECTED hoặc CONNECTING.
 * Skip nếu không có accessToken.
 */
export async function connectStomp(): Promise<void> {
  if (_state === 'CONNECTED' || _state === 'CONNECTING') return

  const token = tokenStorage.getAccessToken()
  if (!token) {
    console.warn('[STOMP] No access token — skip connect')
    return
  }

  _setState('CONNECTING')

  // Deactivate client cũ nếu còn tồn tại (cleanup an toàn)
  if (_client) {
    try {
      await _client.deactivate()
    } catch {
      // Ignore deactivate errors
    }
  }

  _client = _createClient()
  _client.activate()
}

/**
 * Ngắt kết nối STOMP (dùng khi logout).
 * Set _reconnectAttempts = MAX_RECONNECT để chặn auto-reconnect.
 */
export function disconnectStomp(): void {
  // Chặn auto-reconnect trước khi deactivate
  _reconnectAttempts = MAX_RECONNECT

  if (_client) {
    void _client.deactivate()
    _client = null
  }

  _setState('DISCONNECTED')
}

/**
 * Expose client instance cho subscription hooks (Ngày 4).
 * Caller PHẢI check client.connected trước khi subscribe.
 */
export function getStompClient(): Client | null {
  return _client
}
