import type { ApiError } from '@/types/auth'

interface ErrorHandlers {
  setFormError?: (field: string, message: string) => void
  showToast: (message: string, type?: 'error' | 'info') => void
}

export function handleAuthError(error: unknown, handlers: ErrorHandlers): void {
  const { setFormError, showToast } = handlers

  // Extract API error từ axios error shape
  const axiosError = error as { response?: { data?: ApiError } }
  const apiError = axiosError?.response?.data

  if (!apiError?.error) {
    showToast('Có lỗi xảy ra, vui lòng thử lại', 'error')
    return
  }

  const { error: code, message, details } = apiError

  switch (code) {
    case 'AUTH_INVALID_CREDENTIALS':
      setFormError?.('username', 'Tên đăng nhập hoặc mật khẩu không đúng')
      break

    case 'AUTH_ACCOUNT_LOCKED':
    case 'AUTH_ACCOUNT_DISABLED':
      showToast('Tài khoản bị vô hiệu hóa, vui lòng liên hệ admin', 'error')
      break

    case 'RATE_LIMITED': {
      const retry = details?.retryAfterSeconds
      showToast(`Thử quá nhiều lần, vui lòng đợi ${retry ?? 900} giây`, 'error')
      break
    }

    case 'AUTH_EMAIL_TAKEN':
      setFormError?.('email', 'Email này đã được sử dụng')
      break

    case 'AUTH_USERNAME_TAKEN':
      setFormError?.('username', 'Username này đã tồn tại')
      break

    case 'VALIDATION_FAILED':
      if (details?.fields) {
        Object.entries(details.fields).forEach(([field, msg]) => {
          setFormError?.(field, msg)
        })
      } else {
        showToast(message || 'Dữ liệu không hợp lệ', 'error')
      }
      break

    default:
      showToast(message || 'Có lỗi xảy ra, vui lòng thử lại', 'error')
  }
}
