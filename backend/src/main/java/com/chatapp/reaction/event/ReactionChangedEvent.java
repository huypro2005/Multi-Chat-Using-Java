package com.chatapp.reaction.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * ApplicationEvent được publish sau khi reaction toggle thành công.
 *
 * Publisher: ReactionService (trong @Transactional).
 * Listener: ReactionBroadcaster (@TransactionalEventListener AFTER_COMMIT).
 *
 * action: "ADDED" | "REMOVED" | "CHANGED"
 * emoji: emoji HIỆN TẠI sau toggle (null nếu REMOVED)
 * previousEmoji: emoji TRƯỚC toggle (null nếu ADDED, non-null nếu REMOVED/CHANGED)
 */
public class ReactionChangedEvent extends ApplicationEvent {

    private final UUID convId;
    private final UUID messageId;
    private final UUID userId;
    private final String action;
    private final String emoji;
    private final String previousEmoji;

    public ReactionChangedEvent(Object source, UUID convId, UUID messageId,
                                 UUID userId, String action, String emoji, String previousEmoji) {
        super(source);
        this.convId = convId;
        this.messageId = messageId;
        this.userId = userId;
        this.action = action;
        this.emoji = emoji;
        this.previousEmoji = previousEmoji;
    }

    public UUID getConvId() { return convId; }
    public UUID getMessageId() { return messageId; }
    public UUID getUserId() { return userId; }
    public String getAction() { return action; }
    public String getEmoji() { return emoji; }
    public String getPreviousEmoji() { return previousEmoji; }
}
