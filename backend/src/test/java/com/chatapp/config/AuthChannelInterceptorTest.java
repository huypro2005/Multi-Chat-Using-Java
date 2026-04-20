package com.chatapp.config;

import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthChannelInterceptor} — destination-aware policy (W5-D1).
 *
 * <p>Test matrix:
 * <ul>
 *   <li>T-AUTH-SEND-01: Non-member SEND .message → FORBIDDEN</li>
 *   <li>T-AUTH-SEND-02: Non-member SEND .typing  → pass (SILENT_DROP)</li>
 *   <li>T-AUTH-SEND-03: Non-member SEND .read    → pass (SILENT_DROP)</li>
 *   <li>T-AUTH-SEND-04: Member SEND .message     → pass (STRICT_MEMBER authorized)</li>
 *   <li>T-AUTH-SEND-05: No principal SEND        → AUTH_REQUIRED</li>
 *   <li>T-AUTH-SUB-01:  Non-member SUBSCRIBE /topic/conv.{id} → FORBIDDEN (unchanged)</li>
 *   <li>T-AUTH-POLICY-01..04: resolveSendPolicy() coverage</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuthChannelInterceptorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private ConversationMemberRepository conversationMemberRepository;

    private AuthChannelInterceptor interceptor;

    private UUID userId;
    private UUID convId;
    private Principal principal;

    @BeforeEach
    void setUp() {
        interceptor = new AuthChannelInterceptor(jwtTokenProvider, conversationMemberRepository);

        userId = UUID.randomUUID();
        convId = UUID.randomUUID();
        principal = () -> userId.toString();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Build a minimal STOMP SEND {@link Message} with the given destination and optional principal.
     * Uses mutable (writable) header accessor so the interceptor can read values properly.
     */
    private Message<?> buildSendMessage(String destination, Principal p) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(destination);
        if (p != null) {
            accessor.setUser(p);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * Build a minimal STOMP SUBSCRIBE {@link Message}.
     */
    private Message<?> buildSubscribeMessage(String destination, Principal p) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        if (p != null) {
            accessor.setUser(p);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    // =========================================================================
    // T-AUTH-SEND-01: Non-member SEND .message → FORBIDDEN
    // =========================================================================

    @Test
    void send_message_nonMember_throwsForbidden() {
        when(conversationMemberRepository.existsByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(false);

        String destination = "/app/conv." + convId + ".message";
        Message<?> msg = buildSendMessage(destination, principal);

        MessageDeliveryException ex = assertThrows(
                MessageDeliveryException.class,
                () -> interceptor.preSend(msg, null)
        );
        assertEquals("FORBIDDEN", ex.getMessage());
    }

    // =========================================================================
    // T-AUTH-SEND-02: Non-member SEND .typing → pass (SILENT_DROP)
    // =========================================================================

    @Test
    void send_typing_nonMember_passesThrough() {
        // Repository should NOT even be called for SILENT_DROP destinations.
        String destination = "/app/conv." + convId + ".typing";
        Message<?> msg = buildSendMessage(destination, principal);

        // Should not throw
        Message<?> result = assertDoesNotThrow(() -> interceptor.preSend(msg, null));
        assertNotNull(result);

        // Member check must NOT be invoked — handler is responsible for silent drop.
        verifyNoInteractions(conversationMemberRepository);
    }

    // =========================================================================
    // T-AUTH-SEND-03: Non-member SEND .read → pass (SILENT_DROP)
    // =========================================================================

    @Test
    void send_read_nonMember_passesThrough() {
        String destination = "/app/conv." + convId + ".read";
        Message<?> msg = buildSendMessage(destination, principal);

        Message<?> result = assertDoesNotThrow(() -> interceptor.preSend(msg, null));
        assertNotNull(result);

        verifyNoInteractions(conversationMemberRepository);
    }

    // =========================================================================
    // T-AUTH-SEND-04: Member SEND .message → pass
    // =========================================================================

    @Test
    void send_message_member_passesThrough() {
        when(conversationMemberRepository.existsByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(true);

        String destination = "/app/conv." + convId + ".message";
        Message<?> msg = buildSendMessage(destination, principal);

        Message<?> result = assertDoesNotThrow(() -> interceptor.preSend(msg, null));
        assertNotNull(result);

        verify(conversationMemberRepository).existsByConversation_IdAndUser_Id(convId, userId);
    }

    // =========================================================================
    // T-AUTH-SEND-05: No principal SEND → AUTH_REQUIRED
    // =========================================================================

    @Test
    void send_noPrincipal_throwsAuthRequired() {
        String destination = "/app/conv." + convId + ".message";
        Message<?> msg = buildSendMessage(destination, null);

        MessageDeliveryException ex = assertThrows(
                MessageDeliveryException.class,
                () -> interceptor.preSend(msg, null)
        );
        assertEquals("AUTH_REQUIRED", ex.getMessage());
    }

    // =========================================================================
    // T-AUTH-SUB-01: Non-member SUBSCRIBE /topic/conv.{id} → FORBIDDEN (unchanged)
    // =========================================================================

    @Test
    void subscribe_topicConv_nonMember_throwsForbidden() {
        when(conversationMemberRepository.existsByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(false);

        String destination = "/topic/conv." + convId;
        Message<?> msg = buildSubscribeMessage(destination, principal);

        MessageDeliveryException ex = assertThrows(
                MessageDeliveryException.class,
                () -> interceptor.preSend(msg, null)
        );
        assertEquals("FORBIDDEN", ex.getMessage());
    }

    // =========================================================================
    // T-AUTH-POLICY-01..04: resolveSendPolicy() unit coverage
    // =========================================================================

    @Test
    void resolveSendPolicy_messageSuffix_returnsStrictMember() {
        String dest = "/app/conv." + convId + ".message";
        assertEquals(
                AuthChannelInterceptor.DestinationPolicy.STRICT_MEMBER,
                interceptor.resolveSendPolicy(dest)
        );
    }

    @Test
    void resolveSendPolicy_typingSuffix_returnsSilentDrop() {
        String dest = "/app/conv." + convId + ".typing";
        assertEquals(
                AuthChannelInterceptor.DestinationPolicy.SILENT_DROP,
                interceptor.resolveSendPolicy(dest)
        );
    }

    @Test
    void resolveSendPolicy_readSuffix_returnsSilentDrop() {
        String dest = "/app/conv." + convId + ".read";
        assertEquals(
                AuthChannelInterceptor.DestinationPolicy.SILENT_DROP,
                interceptor.resolveSendPolicy(dest)
        );
    }

    @Test
    void resolveSendPolicy_unknownSuffix_returnsStrictMemberDefault() {
        String dest = "/app/conv." + convId + ".somethingNew";
        assertEquals(
                AuthChannelInterceptor.DestinationPolicy.STRICT_MEMBER,
                interceptor.resolveSendPolicy(dest)
        );
    }
}
