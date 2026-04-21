package com.chatapp.conversation.event;

import java.util.UUID;

/**
 * Event fire AFTER_COMMIT của POST /api/conversations/{id}/members (W7-D2).
 * 1 event per user được add (không gộp batch) — shape nhất quán với MEMBER_REMOVED.
 *
 * Broadcaster bắn 2 destinations:
 *  1) `/topic/conv.{convId}` — MEMBER_ADDED envelope cho members hiện hữu.
 *  2) `/user/{addedUserId}/queue/conv-added` — ConversationSummaryDto cho user vừa add.
 *
 * Xem SOCKET_EVENTS.md §3.7.
 */
public record MemberAddedEvent(
        UUID conversationId,
        UUID addedUserId,
        UUID actorUserId
) {}
