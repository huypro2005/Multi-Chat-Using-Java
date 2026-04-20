// ---------------------------------------------------------------------------
// TypingIndicator — hiển thị tên người đang gõ bên dưới message list
// Render null khi không có ai gõ (chiều cao = 0, không chiếm layout).
// ---------------------------------------------------------------------------

interface TypingUser {
  userId: string
  username: string
}

interface Props {
  typingUsers: TypingUser[]
}

export function TypingIndicator({ typingUsers }: Props) {
  if (typingUsers.length === 0) return null

  let text: string
  if (typingUsers.length === 1) {
    text = `${typingUsers[0].username} đang gõ...`
  } else if (typingUsers.length === 2) {
    text = `${typingUsers[0].username}, ${typingUsers[1].username} đang gõ...`
  } else {
    text = `${typingUsers.length} người đang gõ...`
  }

  return (
    <div
      className="px-4 py-1 text-xs text-gray-500 italic min-h-[20px]"
      aria-live="polite"
      aria-label={text}
    >
      {text}
    </div>
  )
}
