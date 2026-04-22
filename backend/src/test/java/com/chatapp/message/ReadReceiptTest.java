package com.chatapp.message;

import com.chatapp.conversation.dto.MemberDto;
import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.event.ReadUpdatedEvent;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.service.ReadReceiptService;
import com.chatapp.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests cho ReadReceiptService.markRead — W7-D5.
 *
 * Tests:
 *  RR-01: markRead_valid_updatesLastReadMessageId        — happy path, DB update, event published.
 *  RR-02: markRead_idempotent_olderMessage_noOp          — incoming older → no DB change, no event.
 *  RR-03: markRead_sameMessage_noOp                      — same createdAt → no-op.
 *  RR-04: markRead_nonMember_throwsForbidden             — NOT_MEMBER 403.
 *  RR-05: markRead_messageNotFound_throws                — MSG_NOT_FOUND 404.
 *  RR-06: markRead_messageInDifferentConv_throws         — MSG_NOT_IN_CONV 403.
 *  RR-07: memberDto_includesLastReadMessageId            — MemberDto.from() includes field.
 *  RR-08: markRead_nullLastRead_advances                 — null current → always advance.
 *  RR-09: markRead_currentLastReadHardDeleted_advances   — current FK deleted → treat as null.
 *  RR-10: markRead_newerMessage_publishes_correctEvent   — event payload has correct values.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReadReceiptTest {

    @Mock private ConversationMemberRepository memberRepo;
    @Mock private MessageRepository messageRepo;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ReadReceiptService service;

    private UUID userId;
    private UUID convId;
    private UUID messageId;
    private UUID otherConvId;

    private Conversation conv;
    private Conversation otherConv;
    private User user;
    private ConversationMember member;
    private Message incomingMessage;

    @BeforeEach
    void setUp() throws Exception {
        service = new ReadReceiptService(memberRepo, messageRepo, eventPublisher);

        userId = UUID.randomUUID();
        convId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        otherConvId = UUID.randomUUID();

        user = User.builder()
                .username("testuser")
                .email("test@test.com")
                .fullName("Test User")
                .passwordHash("$2a$12$hash")
                .status("active")
                .build();

        conv = Conversation.builder()
                .type(ConversationType.ONE_ON_ONE)
                .build();
        setEntityId(conv, convId);

        otherConv = Conversation.builder()
                .type(ConversationType.ONE_ON_ONE)
                .build();
        setEntityId(otherConv, otherConvId);

        member = ConversationMember.builder()
                .conversation(conv)
                .user(user)
                .role(MemberRole.MEMBER)
                .joinedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();

        incomingMessage = Message.builder()
                .id(messageId)
                .conversation(conv)
                .sender(user)
                .type(MessageType.TEXT)
                .content("Hello")
                .build();
        incomingMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    }

    /** Set the `id` field on a Conversation entity via reflection (bypassing @PrePersist). */
    private static void setEntityId(Conversation entity, UUID id) throws Exception {
        java.lang.reflect.Field idField = Conversation.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }

    // =========================================================================
    // RR-01: Happy path — forward advance
    // =========================================================================

    @Test
    void markRead_valid_updatesLastReadMessageId() {
        // Given
        member.setLastReadMessageId(null); // no previous read
        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(member));
        when(messageRepo.findById(messageId))
                .thenReturn(Optional.of(incomingMessage));
        when(memberRepo.save(any(ConversationMember.class))).thenReturn(member);

        // When
        service.markRead(convId, userId, messageId);

        // Then: lastReadMessageId updated
        assertThat(member.getLastReadMessageId()).isEqualTo(messageId);
        verify(memberRepo).save(member);
        verify(eventPublisher).publishEvent(any(ReadUpdatedEvent.class));
    }

    // =========================================================================
    // RR-02: Idempotent — incoming message older than current → no-op
    // =========================================================================

    @Test
    void markRead_idempotent_olderMessage_noOp() {
        // Given: current last read = newer message, incoming = older message
        UUID currentMsgId = UUID.randomUUID();
        Message currentLastRead = Message.builder()
                .id(currentMsgId)
                .conversation(conv)
                .sender(user)
                .type(MessageType.TEXT)
                .content("Newer")
                .build();
        // Current last read has NEWER createdAt
        currentLastRead.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));

        // Incoming has OLDER createdAt
        incomingMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

        member.setLastReadMessageId(currentMsgId);

        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(member));
        when(messageRepo.findById(messageId))
                .thenReturn(Optional.of(incomingMessage));
        when(messageRepo.findById(currentMsgId))
                .thenReturn(Optional.of(currentLastRead));

        // When
        service.markRead(convId, userId, messageId);

        // Then: no DB update, no event
        verify(memberRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        // lastReadMessageId unchanged
        assertThat(member.getLastReadMessageId()).isEqualTo(currentMsgId);
    }

    // =========================================================================
    // RR-03: Same message → no-op (createdAt equal)
    // =========================================================================

    @Test
    void markRead_sameMessage_noOp() {
        // Given: current last read = same messageId = incoming
        OffsetDateTime sameTime = OffsetDateTime.now(ZoneOffset.UTC);
        incomingMessage.setCreatedAt(sameTime);

        Message currentLastRead = Message.builder()
                .id(messageId) // same ID
                .conversation(conv)
                .sender(user)
                .type(MessageType.TEXT)
                .content("Same")
                .build();
        currentLastRead.setCreatedAt(sameTime); // same createdAt

        member.setLastReadMessageId(messageId);

        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(member));
        when(messageRepo.findById(messageId))
                .thenReturn(Optional.of(incomingMessage))
                .thenReturn(Optional.of(currentLastRead));

        // When
        service.markRead(convId, userId, messageId);

        // Then: no-op (same createdAt is NOT before → forward-only check fails)
        verify(memberRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // RR-04: Non-member → 403 NOT_MEMBER
    // =========================================================================

    @Test
    void markRead_nonMember_throwsForbidden() {
        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(convId, userId, messageId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(appEx.getErrorCode()).isEqualTo("NOT_MEMBER");
                });

        verify(messageRepo, never()).findById(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // RR-05: Message not found → 404 MSG_NOT_FOUND
    // =========================================================================

    @Test
    void markRead_messageNotFound_throws() {
        member.setLastReadMessageId(null);
        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(member));
        when(messageRepo.findById(messageId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markRead(convId, userId, messageId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(appEx.getErrorCode()).isEqualTo("MSG_NOT_FOUND");
                });

        verify(memberRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // RR-06: Message belongs to different conv → 403 MSG_NOT_IN_CONV
    // =========================================================================

    @Test
    void markRead_messageInDifferentConv_throws() throws Exception {
        // Build message using otherConv (different ID from convId)
        Message msgInOtherConv = Message.builder()
                .id(messageId)
                .conversation(otherConv)   // otherConv.id = otherConvId ≠ convId
                .sender(user)
                .type(MessageType.TEXT)
                .content("Other conv msg")
                .build();
        msgInOtherConv.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        member.setLastReadMessageId(null);
        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(member));
        when(messageRepo.findById(messageId))
                .thenReturn(Optional.of(msgInOtherConv));

        assertThatThrownBy(() -> service.markRead(convId, userId, messageId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> {
                    AppException appEx = (AppException) ex;
                    assertThat(appEx.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(appEx.getErrorCode()).isEqualTo("MSG_NOT_IN_CONV");
                });

        verify(memberRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // RR-07: MemberDto includes lastReadMessageId
    // =========================================================================

    @Test
    void memberDto_includesLastReadMessageId() {
        UUID lastReadId = UUID.randomUUID();
        member.setLastReadMessageId(lastReadId);

        MemberDto dto = MemberDto.from(member);

        assertThat(dto.lastReadMessageId()).isEqualTo(lastReadId);
    }

    @Test
    void memberDto_nullLastReadMessageId_whenNotRead() {
        member.setLastReadMessageId(null);
        MemberDto dto = MemberDto.from(member);
        assertThat(dto.lastReadMessageId()).isNull();
    }

    // =========================================================================
    // RR-08: Null current lastRead → always advance
    // =========================================================================

    @Test
    void markRead_nullLastRead_advances() {
        // Given: member has never read anything
        member.setLastReadMessageId(null);

        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(member));
        when(messageRepo.findById(messageId))
                .thenReturn(Optional.of(incomingMessage));
        when(memberRepo.save(any())).thenReturn(member);

        // When
        service.markRead(convId, userId, messageId);

        // Then: advance (set lastReadMessageId to incoming)
        assertThat(member.getLastReadMessageId()).isEqualTo(messageId);
        verify(eventPublisher).publishEvent(any(ReadUpdatedEvent.class));
    }

    // =========================================================================
    // RR-09: Current FK hard-deleted (SET NULL triggered) → treat as null, advance
    // =========================================================================

    @Test
    void markRead_currentLastReadHardDeleted_advances() {
        // Given: member has a lastReadMessageId but the message was hard-deleted (FK → SET NULL would have run,
        // but we simulate a stale reference where findById returns empty)
        UUID staleCurrentId = UUID.randomUUID();
        member.setLastReadMessageId(staleCurrentId);

        // incomingMessage has a normal future createdAt
        incomingMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(member));
        when(messageRepo.findById(messageId))
                .thenReturn(Optional.of(incomingMessage));
        // Current last read message is hard-deleted → not found in DB
        when(messageRepo.findById(staleCurrentId))
                .thenReturn(Optional.empty());
        when(memberRepo.save(any())).thenReturn(member);

        // When
        service.markRead(convId, userId, messageId);

        // Then: treated as null path → advance
        assertThat(member.getLastReadMessageId()).isEqualTo(messageId);
        verify(eventPublisher).publishEvent(any(ReadUpdatedEvent.class));
    }

    // =========================================================================
    // RR-10: Published event has correct payload
    // =========================================================================

    @Test
    void markRead_newerMessage_publishes_correctEvent() {
        // Given: current last read has OLD createdAt, incoming has NEWER createdAt
        UUID currentMsgId = UUID.randomUUID();
        Message currentMsg = Message.builder()
                .id(currentMsgId)
                .conversation(conv)
                .sender(user)
                .type(MessageType.TEXT)
                .content("Old")
                .build();
        currentMsg.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));

        incomingMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC)); // newer

        member.setLastReadMessageId(currentMsgId);

        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(member));
        when(messageRepo.findById(messageId))
                .thenReturn(Optional.of(incomingMessage));
        when(messageRepo.findById(currentMsgId))
                .thenReturn(Optional.of(currentMsg));
        when(memberRepo.save(any())).thenReturn(member);

        ArgumentCaptor<ReadUpdatedEvent> captor = ArgumentCaptor.forClass(ReadUpdatedEvent.class);

        // When
        service.markRead(convId, userId, messageId);

        // Then: event published with correct fields
        verify(eventPublisher).publishEvent(captor.capture());
        ReadUpdatedEvent event = captor.getValue();
        assertThat(event.convId()).isEqualTo(convId);
        assertThat(event.userId()).isEqualTo(userId);
        assertThat(event.messageId()).isEqualTo(messageId);
        assertThat(event.readAt()).isNotNull();
    }
}
