package com.chatapp.message.broadcast;

import com.chatapp.message.event.MessageCreatedEvent;
import com.chatapp.message.event.MessageUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
}
