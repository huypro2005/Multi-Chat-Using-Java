package com.chatapp.websocket;

import com.chatapp.exception.AppException;
import com.chatapp.message.dto.ErrorPayload;
import com.chatapp.reaction.service.ReactionService;
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
 * STOMP handler for message reactions — W8-D1.
 *
 * Client sends to /app/msg.{messageId}.react:
 *   { "emoji": "👍" }
 *
 * NOTE về destination: `.react` dùng prefix `/app/msg.{messageId}` (không phải `/app/conv.{convId}`)
 * vì client chỉ biết messageId khi react. convId được lấy từ message.conversationId trong service.
 *
 * HANDLER_CHECK policy (ADR chốt bởi reviewer):
 * - AuthChannelInterceptor KHÔNG check membership cho `.react` (destination pattern khác `/app/conv.*`).
 * - Member check thực hiện trong ReactionService (piggyback message query → convId → memberRepo).
 *
 * Response:
 * - Không có ACK riêng. Confirmation qua broadcast REACTION_CHANGED trên /topic/conv.{convId}
 *   (cả caller và các member khác đều nhận).
 * - ERROR frame qua /user/queue/errors với {operation:"REACT", clientId:null, code, error}.
 *   clientId = null vì REACT payload không có clientId.
 *
 * Rate limit: 5 reactions/second/user — applied trong ReactionService (Redis INCR EX 1s).
 *
 * Contract: SOCKET_EVENTS.md §3.15 (W8-D1).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatReactHandler {

    private final ReactionService reactionService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Payload từ client — chỉ có emoji field.
     * Không có clientId / tempId (fire-and-forget, confirmation qua broadcast).
     */
    public record ReactPayload(String emoji) {}

    // =========================================================================
    // Inbound handler
    // =========================================================================

    /**
     * Handle react frame từ client.
     *
     * messageId: từ destination path variable (không trust payload).
     * userId: từ Principal set bởi AuthChannelInterceptor tại CONNECT.
     */
    @MessageMapping("/msg.{messageId}.react")
    public void handleReact(
            @DestinationVariable UUID messageId,
            @Payload ReactPayload payload,
            Principal principal
    ) {
        UUID userId = UUID.fromString(principal.getName());
        String emoji = payload != null ? payload.emoji() : null;

        log.debug("[STOMP/REACT] Received from userId={}, messageId={}, emoji={}", userId, messageId, emoji);

        reactionService.react(messageId, userId, emoji);

        log.debug("[STOMP/REACT] Processed reaction: userId={}, messageId={}, emoji={}", userId, messageId, emoji);
    }

    // =========================================================================
    // Exception handlers (operation="REACT", clientId=null — ADR-017 + §3.15)
    // =========================================================================

    /**
     * Business exception handler.
     *
     * Per contract §3.15: clientId = null vì REACT không có clientId trong payload.
     * FE handler phải tolerate null clientId cho operation="REACT".
     */
    @MessageExceptionHandler(AppException.class)
    public void handleAppException(AppException ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";
        log.warn("[STOMP/REACT] AppException for userId={}, code={}: {}",
                userId, ex.getErrorCode(), ex.getMessage());

        // clientId = null (REACT không có clientId — contract §3.15 "clientId=null")
        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("REACT", null, ex.getMessage(), ex.getErrorCode())
        );
    }

    /**
     * Catch-all for unexpected exceptions.
     * Log full stack trace server-side, trả generic INTERNAL error cho client.
     */
    @MessageExceptionHandler(Exception.class)
    public void handleGenericException(Exception ex, Principal principal) {
        String userId = principal != null ? principal.getName() : "unknown";
        log.error("[STOMP/REACT] Unexpected error for userId={}", userId, ex);

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/errors",
                new ErrorPayload("REACT", null, "Lỗi server, thử lại sau", "INTERNAL")
        );
    }
}
