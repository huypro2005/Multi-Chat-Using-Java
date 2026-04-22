package com.chatapp.message;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.entity.Message;
import com.chatapp.message.event.MessagePinnedEvent;
import com.chatapp.message.event.MessageUnpinnedEvent;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.service.PinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests cho PinService (W8-D2).
 *
 *  P01: OWNER ghim message trong GROUP → success + broadcast
 *  P02: ADMIN ghim message trong GROUP → success
 *  P03: MEMBER ghim message trong GROUP → FORBIDDEN
 *  P04: Bất kỳ member ghim trong ONE_ON_ONE → success
 *  P05: Ghim message đã deleted → MSG_DELETED
 *  P06: Ghim khi đã có 3 pinned → PIN_LIMIT_EXCEEDED
 *  P07: Ghim message đã pinned → no-op, không broadcast
 *  P08: Bỏ ghim message đang pinned → success + broadcast
 *  P09: Bỏ ghim message chưa pinned → MESSAGE_NOT_PINNED
 *  P10: Ghim message không tồn tại → MSG_NOT_FOUND
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PinServiceTest {

    @Mock private MessageRepository messageRepo;
    @Mock private ConversationMemberRepository memberRepo;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PinService pinService;

    private UUID userId;
    private UUID messageId;
    private UUID convId;
    private Conversation mockConv;
    private Message mockMessage;
    private ConversationMember mockMember;

    @BeforeEach
    void setUp() {
        pinService = new PinService(messageRepo, memberRepo, eventPublisher);

        userId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        convId = UUID.randomUUID();

        mockConv = mock(Conversation.class);
        when(mockConv.getId()).thenReturn(convId);
        when(mockConv.getType()).thenReturn(ConversationType.GROUP);

        mockMessage = new Message();
        mockMessage.setId(messageId);
        mockMessage.setConversation(mockConv);
        mockMessage.setContent("Hello");
        mockMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        mockMember = mock(ConversationMember.class);
        when(mockMember.getRole()).thenReturn(MemberRole.OWNER);

        when(messageRepo.findById(messageId)).thenReturn(Optional.of(mockMessage));
        when(memberRepo.findByConversation_IdAndUser_Id(convId, userId))
                .thenReturn(Optional.of(mockMember));
        when(messageRepo.countPinnedInConversation(convId)).thenReturn(0L);
        when(messageRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // =========================================================================
    // P01: OWNER pin in GROUP success
    // =========================================================================

    @Test
    void P01_OWNER_pin_in_group_success() {
        when(mockMember.getRole()).thenReturn(MemberRole.OWNER);

        pinService.pin(messageId, userId);

        assertThat(mockMessage.getPinnedAt()).isNotNull();
        assertThat(mockMessage.getPinnedByUserId()).isEqualTo(userId);

        ArgumentCaptor<MessagePinnedEvent> captor = ArgumentCaptor.forClass(MessagePinnedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().messageId()).isEqualTo(messageId);
        assertThat(captor.getValue().convId()).isEqualTo(convId);
        assertThat(captor.getValue().userId()).isEqualTo(userId);
    }

    // =========================================================================
    // P02: ADMIN pin in GROUP success
    // =========================================================================

    @Test
    void P02_ADMIN_pin_success() {
        when(mockMember.getRole()).thenReturn(MemberRole.ADMIN);

        pinService.pin(messageId, userId);

        assertThat(mockMessage.getPinnedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(MessagePinnedEvent.class));
    }

    // =========================================================================
    // P03: MEMBER pin in GROUP → FORBIDDEN
    // =========================================================================

    @Test
    void P03_MEMBER_pin_in_group_forbidden() {
        when(mockMember.getRole()).thenReturn(MemberRole.MEMBER);

        assertThatThrownBy(() -> pinService.pin(messageId, userId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo("FORBIDDEN"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // P04: ONE_ON_ONE — any member can pin
    // =========================================================================

    @Test
    void P04_DIRECT_conv_any_member_can_pin() {
        when(mockConv.getType()).thenReturn(ConversationType.ONE_ON_ONE);
        when(mockMember.getRole()).thenReturn(MemberRole.MEMBER);

        pinService.pin(messageId, userId);

        assertThat(mockMessage.getPinnedAt()).isNotNull();
        verify(eventPublisher).publishEvent(any(MessagePinnedEvent.class));
    }

    // =========================================================================
    // P05: Pin deleted message → MSG_DELETED
    // =========================================================================

    @Test
    void P05_pin_deleted_message_fails() {
        mockMessage.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> pinService.pin(messageId, userId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo("MSG_DELETED"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // P06: Pin when limit exceeded → PIN_LIMIT_EXCEEDED
    // =========================================================================

    @Test
    void P06_pin_when_limit_exceeded() {
        when(messageRepo.countPinnedInConversation(convId)).thenReturn(3L);

        assertThatThrownBy(() -> pinService.pin(messageId, userId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo("PIN_LIMIT_EXCEEDED"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // P07: Pin already pinned → no-op, no broadcast
    // =========================================================================

    @Test
    void P07_pin_idempotent_already_pinned() {
        mockMessage.setPinnedAt(Instant.now());
        mockMessage.setPinnedByUserId(userId);

        pinService.pin(messageId, userId);

        verify(messageRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // P08: Unpin pinned message → success + broadcast
    // =========================================================================

    @Test
    void P08_unpin_happy() {
        mockMessage.setPinnedAt(Instant.now());
        mockMessage.setPinnedByUserId(userId);

        pinService.unpin(messageId, userId);

        assertThat(mockMessage.getPinnedAt()).isNull();
        assertThat(mockMessage.getPinnedByUserId()).isNull();

        ArgumentCaptor<MessageUnpinnedEvent> captor = ArgumentCaptor.forClass(MessageUnpinnedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().messageId()).isEqualTo(messageId);
        assertThat(captor.getValue().convId()).isEqualTo(convId);
    }

    // =========================================================================
    // P09: Unpin not pinned → MESSAGE_NOT_PINNED
    // =========================================================================

    @Test
    void P09_unpin_not_pinned_fails() {
        // mockMessage.pinnedAt is null by default

        assertThatThrownBy(() -> pinService.unpin(messageId, userId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo("MESSAGE_NOT_PINNED"));

        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // P10: Pin non-existent message → MSG_NOT_FOUND
    // =========================================================================

    @Test
    void P10_pin_nonexistent_message_not_found() {
        UUID unknownId = UUID.randomUUID();
        when(messageRepo.findById(unknownId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pinService.pin(unknownId, userId))
                .isInstanceOf(AppException.class)
                .satisfies(ex -> assertThat(((AppException) ex).getErrorCode()).isEqualTo("MSG_NOT_FOUND"));
    }
}
