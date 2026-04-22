package com.chatapp.message.broadcast;

import com.chatapp.message.event.MessageCreatedEvent;
import com.chatapp.message.event.MessageDeletedEvent;
import com.chatapp.message.event.MessageUpdatedEvent;
import com.chatapp.message.event.ReadUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broadcasts WebSocket events after database transactions commit.
 *
 * Design choices:
 * - @TransactionalEventListener(phase = AFTER_COMMIT): chỉ broadcast SAU khi
 *   transaction commit thành công. Nếu transaction rollback → không broadcast →
 *   FE không nhận "phantom" message.
 * - try-catch toàn bộ: broadcast fail (broker down, network issue) KHÔNG được
 *   propagate lên — REST 201 đã trả về trước đó, không thể rollback được.
 * - Destination: /topic/conv.{conversationId} — tất cả subscriber của conversation
 *   đều nhận (kể cả sender, FE tự dedupe bằng message id).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Broadcast MESSAGE_CREATED sau khi message mới được save thành công.
     *
     * Envelope: { "type": "MESSAGE_CREATED", "payload": MessageDto }
     * theo SOCKET_EVENTS.md mục 3.1.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(MessageCreatedEvent event) {
        try {
            String destination = "/topic/conv." + event.conversationId();
            Map<String, Object> envelope = Map.of(
                    "type", "MESSAGE_CREATED",
                    "payload", event.messageDto()
            );
            messagingTemplate.convertAndSend(destination, envelope);
            log.debug("Broadcasted MESSAGE_CREATED {} to {}", event.messageDto().id(), destination);
        } catch (Exception e) {
            // Broadcast fail không được ảnh hưởng REST 201 đã commit
            log.error("Failed to broadcast MESSAGE_CREATED {} to conv {}",
                    event.messageDto().id(), event.conversationId(), e);
        }
    }

    /**
     * Broadcast MESSAGE_UPDATED sau khi message được edit thành công.
     *
     * Payload minimal — chỉ fields thay đổi (id, conversationId, content, editedAt).
     * Receiver đã có message trong cache, chỉ cần update 2 fields thay đổi.
     * Xem SOCKET_EVENTS.md mục 3.2.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageUpdated(MessageUpdatedEvent event) {
        try {
            String destination = "/topic/conv." + event.conversationId();
            Map<String, Object> envelope = Map.of(
                    "type", "MESSAGE_UPDATED",
                    "payload", Map.of(
                            "id", event.messageDto().id(),
                            "conversationId", event.conversationId(),
                            "content", event.messageDto().content(),
                            "editedAt", event.messageDto().editedAt()
                    )
            );
            messagingTemplate.convertAndSend(destination, envelope);
            log.debug("Broadcasted MESSAGE_UPDATED {} to {}", event.messageDto().id(), destination);
        } catch (Exception e) {
            log.error("Failed to broadcast MESSAGE_UPDATED {} to conv {}",
                    event.messageDto().id(), event.conversationId(), e);
        }
    }

    /**
     * Broadcast MESSAGE_DELETED sau khi message bị soft-delete thành công.
     *
     * Payload minimal: chỉ id, conversationId, deletedAt, deletedBy.
     * Content đã nil → không broadcast content để tránh leak.
     * Xem SOCKET_EVENTS.md mục 3.3.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageDeleted(MessageDeletedEvent event) {
        try {
            String destination = "/topic/conv." + event.conversationId();
            Map<String, Object> envelope = Map.of(
                    "type", "MESSAGE_DELETED",
                    "payload", Map.of(
                            "id", event.messageId().toString(),
                            "conversationId", event.conversationId().toString(),
                            "deletedAt", event.deletedAt().toString(),
                            "deletedBy", event.deletedBy().toString()
                    )
            );
            messagingTemplate.convertAndSend(destination, envelope);
            log.debug("Broadcasted MESSAGE_DELETED {} to {}", event.messageId(), destination);
        } catch (Exception e) {
            log.error("Failed to broadcast MESSAGE_DELETED {} to conv {}",
                    event.messageId(), event.conversationId(), e);
        }
    }

    /**
     * Broadcast READ_UPDATED sau khi conversation_members.last_read_message_id cập nhật thành công.
     *
     * Chỉ broadcast khi advance forward — ReadReceiptService đảm bảo event chỉ được published
     * khi lastReadMessageId thực sự được cập nhật (không publish khi no-op).
     *
     * Envelope: { "type": "READ_UPDATED", "payload": { conversationId, userId, lastReadMessageId, readAt } }
     * Destination: /topic/conv.{convId} — tất cả members của conv nhận (kể cả sender multi-tab).
     *
     * Contract: SOCKET_EVENTS.md §3.13 (W7-D5).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReadUpdated(ReadUpdatedEvent event) {
        try {
            String destination = "/topic/conv." + event.convId();

            // Use LinkedHashMap để hỗ trợ null values nếu cần (Map.of() throws NPE với null)
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.convId().toString());
            payload.put("userId", event.userId().toString());
            payload.put("lastReadMessageId", event.messageId().toString());
            payload.put("readAt", event.readAt().toString());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "READ_UPDATED");
            envelope.put("payload", payload);

            messagingTemplate.convertAndSend(destination, envelope);
            log.debug("Broadcasted READ_UPDATED userId={} msgId={} to {}", event.userId(), event.messageId(), destination);
        } catch (Exception e) {
            log.error("Failed to broadcast READ_UPDATED for userId={}, convId={}",
                    event.userId(), event.convId(), e);
        }
    }
}
