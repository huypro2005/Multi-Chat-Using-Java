// ---------------------------------------------------------------------------
// editTimerRegistry — module-level singleton để track timeout timers cho edit
//
// Pattern giống timerRegistry.ts nhưng cho edit operation.
// - Accessor: useEditMessage (writer) + useAckErrorSubscription (clearer)
// - Key: clientEditId (UUID v4 client-generated, gửi lên server trong edit frame)
// - Entry: { timerId, messageId, convId }
//
// Tại sao module-level: tương tự timerRegistry — ACK subscription là global,
// component có thể đã unmount khi ACK về (user scroll away). editTimerRegistry
// giúp tab-awareness: tab B không có clientEditId → bỏ qua ACK của tab A
// (xem SOCKET_EVENTS.md §3c.8 về multi-tab caveat).
// ---------------------------------------------------------------------------

interface EditTimerEntry {
  timerId: number
  messageId: string
  convId: string
}

const registry = new Map<string, EditTimerEntry>()

export const editTimerRegistry = {
  set(clientEditId: string, entry: EditTimerEntry): void {
    registry.set(clientEditId, entry)
  },

  get(clientEditId: string): EditTimerEntry | undefined {
    return registry.get(clientEditId)
  },

  /**
   * Clear timeout và xoá entry khỏi registry.
   * Idempotent — gọi nhiều lần với cùng clientEditId không gây lỗi.
   */
  clear(clientEditId: string): void {
    const entry = registry.get(clientEditId)
    if (entry) {
      clearTimeout(entry.timerId)
      registry.delete(clientEditId)
    }
  },

  /**
   * Clear tất cả timers — dùng khi logout để tránh stale callbacks.
   */
  clearAll(): void {
    registry.forEach((entry) => clearTimeout(entry.timerId))
    registry.clear()
  },

  /** Chỉ dùng cho testing */
  size(): number {
    return registry.size
  },
}
