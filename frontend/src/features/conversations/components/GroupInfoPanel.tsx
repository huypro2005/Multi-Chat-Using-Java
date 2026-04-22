// ---------------------------------------------------------------------------
// GroupInfoPanel — side panel thông tin nhóm với member management (W7-D3)
//
// Section 1: Group info (avatar, name, member count, edit button)
// Section 2: Members list (sorted OWNER→ADMIN→MEMBER) with context menu
// Section 3: Actions (add members, leave, delete)
// ---------------------------------------------------------------------------

import { useState } from 'react'
import { MoreHorizontal, UserPlus, X } from 'lucide-react'
import { toast } from 'sonner'
import { useQuery } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { conversationKeys } from '../queryKeys'
import { getConversation } from '../api'
import {
  useAddMembers,
  useRemoveMember,
  useLeaveGroup,
  useChangeRole,
  useTransferOwner,
} from '../hooks/useGroupActions'
import EditGroupInfoDialog from './EditGroupInfoDialog'
import { useAuthStore } from '@/stores/authStore'
import { MemberRole } from '@/types/conversation'
import type { ConversationDto, MemberDto } from '@/types/conversation'
import UserAvatar from '@/components/UserAvatar'
import { useUserSearch } from '@/features/users/hooks'
import { MemberSkeleton } from '@/features/common/components/Skeleton'

/**
 * ADR-021 (W7-D4-fix): avatarUrl từ BE là public URL (/api/files/{id}/public).
 * GroupAvatarDisplay dùng native <img> — không cần useProtectedObjectUrl.
 */
const DEFAULT_GROUP_AVATAR = '/api/files/00000000-0000-0000-0000-000000000002/public'

interface Props {
  conversationId: string
  open: boolean
  onClose: () => void
}

// ---------------------------------------------------------------------------
// ConfirmDialog — generic confirm modal
// ---------------------------------------------------------------------------
interface ConfirmDialogProps {
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  confirmVariant?: 'danger' | 'primary'
  onConfirm: () => void
  onCancel: () => void
}

