package com.chatapp.message.broadcast;

import com.chatapp.message.dto.MessageDto;
import com.chatapp.message.dto.SenderDto;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.event.MessageCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests cho MessageBroadcaster.
 *
 * T-BR-01: onMessageCreated → convertAndSend với destination và envelope đúng.
 * T-BR-02: convertAndSend throw RuntimeException → không propagate (broadcast fail-safe).
 */
@ExtendWith(MockitoExtension.class)
class MessageBroadcasterTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private MessageBroadcaster broadcaster;

    private UUID convId;
    private MessageDto sampleDto;

    @BeforeEach
    void setUp() {
        broadcaster = new MessageBroadcaster(messagingTemplate);
        convId = UUID.randomUUID();
        UUID msgId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();

        SenderDto sender = new SenderDto(senderId, "user1", "User One", null);
        sampleDto = new MessageDto(
                msgId,
                convId,
                sender,
                MessageType.TEXT,
                "Hello",
                null,
                null,
                OffsetDateTime.now(ZoneOffset.UTC),
                null,
                null
        );
    }

    // =========================================================================
    // T-BR-01: Broadcast với destination và envelope đúng
    // =========================================================================

    @Test
    void onMessageCreated_broadcastsToCorrectTopicWithEnvelope() {
        MessageCreatedEvent event = new MessageCreatedEvent(convId, sampleDto);

        broadcaster.onMessageCreated(event);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(
                eq("/topic/conv." + convId),
                payloadCaptor.capture()
        );

        Map<String, Object> envelope = payloadCaptor.getValue();
        assertEquals("MESSAGE_CREATED", envelope.get("type"));
        assertSame(sampleDto, envelope.get("payload"));
    }

    // =========================================================================
    // T-BR-02: convertAndSend throws → không propagate (fail-safe)
    // =========================================================================

    @Test
    void onMessageCreated_whenBrokerThrows_doesNotPropagate() {
        doThrow(new RuntimeException("Broker unavailable"))
                .when(messagingTemplate).convertAndSend(any(String.class), any(Object.class));

        MessageCreatedEvent event = new MessageCreatedEvent(convId, sampleDto);

        // Must NOT throw — broadcast failure must be swallowed
        assertDoesNotThrow(() -> broadcaster.onMessageCreated(event));
    }
}
