// ---------------------------------------------------------------------------
// timerRegistry — module-level singleton để track timeout timers cho Path B
//
// Tại sao module-level thay vì state/ref của component:
// - ACK subscription là global (mount ở App root).
// - Component phát tempId có thể đã unmount khi ACK về (navigate away).
// - Phải accessible từ cả useSendMessage (writer) và useAckErrorSubscription (clearer).
//
// Entry: { timerId: number, convId: string }
// - timerId: window.setTimeout handle để clearTimeout khi nhận ACK/ERROR.
// - convId: cần thiết trong ERROR handler vì error payload không chứa convId.
// ---------------------------------------------------------------------------

interface TimerEntry {
  timerId: number
  convId: string
}

const registry = new Map<string, TimerEntry>()

export const timerRegistry = {
  set(tempId: string, entry: TimerEntry): void {
    registry.set(tempId, entry)
  },

  get(tempId: string): TimerEntry | undefined {
    return registry.get(tempId)
  },

  /**
   * Clear timeout và xoá entry khỏi registry.
   * Idempotent — gọi nhiều lần với cùng tempId không gây lỗi.
   */
  clear(tempId: string): void {
    const entry = registry.get(tempId)
    if (entry) {
      clearTimeout(entry.timerId)
      registry.delete(tempId)
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
