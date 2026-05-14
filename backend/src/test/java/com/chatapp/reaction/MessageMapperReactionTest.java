package com.chatapp.reaction;

import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.file.repository.MessageAttachmentRepository;
import com.chatapp.reaction.dto.ReactionAggregateDto;
import com.chatapp.reaction.entity.MessageReaction;
import com.chatapp.message.service.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests cho MessageMapper.aggregateReactions (W8-D1).
 *
 * T-AGG-01: 2 users react 👍, 1 react ❤️ → [{👍:2}, {❤️:1}] sort count DESC
 * T-AGG-02: currentUserReacted = true khi userId trong 👍 list
 * T-AGG-03: currentUserReacted = false khi userId không trong list
 * T-AGG-04: Empty reactions → empty list
 * T-AGG-05: Null reactions → empty list
 * T-AGG-06: currentUserId = null → all currentUserReacted = false
 * T-AGG-07: Same count → sort emoji ASC (codepoint)
 */
@ExtendWith(MockitoExtension.class)
class MessageMapperReactionTest {

    @Mock private MessageAttachmentRepository messageAttachmentRepository;
    @Mock private FileRecordRepository fileRecordRepository;

    private MessageMapper mapper;

    private UUID userId1;
    private UUID userId2;
    private UUID userId3;
    private UUID messageId;

    @Mock private com.chatapp.user.repository.UserRepository userRepository;

    @BeforeEach
    void setUp() {
        mapper = new MessageMapper(messageAttachmentRepository, fileRecordRepository, userRepository);
        userId1 = UUID.randomUUID();
        userId2 = UUID.randomUUID();
        userId3 = UUID.randomUUID();
        messageId = UUID.randomUUID();
    }

    private MessageReaction reaction(UUID userId, String emoji) {
        MessageReaction r = new MessageReaction();
        r.setId(UUID.randomUUID());
        r.setMessageId(messageId);
        r.setUserId(userId);
        r.setEmoji(emoji);
        r.setCreatedAt(Instant.now()); // needed for @PrePersist not called in unit test
        return r;
    }

    // T-AGG-01: Sort count DESC
    @Test
    void aggregateReactions_sortByCountDesc() {
        List<MessageReaction> reactions = List.of(
                reaction(userId1, "👍"),
                reaction(userId2, "👍"),
                reaction(userId3, "❤️")
        );

        List<ReactionAggregateDto> result = mapper.aggregateReactions(reactions, userId3);

        assertThat(result).hasSize(2);
        // 👍 có count=2 → trước ❤️ count=1
        assertThat(result.get(0).emoji()).isEqualTo("👍");
        assertThat(result.get(0).count()).isEqualTo(2);
        assertThat(result.get(1).emoji()).isEqualTo("❤️");
        assertThat(result.get(1).count()).isEqualTo(1);
    }

    // T-AGG-02: currentUserReacted = true
    @Test
    void aggregateReactions_currentUserReacted_true() {
        List<MessageReaction> reactions = List.of(
                reaction(userId1, "👍"),
                reaction(userId2, "👍")
        );

        List<ReactionAggregateDto> result = mapper.aggregateReactions(reactions, userId1);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentUserReacted()).isTrue();
        assertThat(result.get(0).userIds()).containsExactlyInAnyOrder(userId1, userId2);
    }

    // T-AGG-03: currentUserReacted = false khi caller không trong list
    @Test
    void aggregateReactions_currentUserReacted_false() {
        List<MessageReaction> reactions = List.of(
                reaction(userId1, "👍"),
                reaction(userId2, "👍")
        );

        // userId3 không react
        List<ReactionAggregateDto> result = mapper.aggregateReactions(reactions, userId3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).currentUserReacted()).isFalse();
    }

    // T-AGG-04: Empty reactions → empty list
    @Test
    void aggregateReactions_emptyReactions_returnsEmptyList() {
        List<ReactionAggregateDto> result = mapper.aggregateReactions(List.of(), userId1);
        assertThat(result).isEmpty();
    }

    // T-AGG-05: Null reactions → empty list
    @Test
    void aggregateReactions_nullReactions_returnsEmptyList() {
        List<ReactionAggregateDto> result = mapper.aggregateReactions(null, userId1);
        assertThat(result).isEmpty();
    }

    // T-AGG-06: currentUserId = null → all false
    @Test
    void aggregateReactions_nullCurrentUserId_allCurrentUserReactedFalse() {
        List<MessageReaction> reactions = List.of(
                reaction(userId1, "👍"),
                reaction(userId2, "❤️")
        );

        List<ReactionAggregateDto> result = mapper.aggregateReactions(reactions, null);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(agg -> assertThat(agg.currentUserReacted()).isFalse());
    }

    // T-AGG-07: Same count → sort emoji ASC (codepoint)
    @Test
    void aggregateReactions_sameCount_sortByEmojiAsc() {
        List<MessageReaction> reactions = List.of(
                reaction(userId1, "😍"),  // higher codepoint
                reaction(userId2, "👍")   // lower codepoint
        );

        List<ReactionAggregateDto> result = mapper.aggregateReactions(reactions, userId3);

        // Both count=1, sort by emoji codepoint ASC: 👍 (U+1F44D=128077) vs 😍 (U+1F60D=128525)
        // 👍 has lower codepoint → comes first
        assertThat(result).hasSize(2);
        assertThat(result.get(0).emoji()).isEqualTo("👍");
        assertThat(result.get(1).emoji()).isEqualTo("😍");
    }
}
