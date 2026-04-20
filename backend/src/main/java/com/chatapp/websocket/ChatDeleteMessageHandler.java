package com.chatapp.websocket;

import com.chatapp.exception.AppException;
import com.chatapp.message.dto.DeleteMessagePayload;
import com.chatapp.message.dto.ErrorPayload;
import com.chatapp.message.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.UUID;

/**
 * STOMP inbound controller for delete message — W5-D3.
 *
 * Client sends:
 *   SEND /app/conv.{convId}.delete
 *   body = { clientDeleteId, messageId }
 *
 * Success path:
 *   service validates + deduplicates + soft-deletes message → publishes MessageDeletedEvent →
 *   MessageBroadcaster broadcasts MESSAGE_DELETED on /topic/conv.{id} (AFTER_COMMIT) →
 *   ACK sent to /user/queue/acks {operation:"DELETE", clientId, message:{id,conversationId,deletedAt,deletedBy}} AFTER_COMMIT.
 *
 * Failure path:
 *   @MessageExceptionHandler catches AppException or generic Exception →
 *   ERROR sent to /user/queue/errors with unified shape {operation:"DELETE", clientId, error, code} (ADR-017).
 *
 * clientDeleteId is propagated through AppException.details as { "clientDeleteId": "..." }
 * so exception handlers can echo it back to the client.
 *
 * AuthChannelInterceptor enforces STRICT_MEMBER for .delete destination — non-members
 * receive FORBIDDEN ERROR frame before reaching this handler.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatDeleteMessageHandler {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    // =========================================================================
    // Inbound handler
    // =========================================================================

    /**
     * Receives STOMP delete frame from client.
     * Principal is set by AuthChannelInterceptor at CONNECT time (StompPrincipal).
     * userId = principal.getName() — NEVER trust userId from payload.
     */
    @MessageMapping("/conv.{convId}.delete")
    public void handleDelete(
            @DestinationVariable UUID convId,
            @Payload DeleteMessagePayload payload,
            Principal principal
    ) {
        UUID userId = UUID.fromString(principal.getName());
        log.info("[STOMP/DELETE] Received delete from userId={} for convId={}, clientDeleteId={}, messageId={}",
                userId, convId, payload.clientDeleteId(), payload.messageId());
        messageService.deleteViaStomp(convId, userId, payload);
        log.info("[STOMP/DELETE] Finished processing delete from userId={} for convId={}, clientDeleteId={}",
                userId, convId, payload.clientDeleteId());
    }

    // =========================================================================
    // Exception handlers (unified ErrorPayload with operation="DELETE" — ADR-017)
    // =========================================================================

    /**
     * Business exception (validation, auth, rate-limit, MSG_NOT_FOUND, etc.)
     *
     * clientDeleteId is extracted from AppException.details Map (key "clientDeleteId").
     * Falls back to "unknown" if not present.
     */
    @MessageExceptionHandler(AppException.class)
    public void handleAppException(AppException ex, Principal principal) {
        String clientId = extractClientDeleteId(ex.getDetails());
        String userId = principal != null ? principal.getName() : "unknown";

        log.warn("[STOMP/DELETE] AppException for userId={}, clientDeleteId={}, code={}: {}",
                userId, clientId, ex.getErrorCode(), ex.getMessage());

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("DELETE", clientId, ex.getMessage(), ex.getErrorCode())
        );
    }

    /**
     * Catch-all for unexpected exceptions.
     * Logs full stack trace server-side, sends generic INTERNAL error to client.
     */
    @MessageExceptionHandler(Exception.class)
    public void handleGenericException(Exception ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";

        log.error("[STOMP/DELETE] Unexpected error for userId={}", userId, ex);

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("DELETE", "unknown", "Lỗi server, thử lại sau", "INTERNAL")
        );
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String extractClientDeleteId(Object details) {
        if (details instanceof java.util.Map<?, ?> map) {
            Object val = map.get("clientDeleteId");
            if (val instanceof String s) return s;
        }
        return "unknown";
    }
}
