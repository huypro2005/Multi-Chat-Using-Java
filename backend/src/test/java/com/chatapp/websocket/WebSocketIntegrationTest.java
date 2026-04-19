package com.chatapp.websocket;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.security.JwtTokenProvider;
import com.chatapp.user.entity.User;
import com.chatapp.user.enums.AuthMethod;
import com.chatapp.user.repository.UserRepository;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import javax.crypto.SecretKey;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests cho WebSocket + STOMP auth layer (W4-D3).
 *
 * Verify:
 *  - CONNECT frame với JWT valid → session established.
 *  - CONNECT với JWT invalid/missing/expired → ERROR frame chứa đúng error code.
 *  - SUBSCRIBE /topic/conv.{id} khi là member → OK.
 *  - SUBSCRIBE /topic/conv.{id} khi KHÔNG phải member → ERROR FORBIDDEN.
 *
 * Thiết kế test:
 *  - Dùng {@link WebSocketStompClient} với SockJS transport, connect đến {@code /ws} endpoint
 *    của Spring Boot app đang chạy ở RANDOM_PORT.
 *  - CompletableFuture để bắt async events (connected / error).
 *  - Error từ broker chuyển thành {@link Throwable} trong handleException — check message header.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@ActiveProfiles("test")
class WebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationMemberRepository conversationMemberRepository;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    /**
     * AuthService / JwtAuthFilter inject StringRedisTemplate — mock để context start.
     * Không có interaction thật với Redis trong test WS này.
     */
    @MockBean
    @SuppressWarnings("unused")
    private StringRedisTemplate redisTemplate;

    private WebSocketStompClient stompClient;

    private User userAlice;
    private User userBob;
    private User userCharlie; // non-member
    private Conversation conv;

    @BeforeEach
    void setUp() {
        // Cleanup theo thứ tự FK
        conversationMemberRepository.deleteAll();
        conversationRepository.deleteAll();
        userRepository.deleteAll();

        // Tạo 3 users
        userAlice = userRepository.save(User.builder()
                .username("ws_alice")
                .email("ws_alice@test.com")
                .fullName("Alice WS")
                .passwordHash("$2a$12$hashed")
                .status("active")
                .build());
        userBob = userRepository.save(User.builder()
                .username("ws_bob")
                .email("ws_bob@test.com")
                .fullName("Bob WS")
                .passwordHash("$2a$12$hashed")
                .status("active")
                .build());
        userCharlie = userRepository.save(User.builder()
                .username("ws_charlie")
                .email("ws_charlie@test.com")
                .fullName("Charlie WS")
                .passwordHash("$2a$12$hashed")
                .status("active")
                .build());

        // Tạo conversation có alice + bob là member
        conv = conversationRepository.save(Conversation.builder()
                .type(ConversationType.ONE_ON_ONE)
                .createdBy(userAlice)
                .build());
        conversationMemberRepository.save(ConversationMember.builder()
                .conversation(conv)
                .user(userAlice)
                .role(MemberRole.MEMBER)
                .build());
        conversationMemberRepository.save(ConversationMember.builder()
                .conversation(conv)
                .user(userBob)
                .role(MemberRole.MEMBER)
                .build());

        // Setup STOMP client với raw WebSocket transport (không qua SockJS).
        // Lý do: SockJS wrap CONNECT error thành transport close frame với code 1002,
        // khiến client không parse được STOMP ERROR frame đúng cách trong test.
        // Native WebSocket giữ nguyên STOMP ERROR frame → handleException fire với header "message".
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new StringMessageConverter());
    }

    @AfterEach
    void tearDown() {
        if (stompClient != null && stompClient.isRunning()) {
            stompClient.stop();
        }
    }

    // =========================================================================
    // T01: CONNECT với JWT valid → session established
    // =========================================================================

    @Test
    void connect_withValidJWT_succeeds() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(userAlice, AuthMethod.PASSWORD);

        StompSession session = connectAndExpectSuccess(token);

        assertNotNull(session, "Session should be connected");
        assertTrue(session.isConnected(), "Session should report connected=true");
        session.disconnect();
    }

    // =========================================================================
    // T02: CONNECT với JWT invalid → ERROR frame AUTH_REQUIRED
    // =========================================================================

    @Test
    void connect_withInvalidJWT_receivesAuthRequired() {
        String invalidToken = "not-a-valid-jwt-at-all.garbage.token";

        String errorMessage = connectAndExpectError(invalidToken);

        assertNotNull(errorMessage, "Should receive ERROR frame");
        assertTrue(errorMessage.contains("AUTH_REQUIRED"),
                "ERROR message should contain AUTH_REQUIRED, but was: " + errorMessage);
    }

    // =========================================================================
    // T03: CONNECT với JWT expired → ERROR frame AUTH_TOKEN_EXPIRED
    // =========================================================================

    @Test
    void connect_withExpiredJWT_receivesAuthTokenExpired() {
        String expiredToken = generateExpiredToken(userAlice);

        String errorMessage = connectAndExpectError(expiredToken);

        assertNotNull(errorMessage, "Should receive ERROR frame");
        assertTrue(errorMessage.contains("AUTH_TOKEN_EXPIRED"),
                "ERROR message should contain AUTH_TOKEN_EXPIRED, but was: " + errorMessage);
    }

    // =========================================================================
    // T04: CONNECT không có Authorization header → ERROR frame AUTH_REQUIRED
    // =========================================================================

    @Test
    void connect_withNoAuthHeader_receivesAuthRequired() {
        String errorMessage = connectAndExpectError(null);

        assertNotNull(errorMessage, "Should receive ERROR frame");
        assertTrue(errorMessage.contains("AUTH_REQUIRED"),
                "ERROR message should contain AUTH_REQUIRED, but was: " + errorMessage);
    }

    // =========================================================================
    // T05: SUBSCRIBE /topic/conv.{id} khi là member → OK
    // =========================================================================

    @Test
    void subscribe_asMember_succeeds() throws Exception {
        String token = jwtTokenProvider.generateAccessToken(userAlice, AuthMethod.PASSWORD);
        StompSession session = connectAndExpectSuccess(token);

        CompletableFuture<Throwable> subError = new CompletableFuture<>();
        session.setAutoReceipt(false);

        StompSession.Subscription subscription = session.subscribe(
                "/topic/conv." + conv.getId(),
                new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return String.class;
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        // W4 test: chưa broadcast — không expect payload.
                    }
                });

        assertNotNull(subscription, "Subscription object should not be null");
        assertNotNull(subscription.getSubscriptionId(), "Subscription should have id");

        // Nếu SUBSCRIBE bị reject, broker gửi ERROR frame + đóng session async.
        // Đợi ngắn để xem có error không.
        Thread.sleep(500);
        assertTrue(session.isConnected(), "Session should still be connected after member SUBSCRIBE");
        assertTrue(!subError.isDone(), "No error expected for member subscription");

        subscription.unsubscribe();
        session.disconnect();
    }

    // =========================================================================
    // T06: SUBSCRIBE /topic/conv.{id} khi KHÔNG phải member → ERROR FORBIDDEN
    // =========================================================================

    @Test
    void subscribe_asNonMember_receivesForbidden() throws Exception {
        String tokenCharlie = jwtTokenProvider.generateAccessToken(userCharlie, AuthMethod.PASSWORD);

        // Dùng raw WebSocket để gửi CONNECT + SUBSCRIBE + đọc ERROR frame nguyên bản.
        // Lý do: DefaultStompSession không expose header "message" từ ERROR frame qua
        // callback API — phải parse raw frame.
        CompletableFuture<String> errorCodeFuture = new CompletableFuture<>();
        CompletableFuture<Boolean> connectedFuture = new CompletableFuture<>();

        String connectFrame = buildStompConnectFrame(tokenCharlie);
        String subscribeFrame = buildStompSubscribeFrame("/topic/conv." + conv.getId(), "sub-0");

        StandardWebSocketClient rawClient = new StandardWebSocketClient();
        AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                session.sendMessage(new TextMessage(connectFrame));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                String payload = message.getPayload();
                if (payload.startsWith("CONNECTED")) {
                    connectedFuture.complete(true);
                    session.sendMessage(new TextMessage(subscribeFrame));
                } else if (payload.startsWith("ERROR")) {
                    String code = extractStompHeader(payload, "message");
                    if (code == null) {
                        int bodyStart = payload.indexOf("\n\n");
                        if (bodyStart > 0) {
                            code = payload.substring(bodyStart + 2).replace("\u0000", "").trim();
                        }
                    }
                    if (!errorCodeFuture.isDone()) {
                        errorCodeFuture.complete(code != null && !code.isBlank() ? code : "UNKNOWN_ERROR");
                    }
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                if (!errorCodeFuture.isDone()) {
                    errorCodeFuture.complete("TRANSPORT_CLOSED:" + status.getCode());
                }
            }
        };

        rawClient.execute(handler, "ws://localhost:" + port + "/ws");

        assertTrue(connectedFuture.get(5, TimeUnit.SECONDS), "CONNECT should succeed for valid JWT");
        String errorMessage = errorCodeFuture.get(5, TimeUnit.SECONDS);
        assertNotNull(errorMessage, "Should receive ERROR frame");
        assertTrue(errorMessage.contains("FORBIDDEN"),
                "ERROR message should contain FORBIDDEN, but was: " + errorMessage);
    }

    /**
     * Build STOMP 1.2 SUBSCRIBE frame.
     */
    private String buildStompSubscribeFrame(String destination, String subscriptionId) {
        return "SUBSCRIBE\n"
                + "id:" + subscriptionId + "\n"
                + "destination:" + destination + "\n"
                + "\n"
                + "\u0000";
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Connect với token (nullable). Nếu null → không gửi Authorization header.
     * Trả StompSession khi afterConnected fire. Timeout 5s.
     */
    private StompSession connectAndExpectSuccess(String token) throws Exception {
        CompletableFuture<StompSession> future = new CompletableFuture<>();

        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }

        stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                        future.complete(session);
                    }

                    @Override
                    public void handleException(StompSession session, StompCommand command,
                                                StompHeaders headers, byte[] payload, Throwable exception) {
                        future.completeExceptionally(exception);
                    }

                    @Override
                    public void handleTransportError(StompSession session, Throwable exception) {
                        if (!future.isDone()) {
                            future.completeExceptionally(exception);
                        }
                    }
                });

        return future.get(5, TimeUnit.SECONDS);
    }

    /**
     * Connect với token (nullable). Gửi raw STOMP CONNECT frame qua WebSocket,
     * capture ERROR frame body/header trả về từ server.
     * <p>
     * Lý do KHÔNG dùng {@link WebSocketStompClient}: {@code DefaultStompSession} wrap
     * CONNECT rejection thành {@code ConnectionLostException("Connection closed")}
     * qua {@code handleTransportError} — không expose được header {@code message} của
     * ERROR frame. Cần raw WebSocket để đọc STOMP frame nguyên bản.
     */
    private String connectAndExpectError(String token) {
        CompletableFuture<String> errorCodeFuture = new CompletableFuture<>();

        StandardWebSocketClient rawClient = new StandardWebSocketClient();
        String connectFrame = buildStompConnectFrame(token);

        AbstractWebSocketHandler handler = new AbstractWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // Gửi CONNECT frame ngay khi WS handshake xong.
                session.sendMessage(new TextMessage(connectFrame));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) {
                String payload = message.getPayload();
                // Expect STOMP ERROR frame: "ERROR\nmessage:AUTH_REQUIRED\n...\n\n<body>\0"
                if (payload.startsWith("ERROR")) {
                    String code = extractStompHeader(payload, "message");
                    if (code == null) {
                        // fallback body
                        int bodyStart = payload.indexOf("\n\n");
                        if (bodyStart > 0) {
                            code = payload.substring(bodyStart + 2).replace("\u0000", "").trim();
                        }
                    }
                    if (!errorCodeFuture.isDone()) {
                        errorCodeFuture.complete(code != null && !code.isBlank() ? code : "UNKNOWN_ERROR");
                    }
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
                if (!errorCodeFuture.isDone()) {
                    errorCodeFuture.complete("TRANSPORT_CLOSED:" + status.getCode());
                }
            }
        };

        try {
            rawClient.execute(handler, "ws://localhost:" + port + "/ws");
            return errorCodeFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError("Expected ERROR frame within 5s: " + e.getMessage(), e);
        }
    }

    /**
     * Build STOMP 1.2 CONNECT frame. Nếu token null → không set Authorization header.
     */
    private String buildStompConnectFrame(String token) {
        StringBuilder sb = new StringBuilder();
        sb.append("CONNECT\n");
        sb.append("accept-version:1.2\n");
        sb.append("host:localhost\n");
        sb.append("heart-beat:10000,10000\n");
        if (token != null) {
            sb.append("Authorization:Bearer ").append(token).append("\n");
        }
        sb.append("\n"); // End of headers
        sb.append("\u0000"); // STOMP frame terminator (NULL byte)
        return sb.toString();
    }

    /**
     * Extract giá trị header từ STOMP frame raw text.
     * Format: "CMD\nheader1:value1\nheader2:value2\n\nbody\0"
     */
    private String extractStompHeader(String frame, String headerName) {
        String[] lines = frame.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break; // End of headers
            int colon = line.indexOf(':');
            if (colon > 0) {
                String key = line.substring(0, colon).trim();
                if (headerName.equalsIgnoreCase(key)) {
                    return line.substring(colon + 1).trim();
                }
            }
        }
        return null;
    }

    /**
     * Generate JWT với expiration đã qua (past) — reuse cùng secret với JwtTokenProvider.
     * Không dùng generateTokenWithExpiration (package-private) vì test ở package khác.
     */
    private String generateExpiredToken(User user) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        long past = System.currentTimeMillis() - 60_000; // 1 phút trước
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("auth_method", AuthMethod.PASSWORD.getValue())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(past - 60_000))
                .expiration(new Date(past))
                .signWith(key)
                .compact();
    }
}
