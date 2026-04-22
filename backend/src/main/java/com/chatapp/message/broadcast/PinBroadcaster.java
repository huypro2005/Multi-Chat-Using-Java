package com.chatapp.message.broadcast;

import com.chatapp.message.event.MessagePinnedEvent;
import com.chatapp.message.event.MessageUnpinnedEvent;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Broadcasts MESSAGE_PINNED / MESSAGE_UNPINNED qua WebSocket sau khi transaction commit (W8-D2).
 *
 * Design:
 * - @TransactionalEventListener(AFTER_COMMIT): đảm bảo chỉ broadcast sau DB commit.
 * - @Transactional(REQUIRES_NEW): tạo TX mới để load User entity (DB access sau TX gốc đóng).
 * - try-catch toàn bộ: broadcast fail không propagate.
 * - HashMap (không Map.of()) để support null-tolerant.
 *
 * Destination: /topic/conv.{convId} — broadcast tới toàn bộ members.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PinBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onPinned(MessagePinnedEvent event) {
        try {
            User user = userRepository.findById(event.userId()).orElse(null);
            String userName = user != null ? user.getFullName() : "Unknown";

            Map<String, Object> pinnedBy = new HashMap<>();
            pinnedBy.put("userId", event.userId().toString());
            pinnedBy.put("userName", userName);

            Map<String, Object> payload = new HashMap<>();
            payload.put("messageId", event.messageId().toString());
            payload.put("conversationId", event.convId().toString());
            payload.put("pinnedBy", pinnedBy);
            payload.put("pinnedAt", event.pinnedAt().toString());

            Map<String, Object> envelope = Map.of(
                    "type", "MESSAGE_PINNED",
                    "payload", payload
            );

            messagingTemplate.convertAndSend("/topic/conv." + event.convId(), envelope);
            log.debug("[PinBroadcast] MESSAGE_PINNED → conv={}, msg={}", event.convId(), event.messageId());
        } catch (Exception e) {
            log.error("[PinBroadcast] Failed to broadcast MESSAGE_PINNED for msg={}", event.messageId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onUnpinned(MessageUnpinnedEvent event) {
        try {
            User user = userRepository.findById(event.userId()).orElse(null);
            String userName = user != null ? user.getFullName() : "Unknown";

            Map<String, Object> unpinnedBy = new HashMap<>();
            unpinnedBy.put("userId", event.userId().toString());
            unpinnedBy.put("userName", userName);

            Map<String, Object> payload = new HashMap<>();
            payload.put("messageId", event.messageId().toString());
            payload.put("conversationId", event.convId().toString());
            payload.put("unpinnedBy", unpinnedBy);

            Map<String, Object> envelope = Map.of(
                    "type", "MESSAGE_UNPINNED",
                    "payload", payload
            );

            messagingTemplate.convertAndSend("/topic/conv." + event.convId(), envelope);
            log.debug("[PinBroadcast] MESSAGE_UNPINNED → conv={}, msg={}", event.convId(), event.messageId());
        } catch (Exception e) {
            log.error("[PinBroadcast] Failed to broadcast MESSAGE_UNPINNED for msg={}", event.messageId(), e);
        }
    }
}
