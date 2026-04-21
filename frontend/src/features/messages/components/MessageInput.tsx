import { useState, useRef, useCallback, useEffect } from 'react'
import { Paperclip, Send, WifiOff } from 'lucide-react'
import { toast } from 'sonner'
import { useSendMessage } from '../hooks'
import { getConnectionState, onConnectionStateChange } from '@/lib/stompClient'
import { useUploadFile } from '@/features/files/useUploadFile'
import { validateFiles } from '@/features/files/validateFiles'
import { PendingAttachmentItem } from '@/features/files/components/PendingAttachmentItem'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
interface Props {
  conversationId: string
  /** false (default) = enabled; true = disabled (e.g. no permission) */
  disabled?: boolean
  /** UUID của message đang được reply, null nếu không reply */
  replyToMessageId?: string | null
  /** Called on every keystroke (for typing indicator publish) */
  onTypingStart?: () => void
  /** Called after send or on textarea blur (for typing indicator stop) */
  onTypingStop?: () => void
  /** Called sau khi message được gửi thành công — dùng để clear replyingTo state */
  onSent?: () => void
}

const MAX_CHARS = 5000
const WARN_CHARS = 4500

/**
 * MessageInput — thanh nhập và gửi tin nhắn qua STOMP (Path B, ADR-016).
 * Enter = gửi, Shift+Enter = xuống dòng.
 * Auto-resize textarea tối đa 5 dòng.
 * Disable send khi STOMP chưa connect.
 * Hỗ trợ đính kèm ảnh (tối đa 5) hoặc 1 PDF.
 * Hỗ trợ kéo thả file vào vùng nhập.
 */
export function MessageInput({
  conversationId,
  disabled = false,
  replyToMessageId,
  onTypingStart,
  onTypingStop,
  onSent,
}: Props) {
  const [content, setContent] = useState('')
  const [charError, setCharError] = useState(false)
  const [sendError, setSendError] = useState<string | null>(null)
  const [isDragging, setIsDragging] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  // Track STOMP connection state để disable input khi mất kết nối
  const [isConnected, setIsConnected] = useState(() => getConnectionState() === 'CONNECTED')

  useEffect(() => {
    const unsub = onConnectionStateChange((state) => {
      setIsConnected(state === 'CONNECTED')
    })
    return unsub
  }, [])

  const sendMessage = useSendMessage(conversationId)
  const { pending, upload, cancel, remove, clear } = useUploadFile()

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

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? [])
    // Reset input value so selecting the same file again triggers onChange
    e.target.value = ''
    if (files.length === 0) return
    const { ok, errors } = validateFiles(files, pending.length)
    errors.forEach((err) => toast.error(err))
    if (ok.length > 0) void upload(ok)
  }

  const handleSend = useCallback(() => {
    const trimmed = content.trim()
    const hasDoneAttachments = pending.some((p) => p.status === 'done')

    // Guard: still uploading
    if (pending.some((p) => p.status === 'uploading')) {
      toast.error('Đang tải tệp...')
      return
    }

    // Guard: nothing to send
    if (!trimmed && !hasDoneAttachments) return

    if (disabled || !isConnected) return
    if (trimmed.length > MAX_CHARS) {
      setCharError(true)
      return
    }

    const attachmentIds = pending
      .filter((p) => p.status === 'done' && p.result)
      .map((p) => p.result!.id)

    try {
      onTypingStop?.()
      sendMessage(trimmed, replyToMessageId ?? undefined, attachmentIds)
      onSent?.()
      setContent('')
      setCharError(false)
      setSendError(null)
      clear()
      // Reset textarea height
      if (textareaRef.current) {
        textareaRef.current.style.height = 'auto'
      }
      textareaRef.current?.focus()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Không thể gửi tin nhắn'
      setSendError(message === 'STOMP_NOT_CONNECTED' ? 'Mất kết nối, thử lại sau' : message)
    }
  }, [content, disabled, isConnected, pending, sendMessage, onTypingStop, replyToMessageId, onSent, clear])

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !disabled) {
      e.preventDefault()
      handleSend()
    }
  }

  // Drag-drop handlers
  const handleDragOver = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragging(true)
  }

  const handleDragLeave = (e: React.DragEvent<HTMLDivElement>) => {
    // Only clear when leaving the container (not a child element)
    if (!e.currentTarget.contains(e.relatedTarget as Node)) {
      setIsDragging(false)
    }
  }

  const handleDrop = (e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault()
    setIsDragging(false)
    const files = Array.from(e.dataTransfer.files)
    const { ok, errors } = validateFiles(files, pending.length)
    errors.forEach((err) => toast.error(err))
    if (ok.length > 0) void upload(ok)
  }

  const showCounter = content.length > WARN_CHARS
  const isOverLimit = content.length > MAX_CHARS
  const isInputDisabled = disabled || !isConnected
  const hasDoneAttachments = pending.some((p) => p.status === 'done')

  return (
    <div
      className={`px-4 py-3 border-t bg-white flex flex-col gap-1 flex-shrink-0
        ${isDragging ? 'ring-2 ring-indigo-400 bg-indigo-50/30' : ''}`}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {/* Mất kết nối banner */}
      {!isConnected && (
        <div className="flex items-center gap-1.5 px-1 py-0.5">
          <WifiOff size={12} className="text-amber-500" />
          <span className="text-xs text-amber-600">Mất kết nối, đang thử lại…</span>
        </div>
      )}

      {/* Pending attachments preview */}
      {pending.length > 0 && (
        <div className="flex gap-2 py-1 overflow-x-auto">
          {pending.map((p) => (
            <PendingAttachmentItem
              key={p.localId}
              pending={p}
              onCancel={() =>
                p.status === 'uploading' ? cancel(p.localId) : remove(p.localId)
              }
            />
          ))}
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
        {/* Hidden file input */}
        <input
          ref={fileInputRef}
          type="file"
          multiple
          accept="image/jpeg,image/png,image/webp,image/gif,application/pdf"
          onChange={handleFileChange}
          className="hidden"
        />

        {/* Attach button — enabled */}
        <button
          type="button"
          onClick={() => fileInputRef.current?.click()}
          disabled={isInputDisabled}
          aria-label="Đính kèm file"
          className="p-2 text-gray-500 hover:text-indigo-600 transition-colors flex-shrink-0
            disabled:opacity-40 disabled:cursor-not-allowed"
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
          disabled={
            isInputDisabled ||
            (!content.trim() && !hasDoneAttachments) ||
            isOverLimit
          }
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
