// ---------------------------------------------------------------------------
// DeletedMessagePlaceholder — hiện thay bubble khi message.deletedAt != null
//
// Per §3e SOCKET_EVENTS.md:
//   - gray background (bg-gray-100 dark:bg-gray-800), italic, opacity-70
//   - Không render MessageActions
// ---------------------------------------------------------------------------

export function DeletedMessagePlaceholder() {
  return (
    <div
      className="bg-gray-100 dark:bg-gray-800 rounded-2xl px-4 py-2 italic
        text-gray-400 text-sm opacity-70 select-none"
    >
      🚫 Tin nhắn đã bị xóa
    </div>
  )
}
