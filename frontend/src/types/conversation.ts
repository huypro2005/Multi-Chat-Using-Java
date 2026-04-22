// ---------------------------------------------------------------------------
// Conversation types — generated from docs/API_CONTRACT.md v1.1.0-w7
// Mọi thay đổi contract phải update file này trước.
// ---------------------------------------------------------------------------

// Dùng const object thay vì enum vì TypeScript erasableSyntaxOnly mode.
import type { MessageDto } from './message'

export const ConversationType = {
  ONE_ON_ONE: 'ONE_ON_ONE',
  DIRECT: 'DIRECT',
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
  isBlockedByMe?: boolean
  role: MemberRole
  lastReadMessageId: string | null // W7-D5: read receipt pointer
  joinedAt: string // ISO8601
}

/**
 * Full conversation detail — response của POST /api/conversations (201) và GET /api/conversations/{id} (200).
 * KHÔNG có displayName, displayAvatarUrl, unreadCount, mutedUntil — những fields đó chỉ có trong ConversationSummaryDto.
 * Dùng getConversationDisplayName() để derive display name ở FE runtime.
 */
export interface OwnerDto {
  userId: string
  username: string
  fullName: string
}

export interface ConversationDto {
  id: string
  type: ConversationType
  name: string | null
  avatarUrl: string | null
  createdBy: CreatedByDto | null
  owner: OwnerDto | null // W7: chỉ có cho GROUP
  members: MemberDto[]
  pinnedMessages?: MessageDto[]
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
  memberIds?: string[]
  targetUserId?: string
  name?: string
  avatarFileId?: string | null
}

// W7: Requests cho group member management
export interface AddMembersRequest {
  userIds: string[]
}

export interface AddMembersSkippedItem {
  userId: string
  reason: 'ALREADY_MEMBER' | 'USER_NOT_FOUND' | 'BLOCKED'
}

export interface AddMembersResponse {
  added: MemberDto[]
  skipped: AddMembersSkippedItem[]
}

export interface ChangeRoleRequest {
  role: 'ADMIN' | 'MEMBER'
}

export interface TransferOwnerRequest {
  targetUserId: string
}

export interface UpdateGroupRequest {
  name?: string
  avatarFileId?: string | null // null = xóa avatar, undefined = không đổi
}

export interface UserSearchDto {
  id: string
  username: string
  fullName: string
  avatarUrl: string | null
}
