package com.chatapp.message.service;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.dto.*;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.event.MessageCreatedEvent;
import com.chatapp.message.event.MessageDeletedEvent;
import com.chatapp.message.event.MessageUpdatedEvent;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private static final int RATE_LIMIT_PER_MINUTE = 30;
    private static final int EDIT_RATE_LIMIT_PER_MINUTE = 10;
    private static final int DELETE_RATE_LIMIT_PER_MINUTE = 10;
    private static final int CONTENT_MAX_LENGTH = 5000;
    private static final long EDIT_WINDOW_SECONDS = 300; // 5 minutes
    private static final Duration DEDUP_TTL = Duration.ofSeconds(60);
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
                    Pattern.CASE_INSENSITIVE);

    private final MessageRepository messageRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final MessageMapper messageMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final SimpMessagingTemplate messagingTemplate;

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
        MessageDto dto = messageMapper.toDto(saved);
        eventPublisher.publishEvent(new MessageCreatedEvent(convId, dto));
        return dto;
    }

    // =========================================================================
    // sendViaStomp — Path B (ADR-016)
    // =========================================================================

    /**
     * Handles STOMP inbound message from /app/conv.{convId}.message.
     *
     * Flow:
     *  1. Validate payload (tempId UUID format, content 1..5000 chars non-blank)
     *  2. Authorize: sender must be member of conversation
     *  3. Rate limit: 30 msg/min/user via Redis INCR
     *  4. Dedup: SET NX EX 60 on key msg:dedup:{userId}:{tempId}
     *     - If key already exists (duplicate/retry frame): re-send ACK idempotently
     *     - If key is new: save DB, update dedup value to real messageId
     *  5. Publish MessageCreatedEvent → broadcaster sends broadcast AFTER_COMMIT
     *  6. Send ACK to /user/queue/acks AFTER_COMMIT via TransactionSynchronization
     *
     * tempId is propagated through AppException.details so @MessageExceptionHandler
     * can echo it back in the ERROR frame.
     */
    @Transactional
    public void sendViaStomp(UUID convId, UUID userId, SendMessagePayload payload) {
        String tempId = payload.tempId();

        // Step 1: Validate
        validateStompPayload(payload, tempId);
        // log.info("[STOMP] Payload validated for userId={}, convId={}, tempId={}", userId, convId, tempId);
        // Step 2: Authorize (anti-enumeration: 404 for both non-member and non-existent conv)
        if (!memberRepository.existsByConversation_IdAndUser_Id(convId, userId)) {
            throw new AppException(HttpStatus.NOT_FOUND, "CONV_NOT_FOUND",
                    "Không tìm thấy cuộc trò chuyện",
                    Map.of("tempId", tempId));
        }

        // Step 3: Rate limit (fail-open when Redis down)
        checkStompRateLimit(userId, tempId);

        // Step 4: Dedup — SET NX EX (atomic, must run BEFORE save)
        String dedupKey = "msg:dedup:" + userId + ":" + tempId;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "PENDING", DEDUP_TTL);

        if (Boolean.FALSE.equals(isNew)) {
            // Key already exists → duplicate frame or retry
            handleDuplicateFrame(userId, tempId, dedupKey);
            return;
        }

        // Step 5: Save DB
        User sender = userRepository.getReferenceById(userId);
        Conversation conversation = conversationRepository.getReferenceById(convId);

        Message message = Message.builder()
                .conversation(conversation)
                .sender(sender)
                .type(MessageType.TEXT)
                .content(payload.content().trim())
                .build();

        message = messageRepository.save(message);
        // log.info("[STOMP] Message saved for userId={}, convId={}, tempId={}, messageId={}",
        //         userId, convId, tempId, message.getId());

        // Update dedup value to real messageId (TTL preserved via SET with KEEPTTL not available
        // in Spring Data Redis — use setIfPresent with same TTL as fallback; race window acceptable)
        redisTemplate.opsForValue().set(dedupKey, message.getId().toString(), DEDUP_TTL);
        // log.info("[STOMP] Dedup redis key updated for userId={}, tempId={}, messageId={}", userId, tempId, message.getId());

        // Update conversation.lastMessageAt
        Conversation conv = conversationRepository.findById(convId).orElseThrow();
        conv.touchLastMessage(message.getCreatedAt());
        conversationRepository.save(conv);

        // Reload to get eager associations for mapper
        Message saved = messageRepository.findById(message.getId()).orElseThrow();
        MessageDto dto = messageMapper.toDto(saved);

        // Step 6: Publish broadcast event (broadcaster handles AFTER_COMMIT)
        // log.info("[STOMP] Publishing message created event for userId={}, convId={}, tempId={}", userId, convId, tempId);
        eventPublisher.publishEvent(new MessageCreatedEvent(convId, dto));

        // Step 7: Send ACK AFTER_COMMIT — avoid ACK before potential rollback
        // log.info("[STOMP] Registering transaction synchronization for ACK, userId={}, convId={}, tempId={}",
        //         userId, convId, tempId);
        final String ackTempId = tempId;
        final String ackUserId = userId.toString();
        final MessageDto ackDto = dto;
        // log.info("[STOMP] Checking transaction synchronization active for userId={}, tempId={}: {}",
        //         userId, tempId, TransactionSynchronizationManager.isSynchronizationActive());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendAck(ackUserId, ackTempId, ackDto);
                }
            });
        } else {
            // Fallback: no active transaction (e.g. called outside @Transactional in tests)
            // log.warn("[STOMP] No active transaction synchronization — sending ACK immediately for tempId={}", ackTempId);
            sendAck(ackUserId, ackTempId, ackDto);
        }
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

        List<MessageDto> dtos = pageItems.stream().map(messageMapper::toDto).toList();

        return new MessageListResponse(dtos, hasMore, nextCursor);
    }

    // =========================================================================
    // editViaStomp — Edit message via STOMP (W5-D2)
    // =========================================================================

    /**
     * Handles STOMP inbound edit from /app/conv.{convId}.edit.
     *
     * Flow per contract SOCKET_EVENTS.md §3c.5:
     *  1. Validate clientEditId (UUID format) and newContent (1..5000, non-blank)
     *  2. Rate limit: 10 edit/min/user via Redis INCR on rate:msg-edit:{userId}
     *  3. Dedup: SET NX EX 60 on key msg:edit-dedup:{userId}:{clientEditId}
     *     - Duplicate found: if value != PENDING → re-send ACK idempotently; if PENDING → silent drop
     *  4. Load message by messageId:
     *     - null / wrong conv / not owner / soft-deleted → MSG_NOT_FOUND (anti-enumeration)
     *  5. Check edit window: now() - message.createdAt > 300s → MSG_EDIT_WINDOW_EXPIRED
     *  6. Check no-op: newContent.trim().equals(message.content.trim()) → MSG_NO_CHANGE
     *  7. Update message in @Transactional + update dedup value to messageId
     *  8. Publish MessageUpdatedEvent → broadcaster sends MESSAGE_UPDATED AFTER_COMMIT
     *  9. Send ACK to /user/queue/acks {operation:"EDIT", clientId, message} AFTER_COMMIT
     */
    @Transactional
    public void editViaStomp(UUID convId, UUID userId, EditMessagePayload payload) {
        String clientEditId = payload.clientEditId();

        // Step 1: Validate clientEditId and newContent
        validateEditPayload(payload, clientEditId);

        // Step 2: Rate limit for edits (fail-open when Redis down)
        checkEditRateLimit(userId, clientEditId);

        // Step 3: Dedup — SET NX EX (atomic, must run BEFORE any DB mutation)
        String dedupKey = "msg:edit-dedup:" + userId + ":" + clientEditId;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "PENDING", DEDUP_TTL);

        if (Boolean.FALSE.equals(isNew)) {
            handleDuplicateEditFrame(userId, clientEditId, dedupKey);
            return;
        }

        // Step 4: Load message — anti-enumeration: merge all not-found/not-owner cases → MSG_NOT_FOUND
        Message message = messageRepository.findById(payload.messageId()).orElse(null);

        if (message == null
                || !message.getConversation().getId().equals(convId)
                || message.getSender() == null
                || !message.getSender().getId().equals(userId)
                || message.getDeletedAt() != null) {
            // Clear the dedup key so the client can retry with same clientEditId if it was a transient issue
            // (but really this is a permanent failure so dedup key expiry is fine too)
            throw new AppException(HttpStatus.NOT_FOUND, "MSG_NOT_FOUND",
                    "Tin nhắn không tồn tại hoặc không thể sửa",
                    Map.of("clientEditId", clientEditId));
        }

        // Step 5: Check edit window (5 minutes from createdAt)
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        long secondsSinceCreated = java.time.Duration.between(
                message.getCreatedAt().atZoneSameInstant(ZoneOffset.UTC).toOffsetDateTime(),
                now
        ).getSeconds();

        if (secondsSinceCreated > EDIT_WINDOW_SECONDS) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_EDIT_WINDOW_EXPIRED",
                    "Đã hết thời gian sửa tin nhắn (5 phút)",
                    Map.of("clientEditId", clientEditId));
        }

        // Step 6: No-op check
        String trimmedNew = payload.newContent().trim();
        String trimmedOld = message.getContent() != null ? message.getContent().trim() : "";
        if (trimmedNew.equals(trimmedOld)) {
            throw new AppException(HttpStatus.UNPROCESSABLE_ENTITY, "MSG_NO_CHANGE",
                    "Nội dung không thay đổi",
                    Map.of("clientEditId", clientEditId));
        }

        // Step 7: Update message
        message.setContent(trimmedNew);
        message.setEditedAt(now);
        message = messageRepository.save(message);

        // Update dedup value to real messageId (so retry can re-send ACK idempotently)
        redisTemplate.opsForValue().set(dedupKey, message.getId().toString(), DEDUP_TTL);

        // Reload for mapper to get eager associations
        Message saved = messageRepository.findById(message.getId()).orElseThrow();
        MessageDto dto = messageMapper.toDto(saved);

        // Step 8: Publish broadcast event (AFTER_COMMIT)
        eventPublisher.publishEvent(new MessageUpdatedEvent(convId, dto));

        // Step 9: Send ACK AFTER_COMMIT
        final String ackClientEditId = clientEditId;
        final String ackUserId = userId.toString();
        final MessageDto ackDto = dto;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendEditAck(ackUserId, ackClientEditId, ackDto);
                }
            });
        } else {
            sendEditAck(ackUserId, ackClientEditId, ackDto);
        }
    }

    // =========================================================================
    // deleteViaStomp — Delete message via STOMP (W5-D3)
    // =========================================================================

    /**
     * Handles STOMP inbound delete from /app/conv.{convId}.delete.
     *
     * Flow per contract SOCKET_EVENTS.md §3d.5:
     *  1. Validate clientDeleteId (UUID format) and messageId presence
     *  2. Rate limit: 10 delete/min/user via Redis INCR on rate:msg-delete:{userId}
     *  3. Dedup: SET NX EX 60 on key msg:delete-dedup:{userId}:{clientDeleteId}
     *     - Duplicate found: if value != PENDING → re-send minimal ACK idempotently; if PENDING → silent drop
     *  4. Load message by messageId:
     *     - null / wrong conv / not owner / soft-deleted → MSG_NOT_FOUND (anti-enumeration)
     *  5. Soft delete in @Transactional: set deletedAt + deletedBy, save
     *  6. Update dedup value to messageId (so retry can re-send ACK)
     *  7. Publish MessageDeletedEvent → broadcaster sends MESSAGE_DELETED broadcast AFTER_COMMIT
     *  8. Send minimal DELETE ACK to /user/queue/acks AFTER_COMMIT
     *     {operation:"DELETE", clientId, message:{id, conversationId, deletedAt, deletedBy}}
     *
     * NOTE: No edit-window check — unlike EDIT (5 min), DELETE has no time limit (ADR-018).
     */
    @Transactional
    public void deleteViaStomp(UUID convId, UUID userId, DeleteMessagePayload payload) {
        String clientDeleteId = payload.clientDeleteId();

        // Step 1: Validate clientDeleteId and messageId
        validateDeletePayload(payload, clientDeleteId);

        // Step 2: Rate limit for deletes (fail-open when Redis down)
        checkDeleteRateLimit(userId, clientDeleteId);

        // Step 3: Dedup — SET NX EX (atomic, must run BEFORE any DB mutation)
        String dedupKey = "msg:delete-dedup:" + userId + ":" + clientDeleteId;
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, "PENDING", DEDUP_TTL);

        if (Boolean.FALSE.equals(isNew)) {
            handleDuplicateDeleteFrame(userId, clientDeleteId, dedupKey, convId);
            return;
        }

        // Step 4: Load message — anti-enumeration: merge all not-found/not-owner/already-deleted → MSG_NOT_FOUND
        Message message = messageRepository.findById(payload.messageId()).orElse(null);

        if (message == null
                || !message.getConversation().getId().equals(convId)
                || message.getSender() == null
                || !message.getSender().getId().equals(userId)
                || message.getDeletedAt() != null) {
            throw new AppException(HttpStatus.NOT_FOUND, "MSG_NOT_FOUND",
                    "Tin nhắn không tồn tại hoặc không thể xóa",
                    Map.of("clientDeleteId", clientDeleteId));
        }

        // Step 5: Soft delete
        message.markAsDeletedBy(userId);
        message = messageRepository.save(message);

        Instant deletedAt = message.getDeletedAt().toInstant();

        // Step 6: Update dedup value to real messageId
        redisTemplate.opsForValue().set(dedupKey, message.getId().toString(), DEDUP_TTL);

        // Step 7: Publish broadcast event (AFTER_COMMIT)
        final UUID messageId = message.getId();
        eventPublisher.publishEvent(new MessageDeletedEvent(convId, messageId, deletedAt, userId));

        // Step 8: Send DELETE ACK AFTER_COMMIT
        final String ackClientDeleteId = clientDeleteId;
        final String ackUserId = userId.toString();
        final UUID ackConvId = convId;
        final Instant ackDeletedAt = deletedAt;
        final UUID ackDeletedBy = userId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendDeleteAck(ackUserId, ackClientDeleteId, messageId, ackConvId, ackDeletedAt, ackDeletedBy);
                }
            });
        } else {
            sendDeleteAck(ackUserId, ackClientDeleteId, messageId, ackConvId, ackDeletedAt, ackDeletedBy);
        }
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

    /**
     * Rate limit for STOMP path — same quota as REST but throws MSG_RATE_LIMITED
     * (distinct from REST RATE_LIMITED so FE can show correct error message).
     */
    private void checkStompRateLimit(UUID userId, String tempId) {
        String rateKey = "rate:msg:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, 60, TimeUnit.SECONDS);
            }
            if (count != null && count > RATE_LIMIT_PER_MINUTE) {
                long retryAfter = 60;
                try {
                    Long ttl = redisTemplate.getExpire(rateKey, TimeUnit.SECONDS);
                    if (ttl != null && ttl > 0) retryAfter = ttl;
                } catch (Exception ignored) { /* best-effort */ }
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "MSG_RATE_LIMITED",
                        "Gửi quá nhanh, thử lại sau " + retryAfter + " giây",
                        Map.of("tempId", tempId, "retryAfterSeconds", retryAfter));
            }
        } catch (AppException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for STOMP rate limit check (key={}), fail-open", rateKey);
        }
    }

    /**
     * Validate tempId (UUID format) and content (non-blank, 1..5000 chars).
     * Throws AppException with details.tempId set so exception handler can echo it.
     */
    private void validateStompPayload(SendMessagePayload payload, String tempId) {
        if (tempId == null || !UUID_PATTERN.matcher(tempId).matches()) {
            String echoed = tempId != null ? tempId : "unknown";
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "tempId không hợp lệ, phải là UUID v4",
                    Map.of("tempId", echoed, "field", "tempId", "error", "Must be UUID v4 format"));
        }

        String content = payload.content();
        if (content == null || content.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Nội dung tin nhắn không được để trống",
                    Map.of("tempId", tempId, "field", "content", "error", "Must not be blank"));
        }

        if (content.trim().length() > CONTENT_MAX_LENGTH) {
            throw new AppException(HttpStatus.BAD_REQUEST, "MSG_CONTENT_TOO_LONG",
                    "Tin nhắn quá dài (tối đa 5000 ký tự)",
                    Map.of("tempId", tempId, "maxLength", CONTENT_MAX_LENGTH,
                            "actualLength", content.trim().length()));
        }
    }

    /**
     * Handles duplicate/retry frames when dedup key already exists in Redis.
     *
     * - If value == "PENDING": previous save is still in-flight → drop silently.
     * - If value == real messageId: previous save completed → re-send ACK idempotently.
     *   DOES NOT re-publish MessageCreatedEvent to avoid double broadcast.
     */
    private void handleDuplicateFrame(UUID userId, String tempId, String dedupKey) {
        String savedValue;
        try {
            savedValue = redisTemplate.opsForValue().get(dedupKey);
        } catch (DataAccessException e) {
            log.warn("[DEDUP] Redis unavailable when reading dedup key={}, dropping frame", dedupKey);
            return;
        }

        if ("PENDING".equals(savedValue) || savedValue == null) {
            log.warn("[DEDUP] Concurrent/duplicate frame for tempId={}, userId={} (still PENDING) — dropping",
                    tempId, userId);
            return;
        }

        try {
            UUID existingMsgId = UUID.fromString(savedValue);
            messageRepository.findById(existingMsgId).ifPresentOrElse(
                    existing -> sendAck(userId.toString(), tempId, messageMapper.toDto(existing)),
                    () -> log.warn("[DEDUP] dedup key={} has messageId={} but not found in DB",
                            dedupKey, savedValue)
            );
        } catch (IllegalArgumentException e) {
            log.warn("[DEDUP] dedup key={} has invalid value={}", dedupKey, savedValue);
        }
    }

    /**
     * Validate clientEditId (UUID format) and newContent for editViaStomp.
     */
    private void validateEditPayload(EditMessagePayload payload, String clientEditId) {
        if (clientEditId == null || !UUID_PATTERN.matcher(clientEditId).matches()) {
            String echoed = clientEditId != null ? clientEditId : "unknown";
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "clientEditId không hợp lệ, phải là UUID v4",
                    Map.of("clientEditId", echoed, "field", "clientEditId", "error", "Must be UUID v4 format"));
        }

        String content = payload.newContent();
        if (content == null || content.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "Nội dung tin nhắn không được để trống",
                    Map.of("clientEditId", clientEditId, "field", "newContent", "error", "Must not be blank"));
        }

        if (content.trim().length() > CONTENT_MAX_LENGTH) {
            throw new AppException(HttpStatus.BAD_REQUEST, "MSG_CONTENT_TOO_LONG",
                    "Tin nhắn quá dài (tối đa 5000 ký tự)",
                    Map.of("clientEditId", clientEditId, "maxLength", CONTENT_MAX_LENGTH,
                            "actualLength", content.trim().length()));
        }
    }

    /**
     * Rate limit for edit operations — 10 edits/min/user.
     * Key: rate:msg-edit:{userId}. Fail-open when Redis unavailable.
     */
    private void checkEditRateLimit(UUID userId, String clientEditId) {
        String rateKey = "rate:msg-edit:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, 60, TimeUnit.SECONDS);
            }
            if (count != null && count > EDIT_RATE_LIMIT_PER_MINUTE) {
                long retryAfter = 60;
                try {
                    Long ttl = redisTemplate.getExpire(rateKey, TimeUnit.SECONDS);
                    if (ttl != null && ttl > 0) retryAfter = ttl;
                } catch (Exception ignored) { /* best-effort */ }
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "MSG_RATE_LIMITED",
                        "Sửa quá nhanh, thử lại sau " + retryAfter + " giây",
                        Map.of("clientEditId", clientEditId, "retryAfterSeconds", retryAfter));
            }
        } catch (AppException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for edit rate limit check (key={}), fail-open", rateKey);
        }
    }

    /**
     * Handles duplicate/retry edit frames when dedup key already exists in Redis.
     *
     * - If value == "PENDING": previous edit is still in-flight → drop silently.
     * - If value == real messageId: previous edit completed → re-send ACK idempotently.
     */
    private void handleDuplicateEditFrame(UUID userId, String clientEditId, String dedupKey) {
        String savedValue;
        try {
            savedValue = redisTemplate.opsForValue().get(dedupKey);
        } catch (DataAccessException e) {
            log.warn("[EDIT-DEDUP] Redis unavailable when reading dedup key={}, dropping frame", dedupKey);
            return;
        }

        if ("PENDING".equals(savedValue) || savedValue == null) {
            log.warn("[EDIT-DEDUP] Concurrent/duplicate frame for clientEditId={}, userId={} (still PENDING) — dropping",
                    clientEditId, userId);
            return;
        }

        // Real messageId stored — re-send ACK (idempotent)
        try {
            UUID existingMsgId = UUID.fromString(savedValue);
            messageRepository.findById(existingMsgId).ifPresentOrElse(
                    existing -> sendEditAck(userId.toString(), clientEditId, messageMapper.toDto(existing)),
                    () -> log.warn("[EDIT-DEDUP] dedup key={} has messageId={} but not found in DB",
                            dedupKey, savedValue)
            );
        } catch (IllegalArgumentException e) {
            log.warn("[EDIT-DEDUP] dedup key={} has invalid value={}", dedupKey, savedValue);
        }
    }

    /**
     * Send SEND-operation ACK to /user/queue/acks for the given sender.
     * Called after transaction commit via TransactionSynchronization.afterCommit().
     */
    private void sendAck(String userId, String tempId, MessageDto message) {
        try {
            messagingTemplate.convertAndSendToUser(
                    userId,
                    "/queue/acks",
                    new AckPayload("SEND", tempId, message)
            );
        } catch (Exception e) {
            log.error("[STOMP] Failed to send SEND ACK to userId={}, tempId={}", userId, tempId, e);
        }
    }

    /**
     * Send EDIT-operation ACK to /user/queue/acks for the given sender.
     * Called after transaction commit via TransactionSynchronization.afterCommit().
     */
    private void sendEditAck(String userId, String clientEditId, MessageDto message) {
        try {
            messagingTemplate.convertAndSendToUser(
                    userId,
                    "/queue/acks",
                    new AckPayload("EDIT", clientEditId, message)
            );
        } catch (Exception e) {
            log.error("[STOMP] Failed to send EDIT ACK to userId={}, clientEditId={}", userId, clientEditId, e);
        }
    }

    /**
     * Validate clientDeleteId (UUID format) and messageId presence.
     * Throws AppException with details.clientDeleteId set so exception handler can echo it.
     */
    private void validateDeletePayload(DeleteMessagePayload payload, String clientDeleteId) {
        if (clientDeleteId == null || !UUID_PATTERN.matcher(clientDeleteId).matches()) {
            String echoed = clientDeleteId != null ? clientDeleteId : "unknown";
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "clientDeleteId không hợp lệ, phải là UUID v4",
                    Map.of("clientDeleteId", echoed, "field", "clientDeleteId", "error", "Must be UUID v4 format"));
        }

        if (payload.messageId() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "messageId không được để trống",
                    Map.of("clientDeleteId", clientDeleteId, "field", "messageId", "error", "Must not be null"));
        }
    }

    /**
     * Rate limit for delete operations — 10 deletes/min/user.
     * Key: rate:msg-delete:{userId}. Fail-open when Redis unavailable.
     */
    private void checkDeleteRateLimit(UUID userId, String clientDeleteId) {
        String rateKey = "rate:msg-delete:" + userId;
        try {
            Long count = redisTemplate.opsForValue().increment(rateKey);
            if (count != null && count == 1) {
                redisTemplate.expire(rateKey, 60, TimeUnit.SECONDS);
            }
            if (count != null && count > DELETE_RATE_LIMIT_PER_MINUTE) {
                long retryAfter = 60;
                try {
                    Long ttl = redisTemplate.getExpire(rateKey, TimeUnit.SECONDS);
                    if (ttl != null && ttl > 0) retryAfter = ttl;
                } catch (Exception ignored) { /* best-effort */ }
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "MSG_RATE_LIMITED",
                        "Xóa quá nhanh, thử lại sau " + retryAfter + " giây",
                        Map.of("clientDeleteId", clientDeleteId, "retryAfterSeconds", retryAfter));
            }
        } catch (AppException e) {
            throw e;
        } catch (DataAccessException e) {
            log.warn("Redis unavailable for delete rate limit check (key={}), fail-open", rateKey);
        }
    }

    /**
     * Handles duplicate/retry delete frames when dedup key already exists in Redis.
     *
     * - If value == "PENDING": previous delete is still in-flight → drop silently.
     * - If value == real messageId: previous delete completed → re-send minimal ACK idempotently.
     */
    private void handleDuplicateDeleteFrame(UUID userId, String clientDeleteId, String dedupKey, UUID convId) {
        String savedValue;
        try {
            savedValue = redisTemplate.opsForValue().get(dedupKey);
        } catch (DataAccessException e) {
            log.warn("[DELETE-DEDUP] Redis unavailable when reading dedup key={}, dropping frame", dedupKey);
            return;
        }

        if ("PENDING".equals(savedValue) || savedValue == null) {
            log.warn("[DELETE-DEDUP] Concurrent/duplicate frame for clientDeleteId={}, userId={} (still PENDING) — dropping",
                    clientDeleteId, userId);
            return;
        }

        // Real messageId stored — re-send minimal DELETE ACK (idempotent)
        try {
            UUID existingMsgId = UUID.fromString(savedValue);
            messageRepository.findById(existingMsgId).ifPresentOrElse(
                    existing -> {
                        if (existing.getDeletedAt() != null && existing.getDeletedBy() != null) {
                            sendDeleteAck(userId.toString(), clientDeleteId,
                                    existing.getId(), convId,
                                    existing.getDeletedAt().toInstant(),
                                    existing.getDeletedBy());
                        }
                    },
                    () -> log.warn("[DELETE-DEDUP] dedup key={} has messageId={} but not found in DB",
                            dedupKey, savedValue)
            );
        } catch (IllegalArgumentException e) {
            log.warn("[DELETE-DEDUP] dedup key={} has invalid value={}", dedupKey, savedValue);
        }
    }

    /**
     * Send DELETE-operation minimal ACK to /user/queue/acks.
     *
     * Per contract §3d.3: ACK payload is minimal — only id, conversationId, deletedAt, deletedBy.
     * Does NOT send full MessageDto (unlike SEND/EDIT ACK).
     */
    private void sendDeleteAck(String userId, String clientDeleteId,
                                UUID messageId, UUID conversationId,
                                Instant deletedAt, UUID deletedBy) {
        try {
            Map<String, Object> messagePayload = Map.of(
                    "id", messageId.toString(),
                    "conversationId", conversationId.toString(),
                    "deletedAt", deletedAt.toString(),
                    "deletedBy", deletedBy.toString()
            );
            // AckPayload.message is typed as MessageDto, but DELETE ACK is minimal (not full DTO).
            // Use a raw Map envelope to match §3d.3 contract exactly.
            Map<String, Object> ackEnvelope = Map.of(
                    "operation", "DELETE",
                    "clientId", clientDeleteId,
                    "message", messagePayload
            );
            messagingTemplate.convertAndSendToUser(userId, "/queue/acks", ackEnvelope);
        } catch (Exception e) {
            log.error("[STOMP] Failed to send DELETE ACK to userId={}, clientDeleteId={}", userId, clientDeleteId, e);
        }
    }

}
