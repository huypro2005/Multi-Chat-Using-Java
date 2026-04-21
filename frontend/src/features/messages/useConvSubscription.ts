// ---------------------------------------------------------------------------
// useConvSubscription — subscribe /topic/conv.{id} và merge message realtime
// vào React Query cache với dedupe.
//
// Thiết kế:
// - Subscribe ngay nếu STOMP đã CONNECTED.
// - Dùng onConnectionStateChange để re-subscribe khi state chuyển CONNECTED
//   (sau reconnect, sau auth refresh).
// - Cleanup: unsubscribe + remove state listener trong useEffect cleanup.
// - Dedupe: kiểm tra id trước khi append — tránh duplicate khi sender nhận
//   lại broadcast của chính mình sau onSuccess (xem SOCKET_EVENTS.md mục 3.1).
// - Append vào pages[lastIdx].items (last page = newest messages, sorted ASC).
// - Invalidate ['conversations'] để sidebar refresh lastMessageAt.
// ---------------------------------------------------------------------------

import { useEffect, useRef } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { getStompClient, onConnectionStateChange } from '@/lib/stompClient'
import { conversationKeys, messageKeys } from '@/features/conversations/queryKeys'
import { useAuthStore } from '@/stores/authStore'
import type { MessageDto, MessageListResponse } from '@/types/message'
import type { ConversationDto, MemberDto } from '@/types/conversation'
import { catchUpMissedMessages } from './catchUp'

interface WsEvent {
  type: string
  payload: unknown
}

// Payload của MESSAGE_UPDATED — minimal, chỉ fields thay đổi (§3.2 SOCKET_EVENTS.md)
interface MessageUpdatedPayload {
  id: string
  conversationId: string
  content: string
  editedAt: string
}

// Payload của MESSAGE_DELETED (§3.3 SOCKET_EVENTS.md)
interface MessageDeletedPayload {
  id: string
  conversationId: string
  deletedAt: string
  deletedBy: string
}

// W7 event payloads
interface MemberAddedPayload {
  conversationId: string
  member: MemberDto
  addedBy: { userId: string; username: string; fullName: string }
}

interface MemberRemovedPayload {
  conversationId: string
  userId: string
  reason: 'KICKED' | 'LEFT'
  removedBy: { userId: string; username: string; fullName: string } | null
}

interface RoleChangedPayload {
  conversationId: string
  userId: string
  oldRole: 'ADMIN' | 'MEMBER'
  newRole: 'ADMIN' | 'MEMBER'
  changedBy: { userId: string; username: string; fullName: string }
}

interface OwnerTransferredPayload {
  conversationId: string
  previousOwner: { userId: string; username: string }
  newOwner: { userId: string; username: string; fullName: string }
  autoTransferred: boolean
}

interface ConversationUpdatedPayload {
  conversationId: string
  changes: { name?: string; avatarUrl?: string }
  updatedBy: { userId: string; fullName: string }
}

interface GroupDeletedPayload {
  conversationId: string
  deletedBy: { userId: string; username: string; fullName: string }
}

function isLikelyMatchOptimistic(tempMsg: MessageDto, incoming: MessageDto): boolean {
  if (!tempMsg.clientTempId) return false
  if (tempMsg.type !== incoming.type) return false
  if (tempMsg.content !== incoming.content) return false

  const tempTs = Date.parse(tempMsg.createdAt)
  const incomingTs = Date.parse(incoming.createdAt)
  if (Number.isNaN(tempTs) || Number.isNaN(incomingTs)) return true

  // optimistic timestamp và server timestamp thường rất gần nhau
  return Math.abs(incomingTs - tempTs) <= 30_000
}

