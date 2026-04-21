import { X } from 'lucide-react'
import type { PendingUpload } from '../useUploadFile'

interface Props {
  pending: PendingUpload
  onCancel: () => void
}

// Helper detect icon từ MIME client-side (trước khi server confirm iconType)
function clientIconEmoji(mime: string): string {
  if (mime.startsWith('image/')) return '🖼️'
  if (mime === 'application/pdf') return '📄'
  if (mime.includes('wordprocessingml') || mime === 'application/msword') return '📝'
  if (mime.includes('spreadsheetml') || mime === 'application/vnd.ms-excel') return '📊'
  if (mime.includes('presentationml') || mime === 'application/vnd.ms-powerpoint') return '📽️'
  if (mime === 'text/plain') return '📋'
  if (mime.includes('zip') || mime.includes('7z')) return '📦'
  return '📎'
}

export function PendingAttachmentItem({ pending, onCancel }: Props) {
  const isImage = pending.file.type.startsWith('image/')
  const hasError = pending.status === 'error'

  return (
    <div
      className={`relative w-20 h-20 flex-shrink-0 rounded-lg overflow-hidden border-2
        ${hasError ? 'border-red-400' : 'border-gray-200'}`}
    >
      {/* Preview */}
      {isImage ? (
        <img
          src={pending.previewUrl}
          alt={pending.file.name}
          className="w-full h-full object-cover"
        />
      ) : (
        <div className="w-full h-full flex flex-col items-center justify-center bg-gray-50 p-1 gap-0.5">
          <span className="text-2xl">{clientIconEmoji(pending.file.type)}</span>
          <span
            className="text-[10px] text-gray-500 text-center leading-tight px-0.5 overflow-hidden"
            style={{ wordBreak: 'break-all' }}
          >
            {pending.file.name.length > 12
              ? pending.file.name.slice(0, 8) + '…' + pending.file.name.slice(-3)
              : pending.file.name}
          </span>
        </div>
      )}

      {/* Progress overlay */}
      {pending.status === 'uploading' && (
        <div className="absolute inset-x-0 bottom-0 h-1.5 bg-gray-200/80">
          <div
            className="h-full bg-indigo-500 transition-all duration-200"
            style={{ width: `${pending.progress}%` }}
          />
        </div>
      )}

      {/* Error overlay */}
      {hasError && (
        <div
          className="absolute inset-0 bg-red-50/80 flex items-center justify-center"
          title={pending.error}
        >
          <span className="text-xs text-red-600 text-center p-0.5">!</span>
        </div>
      )}

      {/* Cancel/Remove button */}
      <button
        type="button"
        onClick={onCancel}
        aria-label="Xóa"
        className="absolute top-0.5 right-0.5 w-4 h-4 rounded-full bg-black/50 text-white
          flex items-center justify-center hover:bg-black/70 transition-colors"
      >
        <X size={10} />
      </button>
    </div>
  )
}
