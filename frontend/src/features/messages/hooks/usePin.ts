import { useCallback } from 'react'
import { getStompClient } from '@/lib/stompClient'
import { toast } from 'sonner'

type PinAction = 'PIN' | 'UNPIN'

export function usePin(messageId: string) {
  const send = useCallback(
    (action: PinAction) => {
      const client = getStompClient()
      if (!client?.connected) {
        toast.error('Mất kết nối, vui lòng thử lại')
        return
      }
      client.publish({
        destination: `/app/msg.${messageId}.pin`,
        body: JSON.stringify({ action }),
      })
    },
    [messageId],
  )

  const pin = useCallback(() => {
    send('PIN')
  }, [send])

  const unpin = useCallback(() => {
    send('UNPIN')
  }, [send])

  return { pin, unpin }
}
