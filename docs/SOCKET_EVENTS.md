# WebSocket / STOMP Events Contract
_Version: v1.4-w6_
_Status: Accepted — Path B (STOMP-send) chốt sau W4D4; Edit (W5-D2) + Delete (W5-D3) dùng unified ACK queue; W6-D1 thêm attachments cho SEND_
_Owner: code-reviewer (architect)_

> File này là **source of truth** cho mọi WebSocket event giữa frontend và backend.
> **Chỉ `code-reviewer` agent được sửa file này.** BE và FE đọc và implement theo.
>
> **LƯU Ý KIẾN TRÚC TUẦN 4 → POST-W4**: W4 đã implement mô hình REST-gửi + STOMP-broadcast (ADR-014). Sau W4D4, team chốt **chuyển sang STOMP-send** (ADR-016, Path B): client gửi tin qua `/app/conv.{convId}.message` với `tempId`, server ACK qua `/user/queue/acks`, ERROR qua `/user/queue/errors`, Redis dedup bằng `msg:dedup:{userId}:{tempId}` TTL 60s. REST `POST /messages` **không bị xoá** — giữ làm fallback + batch import + bot API.

## 1. Overview

**Protocol**: STOMP 1.2 over SockJS (fallback to raw WebSocket)
**Endpoint**: `/ws` (HTTP upgrade)
**Client lib**: `@stomp/stompjs` + `sockjs-client`

### Auth flow
1. Client lấy `accessToken` từ REST `/auth/login` hoặc `/auth/refresh`.
2. Client tạo STOMP connection với headers:
   ```
   CONNECT
   Authorization: Bearer <accessToken>
   heart-beat: 10000,10000
   ```
3. BE `ChannelInterceptor` validate JWT trong `CONNECT` frame.
4. Nếu invalid: BE gửi `ERROR` frame với header `message: AUTH_REQUIRED` → disconnect.
5. Nếu valid: connection established, `Principal` set vào session (user id lấy từ JWT claims).

### Reconnect
- Exponential backoff: 1s, 2s, 4s, 8s, 16s, 30s (cap).
- Max 10 attempts, sau đó yêu cầu user reload page.
- Sau reconnect: re-subscribe tất cả channels cũ.
- Khi tab focus lại sau background: force reconnect nếu stale > 30s.
- **Catch-up**: sau reconnect FE PHẢI gọi REST `GET /api/conversations/{id}/messages?cursor=<lastKnown>` để lấy missed messages (xem mục 8 Limitations). STOMP không đảm bảo delivery khi offline.

---

## 2. Destinations

### Server → Client (BE publish, FE subscribe)

| Destination | Mô tả | Auth check | Phase |
|------------|-------|-----------|-------|
| `/topic/conv.{conversationId}` | Events trong conversation | Member check khi SUBSCRIBE | **Tuần 4** |
| `/user/queue/notifications` | Notifications cá nhân | Tự động (user queue, Spring resolve principal) | Tuần 7 |
| `/topic/presence.{userId}` | Online status | Public trong friends-of-friends; V1 open | Tuần 5 |

### Client → Server (FE gửi, BE nhận)

| Destination | Mô tả | Auth policy | Rate limit | Phase |
|------------|-------|-------------|-----------|-------|
| `/app/conv.{conversationId}.message` | **Gửi tin nhắn (Path B, ADR-016)** | STRICT_MEMBER | 30 msg/phút/user | **Post-W4 / Tuần 5** |
| `/app/conv.{convId}.edit` | Edit message | STRICT_MEMBER | 10 edit/phút/user | Tuần 5 |
| `/app/conv.{convId}.delete` | Delete message | STRICT_MEMBER | 10/phút/user | Tuần 5 |
| `/app/conv.{conversationId}.typing` | Typing indicator | SILENT_DROP | 1 event/2s/user/conv | Tuần 5 |
| `/app/conv.{conversationId}.read` | Read receipt | SILENT_DROP | 1 event/5s/user/conv | Tuần 5 |

### User queues (Server → Client, sender-only)

Spring STOMP user destination — BE gửi bằng `SimpMessagingTemplate.convertAndSendToUser(principalName, destination, payload)`. Chỉ session của user đó mới nhận.

| Destination | Mô tả | Trigger |
|------------|-------|---------|
| `/user/queue/acks` | ACK khi gửi tin nhắn thành công | Sau khi `/app/conv.{id}.message` save xong |
| `/user/queue/errors` | ERROR khi gửi tin nhắn thất bại | Khi `/app/conv.{id}.message` validation fail hoặc authorization fail |

> **Scope hiện tại**: `/topic/conv.{conversationId}` broadcast (W4D4 done) + `/app/conv.{conversationId}.message` (Path B Post-W4, ADR-016) + `/app/conv.{id}.edit` (W5-D2) + `/app/conv.{id}.delete` (W5-D3) + `/app/conv.{id}.typing` (W5-D1) + `/user/queue/acks`, `/user/queue/errors` unified (ADR-017). Presence/read giữ Tuần 5 về sau.

---

## 3. Event Envelope

Tất cả events trên `/topic/conv.{convId}` có envelope chung:

```json
{
  "type": "MESSAGE_CREATED",
  "payload": { ... }
}
```

Trường `type` luôn UPPERCASE snake-like. `payload` là object tự do, shape quy định theo từng `type`.

### 3.1 MESSAGE_CREATED (Tuần 4)

**Trigger**: BE broadcast sau khi `POST /api/conversations/{id}/messages` save thành công.

**Payload**: `MessageDto` — **shape IDENTICAL với REST response** của `POST /api/conversations/{convId}/messages` trong `API_CONTRACT.md` mục v0.6.0-messages-rest (+ v0.9.0-files cập nhật).

```json
{
  "type": "MESSAGE_CREATED",
  "payload": {
    "id": "uuid",
    "conversationId": "uuid",
    "sender": {
      "id": "uuid",
      "username": "string",
      "fullName": "string",
      "avatarUrl": "string|null"
    },
    "type": "TEXT | IMAGE | FILE",
    "content": "string | null (W6: nullable khi chỉ có attachments)",
    "attachments": [
      {
        "id": "uuid",
        "mime": "image/jpeg | image/png | image/webp | image/gif | application/pdf",
        "name": "string",
        "size": 123456,
        "url": "/api/files/{id}",
        "thumbUrl": "/api/files/{id}/thumb | null",
        "expiresAt": "2026-05-20T10:00:00Z"
      }
    ],
    "replyToMessage": {
      "id": "uuid",
      "senderName": "string",
      "contentPreview": "string (≤100 chars + '...' nếu truncated)"
    },
    "editedAt": null,
    "deletedAt": null,
    "deletedBy": null,
    "createdAt": "2026-04-20T10:00:00Z"
  }
}
```

> **Rule vàng**: BE PHẢI dùng cùng 1 `MessageMapper` / serialization cho cả REST response và broadcast payload. Nếu shape lệch → FE sẽ mismatch runtime. Reviewer sẽ check điều này ở W4-D3 review. W6-D1: `attachments` là array (có thể rỗng `[]`), **không bao giờ `null`** — FE không phải check null.

**FE action khi nhận MESSAGE_CREATED**:
1. Parse `frame.body` → `WsEvent<MessageDto>`.
2. **Dedupe check** (BẮT BUỘC): kiểm tra `messages.some(m => m.id === payload.id)`. Nếu `true` → skip hoàn toàn (idempotent).
3. Nếu chưa có → append vào React Query cache `messageKeys.all(convId)`.
4. Invalidate / update `useConversations` list để sidebar cập nhật `lastMessageAt` + re-sort.

> **Tại sao BẮT BUỘC dedupe?**: Sender A gửi REST `POST /messages` → React Query `onSuccess` set message vào cache với id thật → BE ALSO broadcast MESSAGE_CREATED qua `/topic/conv.{id}` → A nhận broadcast với CÙNG id → nếu không dedupe sẽ duplicate UI. Sender B (receiver) nhận broadcast → không có trong cache → append bình thường.

### 3.2 MESSAGE_UPDATED _(Tuần 5 — W5-D2)_

**Trigger**: Sau khi STOMP `/app/conv.{convId}.edit` save thành công và commit DB. Xem chi tiết flow tại **§3c**.

**Destination**: `/topic/conv.{convId}` (broadcast tới tất cả members, gồm cả sender để sync UI nhiều tab).

**Envelope**:
```json
{
  "type": "MESSAGE_UPDATED",
  "payload": {
    "id": "uuid",
    "conversationId": "uuid",
    "content": "new content",
    "editedAt": "2026-04-20T10:05:00Z"
  }
}
```

**FE action khi nhận MESSAGE_UPDATED**:
1. Parse `frame.body` → `WsEvent<MessageUpdatedPayload>`.
2. Tìm message trong cache theo `payload.id`. Nếu không có (user chưa load conv đó) → skip.
3. **Dedupe theo editedAt** (BẮT BUỘC): nếu `broadcast.editedAt <= cache.editedAt` → skip (broadcast cũ đến sau ACK mới, hoặc duplicate từ retry). So sánh bằng timestamp ISO8601 lexicographic (UTC Z format đồng nhất) hoặc parse Date.
4. Nếu broadcast mới hơn → update fields `content` + `editedAt` của message tương ứng. Giữ nguyên `id`, `sender`, `createdAt`, `replyToMessage`.
5. KHÔNG invalidate `['conversations']` trừ khi message này là `lastMessage` của conv (V1 đơn giản: luôn invalidate, sidebar tự refresh `lastMessagePreview` nếu BE trả snippet).

### 3.3 MESSAGE_DELETED _(Tuần 5 — W5-D3)_

**Trigger**: Sau khi STOMP `/app/conv.{convId}.delete` save `deleted_at + deleted_by` thành công và commit DB. Xem chi tiết flow tại **§3d**.

**Destination**: `/topic/conv.{convId}` (broadcast tới tất cả members, gồm cả sender để sync UI nhiều tab).

**Envelope**:
```json
{
  "type": "MESSAGE_DELETED",
  "payload": {
    "id": "uuid",
    "conversationId": "uuid",
    "deletedAt": "ISO8601",
    "deletedBy": "uuid"
  }
}
```

**FE action khi nhận MESSAGE_DELETED**:
1. Parse `frame.body` → `WsEvent<MessageDeletedPayload>`.
2. Tìm message trong cache theo `payload.id`. Nếu không có (user chưa load conv đó) → skip.
3. **KHÔNG xoá khỏi cache** — soft delete, giữ nguyên vị trí để bảo toàn thứ tự và scroll position. Set các field:
   - `deletedAt` = `payload.deletedAt`
   - `deletedBy` = `payload.deletedBy`
   - `content` = `null` (strip, render placeholder theo §3e)
4. Nếu tại thời điểm nhận, user đang edit chính message này (`editState === 'editing' | 'saving'`) → **exit edit mode silently** (set `editState = 'idle'`, discard `draftContent`, clear timer+registry nếu có `clientEditId` active). Không toast — UX silent vì message đã biến mất, edit không còn nghĩa.
5. KHÔNG cần dedupe theo timestamp cho DELETED (không có concurrent delete hợp lệ — 1 message delete 1 lần). Broadcast lặp lại (rare do SimpleBroker không duplicate) → FE set lại cùng giá trị, idempotent.
6. Invalidate `['conversations']` để sidebar refresh (trường hợp message bị xoá là `lastMessage` — sidebar hiện preview stale).

### 3.4 TYPING_STARTED / TYPING_STOPPED _(Tuần 5 — chưa implement)_

```json
{
  "type": "TYPING_STARTED",
  "payload": {
    "userId": "uuid",
    "username": "string",
    "conversationId": "uuid"
  }
}
```

TYPING_STOPPED shape giống hệt.

### 3.5 PRESENCE_CHANGED _(Tuần 5 — chưa implement)_

```json
{
  "type": "PRESENCE_CHANGED",
  "payload": {
    "userId": "uuid",
    "online": true,
    "lastSeenAt": "ISO8601|null"
  }
}
```

---

## 3b. Send Message via STOMP — Path B (ADR-016)

> **Post-W4 addition**: Sau khi W4D4 xong, team chuyển path gửi tin từ REST → STOMP để giảm latency và thống nhất transport. REST endpoint `POST /api/conversations/{id}/messages` vẫn hoạt động (cho batch import, bot API, testing) nhưng FE không còn dùng trên hot path.

