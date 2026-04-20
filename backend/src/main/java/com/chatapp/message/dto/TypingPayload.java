package com.chatapp.message.dto;

/**
 * Payload for typing indicator STOMP frames.
 *
 * Client sends to /app/conv.{convId}.typing:
 *   { "action": "START" }  — user started typing
 *   { "action": "STOP" }   — user stopped typing
 *
 * Rate limit: 1 event/2s per user/conversation (enforced in ChatTypingHandler).
 */
public record TypingPayload(String action) {
    // "START" | "STOP"
}
