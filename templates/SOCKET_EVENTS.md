# Socket Events Contract — WebSocket / STOMP

> File này là **source of truth** cho mọi WebSocket event giữa frontend và backend.
> **Chỉ `code-reviewer` agent được sửa file này.** BE và FE đọc và implement theo.

## Connection

- **Endpoint**: `wss://<host>/ws` (SockJS fallback: `https://<host>/ws`)
- **Auth**: JWT trong header `Authorization: Bearer <token>` gửi ở CONNECT frame.
- **Heartbeat**: client → server 10s, server → client 10s.
- **Reconnect**: client tự động với exponential backoff (1s, 2s, 4s, ..., max 30s).

### Sau khi CONNECT thành công, client subscribe các destination:

- `/user/queue/acks` — nhận ACK cho message client gửi.
- `/user/queue/errors` — nhận ERROR cho message client gửi.
- `/user/queue/messages` — nhận tin mới từ conversations mình là thành viên.
- `/user/queue/notifications` — nhận notification chung (presence, conversation update).

---

## Format chuẩn

Mỗi event theo format:

### [Client → Server] /app/DESTINATION

**Payload**:
```json
{ ... }
```

**Server responds**:
- Success → `/user/queue/...` với payload `{ ... }`
- Failure → `/user/queue/errors` với payload `{ tempId, code, message }`

**Broadcast** (nếu có, server push cho người khác):
- Destination: `/user/queue/messages` (hoặc topic khác)
- Payload: `{ ... }`

**Error codes**: liệt kê đầy đủ

**Rate limit**: N events/second/user (nếu có)

---

## Events

### [Client → Server] /app/chat.send

(reviewer sẽ điền khi phase messaging bắt đầu — chi tiết tempId flow đã có trong ARCHITECTURE.md mục 1, flow gửi tin nhắn TEXT)

### [Client → Server] /app/chat.edit

(reviewer điền)

### [Client → Server] /app/chat.delete

(reviewer điền)

### [Client → Server] /app/chat.reaction

(reviewer điền)

### [Client → Server] /app/chat.read

(reviewer điền)

### [Client → Server] /app/chat.typing

(reviewer điền)

### [Client → Server] /app/chat.sync

(reviewer điền — quan trọng cho reconnect, client gửi lastMessageId từng conversation, server trả missed messages)

---

## Server-pushed events (không có trigger từ client)

### [Server → Client] /user/queue/messages (new message)

Push khi có tin mới trong conversation mà user là member (và đang online).

### [Server → Client] /user/queue/notifications (presence)

Push khi user khác online/offline (chỉ với các conversation mà user hiện tại có liên quan).

### [Server → Client] /user/queue/notifications (conversation update)

Push khi có thay đổi conversation (member thêm/bớt, tên đổi, settings đổi).

---

## tempId lifecycle (nguyên tắc chung)

1. **Client** tạo `tempId = crypto.randomUUID()` trước khi gửi.
2. **Client** hiển thị message ngay với state `SENDING`.
3. **Server** nhận → validate → save → trả ACK `{ tempId, messageId, createdAt }` qua `/user/queue/acks`.
4. **Client** nhận ACK → match theo tempId → update state `SENT`, thay tempId bằng messageId thật.
5. **Nếu server trả ERROR** qua `/user/queue/errors`: client update state `FAILED`, hiển thị retry button.
6. **Nếu client không nhận ACK sau 10s**: client tự động set `FAILED` với reason "timeout".
7. **Dedup phía server**: Redis SET `msg:dedup:{userId}:{tempId}` TTL 60s. Nếu đã xử lý, trả về ACK cũ.

---

## Authorization check trong mọi event

Server PHẢI verify:
1. User có JWT hợp lệ (đã check ở STOMP interceptor).
2. User là member của conversation được refer trong payload.
3. User không bị block bởi người nhận (cho direct message).
4. Conversation không bị khóa (locked/archived).

Nếu fail bất kỳ check nào → trả ERROR với code cụ thể.

---

## Error codes dùng chung cho socket

- `SOCKET_NOT_AUTHENTICATED` — JWT invalid/missing, client nên disconnect và login lại.
- `SOCKET_NOT_MEMBER` — user không phải member conversation.
- `SOCKET_BLOCKED` — user bị block.
- `SOCKET_RATE_LIMITED` — quá nhanh, kèm `retryAfter` ms.
- `SOCKET_INVALID_PAYLOAD` — payload sai shape, kèm `details.field`.
- `SOCKET_CONV_LOCKED` — conversation đã khóa.
- `SOCKET_INTERNAL_ERROR` — lỗi server, client có thể retry.

---

## Changelog

- YYYY-MM-DD — initial skeleton
