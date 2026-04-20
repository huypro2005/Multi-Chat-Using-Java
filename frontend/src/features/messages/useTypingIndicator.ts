// ---------------------------------------------------------------------------
// useTypingIndicator — publish typing events khi user gõ,
// subscribe nhận typing events từ người khác trong conversation.
//
// Contract: SOCKET_EVENTS.md mục 3.4
// Publish destination: /app/conv.{convId}.typing  (body: { action: 'START'|'STOP' })
// Subscribe topic:     /topic/conv.{convId}        (event type: TYPING_STARTED / TYPING_STOPPED)
//
// Thiết kế:
// - Subscription RIÊNG với useConvSubscription — STOMP cho phép nhiều sub cùng topic.
// - startTyping() debounce 2s: publish START tối đa 1 lần / 2s.
// - autoStop timer 3s: nếu không gõ thêm sau 3s, tự publish STOP.
// - stopTyping() explicit: publish STOP và clear timers (gọi khi send / blur).
// - Auto-remove user khỏi typingUsers sau 5s (phòng STOPPED bị miss).
// - Skip self-events (userId === currentUserId).
// - Clear typingUsers khi mất kết nối.
// - Cleanup timers khi unmount.
// ---------------------------------------------------------------------------

import { useState, useEffect, useCallback, useRef } from 'react'
import { useAuthStore } from '@/stores/authStore'
import { getStompClient, onConnectionStateChange } from '@/lib/stompClient'

// Shape nhận từ WS — khớp SOCKET_EVENTS.md 3.4
interface TypingUser {
  userId: string
  username: string
  conversationId: string
}

interface WsTypingEvent {
  type: 'TYPING_STARTED' | 'TYPING_STOPPED'
  payload: TypingUser
}

export interface UseTypingIndicatorReturn {
  typingUsers: TypingUser[]
  startTyping: () => void
  stopTyping: () => void
}

export function useTypingIndicator(conversationId: string): UseTypingIndicatorReturn {
  const [typingUsers, setTypingUsers] = useState<TypingUser[]>([])
  const currentUserId = useAuthStore((s) => s.user?.id)

  // debounceTimerRef: block re-publish START trong vòng 2s
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // autoStopTimerRef: tự publish STOP nếu user ngừng gõ 3s
  const autoStopTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  // autoRemoveTimers: mỗi typing user có 1 timer auto-remove sau 5s
  const autoRemoveTimersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map())

  // ---------------------------------------------------------------------------
  // Subscribe /topic/conv.{id} — filter TYPING_STARTED / TYPING_STOPPED
  // ---------------------------------------------------------------------------
  useEffect(() => {
    if (!conversationId) return

    let subCleanup: (() => void) | null = null

    function subscribe(): void {
      const client = getStompClient()
      if (!client?.connected) return

      const sub = client.subscribe(`/topic/conv.${conversationId}`, (frame) => {
        let event: WsTypingEvent
        try {
          const parsed = JSON.parse(frame.body) as { type: string; payload: unknown }
          if (parsed.type !== 'TYPING_STARTED' && parsed.type !== 'TYPING_STOPPED') return
          event = parsed as WsTypingEvent
        } catch (e) {
          console.error('[WS] typing event parse error', e)
          return
        }

        const user = event.payload

        // Skip self
        if (user.userId === currentUserId) return

        if (event.type === 'TYPING_STARTED') {
          setTypingUsers((prev) => {
            if (prev.some((u) => u.userId === user.userId)) return prev
            return [...prev, user]
          })

          // Auto-remove sau 5s phòng STOPPED bị miss
          const existingTimer = autoRemoveTimersRef.current.get(user.userId)
          if (existingTimer) clearTimeout(existingTimer)
          const removeTimer = setTimeout(() => {
            setTypingUsers((prev) => prev.filter((u) => u.userId !== user.userId))
            autoRemoveTimersRef.current.delete(user.userId)
          }, 5000)
          autoRemoveTimersRef.current.set(user.userId, removeTimer)
        } else {
          // TYPING_STOPPED — remove user ngay
          setTypingUsers((prev) => prev.filter((u) => u.userId !== user.userId))
          const existingTimer = autoRemoveTimersRef.current.get(user.userId)
          if (existingTimer) {
            clearTimeout(existingTimer)
            autoRemoveTimersRef.current.delete(user.userId)
          }
        }
      })

      subCleanup = () => sub.unsubscribe()
    }

    // Subscribe ngay nếu đã connected
    const client = getStompClient()
    if (client?.connected) subscribe()

    // Re-subscribe / clear khi state thay đổi
    const unsubState = onConnectionStateChange((state) => {
      if (state === 'CONNECTED') {
        subCleanup?.()
        subCleanup = null
        subscribe()
      } else if (state === 'DISCONNECTED' || state === 'ERROR') {
        subCleanup?.()
        subCleanup = null
        // Clear list — mất kết nối, typing state không còn chính xác
        setTypingUsers([])
      }
    })

    return () => {
      subCleanup?.()
      subCleanup = null
      unsubState()
    }
  }, [conversationId, currentUserId])

  // ---------------------------------------------------------------------------
  // stopTyping — publish STOP, clear timers
  // ---------------------------------------------------------------------------
  const stopTyping = useCallback(() => {
    if (autoStopTimerRef.current) {
      clearTimeout(autoStopTimerRef.current)
      autoStopTimerRef.current = null
    }

    const client = getStompClient()
    if (!client?.connected) return

    client.publish({
      destination: `/app/conv.${conversationId}.typing`,
      body: JSON.stringify({ action: 'STOP' }),
    })
  }, [conversationId])

  // ---------------------------------------------------------------------------
  // startTyping — debounce 2s + auto-stop 3s
  // ---------------------------------------------------------------------------
  const startTyping = useCallback(() => {
    const client = getStompClient()
    if (!client?.connected) return

    // Publish START tối đa 1 lần mỗi 2s (debounce)
    if (!debounceTimerRef.current) {
      client.publish({
        destination: `/app/conv.${conversationId}.typing`,
        body: JSON.stringify({ action: 'START' }),
      })
      debounceTimerRef.current = setTimeout(() => {
        debounceTimerRef.current = null
      }, 2000)
    }

    // Reset auto-stop: nếu user tiếp tục gõ thì delay thêm 3s
    if (autoStopTimerRef.current) clearTimeout(autoStopTimerRef.current)
    autoStopTimerRef.current = setTimeout(() => {
      stopTyping()
      autoStopTimerRef.current = null
    }, 3000)
  }, [conversationId, stopTyping])

  // ---------------------------------------------------------------------------
  // Cleanup tất cả timers khi unmount
  // ---------------------------------------------------------------------------
  useEffect(() => {
    const autoRemoveTimers = autoRemoveTimersRef.current
    return () => {
      if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current)
      if (autoStopTimerRef.current) clearTimeout(autoStopTimerRef.current)
      autoRemoveTimers.forEach((t) => clearTimeout(t))
      autoRemoveTimers.clear()
    }
  }, [])

  return { typingUsers, startTyping, stopTyping }
}