export function useConvSubscription(conversationId: string | undefined): void {
  const queryClient = useQueryClient()
  const currentUserId = useAuthStore((s) => s.user?.id ?? null)
  const navigate = useNavigate()
  const navigateRef = useRef(navigate)

  useEffect(() => {
    navigateRef.current = navigate
  })

  // Track xem đã từng disconnect chưa — chỉ catch-up khi reconnect, không phải connect lần đầu
  const wasDisconnectedRef = useRef(false)

  useEffect(() => {
    if (!conversationId) return

    // cleanup ref — lưu unsubscribe fn của subscription STOMP hiện tại
    let cleanup: (() => void) | null = null

    function subscribe(): void {
      const client = getStompClient()
      if (!client?.connected) return

      const sub = client.subscribe(`/topic/conv.${conversationId}`, (frame) => {
        try {
          const event = JSON.parse(frame.body) as WsEvent
          if (event.type === 'MESSAGE_CREATED') {
            appendToCache(queryClient, conversationId!, event.payload as MessageDto, currentUserId)
          } else if (event.type === 'MESSAGE_UPDATED') {
            handleMessageUpdated(
              queryClient,
              conversationId!,
              event.payload as MessageUpdatedPayload,
            )
          } else if (event.type === 'MESSAGE_DELETED') {
            handleMessageDeleted(
              queryClient,
              conversationId!,
              event.payload as MessageDeletedPayload,
            )
          } else if (event.type === 'MEMBER_ADDED') {
            handleMemberAdded(queryClient, conversationId!, event.payload as MemberAddedPayload)
          } else if (event.type === 'MEMBER_REMOVED') {
            handleMemberRemoved(
              queryClient,
              conversationId!,
              event.payload as MemberRemovedPayload,
              currentUserId,
            )
          } else if (event.type === 'ROLE_CHANGED') {
            handleRoleChanged(
              queryClient,
              conversationId!,
              event.payload as RoleChangedPayload,
              currentUserId,
            )
          } else if (event.type === 'OWNER_TRANSFERRED') {
            handleOwnerTransferred(
              queryClient,
              conversationId!,
              event.payload as OwnerTransferredPayload,
              currentUserId,
            )
          } else if (event.type === 'CONVERSATION_UPDATED') {
            handleConversationUpdated(
              queryClient,
              conversationId!,
              event.payload as ConversationUpdatedPayload,
            )
          } else if (event.type === 'GROUP_DELETED') {
            handleGroupDeleted(
              queryClient,
              conversationId!,
              event.payload as GroupDeletedPayload,
              navigateRef.current,
            )
          }
        } catch (e) {
          console.error('[WS] Failed to parse message frame', e)
        }
      })

      cleanup = () => sub.unsubscribe()
    }

    // Subscribe ngay nếu đã connected
    subscribe()

    // Re-subscribe khi connection state thay đổi
    const unsubState = onConnectionStateChange((state) => {
      if (state === 'DISCONNECTED' || state === 'ERROR') {
        wasDisconnectedRef.current = true
        cleanup?.()
        cleanup = null
      } else if (state === 'CONNECTED') {
        cleanup?.()
        cleanup = null
        subscribe()

        // Chỉ catch-up nếu đã từng disconnect (không phải lần đầu connect)
        if (wasDisconnectedRef.current) {
          void catchUpMissedMessages(queryClient, conversationId!)
          wasDisconnectedRef.current = false
        }
      }
    })

    return () => {
      cleanup?.()
      cleanup = null
      unsubState()
    }
  }, [conversationId, currentUserId, queryClient, navigate])
}

// ---------------------------------------------------------------------------
// Helper: handle MESSAGE_UPDATED broadcast — cập nhật content + editedAt
// ---------------------------------------------------------------------------
function handleMessageUpdated(
  queryClient: QueryClient,
  conversationId: string,
  updated: MessageUpdatedPayload,
): void {
  queryClient.setQueryData(
    messageKeys.all(conversationId),
    (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
      if (!old) return old

      const pages = old.pages.map((page) => {
        const idx = page.items.findIndex((m) => m.id === updated.id)
        if (idx === -1) return page

        const existing = page.items[idx]

        // Dedupe theo editedAt (§3.2): nếu broadcast cũ hơn hoặc bằng cache → skip
        // So sánh lexicographic ISO8601 UTC (Z format đồng nhất) hoặc parse Date
        if (existing.editedAt && existing.editedAt >= updated.editedAt) return page

        const nextItems = [...page.items]
        nextItems[idx] = {
          ...existing,
          content: updated.content,
          editedAt: updated.editedAt,
          // Clear failure state nếu có (ACK về qua broadcast thay vì user queue)
          failureCode: undefined,
          failureReason: undefined,
        }
        return { ...page, items: nextItems }
      })

      return { ...old, pages }
    },
  )

  // V1 đơn giản: luôn invalidate conversations để sidebar refresh lastMessagePreview
  void queryClient.invalidateQueries({ queryKey: ['conversations'] })
}

