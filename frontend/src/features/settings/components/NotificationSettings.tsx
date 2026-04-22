export function NotificationSettings() {
  return (
    <div className="space-y-4">
      <div className="p-4 rounded-lg border border-blue-200 bg-blue-50 text-blue-800 text-sm">
        Tính năng này đang được phát triển. Hiện tại bạn sẽ nhận thông báo cho tất cả tin nhắn mới.
      </div>
      <div className="space-y-2 opacity-60">
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked disabled />
          Thông báo tin nhắn mới
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked disabled />
          Âm thanh khi nhận tin nhắn
        </label>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" disabled />
          Thông báo khi được nhắc tên (V2)
        </label>
      </div>
    </div>
  )
}
