package com.chatapp.config;

import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.security.JwtTokenProvider;
import com.chatapp.security.JwtTokenProvider.TokenValidationResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.UUID;

/**
 * Verify JWT ở CONNECT và authorize member ở SUBSCRIBE/SEND cho STOMP inbound channel.
 * <p>
 * CONNECT:
 *   - Required header {@code Authorization: Bearer <accessToken>}.
 *   - Reuse {@link JwtTokenProvider#validateTokenDetailed(String)} để phân biệt EXPIRED vs INVALID.
 *   - Gán {@link StompPrincipal} vào session accessor — các frame tiếp theo sẽ có {@code getUser()}.
 * <p>
 * SUBSCRIBE:
 *   - Nếu destination bắt đầu bằng {@code /topic/conv.} → kiểm tra user là member của conversation.
 *   - Destinations khác (/user/queue/*, /topic/presence.*) để pass cho Spring tự xử lý.
 * <p>
 * SEND (inbound from client):
 *   - Áp dụng {@link DestinationPolicy} theo destination suffix:
 *     - {@code .message} → STRICT_MEMBER: throw FORBIDDEN nếu non-member → ERROR frame về client.
 *     - {@code .typing}  → SILENT_DROP: pass through, handler tự silent-drop non-member.
 *     - {@code .read}    → SILENT_DROP: (Tuần 5) pass through, handler xử lý.
 *     - Mọi suffix khác  → STRICT_MEMBER (default an toàn).
 * <p>
 * Exception handling: throw {@link MessageDeliveryException} với message là error code
 * (AUTH_REQUIRED, AUTH_TOKEN_EXPIRED, FORBIDDEN). Spring STOMP sẽ convert thành ERROR frame
 * với header {@code message} = error code — FE parse theo SOCKET_EVENTS.md mục 6.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthChannelInterceptor implements ChannelInterceptor {

    private static final String TOPIC_CONV_PREFIX = "/topic/conv.";
    private static final String APP_CONV_PREFIX = "/app/conv.";

    /**
     * Policy xác định cách xử lý SEND frame từ non-member.
     *
     * <ul>
     *   <li>{@link #STRICT_MEMBER} — throw FORBIDDEN → ERROR frame về client.
     *       Dùng cho destination quan trọng như {@code .message}.</li>
     *   <li>{@link #SILENT_DROP} — interceptor pass through, handler tự silent-drop.
     *       Dùng cho ephemeral events như {@code .typing}, {@code .read}.</li>
     * </ul>
     */
    enum DestinationPolicy {
        STRICT_MEMBER,
        SILENT_DROP
    }

    private final JwtTokenProvider jwtTokenProvider;
    private final ConversationMemberRepository conversationMemberRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }

        switch (command) {
            case CONNECT -> handleConnect(accessor);
            case SUBSCRIBE -> handleSubscribe(accessor);
            case SEND -> handleSend(accessor);
            default -> {
                // SEND, DISCONNECT, UNSUBSCRIBE, ... — pass through cho W4.
                // SEND authorization + rate limit sẽ được thêm ở Tuần 5 khi có /app/* inbound.
            }
        }
        return message;
    }

    private void handleConnect(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("STOMP CONNECT rejected: missing or malformed Authorization header");
            throw new MessageDeliveryException("AUTH_REQUIRED");
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            throw new MessageDeliveryException("AUTH_REQUIRED");
        }

        TokenValidationResult result = jwtTokenProvider.validateTokenDetailed(token);
        switch (result) {
            case VALID -> {
                UUID userId = jwtTokenProvider.getUserIdFromToken(token);
                accessor.setUser(new StompPrincipal(userId.toString()));
                log.debug("STOMP CONNECT authenticated: userId={}", userId);
            }
            case EXPIRED -> {
                log.debug("STOMP CONNECT rejected: token expired");
                throw new MessageDeliveryException("AUTH_TOKEN_EXPIRED");
            }
            default -> {
                log.debug("STOMP CONNECT rejected: token invalid");
                throw new MessageDeliveryException("AUTH_REQUIRED");
            }
        }
    }

    private void handleSubscribe(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            // Defense-in-depth: CONNECT đã đảm bảo principal, nhưng check lại phòng edge cases.
            throw new MessageDeliveryException("AUTH_REQUIRED");
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        if (destination.startsWith(TOPIC_CONV_PREFIX)) {
            String convIdStr = destination.substring(TOPIC_CONV_PREFIX.length());
            UUID convId;
            try {
                convId = UUID.fromString(convIdStr);
            } catch (IllegalArgumentException e) {
                log.debug("STOMP SUBSCRIBE rejected: invalid conversation UUID '{}'", convIdStr);
                throw new MessageDeliveryException("FORBIDDEN");
            }

            UUID userId;
            try {
                userId = UUID.fromString(principal.getName());
            } catch (IllegalArgumentException e) {
                log.warn("STOMP SUBSCRIBE: principal name is not a UUID: {}", principal.getName());
                throw new MessageDeliveryException("AUTH_REQUIRED");
            }

            boolean isMember = conversationMemberRepository
                    .existsByConversation_IdAndUser_Id(convId, userId);
            if (!isMember) {
                log.debug("STOMP SUBSCRIBE rejected: userId={} not member of convId={}", userId, convId);
                throw new MessageDeliveryException("FORBIDDEN");
            }
            log.debug("STOMP SUBSCRIBE authorized: userId={} → convId={}", userId, convId);
        }
        // /topic/presence.*, /user/queue/* → pass; sẽ authorize khi implement Tuần 5/7.
    }

    private void handleSend(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new MessageDeliveryException("AUTH_REQUIRED");
        }

        String destination = accessor.getDestination();
        if (destination == null || !destination.startsWith(APP_CONV_PREFIX)) {
            return;
        }

        DestinationPolicy policy = resolveSendPolicy(destination);
        if (policy == DestinationPolicy.SILENT_DROP) {
            // Handler (ChatTypingHandler, etc.) is responsible for silent-dropping non-members.
            return;
        }

        // STRICT_MEMBER: extract convId + verify membership.
        String suffix = destination.substring(APP_CONV_PREFIX.length());
        int dotIndex = suffix.indexOf('.');
        if (dotIndex <= 0) {
            throw new MessageDeliveryException("FORBIDDEN");
        }

        UUID convId;
        try {
            convId = UUID.fromString(suffix.substring(0, dotIndex));
        } catch (IllegalArgumentException e) {
            throw new MessageDeliveryException("FORBIDDEN");
        }

        UUID userId;
        try {
            userId = UUID.fromString(principal.getName());
        } catch (IllegalArgumentException e) {
            throw new MessageDeliveryException("AUTH_REQUIRED");
        }

        boolean isMember = conversationMemberRepository.existsByConversation_IdAndUser_Id(convId, userId);
        if (!isMember) {
            log.debug("STOMP SEND rejected: userId={} not member of convId={}", userId, convId);
            throw new MessageDeliveryException("FORBIDDEN");
        }
        log.debug("STOMP SEND authorized: userId={} → destination={}", userId, destination);
    }

    /**
     * Resolve {@link DestinationPolicy} based on the inbound SEND destination suffix.
     *
     * <p>Rules (ordered by specificity):
     * <ul>
     *   <li>{@code .message} — STRICT_MEMBER (persistent event, auth required)</li>
     *   <li>{@code .typing}  — SILENT_DROP (ephemeral, handler handles non-member)</li>
     *   <li>{@code .read}    — SILENT_DROP (ephemeral, Tuần 5 prep)</li>
     *   <li>everything else  — STRICT_MEMBER (safe default)</li>
     * </ul>
     */
    DestinationPolicy resolveSendPolicy(String destination) {
        if (destination.endsWith(".message")) return DestinationPolicy.STRICT_MEMBER;
        if (destination.endsWith(".edit"))    return DestinationPolicy.STRICT_MEMBER;
        if (destination.endsWith(".delete"))  return DestinationPolicy.STRICT_MEMBER;
        if (destination.endsWith(".typing"))  return DestinationPolicy.SILENT_DROP;
        if (destination.endsWith(".read"))    return DestinationPolicy.SILENT_DROP;
        return DestinationPolicy.STRICT_MEMBER;
    }
}