// ---------------------------------------------------------------------------
// Helper: handle MESSAGE_DELETED broadcast — soft delete (§3.3 SOCKET_EVENTS.md)
// ---------------------------------------------------------------------------
function handleMessageDeleted(
  queryClient: QueryClient,
  conversationId: string,
  deleted: MessageDeletedPayload,
): void {
  queryClient.setQueryData(
    messageKeys.all(conversationId),
    (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
      if (!old) return old

      const pages = old.pages.map((page) => {
        const idx = page.items.findIndex((m) => m.id === deleted.id)
        if (idx === -1) return page

        const nextItems = [...page.items]
        nextItems[idx] = {
          ...nextItems[idx],
          content: null,
          deletedAt: deleted.deletedAt,
          deletedBy: deleted.deletedBy,
          // Clear deleteStatus nếu sender tab này đang chờ ACK từ trước
          deleteStatus: undefined,
        }
        return { ...page, items: nextItems }
      })

      return { ...old, pages }
    },
  )

  // Invalidate conversations để sidebar refresh (message bị xoá có thể là lastMessage)
  void queryClient.invalidateQueries({ queryKey: ['conversations'] })
}

// ---------------------------------------------------------------------------
// Helper: append message vào React Query infinite cache với dedupe
// ---------------------------------------------------------------------------
function appendToCache(
  queryClient: QueryClient,
  conversationId: string,
  newMsg: MessageDto,
  currentUserId: string | null,
): void {
  queryClient.setQueryData(
    messageKeys.all(conversationId),
    (old: { pages: MessageListResponse[]; pageParams: unknown[] } | undefined) => {
      if (!old) return old

      // DEDUPE theo real id (cross-all-pages)
      const exists = old.pages.some((page) => page.items.some((m) => m.id === newMsg.id))
      if (exists) return old

      // Nếu là tin của chính mình, ưu tiên replace optimistic temp gần nhất thay vì append mới.
      // Tránh case UI hiện 2 bubble: 1 cái thành công + 1 cái vẫn loading.
      if (currentUserId && newMsg.sender.id === currentUserId) {
        let replaced = false
        const pages = old.pages.map((page) => {
          if (replaced) return page

          let matchIdx = -1
          for (let i = page.items.length - 1; i >= 0; i -= 1) {
            if (isLikelyMatchOptimistic(page.items[i], newMsg)) {
              matchIdx = i
              break
            }
          }

          if (matchIdx === -1) return page
          replaced = true

          const nextItems = [...page.items]
          nextItems[matchIdx] = newMsg
          return { ...page, items: nextItems }
        })

        if (replaced) return { ...old, pages }
      }

      // Append vào page ĐẦU (index 0 = newest window trong infinite query cache)
      const pages = [...old.pages]
      if (pages.length === 0) return old

      pages[0] = {
        ...pages[0],
        items: [...pages[0].items, newMsg],
      }

      return { ...old, pages }
    },
  )

  // Invalidate conversations list để sidebar cập nhật lastMessageAt + re-sort
  void queryClient.invalidateQueries({ queryKey: ['conversations'] })
}

// ---------------------------------------------------------------------------
// W7 Helper: MEMBER_ADDED — append new member to conversation detail cache
// ---------------------------------------------------------------------------
function handleMemberAdded(
  queryClient: QueryClient,
  conversationId: string,
  payload: MemberAddedPayload,
): void {
  queryClient.setQueryData(
    conversationKeys.detail(conversationId),
    (old: ConversationDto | undefined) => {
      if (!old) return old
      // Dedupe
      if (old.members.some((m) => m.userId === payload.member.userId)) return old
      // Sort: OWNER first → ADMINs → MEMBERs, then by joinedAt
      const newMembers = [...old.members, payload.member].sort(sortMembers)
      return { ...old, members: newMembers }
    },
  )
  void queryClient.invalidateQueries({ queryKey: conversationKeys.lists() })
}

// ---------------------------------------------------------------------------
// W7 Helper: MEMBER_REMOVED — remove member from cache
// Idempotent with /user/queue/conv-removed (check isSelf)
// ---------------------------------------------------------------------------
function handleMemberRemoved(
  queryClient: QueryClient,
  conversationId: string,
  payload: MemberRemovedPayload,
  currentUserId: string | null,
): void {
  const isSelf = payload.userId === currentUserId
  if (!isSelf) {
    queryClient.setQueryData(
      conversationKeys.detail(conversationId),
      (old: ConversationDto | undefined) => {
        if (!old) return old
        return {
          ...old,
          members: old.members.filter((m) => m.userId !== payload.userId),
        }
      },
    )
    void queryClient.invalidateQueries({ queryKey: conversationKeys.lists() })
  }
  // isSelf: handled by /user/queue/conv-removed handler (useConvMembershipSubscription)
}