### 3b.1 Client → Server: `/app/conv.{convId}.message`

**Destination**: `/app/conv.{convId}.message`

**Auth**: STOMP `Principal` đã set ở CONNECT frame (xem mục 1 Auth flow). Nếu không có Principal → BE trả ERROR qua `/user/queue/errors` với `code: AUTH_REQUIRED`.

**Payload**:
```json
{
  "tempId": "uuid v4 (client-generated)",
  "content": "string | null (0-5000 chars, nullable khi có attachments)",
  "type": "TEXT",
  "replyToMessageId": "uuid | null (W5-D4+, nullable)",
  "attachmentIds": ["uuid", "..."]
}
```

**Validation rules** (server-side):
- `tempId`: bắt buộc, UUID v4 format. Server dùng để dedup + route ACK.
- `content`:
  - **Trước W6**: bắt buộc non-empty 1-5000 chars.
  - **Từ W6-D1**: nullable. Phải non-empty (1-5000 chars sau trim) HOẶC `attachmentIds` non-empty. Cả 2 rỗng → `MSG_NO_CONTENT`.
- `type`: chỉ `"TEXT"` ở Path B (server tự derive `IMAGE` / `FILE` từ attachments khi persist — xem API_CONTRACT.md). `SYSTEM` chỉ server phát, client không gửi.
- `replyToMessageId` (W5-D4): nullable UUID. Nếu non-null → PHẢI thuộc cùng `convId`; cross-conv → `VALIDATION_FAILED`. Cho phép reply vào tin nhắn đã bị soft-delete (quoting deleted source OK — AD-16). ACK trả về `replyToMessage.contentPreview = null` + `replyToMessage.deletedAt` set nếu source đã xóa.
- `attachmentIds` (W6-D1): array UUID, có thể rỗng (`[]` hoặc omit). Validation chi tiết (count, mix, ownership, expiry) xem API_CONTRACT.md mục "Validation rules cho SEND + EDIT với attachments". Error codes: `MSG_NO_CONTENT`, `MSG_ATTACHMENTS_MIXED`, `MSG_ATTACHMENTS_TOO_MANY`, `MSG_ATTACHMENT_NOT_FOUND`, `MSG_ATTACHMENT_EXPIRED`, `MSG_ATTACHMENT_NOT_OWNED`, `MSG_ATTACHMENT_ALREADY_USED`.

> **Path B scope (W6 update)**: Qua STOMP `/app/conv.{id}.message` — text + reply + attachments (images hoặc PDF) đều qua path này. File upload TẠM đi trước qua REST `POST /api/files/upload` (trả `fileId`), sau đó client gửi STOMP với `attachmentIds: [fileId]` để tạo message gắn file. Lý do tách 2 bước: (1) upload binary không hợp với STOMP frame size limit 64KB, (2) progress bar + retry dễ hơn với multipart HTTP, (3) re-use file cho multiple message (tương lai).

### 3b.2 Server → Client (sender-only): ACK

**Destination**: `/user/queue/acks`

**Payload**:
```json
{
  "tempId": "uuid (echo từ request)",
  "message": {
    "id": "uuid (real, server-generated)",
    "conversationId": "uuid",
    "sender": { "id": "uuid", "username": "...", "fullName": "...", "avatarUrl": null },
    "type": "TEXT",
    "content": "...",
    "replyToMessage": null,
    "editedAt": null,
    "createdAt": "2026-04-20T10:00:00Z"
  }
}
```

Shape của `message` **IDENTICAL** với `MessageDto` ở REST `POST /messages` response và broadcast payload `MESSAGE_CREATED`. Reuse cùng `MessageMapper` phía BE.

> **W6-D1 note**: `message` trong ACK bao gồm field `attachments: FileDto[]` (có thể rỗng `[]`) khi payload SEND có `attachmentIds`. BE PHẢI load attachments qua `JOIN message_attachments` trước khi trả ACK. Shape `FileDto` xem API_CONTRACT.md mục Files Management.

> **Migration W5-D2 (ADR-017)**: Shape này sẽ đổi sang `{operation: "SEND", clientId: tempId, message}` khi implement edit message, để unify với EDIT ACK. BE + FE phải deploy đồng bộ. Xem §3c.3 + ADR-017 + changelog v1.3-w5d2.

### 3b.3 Server → Client (sender-only): ERROR

**Destination**: `/user/queue/errors`

**Payload**:
```json
{
  "tempId": "uuid (echo từ request)",
  "error": "string (human-readable)",
  "code": "CONV_NOT_FOUND | FORBIDDEN | VALIDATION_FAILED | MSG_CONTENT_TOO_LONG | MSG_RATE_LIMITED | MSG_NO_CONTENT | MSG_ATTACHMENTS_MIXED | MSG_ATTACHMENTS_TOO_MANY | MSG_ATTACHMENT_NOT_FOUND | MSG_ATTACHMENT_EXPIRED | MSG_ATTACHMENT_NOT_OWNED | MSG_ATTACHMENT_ALREADY_USED | AUTH_REQUIRED | INTERNAL"
}
```

**Error code table**:

| code | Điều kiện | FE action |
|------|-----------|-----------|
| `CONV_NOT_FOUND` | Conversation không tồn tại hoặc sender không phải member (merge để chống enumeration) | Mark tempId = failed, hiện "Không tìm thấy cuộc trò chuyện" |
| `FORBIDDEN` | Sender bị user khác trong conv block (khi `user_blocks` wire; V1 chưa fire) | Mark failed, hiện "Không thể gửi tin nhắn tới người này" |
| `VALIDATION_FAILED` | tempId không phải UUID, payload malformed | Mark failed, hiện message cụ thể (FE không retry) |
| `MSG_CONTENT_TOO_LONG` | content > 5000 chars | Mark failed, hiện "Tin nhắn quá dài (tối đa 5000 ký tự)" |
| `MSG_RATE_LIMITED` | Vượt 30 msg/phút/user | Mark failed, hiện "Gửi quá nhanh, thử lại sau N giây"; FE có thể retry sau `retryAfterSeconds` (nếu BE có gửi field này) |
| `MSG_NO_CONTENT` (W6) | Cả `content` và `attachmentIds` đều rỗng/null | Mark failed, hiện "Tin nhắn phải có nội dung hoặc file đính kèm" |
| `MSG_ATTACHMENTS_MIXED` (W6) | Trộn image + PDF trong 1 message | Mark failed, hiện "Không thể gửi chung ảnh và PDF" |
| `MSG_ATTACHMENTS_TOO_MANY` (W6) | >5 images hoặc >1 PDF | Mark failed, hiện "Tối đa 5 ảnh hoặc 1 file PDF" |
| `MSG_ATTACHMENT_NOT_FOUND` (W6) | `attachmentId` không tồn tại trong `files` table | Mark failed, hiện "File không tồn tại hoặc đã bị xóa" (upload lại) |
| `MSG_ATTACHMENT_EXPIRED` (W6) | File đã expire (`expires_at < now()`) | Mark failed, hiện "File đã hết hạn, vui lòng upload lại" |
| `MSG_ATTACHMENT_NOT_OWNED` (W6) | Sender không phải uploader của file (`file.uploader_id != userId`) | Mark failed, hiện "File không hợp lệ" (KHÔNG tiết lộ "file của user khác") |
| `MSG_ATTACHMENT_ALREADY_USED` (W6) | File đã attach vào message khác (DB UNIQUE + service check) | Mark failed, hiện "File đã được sử dụng trong tin nhắn khác" |
| `AUTH_REQUIRED` | STOMP Principal null (token lỗi giữa chừng) | Trigger refresh flow → reconnect |
| `INTERNAL` | Lỗi không xác định (DB down, NPE, …) | Mark failed, hiện "Lỗi server, thử lại sau"; FE có thể retry manual |

> **Dual-delivery invariant**: Với MỖI 1 request `/app/conv.{id}.message`, BE PHẢI trả CHÍNH XÁC 1 response trong 2 queue — hoặc ACK (`/user/queue/acks`) hoặc ERROR (`/user/queue/errors`). Không được gửi cả hai, không được im lặng. Client timeout 10s sẽ đánh giá dựa trên giả định này.

### 3b.4 Dedup Strategy

#### Server-side (Redis)

- **Key**: `msg:dedup:{userId}:{tempId}`
- **TTL**: 60 giây (đủ lâu để bắt retry do network blip, ngắn đủ để không phình Redis).
- **Value**: `messageId` thật (để trả ACK cho retry mà không cần re-query DB).

**Flow**:
1. BE nhận STOMP frame từ `/app/conv.{id}.message`.
2. Validate payload (tempId UUID, content length).
3. Authorize (sender là member của conv).
4. `SETNX msg:dedup:{userId}:{tempId} <placeholder>` → nếu return 0 (key đã tồn tại):
   - `GET` key để lấy `messageId` đã save.
   - Load `Message` từ DB, map sang `MessageDto`.
   - Gửi ACK `/user/queue/acks` với `{tempId, message}` (idempotent — client coi như chưa bao giờ thấy duplicate).
   - KHÔNG publish event `MessageCreatedEvent` lần 2 (tránh broadcast `/topic/conv.{id}` 2 lần).
5. Nếu SETNX return 1 (key mới):
   - `EXPIRE key 60`.
   - Save message DB → lấy `messageId` thật.
   - `SET msg:dedup:{userId}:{tempId} <messageId> EX 60` (update value).
   - `publishEvent(new MessageCreatedEvent(convId, dto))` → `@TransactionalEventListener(AFTER_COMMIT)` broadcast `/topic/conv.{id}`.
   - Gửi ACK `/user/queue/acks`.

> **BLOCKING check**: SETNX + EXPIRE phải ATOMIC. Dùng Lua script hoặc `SET key value NX EX 60` (Redis ≥ 2.6.12). Nếu SETNX xong mà EXPIRE fail → key persist vĩnh viễn, user không thể gửi tempId đó lại (hiếm nhưng có thể).
> **BLOCKING check**: `SET NX EX` phải chạy TRƯỚC transaction DB. Nếu đặt sau save → race window 2 request concurrent cùng pass SETNX check → save 2 lần.

#### Client-side (timeout + retry)

- **Timeout**: 10 giây kể từ khi client gọi `client.publish({ destination, body })`.
- **Nếu hết 10s chưa nhận ACK hoặc ERROR**: set message status `failed`, show retry button.
- **Retry**: user click retry → generate **tempId MỚI** (UUID v4), publish lại. **KHÔNG reuse tempId cũ** — server dedup key đã expire hoặc chưa expire nhưng đã trả ACK → retry sẽ nhầm là duplicate → UI bị ghost.
- **Network error khi publish**: @stomp/stompjs sẽ queue frame và replay sau reconnect. FE không cần handle riêng, chỉ cần timeout chung 10s là đủ.

### 3b.5 tempId Lifecycle (FE state machine)

Mỗi message trong FE cache có trường `status: 'sending' | 'sent' | 'failed'` và trường `clientTempId` (chỉ set khi status != 'sent').

```
                         [user click send]
                               │
                               ▼
                    ┌─────────────────────┐
                    │ tempId = UUID v4    │
                    │ status = 'sending'  │
                    │ append optimistic   │
                    │ (id = tempId)       │
                    └──────────┬──────────┘
                               │ client.publish('/app/conv.{id}.message')
                               │ start 10s timeout
                               │
                ┌──────────────┼──────────────┐
                ▼              ▼              ▼
        [ACK received]   [ERROR received]  [10s timeout]
                │              │              │
                ▼              ▼              ▼
       replace optimistic  set status      set status
       (id = tempId)       'failed'        'failed'
       bằng real msg       show reason     show "Không phản hồi"
       (id = server id)    + retry btn     + retry btn
       status = 'sent'
       clear tempId
                │
                │  Also: broadcast /topic/conv.{id} MESSAGE_CREATED đến
                │  chính sender → dedupe bằng real id (message đã có
                │  trong cache với id server), skip append.
                ▼
        [done]
```

**Chi tiết từng bước**:

