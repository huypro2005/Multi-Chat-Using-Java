# WebSocket / STOMP Events Contract
_Version: v1.0-draft-w4_
_Status: DRAFT — pending implementation W4-D3_
_Owner: code-reviewer (architect)_

> File này là **source of truth** cho mọi WebSocket event giữa frontend và backend.
> **Chỉ `code-reviewer` agent được sửa file này.** BE và FE đọc và implement theo.
>
> **LƯU Ý KIẾN TRÚC TUẦN 4**: Tuần 4 chọn mô hình **REST gửi + STOMP broadcast (publish-only)** thay vì STOMP bidirectional với `tempId` flow như ARCHITECTURE.md mục 5 mô tả. Xem [ADR-014](#adr-reference) để biết lý do. Mô hình `tempId` ACK/ERROR sẽ được đánh giá lại ở Tuần 5 nếu cần reduce latency.

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
| `/app/conv.{conversationId}.typing` | Typing indicator | 1 event/2s/user/conv | Tuần 5 |
| `/app/conv.{conversationId}.read` | Read receipt | 1 event/5s/user/conv | Tuần 5 |

> **Tuần 4 scope**: CHỈ implement `/topic/conv.{conversationId}` subscribe + broadcast từ BE sau REST `POST /messages`. Không có `/app/*` inbound ở W4.

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

BE implement `@MessageExceptionHandler` trên controller (khi có SEND events từ Tuần 5):

```java
@MessageExceptionHandler(AppException.class)
@SendToUser("/queue/errors")
public Map<String, Object> handleAppException(AppException ex) {
    return Map.of(
        "code", ex.getErrorCode(),
        "message", ex.getMessage(),
        "timestamp", Instant.now().toString()
    );
}
```

W4 không dùng vì không có SEND inbound. Documented cho W5.

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
2. Gọi REST `GET /api/conversations/{id}/messages?cursor=<latestKnownCreatedAt>` để lấy messages đã miss trong lúc offline.
3. Merge vào React Query cache với dedupe logic tương tự mục 5.2.
4. KHÔNG re-play events từ SimpleBroker (broker không buffer offline subscribers — xem mục 8).

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

---

## 8. Known Limitations (V1)

| Limitation | Impact | Workaround V1 | Fix khi nào |
|-----------|--------|---------------|------------|
| SimpleBroker (in-memory) | Mất messages nếu BE restart | FE catch-up qua REST sau reconnect | Tuần 5-6: đánh giá RabbitMQ |
| Không persistent subscription | Offline user miss broadcast | REST `GET /messages?cursor=...` khi reconnect | Acceptable V1 |
| Không delivery guarantee (at-most-once) | Message có thể không tới nếu WS disconnect đúng khoảnh khắc broadcast | DB là source-of-truth; FE polling catch-up | Tuần 5: đánh giá ACK pattern hoặc RabbitMQ |
| Member check chỉ khi SUBSCRIBE | User bị kick khỏi conv vẫn nhận message cho đến khi client unsubscribe | Kick flow force unsubscribe qua notification event | Tuần 6 khi implement kick |
| Single-instance BE | Không scale horizontal | Acceptable (ARCHITECTURE.md giả định 1 server Singapore) | V2 |
| Không có ACK ở W4 | Nếu broadcast fail, sender không biết | REST 201 response đã confirm save → UI OK | Tuần 5 đánh giá |

---

## 9. ADR Reference

Các quyết định kiến trúc liên quan (chi tiết trong `.claude/memory/reviewer-knowledge.md`):

- **ADR-014**: STOMP over SockJS + SimpleBroker cho V1 (in-memory). Model REST-gửi + STOMP-broadcast, không dùng tempId inbound ở W4.
- **ADR-015**: SimpleBroker → RabbitMQ V2 khi scale >1 BE instance hoặc cần persistent queue.

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
- [ ] `stompClient.ts` singleton với SockJS + Bearer token + auto-reconnect.
- [ ] `useConvSubscription(convId)` hook với dedupe + cleanup.
- [ ] Mount subscription trong `ConversationDetailPage`, unmount khi rời trang.
- [ ] Dedupe test: gửi REST POST → nhận broadcast → message CHỈ xuất hiện 1 lần trong UI.
- [ ] Reconnect + catch-up: simulate offline 30s → send message từ device khác → reconnect → FE gọi REST catch-up → UI hiển thị missed messages.
- [ ] Handle `AUTH_TOKEN_EXPIRED` STOMP error → refresh → reactivate.

---

## Changelog

| Ngày | Version | Thay đổi |
|------|---------|---------|
| 2026-04-19 | v0.1 | Initial skeleton (không dùng, đã overwrite bởi v1.0-draft-w4). |
| 2026-04-19 | v1.0-draft-w4 | Draft contract W4: REST-gửi + STOMP-broadcast model. `/topic/conv.{id}` + `MESSAGE_CREATED` event shape IDENTICAL với `POST /messages` REST response. BE implementation guide (WebSocketConfig + AuthChannelInterceptor + `@TransactionalEventListener(AFTER_COMMIT)`). FE guide (stompClient singleton + useConvSubscription hook với dedupe bắt buộc). Security: message size 64KB, origin từ config, member check ở SUBSCRIBE. Limitations V1 documented. Placeholder MESSAGE_UPDATED/DELETED (W6), TYPING/PRESENCE (W5). |
