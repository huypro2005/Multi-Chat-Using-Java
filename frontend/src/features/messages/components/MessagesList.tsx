import { useRef, useState, useEffect, useMemo, useCallback } from 'react'
import { RefreshCw, MessageCircle } from 'lucide-react'
import { useAuthStore } from '@/stores/authStore'
import { useMessages } from '../hooks'
import MessageItem from './MessageItem'
import type { MessageDto } from '@/types/message'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
interface Props {
  conversationId: string
  onReply?: (message: MessageDto) => void
}

// ---------------------------------------------------------------------------
// shouldShowAvatar — hiện avatar khi đầu nhóm hoặc gap > 1 phút
// ---------------------------------------------------------------------------
function shouldShowAvatar(messages: MessageDto[], index: number): boolean {
  if (index === 0) return true
  const curr = messages[index]
  const prev = messages[index - 1]
  if (curr.sender.id !== prev.sender.id) return true
  const gap = new Date(curr.createdAt).getTime() - new Date(prev.createdAt).getTime()
  return gap > 60_000
}

// ---------------------------------------------------------------------------
// MessagesSkeleton
// ---------------------------------------------------------------------------
function MessagesSkeleton() {
  return (
    <div className="space-y-3 animate-pulse px-4 py-4">
      {[...Array(5)].map((_, i) => (
        <div key={i} className={`flex ${i % 2 === 0 ? 'justify-start' : 'justify-end'}`}>
          <div
            className="h-10 bg-gray-200 rounded-2xl"
            style={{ width: `${120 + i * 30}px` }}
          />
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// MessagesError
// ---------------------------------------------------------------------------
function MessagesError({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-12 text-center px-4">
      <RefreshCw size={32} className="text-gray-300" />
      <p className="text-gray-500 text-sm">Không thể tải tin nhắn</p>
      <button
        onClick={onRetry}
        className="text-sm text-indigo-600 hover:text-indigo-700 font-medium
          underline underline-offset-2 transition-colors"
      >
        Thử lại
      </button>
    </div>
  )
}

// ---------------------------------------------------------------------------
// MessagesEmpty
// ---------------------------------------------------------------------------
function MessagesEmpty() {
  return (
    <div className="flex flex-col items-center justify-center gap-3 py-12 text-center px-4">
      <MessageCircle size={48} className="text-gray-300" />
      <p className="text-gray-500 font-medium">Bắt đầu cuộc trò chuyện</p>
      <p className="text-gray-400 text-sm">Gửi tin nhắn đầu tiên để bắt đầu</p>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Spinner nhỏ dùng khi fetch trang cũ
// ---------------------------------------------------------------------------
function SmallSpinner() {
  return (
    <div className="flex justify-center py-2">
      <div className="w-5 h-5 border-2 border-gray-300 border-t-indigo-500 rounded-full animate-spin" />
    </div>
  )
}

// ---------------------------------------------------------------------------
// MessagesList
// ---------------------------------------------------------------------------
export function MessagesList({ conversationId, onReply }: Props) {
  const user = useAuthStore((s) => s.user)

  const {
    data,
    isLoading,
    isError,
    refetch,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useMessages(conversationId)

  // Infinite query cache stores pages as:
  // - pages[0]   = newest window
  // - pages[1..] = older windows loaded by fetchNextPage()
  // UI cần render theo thời gian cũ -> mới, nên đảo page trước khi flatten.
  const messages = useMemo(() => {
    if (!data) return []
    return [...data.pages].reverse().flatMap((page) => page.items)
  }, [data])

  // --- Scroll refs ---
  const bottomRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const topSentinelRef = useRef<HTMLDivElement>(null)
  const [isAtBottom, setIsAtBottom] = useState(true)
  const didInitialScrollRef = useRef(false)

  // Mỗi khi đổi conversation, cho phép auto-scroll lần đầu về đáy.
  useEffect(() => {
    didInitialScrollRef.current = false
  }, [conversationId])

  // Lần đầu vào chat: luôn nhảy xuống tin nhắn mới nhất.
  useEffect(() => {
    if (!didInitialScrollRef.current && messages.length > 0 && bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: 'auto' })
      didInitialScrollRef.current = true
    }
  }, [messages.length])

  // Scroll to bottom khi có message mới VÀ đang ở gần bottom
  useEffect(() => {
    if (isAtBottom && bottomRef.current) {
      bottomRef.current.scrollIntoView({ behavior: 'smooth' })
    }
  }, [messages.length, isAtBottom])

  // Track isAtBottom
  const handleScroll = useCallback((e: React.UIEvent<HTMLDivElement>) => {
    const el = e.currentTarget
    const threshold = 80 // px from bottom
    setIsAtBottom(el.scrollHeight - el.scrollTop - el.clientHeight < threshold)
  }, [])

  // Infinite scroll — load older messages khi scroll lên top
  useEffect(() => {
    const sentinel = topSentinelRef.current
    if (!sentinel) return

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting && hasNextPage && !isFetchingNextPage) {
          const container = containerRef.current
          const prevScrollHeight = container?.scrollHeight ?? 0

          void fetchNextPage().then(() => {
            // Preserve scroll position sau khi content được thêm vào trên
            if (container) {
              container.scrollTop += container.scrollHeight - prevScrollHeight
            }
          })
        }
      },
      { threshold: 0.1 },
    )

    observer.observe(sentinel)
    return () => observer.disconnect()
  }, [hasNextPage, isFetchingNextPage, fetchNextPage])

  return (
    <div
      ref={containerRef}
      className="flex-1 overflow-y-auto p-4 space-y-1 bg-gray-50"
      onScroll={handleScroll}
    >
      {/* Sentinel top — kích hoạt infinite scroll khi user scroll lên đây */}
      <div ref={topSentinelRef} />

      {/* Loading older messages */}
      {isFetchingNextPage && <SmallSpinner />}

      {/* States */}
      {isLoading && <MessagesSkeleton />}
      {isError && <MessagesError onRetry={refetch} />}
      {!isLoading && !isError && messages.length === 0 && <MessagesEmpty />}

      {/* Message list */}
      {messages.map((msg, idx) => {
        const isOwn = msg.sender.id === user?.id
        return (
          <MessageItem
            key={msg.id}
            message={msg}
            isOwn={isOwn}
            showAvatar={shouldShowAvatar(messages, idx)}
            onReply={onReply}
          />
        )
      })}

      {/* Bottom anchor — auto-scroll target */}
      <div ref={bottomRef} />
    </div>
  )
}
