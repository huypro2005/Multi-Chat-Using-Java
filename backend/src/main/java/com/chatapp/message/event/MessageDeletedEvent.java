package com.chatapp.message.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Event published WITHIN @Transactional after a message is soft-deleted.
 * MessageBroadcaster listens with AFTER_COMMIT to broadcast MESSAGE_DELETED
 * only after the transaction commits successfully.
 */
public record MessageDeletedEvent(
        UUID conversationId,
        UUID messageId,
        Instant deletedAt,
        UUID deletedBy
) {}
