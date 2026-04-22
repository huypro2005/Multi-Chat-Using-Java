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
  contentPreview: string | null   // null nếu source message đã bị xoá
  deletedAt: string | null        // ISO8601 | null
}

export interface MessageSenderDto {
  id: string
  username: string
  fullName: string
  avatarUrl: string | null
}

// ---------------------------------------------------------------------------
// AttachmentDto — file/image attachment metadata returned by BE
// ---------------------------------------------------------------------------
export interface AttachmentDto {
  id: string
  mime: string
  name: string
  size: number
  url: string
  thumbUrl: string | null
  iconType: 'IMAGE' | 'PDF' | 'WORD' | 'EXCEL' | 'POWERPOINT' | 'TEXT' | 'ARCHIVE' | 'GENERIC'
  expiresAt: string // ISO8601
}

// ---------------------------------------------------------------------------
// SYSTEM message metadata (v1.2.0-w7-system)
// Only present when type == 'SYSTEM'. All fields optional (apply-or-absent per event type).
// ---------------------------------------------------------------------------
export const SystemEventType = {
  GROUP_CREATED: 'GROUP_CREATED',
  MEMBER_ADDED: 'MEMBER_ADDED',
  MEMBER_REMOVED: 'MEMBER_REMOVED',
  MEMBER_LEFT: 'MEMBER_LEFT',
  ROLE_PROMOTED: 'ROLE_PROMOTED',
  ROLE_DEMOTED: 'ROLE_DEMOTED',
  OWNER_TRANSFERRED: 'OWNER_TRANSFERRED',
  GROUP_RENAMED: 'GROUP_RENAMED',
} as const
export type SystemEventType = (typeof SystemEventType)[keyof typeof SystemEventType]

export interface SystemMetadata {
  actorId?: string
  actorName?: string
  targetId?: string
  targetName?: string
  oldValue?: string
  newValue?: string
  autoTransferred?: boolean
}

export interface MessageDto {
  id: string
  conversationId: string
  // null khi type == 'SYSTEM' (SYSTEM messages không có user sender)
  sender: MessageSenderDto | null
  type: MessageType
  content: string | null // null khi message đã bị soft-delete (deletedAt != null) hoặc attachment-only
  attachments: AttachmentDto[] // luôn array, không null (BE trả [] nếu không có)
  replyToMessage: ReplyPreviewDto | null
  editedAt: string | null // ISO8601
  createdAt: string // ISO8601
  // Soft-delete fields — set bởi BE khi xoá, null nếu chưa bị xoá
  deletedAt: string | null // ISO8601
  deletedBy: string | null // UUID string
  // SYSTEM message fields (v1.2.0-w7-system) — null cho mọi type != 'SYSTEM'
  systemEventType?: SystemEventType | null
  systemMetadata?: SystemMetadata | null
  // Optimistic / Path B fields — chỉ set trên client, không có trong REST response
  clientTempId?: string
  status?: 'sending' | 'sent' | 'failed'
  failureCode?: string
  failureReason?: string
  // Delete operation state — chỉ set trên client trong khoảng chờ ACK
  deleteStatus?: 'deleting'
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

// DELETE ACK minimal payload — chỉ có id + conversationId + deletedAt + deletedBy (§3d.3)
export interface DeleteAckMessage {
  id: string
  conversationId: string
  deletedAt: string
  deletedBy: string
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
