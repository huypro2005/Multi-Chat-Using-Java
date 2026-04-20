package com.chatapp.message.event;

import com.chatapp.message.dto.MessageDto;

import java.util.UUID;

/**
 * Spring application event được publish sau khi message edit thành công.
 *
 * Listener dùng @TransactionalEventListener(phase = AFTER_COMMIT) để đảm bảo
 * chỉ broadcast MESSAGE_UPDATED khi transaction commit thành công.
 */
public record MessageUpdatedEvent(UUID conversationId, MessageDto messageDto) {}
