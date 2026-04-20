package com.chatapp.websocket;

import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.message.dto.TypingPayload;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;

/**
 * STOMP handler for ephemeral typing indicator events.
 *
 * Client sends to /app/conv.{convId}.typing:
 *   { "action": "START" }  — user started typing
 *   { "action": "STOP" }   — user stopped typing
 *
 * Server broadcasts to /topic/conv.{convId}:
 *   { "type": "TYPING_STARTED" | "TYPING_STOPPED", "payload": { userId, username, conversationId } }
 *
 * Design decisions:
 * - Ephemeral: NOT persisted to DB. Fire-and-forget broadcast.
 * - Silent drop on non-member: no ERROR frame sent — typing is non-critical.
 * - Silent drop on rate limit: 1 event per 2s per user/conversation.
 * - Defense-in-depth: member check in handler even though AuthChannelInterceptor
 *   already enforces it for all /app/conv.* destinations.
 *
 * Contract: SOCKET_EVENTS.md section 3.4 (Tuần 5).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatTypingHandler {

    private final SimpMessagingTemplate messagingTemplate;
    private final ConversationMemberRepository memberRepo;
    private final TypingRateLimiter typingRateLimiter;
    private final UserRepository userRepo;

    /**
     * Handle typing indicator from client.
     *
     * userId is extracted from Principal set by AuthChannelInterceptor at CONNECT time.
     * NEVER trust userId from payload.
     */
    @MessageMapping("/conv.{convId}.typing")
    public void handleTyping(
            @DestinationVariable UUID convId,
            @Payload TypingPayload payload,
            Principal principal
    ) {
        UUID userId = UUID.fromString(principal.getName());

        // 1. Authorize member — silent drop if not a member
        if (!memberRepo.existsByConversation_IdAndUser_Id(convId, userId)) {
            log.debug("[TYPING] Non-member attempt: userId={}, convId={}", userId, convId);
            return;
        }

        // 2. Rate limit: 1 event/2s/user/conv — silent drop if too fast
        String rateLimitKey = "rate:typing:" + userId + ":" + convId;
        if (!typingRateLimiter.allow(rateLimitKey)) {
            log.debug("[TYPING] Rate limited: userId={}, convId={}", userId, convId);
            return;
        }

        // 3. Load user info for broadcast — silent drop if user not found (should not happen)
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            log.warn("[TYPING] User not found: userId={}", userId);
            return;
        }

        // 4. Map action to event type
        // Any action that is not "START" is treated as STOP (includes "STOP" and unknown values)
        String eventType = "START".equals(payload.action()) ? "TYPING_STARTED" : "TYPING_STOPPED";

        // 5. Broadcast to /topic/conv.{convId} — all conversation subscribers receive it
        // Shape per SOCKET_EVENTS.md section 3.4: { type, payload: { userId, username, conversationId } }
        Map<String, Object> event = Map.of(
                "type", eventType,
                "payload", Map.of(
                        "userId", userId,
                        "username", user.getUsername(),
                        "conversationId", convId
                )
        );

        messagingTemplate.convertAndSend("/topic/conv." + convId, event);
        log.debug("[TYPING] Broadcast {}: userId={}, convId={}", eventType, userId, convId);
    }
}
