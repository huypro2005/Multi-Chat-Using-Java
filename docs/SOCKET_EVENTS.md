# WebSocket / STOMP Events Contract
_Version: v1.1-w4_
_Status: Accepted — Path B (STOMP-send) chốt sau W4D4_
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

| Destination | Mô tả | Rate limit | Phase |
|------------|-------|-----------|-------|
| `/app/conv.{conversationId}.message` | **Gửi tin nhắn (Path B, ADR-016)** | 30 msg/phút/user | **Post-W4 / Tuần 5** |
| `/app/conv.{conversationId}.typing` | Typing indicator | 1 event/2s/user/conv | Tuần 5 |
| `/app/conv.{conversationId}.read` | Read receipt | 1 event/5s/user/conv | Tuần 5 |

### User queues (Server → Client, sender-only)

Spring STOMP user destination — BE gửi bằng `SimpMessagingTemplate.convertAndSendToUser(principalName, destination, payload)`. Chỉ session của user đó mới nhận.

| Destination | Mô tả | Trigger |
|------------|-------|---------|
| `/user/queue/acks` | ACK khi gửi tin nhắn thành công | Sau khi `/app/conv.{id}.message` save xong |
| `/user/queue/errors` | ERROR khi gửi tin nhắn thất bại | Khi `/app/conv.{id}.message` validation fail hoặc authorization fail |

> **Scope hiện tại**: `/topic/conv.{conversationId}` broadcast (W4D4 done) + `/app/conv.{conversationId}.message` inbound + `/user/queue/acks`, `/user/queue/errors` (Post-W4, ADR-016). Typing/read/presence giữ Tuần 5.

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

**Payload**: `MessageDto` — **shape IDENTICAL với REST response** của `POST /api/conversations/{convId}/messages` trong `API_CONTRACT.md` mục v0.6.0-messages-rest.

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
    "type": "TEXT",
    "content": "Hello",
    "replyToMessage": {
      "id": "uuid",
      "senderName": "string",
      "contentPreview": "string (≤100 chars + '...' nếu truncated)"
    },
    "editedAt": null,
    "createdAt": "2026-04-20T10:00:00Z"
  }
}
```

> **Rule vàng**: BE PHẢI dùng cùng 1 `MessageMapper` / serialization cho cả REST response và broadcast payload. Nếu shape lệch → FE sẽ mismatch runtime. Reviewer sẽ check điều này ở W4-D3 review.

**FE action khi nhận MESSAGE_CREATED**:
1. Parse `frame.body` → `WsEvent<MessageDto>`.
2. **Dedupe check** (BẮT BUỘC): kiểm tra `messages.some(m => m.id === payload.id)`. Nếu `true` → skip hoàn toàn (idempotent).
3. Nếu chưa có → append vào React Query cache `messageKeys.all(convId)`.
4. Invalidate / update `useConversations` list để sidebar cập nhật `lastMessageAt` + re-sort.

> **Tại sao BẮT BUỘC dedupe?**: Sender A gửi REST `POST /messages` → React Query `onSuccess` set message vào cache với id thật → BE ALSO broadcast MESSAGE_CREATED qua `/topic/conv.{id}` → A nhận broadcast với CÙNG id → nếu không dedupe sẽ duplicate UI. Sender B (receiver) nhận broadcast → không có trong cache → append bình thường.

### 3.2 MESSAGE_UPDATED _(Tuần 6 — chưa implement)_

```json
{
  "type": "MESSAGE_UPDATED",
  "payload": {
    "id": "uuid",
    "conversationId": "uuid",
    "content": "edited content",
    "editedAt": "2026-04-20T10:05:00Z"
  }
}
```

### 3.3 MESSAGE_DELETED _(Tuần 6 — chưa implement)_

```json
{
  "type": "MESSAGE_DELETED",
  "payload": {
    "id": "uuid",
    "conversationId": "uuid",
    "deletedAt": "2026-04-20T10:10:00Z"
  }
}
```

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
  "content": "string (1-5000 chars, required)",
  "type": "TEXT"
}
```

**Validation rules** (server-side):
- `tempId`: bắt buộc, UUID v4 format. Server dùng để dedup + route ACK.
- `content`: bắt buộc, trim sau đó 1–5000 chars. Content toàn whitespace → validation fail.
- `type`: chỉ `"TEXT"` ở Path B. `IMAGE` / `FILE` vẫn đi qua REST (kèm file upload). `SYSTEM` chỉ server phát, client không gửi.

> **Path B scope**: Chỉ `TEXT` qua STOMP. Reply-to-message (`replyToMessageId`) sẽ add vào payload ở Tuần 5 khi FE wire UI trả lời tin. V1 Path B chưa hỗ trợ reply inline.

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

### 3b.3 Server → Client (sender-only): ERROR

**Destination**: `/user/queue/errors`

**Payload**:
```json
{
  "tempId": "uuid (echo từ request)",
  "error": "string (human-readable)",
  "code": "CONV_NOT_FOUND | FORBIDDEN | VALIDATION_FAILED | MSG_CONTENT_TOO_LONG | MSG_RATE_LIMITED | AUTH_REQUIRED | INTERNAL"
}
```

**Error code table**:

| code | Điều kiện | FE action |
|------|-----------|-----------|
| `CONV_NOT_FOUND` | Conversation không tồn tại hoặc sender không phải member (merge để chống enumeration) | Mark tempId = failed, hiện "Không tìm thấy cuộc trò chuyện" |
| `FORBIDDEN` | Sender bị user khác trong conv block (khi `user_blocks` wire; V1 chưa fire) | Mark failed, hiện "Không thể gửi tin nhắn tới người này" |
| `VALIDATION_FAILED` | content rỗng, toàn whitespace, hoặc tempId không phải UUID | Mark failed, hiện message cụ thể (FE không retry) |
| `MSG_CONTENT_TOO_LONG` | content > 5000 chars | Mark failed, hiện "Tin nhắn quá dài (tối đa 5000 ký tự)" |
| `MSG_RATE_LIMITED` | Vượt 30 msg/phút/user | Mark failed, hiện "Gửi quá nhanh, thử lại sau N giây"; FE có thể retry sau `retryAfterSeconds` (nếu BE có gửi field này) |
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

---

## 9. ADR Reference

Các quyết định kiến trúc liên quan (chi tiết trong `.claude/memory/reviewer-knowledge.md` và `docs/ARCHITECTURE.md` mục 12):

- **ADR-014** _(Superseded bởi ADR-016)_: W4 chọn REST-gửi + STOMP-broadcast cho đơn giản, không dùng tempId inbound.
- **ADR-015**: SimpleBroker → RabbitMQ V2 khi scale >1 BE instance hoặc cần persistent queue.
- **ADR-016**: Chuyển path gửi tin từ REST → STOMP (Path B). Client gửi qua `/app/conv.{id}.message` với tempId, server ACK qua `/user/queue/acks`, ERROR qua `/user/queue/errors`. Redis dedup `msg:dedup:{userId}:{tempId}` TTL 60s. REST giữ cho batch/bot/fallback.

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
