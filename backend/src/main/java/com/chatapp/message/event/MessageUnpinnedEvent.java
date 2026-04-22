package com.chatapp.message.event;

import java.util.UUID;

/**
 * Published after a message is unpinned (AFTER_COMMIT).
 * Consumed by PinBroadcaster to send MESSAGE_UNPINNED to /topic/conv.{convId}.
 */
public record MessageUnpinnedEvent(
        UUID convId,
        UUID messageId,
        UUID userId
) {}
