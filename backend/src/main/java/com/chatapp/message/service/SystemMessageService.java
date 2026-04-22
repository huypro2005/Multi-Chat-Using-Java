package com.chatapp.message.service;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.message.dto.MessageDto;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.event.MessageCreatedEvent;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Service for creating and publishing SYSTEM messages (W7-D4).
 *
 * SYSTEM messages are server-generated audit trail entries that appear inline
 * in the message timeline to notify group members of management events.
 *
 * Contract: API_CONTRACT.md "MessageDto -- finalized shape (v1.2.0-w7-system)".
 *
 * Key invariants:
 * - sender_id is always NULL (no user actor in sender slot)
 * - content is always empty string ""
 * - type is always SYSTEM
 * - systemEventType must be non-null (one of the 8 SystemEventType constants)
 * - systemMetadata always includes actorId + actorName from the actor user
 * - Reuses MessageCreatedEvent + @TransactionalEventListener(AFTER_COMMIT) pipe
 *   for broadcast -- no new infrastructure needed
 *
 * Called within the same @Transactional as the triggering service method so that
 * the system message row and the management action (add member, etc.) commit atomically.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMessageService {

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final ConversationRepository conversationRepository;
    private final MessageMapper messageMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Create a SYSTEM message row and publish a MessageCreatedEvent so the
     * existing broadcast pipe forwards the message to all online subscribers.
     *
     * Must be called within a @Transactional method (the caller's transaction).
     * The broadcast fires AFTER_COMMIT via @TransactionalEventListener.
     *
     * @param convId     Conversation UUID where the SYSTEM message is inserted
     * @param actorId    UUID of the user who performed the management action
     * @param eventType  One of the 8 SystemEventType constants
     * @param metadata   Additional metadata fields (targetId, targetName, etc.)
     *                   actorId and actorName are injected automatically
     * @return The persisted Message entity
     */
    @Transactional
    public Message createAndPublish(UUID convId, UUID actorId, String eventType,
                                    Map<String, Object> metadata) {
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new IllegalStateException("Actor not found: " + actorId));

        // Build full metadata: inject actorId + actorName then merge caller's fields
        Map<String, Object> fullMetadata = new HashMap<>();
        fullMetadata.put("actorId", actorId.toString());
        fullMetadata.put("actorName", actor.getFullName());
        fullMetadata.putAll(metadata);

        // Load conversation reference (lazy — just need the entity for the FK)
        Conversation conv = conversationRepository.getReferenceById(convId);

        Message message = new Message();
        message.setConversation(conv);
        message.setSender(null);            // SYSTEM messages have null sender per contract
        message.setType(MessageType.SYSTEM);
        message.setContent("");             // always empty string, never null (FE contract)
        message.setSystemEventType(eventType);
        message.setSystemMetadata(fullMetadata);
        message.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

        message = messageRepository.save(message);

        // Reload to ensure eager associations (conversation) are populated for mapper
        message = messageRepository.findById(message.getId()).orElseThrow();
        MessageDto dto = messageMapper.toDto(message);

        // Piggyback on existing MESSAGE_CREATED broadcast pipe (AFTER_COMMIT listener)
        eventPublisher.publishEvent(new MessageCreatedEvent(convId, dto));

        log.debug("[SYSTEM] Created {} message in conv={} by actor={}",
                eventType, convId, actorId);

        return message;
    }
}
