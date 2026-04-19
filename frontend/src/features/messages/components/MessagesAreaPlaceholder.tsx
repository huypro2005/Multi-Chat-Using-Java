import { MessageCircle } from 'lucide-react'

/**
 * MessagesAreaPlaceholder — hiển thị khi chưa có messages hoặc messages chưa được load.
 * Tuần 4: component này sẽ được thay bằng MessagesList + infinite scroll.
 */
export function MessagesAreaPlaceholder() {
  return (
    <div className="flex-1 overflow-y-auto bg-gray-50 flex flex-col items-center justify-center">
      <MessageCircle size={64} className="text-gray-300 mb-4" />
      <p className="text-gray-500 font-medium">Bắt đầu cuộc trò chuyện</p>
      <p className="text-gray-400 text-sm mt-1">Gửi tin nhắn đầu tiên để bắt đầu</p>
    </div>
  )
}