1. **Client sinh `tempId = crypto.randomUUID()`** (UUID v4).
2. **Optimistic append** vào React Query cache với id = tempId, `status = 'sending'`, `clientTempId = tempId`, `createdAt = new Date().toISOString()` (tạm, sẽ thay bằng server timestamp khi ACK).
3. **Client publish STOMP frame** tới `/app/conv.{convId}.message` với body `{tempId, content, type: 'TEXT'}`. Bật timer 10s.
4. **Server nhận** → validate → authorize → SETNX dedup → save DB → `publishEvent(MessageCreatedEvent)`.
5. **`MessageBroadcaster` `@TransactionalEventListener(AFTER_COMMIT)`** → broadcast `/topic/conv.{convId}` event `MESSAGE_CREATED` → tất cả subscriber (kể cả sender) nhận.
6. **Server gửi ACK** `/user/queue/acks` với `{tempId, message: MessageDto}` → chỉ sender nhận.
7. **Client nhận ACK**:
   - Clear timer.
   - Tìm message trong cache bằng `m.clientTempId === tempId`.
   - Replace toàn bộ fields (id, createdAt, sender, …) bằng `message` từ ACK.
   - Set `status = 'sent'`, clear `clientTempId`.
8. **Client nhận broadcast `/topic/conv.{convId}`** (sender tự nhận broadcast của chính mình):
   - Dedupe bằng **real id** (`m.id === broadcast.payload.id`). Vì bước 7 đã set id = real id, `some(m => m.id === newMsg.id)` return true → skip append.
   - Đối với receiver (sender khác): không có optimistic → append bình thường (dedupe vẫn cần phòng double-subscribe).
9. **Client nhận ERROR** `/user/queue/errors`:
   - Clear timer.
   - Tìm message bằng `clientTempId === tempId`.
   - Set `status = 'failed'`, lưu `failureCode` + `failureReason` để UI hiện.
   - **KHÔNG xoá** message khỏi cache — giữ để user retry hoặc delete thủ công.
10. **Client timeout 10s**:
    - Tìm message bằng `clientTempId === tempId`, check `status === 'sending'`.
    - Nếu vẫn sending → set `status = 'failed'`, `failureCode = 'TIMEOUT'`.
    - Nếu đã là `sent` (race: ACK đến trước khi timer cleanup) → skip.

> **BLOCKING check FE**: Timer PHẢI được clear trong CẢ 3 branch (ACK, ERROR, timeout expire). Leak timer → khi user gửi 100 message, có 100 timer pending → memory leak + false-positive timeout sau 10s nếu server chậm.

> **BLOCKING check FE**: Dedupe broadcast PHẢI dùng **real id** (sau ACK replace), không phải tempId. Nếu dedupe bằng tempId:
> - Broadcast đến trước ACK → chưa có real id trong cache → append duplicate.
> - Broadcast đến sau ACK → real id đã set → tempId không còn trong cache → append duplicate.
> Cả 2 case đều sai. Đúng: dedupe `some(m => m.id === broadcast.payload.id)` cross-all-pages của infinite cache.

### 3b.6 Interaction với REST endpoint cũ

| Tình huống | FE dùng path nào? |
|-----------|-------------------|
| User gõ tin text trong ConversationDetailPage | STOMP `/app/conv.{id}.message` (Path B) |
| User gửi file/ảnh | REST upload → REST `POST /messages` (vẫn giữ, ADR-014 path cũ cho non-text) — V1 acceptable |
| Bot API, batch import, admin tool | REST `POST /messages` với service token |
| STOMP chưa connect (mount page trước khi WS ready) | Disable input + spinner "Đang kết nối…" — KHÔNG fallback sang REST. Lý do: fallback tạo path phân mảnh khó debug. Chờ connect <3s acceptable. |
| STOMP mất kết nối khi đang gõ | Disable send button, show banner "Mất kết nối, đang thử lại…". Queue trong @stomp/stompjs tự replay khi reconnect. |

> **REST không deprecated hoàn toàn** — giữ để: (1) fallback khi STOMP bất khả dụng nhiều ngày (infra outage), (2) batch import từ CSV/migration tool, (3) bot tích hợp 3rd-party dùng HTTP không STOMP, (4) integration test dễ viết với REST hơn STOMP.

---

## 3c. Edit Message via STOMP — W5-D2

> **Thêm W5-D2**: Cho phép user sửa tin nhắn đã gửi trong cửa sổ 5 phút kể từ `createdAt`. Dùng STOMP send (không REST) để thống nhất transport với Path B và tận dụng dedup + ACK pipeline. Broadcast kết quả qua `MESSAGE_UPDATED` trên `/topic/conv.{convId}` (đã fill §3.2).

### 3c.1 Client → Server: `/app/conv.{convId}.edit`

**Destination**: `/app/conv.{convId}.edit`

**Auth**: STOMP `Principal` đã set ở CONNECT (xem mục 1). `AuthChannelInterceptor` enforce STRICT_MEMBER policy (§7.1) — non-member throw `MessageDeliveryException("FORBIDDEN")` → ERROR frame.

**Payload**:
```json
{
  "clientEditId": "uuid v4 (client-generated, dùng để dedup + ACK routing)",
  "messageId": "uuid (real server id — message đã tồn tại)",
  "newContent": "string (1-5000 chars, trim sau đó non-empty)"
}
```

> **W6-D1 note**: EDIT payload **KHÔNG có** `attachmentIds`. V1 chỉ cho phép sửa `content`, không sửa attachments. Nếu FE vô tình gửi thêm field → BE bỏ qua (không lỗi). Rationale: edit window 5 phút ngắn, user thường chỉ sửa typo; thay attachment cần re-upload + dedup cleanup phức tạp. V2 xem xét mở rộng. Message có attachments thì EDIT chỉ update `content` — attachments giữ nguyên. Nếu message **chỉ có attachments, không có content** (`content IS NULL`) thì EDIT với `newContent` non-empty sẽ set content — message sau edit có cả content và attachments. ACK trả full `MessageDto` với attachments không đổi (load lại từ DB).

### 3c.2 Validation rules (server-side)

1. **`clientEditId`**: bắt buộc, UUID v4 format. Reject `VALIDATION_FAILED` nếu sai.
2. **`messageId`**: bắt buộc, UUID format. Lookup DB — nếu không tồn tại HOẶC `message.conversationId != convId` HOẶC sender không phải owner HOẶC đã bị soft-delete (`deleted_at IS NOT NULL`) → trả `MSG_NOT_FOUND` (merge các case để anti-enumeration, giống pattern REST 404).
3. **Owner check**: `message.senderId == userId` (từ Principal). Merge với not-found → trả cùng `MSG_NOT_FOUND` — KHÔNG dùng `FORBIDDEN` để tránh tiết lộ "message tồn tại nhưng không phải của bạn".
4. **Edit window**: `now() - message.createdAt ≤ 300 giây` (5 phút). Vượt → `MSG_EDIT_WINDOW_EXPIRED`. Dùng clock server (UTC) làm authority; clock skew xử lý ở FE (xem mục 8 Limitations).
5. **`newContent`**: trim whitespace đầu cuối, sau đó:
   - Rỗng hoặc toàn whitespace → `VALIDATION_FAILED`.
   - `> 5000 chars` → `MSG_CONTENT_TOO_LONG`.
   - `== message.content` (sau khi trim cả 2) → `MSG_NO_CHANGE` (không waste DB write + broadcast cho no-op edit).
6. **Rate limit**: 10 edit/phút/user. Redis key `rate:msg-edit:{userId}` INCR + EX 60 (pattern ADR-005). Vượt → `MSG_RATE_LIMITED`.

> **Không cho phép edit** nếu message là `type != 'TEXT'` — IMAGE/FILE/SYSTEM edit không có nghĩa. Trả `VALIDATION_FAILED` với message rõ.

### 3c.3 Server → Client (sender-only): ACK — **unified `/user/queue/acks` với operation discriminator** (ADR-017)

**Destination**: `/user/queue/acks` (reuse cùng queue với SEND ACK — tránh proliferation queues).

**Payload**:
```json
{
  "operation": "EDIT",
  "clientId": "clientEditId (echo từ request)",
  "message": {
    "id": "uuid (messageId đã edit)",
    "conversationId": "uuid",
    "sender": { "id": "uuid", "username": "...", "fullName": "...", "avatarUrl": null },
    "type": "TEXT",
    "content": "new content (sau trim)",
    "replyToMessage": null,
    "editedAt": "2026-04-20T10:05:00Z",
    "createdAt": "2026-04-20T10:00:00Z"
  }
}
```

**Discriminator contract**:
- `operation`: `"SEND" | "EDIT"` (tương lai `"DELETE" | "REACT"` sẽ thêm). FE dùng `switch(operation)` để route handler.
- `clientId`: tên thống nhất cho `tempId` (SEND) hoặc `clientEditId` (EDIT). Là UUID v4 client sinh, server echo nguyên văn.
- `message`: MessageDto đầy đủ (shape IDENTICAL với REST response + broadcast payload của MESSAGE_CREATED, reuse `MessageMapper`). Với EDIT, `editedAt != null` và content là bản mới.

> **Migration note (ADR-017)**: Shape SEND ACK cũ là `{tempId, message}` (xem §3b.2) — sẽ migrate sang `{operation: "SEND", clientId: tempId, message}` cùng lúc khi implement EDIT. BE + FE phải deploy đồng bộ (breaking change cho Path B hiện tại). Xem changelog v1.3-w5d2 ở cuối file.

### 3c.4 Server → Client (sender-only): ERROR — **unified `/user/queue/errors` với operation discriminator**

**Destination**: `/user/queue/errors`

**Payload**:
```json
{
  "operation": "EDIT",
  "clientId": "clientEditId (echo từ request)",
  "error": "string (human-readable, có thể i18n sau)",
  "code": "MSG_NOT_FOUND | MSG_EDIT_WINDOW_EXPIRED | MSG_NO_CHANGE | MSG_CONTENT_TOO_LONG | VALIDATION_FAILED | MSG_RATE_LIMITED | AUTH_REQUIRED | INTERNAL"
}
```

**Error code table**:

| code | Điều kiện | FE action |
|------|-----------|-----------|
| `MSG_NOT_FOUND` | messageId không tồn tại / không thuộc conv / không phải owner / đã soft-delete | Mark edit = failed, hiện "Tin nhắn không tồn tại hoặc không thể sửa". Revert UI về content cũ. |
| `MSG_EDIT_WINDOW_EXPIRED` | `createdAt` cách now > 5 phút | Mark failed, hiện "Đã hết thời gian sửa (5 phút)". Revert + disable nút Edit. |
| `MSG_NO_CHANGE` | `newContent.trim() == message.content.trim()` | Mark failed nhẹ (no toast), revert UI về idle. Không xem là lỗi nghiêm trọng. |
| `MSG_CONTENT_TOO_LONG` | `newContent > 5000 chars` sau trim | Mark failed, hiện "Nội dung quá dài (tối đa 5000 ký tự)". Giữ content đang edit để user sửa. |
| `VALIDATION_FAILED` | clientEditId/messageId malformed, content rỗng/whitespace, message type != TEXT | Mark failed, hiện message cụ thể. Không retry. |
| `MSG_RATE_LIMITED` | Vượt 10 edit/phút | Mark failed, hiện "Sửa quá nhanh, thử lại sau N giây". FE backoff theo `retryAfterSeconds` (nếu BE gửi kèm trong `error`). |
| `AUTH_REQUIRED` | Principal null giữa chừng | Trigger refresh flow → reconnect. |
| `INTERNAL` | DB down, NPE, unexpected | Mark failed, hiện "Lỗi server, thử lại sau". Cho phép user retry thủ công. |

> **Dual-delivery invariant**: mỗi request `/app/conv.{id}.edit` → CHÍNH XÁC 1 response (ACK hoặc ERROR). Client timeout 10s dựa trên invariant này.

### 3c.5 Dedup Strategy (server-side, Redis)

- **Key**: `msg:edit-dedup:{userId}:{clientEditId}`
- **TTL**: 60 giây (giống send — chống network retry trong window ngắn).
- **Value**: `messageId` (echo cho ACK retry mà không cần re-query DB).

