import { useState } from 'react'
import { Paperclip, Send } from 'lucide-react'

interface Props {
  /** true tuần 3 (placeholder), false tuần 4 (real send) */
  disabled?: boolean
  /** undefined tuần 3, implemented tuần 4 */
  onSend?: (text: string) => void
}

/**
 * MessageInput — thanh nhập tin nhắn ở cuối ConversationDetailPage.
 * Tuần 3: disabled=true, onSend=undefined (UI placeholder).
 * Tuần 4: disabled=false, onSend wired với STOMP/REST.
 */
export function MessageInput({ disabled = true, onSend }: Props) {
  const [value, setValue] = useState('')

  const handleSend = () => {
    if (!value.trim() || !onSend) return
    onSend(value.trim())
    setValue('')
  }

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !disabled) {
      e.preventDefault()
      handleSend()
    }
  }

  return (
    <div className="px-4 py-3 border-t bg-white flex items-end gap-2 flex-shrink-0">
      {/* Attach button — disabled until Week 5 file upload */}
      <button
        disabled
        aria-label="Đính kèm file"
        className="p-2 text-gray-400 opacity-50 cursor-not-allowed flex-shrink-0"
      >
        <Paperclip size={20} />
      </button>

      {/* Text input */}
      <textarea
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        placeholder={disabled ? 'Chat sẽ mở ở Tuần 4...' : 'Nhập tin nhắn...'}
        rows={1}
        className="flex-1 resize-none rounded-2xl border border-gray-200 px-4 py-2 text-sm
          focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent
          disabled:bg-gray-50 disabled:text-gray-400 max-h-32 overflow-y-auto"
      />

      {/* Send button */}
      <button
        onClick={handleSend}
        disabled={disabled || !value.trim()}
        aria-label="Gửi tin nhắn"
        className="bg-indigo-600 text-white rounded-full p-2 disabled:opacity-40
          hover:bg-indigo-700 transition-colors flex-shrink-0"
      >
        <Send size={18} />
      </button>
    </div>
  )
}
