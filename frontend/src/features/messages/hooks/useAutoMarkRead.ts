// ---------------------------------------------------------------------------
// useAutoMarkRead — auto send read receipt when viewing conversation (W7-D5)
//
// Debounce 500ms để tránh spam (contract §3f rate limit + §3f.2 idempotent).
// Dedupe bằng lastSentRef — không gửi lại cùng messageId.
// ---------------------------------------------------------------------------

import { useCallback, useRef, useEffect } from 'react'
import { getStompClient } from '@/lib/stompClient'

export function useAutoMarkRead(
  convId: string | undefined,
  lastMessageId: string | undefined,
): void {
  const lastSentRef = useRef<string | null>(null)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const sendRead = useCallback(
    (messageId: string) => {
      if (!convId) return
      // Dedupe — không gửi lại nếu đã gửi cùng messageId
      if (lastSentRef.current === messageId) return

      if (timerRef.current) clearTimeout(timerRef.current)
      timerRef.current = setTimeout(() => {
        const client = getStompClient()
        if (!client?.connected) return

        try {
          client.publish({
            destination: `/app/conv.${convId}.read`,
            body: JSON.stringify({ messageId }),
          })
          lastSentRef.current = messageId
        } catch {
          // STOMP not connected — silent fail, will retry on next lastMessageId change
        }
      }, 500)
    },
    [convId],
  )

  useEffect(() => {
    if (!lastMessageId) return
    sendRead(lastMessageId)
  }, [lastMessageId, sendRead])

  // Reset dedupe when conversation changes
  useEffect(() => {
    lastSentRef.current = null
  }, [convId])

  // Cleanup timer on unmount
  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [])
}