**Flow** (giống §3b.4 nhưng cho operation EDIT):
1. BE nhận STOMP frame từ `/app/conv.{id}.edit`.
2. Validate payload shape (tempId/clientEditId + messageId UUID + content length).
3. Authorize member (interceptor đã check STRICT_MEMBER ở SEND; service-level cũng kiểm tra lại — defense in depth).
4. `SET msg:edit-dedup:{userId}:{clientEditId} <placeholder> NX EX 60` atomic:
   - Nếu return 0 (duplicate): GET value → nếu != "PENDING" → load Message từ DB, map DTO, re-send ACK. Nếu == "PENDING" → log WARN + silent drop (client sẽ timeout retry với clientEditId MỚI).
5. Nếu return 1 (mới): chạy validation nghiệp vụ (owner check, edit window, no-change, rate limit) → update `message.content` + `message.editedAt = now()` trong `@Transactional` → update dedup value với `messageId` (TTL giữ 60s) → publish `MessageUpdatedEvent` → ACK trong `afterCommit`.

> **BLOCKING checks** (giống 3b.4):
> - `SET NX EX` atomic — không tách SETNX + EXPIRE.
> - Dedup TRƯỚC transaction DB (không sau).
> - ACK + broadcast trong `afterCommit` để không bay trước rollback.

### 3c.6 FE State Machine (edit lifecycle)

Mỗi message có thêm field `editState: 'idle' | 'editing' | 'saving' | 'error'` (ngoài `status` đã có cho send).

```
  idle
    │ click "Edit" button
    ▼
  editing                    (user đang gõ trong textarea inline)
    │ click "Save"
    │ generate clientEditId = UUID v4
    │ publish /app/conv.{id}.edit
    │ start 10s timer
    ▼
  saving
    │
    ├── ACK received (operation="EDIT", clientId matches)
    │      │
    │      ▼
    │    saved → idle       (update content + editedAt, clear clientEditId)
    │
    ├── ERROR received
    │      │
    │      ▼
    │    error              (keep editing textarea content, show code + message)
    │      │
    │      ├── click retry → saving (new clientEditId, republish)
    │      └── click cancel → idle  (revert to original content)
    │
    └── 10s timeout
           │
           ▼
         error (failureCode = "TIMEOUT")
```

**Chi tiết từng bước**:
1. **Click Edit**: set `editState = 'editing'`, clone `message.content` vào local `draftContent`.
2. **Click Save**: generate `clientEditId = crypto.randomUUID()`, publish frame `{clientEditId, messageId, newContent: draftContent}`, set `editState = 'saving'`, start 10s timer. KHÔNG update UI content ngay (chờ ACK) — tránh flash nếu server reject.
3. **ACK received**: clear timer, update message fields (`content`, `editedAt`) từ `ack.message`, clear `clientEditId`, set `editState = 'idle'`.
4. **ERROR received**: clear timer, set `editState = 'error'`, lưu `failureCode + failureReason`. UI hiện inline error trên textarea, giữ `draftContent` để user sửa tiếp hoặc cancel.
5. **Timeout 10s**: set `editState = 'error'` với `failureCode = 'TIMEOUT'`, message "Server không phản hồi".
6. **Cancel**: discard `draftContent`, set `editState = 'idle'`, content về bản gốc.

> **BLOCKING check FE**:
> - Timer PHẢI clear trong CẢ 3 branch (ACK, ERROR, timeout) — leak timer khi user edit 10 lần liên tiếp = 10 timer pending.
> - Routing ACK/ERROR PHẢI dựa trên `operation` field (ADR-017). Không gộp chung với SEND ACK handler — sẽ patch sai cache (ví dụ replace tempId message bằng edit message).
> - `clientEditId` trong ACK PHẢI match với client's current edit session. Nếu user đã cancel nhưng ACK về muộn → skip (editState != 'saving').

### 3c.7 Broadcast — `MESSAGE_UPDATED` (reuse §3.2)

Spec đầy đủ tại §3.2. Tóm tắt flow BE:

1. Service `editMessage` update DB thành công trong `@Transactional`.
2. Publish `MessageUpdatedEvent(convId, messageDto)`.
3. `MessageBroadcaster` `@TransactionalEventListener(AFTER_COMMIT)` → `convertAndSend("/topic/conv.{convId}", {type: "MESSAGE_UPDATED", payload: {id, conversationId, content, editedAt}})`.

**Payload shape** (Broadcast): minimal — chỉ fields thay đổi.
```json
{
  "type": "MESSAGE_UPDATED",
  "payload": {
    "id": "uuid",
    "conversationId": "uuid",
    "content": "new content",
    "editedAt": "ISO8601"
  }
}
```

Lý do payload minimal (không phải MessageDto đầy đủ): receiver đã có message trong cache — chỉ cần update 2 field thay đổi. Giảm payload size cho group lớn.

**FE dedupe invariant**: nếu `broadcast.editedAt <= cache.editedAt` → skip (có thể là broadcast cũ đến sau edit mới của chính user — rare nhưng có thể với 2 tab).

### 3c.8 Interaction với các tab khác của cùng user

- **Sender tab khác**: nhận MESSAGE_UPDATED broadcast (vì subscribe cùng `/topic/conv.{id}`), update cache qua handler MESSAGE_UPDATED (§3.2). KHÔNG nhận ACK vì ACK là user-queue session-scoped? **Caveat**: Spring `convertAndSendToUser` gửi tới TẤT CẢ sessions của user đó → cả tab A và tab B đều nhận ACK. Tab B không có session edit active → route handler check `clientEditId not in activeEditSessions` → ignore ACK. Đây là lý do FE cần module-level `editTimerRegistry` (tương tự `timerRegistry` cho send): tab A store `clientEditId`, tab B không có → tab B bỏ qua ACK.
- **Receiver (user khác)**: chỉ nhận broadcast MESSAGE_UPDATED, không nhận ACK (queue khác user). Update UI bình thường qua handler §3.2.

---

## 3d. Delete Message via STOMP — W5-D3

> **Thêm W5-D3**: Cho phép user xoá tin nhắn của chính mình **không giới hạn thời gian** (khác Edit 5 phút — xem ADR-018). Soft delete: set `deleted_at + deleted_by`, giữ row trong DB để bảo toàn lịch sử + unread count + reply-to reference. BE strip `content=null` khi serialize nếu `deletedAt != null` (áp dụng cho cả REST và WS). Broadcast `MESSAGE_DELETED` qua `/topic/conv.{convId}` (§3.3).

### 3d.1 Client → Server: `/app/conv.{convId}.delete`

**Destination**: `/app/conv.{convId}.delete`

**Auth**: STOMP `Principal` set ở CONNECT. `AuthChannelInterceptor` enforce `STRICT_MEMBER` (§7.1) — non-member throw `MessageDeliveryException("FORBIDDEN")` → ERROR frame.

**Payload**:
```json
{
  "clientDeleteId": "uuid v4 (client-generated, dùng để dedup + ACK routing)",
  "messageId": "uuid (real server id — message đã tồn tại)"
}
```

### 3d.2 Validation rules (server-side)

1. **`clientDeleteId`**: bắt buộc, UUID v4 format. Reject `VALIDATION_FAILED` nếu sai.
2. **`messageId`**: bắt buộc, UUID format. Lookup DB — nếu **null HOẶC `message.conversationId != convId` HOẶC sender không phải owner (`message.senderId != userId`) HOẶC đã bị soft-delete (`deletedAt != null`)** → merge tất cả thành **`MSG_NOT_FOUND`** (anti-enumeration, cùng pattern EDIT §3c.2 — không dùng `FORBIDDEN` để không tiết lộ "message tồn tại nhưng không phải của bạn").
3. **KHÔNG có edit-window check**: khác EDIT (5 phút), DELETE không giới hạn thời gian — user có quyền xoá lịch sử của chính mình bất kỳ lúc nào (xem ADR-018).
4. **Rate limit**: 10 delete/phút/user. Redis key `rate:msg-delete:{userId}` INCR + EX 60 (pattern ADR-005). Vượt → `MSG_RATE_LIMITED`.

> **Không check `type != TEXT`** như EDIT — IMAGE/FILE/SYSTEM đều xoá được. Owner có quyền xoá mọi loại tin nhắn mình đã gửi. SYSTEM messages do server tự phát → sender là system user, user thường không thể delete (vì owner check fail → `MSG_NOT_FOUND`).

### 3d.3 Server → Client (sender-only): ACK — unified `/user/queue/acks` (ADR-017)

**Destination**: `/user/queue/acks` (reuse cùng queue với SEND + EDIT).

**Payload**:
```json
{
  "operation": "DELETE",
  "clientId": "clientDeleteId (echo từ request)",
  "message": {
    "id": "uuid",
    "conversationId": "uuid",
    "deletedAt": "ISO8601",
    "deletedBy": "uuid"
  }
}
```

**Discriminator contract**:
- `operation`: `"DELETE"`. FE dùng `switch(operation)` để route handler.
- `clientId`: echo `clientDeleteId` nguyên văn.
- `message`: **metadata minimal** — chỉ `id + conversationId + deletedAt + deletedBy`. **KHÔNG có `content`, `sender`, `type`, `createdAt`, `replyToMessage`, `editedAt`**. Lý do: (1) sau delete, content đã nil; (2) FE đã có message trong cache, chỉ cần patch 2 field `deletedAt + deletedBy + content=null`; (3) giảm payload size + tránh leak content đã xoá qua ACK.

> **Khác với EDIT ACK**: EDIT trả full `MessageDto` (vì content mới cần sync đầy đủ). DELETE trả metadata — nhỏ hơn, đủ cho FE patch.

### 3d.4 Server → Client (sender-only): ERROR — unified `/user/queue/errors`

**Destination**: `/user/queue/errors`

**Payload**:
```json
{
  "operation": "DELETE",
  "clientId": "clientDeleteId (echo từ request)",
  "error": "string (human-readable)",
  "code": "MSG_NOT_FOUND | MSG_RATE_LIMITED | VALIDATION_FAILED | AUTH_REQUIRED | INTERNAL"
}
```

**Error code table**:

| code | Điều kiện | FE action |
|------|-----------|-----------|
| `MSG_NOT_FOUND` | messageId không tồn tại / không thuộc conv / không phải owner / đã soft-delete | Mark delete = failed, hiện "Tin nhắn không tồn tại hoặc không thể xoá". Giữ nguyên message trong UI (không thay đổi). |
| `VALIDATION_FAILED` | clientDeleteId/messageId malformed | Mark failed, hiện message cụ thể. Không retry auto. |
| `MSG_RATE_LIMITED` | Vượt 10 delete/phút | Mark failed, hiện "Xoá quá nhanh, thử lại sau N giây". FE backoff theo `retryAfterSeconds`. |
| `AUTH_REQUIRED` | Principal null giữa chừng | Trigger refresh flow → reconnect. |
| `INTERNAL` | DB down, NPE, unexpected | Mark failed, hiện "Lỗi server, thử lại sau". Cho phép user retry thủ công. |

> **Dual-delivery invariant**: mỗi request `/app/conv.{id}.delete` → CHÍNH XÁC 1 response (ACK hoặc ERROR). Client timeout 10s dựa trên invariant này.

### 3d.5 Dedup Strategy (server-side, Redis)

- **Key**: `msg:delete-dedup:{userId}:{clientDeleteId}`
- **TTL**: 60 giây (giống send + edit — chống network retry trong window ngắn).
- **Value**: `messageId` (echo cho ACK retry mà không cần re-query DB).

**Flow** (giống §3c.5 nhưng cho operation DELETE):
1. BE nhận STOMP frame từ `/app/conv.{id}.delete`.
2. Validate payload shape (clientDeleteId + messageId UUID).
3. Authorize member (interceptor đã check STRICT_MEMBER ở SEND; service-level cũng kiểm tra lại — defense in depth).
4. `SET msg:delete-dedup:{userId}:{clientDeleteId} <placeholder> NX EX 60` atomic:
   - Nếu return 0 (duplicate): GET value → nếu != "PENDING" → load Message từ DB, map DeleteAckDto minimal, re-send ACK. Nếu == "PENDING" → log WARN + silent drop (client sẽ timeout retry với clientDeleteId MỚI).
5. Nếu return 1 (mới): chạy validation nghiệp vụ (owner check + not-found merge + rate limit) → update `message.deletedAt = now()` + `message.deletedBy = userId` trong `@Transactional` → update dedup value với `messageId` (TTL giữ 60s) → publish `MessageDeletedEvent` → ACK trong `afterCommit`.

