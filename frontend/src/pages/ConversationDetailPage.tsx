import { useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { MessageCircle } from 'lucide-react'
import { useConversation } from '@/features/conversations/hooks'
import ConversationHeader from '@/features/conversations/components/ConversationHeader'
import { ConversationInfoPanel } from '@/features/conversations/components/ConversationInfoPanel'
import { MessagesList } from '@/features/messages/components/MessagesList'
import { MessageInput } from '@/features/messages/components/MessageInput'
import { ReplyPreviewBox } from '@/features/messages/components/ReplyPreviewBox'
import { useConvSubscription } from '@/features/messages/useConvSubscription'
import { useTypingIndicator } from '@/features/messages/useTypingIndicator'
import { TypingIndicator } from '@/features/messages/components/TypingIndicator'
import type { MessageDto } from '@/types/message'

// ---------------------------------------------------------------------------
// Skeleton — hiển thị khi conversation đang load
// ---------------------------------------------------------------------------
function DetailPageSkeleton() {
  return (
    <div className="flex flex-col h-full animate-pulse">
      {/* header skeleton */}
      <div className="h-16 bg-gray-100 border-b border-gray-200 flex-shrink-0" />
      {/* messages area skeleton */}
      <div className="flex-1 bg-gray-50" />
      {/* input skeleton */}
      <div className="h-[68px] bg-gray-100 border-t border-gray-200 flex-shrink-0" />
    </div>
  )
}

// ---------------------------------------------------------------------------
// ConversationDetailPage
// ---------------------------------------------------------------------------
export default function ConversationDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const { data: conversation, isLoading, isError, error } = useConversation(id ?? '')
  const [showInfo, setShowInfo] = useState(false)
  // replyingTo lưu kèm conversationId để auto-clear khi đổi conversation
  const [replyState, setReplyState] = useState<{ msg: MessageDto; convId: string } | null>(null)
  // Chỉ hiển thị reply nếu convId khớp với id hiện tại (auto-clear khi điều hướng)
  const replyingTo = replyState !== null && replyState.convId === id ? replyState.msg : null

  const handleReply = useCallback((msg: MessageDto) => {
    setReplyState({ msg, convId: id! })
  }, [id])

  const handleCancelReply = useCallback(() => {
    setReplyState(null)
  }, [])

  // Subscribe /topic/conv.{id} — nhận message realtime, merge vào cache với dedupe
  useConvSubscription(id)

  // Typing indicator — publish khi gõ, nhận khi người khác gõ
  const { typingUsers, startTyping, stopTyping } = useTypingIndicator(id ?? '')

  // -- Loading --
  if (isLoading) return <DetailPageSkeleton />

  // -- Error --
  if (isError) {
    // Axios wraps HTTP errors in error.response
    const status = (error as { response?: { status?: number } })?.response?.status
    const is404 = status === 404

    return (
      <div className="flex-1 flex flex-col items-center justify-center gap-4 text-center p-8 bg-gray-50">
        <MessageCircle size={48} className="text-gray-300" />
        <p className="text-gray-600 font-medium">
          {is404 ? 'Không tìm thấy cuộc trò chuyện' : 'Không thể tải cuộc trò chuyện'}
        </p>
        <button
          onClick={() => navigate('/conversations')}
          className="px-4 py-2 bg-indigo-600 text-white rounded-lg text-sm
            hover:bg-indigo-700 transition-colors"
        >
          Quay lại
        </button>
      </div>
    )
  }

  // -- No data (should not happen with React Query but type-guard) --
  if (!conversation) return null

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <ConversationHeader
        conversation={conversation}
        onToggleInfo={() => setShowInfo((v) => !v)}
        onBack={() => navigate('/conversations')}
      />

      {/* Body: messages + optional info panel */}
      <div className="flex flex-1 overflow-hidden">
        {/* Messages column */}
        <div className="flex-1 flex flex-col overflow-hidden">
          <MessagesList conversationId={id!} onReply={handleReply} />
          <TypingIndicator typingUsers={typingUsers} />
          <ReplyPreviewBox replyingTo={replyingTo} onCancel={handleCancelReply} />
          <MessageInput
            conversationId={id!}
            replyToMessageId={replyingTo?.id ?? null}
            onTypingStart={startTyping}
            onTypingStop={stopTyping}
            onSent={handleCancelReply}
          />
        </div>

        {/* Info panel — slide-in từ phải */}
        <ConversationInfoPanel
          conversation={conversation}
          open={showInfo}
          onClose={() => setShowInfo(false)}
        />
      </div>
    </div>
  )
}
