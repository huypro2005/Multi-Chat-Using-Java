package com.chatapp.message.service;

import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.exception.AppException;
import com.chatapp.message.entity.Message;
import com.chatapp.message.event.MessagePinnedEvent;
import com.chatapp.message.event.MessageUnpinnedEvent;
import com.chatapp.message.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Service xử lý pin/unpin message (W8-D2, ADR-023).
 *
 * Authorization:
 *   - GROUP: chỉ OWNER hoặc ADMIN được pin/unpin.
 *   - ONE_ON_ONE (DIRECT): mọi member được pin/unpin.
 *
 * Limit: max 3 pinned messages per conversation.
 * Idempotent: pin đã pinned → no-op (không broadcast).
 * Race note: V1 single-instance acceptable; V2 multi-instance cần distributed lock.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PinService {

    private static final int PIN_LIMIT = 3;

    private final MessageRepository messageRepository;
    private final ConversationMemberRepository memberRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void pin(UUID messageId, UUID userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MSG_NOT_FOUND",
                        "Không tìm thấy tin nhắn"));

        UUID convId = message.getConversation().getId();

        ConversationMember member = memberRepository.findByConversation_IdAndUser_Id(convId, userId)
                .orElseThrow(() -> new AppException(HttpStatus.FORBIDDEN, "NOT_MEMBER",
                        "Không phải thành viên"));

        // Role check: GROUP chỉ OWNER/ADMIN, ONE_ON_ONE mọi member
        if (message.getConversation().getType() == ConversationType.GROUP
                && !member.getRole().isAdminOrHigher()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Chỉ chủ nhóm/phó nhóm được ghim");
        }

        if (message.getDeletedAt() != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "MSG_DELETED",
                    "Tin nhắn đã bị xóa");
        }

        // Idempotent: đã pinned → no-op, không broadcast
        if (message.getPinnedAt() != null) {
            return;
        }

        // Limit check (V1 single-instance; V2 cần distributed lock hoặc DB trigger)
        long currentPinned = messageRepository.countPinnedInConversation(convId);
        if (currentPinned >= PIN_LIMIT) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PIN_LIMIT_EXCEEDED",
                    "Đã đạt giới hạn " + PIN_LIMIT + " tin ghim",
                    Map.of("currentCount", currentPinned, "limit", (long) PIN_LIMIT));
        }

        Instant now = Instant.now();
        message.setPinnedAt(now);
        message.setPinnedByUserId(userId);
        messageRepository.save(message);

        eventPublisher.publishEvent(new MessagePinnedEvent(convId, messageId, userId, now));

        log.info("[Pin] Message {} pinned by user {} in conv {}", messageId, userId, convId);
    }

    @Transactional
    public void unpin(UUID messageId, UUID userId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "MSG_NOT_FOUND",
                        "Không tìm thấy tin nhắn"));

        UUID convId = message.getConversation().getId();

        ConversationMember member = memberRepository.findByConversation_IdAndUser_Id(convId, userId)
                .orElseThrow(() -> new AppException(HttpStatus.FORBIDDEN, "NOT_MEMBER",
                        "Không phải thành viên"));

        if (message.getConversation().getType() == ConversationType.GROUP
                && !member.getRole().isAdminOrHigher()) {
            throw new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Chỉ chủ nhóm/phó nhóm được bỏ ghim");
        }

        if (message.getPinnedAt() == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "MESSAGE_NOT_PINNED",
                    "Tin nhắn chưa được ghim");
        }

        message.setPinnedAt(null);
        message.setPinnedByUserId(null);
        messageRepository.save(message);

        eventPublisher.publishEvent(new MessageUnpinnedEvent(convId, messageId, userId));

        log.info("[Pin] Message {} unpinned by user {} in conv {}", messageId, userId, convId);
    }
}
