package com.chatapp.websocket;

import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.message.dto.TypingPayload;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatTypingHandler.
 *
 * T-TYPING-01: happy path START — member publishes START → convertAndSend called with TYPING_STARTED envelope.
 * T-TYPING-02: happy path STOP — member publishes STOP → convertAndSend called with TYPING_STOPPED envelope.
 * T-TYPING-03: non-member — convertAndSend NOT called (silent drop).
 * T-TYPING-04: rate limit — 2 calls within window → only first broadcast, second dropped.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatTypingHandlerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ConversationMemberRepository memberRepo;

    @Mock
    private TypingRateLimiter typingRateLimiter;

    @Mock
    private UserRepository userRepo;

    private ChatTypingHandler handler;

    private UUID userId;
    private UUID convId;
    private User user;
    private Principal principal;

    @BeforeEach
    void setUp() {
        handler = new ChatTypingHandler(messagingTemplate, memberRepo, typingRateLimiter, userRepo);

        userId = UUID.randomUUID();
        convId = UUID.randomUUID();

        user = User.builder()
                .id(userId)
                .email("test@example.com")
                .username("testuser")
                .fullName("Test User")
                .build();

        principal = () -> userId.toString();

        // Default: user is a member
        when(memberRepo.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);

        // Default: rate limit allows
        when(typingRateLimiter.allow(anyString())).thenReturn(true);

        // Default: user found
        when(userRepo.findById(userId)).thenReturn(Optional.of(user));
    }

    // =========================================================================
    // T-TYPING-01: happy path START
    // =========================================================================

    @Test
    void handleTyping_startAction_broadcastsTypingStarted() {
        TypingPayload payload = new TypingPayload("START");

        handler.handleTyping(convId, payload, principal);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/conv." + convId),
                captor.capture()
        );

        Map<String, Object> envelope = captor.getValue();
        assertEquals("TYPING_STARTED", envelope.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> eventPayload = (Map<String, Object>) envelope.get("payload");
        assertNotNull(eventPayload);
        assertEquals(userId, eventPayload.get("userId"));
        assertEquals("testuser", eventPayload.get("username"));
        assertEquals(convId, eventPayload.get("conversationId"));
    }

    // =========================================================================
    // T-TYPING-02: happy path STOP
    // =========================================================================

    @Test
    void handleTyping_stopAction_broadcastsTypingStopped() {
        TypingPayload payload = new TypingPayload("STOP");

        handler.handleTyping(convId, payload, principal);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/conv." + convId),
                captor.capture()
        );

        Map<String, Object> envelope = captor.getValue();
        assertEquals("TYPING_STOPPED", envelope.get("type"));
    }

    // =========================================================================
    // T-TYPING-03: non-member — silent drop
    // =========================================================================

    @Test
    void handleTyping_nonMember_doesNotBroadcast() {
        when(memberRepo.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(false);

        TypingPayload payload = new TypingPayload("START");

        handler.handleTyping(convId, payload, principal);

        // No broadcast should occur
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    // =========================================================================
    // T-TYPING-04: rate limit — second call within window is dropped
    // =========================================================================

    @Test
    void handleTyping_rateLimited_secondCallDropped() {
        TypingPayload payload = new TypingPayload("START");
        String expectedKey = "rate:typing:" + userId + ":" + convId;

        // First call allowed, second call denied
        when(typingRateLimiter.allow(expectedKey))
                .thenReturn(true)
                .thenReturn(false);

        // First call — should broadcast
        handler.handleTyping(convId, payload, principal);

        // Second call within rate limit window — should be dropped
        handler.handleTyping(convId, payload, principal);

        // convertAndSend should only be called once (for the first allowed call)
        verify(messagingTemplate, times(1)).convertAndSend(anyString(), any(Object.class));
    }
}
