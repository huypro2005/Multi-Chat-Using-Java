package com.chatapp.reaction;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.reaction.entity.MessageReaction;
import com.chatapp.reaction.event.ReactionChangedEvent;
import com.chatapp.reaction.repository.MessageReactionRepository;
import com.chatapp.reaction.service.EmojiValidator;
import com.chatapp.reaction.service.ReactionService;
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

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests cho ReactionService (W8-D1).
 *
 * Test coverage (≥10 per spec):
 *  T-REACT-01: React new emoji → INSERT, broadcast ADDED
 *  T-REACT-02: React same emoji (existing) → DELETE, broadcast REMOVED với previousEmoji set
 *  T-REACT-03: React different emoji (existing) → UPDATE, broadcast CHANGED với previousEmoji set
 *  T-REACT-04: React invalid emoji ("hello" plain text) → REACTION_INVALID_EMOJI
 *  T-REACT-05: React emoji quá dài (> 20 bytes) → REACTION_INVALID_EMOJI
 *  T-REACT-06: React SYSTEM message → REACTION_NOT_ALLOWED_FOR_SYSTEM
 *  T-REACT-07: React soft-deleted message → REACTION_MSG_DELETED
 *  T-REACT-08: React non-member → NOT_MEMBER
 *  T-REACT-09: React non-existent messageId → MSG_NOT_FOUND
 *  T-REACT-10: Rate limit exceeded (>5/s) → MSG_RATE_LIMITED
 *  T-REACT-11: Redis down for rate limit → fail-open (proceed normally)
 *  T-REACT-12: Empty emoji → REACTION_INVALID_EMOJI
 *  T-REACT-13: Null emoji → REACTION_INVALID_EMOJI
 *  T-REACT-14: Broadcast ADDED — emoji non-null, previousEmoji null
 *  T-REACT-15: Broadcast REMOVED — emoji null, previousEmoji non-null
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReactionServiceTest {

    @Mock private MessageReactionRepository reactionRepo;
    @Mock private MessageRepository messageRepo;
    @Mock private ConversationMemberRepository memberRepo;
    @Mock private EmojiValidator emojiValidator; // real validator tested separately
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private ReactionService reactionService;

    private UUID userId;
    private UUID messageId;
    private UUID convId;
    private Message mockMessage;
    private Conversation mockConv;

    @BeforeEach
    void setUp() {
        reactionService = new ReactionService(
                reactionRepo, messageRepo, memberRepo, emojiValidator, eventPublisher, redisTemplate
        );

        userId = UUID.randomUUID();
        messageId = UUID.randomUUID();
        convId = UUID.randomUUID();

        mockConv = new Conversation();
        // Use reflection-style: Conversation has an id field, set via getId() after save
        // For test, use a spy or set via field
        mockConv = mock(Conversation.class);
        when(mockConv.getId()).thenReturn(convId);

        mockMessage = new Message();
        mockMessage.setId(messageId);
        mockMessage.setConversation(mockConv);
        mockMessage.setType(MessageType.TEXT);
        mockMessage.setContent("Hello");
        mockMessage.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        // Default: rate limit OK (count=1)
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);

        // Default: member check passes
        when(memberRepo.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(true);

        // Default: message found
        when(messageRepo.findById(messageId)).thenReturn(Optional.of(mockMessage));

        // Default: no existing reaction
        when(reactionRepo.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.empty());
    }

    // =========================================================================
    // T-REACT-01: React new emoji → INSERT, broadcast ADDED
    // =========================================================================

    @Test
    void react_newEmoji_insertsAndBroadcastsAdded() {
        String emoji = "👍";
        // emojiValidator: no-op (mock, not throwing)

        reactionService.react(messageId, userId, emoji);

        // Verify INSERT
        ArgumentCaptor<MessageReaction> captor = ArgumentCaptor.forClass(MessageReaction.class);
        verify(reactionRepo).save(captor.capture());
        MessageReaction saved = captor.getValue();
        assertThat(saved.getMessageId()).isEqualTo(messageId);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getEmoji()).isEqualTo(emoji);

        // Verify broadcast event ADDED
        ArgumentCaptor<ReactionChangedEvent> eventCaptor = ArgumentCaptor.forClass(ReactionChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ReactionChangedEvent event = eventCaptor.getValue();
        assertThat(event.getAction()).isEqualTo("ADDED");
        assertThat(event.getEmoji()).isEqualTo(emoji);
        assertThat(event.getPreviousEmoji()).isNull();
        assertThat(event.getConvId()).isEqualTo(convId);
        assertThat(event.getMessageId()).isEqualTo(messageId);
        assertThat(event.getUserId()).isEqualTo(userId);
    }

    // =========================================================================
    // T-REACT-02: React same emoji (existing) → DELETE, broadcast REMOVED
    // =========================================================================

    @Test
    void react_sameEmojiExisting_deletesAndBroadcastsRemoved() {
        String emoji = "👍";

        MessageReaction existing = new MessageReaction();
        existing.setId(UUID.randomUUID());
        existing.setMessageId(messageId);
        existing.setUserId(userId);
        existing.setEmoji(emoji);
        when(reactionRepo.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.of(existing));

        reactionService.react(messageId, userId, emoji);

        // Verify DELETE
        verify(reactionRepo).delete(existing);
        verify(reactionRepo, never()).save(any());

        // Verify broadcast event REMOVED
        ArgumentCaptor<ReactionChangedEvent> eventCaptor = ArgumentCaptor.forClass(ReactionChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ReactionChangedEvent event = eventCaptor.getValue();
        assertThat(event.getAction()).isEqualTo("REMOVED");
        assertThat(event.getEmoji()).isNull();              // REMOVED: emoji=null
        assertThat(event.getPreviousEmoji()).isEqualTo(emoji); // REMOVED: previousEmoji=old emoji
    }

    // =========================================================================
    // T-REACT-03: React different emoji → UPDATE, broadcast CHANGED
    // =========================================================================

    @Test
    void react_differentEmojiExisting_updatesAndBroadcastsChanged() {
        String oldEmoji = "👍";
        String newEmoji = "❤️";

        MessageReaction existing = new MessageReaction();
        existing.setId(UUID.randomUUID());
        existing.setMessageId(messageId);
        existing.setUserId(userId);
        existing.setEmoji(oldEmoji);
        when(reactionRepo.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.of(existing));

        reactionService.react(messageId, userId, newEmoji);

        // Verify UPDATE (save called with updated emoji)
        ArgumentCaptor<MessageReaction> captor = ArgumentCaptor.forClass(MessageReaction.class);
        verify(reactionRepo).save(captor.capture());
        assertThat(captor.getValue().getEmoji()).isEqualTo(newEmoji);
        verify(reactionRepo, never()).delete(any());

        // Verify broadcast event CHANGED
        ArgumentCaptor<ReactionChangedEvent> eventCaptor = ArgumentCaptor.forClass(ReactionChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ReactionChangedEvent event = eventCaptor.getValue();
        assertThat(event.getAction()).isEqualTo("CHANGED");
        assertThat(event.getEmoji()).isEqualTo(newEmoji);
        assertThat(event.getPreviousEmoji()).isEqualTo(oldEmoji);
    }

    // =========================================================================
    // T-REACT-04: Invalid emoji (plain text) → REACTION_INVALID_EMOJI
    // =========================================================================

    @Test
    void react_invalidEmojiPlainText_throwsReactionInvalidEmoji() {
        doThrow(new AppException(HttpStatus.BAD_REQUEST, "REACTION_INVALID_EMOJI", "Invalid"))
                .when(emojiValidator).validate("hello");

        assertThatThrownBy(() -> reactionService.react(messageId, userId, "hello"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REACTION_INVALID_EMOJI");

        // No DB operations should happen
        verify(messageRepo, never()).findById(any());
        verify(reactionRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // T-REACT-05: Emoji quá dài (> 20 bytes UTF-8) → REACTION_INVALID_EMOJI
    // =========================================================================

    @Test
    void react_emojiTooLong_throwsReactionInvalidEmoji() {
        // Scotland flag 🏴󠁧󠁢󠁳󠁣󠁴󠁿 = 28 bytes UTF-8 → should be rejected by validator
        String scotlandFlag = "🏴󠁧󠁢󠁳󠁣󠁴󠁿";
        doThrow(new AppException(HttpStatus.BAD_REQUEST, "REACTION_INVALID_EMOJI", "Too long"))
                .when(emojiValidator).validate(scotlandFlag);

        assertThatThrownBy(() -> reactionService.react(messageId, userId, scotlandFlag))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REACTION_INVALID_EMOJI");
    }

    // =========================================================================
    // T-REACT-06: SYSTEM message → REACTION_NOT_ALLOWED_FOR_SYSTEM
    // =========================================================================

    @Test
    void react_systemMessage_throwsReactionNotAllowedForSystem() {
        mockMessage.setType(MessageType.SYSTEM);

        assertThatThrownBy(() -> reactionService.react(messageId, userId, "👍"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REACTION_NOT_ALLOWED_FOR_SYSTEM");

        verify(reactionRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // T-REACT-07: Soft-deleted message → REACTION_MSG_DELETED
    // =========================================================================

    @Test
    void react_softDeletedMessage_throwsReactionMsgDeleted() {
        mockMessage.setDeletedAt(OffsetDateTime.now(ZoneOffset.UTC));

        assertThatThrownBy(() -> reactionService.react(messageId, userId, "👍"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REACTION_MSG_DELETED");

        verify(reactionRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // T-REACT-08: Non-member → NOT_MEMBER
    // =========================================================================

    @Test
    void react_nonMember_throwsNotMember() {
        when(memberRepo.existsByConversation_IdAndUser_Id(convId, userId)).thenReturn(false);

        assertThatThrownBy(() -> reactionService.react(messageId, userId, "👍"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "NOT_MEMBER");

        verify(reactionRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // T-REACT-09: Non-existent messageId → MSG_NOT_FOUND
    // =========================================================================

    @Test
    void react_nonExistentMessage_throwsMsgNotFound() {
        when(messageRepo.findById(messageId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reactionService.react(messageId, userId, "👍"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MSG_NOT_FOUND");

        verify(reactionRepo, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // =========================================================================
    // T-REACT-10: Rate limit exceeded → MSG_RATE_LIMITED
    // =========================================================================

    @Test
    void react_rateLimitExceeded_throwsMsgRateLimited() {
        // Simulate count > 5
        when(valueOps.increment(startsWith("rate:msg-react:"))).thenReturn(6L);

        assertThatThrownBy(() -> reactionService.react(messageId, userId, "👍"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "MSG_RATE_LIMITED");

        // No DB operations after rate limit
        verify(messageRepo, never()).findById(any());
        verify(reactionRepo, never()).save(any());
    }

    // =========================================================================
    // T-REACT-11: Redis down for rate limit → fail-open
    // =========================================================================

    @Test
    void react_redisDownForRateLimit_failOpen() {
        when(valueOps.increment(anyString()))
                .thenThrow(new QueryTimeoutException("Redis timeout"));

        // Should NOT throw — fail-open
        reactionService.react(messageId, userId, "👍");

        // Message processed normally
        verify(reactionRepo).save(any());
        verify(eventPublisher).publishEvent(any(ReactionChangedEvent.class));
    }

    // =========================================================================
    // T-REACT-12: Empty emoji string → REACTION_INVALID_EMOJI
    // =========================================================================

    @Test
    void react_emptyEmoji_throwsReactionInvalidEmoji() {
        doThrow(new AppException(HttpStatus.BAD_REQUEST, "REACTION_INVALID_EMOJI", "Empty"))
                .when(emojiValidator).validate("");

        assertThatThrownBy(() -> reactionService.react(messageId, userId, ""))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REACTION_INVALID_EMOJI");
    }

    // =========================================================================
    // T-REACT-13: Null emoji → REACTION_INVALID_EMOJI
    // =========================================================================

    @Test
    void react_nullEmoji_throwsReactionInvalidEmoji() {
        doThrow(new AppException(HttpStatus.BAD_REQUEST, "REACTION_INVALID_EMOJI", "Null"))
                .when(emojiValidator).validate(null);

        assertThatThrownBy(() -> reactionService.react(messageId, userId, null))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", "REACTION_INVALID_EMOJI");
    }

    // =========================================================================
    // T-REACT-14: ADDED broadcast — emoji non-null, previousEmoji null
    // =========================================================================

    @Test
    void react_added_broadcastHasCorrectNullPattern() {
        // No existing reaction → ADDED
        when(reactionRepo.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.empty());

        reactionService.react(messageId, userId, "🎉");

        ArgumentCaptor<ReactionChangedEvent> eventCaptor = ArgumentCaptor.forClass(ReactionChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ReactionChangedEvent event = eventCaptor.getValue();

        assertThat(event.getAction()).isEqualTo("ADDED");
        assertThat(event.getEmoji()).isNotNull().isEqualTo("🎉"); // emoji present for ADDED
        assertThat(event.getPreviousEmoji()).isNull();             // previousEmoji null for ADDED
    }

    // =========================================================================
    // T-REACT-15: REMOVED broadcast — emoji null, previousEmoji non-null
    // =========================================================================

    @Test
    void react_removed_broadcastHasCorrectNullPattern() {
        String emoji = "😍";
        MessageReaction existing = new MessageReaction();
        existing.setId(UUID.randomUUID());
        existing.setMessageId(messageId);
        existing.setUserId(userId);
        existing.setEmoji(emoji);
        when(reactionRepo.findByMessageIdAndUserId(messageId, userId)).thenReturn(Optional.of(existing));

        reactionService.react(messageId, userId, emoji); // Same emoji → REMOVED

        ArgumentCaptor<ReactionChangedEvent> eventCaptor = ArgumentCaptor.forClass(ReactionChangedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ReactionChangedEvent event = eventCaptor.getValue();

        assertThat(event.getAction()).isEqualTo("REMOVED");
        assertThat(event.getEmoji()).isNull();                       // emoji null for REMOVED
        assertThat(event.getPreviousEmoji()).isNotNull().isEqualTo(emoji); // previousEmoji set for REMOVED
    }
}
