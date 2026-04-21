import type { AttachmentDto } from '@/types/message'
import { Download } from 'lucide-react'
import { useProtectedObjectUrl } from '../hooks/useProtectedObjectUrl'

interface Props {
  attachment: AttachmentDto
}

// Icon + color mapping theo iconType từ BE
const ICON_EMOJI: Record<string, string> = {
  PDF: '📄',
  WORD: '📝',
  EXCEL: '📊',
  POWERPOINT: '📽️',
  TEXT: '📋',
  ARCHIVE: '📦',
  GENERIC: '📎',
  IMAGE: '🖼️',
}

const ICON_COLOR: Record<string, string> = {
  PDF: 'text-red-500',
  WORD: 'text-blue-600',
  EXCEL: 'text-green-600',
  POWERPOINT: 'text-orange-500',
  TEXT: 'text-gray-600',
  ARCHIVE: 'text-yellow-600',
  GENERIC: 'text-gray-500',
  IMAGE: 'text-indigo-500',
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

export function FileCard({ attachment }: Props) {
  const blobUrl = useProtectedObjectUrl(attachment.url)

  const iconType = attachment.iconType ?? 'GENERIC'
  const emoji = ICON_EMOJI[iconType] ?? '📎'
  const colorClass = ICON_COLOR[iconType] ?? 'text-gray-500'

  // Truncate filename: keep extension, truncate middle
  const displayName =
    attachment.name.length > 35
      ? attachment.name.slice(0, 25) + '…' + attachment.name.slice(-8)
      : attachment.name

  const handleDownload = () => {
    if (!blobUrl) return
    const a = document.createElement('a')
    a.href = blobUrl
    a.download = attachment.name
    a.click()
    // Note: không revoke blobUrl ở đây vì useProtectedObjectUrl manage lifecycle
  }

  return (
    <div
      className="flex items-center gap-3 p-3 bg-gray-50 border border-gray-200 rounded-xl
        max-w-xs w-full"
    >
      {/* Icon */}
      <span className={`text-3xl flex-shrink-0 ${colorClass}`} aria-hidden="true">
        {emoji}
      </span>

      {/* File info */}
      <div className="flex-1 min-w-0">
        <div
          className="text-sm font-medium text-gray-800 truncate"
          title={attachment.name}
        >
          {displayName}
        </div>
        <div className="text-xs text-gray-500">{formatSize(attachment.size)}</div>
      </div>

      {/* Download button */}
      <button
        type="button"
        onClick={handleDownload}
        disabled={!blobUrl}
        aria-label={`Tải xuống ${attachment.name}`}
        className="flex-shrink-0 p-1.5 rounded-lg text-gray-400 hover:text-indigo-600
          hover:bg-indigo-50 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
      >
        <Download size={16} />
      </button>
    </div>
  )
}
