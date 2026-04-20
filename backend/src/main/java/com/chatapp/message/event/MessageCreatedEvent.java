package com.chatapp.message.event;

import com.chatapp.message.dto.MessageDto;

import java.util.UUID;

/**
 * Spring application event được publish sau khi message save thành công.
 *
 * Dùng record vì immutable + tự generate equals/hashCode/toString.
 * Listener dùng @TransactionalEventListener(phase = AFTER_COMMIT) để đảm bảo
 * chỉ broadcast khi transaction commit thành công.
 */
public record MessageCreatedEvent(UUID conversationId, MessageDto messageDto) {}
