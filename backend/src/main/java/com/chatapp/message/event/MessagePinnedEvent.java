package com.chatapp.message.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a message is pinned (AFTER_COMMIT).
 * Consumed by PinBroadcaster to send MESSAGE_PINNED to /topic/conv.{convId}.
 */
public record MessagePinnedEvent(
        UUID convId,
        UUID messageId,
        UUID userId,
        Instant pinnedAt
) {}
