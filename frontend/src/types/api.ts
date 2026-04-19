// ---------------------------------------------------------------------------
// Common API types — dùng chung toàn hệ thống
// ---------------------------------------------------------------------------

/**
 * Shape chuẩn của mọi error response từ backend.
 * Khớp với com.chatapp.exception.ErrorResponse (record).
 *
 * {
 *   "error": "ERROR_CODE_STRING",
 *   "message": "Human readable message",
 *   "timestamp": "2026-04-19T10:00:00Z",
 *   "details": { ... }   // optional, null nếu không có
 * }
 */
export interface ApiErrorBody {
  error: string
  message: string
  timestamp: string
  details?: unknown
}
