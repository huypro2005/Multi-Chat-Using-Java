package com.chatapp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

import java.nio.charset.StandardCharsets;

/**
 * Customize STOMP ERROR frame để expose error code từ {@link MessageDeliveryException}
 * vào header {@code message} của ERROR frame — FE dựa vào header này để map error code
 * (AUTH_REQUIRED / AUTH_TOKEN_EXPIRED / FORBIDDEN / ...) theo SOCKET_EVENTS.md mục 6.
 * <p>
 * Mặc định Spring STOMP trả ERROR frame rỗng khi interceptor throw exception —
 * FE sẽ chỉ thấy "Connection closed" thay vì error code cụ thể.
 * <p>
 * Root cause: MessageDeliveryException thường wrap cause bên trong (nested message),
 * nên phải unwrap đến exception gốc để lấy đúng code.
 */
@Slf4j
@Component
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    @Override
    public Message<byte[]> handleClientMessageProcessingError(Message<byte[]> clientMessage, Throwable ex) {
        String errorCode = extractErrorCode(ex);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(errorCode);
        accessor.setLeaveMutable(true);

        // Body có thể rỗng — FE đọc từ header `message`. Để một đoạn text tối thiểu
        // để debug dễ hơn khi log raw frame.
        byte[] body = errorCode.getBytes(StandardCharsets.UTF_8);

        log.debug("STOMP ERROR frame: code={}, cause={}", errorCode,
                ex != null ? ex.getClass().getSimpleName() : "null");

        return MessageBuilder.createMessage(body, accessor.getMessageHeaders());
    }

    /**
     * Unwrap exception chain tìm message đầu tiên không rỗng.
     * MessageDeliveryException thường wrap cause nên phải loop đến root.
     */
    private String extractErrorCode(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && !msg.isBlank()) {
                return msg;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return "SERVER_ERROR";
    }
}
