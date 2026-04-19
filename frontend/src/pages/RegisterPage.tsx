import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Eye, EyeOff, MessageSquare } from 'lucide-react'
import { registerSchema, type RegisterFormData } from '@/features/auth/schemas/registerSchema'
import { registerApi } from '@/features/auth/api'
import { handleAuthError } from '@/features/auth/utils/handleAuthError'
import { useAuthStore } from '@/stores/authStore'
import { ToastContainer } from '@/components/Toast'
import { useToast } from '@/hooks/useToast'

// ---------------------------------------------------------------------------
// Reusable input className builder
// ---------------------------------------------------------------------------
function inputClass(hasError: boolean, withPaddingRight = false): string {
  return [
    'w-full px-3 py-2.5 rounded-lg border text-sm text-gray-900 placeholder-gray-400',
    'focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent',
    'disabled:bg-gray-50 disabled:text-gray-400 disabled:cursor-not-allowed',
    'transition-colors',
    withPaddingRight ? 'pr-10' : '',
    hasError ? 'border-red-500' : 'border-gray-300',
  ]
    .filter(Boolean)
    .join(' ')
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------
export default function RegisterPage() {
  const [showPassword, setShowPassword] = useState(false)
  const [showConfirmPassword, setShowConfirmPassword] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const { toasts, addToast, removeToast } = useToast()

  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<RegisterFormData>({
    resolver: zodResolver(registerSchema),
    mode: 'onTouched',
  })

  const onSubmit = async (data: RegisterFormData) => {
    setIsLoading(true)
    // Bỏ confirmPassword — BE không expect field này
    const payload = {
      email: data.email,
      username: data.username,
      password: data.password,
      fullName: data.fullName,
    }
    try {
      const response = await registerApi(payload)
      setAuth(response)
      addToast('Đăng ký thành công! Chào mừng bạn.', 'success')
      navigate('/')
    } catch (error) {
      handleAuthError(error, {
        setFormError: (field, msg) =>
          setError(field as keyof RegisterFormData, { message: msg }),
        showToast: (msg, type) => addToast(msg, type ?? 'error'),
      })
    } finally {
      setIsLoading(false)
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4 py-8">
      <div className="w-full max-w-md bg-white rounded-xl shadow-sm border border-gray-200 p-8">
        {/* Logo / Title */}
        <div className="flex flex-col items-center mb-8">
          <div className="flex items-center gap-2 mb-2">
            <MessageSquare className="text-indigo-600" size={28} />
            <span className="text-2xl font-bold text-gray-900">ChatApp</span>
          </div>
          <p className="text-sm text-gray-500">Tạo tài khoản mới</p>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-5">
          {/* Full Name */}
          <div className="flex flex-col gap-1.5">
            <label htmlFor="fullName" className="text-sm font-medium text-gray-700">
              Họ và tên
            </label>
            <input
              id="fullName"
              type="text"
              autoComplete="name"
              disabled={isLoading}
              placeholder="Nguyễn Văn A"
              {...register('fullName')}
              className={inputClass(!!errors.fullName)}
            />
            {errors.fullName && (
              <p className="text-red-500 text-sm" role="alert">
                {errors.fullName.message}
              </p>
            )}
          </div>

          {/* Email */}
          <div className="flex flex-col gap-1.5">
            <label htmlFor="email" className="text-sm font-medium text-gray-700">
              Email
            </label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              disabled={isLoading}
              placeholder="example@email.com"
              {...register('email')}
              className={inputClass(!!errors.email)}
            />
            {errors.email && (
              <p className="text-red-500 text-sm" role="alert">
                {errors.email.message}
              </p>
            )}
          </div>

          {/* Username */}
          <div className="flex flex-col gap-1.5">
            <label htmlFor="username" className="text-sm font-medium text-gray-700">
              Tên đăng nhập
            </label>
            <input
              id="username"
              type="text"
              autoComplete="username"
              disabled={isLoading}
              placeholder="ten_dang_nhap"
              {...register('username')}
              className={inputClass(!!errors.username)}
            />
            {errors.username && (
              <p className="text-red-500 text-sm" role="alert">
                {errors.username.message}
              </p>
            )}
          </div>

          {/* Password */}
          <div className="flex flex-col gap-1.5">
            <label htmlFor="password" className="text-sm font-medium text-gray-700">
              Mật khẩu
            </label>
            <div className="relative">
              <input
                id="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="new-password"
                disabled={isLoading}
                placeholder="Ít nhất 8 ký tự, 1 chữ hoa, 1 chữ số"
                {...register('password')}
                className={inputClass(!!errors.password, true)}
              />
              <button
                type="button"
                onClick={() => setShowPassword((prev) => !prev)}
                disabled={isLoading}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600
                  disabled:cursor-not-allowed transition-colors"
                aria-label={showPassword ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
              >
                {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
              </button>
            </div>
            {errors.password && (
              <p className="text-red-500 text-sm" role="alert">
                {errors.password.message}
              </p>
            )}
          </div>

          {/* Confirm Password */}
          <div className="flex flex-col gap-1.5">
            <label htmlFor="confirmPassword" className="text-sm font-medium text-gray-700">
              Xác nhận mật khẩu
            </label>
            <div className="relative">
              <input
                id="confirmPassword"
                type={showConfirmPassword ? 'text' : 'password'}
                autoComplete="new-password"
                disabled={isLoading}
                placeholder="Nhập lại mật khẩu"
                {...register('confirmPassword')}
                className={inputClass(!!errors.confirmPassword, true)}
              />
              <button
                type="button"
                onClick={() => setShowConfirmPassword((prev) => !prev)}
                disabled={isLoading}
                className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600
                  disabled:cursor-not-allowed transition-colors"
                aria-label={showConfirmPassword ? 'Ẩn mật khẩu xác nhận' : 'Hiện mật khẩu xác nhận'}
              >
                {showConfirmPassword ? <EyeOff size={18} /> : <Eye size={18} />}
              </button>
            </div>
            {errors.confirmPassword && (
              <p className="text-red-500 text-sm" role="alert">
                {errors.confirmPassword.message}
              </p>
            )}
          </div>

          {/* Submit */}
          <button
            type="submit"
            disabled={isLoading}
            className="w-full py-2.5 px-4 bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-400
              text-white font-medium rounded-lg text-sm
              focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2
              disabled:cursor-not-allowed transition-colors
              flex items-center justify-center gap-2"
          >
            {isLoading ? (
              <>
                <svg
                  className="animate-spin h-4 w-4 text-white"
                  xmlns="http://www.w3.org/2000/svg"
                  fill="none"
                  viewBox="0 0 24 24"
                  aria-hidden="true"
                >
                  <circle
                    className="opacity-25"
                    cx="12"
                    cy="12"
                    r="10"
                    stroke="currentColor"
                    strokeWidth="4"
                  />
                  <path
                    className="opacity-75"
                    fill="currentColor"
                    d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"
                  />
                </svg>
                Đang tạo tài khoản...
              </>
            ) : (
              'Tạo tài khoản'
            )}
          </button>
        </form>

        {/* Login link */}
        <p className="mt-6 text-center text-sm text-gray-500">
          Đã có tài khoản?{' '}
          <Link
            to="/login"
            className="text-indigo-600 font-medium hover:text-indigo-700 transition-colors"
          >
            Đăng nhập
          </Link>
        </p>
      </div>

      {/* Toast container */}
      <ToastContainer toasts={toasts} onRemove={removeToast} />
    </div>
  )
}
