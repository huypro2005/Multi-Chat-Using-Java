package com.chatapp.websocket;

import com.chatapp.exception.AppException;
import com.chatapp.message.dto.EditMessagePayload;
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
 * STOMP inbound controller for edit message — W5-D2.
 *
 * Client sends:
 *   SEND /app/conv.{convId}.edit
 *   body = { clientEditId, messageId, newContent }
 *
 * Success path:
 *   service validates + deduplicates + updates message → publishes MessageUpdatedEvent →
 *   MessageBroadcaster broadcasts MESSAGE_UPDATED on /topic/conv.{id} (AFTER_COMMIT) →
 *   ACK sent to /user/queue/acks {operation:"EDIT", clientId, message} (AFTER_COMMIT) by service.
 *
 * Failure path:
 *   @MessageExceptionHandler catches AppException or generic Exception →
 *   ERROR sent to /user/queue/errors with unified shape {operation:"EDIT", clientId, error, code} (ADR-017).
 *
 * clientEditId extraction: lives in the JSON payload — propagated via AppException.details
 * as { "clientEditId": "..." } so exception handlers can echo it back.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatEditMessageHandler {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;

    // =========================================================================
    // Inbound handler
    // =========================================================================

    /**
     * Receives STOMP edit frame from client.
     * Principal is set by AuthChannelInterceptor at CONNECT time (StompPrincipal).
     * userId = principal.getName() — NEVER trust userId from payload.
     */
    @MessageMapping("/conv.{convId}.edit")
    public void handleEdit(
            @DestinationVariable UUID convId,
            @Payload EditMessagePayload payload,
            Principal principal
    ) {
        UUID userId = UUID.fromString(principal.getName());
        log.info("[STOMP/EDIT] Received edit from userId={} for convId={}, clientEditId={}, messageId={}",
                userId, convId, payload.clientEditId(), payload.messageId());
        messageService.editViaStomp(convId, userId, payload);
        log.info("[STOMP/EDIT] Finished processing edit from userId={} for convId={}, clientEditId={}",
                userId, convId, payload.clientEditId());
    }

    // =========================================================================
    // Exception handlers (unified ErrorPayload with operation="EDIT" — ADR-017)
    // =========================================================================

    /**
     * Business exception (validation, auth, rate-limit, MSG_NOT_FOUND, etc.)
     *
     * clientEditId is extracted from AppException.details Map (key "clientEditId").
     * Falls back to "unknown" if not present.
     */
    @MessageExceptionHandler(AppException.class)
    public void handleAppException(AppException ex, Principal principal) {
        String clientId = extractClientEditId(ex.getDetails());
        String userId = principal != null ? principal.getName() : "unknown";

        log.warn("[STOMP/EDIT] AppException for userId={}, clientEditId={}, code={}: {}",
                userId, clientId, ex.getErrorCode(), ex.getMessage());

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("EDIT", clientId, ex.getMessage(), ex.getErrorCode())
        );
    }

    /**
     * Catch-all for unexpected exceptions.
     * Logs full stack trace server-side, sends generic INTERNAL error to client.
     */
    @MessageExceptionHandler(Exception.class)
    public void handleGenericException(Exception ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";

        log.error("[STOMP/EDIT] Unexpected error for userId={}", userId, ex);

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("EDIT", "unknown", "Lỗi server, thử lại sau", "INTERNAL")
        );
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String extractClientEditId(Object details) {
        if (details instanceof java.util.Map<?, ?> map) {
            Object val = map.get("clientEditId");
            if (val instanceof String s) return s;
        }
        return "unknown";
    }
}
