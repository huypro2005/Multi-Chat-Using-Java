// ---------------------------------------------------------------------------
// useReact — publish STOMP /app/msg.{messageId}.react (W8-D1)
//
// Fire-and-forget. Confirmation qua REACTION_CHANGED broadcast (§3.16).
// ERROR qua /user/queue/errors với operation='REACT' và clientId: null.
// ---------------------------------------------------------------------------

import { useCallback } from 'react'
import { toast } from 'sonner'
import { getStompClient } from '@/lib/stompClient'

export function useReact(messageId: string) {
  return useCallback(
    (emoji: string) => {
      const client = getStompClient()
      if (!client?.connected) {
        toast.error('Mất kết nối, vui lòng thử lại')
        return
      }
      client.publish({
        destination: `/app/msg.${messageId}.react`,
        body: JSON.stringify({ emoji }),
      })
    },
    [messageId],
  )
}
