package com.chatapp.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;
import org.springframework.web.socket.messaging.SubProtocolHandler;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import java.util.Arrays;

/**
 * WebSocket + STOMP config — tuân theo {@code docs/SOCKET_EVENTS.md} mục 4.1.
 * <p>
 * Simple in-memory broker (V1). Khi scale >1 BE instance, migrate RabbitMQ (ADR-015).
 * <p>
 * Destinations:
 *   - {@code /topic/*} — pub/sub broadcast (messages trong conversation).
 *   - {@code /queue/*} — per-user queue (errors, notifications).
 *   - {@code /app/*}  — inbound (client → server), handler với @MessageMapping ở W5+.
 *   - {@code /user/*} — user-specific destinations, Spring resolve qua Principal.
 * <p>
 * Auth: {@link AuthChannelInterceptor} xử lý CONNECT (JWT) và SUBSCRIBE (member check).
 * <p>
 * Security:
 *   - {@code setAllowedOriginPatterns} đọc từ {@code app.websocket.allowed-origins} — KHÔNG hardcode "*".
 *   - {@code setMessageSizeLimit(64KB)} chặn DoS qua frame lớn.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Raw comma-separated origin patterns (e.g. "http://localhost:3000,http://localhost:5173").
     * Production phải set giá trị cụ thể qua env {@code WS_ALLOWED_ORIGINS}, không để "*".
     */
    @Value("${app.websocket.allowed-origins}")
    private String allowedOriginsRaw;

    private final AuthChannelInterceptor authChannelInterceptor;
    private final StompErrorHandler stompErrorHandler;
    private final ApplicationContext applicationContext;

    /**
     * Sau khi context refresh xong, lookup bean {@code subProtocolWebSocketHandler}
     * (type public là {@link WebSocketHandler}, implement thực là
     * {@link SubProtocolWebSocketHandler}) và set custom error handler lên
     * {@link StompSubProtocolHandler} nằm bên trong.
     * <p>
     * Dùng {@link ContextRefreshedEvent} thay vì {@code @PostConstruct} vì
     * {@code subProtocolWebSocketHandler} do {@code DelegatingWebSocketMessageBrokerConfiguration}
     * tạo — mà class đó lại phụ thuộc vào chính configurer này (circular).
     * Event listener chạy sau khi tất cả beans đã init → tránh circular reference.
     * <p>
     * Mục đích: ERROR frame có header {@code message} chứa đúng error code
     * (AUTH_REQUIRED / AUTH_TOKEN_EXPIRED / FORBIDDEN) theo SOCKET_EVENTS.md mục 6.
     */
    @EventListener(ContextRefreshedEvent.class)
    void configureStompErrorHandler() {
        WebSocketHandler current = applicationContext.getBean(
                "subProtocolWebSocketHandler", WebSocketHandler.class);
        while (current instanceof WebSocketHandlerDecorator decorator) {
            current = decorator.getDelegate();
        }
        if (current instanceof SubProtocolWebSocketHandler subProtocolHandler) {
            for (SubProtocolHandler handler : subProtocolHandler.getProtocolHandlers()) {
                if (handler instanceof StompSubProtocolHandler stompHandler) {
                    stompHandler.setErrorHandler(stompErrorHandler);
                }
            }
        }
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] originPatterns = parseOriginPatterns(allowedOriginsRaw);
        // Native WebSocket endpoint (browser/client nào hỗ trợ WS thẳng thì dùng).
        registry.addEndpoint("/ws")
                // setAllowedOriginPatterns (KHÔNG setAllowedOrigins) — cho phép pattern matching
                // và tương thích với allowCredentials=true trong SockJS handshake.
                .setAllowedOriginPatterns(originPatterns);
        // SockJS fallback cho browser cũ / proxy không hỗ trợ WS (separate path để tránh conflict).
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(originPatterns)
                .withSockJS();
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authChannelInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Giới hạn frame 64KB — chặn DoS qua payload lớn.
        registration.setMessageSizeLimit(64 * 1024);
        // Timeout gửi 10s — tránh connection "zombie" block broker thread.
        registration.setSendTimeLimit(10 * 1000);
        // Buffer tối đa 512KB cho outbound messages (SockJS fallback có thể buffer nhiều frames).
        registration.setSendBufferSizeLimit(512 * 1024);
    }

    private String[] parseOriginPatterns(String raw) {
        if (raw == null || raw.isBlank()) {
            // Fail-safe: không config → không allow origin nào. Sẽ fail handshake rõ ràng.
            return new String[0];
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
