package com.chatapp.reaction.service;

import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.reaction.entity.MessageReaction;
import com.chatapp.reaction.event.ReactionChangedEvent;
import com.chatapp.reaction.repository.MessageReactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Service xử lý toggle reaction cho messages (W8-D1).
 *
 * Toggle semantics (atomic trong 1 @Transactional):
 *   - Không có row → INSERT → broadcast ADDED
 *   - Có row, emoji == incoming → DELETE → broadcast REMOVED (previousEmoji = emoji)
 *   - Có row, emoji != incoming → UPDATE → broadcast CHANGED (previousEmoji = old emoji)
 *
 * Validation chain (rẻ trước đắt theo contract §3.15):
 *   1. Emoji format (EmojiValidator: null/empty check + byte-length + regex)
 *   2. Rate limit (5 reactions/second/user via Redis INCR EX 1s)
 *   3. Message exists (MSG_NOT_FOUND anti-enum)
 *   4. Member check in handler (HANDLER_CHECK policy — piggyback message.conversationId)
 *   5. SYSTEM check (REACTION_NOT_ALLOWED_FOR_SYSTEM)
 *   6. Soft-delete check (REACTION_MSG_DELETED)
 *   7. Toggle DB
 *
 * Rate limit: 5/s per user (key: rate:msg-react:{userId}, EX 1s).
 * Fail-open khi Redis down.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ReactionService {

    private static final int REACT_RATE_LIMIT_PER_SECOND = 5;

    private final MessageReactionRepository reactionRepo;
    private final MessageRepository messageRepo;
    private final ConversationMemberRepository memberRepo;
    private final EmojiValidator emojiValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redisTemplate;

    /**
     * Toggle reaction cho message.
     *
     * @param messageId ID của message cần react
     * @param userId    User thực hiện react (từ STOMP Principal — không tin payload)
     * @param emoji     Emoji string cần react
     * @throws AppException với các error code tương ứng nếu validation fail
     */
    public void react(UUID messageId, UUID userId, String emoji) {
        // Step 1: Validate emoji (null/empty → byte-length → regex)
        emojiValidator.validate(emoji);

        // Step 2: Rate limit — 5 reactions/second/user (fail-open khi Redis down)
        checkReactRateLimit(userId);

        // Step 3: Load message (MSG_NOT_FOUND nếu không tồn tại — anti-enum)
        Message message = messageRepo.findById(messageId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND,
                        "MSG_NOT_FOUND", "Tin nhắn không tồn tại"));

        // Step 4: Member check (HANDLER_CHECK policy — lấy convId từ message để 1-query)
        UUID convId = message.getConversation().getId();
        if (!memberRepo.existsByConversation_IdAndUser_Id(convId, userId)) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "NOT_MEMBER", "Bạn không phải thành viên cuộc trò chuyện này");
        }

        // Step 5: SYSTEM message check (ngoại lệ anti-enum — documented)
        if (MessageType.SYSTEM == message.getType()) {
            throw new AppException(HttpStatus.FORBIDDEN,
                    "REACTION_NOT_ALLOWED_FOR_SYSTEM",
                    "Không thể react tin nhắn hệ thống");
        }

        // Step 6: Soft-delete check (ngoại lệ anti-enum — documented)
        if (message.getDeletedAt() != null) {
            throw new AppException(HttpStatus.GONE,
                    "REACTION_MSG_DELETED",
                    "Tin nhắn đã bị xóa, không thể react");
        }

        // Step 7: Toggle logic (atomic trong @Transactional)
        Optional<MessageReaction> existing = reactionRepo.findByMessageIdAndUserId(messageId, userId);

        String action;
        String previousEmoji = null;
        String resultEmoji = emoji;

        if (existing.isEmpty()) {
            // ADDED: INSERT new reaction
            MessageReaction reaction = new MessageReaction();
            reaction.setMessageId(messageId);
            reaction.setUserId(userId);
            reaction.setEmoji(emoji);
            reactionRepo.save(reaction);
            action = "ADDED";
            log.debug("[REACT] ADDED: userId={}, messageId={}, emoji={}", userId, messageId, emoji);

        } else {
            MessageReaction reaction = existing.get();
            if (reaction.getEmoji().equals(emoji)) {
                // REMOVED: DELETE same emoji (toggle off)
                previousEmoji = emoji;
                resultEmoji = null;
                reactionRepo.delete(reaction);
                action = "REMOVED";
                log.debug("[REACT] REMOVED: userId={}, messageId={}, emoji={}", userId, messageId, emoji);
            } else {
                // CHANGED: UPDATE to different emoji (UPDATE row, keep id stable for audit)
                previousEmoji = reaction.getEmoji();
                reaction.setEmoji(emoji);
                reactionRepo.save(reaction);
                action = "CHANGED";
                log.debug("[REACT] CHANGED: userId={}, messageId={}, from={}, to={}", userId, messageId, previousEmoji, emoji);
            }
        }

        // Publish event → ReactionBroadcaster handles AFTER_COMMIT broadcast
        eventPublisher.publishEvent(new ReactionChangedEvent(
                this, convId, messageId, userId, action, resultEmoji, previousEmoji
        ));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Rate limit: 5 reactions/second/user.
     * Key: rate:msg-react:{userId}, TTL: 1 second.
     * Pattern: INCR → if count==1 set EX 1 → if count > 5 reject.
     * Fail-open khi Redis down.
     */
    private void checkReactRateLimit(UUID userId) {
        String rateKey = "rate:msg-react:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, 1, TimeUnit.SECONDS);
            }
            if (count != null && count > REACT_RATE_LIMIT_PER_SECOND) {
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS,
                        "MSG_RATE_LIMITED",
                        "React quá nhanh, thử lại sau 1 giây");
            }
        } catch (AppException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("[REACT] Redis unavailable for rate limit check (key={}), fail-open", rateKey);
        }
    }
}
