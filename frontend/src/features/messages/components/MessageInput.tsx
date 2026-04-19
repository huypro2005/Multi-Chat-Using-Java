import { useState, useRef, useCallback, useEffect } from 'react'
import { Paperclip, Send } from 'lucide-react'
import { useSendMessage } from '../hooks'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
interface Props {
  conversationId: string
  /** false (default) = enabled; true = disabled (e.g. no permission) */
  disabled?: boolean
}

const MAX_CHARS = 5000
const WARN_CHARS = 4500

/**
 * MessageInput — thanh nhập và gửi tin nhắn.
 * Gửi qua REST POST (Tuần 4). WebSocket sẽ wire ở Tuần 4+.
 * Enter = gửi, Shift+Enter = xuống dòng.
 * Auto-resize textarea tối đa 5 dòng.
 */
export function MessageInput({ conversationId, disabled = false }: Props) {
  const [content, setContent] = useState('')
  const [charError, setCharError] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const shouldRefocusAfterSendRef = useRef(false)

  const { mutate: sendMessage, isPending } = useSendMessage(conversationId)

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
    autoResize()
  }

  const handleSend = useCallback(() => {
    const trimmed = content.trim()
    if (!trimmed || isPending || disabled) return
    if (trimmed.length > MAX_CHARS) {
      setCharError(true)
      return
    }
    sendMessage({ content: trimmed })
    shouldRefocusAfterSendRef.current = true
    setContent('')
    setCharError(false)
    // Reset textarea height
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }, [content, disabled, isPending, sendMessage])

  useEffect(() => {
    if (!isPending && !disabled && shouldRefocusAfterSendRef.current) {
      textareaRef.current?.focus()
      shouldRefocusAfterSendRef.current = false
    }
  }, [disabled, isPending])

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !disabled) {
      e.preventDefault()
      handleSend()
    }
  }

  const showCounter = content.length > WARN_CHARS
  const isOverLimit = content.length > MAX_CHARS

  return (
    <div className="px-4 py-3 border-t bg-white flex flex-col gap-1 flex-shrink-0">
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
          disabled={disabled || isPending}
          placeholder="Nhập tin nhắn..."
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
          disabled={disabled || isPending || !content.trim() || isOverLimit}
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
