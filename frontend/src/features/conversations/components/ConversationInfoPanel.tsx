import { X } from 'lucide-react'
import UserAvatar from '@/components/UserAvatar'
import type { ConversationDto } from '@/types/conversation'

interface Props {
  conversation: ConversationDto
  open: boolean
  onClose: () => void
}

/**
 * ConversationInfoPanel — slide-in panel từ phải, hiển thị thành viên của conversation.
 * Mobile: fixed overlay từ phải.
 * Desktop: relative panel bên phải, border-l.
 */
export function ConversationInfoPanel({ conversation, open, onClose }: Props) {
  return (
    <div
      className={`
        fixed inset-y-0 right-0 w-72 bg-white shadow-xl z-40
        transform transition-transform duration-200
        ${open ? 'translate-x-0' : 'translate-x-full'}
        md:relative md:inset-auto md:shadow-none md:border-l md:border-gray-200
        md:translate-x-0 md:z-auto
        ${!open ? 'md:hidden' : ''}
      `}
    >
      {/* Header */}
      <div className="p-4 border-b border-gray-200 flex items-center justify-between">
        <h3 className="font-semibold text-gray-900">Thông tin</h3>
        <button
          onClick={onClose}
          aria-label="Đóng"
          className="p-1 rounded-lg text-gray-500 hover:text-gray-700 hover:bg-gray-100
            transition-colors"
        >
          <X size={18} />
        </button>
      </div>

      {/* Members list */}
      <div className="p-4">
        <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-3">
          Thành viên ({conversation.members.length})
        </p>
        <ul className="space-y-3">
          {conversation.members.map((member) => (
            <li key={member.userId} className="flex items-center gap-3">
              <UserAvatar
                user={{ fullName: member.fullName, avatarUrl: member.avatarUrl }}
                size={36}
              />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-gray-900 truncate">{member.fullName}</p>
                {(member.role === 'OWNER' || member.role === 'ADMIN') && (
                  <p className="text-xs text-gray-500">
                    {member.role === 'OWNER' ? 'Chủ nhóm' : 'Admin'}
                  </p>
                )}
              </div>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
