// ---------------------------------------------------------------------------
// deleteTimerRegistry — module-level singleton để track timeout timers cho delete
//
// Pattern giống editTimerRegistry.ts nhưng cho delete operation.
// - Accessor: useDeleteMessage (writer) + useAckErrorSubscription (clearer)
// - Key: clientDeleteId (UUID v4 client-generated, gửi lên server trong delete frame)
// - Entry: { timerId, messageId, convId }
//
// Tại sao module-level: ACK subscription là global, component có thể đã unmount
// khi ACK về (user scroll away). deleteTimerRegistry cung cấp tab-awareness:
// tab B không có clientDeleteId → bỏ qua ACK của tab A.
// ---------------------------------------------------------------------------

interface DeleteTimerEntry {
  timerId: number
  messageId: string
  convId: string
}

const registry = new Map<string, DeleteTimerEntry>()

export const deleteTimerRegistry = {
  set(clientDeleteId: string, entry: DeleteTimerEntry): void {
    registry.set(clientDeleteId, entry)
  },

  get(clientDeleteId: string): DeleteTimerEntry | undefined {
    return registry.get(clientDeleteId)
  },

  /**
   * Clear timeout và xoá entry khỏi registry.
   * Idempotent — gọi nhiều lần với cùng clientDeleteId không gây lỗi.
   */
  clear(clientDeleteId: string): void {
    const entry = registry.get(clientDeleteId)
    if (entry) {
      clearTimeout(entry.timerId)
      registry.delete(clientDeleteId)
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
