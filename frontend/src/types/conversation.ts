// ---------------------------------------------------------------------------
// Conversation types — generated from docs/API_CONTRACT.md v0.5.0-conversations
// Mọi thay đổi contract phải update file này trước.
// ---------------------------------------------------------------------------

// Dùng const object thay vì enum vì TypeScript erasableSyntaxOnly mode.
export const ConversationType = {
  ONE_ON_ONE: 'ONE_ON_ONE',
  GROUP: 'GROUP',
} as const
export type ConversationType = (typeof ConversationType)[keyof typeof ConversationType]

export const MemberRole = {
  OWNER: 'OWNER',
  ADMIN: 'ADMIN',
  MEMBER: 'MEMBER',
} as const
export type MemberRole = (typeof MemberRole)[keyof typeof MemberRole]

export interface CreatedByDto {
  id: string
  username: string
  fullName: string
  avatarUrl: string | null
}

export interface MemberDto {
  userId: string
  username: string
  fullName: string
  avatarUrl: string | null
  role: MemberRole
  joinedAt: string // ISO8601
}

/**
 * Full conversation detail — response của POST /api/conversations (201) và GET /api/conversations/{id} (200).
 * KHÔNG có displayName, displayAvatarUrl, unreadCount, mutedUntil — những fields đó chỉ có trong ConversationSummaryDto.
 * Dùng getConversationDisplayName() để derive display name ở FE runtime.
 */
export interface ConversationDto {
  id: string
  type: ConversationType
  name: string | null
  avatarUrl: string | null
  createdBy: CreatedByDto | null
  members: MemberDto[]
  createdAt: string // ISO8601
  lastMessageAt: string | null // ISO8601
}

/**
 * Derive display name từ ConversationDto ở FE runtime.
 * - GROUP: dùng conv.name (fallback 'Nhóm không tên')
 * - ONE_ON_ONE: lấy fullName (hoặc username) của member khác
 */
export function getConversationDisplayName(
  conv: ConversationDto,
  currentUserId: string,
): string {
  if (conv.type === ConversationType.GROUP) {
    return conv.name ?? 'Nhóm không tên'
  }
  // ONE_ON_ONE: lấy tên người còn lại
  const other = conv.members.find((m) => m.userId !== currentUserId)
  return other?.fullName ?? other?.username ?? 'Unknown'
}

export interface ConversationSummaryDto {
  id: string
  type: ConversationType
  name: string | null
  avatarUrl: string | null
  displayName: string
  displayAvatarUrl: string | null
  memberCount: number
  lastMessageAt: string | null
  unreadCount: number // V1 = 0
  mutedUntil: string | null
}

// Spring Page format — match BE response thực tế
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface CreateConversationRequest {
  type: ConversationType
  memberIds: string[]
  name?: string
}

export interface UserSearchDto {
  id: string
  username: string
  fullName: string
  avatarUrl: string | null
}
