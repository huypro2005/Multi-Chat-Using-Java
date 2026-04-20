package com.chatapp.message;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.dto.AckPayload;
import com.chatapp.message.dto.MessageDto;
import com.chatapp.message.dto.SendMessagePayload;
import com.chatapp.message.dto.SenderDto;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.event.MessageCreatedEvent;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests cho MessageService.sendViaStomp (Path B — ADR-016).
 *
 * Tests:
 *  T-STOMP-01: happy path — message saved, ACK via TransactionSynchronization.
 *  T-STOMP-02: non-member conv → AppException CONV_NOT_FOUND.
 *  T-STOMP-03: content 5001 chars → AppException MSG_CONTENT_TOO_LONG.
 *  T-STOMP-04: blank content → AppException VALIDATION_FAILED.
 *  T-STOMP-05: invalid tempId format → AppException VALIDATION_FAILED.
 *  T-STOMP-06: rate limit exceeded → AppException MSG_RATE_LIMITED.
 *  T-STOMP-07: duplicate tempId (PENDING) → silently dropped (no save, no ACK).
 *  T-STOMP-08: duplicate tempId (real messageId) → ACK re-sent idempotently (no new save).
 *  T-STOMP-09: Redis unavailable for rate limit → fail-open (message processed normally).
 *  T-STOMP-10: null tempId → AppException VALIDATION_FAILED with echoed tempId="unknown".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessageServiceStompTest {

    @Mock private MessageRepository messageRepository;
    @Mock private ConversationMemberRepository memberRepository;
    @Mock private ConversationRepository conversationRepository;
    @Mock private UserRepository userRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private MessageMapper messageMapper;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private MessageService messageService;

    private UUID userId;
    private UUID convId;
    private User mockUser;
    private Conversation mockConv;
    private Message mockSavedMessage;
    private MessageDto mockDto;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(
                messageRepository, memberRepository, conversationRepository,
                userRepository, redisTemplate, messageMapper, eventPublisher, messagingTemplate
        );

        userId = UUID.randomUUID();
        convId = UUID.randomUUID();

        mockUser = User.builder()
                .username("testuser")
                .email("test@test.com")
                .fullName("Test User")
                .passwordHash("$2a$12$hash")
                .status("active")
                .build();
        // Set id via reflection substitute — use builder with explicit UUID
        // Actually need to set id on user; use a real UUID
        mockConv = Conversation.builder().build();

        UUID savedMsgId = UUID.randomUUID();
        mockSavedMessage = Message.builder()
                .id(savedMsgId)
                .conversation(mockConv)
                .sender(mockUser)
                .type(MessageType.TEXT)
                .content("Hello")
                .build();
        // Set createdAt for mapper
        mockSavedMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        SenderDto senderDto = new SenderDto(userId, "testuser", "Test User", null);
        mockDto = new MessageDto(
                savedMsgId, convId, senderDto, MessageType.TEXT,
                "Hello", null, null, OffsetDateTime.now(ZoneOffset.UTC)
        );

        // Default: valueOps is returned by redisTemplate.opsForValue()
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // =========================================================================
    // Helper: valid payload
    // =========================================================================

    private SendMessagePayload validPayload(String tempId) {
        return new SendMessagePayload(tempId, "Hello", "TEXT");
    }

    // =========================================================================
    // T-STOMP-01: happy path — all steps execute
    // =========================================================================

    @Test
    void sendViaStomp_happyPath_savesMessageAndPublishesEvent() {
        String tempId = UUID.randomUUID().toString();

        when(memberRepository.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);
        // Rate limit: count = 1
        when(valueOps.increment(startsWith("rate:msg:"))).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
        // Dedup: new key
        when(valueOps.setIfAbsent(anyString(), eq("PENDING"), any(Duration.class))).thenReturn(true);
        // Save
        when(userRepository.getReferenceById(userId)).thenReturn(mockUser);
        when(conversationRepository.getReferenceById(convId)).thenReturn(mockConv);
        when(messageRepository.save(any(Message.class))).thenReturn(mockSavedMessage);
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(mockConv));
        when(messageRepository.findById(any(UUID.class))).thenReturn(Optional.of(mockSavedMessage));
        when(messageMapper.toDto(mockSavedMessage)).thenReturn(mockDto);

        // sendViaStomp is @Transactional — in unit test without Spring container,
        // TransactionSynchronizationManager is NOT active → registerSynchronization silently
        // skips (afterCommit never fires). We only verify save + event published.
        assertDoesNotThrow(() -> messageService.sendViaStomp(convId, userId, validPayload(tempId)));

        verify(messageRepository).save(any(Message.class));
        verify(eventPublisher).publishEvent(any(MessageCreatedEvent.class));
        // Dedup value should be updated to real messageId
        verify(valueOps).set(eq("msg:dedup:" + userId + ":" + tempId),
                eq(mockSavedMessage.getId().toString()),
                any(Duration.class));
    }

    // =========================================================================
    // T-STOMP-02: non-member conv → CONV_NOT_FOUND
    // =========================================================================

    @Test
    void sendViaStomp_nonMember_throwsConvNotFound() {
        String tempId = UUID.randomUUID().toString();
        when(memberRepository.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(false);

        AppException ex = assertThrows(AppException.class,
                () -> messageService.sendViaStomp(convId, userId, validPayload(tempId)));

        assertEquals("CONV_NOT_FOUND", ex.getErrorCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        // tempId must be in details for echo back
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("tempId")).isEqualTo(tempId));
    }

    // =========================================================================
    // T-STOMP-03: content > 5000 chars → MSG_CONTENT_TOO_LONG
    // =========================================================================

    @Test
    void sendViaStomp_contentTooLong_throwsMsgContentTooLong() {
        String tempId = UUID.randomUUID().toString();
        String longContent = "A".repeat(5001);
        SendMessagePayload payload = new SendMessagePayload(tempId, longContent, "TEXT");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.sendViaStomp(convId, userId, payload));

        assertEquals("MSG_CONTENT_TOO_LONG", ex.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("tempId")).isEqualTo(tempId));
    }

    // =========================================================================
    // T-STOMP-04: blank content → VALIDATION_FAILED
    // =========================================================================

    @Test
    void sendViaStomp_blankContent_throwsValidationFailed() {
        String tempId = UUID.randomUUID().toString();
        SendMessagePayload payload = new SendMessagePayload(tempId, "   ", "TEXT");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.sendViaStomp(convId, userId, payload));

        assertEquals("VALIDATION_FAILED", ex.getErrorCode());
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("tempId")).isEqualTo(tempId));
    }

    // =========================================================================
    // T-STOMP-05: tempId not UUID format → VALIDATION_FAILED
    // =========================================================================

    @Test
    void sendViaStomp_invalidTempId_throwsValidationFailed() {
        SendMessagePayload payload = new SendMessagePayload("not-a-uuid", "Hello", "TEXT");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.sendViaStomp(convId, userId, payload));

        assertEquals("VALIDATION_FAILED", ex.getErrorCode());
        // echoed tempId should be the invalid value itself
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("tempId")).isEqualTo("not-a-uuid"));
    }

    // =========================================================================
    // T-STOMP-06: rate limit exceeded → MSG_RATE_LIMITED
    // =========================================================================

    @Test
    void sendViaStomp_rateLimitExceeded_throwsMsgRateLimited() {
        String tempId = UUID.randomUUID().toString();
        // Member check passes
        when(memberRepository.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);
        // Rate limit: count = 31 (over limit)
        when(valueOps.increment(startsWith("rate:msg:"))).thenReturn(31L);
        when(redisTemplate.getExpire(anyString(), any())).thenReturn(45L);

        AppException ex = assertThrows(AppException.class,
                () -> messageService.sendViaStomp(convId, userId, validPayload(tempId)));

        assertEquals("MSG_RATE_LIMITED", ex.getErrorCode());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatus());
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("tempId")).isEqualTo(tempId));
    }

    // =========================================================================
    // T-STOMP-07: duplicate tempId with value PENDING → silently dropped
    // =========================================================================

    @Test
    void sendViaStomp_duplicateTempIdPending_dropsFrameSilently() {
        String tempId = UUID.randomUUID().toString();

        when(memberRepository.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);
        when(valueOps.increment(startsWith("rate:msg:"))).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
        // Dedup key already exists
        when(valueOps.setIfAbsent(anyString(), eq("PENDING"), any(Duration.class))).thenReturn(false);
        // Value is still PENDING
        when(valueOps.get(startsWith("msg:dedup:"))).thenReturn("PENDING");

        assertDoesNotThrow(() -> messageService.sendViaStomp(convId, userId, validPayload(tempId)));

        // Message must NOT be saved
        verify(messageRepository, never()).save(any());
        // ACK must NOT be sent
        verify(messagingTemplate, never()).convertAndSendToUser(anyString(), eq("/queue/acks"), any());
        // Event must NOT be published
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // T-STOMP-08: duplicate tempId with real messageId → ACK re-sent idempotently
    // =========================================================================

    @Test
    void sendViaStomp_duplicateTempIdRealMessageId_resendsAckIdempotently() {
        String tempId = UUID.randomUUID().toString();
        UUID existingMsgId = mockSavedMessage.getId();

        when(memberRepository.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);
        when(valueOps.increment(startsWith("rate:msg:"))).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
        // Key already exists
        when(valueOps.setIfAbsent(anyString(), eq("PENDING"), any(Duration.class))).thenReturn(false);
        // Value is real messageId
        when(valueOps.get(startsWith("msg:dedup:"))).thenReturn(existingMsgId.toString());
        when(messageRepository.findById(existingMsgId)).thenReturn(Optional.of(mockSavedMessage));
        when(messageMapper.toDto(mockSavedMessage)).thenReturn(mockDto);

        assertDoesNotThrow(() -> messageService.sendViaStomp(convId, userId, validPayload(tempId)));

        // Message must NOT be saved again
        verify(messageRepository, never()).save(any());
        // Event must NOT be published again
        verify(eventPublisher, never()).publishEvent(any());
        // ACK must be sent (idempotent re-send)
        ArgumentCaptor<Object> ackCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/acks"), ackCaptor.capture()
        );
        assertThat(ackCaptor.getValue()).isInstanceOfSatisfying(AckPayload.class, ack -> {
            assertEquals("SEND", ack.operation());
            assertEquals(tempId, ack.clientId());
            assertEquals(mockDto, ack.message());
        });
    }

    // =========================================================================
    // T-STOMP-09: Redis down during rate limit → fail-open (message processed)
    // =========================================================================

    @Test
    void sendViaStomp_redisDownDuringRateLimit_failOpen() {
        String tempId = UUID.randomUUID().toString();

        when(memberRepository.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);
        // Redis throws DataAccessException during rate limit check
        when(valueOps.increment(anyString())).thenThrow(new QueryTimeoutException("Redis timeout"));
        // Dedup: new key (fail-open, rate limit skipped)
        when(valueOps.setIfAbsent(anyString(), eq("PENDING"), any(Duration.class))).thenReturn(true);
        when(userRepository.getReferenceById(userId)).thenReturn(mockUser);
        when(conversationRepository.getReferenceById(convId)).thenReturn(mockConv);
        when(messageRepository.save(any(Message.class))).thenReturn(mockSavedMessage);
        when(conversationRepository.findById(convId)).thenReturn(Optional.of(mockConv));
        when(messageRepository.findById(any(UUID.class))).thenReturn(Optional.of(mockSavedMessage));
        when(messageMapper.toDto(mockSavedMessage)).thenReturn(mockDto);

        // Must not throw — fail-open
        assertDoesNotThrow(() -> messageService.sendViaStomp(convId, userId, validPayload(tempId)));

        // Message should still be saved
        verify(messageRepository).save(any(Message.class));
    }

    // =========================================================================
    // T-STOMP-10: null tempId → VALIDATION_FAILED with tempId="unknown"
    // =========================================================================

    @Test
    void sendViaStomp_nullTempId_throwsValidationFailedWithUnknown() {
        SendMessagePayload payload = new SendMessagePayload(null, "Hello", "TEXT");

        AppException ex = assertThrows(AppException.class,
                () -> messageService.sendViaStomp(convId, userId, payload));

        assertEquals("VALIDATION_FAILED", ex.getErrorCode());
        // echoed tempId should be "unknown" when null
        assertThat(ex.getDetails()).isInstanceOfSatisfying(java.util.Map.class,
                map -> assertThat(map.get("tempId")).isEqualTo("unknown"));
    }
}
