package com.chatapp.message.service;

import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.entity.Message;
import com.chatapp.message.event.ReadUpdatedEvent;
import com.chatapp.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service xử lý read receipt: cập nhật conversation_members.last_read_message_id
 * và broadcast READ_UPDATED sau commit.
 *
 * Pattern: forward-only idempotent — chỉ advance last_read khi messageId mới hơn hiện tại
 * (so sánh createdAt, không so UUID). No-op silent nếu incoming <= current.
 *
 * Contract: SOCKET_EVENTS.md §3f.2 (W7-D5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private final ConversationMemberRepository memberRepo;
    private final MessageRepository messageRepo;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Đánh dấu user đã đọc đến messageId trong convId.
     *
     * <p>Flow:
     * <ol>
     *   <li>Load ConversationMember — 403 nếu không phải thành viên.</li>
     *   <li>Load Message — 404 nếu không tồn tại.</li>
     *   <li>Validate message thuộc conv — MSG_NOT_IN_CONV nếu cross-conv.</li>
     *   <li>Forward-only idempotent: nếu incoming.createdAt <= current.createdAt → no-op, return.</li>
     *   <li>Update last_read_message_id, save, publish ReadUpdatedEvent (AFTER_COMMIT broadcast).</li>
     * </ol>
     *
     * @param convId    path variable từ STOMP destination
     * @param userId    từ Principal (KHÔNG trust payload)
     * @param messageId payload.messageId() — message vừa được đọc
     * @throws AppException 403 NOT_MEMBER, 404 MSG_NOT_FOUND, 403 MSG_NOT_IN_CONV
     */
    @Transactional
    public void markRead(UUID convId, UUID userId, UUID messageId) {
        // Step 1: Load member — 403 nếu không phải thành viên
        ConversationMember member = memberRepo
                .findByConversation_IdAndUser_Id(convId, userId)
                .orElseThrow(() -> new AppException(HttpStatus.FORBIDDEN, "NOT_MEMBER",
                        "Bạn không phải thành viên của cuộc trò chuyện"));

        // Step 2: Load incoming message — 404 nếu không tồn tại
        Message message = messageRepo.findById(messageId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MSG_NOT_FOUND",
                        "Tin nhắn không tồn tại"));

        // Step 3: Validate message thuộc đúng conv (anti-enum: code rõ ràng vì member check đã pass)
        if (!message.getConversation().getId().equals(convId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "MSG_NOT_IN_CONV",
                    "Tin nhắn không thuộc cuộc trò chuyện này");
        }

        // Step 4: Forward-only idempotent — chỉ advance nếu incoming mới hơn current
        if (member.getLastReadMessageId() != null) {
            Optional<Message> currentLastReadOpt = messageRepo.findById(member.getLastReadMessageId());
            if (currentLastReadOpt.isPresent()) {
                Message currentLastRead = currentLastReadOpt.get();
                // incoming.createdAt <= current.createdAt → no-op (không update, không broadcast)
                if (!currentLastRead.getCreatedAt().isBefore(message.getCreatedAt())) {
                    log.debug("[READ] No-op (not advancing): userId={}, convId={}, incomingMsgId={}, currentMsgId={}",
                            userId, convId, messageId, member.getLastReadMessageId());
                    return;
                }
            }
            // currentLastRead đã bị hard-delete (FK SET NULL trigger) → treat as null path → advance
        }

        // Step 5: Update và publish event
        member.setLastReadMessageId(messageId);
        memberRepo.save(member);

        eventPublisher.publishEvent(new ReadUpdatedEvent(convId, userId, messageId, Instant.now()));
        log.debug("[READ] Marked read: userId={}, convId={}, msgId={}", userId, convId, messageId);
    }
}
