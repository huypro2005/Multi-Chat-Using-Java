import { useRef, useState, useEffect, useMemo, useCallback } from 'react'
import { RefreshCw } from 'lucide-react'
import { useAuthStore } from '@/stores/authStore'
import { useMessages } from '../hooks'
import { useConversation } from '@/features/conversations/hooks'
import { ConversationType } from '@/types/conversation'
import MessageItem from './MessageItem'
import { SystemMessage } from './SystemMessage'
import { PinnedMessagesBanner } from './PinnedMessagesBanner'
import { useAutoMarkRead } from '../hooks/useAutoMarkRead'
import type { MessageDto } from '@/types/message'
import { MessageSkeleton } from '@/features/common/components/Skeleton'
import { EmptyState } from '@/features/common/components/EmptyState'

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------
interface Props {
  conversationId: string
  onReply?: (message: MessageDto) => void
}

// ---------------------------------------------------------------------------
// shouldShowAvatar — hiện avatar khi đầu nhóm hoặc gap > 1 phút
// SYSTEM messages (sender=null) không có avatar → luôn trả false
// ---------------------------------------------------------------------------
function shouldShowAvatar(messages: MessageDto[], index: number): boolean {
  const curr = messages[index]
  // SYSTEM messages không có sender → không bao giờ hiện avatar
  if (!curr.sender) return false
  if (index === 0) return true
  const prev = messages[index - 1]
  // Nếu message trước là SYSTEM hoặc sender khác → hiện avatar
  if (!prev.sender || curr.sender.id !== prev.sender.id) return true
  const gap = new Date(curr.createdAt).getTime() - new Date(prev.createdAt).getTime()
  return gap > 60_000
}

// ---------------------------------------------------------------------------
// MessagesSkeleton
// ---------------------------------------------------------------------------
function MessagesSkeleton() {
  return (
    <div className="px-2 py-2">
      {[...Array(5)].map((_, i) => (
        <MessageSkeleton key={i} />
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
    <EmptyState
      icon="👋"
      title="Bắt đầu cuộc trò chuyện"
      description="Hãy gửi tin nhắn đầu tiên."
    />
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

  // Fetch conversation detail (cached — no extra network call if ConversationDetailPage already fetched)
  const { data: conversation } = useConversation(conversationId)
  const members = conversation?.members ?? []
  const currentMember = members.find((m) => m.userId === user?.id)
  const canPinInConversation = conversation
    ? conversation.type === ConversationType.GROUP
      ? currentMember?.role === 'OWNER' || currentMember?.role === 'ADMIN'
      : true
    : false

  // Infinite query cache stores pages as:
  // - pages[0]   = newest window
  // - pages[1..] = older windows loaded by fetchNextPage()
  // UI cần render theo thời gian cũ -> mới, nên đảo page trước khi flatten.
  const messages = useMemo(() => {
    if (!data) return []
    return [...data.pages].reverse().flatMap((page) => page.items)
  }, [data])

  // Last confirmed message id (non-optimistic, non-system) for auto mark-read
  const lastMessageId = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i]
      if (m.type !== 'SYSTEM' && !m.clientTempId) return m.id
    }
    return undefined
  }, [messages])

  // Auto send read receipt when viewing conversation
  useAutoMarkRead(conversationId, lastMessageId)

  // --- Scroll refs ---
  const bottomRef = useRef<HTMLDivElement>(null)
  const containerRef = useRef<HTMLDivElement>(null)
  const topSentinelRef = useRef<HTMLDivElement>(null)
  const messageRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const highlightTimeoutsRef = useRef<Record<string, number>>({})
  const [isAtBottom, setIsAtBottom] = useState(true)
  const didInitialScrollRef = useRef(false)

  // Mỗi khi đổi conversation, cho phép auto-scroll lần đầu về đáy.
  useEffect(() => {
    didInitialScrollRef.current = false
    messageRefs.current = {}
  }, [conversationId])

  useEffect(() => {
    const highlightTimeouts = highlightTimeoutsRef.current
    return () => {
      for (const timeoutId of Object.values(highlightTimeouts)) {
        window.clearTimeout(timeoutId)
      }
    }
  }, [])

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

  const scrollToMessage = useCallback((messageId: string) => {
    const el = messageRefs.current[messageId]
    if (!el) return

    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
    el.classList.add('ring-2', 'ring-amber-300', 'rounded-xl')

    if (highlightTimeoutsRef.current[messageId]) {
      window.clearTimeout(highlightTimeoutsRef.current[messageId])
    }
    highlightTimeoutsRef.current[messageId] = window.setTimeout(() => {
      el.classList.remove('ring-2', 'ring-amber-300', 'rounded-xl')
      delete highlightTimeoutsRef.current[messageId]
    }, 2000)
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

      <PinnedMessagesBanner
        pinnedMessages={conversation?.pinnedMessages ?? []}
        onScrollTo={scrollToMessage}
      />

      {/* Loading older messages */}
      {isFetchingNextPage && <SmallSpinner />}

      {/* States */}
      {isLoading && <MessagesSkeleton />}
      {isError && <MessagesError onRetry={refetch} />}
      {!isLoading && !isError && messages.length === 0 && <MessagesEmpty />}

      {/* Message list */}
      {messages.map((msg, idx) => {
        // SYSTEM messages: render centered pill, skip avatar/actions/reply logic
        if (msg.type === 'SYSTEM') {
          return (
            <SystemMessage
              key={msg.id}
              message={msg}
              currentUserId={user?.id ?? ''}
            />
          )
        }

        const isOwn = msg.sender?.id === user?.id
        return (
          <div
            key={msg.id}
            ref={(el) => {
              messageRefs.current[msg.id] = el
            }}
          >
            <MessageItem
              message={msg}
              isOwn={isOwn ?? false}
              showAvatar={shouldShowAvatar(messages, idx)}
              onReply={onReply}
              members={members}
              currentUserId={user?.id ?? ''}
              canPin={canPinInConversation}
            />
          </div>
        )
      })}

      {/* Bottom anchor — auto-scroll target */}
      <div ref={bottomRef} />
    </div>
  )
}
