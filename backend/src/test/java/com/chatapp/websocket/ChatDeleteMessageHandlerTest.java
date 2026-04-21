package com.chatapp.websocket;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.dto.DeleteMessagePayload;
import com.chatapp.message.dto.EditMessagePayload;
import com.chatapp.message.dto.ErrorPayload;
import com.chatapp.message.dto.MessageDto;
import com.chatapp.message.dto.SenderDto;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.event.MessageDeletedEvent;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.security.Principal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatDeleteMessageHandler + MessageService.deleteViaStomp (W5-D3).
 *
 * Tests:
 *  T-DEL-01: happy path — soft delete → ACK operation=DELETE + broadcast MESSAGE_DELETED
 *  T-DEL-02: not-owner → MSG_NOT_FOUND (anti-enumeration)
 *  T-DEL-03: message not found → MSG_NOT_FOUND
 *  T-DEL-04: message already deleted → MSG_NOT_FOUND
 *  T-DEL-05: dedup — 2 frames same clientDeleteId → 1 DB update + 2 ACKs
 *  T-DEL-06: rate limit (11 deletes/min) → MSG_RATE_LIMITED
 *  T-DEL-07: edit after delete → MSG_NOT_FOUND (regression guard)
 *  T-DEL-08: MessageMapper strips content when deletedAt != null
 *  T-DEL-09: handler routes AppException to /user/queue/errors with operation="DELETE"
 *  T-DEL-10: handler routes generic Exception to /user/queue/errors INTERNAL
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatDeleteMessageHandlerTest {

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
    private ChatDeleteMessageHandler handler;

    private UUID userId;
    private UUID convId;
    private UUID messageId;

    private User mockUser;
    private Conversation mockConv;
    private Message mockMessage;
    private Principal principal;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository, memberRepository, conversationRepository,
                userRepository, redisTemplate, messageMapper, eventPublisher, messagingTemplate,
                fileRecordRepository, messageAttachmentRepository
        );
        handler = new ChatDeleteMessageHandler(messageService, messagingTemplate);

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
        mockConv.setId(convId);

        mockMessage = Message.builder()
                .id(messageId)
                .conversation(mockConv)
                .sender(mockUser)
                .type(MessageType.TEXT)
                .content("Hello world")
                .build();
        mockMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        // Default stubs
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Delete rate limit: count=1 (not exceeded)
        when(valueOps.increment(startsWith("rate:msg-delete:"))).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
        // Dedup: new key by default
        when(valueOps.setIfAbsent(startsWith("msg:delete-dedup:"), eq("PENDING"), any(Duration.class)))
                .thenReturn(true);
        // Member check: member by default
        when(memberRepository.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);
        // Message load: found by default
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(mockMessage));
        // Save returns same message (with deletedAt set by markAsDeletedBy)
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            // Simulate DB save — return the message as-is (markAsDeletedBy already called)
            return m;
        });
    }

    // =========================================================================
    // T-DEL-01: happy path — soft delete → ACK + broadcast MESSAGE_DELETED
    // =========================================================================

    @Test
    void deleteViaStomp_happyPath_softDeletesAndPublishesEvent() {
        String clientDeleteId = UUID.randomUUID().toString();
        DeleteMessagePayload payload = new DeleteMessagePayload(clientDeleteId, messageId);

        assertDoesNotThrow(() -> messageService.deleteViaStomp(convId, userId, payload));

        // Message should be saved (soft-deleted)
        verify(messageRepository).save(any(Message.class));
        // Event published after save
        verify(eventPublisher).publishEvent(any(MessageDeletedEvent.class));
        // Dedup value updated to messageId
        verify(valueOps).set(
                eq("msg:delete-dedup:" + userId + ":" + clientDeleteId),
                eq(messageId.toString()),
                any(Duration.class)
        );
        // Message must have deletedAt and deletedBy set
        ArgumentCaptor<Message> savedCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getDeletedAt()).isNotNull();
        assertThat(savedCaptor.getValue().getDeletedBy()).isEqualTo(userId);
    }

    // =========================================================================
    // T-DEL-02: not-owner → MSG_NOT_FOUND (anti-enumeration)
    // =========================================================================

    @Test
    void deleteViaStomp_notOwner_throwsMsgNotFound() {
        String clientDeleteId = UUID.randomUUID().toString();

        UUID otherUserId = UUID.randomUUID();
        User otherUser = User.builder()
                .id(otherUserId)
                .username("other")
                .email("other@test.com")
                .fullName("Other User")
                .passwordHash("$2a$12$hash")
                .status("active")
                .build();
        Message notOwnedMessage = Message.builder()
                .id(messageId)
                .conversation(mockConv)
                .sender(otherUser) // different sender
                .type(MessageType.TEXT)
                .content("Other's message")
                .build();
        notOwnedMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(notOwnedMessage));

        DeleteMessagePayload payload = new DeleteMessagePayload(clientDeleteId, messageId);

        AppException ex = assertThrows(AppException.class,
                () -> messageService.deleteViaStomp(convId, userId, payload));

        assertEquals("MSG_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        // Anti-enumeration: same code for not-owner and not-found
        assertThat(ex.getDetails()).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map.get("clientDeleteId")).isEqualTo(clientDeleteId));
    }

    // =========================================================================
    // T-DEL-03: message not found → MSG_NOT_FOUND
    // =========================================================================

    @Test
    void deleteViaStomp_messageNotFound_throwsMsgNotFound() {
        String clientDeleteId = UUID.randomUUID().toString();
        UUID fakeMessageId = UUID.randomUUID();

        when(messageRepository.findById(fakeMessageId)).thenReturn(Optional.empty());

        DeleteMessagePayload payload = new DeleteMessagePayload(clientDeleteId, fakeMessageId);

        AppException ex = assertThrows(AppException.class,
                () -> messageService.deleteViaStomp(convId, userId, payload));

        assertEquals("MSG_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // =========================================================================
    // T-DEL-04: message already deleted → MSG_NOT_FOUND
    // =========================================================================

    @Test
    void deleteViaStomp_alreadyDeleted_throwsMsgNotFound() {
        String clientDeleteId = UUID.randomUUID().toString();

        // Mark message as already soft-deleted
        mockMessage.markAsDeletedBy(userId);
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(mockMessage));

        DeleteMessagePayload payload = new DeleteMessagePayload(clientDeleteId, messageId);

        AppException ex = assertThrows(AppException.class,
                () -> messageService.deleteViaStomp(convId, userId, payload));

        assertEquals("MSG_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        // No DB save should happen
        verify(messageRepository, never()).save(any());
    }

    // =========================================================================
    // T-DEL-05: dedup — 2 frames same clientDeleteId → 1 DB update + 2 ACKs
    // =========================================================================

    @Test
    void deleteViaStomp_dedupDuplicateFrame_resendsAckIdempotently() {
        String clientDeleteId = UUID.randomUUID().toString();

        // First call: isNew=true (normal delete)
        // Second call: duplicate (isNew=false, value=messageId)
        when(valueOps.setIfAbsent(startsWith("msg:delete-dedup:"), eq("PENDING"), any(Duration.class)))
                .thenReturn(true)   // first call: new key
                .thenReturn(false); // second call: duplicate
        when(valueOps.get(startsWith("msg:delete-dedup:"))).thenReturn(messageId.toString());

        // After first save, message has deletedAt set
        when(messageRepository.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            return m; // markAsDeletedBy already set on m
        });

        // For the idempotent re-send, findById returns the already-deleted message
        Message alreadyDeletedMsg = Message.builder()
                .id(messageId)
                .conversation(mockConv)
                .sender(mockUser)
                .type(MessageType.TEXT)
                .content("Hello world") // content still in DB, mapper will strip it
                .build();
        alreadyDeletedMsg.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        alreadyDeletedMsg.markAsDeletedBy(userId);
        when(messageRepository.findById(messageId))
                .thenReturn(Optional.of(mockMessage))   // first call: not yet deleted
                .thenReturn(Optional.of(alreadyDeletedMsg)); // second call: already deleted (for dedup ACK)

        DeleteMessagePayload payload = new DeleteMessagePayload(clientDeleteId, messageId);

        // First call — saves
        assertDoesNotThrow(() -> messageService.deleteViaStomp(convId, userId, payload));
        // Second call — dedup re-send ACK
        assertDoesNotThrow(() -> messageService.deleteViaStomp(convId, userId, payload));

        // DB save called only once (first frame only)
        verify(messageRepository, times(1)).save(any(Message.class));
        // Event published only once
        verify(eventPublisher, times(1)).publishEvent(any(MessageDeletedEvent.class));
        // ACK sent at least once (idempotent re-send on second call)
        verify(messagingTemplate, atLeastOnce()).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/acks"), any()
        );
    }

    // =========================================================================
    // T-DEL-06: rate limit (11 deletes/min) → MSG_RATE_LIMITED
    // =========================================================================

    @Test
    void deleteViaStomp_rateLimitExceeded_throwsMsgRateLimited() {
        String clientDeleteId = UUID.randomUUID().toString();

        // Rate limit: count = 11 (over 10/min limit)
        when(valueOps.increment(startsWith("rate:msg-delete:"))).thenReturn(11L);
        when(redisTemplate.getExpire(anyString(), any())).thenReturn(45L);

        DeleteMessagePayload payload = new DeleteMessagePayload(clientDeleteId, messageId);

        AppException ex = assertThrows(AppException.class,
                () -> messageService.deleteViaStomp(convId, userId, payload));

        assertEquals("MSG_RATE_LIMITED", ex.getErrorCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertThat(ex.getDetails()).isInstanceOfSatisfying(Map.class,
                map -> assertThat(map.get("clientDeleteId")).isEqualTo(clientDeleteId));
    }

    // =========================================================================
    // T-DEL-07: edit after delete → MSG_NOT_FOUND (regression guard)
    // =========================================================================

    @Test
    void editViaStomp_afterSoftDelete_throwsMsgNotFound() {
        // Simulate: message was soft-deleted BEFORE an edit arrives
        mockMessage.markAsDeletedBy(userId);
        when(messageRepository.findById(messageId)).thenReturn(Optional.of(mockMessage));

        // Edit rate limit stubs
        when(valueOps.increment(startsWith("rate:msg-edit:"))).thenReturn(1L);
        when(valueOps.setIfAbsent(startsWith("msg:edit-dedup:"), eq("PENDING"), any(Duration.class)))
                .thenReturn(true);

        String clientEditId = UUID.randomUUID().toString();
        EditMessagePayload editPayload = new EditMessagePayload(clientEditId, messageId, "Try to edit deleted message");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.editViaStomp(convId, userId, editPayload));

        assertEquals("MSG_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    // =========================================================================
    // T-DEL-08: MessageMapper strips content when deletedAt != null
    // =========================================================================

    @Test
    void messageMapper_stripsContentWhenDeleted() {
        // Use a REAL MessageMapper (not a mock) to test the strip logic.
        // W6-D1: MessageMapper now depends on MessageAttachmentRepository + FileRecordRepository,
        // but deleted messages strip attachments to empty list without querying — safe to pass nulls.
        MessageMapper realMapper = new MessageMapper(null, null);

        // Build a deleted message
        Message deletedMsg = Message.builder()
                .id(messageId)
                .conversation(mockConv)
                .sender(mockUser)
                .type(MessageType.TEXT)
                .content("Secret content that should be stripped")
                .build();
        deletedMsg.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        deletedMsg.markAsDeletedBy(userId);

        MessageDto dto = realMapper.toDto(deletedMsg);

        // Content must be null (stripped)
        assertThat(dto.content()).isNull();
        // deletedAt must be set
        assertThat(dto.deletedAt()).isNotNull();
        // deletedBy must be the userId string
        assertThat(dto.deletedBy()).isEqualTo(userId.toString());
        // W6-D1: attachments must be empty list when deleted (strip)
        assertThat(dto.attachments()).isNotNull();
        assertThat(dto.attachments()).isEmpty();
    }

    // =========================================================================
    // T-DEL-09: handler routes AppException → /user/queue/errors operation="DELETE"
    // =========================================================================

    @Test
    void handler_appException_sendsErrorPayloadWithOperationDelete() {
        AppException ex = new AppException(HttpStatus.NOT_FOUND, "MSG_NOT_FOUND",
                "Tin nhắn không tồn tại hoặc không thể xóa",
                Map.of("clientDeleteId", "test-client-id"));

        handler.handleAppException(ex, principal);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/errors"), captor.capture()
        );

        assertThat(captor.getValue()).isInstanceOfSatisfying(ErrorPayload.class, payload -> {
            assertEquals("DELETE", payload.operation());
            assertEquals("test-client-id", payload.clientId());
            assertEquals("MSG_NOT_FOUND", payload.code());
        });
    }

    // =========================================================================
    // T-DEL-10: handler routes generic Exception → /user/queue/errors INTERNAL
    // =========================================================================

    @Test
    void handler_genericException_sendsInternalError() {
        RuntimeException ex = new RuntimeException("Unexpected DB failure");

        handler.handleGenericException(ex, principal);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/errors"), captor.capture()
        );

        assertThat(captor.getValue()).isInstanceOfSatisfying(ErrorPayload.class, payload -> {
            assertEquals("DELETE", payload.operation());
            assertEquals("unknown", payload.clientId());
            assertEquals("INTERNAL", payload.code());
        });
    }
}