> **BLOCKING checks** (giống §3b.4 và §3c.5):
> - `SET NX EX` atomic — không tách SETNX + EXPIRE.
> - Dedup TRƯỚC transaction DB (không sau).
> - ACK + broadcast trong `afterCommit` để không bay trước rollback.

### 3d.6 Interaction với EDIT và REPLY

- **Edit attempt sau khi đã delete**: user ở tab A delete message X. Tab A (hoặc tab B của cùng user) vẫn đang trong `editState='editing' | 'saving'` cho X → trước khi submit Save, MESSAGE_DELETED broadcast về → FE exit edit mode silently (xem §3.3 FE action bước 4). Nếu submit `/app/conv.{id}.edit` sau đó → server check `deletedAt != null` → trả `MSG_NOT_FOUND` (anti-enum). FE handle error branch bình thường → `editState='error'` với failureCode `MSG_NOT_FOUND`.
- **Reply-to deleted message**: message Y có `replyToMessage = { id: X, senderName, contentPreview }` (shallow 1-level snapshot). Khi X bị delete, Y **vẫn giữ nguyên** `replyToMessage` (snapshot không cascade). FE render:
  - Nếu FE có cache của message X gốc → check `cache[X].deletedAt != null` → hiện placeholder "🚫 Tin nhắn đã bị xoá" trong quote box.
  - Nếu FE không có X trong cache (scroll xa) → render `contentPreview` từ snapshot bình thường (FE không biết X đã bị xoá). V1 acceptable — tech debt V2 có thể BE trả thêm `replyToMessage.deleted: boolean` flag.

### 3d.7 FE Lifecycle (delete)

Tương tự edit nhưng đơn giản hơn (không có textarea, không có "cancel" sau khi submit):

```
  idle
    │ click "Delete" button (trong context menu)
    ▼
  confirm dialog                 (UI hỏi "Xoá tin nhắn này?")
    │ click "Xoá"
    │ generate clientDeleteId = UUID v4
    │ publish /app/conv.{id}.delete
    │ start 10s timer
    │ (tuỳ chọn) optimistic: mark message.deleteStatus = 'deleting'
    ▼
  deleting
    │
    ├── ACK received (operation="DELETE", clientId matches)
    │      │
    │      ▼
    │    deleted → render placeholder     (patch deletedAt+deletedBy, content=null)
    │
    ├── ERROR received
    │      │
    │      ▼
    │    error              (revert nếu có optimistic, toast failure reason)
    │
    └── 10s timeout
           │
           ▼
         error (failureCode = "TIMEOUT", revert nếu có optimistic)
```

**FE khuyến nghị**: Giống Option A của edit (§3c.6) — **KHÔNG optimistic set `deletedAt` ngay**. Chỉ set flag UI `deleteStatus='deleting'` (disable actions + greyish loading indicator). ACK về → patch thật từ ACK. ERROR → clear flag, message giữ nguyên. Lý do: tránh cache lệch DB khi ERROR (MSG_NOT_FOUND, TIMEOUT); đơn giản hơn không cần lưu originalContent.

**Registry**: module-level `deleteTimerRegistry: Map<clientDeleteId, { timerId, messageId, convId }>`. Cleanup khi logout (gộp với `editTimerRegistry.clearAll()` thành `ackRegistry.clearAll()`).

---

## 3e. Deleted Message Rendering (FE contract)

Áp dụng cho mọi nơi render message (ConversationDetailPage, search results, reply quote box nếu parent deleted).

### Hiển thị
- **Bubble**: gray background (Tailwind `bg-gray-100 dark:bg-gray-800`), italic text, giảm opacity (`opacity-70`).
- **Nội dung cố định**: `"🚫 Tin nhắn đã bị xóa"` (hardcode V1, i18n V2). Biểu tượng 🚫 là 1 ký tự — có thể thay bằng SVG icon nếu design cần.
- **KHÔNG hiện `content` cũ** — FE PHẢI render placeholder kể cả khi cache client còn content cũ (trước khi broadcast về): sau khi patch `content=null`, component render `null/undefined` fallback → placeholder.
- **KHÔNG hover actions** (edit, reply, react, copy) — disable toàn bộ menu khi `message.deletedAt != null`.
- **Giữ sender avatar + timestamp** để bảo toàn dòng chảy chat. Timestamp hiển thị `createdAt` gốc (không phải `deletedAt`).

### Contract BE → FE
- **`MessageDto`** (cả REST response + broadcast MESSAGE_CREATED/UPDATED + ACK) PHẢI có 2 field mới:
  - `deletedAt: string | null` (ISO8601, `null` nếu chưa xoá).
  - `deletedBy: string | null` (UUID, `null` nếu chưa xoá).
- **BE mapper** (`MessageMapper.toDto`) PHẢI strip `content = null` khi `deletedAt != null`. Áp dụng nhất quán:
  - REST `GET /conversations/{id}/messages` — list cũ có message đã xoá → content null.
  - REST `POST /conversations/{id}/messages` response — sender không thể trả về đã xoá ngay sau create, nhưng giữ logic consistent.
  - STOMP broadcast `MESSAGE_CREATED` — không fire cho message đã xoá (delete sau create).
  - STOMP broadcast `MESSAGE_UPDATED` — edit flow đã reject `deletedAt != null` ở service (anti-enum → MSG_NOT_FOUND). Defense in depth: mapper vẫn strip.
  - STOMP ACK `{operation: SEND|EDIT}.message` — same rule.
- **Reply preview** (`replyToMessage.contentPreview`): V1 giữ nguyên snapshot (không strip). Tech debt V2 — xem §3d.6.

### FE dedupe / idempotency
- Khi nhận MESSAGE_DELETED broadcast nhiều lần cùng id (rare): FE set `deletedAt + deletedBy + content=null` lặp lại — idempotent, không cần check `deletedAt` đã set.
- Nếu REST list trả message đã có `deletedAt` thì FE render placeholder ngay (không cần chờ broadcast).

---

## 4. BE Implementation Guide — W4-D3

### 4.1 Spring Config (bắt buộc)

```java
// WebSocketConfig.java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue")
              .setHeartbeatValue(new long[]{10000, 10000})
              .setTaskScheduler(heartbeatScheduler());
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(allowedOrigins) // đọc từ config, KHÔNG "*"
            .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }
}
```

> **BLOCKING check cho reviewer**: `setAllowedOriginPatterns("*")` CHỈ được dùng ở dev profile. Production phải đọc từ `app.ws.allowed-origins` trong `application.yml`. Nếu thấy hardcode `"*"` trong code PR → REQUEST CHANGES.

### 4.2 Auth Interceptor (bắt buộc)

```java
@Component
@RequiredArgsConstructor
public class AuthChannelInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor =
            MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) return message;

        StompCommand cmd = accessor.getCommand();
        if (StompCommand.CONNECT.equals(cmd)) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                throw new MessageDeliveryException("AUTH_REQUIRED");
            }
            String token = authHeader.substring(7);
            TokenStatus status = jwtTokenProvider.validateTokenDetailed(token);
            if (status == TokenStatus.EXPIRED) {
                throw new MessageDeliveryException("AUTH_TOKEN_EXPIRED");
            }
            if (status != TokenStatus.VALID) {
                throw new MessageDeliveryException("AUTH_REQUIRED");
            }
            // TODO W5: check Redis blacklist jwt:blacklist:{jti} (fail-open — ADR-011)
            UUID userId = jwtTokenProvider.getUserIdFromToken(token);
            accessor.setUser(new StompPrincipal(userId.toString()));
        }

        if (StompCommand.SUBSCRIBE.equals(cmd)) {
            // Verify user is member of conversation if destination matches /topic/conv.{id}
            String dest = accessor.getDestination();
            if (dest != null && dest.startsWith("/topic/conv.")) {
                UUID convId = parseConvId(dest); // dùng try/catch InvalidUuidException
                UUID userId = UUID.fromString(accessor.getUser().getName());
                if (!conversationMemberRepository.existsByConvIdAndUserId(convId, userId)) {
                    throw new MessageDeliveryException("FORBIDDEN");
                }
            }
        }
        return message;
    }
}
```

> **BLOCKING check**: Member authorization PHẢI ở `SUBSCRIBE` interceptor, không chỉ dựa vào client tự chọn topic. SimpleBroker V1 không có destination-level ACL built-in.

### 4.3 Broadcast sau REST POST /messages (bắt buộc)

Trong `MessageService.sendMessage()`, **SAU khi `messageRepository.save(message)` trả về entity có id thật** và **SAU khi transaction commit thành công** (dùng `TransactionSynchronizationManager.registerSynchronization` hoặc `@TransactionalEventListener(phase = AFTER_COMMIT)`):

```java
// Pattern KHUYẾN NGHỊ: Event + AFTER_COMMIT listener
// MessageService.sendMessage()
Message saved = messageRepository.save(message);
MessageDto dto = messageMapper.toDto(saved);
eventPublisher.publishEvent(new MessageCreatedEvent(convId, dto));
return dto;

// MessageBroadcaster
@Component
public class MessageBroadcaster {
    private final SimpMessagingTemplate messagingTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(MessageCreatedEvent event) {
        messagingTemplate.convertAndSend(
            "/topic/conv." + event.convId(),
            Map.of("type", "MESSAGE_CREATED", "payload", event.messageDto())
        );
    }
}
```

> **BLOCKING check**:
> - KHÔNG broadcast **trước** commit — nếu transaction rollback sau broadcast, FE đã thấy message "ma" (xuất hiện rồi biến mất khi refetch).
> - KHÔNG gọi `messagingTemplate.convertAndSend` trực tiếp trong `@Transactional` method (sẽ broadcast trước commit).

### 4.4 Exception → ERROR frame mapping

BE implement `@MessageExceptionHandler` trên controller `ChatMessageHandler` (Path B — ADR-016):

```java
@Controller
public class ChatMessageHandler {

    @MessageMapping("/conv.{convId}.message")
    public void sendMessage(
        @DestinationVariable UUID convId,
        @Payload SendMessagePayload payload,
        Principal principal
    ) {
        UUID userId = UUID.fromString(principal.getName());
        // Service handles: validate, dedup (Redis SET NX EX), save, publishEvent, send ACK.
        messageService.sendViaStomp(convId, userId, payload);
    }

    @MessageExceptionHandler(AppException.class)
    public void handleAppException(AppException ex, @Header("tempId") String tempId, Principal principal) {
        // Gửi ERROR tới /user/queue/errors — chỉ sender nhận.
        messagingTemplate.convertAndSendToUser(
            principal.getName(),
            "/queue/errors",
            Map.of(
                "tempId", tempId,
                "error", ex.getMessage(),
                "code", ex.getErrorCode()
            )
        );
    }

    @MessageExceptionHandler(Exception.class)
    public void handleGeneric(Exception ex, @Header("tempId") String tempId, Principal principal) {
        log.error("[STOMP] Unexpected error sending message, userId={}, tempId={}", principal.getName(), tempId, ex);
        messagingTemplate.convertAndSendToUser(
            principal.getName(),
            "/queue/errors",
            Map.of("tempId", tempId, "error", "Lỗi server, thử lại sau", "code", "INTERNAL")
        );
    }
}
```

> **BLOCKING check**: `@MessageExceptionHandler` methods MUST echo `tempId` trong ERROR payload. Nếu không echo, FE không biết message nào bị fail → không thể mark `status=failed` cho đúng optimistic entry.

> **Lưu ý tempId extraction**: `tempId` nằm trong PAYLOAD (JSON body), không phải STOMP header. Nếu dùng `@Header("tempId")` sẽ null. Cách đúng: (1) extract tempId từ payload trong handler chính và propagate qua `AppException.getDetails()`, HOẶC (2) dùng `MessageHeaderAccessor` để đọc raw message body từ exception handler. Option (1) đơn giản hơn — recommended.

### 4.5 STOMP send-message handler + Redis dedup — Post-W4 (ADR-016)

