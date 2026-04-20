import { useState, useRef, useCallback, useEffect } from 'react'
import { Paperclip, Send, WifiOff } from 'lucide-react'
import { useSendMessage } from '../hooks'
import { getConnectionState } from '@/lib/stompClient'
import { onConnectionStateChange } from '@/lib/stompClient'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
interface Props {
  conversationId: string
  /** false (default) = enabled; true = disabled (e.g. no permission) */
  disabled?: boolean
  /** Called on every keystroke (for typing indicator publish) */
  onTypingStart?: () => void
  /** Called after send or on textarea blur (for typing indicator stop) */
  onTypingStop?: () => void
}

const MAX_CHARS = 5000
const WARN_CHARS = 4500

/**
 * MessageInput — thanh nhập và gửi tin nhắn qua STOMP (Path B, ADR-016).
 * Enter = gửi, Shift+Enter = xuống dòng.
 * Auto-resize textarea tối đa 5 dòng.
 * Disable send khi STOMP chưa connect.
 */
export function MessageInput({
  conversationId,
  disabled = false,
  onTypingStart,
  onTypingStop,
}: Props) {
  const [content, setContent] = useState('')
  const [charError, setCharError] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Track STOMP connection state để disable input khi mất kết nối
  const [isConnected, setIsConnected] = useState(() => getConnectionState() === 'CONNECTED')

  useEffect(() => {
    const unsub = onConnectionStateChange((state) => {
      setIsConnected(state === 'CONNECTED')
    })
    return unsub
  }, [])

  const sendMessage = useSendMessage(conversationId)

  // Auto-resize textarea height
  const autoResize = useCallback(() => {
    const el = textareaRef.current
    if (!el) return
    el.style.height = 'auto'
    // max ~5 rows (line-height ~24px)
    el.style.height = Math.min(el.scrollHeight, 5 * 24) + 'px'
  }, [])

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    setContent(e.target.value)
    setCharError(false)
    setSendError(null)
    autoResize()
    onTypingStart?.()
  }

  const handleSend = useCallback(() => {
    const trimmed = content.trim()
    if (!trimmed || disabled || !isConnected) return
    if (trimmed.length > MAX_CHARS) {
      setCharError(true)
      return
    }
    try {
      onTypingStop?.()
      sendMessage(trimmed)
      setContent('')
      setCharError(false)
      setSendError(null)
      // Reset textarea height
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto'
      }
      textareaRef.current?.focus()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Không thể gửi tin nhắn'
      setSendError(message === 'STOMP_NOT_CONNECTED' ? 'Mất kết nối, thử lại sau' : message)
    }
  }, [content, disabled, isConnected, sendMessage, onTypingStop])

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !disabled) {
      e.preventDefault()
      handleSend()
    }
  }

  const showCounter = content.length > WARN_CHARS
  const isOverLimit = content.length > MAX_CHARS
  const isInputDisabled = disabled || !isConnected

  return (
    <div className="px-4 py-3 border-t bg-white flex flex-col gap-1 flex-shrink-0">
      {/* Mất kết nối banner */}
      {!isConnected && (
        <div className="flex items-center gap-1.5 px-1 py-0.5">
          <WifiOff size={12} className="text-amber-500" />
          <span className="text-xs text-amber-600">Mất kết nối, đang thử lại…</span>
        </div>
      )}

      {/* Character counter */}
      {showCounter && (
        <div className="flex justify-end px-1">
          <span
            className={`text-xs ${isOverLimit ? 'text-red-500' : 'text-yellow-500'}`}
            aria-live="polite"
          >
            {content.length}/{MAX_CHARS}
          </span>
        </div>
      )}

      {/* Over-limit warning */}
      {charError && (
        <p className="text-red-500 text-xs px-1" role="alert">
          Tin nhắn không được vượt quá {MAX_CHARS.toLocaleString()} ký tự.
        </p>
      )}

      {/* Send error */}
      {sendError && (
        <p className="text-red-500 text-xs px-1" role="alert">
          {sendError}
        </p>
      )}

      <div className="flex items-end gap-2">
        {/* Attach button — disabled until Week 5 file upload */}
        <button
          type="button"
          disabled
          aria-label="Đính kèm file"
          className="p-2 text-gray-400 opacity-50 cursor-not-allowed flex-shrink-0"
        >
          <Paperclip size={20} />
        </button>

        {/* Text input */}
        <textarea
          ref={textareaRef}
          value={content}
          onChange={handleChange}
          onKeyDown={handleKeyDown}
          onBlur={() => onTypingStop?.()}
          disabled={isInputDisabled}
          placeholder={isConnected ? 'Nhập tin nhắn...' : 'Đang kết nối…'}
          rows={1}
          aria-label="Nội dung tin nhắn"
          className="flex-1 resize-none rounded-2xl border border-gray-200 px-4 py-2 text-sm
            focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent
            disabled:bg-gray-50 disabled:text-gray-400 overflow-y-auto"
        />

        {/* Send button */}
        <button
          type="button"
          onClick={handleSend}
          disabled={isInputDisabled || !content.trim() || isOverLimit}
          aria-label="Gửi tin nhắn"
          className="bg-indigo-600 text-white rounded-full p-2 disabled:opacity-40
            hover:bg-indigo-700 transition-colors flex-shrink-0"
        >
          <Send size={18} />
        </button>
      </div>
    </div>
  )
}
