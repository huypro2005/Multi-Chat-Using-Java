import { FileText, Download } from 'lucide-react'
import type { AttachmentDto } from '@/types/message'

interface Props {
  attachment: AttachmentDto
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  return `${Math.round(bytes / 1024)} KB`
}

export function PdfCard({ attachment }: Props) {
  const displayName =
    attachment.name.length > 30
      ? attachment.name.slice(0, 28) + '…'
      : attachment.name

  return (
    <a
      href={attachment.url}
      target="_blank"
      rel="noreferrer"
      download={attachment.name}
      className="flex items-center gap-3 p-3 bg-gray-50 border border-gray-200 rounded-xl
        hover:bg-gray-100 transition-colors no-underline text-current max-w-xs"
    >
      <FileText size={28} className="text-red-500 flex-shrink-0" />
      <div className="flex-1 min-w-0">
        <div className="text-sm font-medium text-gray-800 truncate">{displayName}</div>
        <div className="text-xs text-gray-500">{formatSize(attachment.size)}</div>
      </div>
      <Download size={16} className="text-gray-400 flex-shrink-0" />
    </a>
  )
}