**Service signature**:
```java
// MessageService.sendViaStomp (Path B)
@Transactional
public void sendViaStomp(UUID convId, UUID userId, SendMessagePayload payload) {
    // 1. Validate payload
    validateContent(payload.content()); // 1..5000, trimmed non-empty
    validateTempId(payload.tempId());   // UUID v4 format

    // 2. Authorize
    if (!conversationMemberRepository.existsByConvIdAndUserId(convId, userId)) {
        throw new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND", "Conversation không tồn tại");
    }

    // 3. Rate limit (Redis INCR rate:msg:{userId} EX 60, >30 → throw MSG_RATE_LIMITED)
    rateLimitService.checkMessageSendLimit(userId);

    // 4. Dedup — SETNX trước khi save
    String dedupKey = "msg:dedup:" + userId + ":" + payload.tempId();
    Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "PENDING", Duration.ofSeconds(60));
    if (Boolean.FALSE.equals(isNew)) {
        // Key đã tồn tại → retry hoặc duplicate frame
        String savedMessageId = redisTemplate.opsForValue().get(dedupKey);
        if ("PENDING".equals(savedMessageId)) {
            // Request trước chưa save xong — ignore silently, client sẽ timeout và retry
            log.warn("[DEDUP] Concurrent frame for tempId={}, userId={} — dropping", payload.tempId(), userId);
            return;
        }
        // Re-send ACK cho message đã save
        Message existing = messageRepository.findById(UUID.fromString(savedMessageId))
            .orElseThrow(() -> new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL", "Dedup key mất consistency"));
        MessageDto dto = messageMapper.toDto(existing);
        sendAck(userId, payload.tempId(), dto);
        return;
    }

    // 5. Save DB
    Message saved = messageRepository.save(Message.builder()
        .conversationId(convId).senderId(userId)
        .type(MessageType.TEXT).content(payload.content().trim())
        .build());

    // 6. Update dedup value với real id (TTL giữ nguyên 60s)
    redisTemplate.opsForValue().set(dedupKey, saved.getId().toString(), Duration.ofSeconds(60));

    // 7. Publish event — listener ở AFTER_COMMIT sẽ broadcast /topic/conv.{id}
    MessageDto dto = messageMapper.toDto(saved);
    eventPublisher.publishEvent(new MessageCreatedEvent(convId, dto));

    // 8. Gửi ACK — AFTER_COMMIT để tránh ACK trước rollback
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            sendAck(userId, payload.tempId(), dto);
        }
    });
}

private void sendAck(UUID userId, String tempId, MessageDto message) {
    messagingTemplate.convertAndSendToUser(
        userId.toString(),
        "/queue/acks",
        Map.of("tempId", tempId, "message", message)
    );
}
```

> **BLOCKING checks**:
> - Dedup `setIfAbsent` PHẢI có TTL atomic (dùng `Duration` param, không SETNX rồi EXPIRE riêng → race).
> - Dedup PHẢI chạy TRƯỚC save DB (không sau — race 2 frame cùng pass).
> - ACK PHẢI gửi ở `afterCommit` (không trong `@Transactional` body) — cùng lý do với broadcast: tránh ACK trước rollback → client nhận ACK rồi message biến mất khỏi DB.
> - Rate limit counter dùng key riêng `rate:msg:{userId}` — không trộn với dedup key.
> - Error throw trong `@Transactional` → rollback + `@MessageExceptionHandler` gửi ERROR frame → client nhận ERROR (không nhận ACK).

### 4.6 Rate limit cho STOMP send

- **Quota**: 30 messages/phút/user (khớp REST `POST /messages` rate limit — user không lách qua path khác).
- **Redis key**: `rate:msg:{userId}`, INCR + EXPIRE 60s lần đầu.
- **Vượt quota** → throw `AppException(HttpStatus.TOO_MANY_REQUESTS, "MSG_RATE_LIMITED", ...)` → `@MessageExceptionHandler` convert sang ERROR frame.
- **Optional**: kèm `retryAfterSeconds` (từ `redisTemplate.getExpire(key)`) vào payload ERROR để FE show countdown.

---

## 5. FE Implementation Guide — W4-D4

### 5.1 Setup STOMP client (singleton)

```typescript
// src/lib/stompClient.ts
import { Client, IFrame } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { tokenStorage } from './tokenStorage';

let clientInstance: Client | null = null;

export function getStompClient(): Client {
  if (clientInstance) return clientInstance;

  clientInstance = new Client({
    webSocketFactory: () => new SockJS('/ws'),
    connectHeaders: { Authorization: `Bearer ${tokenStorage.getAccessToken() ?? ''}` },
    beforeConnect: () => {
      // Refresh token if expired BEFORE connect (không để CONNECT fail rồi mới refresh)
      const token = tokenStorage.getAccessToken();
      if (!token) throw new Error('No access token');
      clientInstance!.connectHeaders = { Authorization: `Bearer ${token}` };
    },
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    reconnectDelay: 1000, // @stomp/stompjs exponential backoff is built-in when > 0
    onStompError: (frame: IFrame) => {
      const errorCode = frame.headers['message'];
      if (errorCode === 'AUTH_TOKEN_EXPIRED' || errorCode === 'AUTH_REQUIRED') {
        // Trigger refresh flow; deactivate until new token ready
        clientInstance?.deactivate();
        // ... refresh then reactivate
      }
    },
  });
  return clientInstance;
}
```

### 5.2 Subscribe + dedupe (hook chuẩn)

```typescript
// src/features/messages/useConvSubscription.ts
export function useConvSubscription(convId: string | undefined) {
  const queryClient = useQueryClient();
  const client = getStompClient();

  useEffect(() => {
    if (!convId || !client.connected) return;

    const sub = client.subscribe(`/topic/conv.${convId}`, (frame) => {
      const event = JSON.parse(frame.body) as { type: string; payload: unknown };

      if (event.type === 'MESSAGE_CREATED') {
        const newMsg = event.payload as MessageDto;

        queryClient.setQueryData(
          messageKeys.list(convId),
          (old: InfiniteData<MessagePage> | undefined) => {
            if (!old) return old;
            const allIds = old.pages.flatMap(p => p.items.map(m => m.id));
            if (allIds.includes(newMsg.id)) return old; // DEDUPE
            const lastPageIdx = old.pages.length - 1;
            const pages = [...old.pages];
            pages[lastPageIdx] = {
              ...pages[lastPageIdx],
              items: [...pages[lastPageIdx].items, newMsg],
            };
            return { ...old, pages };
          }
        );
        queryClient.invalidateQueries({ queryKey: ['conversations'] });
      }
    });

    return () => sub.unsubscribe();
  }, [convId, client.connected]);
}
```

> **BLOCKING check FE**:
> - Subscription PHẢI `unsubscribe()` trong cleanup. Không cleanup = leak + duplicate message handlers khi navigate between conversations.
> - Dependency array PHẢI có `client.connected`. Nếu chỉ `[convId]`, hook chạy khi chưa connect → subscribe silently fail.

### 5.3 Reconnect + catch-up flow

Sau khi STOMP reconnect thành công:
1. Re-subscribe tất cả active `/topic/conv.{id}` (1 cho conversation đang mở).
2. Re-subscribe 2 user queue: `/user/queue/acks` và `/user/queue/errors` (Path B, ADR-016).
3. Gọi REST `GET /api/conversations/{id}/messages?cursor=<latestKnownCreatedAt>` để lấy messages đã miss trong lúc offline.
4. Merge vào React Query cache với dedupe logic tương tự mục 5.2.
5. Các message FE đã enqueue qua `client.publish()` trước khi disconnect: @stomp/stompjs tự replay sau reconnect. Client timer 10s đã bắt đầu từ lúc publish — nếu reconnect > 10s, message sẽ bị mark `failed` mặc dù server có thể nhận được sau. FE phải cleanup timer khi nhận ACK muộn (branch "ACK đến sau khi đã failed" — edge case documented trong mục 3b.5 dưới).
6. KHÔNG re-play events từ SimpleBroker (broker không buffer offline subscribers — xem mục 8).

### 5.4 Path B — `useSendMessage` mutation (ADR-016)

**Target**: thay thế hook REST `useSendMessage` hiện tại (POST `/api/conversations/{id}/messages`) bằng STOMP publish. Signature giữ tương đương để ConversationDetailPage gọi như cũ.

```typescript
// src/features/messages/useSendMessage.ts
import { useQueryClient } from '@tanstack/react-query';
import { useCallback } from 'react';
import { getStompClient } from '@/lib/stompClient';
import { messageKeys } from './keys';
import type { MessageDto, OptimisticMessage } from './types';

const SEND_TIMEOUT_MS = 10_000;

export function useSendMessage(convId: string | undefined) {
  const queryClient = useQueryClient();
  const client = getStompClient();

  return useCallback((content: string): { tempId: string } => {
    if (!convId) throw new Error('convId required');
    if (!client.connected) throw new Error('STOMP not connected');

    const tempId = crypto.randomUUID();
    const optimistic: OptimisticMessage = {
      id: tempId,
      clientTempId: tempId,
      conversationId: convId,
      sender: { /* current user from authStore */ },
      type: 'TEXT',
      content,
      replyToMessage: null,
      editedAt: null,
      createdAt: new Date().toISOString(),
      status: 'sending',
    };

    // 1. Optimistic append
    queryClient.setQueryData(
      messageKeys.list(convId),
      (old) => appendToInfiniteCache(old, optimistic)
    );

    // 2. Publish STOMP frame
    client.publish({
      destination: `/app/conv.${convId}.message`,
      body: JSON.stringify({ tempId, content, type: 'TEXT' }),
    });

    // 3. Start timeout timer
    const timerId = setTimeout(() => {
      queryClient.setQueryData(messageKeys.list(convId), (old) =>
        patchMessageByTempId(old, tempId, {
          status: 'failed',
          failureCode: 'TIMEOUT',
          failureReason: 'Server không phản hồi sau 10 giây',
        })
      );
      timerRegistry.delete(tempId);
    }, SEND_TIMEOUT_MS);

    timerRegistry.set(tempId, timerId);
    return { tempId };
  }, [convId, client, queryClient]);
}
```

### 5.5 Path B — `useAckErrorSubscription` (global, mount 1 lần)

Subscribe 2 user queue `/user/queue/acks` + `/user/queue/errors` **ở app root** (sau khi user login + STOMP connected). KHÔNG mount per-conversation vì user queue là global — mỗi session chỉ 1 subscription.

```typescript
// src/features/messages/useAckErrorSubscription.ts
export function useAckErrorSubscription() {
  const queryClient = useQueryClient();
  const client = getStompClient();

  useEffect(() => {
    if (!client.connected) return;
    let ackSub: StompSubscription | null = null;
    let errSub: StompSubscription | null = null;

    const subscribe = () => {
      ackSub = client.subscribe('/user/queue/acks', (frame) => {
        const { tempId, message } = JSON.parse(frame.body) as {
          tempId: string; message: MessageDto;
        };
        // Clear timer
        const timer = timerRegistry.get(tempId);
        if (timer) { clearTimeout(timer); timerRegistry.delete(tempId); }
        // Replace optimistic trong cache của conv tương ứng
        queryClient.setQueryData(
          messageKeys.list(message.conversationId),
          (old) => replaceTempIdWithReal(old, tempId, message)
        );
        // Invalidate conversations list để sidebar refresh lastMessageAt
        queryClient.invalidateQueries({ queryKey: ['conversations'] });
      });

      errSub = client.subscribe('/user/queue/errors', (frame) => {
        const { tempId, error, code } = JSON.parse(frame.body) as {
          tempId: string; error: string; code: string;
        };
        const timer = timerRegistry.get(tempId);
        if (timer) { clearTimeout(timer); timerRegistry.delete(tempId); }
        // Mark failed — phải tìm trong TẤT CẢ conv (không biết convId từ error)
        // → duyệt tất cả messageKeys.list() queries và patch match tempId
        markFailedAcrossAllConvs(queryClient, tempId, error, code);

        if (code === 'AUTH_REQUIRED' || code === 'AUTH_TOKEN_EXPIRED') {
          // Trigger refresh flow
          triggerRefreshAndReconnect();
        }
      });
    };

    subscribe();
    // Re-subscribe on reconnect
    const unsubState = onConnectionStateChange((state) => {
      if (state === 'CONNECTED') {
        ackSub?.unsubscribe();
        errSub?.unsubscribe();
        subscribe();
      }
    });

    return () => {
      ackSub?.unsubscribe();
      errSub?.unsubscribe();
      unsubState();
    };
  }, [client, queryClient]);
}
```

