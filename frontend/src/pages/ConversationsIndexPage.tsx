import { MessageSquare } from 'lucide-react'

/**
 * ConversationsIndexPage — hiện khi user ở /conversations mà chưa chọn cuộc trò chuyện nào.
 * Route: /conversations (index)
 */
export default function ConversationsIndexPage() {
  return (
    <div className="flex-1 flex flex-col items-center justify-center gap-4 p-8 bg-gray-50">
      <div className="rounded-full bg-indigo-50 p-5">
        <MessageSquare className="text-indigo-400" size={40} />
      </div>
      <div className="text-center">
        <p className="text-lg font-medium text-gray-700">
          Chọn một cuộc trò chuyện để bắt đầu
        </p>
        <p className="text-sm text-gray-400 mt-1">
          Hoặc tạo cuộc trò chuyện mới từ danh sách bên trái
        </p>
      </div>
    </div>
  )
}
