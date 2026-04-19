package com.chatapp.message.service;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.dto.*;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private static final int RATE_LIMIT_PER_MINUTE = 30;
    private static final int CONTENT_PREVIEW_MAX_LENGTH = 100;

    private final MessageRepository messageRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;

    // =========================================================================
    // sendMessage
    // =========================================================================

    /**
     * Gửi tin nhắn vào conversation.
     *
     * 1. Check membership — 404 nếu không phải thành viên (anti-enumeration).
     * 2. Validate replyToMessageId nếu có.
     * 3. Rate limit: 30 messages/minute per user (fail-open khi Redis down).
     * 4. Save message + update conversation.lastMessageAt.
     */
    @Transactional
    public MessageDto sendMessage(UUID currentUserId, UUID convId, SendMessageRequest req) {
        // Step 1: Check membership (anti-enumeration: trả 404 dù conv không tồn tại hay user không phải thành viên)
        if (!memberRepository.existsByConversation_IdAndUser_Id(convId, currentUserId)) {
            throw new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                    "Không tìm thấy cuộc trò chuyện");
        }

        // Step 2: Validate replyToMessageId nếu có
        if (req.replyToMessageId() != null) {
            if (!messageRepository.existsByIdAndConversation_Id(req.replyToMessageId(), convId)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "Tin nhắn reply không tồn tại trong cuộc trò chuyện này",
                        Map.of("field", "replyToMessageId", "error", "Invalid message reference"));
            }
        }

        // Step 3: Rate limit — fail-open nếu Redis down
        checkRateLimit(currentUserId);

        // Step 4: Load entities và save
        User sender = userRepository.getReferenceById(currentUserId);
        Conversation conversation = conversationRepository.getReferenceById(convId);

        MessageType type = req.type() != null ? req.type() : MessageType.TEXT;

        Message message = Message.builder()
                .conversation(conversation)
                .sender(sender)
                .type(type)
                .content(req.content())
                .build();

        if (req.replyToMessageId() != null) {
            Message replyTo = messageRepository.getReferenceById(req.replyToMessageId());
            message.setReplyToMessage(replyTo);
        }

        message = messageRepository.save(message);

        // Update conversation.lastMessageAt
        Conversation conv = conversationRepository.findById(convId).orElseThrow();
        conv.touchLastMessage(message.getCreatedAt());
        conversationRepository.save(conv);

        // Load full message for mapping (reload to get eager sender/replyTo)
        Message saved = messageRepository.findById(message.getId()).orElseThrow();
        return toMessageDto(saved);
    }

    // =========================================================================
    // getMessages
    // =========================================================================

    /**
     * Lấy lịch sử tin nhắn với cursor-based pagination.
     *
     * - Nếu cursor != null: lấy messages có createdAt < cursor.
     * - Query trả mới → cũ (DESC), sau đó reverse để trả FE cũ → mới (ASC).
     * - nextCursor = createdAt của item cũ nhất trong page (item đầu sau reverse).
     */
    @Transactional(readOnly = true)
    public MessageListResponse getMessages(UUID currentUserId, UUID convId,
                                           OffsetDateTime cursor, int limit) {
        // Validate limit
        if (limit < 1 || limit > 100) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Limit phải từ 1 đến 100",
                    Map.of("field", "limit", "error", "Must be between 1 and 100"));
        }

        // Check membership
        if (!memberRepository.existsByConversation_IdAndUser_Id(convId, currentUserId)) {
            throw new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                    "Không tìm thấy cuộc trò chuyện");
        }

        // Query limit+1 để detect hasMore
        int fetchSize = limit + 1;
        Pageable pageable = Pageable.ofSize(fetchSize);

        List<Message> results;
        if (cursor != null) {
            results = messageRepository
                    .findByConversation_IdAndCreatedAtBeforeAndDeletedAtIsNullOrderByCreatedAtDesc(
                            convId, cursor, pageable);
        } else {
            results = messageRepository
                    .findByConversation_IdAndDeletedAtIsNullOrderByCreatedAtDesc(convId, pageable);
        }

        boolean hasMore = results.size() > limit;
        List<Message> pageItems = new ArrayList<>(results.subList(0, Math.min(results.size(), limit)));

        // Reverse từ mới→cũ sang cũ→mới để FE hiển thị đúng thứ tự
        Collections.reverse(pageItems);

        // nextCursor = createdAt của item cũ nhất = item đầu tiên sau reverse
        // Normalize về UTC để FE parse được nhất quán
        String nextCursor = null;
        if (hasMore && !pageItems.isEmpty()) {
            nextCursor = pageItems.get(0).getCreatedAt()
                    .atZoneSameInstant(ZoneOffset.UTC)
                    .toOffsetDateTime()
                    .toString();
        }

        List<MessageDto> dtos = pageItems.stream().map(this::toMessageDto).toList();

        return new MessageListResponse(dtos, hasMore, nextCursor);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void checkRateLimit(UUID userId) {
        String rateKey = "rate:msg:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, 60, TimeUnit.SECONDS);
            }
            if (count != null && count > RATE_LIMIT_PER_MINUTE) {
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Quá nhiều tin nhắn, vui lòng chờ",
                        Map.of("retryAfterSeconds", 60));
            }
        } catch (AppException e) {
            throw e;
        } catch (DataAccessException e) {
            // Fail-open: Redis down thì vẫn cho gửi
            log.warn("Redis unavailable for rate limit check (key={}), fail-open", rateKey);
        }
    }

    private MessageDto toMessageDto(Message message) {
        SenderDto senderDto = null;
        if (message.getSender() != null) {
            User sender = message.getSender();
            senderDto = new SenderDto(
                    sender.getId(),
                    sender.getUsername(),
                    sender.getFullName(),
                    sender.getAvatarUrl()
            );
        }

        ReplyPreviewDto replyPreview = null;
        if (message.getReplyToMessage() != null) {
            Message replyMsg = message.getReplyToMessage();
            String senderName = replyMsg.getSender() != null
                    ? replyMsg.getSender().getFullName()
                    : "Deleted User";
            String contentPreview = replyMsg.getContent();
            if (contentPreview != null && contentPreview.length() > CONTENT_PREVIEW_MAX_LENGTH) {
                contentPreview = contentPreview.substring(0, CONTENT_PREVIEW_MAX_LENGTH) + "...";
            }
            replyPreview = new ReplyPreviewDto(replyMsg.getId(), senderName, contentPreview);
        }

        return new MessageDto(
                message.getId(),
                message.getConversation().getId(),
                senderDto,
                message.getType(),
                message.getContent(),
                replyPreview,
                message.getEditedAt(),
                message.getCreatedAt()
        );
    }
}