> **BLOCKING check FE**:
> - `timerRegistry` PHẢI là singleton (module-level Map<tempId, timerId>). Không lưu trong state/ref của component vì ACK subscription là global — component phát tempId có thể đã unmount khi ACK về.
> - Error handler không biết `convId` → phải scan all cached `messageKeys.list()` queries để find & patch. Cân nhắc lưu `tempId → convId` map trong `timerRegistry.set(tempId, { timerId, convId })` để tra O(1).
> - Nếu user logout → PHẢI clear toàn bộ `timerRegistry` + unsubscribe 2 queue, tránh ACK bay đến sau khi đã logout (rare nhưng có thể xảy ra với in-flight message).

### 5.6 Path B — Mount point

- `useAckErrorSubscription()` mount ở `App.tsx` (hoặc root layout authenticated). Chạy 1 lần cho cả session.
- `useConvSubscription(convId)` giữ nguyên mount per-page như W4D4 — đây là topic-level subscription, cần per-convId.
- `useSendMessage(convId)` dùng trong `ConversationDetailPage` / `MessageInput`, gọi khi user submit.

### 5.7 Path B — Retry flow

Khi message status = `failed`, UI hiện nút "Thử lại". Click retry:
1. Generate **tempId MỚI** (UUID v4). KHÔNG reuse tempId cũ.
2. Remove message cũ khỏi cache (hoặc mark `status = 'retrying'` tạm thời).
3. Gọi lại `sendMessage(content)` như lần đầu — flow từ bước 1 của mục 5.4.

> **Tại sao không reuse tempId?**: Server dedup key `msg:dedup:{userId}:{tempId}` TTL 60s. Nếu:
> - Key còn → server trả ACK với message đã save lần trước (nếu có) → nhưng FE đã mark failed và UI đã hiện retry → confusion.
> - Key expired → server save mới → duplicate message trong DB.
> - **Đúng**: luôn tempId mới. Server dedup chỉ để chống network retry trong window 60s, không phải user-initiated retry.

---

## 6. Error Handling

### STOMP ERROR frame headers

BE gửi STOMP ERROR frame với header `message` chứa error code. FE map theo bảng:

| `message` header | Mô tả | FE action |
|-----------------|-------|-----------|
| `AUTH_REQUIRED` | Token missing/invalid | Clear auth state, redirect `/login` |
| `AUTH_TOKEN_EXPIRED` | Access token hết hạn | Call `/auth/refresh` → reactivate STOMP |
| `FORBIDDEN` | Không phải member conversation | Unsubscribe, toast "Bạn không có quyền truy cập" |
| `RATE_LIMITED` | Gửi quá nhanh (W5+) | Backoff, retry sau `retryAfterMs` |
| `SERVER_ERROR` | Lỗi BE không xác định | Log, để auto-reconnect xử lý |

> FE PHẢI có handler cho **AUTH_TOKEN_EXPIRED** riêng khỏi AUTH_REQUIRED — cái đầu là refresh, cái sau là logout. Nếu FE nhầm 2 cái này → user bị đá ra login khi chỉ cần refresh.

---

## 7. Security Notes

- **Broker authorization**: SimpleBroker V1 KHÔNG enforce destination-level auth built-in → PHẢI custom trong `AuthChannelInterceptor` SUBSCRIBE. Tuần 5-6 đánh giá migrate RabbitMQ nếu cần persistent + better ACL.
- **Subscribe authorization**: check member khi SUBSCRIBE là đủ cho V1. Không re-check mỗi message (message đã filter qua topic member).
- **Message size**: max 64KB mỗi STOMP frame (cấu hình `setMessageSizeLimit(64 * 1024)` trong `configureWebSocketTransport`). Chặn DoS qua payload lớn.
- **Origin check**: `setAllowedOriginPatterns` lấy từ config, KHÔNG "*" ở production. Lock down trước khi deploy.
- **Same JWT validation logic REST và WS**: reuse `JwtTokenProvider.validateTokenDetailed()` — không viết logic verify riêng (nhân bản → drift).
- **Heartbeat enforcement**: nếu client không heartbeat trong 2 interval (20s) → BE disconnect. Ngăn connection "zombie" sau network partition.

### 7.1 Destination Policy Table (SEND inbound — W5-D1)

`AuthChannelInterceptor.handleSend()` áp dụng policy sau cho từng destination:

| Destination | STOMP Command | Policy | Non-member behavior |
|---|---|---|---|
| `/app/conv.{id}.message` | SEND | `STRICT_MEMBER` | `MessageDeliveryException("FORBIDDEN")` → ERROR frame `/user/queue/errors` `{code: FORBIDDEN}` |
| `/app/conv.{id}.edit` | SEND | `STRICT_MEMBER` | `MessageDeliveryException("FORBIDDEN")` → ERROR `{operation:"EDIT", code:"FORBIDDEN"}` |
| `/app/conv.{id}.delete` | SEND | `STRICT_MEMBER` | `MessageDeliveryException("FORBIDDEN")` → ERROR `{operation:"DELETE", code:"FORBIDDEN"}` |
| `/app/conv.{id}.typing` | SEND | `SILENT_DROP` | Frame accepted by interceptor, `ChatTypingHandler` silent-drops non-member |
| `/app/conv.{id}.read` | SEND | `SILENT_DROP` | (Tuần 5) Frame accepted, handler xử lý |
| `/app/conv.{id}.<unknown>` | SEND | `STRICT_MEMBER` (default safe) | `MessageDeliveryException("FORBIDDEN")` |
| `/topic/conv.{id}` | SUBSCRIBE | `STRICT_MEMBER` | `MessageDeliveryException("FORBIDDEN")` → STOMP ERROR frame |

**Lý do phân tách policy**:
- `.message` là persistent event — non-member gửi phải nhận ERROR rõ ràng để FE mark `status=failed`.
- `.typing` / `.read` là ephemeral events — throw FORBIDDEN tạo noise trong log + bad UX. Handler tự silent-drop là đủ. Interceptor không cần DB query cho những event này.
- Default `STRICT_MEMBER` cho unknown suffix đảm bảo forward-compatible security khi thêm destination mới.

---

## 8. Known Limitations (V1)

| Limitation | Impact | Workaround V1 | Fix khi nào |
|-----------|--------|---------------|------------|
| SimpleBroker (in-memory) | Mất messages nếu BE restart | FE catch-up qua REST sau reconnect | Tuần 5-6: đánh giá RabbitMQ |
| Không persistent subscription | Offline user miss broadcast | REST `GET /messages?cursor=...` khi reconnect | Acceptable V1 |
| Không delivery guarantee (at-most-once) | Message có thể không tới nếu WS disconnect đúng khoảnh khắc broadcast | DB là source-of-truth; FE polling catch-up | Tuần 5: đánh giá ACK pattern hoặc RabbitMQ |
| Member check chỉ khi SUBSCRIBE | User bị kick khỏi conv vẫn nhận message cho đến khi client unsubscribe | Kick flow force unsubscribe qua notification event | Tuần 6 khi implement kick |
| Single-instance BE | Không scale horizontal | Acceptable (ARCHITECTURE.md giả định 1 server Singapore) | V2 |
| ~~Không có ACK ở W4~~ (superseded) | ~~Nếu broadcast fail, sender không biết~~ | Path B (ADR-016) thêm ACK/ERROR qua `/user/queue/*` | Done post-W4 |
| Dedup key TTL = 60s | Retry sau 60s sẽ tạo message duplicate | FE tempId mới mỗi retry — không reuse | Acceptable V1 |
| Client timeout cố định 10s | Request slow hợp lệ có thể bị mark failed | FE retry tempId mới — server dedup chống duplicate trong 60s | Acceptable V1 |
| `@TransactionalEventListener(AFTER_COMMIT)` không re-fire khi listener crash | Broadcast có thể miss nếu broker thread chết | Try-catch toàn bộ trong listener, log lỗi nhưng không propagate (REST 201 / ACK đã trả) | V2 retry queue nếu cần |
| Edit message 5 phút window tính từ `createdAt` | Sau 5 phút không sửa được; clock skew client/server có thể gây race ở ranh giới | FE disable nút Edit sớm hơn (ở giây 290 / 4:50) thay vì đợi tới 300 — tránh user bấm Save ở 4:59 và server reject vì clock skew 2s. Authority là server clock (UTC). | V2 cân nhắc tăng window hoặc cho phép admin override |
| Unified ACK queue `/user/queue/acks` gửi tới TẤT CẢ sessions của cùng user | Tab không active edit vẫn nhận ACK của tab khác | FE check `clientId` in local `editTimerRegistry` → ignore nếu không match session | Acceptable V1 |

---

## 9. ADR Reference

Các quyết định kiến trúc liên quan (chi tiết trong `.claude/memory/reviewer-knowledge.md` và `docs/ARCHITECTURE.md` mục 12):

- **ADR-014** _(Superseded bởi ADR-016)_: W4 chọn REST-gửi + STOMP-broadcast cho đơn giản, không dùng tempId inbound.
- **ADR-015**: SimpleBroker → RabbitMQ V2 khi scale >1 BE instance hoặc cần persistent queue.
- **ADR-016**: Chuyển path gửi tin từ REST → STOMP (Path B). Client gửi qua `/app/conv.{id}.message` với tempId, server ACK qua `/user/queue/acks`, ERROR qua `/user/queue/errors`. Redis dedup `msg:dedup:{userId}:{tempId}` TTL 60s. REST giữ cho batch/bot/fallback.
- **ADR-017**: **Unified ACK/ERROR queue với `operation` discriminator** thay vì tách queue per operation (`/user/queue/acks-edit`, `/user/queue/acks-delete`, ...). Payload shape: `{operation: "SEND"|"EDIT"|"DELETE"|"REACT", clientId: UUID, message|error, ...}`. Lý do: (1) tránh proliferation queues khi thêm operation (DELETE, REACT, REPLY), (2) FE routing đơn giản qua `switch(operation)` một chỗ thay vì mount N subscription, (3) giữ `/user/queue/acks` + `/user/queue/errors` làm 2 entry-point duy nhất cho mọi operation client-initiated. Trade-off: SEND ACK shape cũ `{tempId, message}` phải migrate breaking — BE + FE deploy đồng bộ. `tempId` rename thành `clientId` ở contract mới (generic hơn), nhưng giá trị vẫn là UUID v4 client sinh. Ngày: 2026-04-20 (W5-D2).
- **ADR-018**: **Delete policy — no time window, soft delete, content strip tại mapper**. Khác EDIT (cửa sổ 5 phút chống gaslight), DELETE không có thời gian giới hạn — user có quyền xoá lịch sử của chính mình bất kỳ lúc nào. Soft delete bằng 2 cột `deleted_at` + `deleted_by` (giữ row để bảo toàn thứ tự, unread count, reply-to reference). BE `MessageMapper.toDto` strip `content=null` nhất quán khi `deletedAt != null` (áp dụng REST + WS broadcast + ACK). FE render placeholder "🚫 Tin nhắn đã bị xóa". ACK DELETE dùng metadata minimal `{id, conversationId, deletedAt, deletedBy}` (không có content/sender/createdAt) để giảm payload + tránh leak. Trade-off: V2 có thể add grace-period 5s client-side để user undo (tech debt đã note). Ngày: 2026-04-20 (W5-D3).

---

## 10. W4 Implementation Checklist

### BE (W4-D3)
- [ ] `WebSocketConfig.java` với `/ws` endpoint + SockJS + SimpleBroker `/topic` `/queue`.
- [ ] `AuthChannelInterceptor` validate JWT ở CONNECT, member check ở SUBSCRIBE.
- [ ] `MessageBroadcaster` `@TransactionalEventListener(AFTER_COMMIT)` publish `MESSAGE_CREATED` lên `/topic/conv.{id}`.
- [ ] `MessageService.sendMessage` publish `MessageCreatedEvent` sau save.
- [ ] `setMessageSizeLimit(64KB)` và `setAllowedOriginPatterns` từ config.
- [ ] Integration test: POST message → subscriber khác nhận broadcast với cùng `MessageDto` shape.
- [ ] Test: non-member SUBSCRIBE `/topic/conv.{id}` → ERROR frame `FORBIDDEN`.
- [ ] Test: invalid JWT CONNECT → ERROR frame `AUTH_REQUIRED`.