function ConfirmDialog({
  open,
  title,
  message,
  confirmLabel = 'Xác nhận',
  confirmVariant = 'primary',
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  if (!open) return null
  return (
    <>
      <div
        className="fixed inset-0 bg-black/50 z-[60]"
        onClick={onCancel}
        aria-hidden="true"
      />
      <div className="fixed inset-0 z-[60] flex items-center justify-center pointer-events-none">
        <div
          role="dialog"
          aria-modal="true"
          className="bg-white rounded-xl shadow-xl w-full max-w-sm mx-4 p-6 pointer-events-auto"
          onClick={(e) => e.stopPropagation()}
        >
          <h4 className="text-base font-semibold text-gray-900 mb-2">{title}</h4>
          <p className="text-sm text-gray-600 mb-5">{message}</p>
          <div className="flex justify-end gap-3">
            <button
              type="button"
              onClick={onCancel}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300
                rounded-lg hover:bg-gray-50 transition-colors"
            >
              Hủy
            </button>
            <button
              type="button"
              onClick={onConfirm}
              className={`px-4 py-2 text-sm font-medium text-white rounded-lg transition-colors
                ${confirmVariant === 'danger'
                  ? 'bg-red-600 hover:bg-red-700'
                  : 'bg-indigo-600 hover:bg-indigo-700'}`}
            >
              {confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// AddMembersDialog — search and add members
// ---------------------------------------------------------------------------
interface AddMembersDialogProps {
  open: boolean
  conversationId: string
  existingMemberIds: string[]
  onClose: () => void
}

function AddMembersDialog({ open, conversationId, existingMemberIds, onClose }: AddMembersDialogProps) {
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedUsers, setSelectedUsers] = useState<{ id: string; fullName: string; username: string; avatarUrl: string | null }[]>([])
  const { data: searchResults } = useUserSearch(searchQuery)
  const { mutateAsync: addMembers, isPending } = useAddMembers(conversationId)

  function handleClose() {
    setSearchQuery('')
    setSelectedUsers([])
    onClose()
  }

  function handleToggle(user: { id: string; fullName: string; username: string; avatarUrl: string | null }) {
    setSelectedUsers((prev) => {
      const exists = prev.find((u) => u.id === user.id)
      if (exists) return prev.filter((u) => u.id !== user.id)
      return [...prev, user]
    })
  }

  async function handleSubmit() {
    if (selectedUsers.length === 0) return
    try {
      await addMembers({ userIds: selectedUsers.map((u) => u.id) })
      handleClose()
    } catch {
      // Handled in hook
    }
  }

  if (!open) return null

  const filteredResults = searchResults?.filter((u) => !existingMemberIds.includes(u.id))

  return (
    <>
      <div className="fixed inset-0 bg-black/50 z-[60]" onClick={handleClose} aria-hidden="true" />
      <div className="fixed inset-0 z-[60] flex items-center justify-center pointer-events-none">
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Thêm thành viên"
          className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4 pointer-events-auto
            max-h-[80vh] flex flex-col"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200 flex-shrink-0">
            <h3 className="text-base font-semibold text-gray-900">Thêm thành viên</h3>
            <button type="button" onClick={handleClose} aria-label="Đóng"
              className="w-8 h-8 rounded-lg flex items-center justify-center
                text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors">
              <X size={18} />
            </button>
          </div>

          <div className="flex-1 overflow-y-auto px-6 py-4 space-y-4">
            <input
              autoFocus
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Tìm username hoặc tên..."
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm
                focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
            />

            {filteredResults && filteredResults.length > 0 && (
              <ul className="border border-gray-200 rounded-lg divide-y divide-gray-100 max-h-48 overflow-y-auto">
                {filteredResults.map((user) => {
                  const isSelected = selectedUsers.some((u) => u.id === user.id)
                  return (
                    <li key={user.id}>
                      <button
                        type="button"
                        onClick={() => handleToggle(user)}
                        className="w-full flex items-center gap-3 px-3 py-2.5 text-left hover:bg-gray-50 transition-colors"
                      >
                        <UserAvatar user={user} size={32} />
                        <div className="flex-1 min-w-0">
                          <p className="text-sm font-medium text-gray-900 truncate">{user.fullName}</p>
                          <p className="text-xs text-gray-500">@{user.username}</p>
                        </div>
                        <div className={`w-5 h-5 rounded-full border-2 flex-shrink-0 flex items-center justify-center
                          ${isSelected ? 'bg-indigo-600 border-indigo-600' : 'border-gray-300'}`}>
                          {isSelected && (
                            <svg viewBox="0 0 12 12" fill="none" className="w-3 h-3 text-white" aria-hidden="true">
                              <path d="M2 6l3 3 5-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                            </svg>
                          )}
                        </div>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}

            {selectedUsers.length > 0 && (
              <div className="flex flex-wrap gap-2">
                {selectedUsers.map((user) => (
                  <span key={user.id}
                    className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full bg-indigo-50 text-indigo-700 text-sm">
                    <span className="max-w-[100px] truncate">{user.fullName}</span>
                    <button type="button" onClick={() => handleToggle(user)} aria-label={`Xóa ${user.fullName}`}
                      className="text-indigo-400 hover:text-indigo-700">
                      <X size={14} />
                    </button>
                  </span>
                ))}
              </div>
            )}
          </div>

          <div className="px-6 py-4 border-t border-gray-200 flex justify-end gap-3 flex-shrink-0">
            <button type="button" onClick={handleClose}
              className="px-4 py-2 text-sm font-medium text-gray-700 bg-white border border-gray-300 rounded-lg hover:bg-gray-50 transition-colors">
              Hủy
            </button>
            <button
              type="button"
              onClick={() => void handleSubmit()}
              disabled={selectedUsers.length === 0 || isPending}
              className="px-4 py-2 text-sm font-medium text-white bg-indigo-600 rounded-lg
                hover:bg-indigo-700 transition-colors disabled:bg-indigo-400 disabled:cursor-not-allowed"
            >
              Thêm ({selectedUsers.length})
            </button>
          </div>
        </div>
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// MemberContextMenu — context menu cho từng member (authorization matrix)
// ---------------------------------------------------------------------------
interface MemberContextMenuProps {
  open: boolean
  currentRole: MemberDto['role']
  targetMember: MemberDto
  currentUserId: string
  conversationId: string
  onClose: () => void
  onActionDone: () => void
}

function MemberContextMenu({
  open,
  currentRole,
  targetMember,
  currentUserId,
  conversationId,
  onClose,
  onActionDone,
}: MemberContextMenuProps) {
  const { mutateAsync: removeMember, isPending: isRemoving } = useRemoveMember(conversationId)
  const { mutateAsync: changeRole, isPending: isChangingRole } = useChangeRole(conversationId)
  const { mutateAsync: transferOwner, isPending: isTransferring } = useTransferOwner(conversationId)

  const [confirm, setConfirm] = useState<{
    title: string
    message: string
    action: () => void
    variant?: 'danger' | 'primary'
    label?: string
  } | null>(null)

  if (!open) return null

  const isTarget = targetMember.userId === currentUserId
  if (isTarget) return null

  // Build actions based on authorization matrix
  const actions: { label: string; action: () => void; variant?: 'danger' | 'warning' }[] = []

  if (currentRole === MemberRole.OWNER) {
    if (targetMember.role === MemberRole.MEMBER) {
      actions.push({
        label: '⭐ Thăng làm phó nhóm',
        action: () => {
          setConfirm({
            title: 'Thăng làm phó nhóm?',
            message: `${targetMember.fullName} sẽ có thể quản lý thành viên nhóm.`,
            label: 'Xác nhận',
            action: async () => {
              try {
                await changeRole({ userId: targetMember.userId, role: 'ADMIN' })
                onActionDone()
              } catch { /* handled in hook */ }
            },
          })
        },
      })
    } else if (targetMember.role === MemberRole.ADMIN) {
      actions.push({
        label: 'Giáng xuống thành viên',
        action: () => {
          setConfirm({
            title: 'Giáng xuống thành viên?',
            message: `${targetMember.fullName} sẽ không còn quyền quản lý nhóm.`,
            label: 'Xác nhận',
            action: async () => {
              try {
                await changeRole({ userId: targetMember.userId, role: 'MEMBER' })
                onActionDone()
              } catch { /* handled in hook */ }
            },
          })
        },
      })
      actions.push({
        label: '👑 Chuyển quyền chủ nhóm',
        action: () => {
          setConfirm({
            title: 'Chuyển quyền chủ nhóm?',
            message: `Chuyển quyền chủ nhóm cho ${targetMember.fullName}? Bạn sẽ trở thành phó nhóm.`,
            label: 'Chuyển quyền',
            action: async () => {
              try {
                await transferOwner({ targetUserId: targetMember.userId })
                onActionDone()
              } catch { /* handled in hook */ }
            },
          })
        },
      })
    }

    // OWNER can remove ADMIN/MEMBER
    if (targetMember.role !== MemberRole.OWNER) {
      actions.push({
        label: 'Xóa khỏi nhóm',
        variant: 'danger',
        action: () => {
          setConfirm({
            title: `Xóa ${targetMember.fullName} khỏi nhóm?`,
            message: 'Họ sẽ không thể xem và gửi tin nhắn trong nhóm này nữa.',
            label: 'Xóa khỏi nhóm',
            variant: 'danger',
            action: async () => {
              try {
                await removeMember(targetMember.userId)
                onActionDone()
              } catch { /* handled in hook */ }
            },
          })
        },
      })
    }
  } else if (currentRole === MemberRole.ADMIN) {
    // ADMIN can only remove MEMBER
    if (targetMember.role === MemberRole.MEMBER) {
      actions.push({
        label: 'Xóa khỏi nhóm',
        variant: 'danger',
        action: () => {
          setConfirm({
            title: `Xóa ${targetMember.fullName} khỏi nhóm?`,
            message: 'Họ sẽ không thể xem và gửi tin nhắn trong nhóm này nữa.',
            label: 'Xóa khỏi nhóm',
            variant: 'danger',
            action: async () => {
              try {
                await removeMember(targetMember.userId)
                onActionDone()
              } catch { /* handled in hook */ }
            },
          })
        },
      })
    }
  }

  if (actions.length === 0) return null

  const isPending = isRemoving || isChangingRole || isTransferring

  return (
    <>
      {/* Menu overlay */}
      <div className="fixed inset-0 z-[55]" onClick={onClose} aria-hidden="true" />
      <div
        className="absolute right-0 top-full mt-1 z-[56] bg-white rounded-lg shadow-lg border border-gray-200
          min-w-[180px] py-1 pointer-events-auto"
        role="menu"
      >
        {actions.map((action, idx) => (
          <button
            key={idx}
            type="button"
            role="menuitem"
            disabled={isPending}
            onClick={() => {
              action.action()
            }}
            className={`w-full text-left px-4 py-2 text-sm hover:bg-gray-50 transition-colors
              disabled:opacity-50 disabled:cursor-not-allowed
              ${action.variant === 'danger' ? 'text-red-600' : 'text-gray-700'}`}
          >
            {action.label}
          </button>
        ))}
      </div>

      {/* Confirm dialog */}
      {confirm && (
        <ConfirmDialog
          open
          title={confirm.title}
          message={confirm.message}
          confirmLabel={confirm.label}
          confirmVariant={confirm.variant === 'danger' ? 'danger' : 'primary'}
          onConfirm={() => {
            void confirm.action()
            setConfirm(null)
            onClose()
          }}
          onCancel={() => setConfirm(null)}
        />
      )}
    </>
  )
}

// ---------------------------------------------------------------------------
// GroupAvatarDisplay
// ---------------------------------------------------------------------------
function GroupAvatarDisplay({ avatarUrl, name }: { avatarUrl: string | null; name: string }) {
  const src = avatarUrl ?? DEFAULT_GROUP_AVATAR

  return (
    <div className="relative w-16 h-16">
      {/* Initial letter fallback (rendered underneath img) */}
      <div
        className="absolute inset-0 w-16 h-16 rounded-full bg-indigo-100 flex items-center
          justify-center text-indigo-700 text-2xl font-semibold select-none"
        aria-hidden="true"
      >
        {name.charAt(0).toUpperCase()}
      </div>
      <img
        src={src}
        alt={name}
        className="absolute inset-0 w-16 h-16 rounded-full object-cover"
        onError={(e) => {
          e.currentTarget.style.display = 'none'
        }}
      />
    </div>
  )
}

// ---------------------------------------------------------------------------
// RoleBadge
// ---------------------------------------------------------------------------
function RoleBadge({ role }: { role: MemberDto['role'] }) {
  if (role === MemberRole.OWNER) {
    return (
      <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-full
        bg-amber-50 text-amber-700 text-xs font-medium">
        👑 Chủ nhóm
      </span>
    )
  }
  if (role === MemberRole.ADMIN) {
    return (
      <span className="inline-flex items-center gap-0.5 px-1.5 py-0.5 rounded-full
        bg-blue-50 text-blue-700 text-xs font-medium">
        ⭐ Phó nhóm
      </span>
    )
  }
  return null
}

// ---------------------------------------------------------------------------
// GroupInfoPanel — main component
// ---------------------------------------------------------------------------
export function GroupInfoPanel({ conversationId, open, onClose }: Props) {
  const currentUserId = useAuthStore((s) => s.user?.id ?? '')
  const navigate = useNavigate()

  const { data: conv, isLoading } = useQuery<ConversationDto>({
    queryKey: conversationKeys.detail(conversationId),
    queryFn: () => getConversation(conversationId),
    enabled: open && !!conversationId,
  })

  const { mutateAsync: leaveGroupMutate } = useLeaveGroup(conversationId)
  const { mutateAsync: deleteGroupMutate } = useDeleteGroupWrapper(conversationId)

  const [openMenuUserId, setOpenMenuUserId] = useState<string | null>(null)
  const [showEditDialog, setShowEditDialog] = useState(false)
  const [showAddMembers, setShowAddMembers] = useState(false)
  const [showLeaveConfirm, setShowLeaveConfirm] = useState(false)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [showDeleteFinalConfirm, setShowDeleteFinalConfirm] = useState(false)

  const currentMember = conv?.members.find((m) => m.userId === currentUserId)
  const currentRole = currentMember?.role ?? MemberRole.MEMBER
  const canManage = currentRole === MemberRole.OWNER || currentRole === MemberRole.ADMIN
  const isOwner = currentRole === MemberRole.OWNER

  // Find next owner candidate if current user is owner and wants to leave
  const nextOwnerCandidate = isOwner
    ? (conv?.members.find((m) => m.role === MemberRole.ADMIN && m.userId !== currentUserId) ??
       conv?.members.find((m) => m.role === MemberRole.MEMBER && m.userId !== currentUserId))
    : null

  return (
    <div
      className={`
        fixed inset-y-0 right-0 w-80 bg-white shadow-xl z-40
        transform transition-transform duration-200 flex flex-col
        ${open ? 'translate-x-0' : 'translate-x-full'}
        md:relative md:inset-auto md:shadow-none md:border-l md:border-gray-200
        md:translate-x-0 md:z-auto
        ${!open ? 'md:hidden' : ''}
      `}
    >
      {/* Header */}
      <div className="p-4 border-b border-gray-200 flex items-center justify-between flex-shrink-0">
        <h3 className="font-semibold text-gray-900">Thông tin nhóm</h3>
        <button
          onClick={onClose}
          aria-label="Đóng"
          className="p-1 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100 transition-colors"
        >
          <X size={18} />
        </button>
      </div>

      {/* Scrollable content */}
      <div className="flex-1 overflow-y-auto">
        {isLoading && (
          <div className="p-6">
            <div className="flex flex-col items-center gap-3 mb-4 animate-pulse">
              <div className="w-16 h-16 rounded-full bg-gray-200" />
              <div className="h-4 bg-gray-200 rounded w-32" />
              <div className="h-3 bg-gray-200 rounded w-20" />
            </div>
            <div className="space-y-1">
              <MemberSkeleton />
              <MemberSkeleton />
              <MemberSkeleton />
            </div>
          </div>
        )}

        {conv && (
          <>
            {/* Section 1: Group info */}
            <div className="flex flex-col items-center gap-2 p-5 border-b border-gray-100">
              <GroupAvatarDisplay avatarUrl={conv.avatarUrl} name={conv.name ?? 'Nhóm'} />
              <p className="text-base font-semibold text-gray-900 text-center">
                {conv.name ?? 'Nhóm không tên'}
              </p>
              <p className="text-sm text-gray-500">{conv.members.length} thành viên</p>
              {canManage && (
                <button
                  type="button"
                  onClick={() => setShowEditDialog(true)}
                  className="mt-1 text-sm text-indigo-600 hover:underline"
                >
                  Chỉnh sửa thông tin nhóm
                </button>
              )}
            </div>

            {/* Section 2: Members list */}
            <div className="p-4">
              <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-3">
                Thành viên ({conv.members.length})
              </p>
              <ul className="space-y-2">
                {conv.members.map((member) => {
                  const isSelf = member.userId === currentUserId
                  // Show menu button only if current user has permission over this target
                  const showMenuBtn =
                    !isSelf &&
                    ((currentRole === MemberRole.OWNER && member.role !== MemberRole.OWNER) ||
                      (currentRole === MemberRole.ADMIN && member.role === MemberRole.MEMBER))

                  return (
                    <li key={member.userId} className="flex items-center gap-3 relative">
                      <UserAvatar
                        user={{ fullName: member.fullName, avatarUrl: member.avatarUrl }}
                        size={36}
                      />
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-1.5">
                          <p className="text-sm font-medium text-gray-900 truncate">
                            {member.fullName}
                          </p>
                          {isSelf && (
                            <span className="text-xs text-gray-400 flex-shrink-0">(Bạn)</span>
                          )}
                        </div>
                        <div className="flex items-center gap-1.5 mt-0.5">
                          <RoleBadge role={member.role} />
                          <span className="text-xs text-gray-400">
                            {new Date(member.joinedAt).toLocaleDateString('vi-VN')}
                          </span>
                        </div>
                      </div>

                      {showMenuBtn && (
                        <div className="relative flex-shrink-0">
                          <button
                            type="button"
                            onClick={() =>
                              setOpenMenuUserId((prev) =>
                                prev === member.userId ? null : member.userId,
                              )
                            }
                            aria-label={`Tùy chọn cho ${member.fullName}`}
                            className="p-1 rounded-lg text-gray-400 hover:bg-gray-100 hover:text-gray-600 transition-colors"
                          >
                            <MoreHorizontal size={16} />
                          </button>

                          {openMenuUserId === member.userId && (
                            <MemberContextMenu
                              open
                              currentRole={currentRole}
                              targetMember={member}
                              currentUserId={currentUserId}
                              conversationId={conversationId}
                              onClose={() => setOpenMenuUserId(null)}
                              onActionDone={() => setOpenMenuUserId(null)}
                            />
                          )}
                        </div>
                      )}
                    </li>
                  )
                })}
              </ul>
            </div>

            {/* Section 3: Actions */}
            <div className="p-4 border-t border-gray-100 space-y-2">
              {canManage && (
                <button
                  type="button"
                  onClick={() => setShowAddMembers(true)}
                  className="w-full flex items-center gap-2 px-3 py-2 text-sm text-indigo-600
                    rounded-lg hover:bg-indigo-50 transition-colors"
                >
                  <UserPlus size={16} />
                  Thêm thành viên
                </button>
              )}

              <button
                type="button"
                onClick={() => setShowLeaveConfirm(true)}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm text-gray-700
                  rounded-lg hover:bg-gray-100 transition-colors"
              >
                Rời nhóm
              </button>

              {isOwner && (
                <button
                  type="button"
                  onClick={() => setShowDeleteConfirm(true)}
                  className="w-full flex items-center gap-2 px-3 py-2 text-sm text-red-600
                    rounded-lg hover:bg-red-50 transition-colors"
                >
                  Xóa nhóm
                </button>
              )}
            </div>
          </>
        )}
      </div>

      {/* Edit dialog */}
      {conv && (
        <EditGroupInfoDialog
          conversationId={conversationId}
          open={showEditDialog}
          onClose={() => setShowEditDialog(false)}
          currentName={conv.name ?? ''}
          currentAvatarUrl={conv.avatarUrl}
        />
      )}

      {/* Add members dialog */}
      <AddMembersDialog
        open={showAddMembers}
        conversationId={conversationId}
        existingMemberIds={conv?.members.map((m) => m.userId) ?? []}
        onClose={() => setShowAddMembers(false)}
      />

      {/* Leave confirm */}
      <ConfirmDialog
        open={showLeaveConfirm}
        title="Rời khỏi nhóm?"
        message={
          isOwner && nextOwnerCandidate
            ? `Bạn là chủ nhóm. Quyền sẽ tự động chuyển cho ${nextOwnerCandidate.fullName}. Tiếp tục?`
            : `Rời khỏi nhóm "${conv?.name ?? 'này'}"?`
        }
        confirmLabel="Rời nhóm"
        onConfirm={async () => {
          setShowLeaveConfirm(false)
          try {
            await leaveGroupMutate()
          } catch { /* handled in hook */ }
        }}
        onCancel={() => setShowLeaveConfirm(false)}
      />

      {/* Delete confirm step 1 */}
      <ConfirmDialog
        open={showDeleteConfirm}
        title="Xóa nhóm?"
        message={`Bạn chắc chắn muốn xóa nhóm "${conv?.name ?? 'này'}"? Thao tác này không thể hoàn tác.`}
        confirmLabel="Tiếp tục"
        confirmVariant="danger"
        onConfirm={() => {
          setShowDeleteConfirm(false)
          setShowDeleteFinalConfirm(true)
        }}
        onCancel={() => setShowDeleteConfirm(false)}
      />

      {/* Delete confirm step 2 */}
      <ConfirmDialog
        open={showDeleteFinalConfirm}
        title="Xóa nhóm vĩnh viễn?"
        message="Nhóm và toàn bộ tin nhắn sẽ bị xóa vĩnh viễn. Không thể hoàn tác."
        confirmLabel="Xóa vĩnh viễn"
        confirmVariant="danger"
        onConfirm={async () => {
          setShowDeleteFinalConfirm(false)
          try {
            await deleteGroupMutate()
            navigate('/')
            onClose()
          } catch {
            toast.error('Không thể xóa nhóm')
          }
        }}
        onCancel={() => setShowDeleteFinalConfirm(false)}
      />
    </div>
  )
}

// ---------------------------------------------------------------------------
// useDeleteGroupWrapper — thin wrapper around deleteGroup API + cache cleanup
// ---------------------------------------------------------------------------
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { deleteGroup } from '../api'

function useDeleteGroupWrapper(convId: string) {
  const queryClient = useQueryClient()
  return useMutation<void, unknown, void>({
    mutationFn: () => deleteGroup(convId),
    onSuccess: () => {
      queryClient.setQueryData(
        conversationKeys.lists(),
        (old: { content?: unknown[] } | undefined) => {
          if (!old) return old
          return {
            ...old,
            content: (old.content ?? []).filter((c: unknown) => {
              const conv = c as { id?: string }
              return conv.id !== convId
            }),
          }
        },
      )
      queryClient.removeQueries({ queryKey: conversationKeys.detail(convId) })
    },
  })
}
