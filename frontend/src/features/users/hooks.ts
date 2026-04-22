import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { userKeys } from '../conversations/queryKeys'
import {
  blockUser,
  changePassword,
  getBlockedUsers,
  getCurrentUser,
  searchUsers,
  unblockUser,
  updateProfile,
  type ChangePasswordPayload,
  type UpdateProfilePayload,
} from './api'
import { useDebounce } from '@/hooks/useDebounce'
import { toast } from 'sonner'
import axios from 'axios'
import type { ApiErrorBody } from '@/types/api'
import { useAuthStore } from '@/stores/authStore'

// ---------------------------------------------------------------------------
// useUserSearch — GET /api/users/search?q=&limit=
// Debounce 300ms, chỉ fire khi query >= 2 ký tự (khớp contract min 2 chars).
// ---------------------------------------------------------------------------
export function useUserSearch(query: string, limit = 20) {
  const debouncedQuery = useDebounce(query, 300)

  return useQuery({
    queryKey: userKeys.search(debouncedQuery),
    queryFn: () => searchUsers(debouncedQuery, limit),
    enabled: debouncedQuery.trim().length >= 2,
  })
}

function handleBlockError(err: unknown): void {
  if (!axios.isAxiosError(err)) {
    toast.error('Không thể thực hiện thao tác chặn')
    return
  }
  const code = (err.response?.data as ApiErrorBody | undefined)?.error
  switch (code) {
    case 'CANNOT_BLOCK_SELF':
      toast.error('Không thể tự chặn mình')
      break
    case 'BLOCK_NOT_FOUND':
      toast.error('Chưa chặn người dùng này')
      break
    case 'USER_NOT_FOUND':
      toast.error('Không tìm thấy người dùng')
      break
    default:
      toast.error('Không thể thực hiện thao tác chặn')
      break
  }
}

export function useBlockUser(userId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => blockUser(userId),
    onSuccess: () => {
      toast.success('Đã chặn người dùng')
      void queryClient.invalidateQueries({ queryKey: ['blocked-users'] })
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
      void queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: handleBlockError,
  })
}

export function useUnblockUser(userId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => unblockUser(userId),
    onSuccess: () => {
      toast.success('Đã bỏ chặn')
      void queryClient.invalidateQueries({ queryKey: ['blocked-users'] })
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
      void queryClient.invalidateQueries({ queryKey: ['users'] })
    },
    onError: handleBlockError,
  })
}

export function useBlockedUsers() {
  return useQuery({
    queryKey: ['blocked-users'],
    queryFn: getBlockedUsers,
  })
}

export function useCurrentUser() {
  return useQuery({
    queryKey: ['currentUser'],
    queryFn: getCurrentUser,
    staleTime: 5 * 60 * 1000,
  })
}

export function useUpdateProfile() {
  const queryClient = useQueryClient()
  const updateUser = useAuthStore((s) => s.updateUser)

  return useMutation({
    mutationFn: (data: UpdateProfilePayload) => updateProfile(data),
    onSuccess: (user) => {
      updateUser(user)
      void queryClient.invalidateQueries({ queryKey: ['currentUser'] })
      void queryClient.invalidateQueries({ queryKey: ['conversations'] })
      toast.success('Đã cập nhật thông tin')
    },
    onError: (err: unknown) => {
      if (axios.isAxiosError(err)) {
        const message = (err.response?.data as ApiErrorBody | undefined)?.message
        toast.error(message ? `Cập nhật thất bại: ${message}` : 'Cập nhật thất bại')
        return
      }
      toast.error('Cập nhật thất bại')
    },
  })
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (data: ChangePasswordPayload) => changePassword(data),
  })
}
