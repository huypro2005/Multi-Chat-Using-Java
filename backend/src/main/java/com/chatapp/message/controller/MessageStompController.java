package com.chatapp.message.controller;

import com.chatapp.exception.AppException;
import com.chatapp.message.dto.ErrorPayload;
import com.chatapp.message.dto.SendMessagePayload;
import com.chatapp.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.messaging.handler.annotation.MessageMapping;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP inbound controller — Path B (ADR-016).
 *
 * Client sends:
 *   SEND /app/conv.{convId}.message
 *   body = { tempId, content, type }
 *
 * Success path:
 *   service saves message → publishes MessageCreatedEvent →
 *   MessageBroadcaster broadcasts /topic/conv.{id} (AFTER_COMMIT) →
 *   ACK sent to /user/queue/acks (AFTER_COMMIT) by service.
 *
 * Failure path:
 *   @MessageExceptionHandler catches AppException or generic Exception →
 *   ERROR sent to /user/queue/errors with unified shape {operation, clientId, error, code} (ADR-017).
 *
 * tempId extraction: tempId lives in the JSON payload body — propagated
 * through AppException.details as { "tempId": "..." } so exception handlers
 * can echo it back to the client.
 *
 * REST MessageController is NOT modified — kept for batch/bot/fallback use.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class MessageStompController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    // =========================================================================
    // Inbound handler
    // =========================================================================

    /**
     * Receives STOMP frame from client.
     * Principal is set by AuthChannelInterceptor at CONNECT time (StompPrincipal).
     * userId = principal.getName() — NEVER trust userId from payload.
     */
    @MessageMapping("/conv.{convId}.message")
    public void sendMessage(
            @DestinationVariable UUID convId,
            @Payload SendMessagePayload payload,
            Principal principal
    ) {
        UUID userId = UUID.fromString(principal.getName());
        log.info("[STOMP] Received message from userId={} for convId={}, tempId={}",
                userId, convId, payload.tempId());
        messageService.sendViaStomp(convId, userId, payload);
        log.info("[STOMP] Finished processing message from userId={} for convId={}, tempId={}",
                userId, convId, payload.tempId());
    }

    // =========================================================================
    // Exception handlers (unified ErrorPayload with operation="SEND" — ADR-017)
    // =========================================================================

    /**
     * Business exception (validation, auth, rate-limit, etc.)
     *
     * tempId is extracted from AppException.details Map (key "tempId").
     * If details doesn't contain tempId (e.g. unexpected code path), falls
     * back to "unknown" so FE at least receives an ERROR frame.
     */
    @MessageExceptionHandler(AppException.class)
    public void handleAppException(AppException ex, Principal principal) {
        String clientId = extractClientId(ex.getDetails(), "tempId");
        String userId = principal != null ? principal.getName() : "unknown";

        log.warn("[STOMP/SEND] AppException for userId={}, clientId={}, code={}: {}",
                userId, clientId, ex.getErrorCode(), ex.getMessage());

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("SEND", clientId, ex.getMessage(), ex.getErrorCode())
        );
    }

    /**
     * Catch-all for unexpected exceptions.
     * Logs full stack trace server-side, sends generic INTERNAL error to client.
     */
    @MessageExceptionHandler(Exception.class)
    public void handleGenericException(Exception ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";

        log.error("[STOMP/SEND] Unexpected error for userId={}", userId, ex);

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("SEND", "unknown", "Lỗi server, thử lại sau", "INTERNAL")
        );
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Extract a string value from exception details by key.
     * AppException.details is expected to be a Map<String, Object>.
     */
    @SuppressWarnings("unchecked")
    protected String extractClientId(Object details, String key) {
        if (details instanceof java.util.Map<?, ?> map) {
            Object val = map.get(key);
            if (val instanceof String s) return s;
        }
        return "unknown";
    }
}
