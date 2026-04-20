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
  // Optimistic / Path B fields — chỉ set trên client, không có trong REST response
  clientTempId?: string
  status?: 'sending' | 'sent' | 'failed'
  failureCode?: string
  failureReason?: string
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

// Optimistic message (trước khi BE confirm) — Path B (ADR-016)
export interface OptimisticMessage extends Omit<MessageDto, 'status'> {
  clientTempId: string
  status: 'sending' | 'failed'
}

// ---------------------------------------------------------------------------
// Unified ACK/ERROR envelope (ADR-017) — breaking change từ {tempId, message}
// operation discriminator dùng để route handler trong useAckErrorSubscription
// ---------------------------------------------------------------------------
export interface AckEnvelope {
  operation: 'SEND' | 'EDIT' | 'DELETE'
  clientId: string // tempId cho SEND, clientEditId cho EDIT
  message: MessageDto
}

export interface ErrorEnvelope {
  operation: 'SEND' | 'EDIT' | 'DELETE'
  clientId: string
  error: string
  code: string
}

// Legacy — giữ lại để không break import cũ nếu có, nhưng sẽ dùng AckEnvelope/ErrorEnvelope
/** @deprecated Use AckEnvelope instead */
export interface AckPayload {
  tempId: string
  message: MessageDto
}

/** @deprecated Use ErrorEnvelope instead */
export interface ErrorPayload {
  tempId: string
  error: string
  code: string
}

// Edit state cho optimistic UI
export type EditStatus = 'idle' | 'editing' | 'saving' | 'saved' | 'error'
