import { useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Eye, EyeOff, MessageSquare } from 'lucide-react'
import { loginSchema, type LoginFormData } from '@/features/auth/schemas/loginSchema'
import { loginApi } from '@/features/auth/api'
import { handleAuthError } from '@/features/auth/utils/handleAuthError'
import { useAuthStore } from '@/stores/authStore'
import { useAuth } from '@/hooks/useAuth'
import { ToastContainer } from '@/components/Toast'
import { useToast } from '@/hooks/useToast'
import GoogleLoginButton from '@/features/auth/components/GoogleLoginButton'

export default function LoginPage() {
  const [showPassword, setShowPassword] = useState(false)
  const [isLoading, setIsLoading] = useState(false)
  const { toasts, addToast, removeToast } = useToast()

  const navigate = useNavigate()
  const setAuth = useAuthStore((s) => s.setAuth)
  const { isAuthenticated } = useAuth()

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
    mode: 'onTouched',
  })

  const onSubmit = async (data: LoginFormData) => {
    setIsLoading(true)
    try {
      const response = await loginApi(data)
      setAuth(response)
      addToast('Đăng nhập thành công!', 'success')
      navigate('/')
    } catch (error) {
      handleAuthError(error, {
        setFormError: (field, msg) =>
          setError(field as keyof LoginFormData, { message: msg }),
        showToast: (msg, type) => addToast(msg, type ?? 'error'),
      })
    } finally {
      setIsLoading(false)
    }
  }

  // App đã chạy authService.init() (refresh) trước khi mount routes — nếu còn session thì về trang chủ
  if (isAuthenticated) {
    return <Navigate to="/" replace />
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-md bg-white rounded-xl shadow-sm border border-gray-200 p-8">
        {/* Logo / Title */}
        <div className="flex flex-col items-center mb-8">
          <div className="flex items-center gap-2 mb-2">
            <MessageSquare className="text-indigo-600" size={28} />
            <span className="text-2xl font-bold text-gray-900">ChatApp</span>
          </div>
          <p className="text-sm text-gray-500">Đăng nhập để tiếp tục</p>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} noValidate className="flex flex-col gap-5">
          {/* Username field */}
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="username"
              className="text-sm font-medium text-gray-700"
            >
              Tên đăng nhập
            </label>
            <input
              id="username"
              type="text"
              autoComplete="username"
              disabled={isLoading}
              placeholder="Nhập tên đăng nhập"
              {...register('username')}
              className={`w-full px-3 py-2.5 rounded-lg border text-sm text-gray-900 placeholder-gray-400
                focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent
                disabled:bg-gray-50 disabled:text-gray-400 disabled:cursor-not-allowed
                transition-colors
                ${errors.username ? 'border-red-500' : 'border-gray-300'}`}
            />
            {errors.username && (
              <p className="text-red-500 text-sm" role="alert">
                {errors.username.message}
              </p>
            )}
          </div>

          {/* Password field */}
          <div className="flex flex-col gap-1.5">
            <label
              htmlFor="password"
              className="text-sm font-medium text-gray-700"
            >
              Mật khẩu
            </label>
            <div className="relative">
              <input
                id="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                disabled={isLoading}
                placeholder="Nhập mật khẩu"
                {...register('password')}
                className={`w-full px-3 py-2.5 pr-10 rounded-lg border text-sm text-gray-900 placeholder-gray-400
                  focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent
                  disabled:bg-gray-50 disabled:text-gray-400 disabled:cursor-not-allowed
                  transition-colors
                  ${errors.password ? 'border-red-500' : 'border-gray-300'}`}
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

          {/* Forgot password */}
          <div className="flex justify-end">
            <button
              type="button"
              disabled
              className="text-gray-400 cursor-not-allowed text-sm"
            >
              Quên mật khẩu?
            </button>
          </div>

          {/* Submit button */}
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
                Đang đăng nhập...
              </>
            ) : (
              'Đăng nhập'
            )}
          </button>
        </form>

        {/* Google OAuth divider + button */}
        <div className="mt-4">
          <div className="relative flex items-center gap-3 my-4">
            <div className="flex-1 h-px bg-gray-200" />
            <span className="text-xs text-gray-400 uppercase tracking-wide">hoặc</span>
            <div className="flex-1 h-px bg-gray-200" />
          </div>

          <GoogleLoginButton
            onError={(err) => {
              const apiErr = (err as { response?: { data?: { error?: string } } })?.response?.data
              if (apiErr?.error === 'PROVIDER_ALREADY_LINKED') {
                addToast('Tài khoản Google này đã liên kết với user khác', 'error')
              } else {
                addToast('Đăng nhập Google thất bại, vui lòng thử lại', 'error')
              }
            }}
          />
        </div>

        {/* Register link */}
        <p className="mt-6 text-center text-sm text-gray-500">
          Chưa có tài khoản?{' '}
          <Link
            to="/register"
            className="text-indigo-600 font-medium hover:text-indigo-700 transition-colors"
          >
            Đăng ký ngay
          </Link>
        </p>
      </div>

      {/* Toast container */}
      <ToastContainer toasts={toasts} onRemove={removeToast} />
    </div>
  )
}
