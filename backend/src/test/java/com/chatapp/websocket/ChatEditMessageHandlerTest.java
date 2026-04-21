package com.chatapp.websocket;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.dto.AckPayload;
import com.chatapp.message.dto.EditMessagePayload;
import com.chatapp.message.dto.ErrorPayload;
import com.chatapp.message.dto.MessageDto;
import com.chatapp.message.dto.SenderDto;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.event.MessageUpdatedEvent;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.service.MessageMapper;
import com.chatapp.message.service.MessageService;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatEditMessageHandler + MessageService.editViaStomp (W5-D2).
 *
 * Tests:
 *  T-EDIT-01: happy path — owner edits within window → ACK operation=EDIT + broadcast event
 *  T-EDIT-02: dedup — 2 frames same clientEditId → 1 DB update + 2 ACKs (idempotent)
 *  T-EDIT-03: not-owner → MSG_NOT_FOUND (anti-enumeration, no leak that message exists)
 *  T-EDIT-04: non-existent messageId → MSG_NOT_FOUND
 *  T-EDIT-05: edit window expired (createdAt 301s ago) → MSG_EDIT_WINDOW_EXPIRED
 *  T-EDIT-06: no-op edit (same content after trim) → MSG_NO_CHANGE
 *  T-EDIT-07: content > 5000 chars → MSG_CONTENT_TOO_LONG
 *  T-EDIT-08: rate limit (11 edits/min) → MSG_RATE_LIMITED
 *  T-EDIT-09: handler routes AppException to /user/queue/errors with operation="EDIT"
 *  T-EDIT-10: handler routes generic Exception to /user/queue/errors INTERNAL
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatEditMessageHandlerTest {

    // =========================================================================
    // Service-layer mocks
    // =========================================================================
    @Mock private MessageRepository messageRepository;
    @Mock private ConversationMemberRepository memberRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private MessageMapper messageMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private com.chatapp.file.repository.FileRecordRepository fileRecordRepository;
    @Mock private com.chatapp.file.repository.MessageAttachmentRepository messageAttachmentRepository;

    private MessageService messageService;
    private ChatEditMessageHandler handler;

    private UUID userId;
    private UUID convId;
    private UUID messageId;

    private User mockUser;
    private Conversation mockConv;
    private Message mockMessage;
    private MessageDto mockDto;
    private Principal principal;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository, memberRepository, conversationRepository,
                userRepository, redisTemplate, messageMapper, eventPublisher, messagingTemplate,
                fileRecordRepository, messageAttachmentRepository
        );
        handler = new ChatEditMessageHandler(messageService, messagingTemplate);

        userId = UUID.randomUUID();
        convId = UUID.randomUUID();
        messageId = UUID.randomUUID();

        principal = () -> userId.toString();

        mockUser = User.builder()
                .id(userId)
                .username("testuser")
                .email("test@test.com")
                .fullName("Test User")
                .passwordHash("$2a$12$hash")
                .status("active")
                .build();

        mockConv = Conversation.builder().build();
        // Set conversation id via reflection/builder workaround — use a spy or set directly
        // Actually Conversation uses @PrePersist, but for test we can set it manually:
        mockConv.setId(convId);

        mockMessage = Message.builder()
                .id(messageId)
                .conversation(mockConv)
                .sender(mockUser)
                .type(MessageType.TEXT)
                .content("Original content")
                .build();
        mockMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC)); // fresh, within edit window

        SenderDto senderDto = new SenderDto(userId, "testuser", "Test User", null);
        mockDto = new MessageDto(
                messageId, convId, senderDto, MessageType.TEXT,
                "Updated content", java.util.Collections.emptyList(),
                null, OffsetDateTime.now(ZoneOffset.UTC),
                mockMessage.getCreatedAt(), null, null
        );

        // Default stubs
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Edit rate limit: count=1 (not exceeded)
        when(valueOps.increment(startsWith("rate:msg-edit:"))).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
        // Dedup: new key by default
        when(valueOps.setIfAbsent(startsWith("msg:edit-dedup:"), eq("PENDING"), any(Duration.class)))
                .thenReturn(true);
        // Member check: member by default
        when(memberRepository.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);
        // Message load: found by default
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(mockMessage));
        // Save returns same message
        when(messageRepository.save(any(Message.class))).thenReturn(mockMessage);
        // Reload after save
        when(messageMapper.toDto(mockMessage)).thenReturn(mockDto);
    }

    // =========================================================================
    // T-EDIT-01: happy path — owner edits within window → ACK + broadcast
    // =========================================================================

    @Test
    void editViaStomp_happyPath_savesUpdatedMessageAndPublishesEvent() {
        String clientEditId = UUID.randomUUID().toString();
        // Reload after save
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(mockMessage));

        EditMessagePayload payload = new EditMessagePayload(clientEditId, messageId, "Updated content");

        assertDoesNotThrow(() -> messageService.editViaStomp(convId, userId, payload));

        // Message should be saved with updated content
        verify(messageRepository).save(any(Message.class));
        // Event published
        verify(eventPublisher).publishEvent(any(MessageUpdatedEvent.class));
        // Dedup value updated to messageId
        verify(valueOps).set(
                eq("msg:edit-dedup:" + userId + ":" + clientEditId),
                eq(messageId.toString()),
                any(Duration.class)
        );
    }

    // =========================================================================
    // T-EDIT-02: dedup — 2 frames same clientEditId → 1 DB update + 2 ACKs
    // =========================================================================

    @Test
    void editViaStomp_dedupDuplicateFrame_resendsAckIdempotently() {
        String clientEditId = UUID.randomUUID().toString();

        // First call: isNew = true (normal save)
        when(valueOps.setIfAbsent(startsWith("msg:edit-dedup:"), eq("PENDING"), any(Duration.class)))
                .thenReturn(true)
                .thenReturn(false); // Second call: duplicate

        // Second call: value = real messageId
        when(valueOps.get(startsWith("msg:edit-dedup:"))).thenReturn(messageId.toString());
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(mockMessage));

        EditMessagePayload payload = new EditMessagePayload(clientEditId, messageId, "Updated content");

        // First call — saves
        assertDoesNotThrow(() -> messageService.editViaStomp(convId, userId, payload));
        // Second call — dedup re-send ACK
        assertDoesNotThrow(() -> messageService.editViaStomp(convId, userId, payload));

        // DB save called only once (for first frame)
        verify(messageRepository, times(1)).save(any(Message.class));
        // Event published only once (no double broadcast)
        verify(eventPublisher, times(1)).publishEvent(any(MessageUpdatedEvent.class));
        // ACK sent to user (idempotent re-send on second call)
        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/acks"), any()
        );
    }

    // =========================================================================
    // T-EDIT-03: not-owner → MSG_NOT_FOUND (anti-enumeration)
    // =========================================================================

    @Test
    void editViaStomp_notOwner_throwsMsgNotFound() {
        String clientEditId = UUID.randomUUID().toString();

        // Message belongs to a DIFFERENT user
        UUID differentUserId = UUID.randomUUID();
        User otherUser = User.builder()
                .id(differentUserId)
                .username("other")
                .email("other@test.com")
                .fullName("Other User")
                .passwordHash("$2a$12$hash")
                .status("active")
                .build();
        Message notOwnedMessage = Message.builder()
                .id(messageId)
                .conversation(mockConv)
                .sender(otherUser)   // different sender
                .type(MessageType.TEXT)
                .content("Other's content")
                .build();
        notOwnedMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(notOwnedMessage));

        EditMessagePayload payload = new EditMessagePayload(clientEditId, messageId, "Updated content");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, userId, payload));

        assertEquals("MSG_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        // Anti-enumeration: same code for not-owner and not-found
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("clientEditId")).isEqualTo(clientEditId));
    }

    // =========================================================================
    // T-EDIT-04: non-existent messageId → MSG_NOT_FOUND
    // =========================================================================

    @Test
    void editViaStomp_nonExistentMessage_throwsMsgNotFound() {
        String clientEditId = UUID.randomUUID().toString();
        UUID fakeMessageId = UUID.randomUUID();

        when(messageRepository.findById(fakeMessageId)).thenReturn(Optional.empty());

        EditMessagePayload payload = new EditMessagePayload(clientEditId, fakeMessageId, "Updated content");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, userId, payload));

        assertEquals("MSG_NOT_FOUND", ex.getErrorCode());
    }

    // =========================================================================
    // T-EDIT-05: edit window expired (createdAt 301s ago) → MSG_EDIT_WINDOW_EXPIRED
    // =========================================================================

    @Test
    void editViaStomp_editWindowExpired_throwsMsgEditWindowExpired() {
        String clientEditId = UUID.randomUUID().toString();

        // Set createdAt 301 seconds in the past
        mockMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(301));

        EditMessagePayload payload = new EditMessagePayload(clientEditId, messageId, "Updated content");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, userId, payload));

        assertEquals("MSG_EDIT_WINDOW_EXPIRED", ex.getErrorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("clientEditId")).isEqualTo(clientEditId));
    }

    // =========================================================================
    // T-EDIT-06: no-op edit (same content after trim) → MSG_NO_CHANGE
    // =========================================================================

    @Test
    void editViaStomp_sameContentAfterTrim_throwsMsgNoChange() {
        String clientEditId = UUID.randomUUID().toString();

        // newContent is same as message.content (with surrounding whitespace)
        EditMessagePayload payload = new EditMessagePayload(clientEditId, messageId, "  Original content  ");
        // mockMessage.content = "Original content"

        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, userId, payload));

        assertEquals("MSG_NO_CHANGE", ex.getErrorCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
    }

    // =========================================================================
    // T-EDIT-07: content > 5000 chars → MSG_CONTENT_TOO_LONG
    // =========================================================================

    @Test
    void editViaStomp_contentTooLong_throwsMsgContentTooLong() {
        String clientEditId = UUID.randomUUID().toString();
        String longContent = "A".repeat(5001);

        EditMessagePayload payload = new EditMessagePayload(clientEditId, messageId, longContent);

        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, userId, payload));

        assertEquals("MSG_CONTENT_TOO_LONG", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("clientEditId")).isEqualTo(clientEditId));
    }

    // =========================================================================
    // T-EDIT-08: rate limit (11 edits/min) → MSG_RATE_LIMITED
    // =========================================================================

    @Test
    void editViaStomp_rateLimitExceeded_throwsMsgRateLimited() {
        String clientEditId = UUID.randomUUID().toString();

        // Rate limit: count = 11 (over 10/min limit)
        when(valueOps.increment(startsWith("rate:msg-edit:"))).thenReturn(11L);
        when(redisTemplate.getExpire(anyString(), any())).thenReturn(45L);

        EditMessagePayload payload = new EditMessagePayload(clientEditId, messageId, "Updated content");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, userId, payload));

        assertEquals("MSG_RATE_LIMITED", ex.getErrorCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("clientEditId")).isEqualTo(clientEditId));
    }

    // =========================================================================
    // T-EDIT-09: handler routes AppException → /user/queue/errors with operation="EDIT"
    // =========================================================================

    @Test
    void handleAppException_routesErrorWithEditOperation() {
        String clientEditId = UUID.randomUUID().toString();
        AppException ex = new AppException(
                HttpStatus.NOT_FOUND, "MSG_NOT_FOUND", "Tin nhắn không tồn tại",
                java.util.Map.of("clientEditId", clientEditId)
        );

        handler.handleAppException(ex, principal);

        ArgumentCaptor<ErrorPayload> captor = ArgumentCaptor.forClass(ErrorPayload.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/errors"), captor.capture()
        );
        ErrorPayload error = captor.getValue();
        assertEquals("EDIT", error.operation());
        assertEquals(clientEditId, error.clientId());
        assertEquals("MSG_NOT_FOUND", error.code());
        assertEquals("Tin nhắn không tồn tại", error.error());
    }

    // =========================================================================
    // T-EDIT-10: handler routes generic Exception → INTERNAL error with operation="EDIT"
    // =========================================================================

    @Test
    void handleGenericException_routesInternalError() {
        RuntimeException ex = new RuntimeException("Unexpected DB error");

        handler.handleGenericException(ex, principal);

        ArgumentCaptor<ErrorPayload> captor = ArgumentCaptor.forClass(ErrorPayload.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/errors"), captor.capture()
        );
        ErrorPayload error = captor.getValue();
        assertEquals("EDIT", error.operation());
        assertEquals("unknown", error.clientId());
        assertEquals("INTERNAL", error.code());
    }

    // =========================================================================
    // T-EDIT-11: invalid clientEditId format → VALIDATION_FAILED
    // =========================================================================

    @Test
    void editViaStomp_invalidClientEditIdFormat_throwsValidationFailed() {
        EditMessagePayload payload = new EditMessagePayload("not-a-uuid", messageId, "Updated content");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, userId, payload));

        assertEquals("VALIDATION_FAILED", ex.getErrorCode());
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("clientEditId")).isEqualTo("not-a-uuid"));
    }

    // =========================================================================
    // T-EDIT-12: ACK payload shape — operation="EDIT", clientId=clientEditId
    // =========================================================================

    @Test
    void editViaStomp_happyPath_ackPayloadHasCorrectShape() {
        String clientEditId = UUID.randomUUID().toString();
        EditMessagePayload payload = new EditMessagePayload(clientEditId, messageId, "Updated content");

        // Without active transaction, sendEditAck fires immediately (fallback path in service)
        assertDoesNotThrow(() -> messageService.editViaStomp(convId, userId, payload));

        // In unit test (no Spring container), TransactionSynchronizationManager is inactive →
        // sendEditAck fires synchronously as fallback
        ArgumentCaptor<Object> ackCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/acks"), ackCaptor.capture()
        );
        // Find the EDIT ACK
        Object ackValue = ackCaptor.getAllValues().stream()
                .filter(v -> v instanceof AckPayload ap && "EDIT".equals(ap.operation()))
                .findFirst().orElse(null);
        assertNotNull(ackValue, "Expected EDIT ACK to be sent");
        AckPayload ack = (AckPayload) ackValue;
        assertEquals("EDIT", ack.operation());
        assertEquals(clientEditId, ack.clientId());
        assertEquals(mockDto, ack.message());
    }
}