### FE (W4-D4)
- [x] `stompClient.ts` singleton với SockJS + Bearer token + auto-reconnect.
- [x] `useConvSubscription(convId)` hook với dedupe + cleanup.
- [x] Mount subscription trong `ConversationDetailPage`, unmount khi rời trang.
- [x] Dedupe test: gửi REST POST → nhận broadcast → message CHỈ xuất hiện 1 lần trong UI.
- [ ] Reconnect + catch-up: simulate offline 30s → send message từ device khác → reconnect → FE gọi REST catch-up → UI hiển thị missed messages.
- [ ] Handle `AUTH_TOKEN_EXPIRED` STOMP error → refresh → reactivate.

---

## 11. Post-W4 — Path B (ADR-016) Implementation Checklist

### BE (Post-W4)
- [ ] `ChatMessageHandler` với `@MessageMapping("/conv.{convId}.message")` — nhận `{tempId, content, type}`, gọi `MessageService.sendViaStomp`.
- [ ] `MessageService.sendViaStomp(convId, userId, payload)`:
  - [ ] Validate payload (tempId UUID, content 1..5000, toàn whitespace fail).
  - [ ] Authorize member (`existsByConvIdAndUserId`).
  - [ ] Rate limit `rate:msg:{userId}` INCR EX 60 → 30/phút.
  - [ ] Dedup `SET msg:dedup:{userId}:{tempId} <value> NX EX 60` atomic trước khi save.
  - [ ] Save message DB trong `@Transactional`.
  - [ ] Update dedup value bằng real messageId.
  - [ ] `eventPublisher.publishEvent(MessageCreatedEvent)` — broadcaster `@TransactionalEventListener(AFTER_COMMIT)` đã có từ W4D4, reuse.
  - [ ] Gửi ACK `convertAndSendToUser(userId, "/queue/acks", {tempId, message})` trong `TransactionSynchronization.afterCommit()`.
- [ ] `@MessageExceptionHandler(AppException)` + `@MessageExceptionHandler(Exception)` → gửi ERROR `/user/queue/errors` với `{tempId, error, code}`.
- [ ] tempId extraction từ payload (không phải header) — propagate qua exception details hoặc ThreadLocal tạm.
- [ ] Test: gửi 2 frame cùng tempId → chỉ 1 message lưu DB + 2 ACK identical.
- [ ] Test: gửi với convId không phải member → ERROR `CONV_NOT_FOUND`.
- [ ] Test: content 5001 chars → ERROR `MSG_CONTENT_TOO_LONG`.
- [ ] Test: gửi 31 frame trong 1 phút → frame thứ 31 → ERROR `MSG_RATE_LIMITED`.
- [ ] Test: tempId không phải UUID → ERROR `VALIDATION_FAILED`.
- [ ] Test: sender tự nhận broadcast trên `/topic/conv.{id}` đúng 1 lần (khớp ACK).

### FE (Post-W4)
- [ ] `useSendMessage(convId)` mutation hook — generate tempId, optimistic append, `client.publish`, start 10s timer.
- [ ] Module-level `timerRegistry: Map<tempId, { timerId, convId }>` singleton.
- [ ] `useAckErrorSubscription()` mount ở App root — subscribe `/user/queue/acks` + `/user/queue/errors`, re-subscribe khi reconnect.
- [ ] ACK handler: clear timer, replace optimistic (bằng `clientTempId` match) với real message, invalidate `['conversations']`.
- [ ] ERROR handler: clear timer, mark `status='failed'` với `failureCode` + `failureReason`. Handle `AUTH_REQUIRED` → trigger refresh.
- [ ] Timeout 10s handler: mark `status='failed'` nếu vẫn sending, show "Không phản hồi" + retry button.
- [ ] Update `useConvSubscription` dedupe: vẫn dùng real id (không đổi từ W4D4). Đảm bảo khi broadcast đến trước ACK (race hiếm), dedupe không nhầm.
- [ ] Update `ConversationDetailPage` / `MessageInput` — gọi `useSendMessage` thay cho REST mutation cũ.
- [ ] Retry flow: click retry → generate tempId MỚI, không reuse.
- [ ] Logout hook → clear `timerRegistry` + unsubscribe 2 user queue.
- [ ] Test: gửi message → nhận ACK → UI sent đúng 1 lần (không duplicate với broadcast).
- [ ] Test: gửi message với STOMP disconnected → input disabled hoặc publish fail → UI hiện lỗi.
- [ ] Test: gửi message → server chậm 11s → FE mark failed → sau đó ACK về → FE giữ failed (không flap) HOẶC update sent nếu muốn (document behavior đã chọn).
- [ ] Test: retry 3 lần liên tiếp → 3 tempId khác nhau → DB có 3 message (không dedup nhầm).

---

## Changelog

| Ngày | Version | Thay đổi |
|------|---------|---------|
| 2026-04-19 | v0.1 | Initial skeleton (không dùng, đã overwrite bởi v1.0-draft-w4). |
| 2026-04-19 | v1.0-draft-w4 | Draft contract W4: REST-gửi + STOMP-broadcast model. `/topic/conv.{id}` + `MESSAGE_CREATED` event shape IDENTICAL với `POST /messages` REST response. BE implementation guide (WebSocketConfig + AuthChannelInterceptor + `@TransactionalEventListener(AFTER_COMMIT)`). FE guide (stompClient singleton + useConvSubscription hook với dedupe bắt buộc). Security: message size 64KB, origin từ config, member check ở SUBSCRIBE. Limitations V1 documented. Placeholder MESSAGE_UPDATED/DELETED (W6), TYPING/PRESENCE (W5). |
| 2026-04-20 | v1.1-w4 | **Path B (ADR-016)**: Chuyển send path từ REST → STOMP. Thêm inbound `/app/conv.{convId}.message` với payload `{tempId, content, type}`. Thêm 2 user queue `/user/queue/acks` (payload `{tempId, message}`) + `/user/queue/errors` (payload `{tempId, error, code}`). Redis dedup `msg:dedup:{userId}:{tempId}` TTL 60s atomic SET NX EX. FE tempId lifecycle state machine: optimistic → ACK/ERROR/timeout 10s. BE handler + `@MessageExceptionHandler` pattern. Rate limit 30/phút khớp REST. REST `POST /messages` không deprecated — giữ cho batch/bot/fallback. Error codes mới: MSG_CONTENT_TOO_LONG, MSG_RATE_LIMITED, FORBIDDEN, INTERNAL. Bỏ "draft" suffix vì contract đã được accept sau W4D4. |
| 2026-04-20 | v1.2-w5d1 | **Destination-aware auth policy (W5-D1)**: Thêm mục 7.1 Destination Policy Table. `AuthChannelInterceptor.handleSend()` refactored với `DestinationPolicy` enum: `.message` → STRICT_MEMBER (throw FORBIDDEN), `.typing` + `.read` → SILENT_DROP (pass through, handler tự xử lý). Không còn throw FORBIDDEN cho `.typing` — fix spec mismatch (typing phải silent drop, không ERROR frame). |
| 2026-04-20 | v1.3-w5d2 | **Edit Message via STOMP (W5-D2)** + **Unified ACK queue (ADR-017)**. Thêm inbound `/app/conv.{convId}.edit` với payload `{clientEditId, messageId, newContent}`. Fill §3.2 MESSAGE_UPDATED đầy đủ (trước đây là placeholder W6, nay dời sang W5). Thêm §3c toàn bộ spec: validation (UUID, 1-5000, 5 phút window, owner + not-found merge chống enumeration, no-change check, TEXT-only), ACK shape mới `{operation: "SEND"|"EDIT", clientId, message}` thay thế shape cũ `{tempId, message}` (breaking — BE + FE deploy đồng bộ), ERROR shape mới `{operation, clientId, error, code}`. Error codes mới: `MSG_NOT_FOUND`, `MSG_EDIT_WINDOW_EXPIRED`, `MSG_NO_CHANGE`. Dedup Redis key `msg:edit-dedup:{userId}:{clientEditId}` TTL 60s atomic SET NX EX (giống send). Rate limit 10 edit/phút/user (`rate:msg-edit:{userId}`). FE state machine idle → editing → saving → saved/error với timer 10s. Thêm row mới vào destinations table (§2) + §7.1 Destination Policy Table (STRICT_MEMBER cho `.edit`). Thêm limitation về clock skew edit window (FE disable sớm ở 4:50) + unified queue multi-session caveat. ADR-017 thêm vào §9. Broadcast MESSAGE_UPDATED giữ minimal payload (id + conversationId + content + editedAt) — không phải MessageDto đầy đủ. FE dedup broadcast theo `editedAt` timestamp. |
| 2026-04-20 | v1.4-w6 | **W6-D1 attachments for SEND** (ADR-019). §3b.1 payload thêm `attachmentIds?: string[]` (array UUID, 0-5 items). `content` nullable khi có attachments (1 trong 2 phải non-null). Validation rules chi tiết trong API_CONTRACT.md mục Files Management. Error codes mới trong bảng §3b.3: `MSG_NO_CONTENT`, `MSG_ATTACHMENTS_MIXED`, `MSG_ATTACHMENTS_TOO_MANY`, `MSG_ATTACHMENT_NOT_FOUND`, `MSG_ATTACHMENT_EXPIRED`, `MSG_ATTACHMENT_NOT_OWNED`, `MSG_ATTACHMENT_ALREADY_USED`. §3.1 `MESSAGE_CREATED` payload update: MessageDto thêm `attachments: FileDto[]` (luôn là array, không null), `type` mở rộng `TEXT | IMAGE | FILE`. ACK §3b.2 reuse MessageMapper với attachments load. §3c (EDIT) KHÔNG đổi payload — V1 không cho sửa attachments, chỉ sửa content. EDIT với message có attachments: update content giữ attachments. File upload qua REST `POST /api/files/upload` (xem API_CONTRACT.md), sau đó SEND qua STOMP với `attachmentIds`. Rationale tách 2 bước: binary không fit STOMP frame 64KB, progress bar dễ với multipart, reuse file cho multiple message V2. |
| 2026-04-20 | v1.4-w5d3 | **Delete Message via STOMP (W5-D3)** + **ADR-018 (delete policy)**. Thêm inbound `/app/conv.{convId}.delete` với payload `{clientDeleteId, messageId}`. Fill §3.3 MESSAGE_DELETED đầy đủ (trước placeholder W6, nay dời W5 cùng với EDIT): payload `{id, conversationId, deletedAt, deletedBy}`, FE action patch `deletedAt + deletedBy + content=null` + exit edit mode silently nếu đang edit cùng message, KHÔNG xoá khỏi cache. Thêm §3d toàn bộ spec delete flow: validation (UUID, anti-enum MSG_NOT_FOUND merge 4 case null/wrong-conv/not-owner/already-deleted, KHÔNG có time window — khác EDIT), rate limit 10/phút/user (`rate:msg-delete:{userId}`), dedup Redis `msg:delete-dedup:{userId}:{clientDeleteId}` TTL 60s, ACK shape `{operation: "DELETE", clientId, message}` với `message` là metadata minimal (id + conversationId + deletedAt + deletedBy, KHÔNG có content/sender/createdAt), ERROR shape `{operation: "DELETE", clientId, error, code}` với error codes `MSG_NOT_FOUND | MSG_RATE_LIMITED | VALIDATION_FAILED | AUTH_REQUIRED | INTERNAL`. Thêm §3e Deleted Message Rendering contract: bubble gray italic "🚫 Tin nhắn đã bị xóa", không hover actions, BE strip `content=null` tại `MessageMapper.toDto` khi `deletedAt != null` (áp dụng nhất quán REST + WS + ACK), `MessageDto` thêm 2 field `deletedAt` + `deletedBy`. Thêm row `/app/conv.{id}.delete` vào destinations table (§2) + §7.1 Destination Policy (STRICT_MEMBER). Interaction: edit-after-delete → `MSG_NOT_FOUND` (anti-enum), reply-to-deleted → OK V1 (snapshot giữ nguyên, V2 flag). ADR-018 thêm vào §9. |
