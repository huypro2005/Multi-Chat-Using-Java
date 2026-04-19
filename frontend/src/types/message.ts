// ---------------------------------------------------------------------------
// Message types — generated from docs/API_CONTRACT.md
// Mọi thay đổi contract phải update file này trước.
// ---------------------------------------------------------------------------

// Dùng const object thay vì enum vì TypeScript erasableSyntaxOnly mode.
export const MessageType = {
  TEXT: 'TEXT',
  IMAGE: 'IMAGE',
  FILE: 'FILE',
  SYSTEM: 'SYSTEM',
} as const
export type MessageType = (typeof MessageType)[keyof typeof MessageType]

export interface ReplyPreviewDto {
  id: string
  senderName: string
  contentPreview: string
}

export interface MessageSenderDto {
  id: string
  username: string
  fullName: string
  avatarUrl: string | null
}

export interface MessageDto {
  id: string
  conversationId: string
  sender: MessageSenderDto
  type: MessageType
  content: string
  replyToMessage: ReplyPreviewDto | null
  editedAt: string | null // ISO8601
  createdAt: string // ISO8601
}

export interface MessageListResponse {
  items: MessageDto[]
  hasMore: boolean
  nextCursor: string | null // ISO8601, dùng để fetch trang cũ hơn
}

export interface SendMessageRequest {
  content: string
  type?: MessageType
  replyToMessageId?: string // UUID, optional
}

// Optimistic message (trước khi BE confirm)
export interface OptimisticMessage extends MessageDto {
  tempId: string
  status: 'SENDING' | 'FAILED'
}
