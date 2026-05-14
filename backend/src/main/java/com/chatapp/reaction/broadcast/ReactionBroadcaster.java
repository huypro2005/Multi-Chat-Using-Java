package com.chatapp.reaction.broadcast;

import com.chatapp.reaction.event.ReactionChangedEvent;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broadcasts REACTION_CHANGED event qua WebSocket sau khi transaction commit (W8-D1).
 *
 * Design:
 * - @TransactionalEventListener(AFTER_COMMIT): đảm bảo chỉ broadcast sau DB commit thành công.
 * - @Transactional(REQUIRES_NEW): tạo TX mới để đọc DB (userRepo) sau khi TX gốc đã close.
 *   Không có REQUIRES_NEW → LazyInitializationException khi access DB entity.
 * - try-catch toàn bộ: broadcast fail không propagate → service đã commit rồi.
 * - LinkedHashMap: support null values (Map.of() throw NPE với null value như emoji=null khi REMOVED).
 *
 * Destination: /topic/conv.{convId} — broadcast tới toàn bộ members của conv (kể cả caller).
 * FE handler tự phân biệt action theo field "action" + patch cache theo userId.
 *
 * Envelope (SOCKET_EVENTS.md §3.16):
 * {
 *   "type": "REACTION_CHANGED",
 *   "payload": {
 *     "messageId": "uuid",
 *     "userId": "uuid",
 *     "userName": "string",
 *     "action": "ADDED | REMOVED | CHANGED",
 *     "emoji": "string | null",
 *     "previousEmoji": "string | null"
 *   }
 * }
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReactionBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepo;

    /**
     * Handle ReactionChangedEvent và broadcast REACTION_CHANGED tới conv topic.
     *
     * @param event event published bởi ReactionService sau toggle thành công
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onReactionChanged(ReactionChangedEvent event) {
        try {
            // Load user để lấy fullName snapshot tại thời điểm broadcast
            User user = userRepo.findById(event.getUserId()).orElse(null);
            String userName = user != null ? user.getFullName() : "Unknown";

            // Build payload — LinkedHashMap để support null values (emoji=null khi REMOVED)
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("messageId", event.getMessageId().toString());
            payload.put("userId", event.getUserId().toString());
            payload.put("userName", userName);
            payload.put("action", event.getAction());
            payload.put("emoji", event.getEmoji());               // null khi REMOVED
            payload.put("previousEmoji", event.getPreviousEmoji()); // null khi ADDED

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "REACTION_CHANGED");
            envelope.put("payload", payload);

            String destination = "/topic/conv." + event.getConvId();
            messagingTemplate.convertAndSend(destination, envelope);

            log.debug("[REACTION] Broadcasted REACTION_CHANGED: action={}, userId={}, messageId={}, convId={}",
                    event.getAction(), event.getUserId(), event.getMessageId(), event.getConvId());

        } catch (Exception e) {
            // Broadcast fail không được propagate — DB đã committed
            log.error("[REACTION] Failed to broadcast REACTION_CHANGED: messageId={}, userId={}, convId={}",
                    event.getMessageId(), event.getUserId(), event.getConvId(), e);
        }
    }
}