// ---------------------------------------------------------------------------
// W7 Helper: ROLE_CHANGED — update member role in cache
// ---------------------------------------------------------------------------
function handleRoleChanged(
  queryClient: QueryClient,
  conversationId: string,
  payload: RoleChangedPayload,
  currentUserId: string | null,
): void {
  queryClient.setQueryData(
    conversationKeys.detail(conversationId),
    (old: ConversationDto | undefined) => {
      if (!old) return old
      const newMembers = old.members
        .map((m) => (m.userId === payload.userId ? { ...m, role: payload.newRole } : m))
        .sort(sortMembers)
      return { ...old, members: newMembers }
    },
  )

  if (payload.userId === currentUserId) {
    const roleLabel = payload.newRole === 'ADMIN' ? 'phó nhóm' : 'thành viên'
    toast.info(`Bạn đã trở thành ${roleLabel}`)
  }
}

// ---------------------------------------------------------------------------
// W7 Helper: OWNER_TRANSFERRED — atomic 2-way role swap
// ---------------------------------------------------------------------------
function handleOwnerTransferred(
  queryClient: QueryClient,
  conversationId: string,
  payload: OwnerTransferredPayload,
  currentUserId: string | null,
): void {
  queryClient.setQueryData(
    conversationKeys.detail(conversationId),
    (old: ConversationDto | undefined) => {
      if (!old) return old
      const newMembers = old.members
        .map((m) => {
          if (m.userId === payload.newOwner.userId) return { ...m, role: 'OWNER' as const }
          if (m.userId === payload.previousOwner.userId) {
            // autoTransferred: previous owner will be removed (MEMBER_REMOVED follows)
            // non-auto: previous owner stays as ADMIN
            if (payload.autoTransferred) return m // keep as-is, will be removed by MEMBER_REMOVED
            return { ...m, role: 'ADMIN' as const }
          }
          return m
        })
        .sort(sortMembers)
      return { ...old, owner: { userId: payload.newOwner.userId, username: payload.newOwner.username, fullName: payload.newOwner.fullName }, members: newMembers }
    },
  )

  if (payload.newOwner.userId === currentUserId) {
    toast.success('Bạn đã trở thành chủ nhóm')
  }
}

// ---------------------------------------------------------------------------
// W7 Helper: CONVERSATION_UPDATED — update group name/avatar
// ---------------------------------------------------------------------------
function handleConversationUpdated(
  queryClient: QueryClient,
  conversationId: string,
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  _payload: ConversationUpdatedPayload,
): void {
  void queryClient.invalidateQueries({ queryKey: conversationKeys.detail(conversationId) })
  void queryClient.invalidateQueries({ queryKey: conversationKeys.lists() })
}

// ---------------------------------------------------------------------------
// W7 Helper: GROUP_DELETED — remove conv from cache, navigate away
// ---------------------------------------------------------------------------
function handleGroupDeleted(
  queryClient: QueryClient,
  conversationId: string,
  _payload: GroupDeletedPayload,
  navigate: (path: string) => void,
): void {
  // Đọc tên nhóm từ cache TRƯỚC khi xóa — BE không gửi name trong GROUP_DELETED event
  const cachedConv = queryClient.getQueryData<ConversationDto>(
    conversationKeys.detail(conversationId),
  )
  const groupName = cachedConv?.name ?? 'này'

  queryClient.setQueryData(
    conversationKeys.lists(),
    (old: { content?: ConversationDto[] } | undefined) => {
      if (!old) return old
      return {
        ...old,
        content: (old.content ?? []).filter((c) => c.id !== conversationId),
      }
    },
  )
  queryClient.removeQueries({ queryKey: conversationKeys.detail(conversationId) })

  if (window.location.pathname.includes(conversationId)) {
    navigate('/')
  }
  toast.warning(`Nhóm "${groupName}" đã bị xóa`)
}

// ---------------------------------------------------------------------------
// Sort helper: OWNER first → ADMIN → MEMBER, then by joinedAt ASC
// ---------------------------------------------------------------------------
function sortMembers(a: MemberDto, b: MemberDto): number {
  const roleOrder = { OWNER: 0, ADMIN: 1, MEMBER: 2 }
  const roleA = roleOrder[a.role] ?? 3
  const roleB = roleOrder[b.role] ?? 3
  if (roleA !== roleB) return roleA - roleB
  return new Date(a.joinedAt).getTime() - new Date(b.joinedAt).getTime()
}
