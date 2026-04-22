package com.chatapp.websocket;

import com.chatapp.exception.AppException;
import com.chatapp.message.dto.ErrorPayload;
import com.chatapp.message.service.PinService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * STOMP handler cho pin/unpin message (W8-D2, ADR-023).
 *
 * Client gửi tới /app/msg.{messageId}.pin:
 *   { "action": "PIN" | "UNPIN" }
 *
 * AuthChannelInterceptor pass-through cho /app/msg.* (HANDLER_CHECK policy — đã set từ W8-D1).
 * Member check và role check thực hiện trong PinService.
 *
 * Response:
 * - Không có ACK riêng. Confirmation qua broadcast MESSAGE_PINNED/UNPINNED trên /topic/conv.{convId}.
 * - ERROR frame qua /user/queue/errors với {operation:"PIN", clientId:null, code, error}.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatPinHandler {

    private static final int PIN_RATE_LIMIT_PER_SECOND = 5;

    private final PinService pinService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;

    public record PinPayload(String action) {}

    @MessageMapping("/msg.{messageId}.pin")
    public void handlePin(
            @DestinationVariable UUID messageId,
            @Payload PinPayload payload,
            Principal principal
    ) {
        UUID userId = UUID.fromString(principal.getName());
        String action = payload != null ? payload.action() : null;

        checkRateLimit(userId);

        log.debug("[STOMP/PIN] userId={}, messageId={}, action={}", userId, messageId, action);

        if ("PIN".equals(action)) {
            pinService.pin(messageId, userId);
        } else if ("UNPIN".equals(action)) {
            pinService.unpin(messageId, userId);
        } else {
            throw new AppException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "INVALID_ACTION",
                    "Action phải là PIN hoặc UNPIN"
            );
        }
    }

    private void checkRateLimit(UUID userId) {
        String rateKey = "rate:msg-pin:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, 1, TimeUnit.SECONDS);
            }
            if (count != null && count > PIN_RATE_LIMIT_PER_SECOND) {
                throw new AppException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "MSG_RATE_LIMITED",
                        "Thao tác ghim quá nhanh, vui lòng thử lại sau",
                        Map.of("retryAfterSeconds", 1)
                );
            }
        } catch (AppException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("[STOMP/PIN] Redis unavailable for pin rate limit, fail-open");
        }
    }

    @MessageExceptionHandler(AppException.class)
    public void handleAppException(AppException ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";
        log.warn("[STOMP/PIN] AppException userId={}, code={}: {}", userId, ex.getErrorCode(), ex.getMessage());

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("PIN", null, ex.getMessage(), ex.getErrorCode())
        );
    }

    @MessageExceptionHandler(Exception.class)
    public void handleGenericException(Exception ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";
        log.error("[STOMP/PIN] Unexpected error for userId={}", userId, ex);

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("PIN", null, "Lỗi server, thử lại sau", "INTERNAL")
        );
    }
}
