package com.chatapp.conversation.broadcast;

import com.chatapp.conversation.event.ConversationUpdatedEvent;
import com.chatapp.conversation.event.GroupDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broadcast WebSocket events cho Group Chat lifecycle (W7-D1).
 *
 * Listen AFTER_COMMIT để đảm bảo transaction đã persist; nếu rollback thì không fire.
 * try-catch toàn bộ — broadcast fail KHÔNG được propagate (REST đã trả response trước đó).
 *
 * Xem SOCKET_EVENTS.md §3.6 (CONVERSATION_UPDATED) và §3.11 (GROUP_DELETED).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Broadcast CONVERSATION_UPDATED khi rename group hoặc đổi avatar.
     *
     * Envelope theo contract:
     * {
     *   "type": "CONVERSATION_UPDATED",
     *   "payload": {
     *     "conversationId": "uuid",
     *     "changes": {"name": "..."} | {"avatarUrl": "..." | null} | both,
     *     "updatedBy": {"userId": "uuid", "fullName": "..."}
     *   }
     * }
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationUpdated(ConversationUpdatedEvent event) {
        try {
            String destination = "/topic/conv." + event.conversationId();

            // Sử dụng LinkedHashMap để cho phép null values (Map.of throws NPE với null).
            Map<String, Object> updatedBy = new LinkedHashMap<>();
            updatedBy.put("userId", event.updatedByUserId().toString());
            updatedBy.put("fullName", event.updatedByFullName());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.conversationId().toString());
            payload.put("changes", event.changes()); // changes map có thể chứa avatarUrl: null (remove)
            payload.put("updatedBy", updatedBy);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "CONVERSATION_UPDATED");
            envelope.put("payload", payload);

            messagingTemplate.convertAndSend(destination, envelope);
            log.debug("Broadcasted CONVERSATION_UPDATED conv={} changes={}",
                    event.conversationId(), event.changes().keySet());
        } catch (Exception e) {
            log.error("Failed to broadcast CONVERSATION_UPDATED for conv {}",
                    event.conversationId(), e);
        }
    }

    /**
     * Broadcast GROUP_DELETED khi OWNER soft-delete group.
     *
     * Envelope:
     * {
     *   "type": "GROUP_DELETED",
     *   "payload": {
     *     "conversationId": "uuid",
     *     "deletedBy": {"userId": "uuid", "fullName": "..."},
     *     "deletedAt": "ISO8601"
     *   }
     * }
     *
     * Note: broadcaster fire AFTER_COMMIT → tại thời điểm này conversation_members
     * đã hard-deleted → chỉ client còn subscription active mới nhận được frame.
     * Acceptable V1 (xem SOCKET_EVENTS §8 Limitations).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onGroupDeleted(GroupDeletedEvent event) {
        try {
            String destination = "/topic/conv." + event.conversationId();

            Map<String, Object> deletedBy = new LinkedHashMap<>();
            deletedBy.put("userId", event.deletedByUserId().toString());
            deletedBy.put("fullName", event.deletedByFullName());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", event.conversationId().toString());
            payload.put("deletedBy", deletedBy);
            payload.put("deletedAt", event.deletedAt().toString());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "GROUP_DELETED");
            envelope.put("payload", payload);

            messagingTemplate.convertAndSend(destination, envelope);
            log.debug("Broadcasted GROUP_DELETED conv={}", event.conversationId());
        } catch (Exception e) {
            log.error("Failed to broadcast GROUP_DELETED for conv {}",
                    event.conversationId(), e);
        }
    }
}
