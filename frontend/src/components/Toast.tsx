import { useEffect, useState } from 'react'
import { X } from 'lucide-react'

export type ToastType = 'success' | 'error' | 'info'

interface ToastProps {
  message: string
  type?: ToastType
  duration?: number
  onClose: () => void
}

const toastStyles: Record<ToastType, string> = {
  success: 'bg-green-600 text-white',
  error: 'bg-red-600 text-white',
  info: 'bg-indigo-600 text-white',
}

export function Toast({ message, type = 'info', duration = 3000, onClose }: ToastProps) {
  const [visible, setVisible] = useState(true)

  useEffect(() => {
    const timer = setTimeout(() => {
      setVisible(false)
      setTimeout(onClose, 300)
    }, duration)
    return () => clearTimeout(timer)
  }, [duration, onClose])

  return (
    <div
      className={`flex items-center gap-3 px-4 py-3 rounded-lg shadow-lg transition-all duration-300 ${
        toastStyles[type]
      } ${visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-2'}`}
      role="alert"
    >
      <span className="text-sm font-medium">{message}</span>
      <button
        onClick={() => {
          setVisible(false)
          setTimeout(onClose, 300)
        }}
        className="ml-auto p-0.5 rounded hover:bg-white/20 transition-colors"
        aria-label="Đóng thông báo"
      >
        <X size={14} />
      </button>
    </div>
  )
}

// Simple toast manager — dùng trong một page, không cần global state
interface ToastItem {
  id: string
  message: string
  type: ToastType
}

interface ToastContainerProps {
  toasts: ToastItem[]
  onRemove: (id: string) => void
}

export function ToastContainer({ toasts, onRemove }: ToastContainerProps) {
  if (toasts.length === 0) return null

  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2 max-w-sm w-full">
      {toasts.map((toast) => (
        <Toast
          key={toast.id}
          message={toast.message}
          type={toast.type}
          onClose={() => onRemove(toast.id)}
        />
      ))}
    </div>
  )
}
