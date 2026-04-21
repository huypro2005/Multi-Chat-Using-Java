import { useState } from 'react'
import { X, ChevronLeft, ChevronRight, Download } from 'lucide-react'
import type { AttachmentDto } from '@/types/message'

interface Props {
  attachments: AttachmentDto[]
}

export function AttachmentGallery({ attachments }: Props) {
  const [lightboxIdx, setLightboxIdx] = useState<number | null>(null)

  if (attachments.length === 0) return null

  // Grid layout based on count
  const gridClass = attachments.length === 1 ? 'grid-cols-1' : 'grid-cols-2'
  const maxHeight = attachments.length === 1 ? 'max-h-72' : 'max-h-40'

  return (
    <>
      <div className={`grid ${gridClass} gap-0.5 rounded-xl overflow-hidden`}>
        {attachments.map((att, idx) => (
          <button
            key={att.id}
            type="button"
            className={`block bg-gray-100 overflow-hidden ${maxHeight} focus:outline-none`}
            onClick={() => setLightboxIdx(idx)}
            aria-label={`Xem ảnh ${att.name}`}
          >
            <img
              src={att.thumbUrl ?? att.url}
              alt={att.name}
              loading="lazy"
              className="w-full h-full object-cover"
              onError={(e) => {
                // File expired/deleted — show placeholder
                e.currentTarget.style.display = 'none'
              }}
            />
          </button>
        ))}
      </div>

      {/* Lightbox */}
      {lightboxIdx !== null && (
        <Lightbox
          attachments={attachments}
          initialIdx={lightboxIdx}
          onClose={() => setLightboxIdx(null)}
        />
      )}
    </>
  )
}

interface LightboxProps {
  attachments: AttachmentDto[]
  initialIdx: number
  onClose: () => void
}

function Lightbox({ attachments, initialIdx, onClose }: LightboxProps) {
  const [idx, setIdx] = useState(initialIdx)
  const current = attachments[idx]

  const prev = () => setIdx((i) => Math.max(0, i - 1))
  const next = () => setIdx((i) => Math.min(attachments.length - 1, i + 1))

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'ArrowLeft') prev()
    if (e.key === 'ArrowRight') next()
    if (e.key === 'Escape') onClose()
  }

  return (
    // eslint-disable-next-line jsx-a11y/no-noninteractive-element-interactions
    <div
      className="fixed inset-0 z-50 bg-black/90 flex items-center justify-center"
      onClick={onClose}
      onKeyDown={handleKeyDown}
      role="dialog"
      aria-modal="true"
      aria-label="Xem ảnh"
      tabIndex={-1}
    >
      {/* Image */}
      <img
        src={current.url}
        alt={current.name}
        className="max-w-[90vw] max-h-[85vh] object-contain rounded"
        onClick={(e) => e.stopPropagation()}
      />

      {/* Close */}
      <button
        type="button"
        onClick={onClose}
        className="absolute top-4 right-4 text-white/80 hover:text-white"
        aria-label="Đóng"
      >
        <X size={24} />
      </button>

      {/* Download */}
      <a
        href={current.url}
        download={current.name}
        target="_blank"
        rel="noreferrer"
        className="absolute top-4 right-14 text-white/80 hover:text-white"
        aria-label="Tải xuống"
        onClick={(e) => e.stopPropagation()}
      >
        <Download size={20} />
      </a>

      {/* Prev/Next */}
      {attachments.length > 1 && (
        <>
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation()
              prev()
            }}
            disabled={idx === 0}
            className="absolute left-4 top-1/2 -translate-y-1/2 text-white/80 hover:text-white disabled:opacity-30"
            aria-label="Ảnh trước"
          >
            <ChevronLeft size={32} />
          </button>
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation()
              next()
            }}
            disabled={idx === attachments.length - 1}
            className="absolute right-4 top-1/2 -translate-y-1/2 text-white/80 hover:text-white disabled:opacity-30"
            aria-label="Ảnh tiếp theo"
          >
            <ChevronRight size={32} />
          </button>
          <span className="absolute bottom-4 left-1/2 -translate-x-1/2 text-white/60 text-sm select-none">
            {idx + 1} / {attachments.length}
          </span>
        </>
      )}
    </div>
  )
}
