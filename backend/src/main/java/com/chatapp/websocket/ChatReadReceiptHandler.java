package com.chatapp.websocket;

import com.chatapp.exception.AppException;
import com.chatapp.message.dto.ErrorPayload;
import com.chatapp.message.dto.ReadReceiptPayload;
import com.chatapp.message.service.ReadReceiptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * STOMP handler for read receipt events — W7-D5.
 *
 * Client sends to /app/conv.{convId}.read:
 *   { "messageId": "uuid" }
 *
 * Success path (forward advance):
 *   ReadReceiptService.markRead() updates DB → publishes ReadUpdatedEvent →
 *   MessageBroadcaster broadcasts READ_UPDATED on /topic/conv.{convId} (AFTER_COMMIT).
 *   No ACK sent — read receipt is fire-and-forget (unlike SEND/EDIT/DELETE).
 *
 * No-op path (duplicate / old message):
 *   ReadReceiptService returns void silently — no ERROR, no broadcast.
 *
 * Failure path:
 *   @MessageExceptionHandler catches AppException →
 *   ERROR sent to /user/queue/errors with {operation:"READ", error, code} (no clientId).
 *
 * Rate limit: 1 event/2s/user/conv via Redis INCR key rate:msg-read:{userId}:{convId}.
 *
 * Contract: SOCKET_EVENTS.md §3f (W7-D5).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatReadReceiptHandler {

    private final ReadReceiptService readReceiptService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;

    /**
     * Handle read receipt frame from client.
     *
     * Auth: Principal set by AuthChannelInterceptor at CONNECT time.
     * userId from Principal — NEVER trust payload.
     * convId from destination path variable.
     */
    @MessageMapping("/conv.{convId}.read")
    public void handleRead(
            @DestinationVariable UUID convId,
            @Payload ReadReceiptPayload payload,
            Principal principal
    ) {
        UUID userId = UUID.fromString(principal.getName());

        // Step 1: Validate payload — messageId must be non-null
        if (payload.messageId() == null) {
            throw new AppException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "messageId không được để trống",
                    java.util.Map.of("field", "messageId", "error", "Must not be null"));
        }

        // Step 2: Rate limit — 1 event/2s/user/conv (defense-in-depth, FE debounce ~500ms)
        String rateKey = "rate:msg-read:" + userId + ":" + convId;
        try {
            Long count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, 2, TimeUnit.SECONDS);
            }
            if (count != null && count > 1) {
                log.debug("[READ] Rate limited: userId={}, convId={}", userId, convId);
                throw new AppException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "MSG_RATE_LIMITED",
                        "Đánh dấu đọc quá nhanh, thử lại sau");
            }
        } catch (AppException e) {
            throw e;
        } catch (DataAccessException e) {
            // Fail-open when Redis unavailable
            log.warn("[READ] Redis unavailable for rate limit check, fail-open: userId={}, convId={}", userId, convId);
        }

        // Step 3: Delegate to service (validates member, message, forward-only idempotent)
        readReceiptService.markRead(convId, userId, payload.messageId());
        log.debug("[READ] Processed read receipt: userId={}, convId={}, msgId={}", userId, convId, payload.messageId());
    }

    // =========================================================================
    // Exception handlers (operation="READ" — no clientId per contract §3f.3)
    // =========================================================================

    /**
     * Business exception: NOT_MEMBER, MSG_NOT_FOUND, MSG_NOT_IN_CONV,
     * MSG_RATE_LIMITED, VALIDATION_FAILED, etc.
     *
     * Per contract §3f.3: no clientId in error payload (read receipt has no client-side dedup ID).
     */
    @MessageExceptionHandler(AppException.class)
    public void handleAppException(AppException ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";
        log.warn("[READ] AppException for userId={}, code={}: {}", userId, ex.getErrorCode(), ex.getMessage());

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("READ", null, ex.getMessage(), ex.getErrorCode())
        );
    }

    /**
     * Catch-all for unexpected exceptions.
     */
    @MessageExceptionHandler(Exception.class)
    public void handleGenericException(Exception ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";
        log.error("[READ] Unexpected error for userId={}", userId, ex);

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("READ", null, "Lỗi server, thử lại sau", "INTERNAL")
        );
    }
}
