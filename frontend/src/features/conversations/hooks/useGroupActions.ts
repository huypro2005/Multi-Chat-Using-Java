// ---------------------------------------------------------------------------
// useGroupActions — 5 mutation hooks cho group member management (W7-D3)
//
// Pattern: mỗi hook nhận convId, expose useMutation.
// onSuccess: invalidate detail cache + toast (xem từng hook).
// onError: toast với error code từ contract.
// ---------------------------------------------------------------------------

import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import axios from 'axios'
import { conversationKeys } from '../queryKeys'
import {
  addMembers,
  removeMember,
  leaveGroup,
  changeMemberRole,
  transferOwner,
} from '../api'
import type {
  AddMembersRequest,
  AddMembersResponse,
  ChangeRoleRequest,
  TransferOwnerRequest,
} from '@/types/conversation'
import type { ApiErrorBody } from '@/types/api'

// ---------------------------------------------------------------------------
// Error handler helper — map error codes thành toast messages
// ---------------------------------------------------------------------------
function handleGroupError(err: unknown): void {
  if (!axios.isAxiosError(err)) {
    toast.error('Đã có lỗi xảy ra')
    return
  }
  const code = (err.response?.data as ApiErrorBody | undefined)?.error
  const details = (err.response?.data as ApiErrorBody | undefined)?.details as
    | Record<string, unknown>
    | undefined

  switch (code) {
    case 'MEMBER_ALREADY_EXISTS':
      toast.error('Đã là thành viên')
      break
    case 'MEMBER_LIMIT_EXCEEDED':
      toast.error(
        `Vượt giới hạn (${details?.currentCount ?? '?'}/${details?.limit ?? 50})`,
      )
      break
    case 'USER_NOT_FOUND':
      toast.error('Không tìm thấy người dùng')
      break
    case 'USER_BLOCKED':
      toast.error('Không thể thêm (bị chặn)')
      break
    case 'MEMBER_NOT_FOUND':
      toast.error('Không phải thành viên')
      break
    case 'CANNOT_KICK_SELF':
      toast.error('Dùng Rời nhóm để thoát')
      break
    case 'CANNOT_LEAVE_EMPTY_GROUP':
      toast.error('Bạn là thành viên duy nhất. Xóa nhóm thay.')
      break
    case 'CANNOT_CHANGE_OWNER_ROLE':
      toast.error('Không thể đổi role của chủ nhóm')
      break
    case 'INVALID_ROLE':
      toast.error('Role không hợp lệ')
      break
    case 'CANNOT_TRANSFER_TO_SELF':
      toast.error('Không thể chuyển quyền cho chính mình')
      break
    case 'INSUFFICIENT_PERMISSION':
      toast.error('Bạn không có quyền thực hiện thao tác này')
      break
    case 'NOT_GROUP':
      toast.error('Thao tác này chỉ dành cho nhóm')
      break
    default:
      toast.error('Đã có lỗi xảy ra')
  }
}

// ---------------------------------------------------------------------------
// useAddMembers — POST /api/conversations/{id}/members
// ---------------------------------------------------------------------------
export function useAddMembers(convId: string) {
  const queryClient = useQueryClient()
  return useMutation<AddMembersResponse, unknown, AddMembersRequest>({
    mutationFn: (data) => addMembers(convId, data),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: conversationKeys.detail(convId) })
      void queryClient.invalidateQueries({ queryKey: conversationKeys.lists() })
      if (result.added.length > 0) {
        toast.success(`Đã thêm ${result.added.length} người`)
      }
      // Handle skipped
      result.skipped.forEach((skip) => {
        switch (skip.reason) {
          case 'ALREADY_MEMBER':
            toast.warning('Một số người đã là thành viên')
            break
          case 'BLOCKED':
            toast.warning('Một số người không thể thêm (bị chặn)')
            break
          case 'USER_NOT_FOUND':
            toast.warning('Không tìm thấy một số người dùng')
            break
        }
      })
    },
    onError: handleGroupError,
  })
}

// ---------------------------------------------------------------------------
// useRemoveMember — DELETE /api/conversations/{id}/members/{userId}
// ---------------------------------------------------------------------------
export function useRemoveMember(convId: string) {
  const queryClient = useQueryClient()
  return useMutation<void, unknown, string>({
    mutationFn: (userId) => removeMember(convId, userId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: conversationKeys.detail(convId) })
    },
    onError: handleGroupError,
  })
}

// ---------------------------------------------------------------------------
// useLeaveGroup — POST /api/conversations/{id}/leave
// onSuccess: remove conv from sidebar cache + navigate('/')
// ---------------------------------------------------------------------------
export function useLeaveGroup(convId: string) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  return useMutation<void, unknown, void>({
    mutationFn: () => leaveGroup(convId),
    onSuccess: () => {
      // Remove conv from all cache entries
      queryClient.setQueryData(
        conversationKeys.lists(),
        (old: { content?: unknown[]; page?: number; size?: number; totalElements?: number; totalPages?: number } | undefined) => {
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
      // Remove detail cache
      queryClient.removeQueries({ queryKey: conversationKeys.detail(convId) })
      navigate('/')
    },
    onError: handleGroupError,
  })
}

// ---------------------------------------------------------------------------
// useChangeRole — PATCH /api/conversations/{id}/members/{userId}/role
// ---------------------------------------------------------------------------
export function useChangeRole(convId: string) {
  const queryClient = useQueryClient()
  return useMutation<void, unknown, { userId: string; role: ChangeRoleRequest['role'] }>({
    mutationFn: ({ userId, role }) => changeMemberRole(convId, userId, { role }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: conversationKeys.detail(convId) })
    },
    onError: handleGroupError,
  })
}

// ---------------------------------------------------------------------------
// useTransferOwner — POST /api/conversations/{id}/transfer-owner
// onSuccess: toast.success
// ---------------------------------------------------------------------------
export function useTransferOwner(convId: string) {
  const queryClient = useQueryClient()
  return useMutation<void, unknown, TransferOwnerRequest>({
    mutationFn: (data) => transferOwner(convId, data),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: conversationKeys.detail(convId) })
      toast.success('Đã chuyển quyền chủ nhóm')
    },
    onError: handleGroupError,
  })
}
