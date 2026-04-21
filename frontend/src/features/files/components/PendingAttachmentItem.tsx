import { X } from 'lucide-react'
import type { PendingUpload } from '../useUploadFile'

interface Props {
  pending: PendingUpload
  onCancel: () => void
}

export function PendingAttachmentItem({ pending, onCancel }: Props) {
  const isImage = pending.file.type.startsWith('image/')
  const isPdf = pending.file.type === 'application/pdf'
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
      ) : isPdf ? (
        <div className="w-full h-full flex flex-col items-center justify-center bg-gray-50 p-1">
          <span className="text-2xl">📄</span>
          <span className="text-xs text-gray-500 truncate w-full text-center mt-0.5">
            {pending.file.name.length > 10
              ? pending.file.name.slice(0, 8) + '…'
              : pending.file.name}
          </span>
        </div>
      ) : null}

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
