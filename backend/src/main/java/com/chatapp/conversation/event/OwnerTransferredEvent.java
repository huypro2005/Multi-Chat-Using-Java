package com.chatapp.conversation.event;

import java.util.UUID;

/**
 * Event fire AFTER_COMMIT cho:
 *  - POST /api/conversations/{id}/transfer-owner (autoTransferred=false)
 *  - POST /api/conversations/{id}/leave khi OWNER leave (autoTransferred=true)
 *
 * Atomic 2-way swap, broadcast 1 event thay vì 2 ROLE_CHANGED để tránh "2 OWNER" flicker.
 *
 * Broadcaster bắn `/topic/conv.{convId}` envelope OWNER_TRANSFERRED
 * với previousOwner {userId, username}, newOwner {userId, username, fullName}, autoTransferred.
 *
 * Khi autoTransferred=true, fire TRƯỚC MemberRemovedEvent (reason=LEFT) trong flow /leave.
 *
 * Xem SOCKET_EVENTS.md §3.10.
 */
public record OwnerTransferredEvent(
        UUID conversationId,
        UUID previousOwnerUserId,
        UUID newOwnerUserId,
        boolean autoTransferred
) {}
