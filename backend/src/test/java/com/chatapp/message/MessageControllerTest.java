package com.chatapp.message;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.user.repository.UserAuthProviderRepository;
import com.chatapp.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests cho Message REST endpoints.
 *
 * Tests:
 *  T01: sendMessage_happy                   → 201, message saved, conv.lastMessageAt updated
 *  T02: sendMessage_nonMember               → 404 CONV_NOT_FOUND
 *  T03: sendMessage_emptyContent            → 400 VALIDATION_FAILED
 *  T04: sendMessage_contentTooLong          → 400 VALIDATION_FAILED
 *  T05: sendMessage_replyToDifferentConv    → 400 VALIDATION_FAILED
 *  T06: sendMessage_replyToNonExistent      → 400 VALIDATION_FAILED
 *  T07: sendMessage_rateLimitExceeded       → 429 RATE_LIMITED
 *  T08: getMessages_happyPath               → 200, items sorted ASC, hasMore=false
 *  T09: getMessages_empty                   → 200, items=[], hasMore=false
 *  T10: getMessages_cursorPagination        → page 2 returns older messages
 *  T11: getMessages_nonMember               → 404 CONV_NOT_FOUND
 *  T12: getMessages_invalidLimit            → 400 VALIDATION_FAILED
 *  T13: sendMessage_withReply               → 201, replyToMessage preview in response
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAuthProviderRepository userAuthProviderRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationMemberRepository conversationMemberRepository;

    @Autowired
    private MessageRepository messageRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private FirebaseAuth firebaseAuth;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        messageRepository.deleteAll();
        conversationMemberRepository.deleteAll();
        conversationRepository.deleteAll();
        userAuthProviderRepository.deleteAll();
        userRepository.deleteAll();

        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        // Default: rate limit counter at 1 (well within limit)
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(900L);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(0L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String registerAndGetToken(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "username": "%s",
                                  "password": "Pass123!A",
                                  "fullName": "Test User %s"
                                }
                                """.formatted(email, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private String registerAndGetUserId(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "username": "%s",
                                  "password": "Pass123!A",
                                  "fullName": "Test User %s"
                                }
                                """.formatted(email, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("user").get("id").asText();
    }

    /**
     * Tạo conversation qua REST, trả về conversationId.
     */
    private String createOneOnOneConversation(String tokenA, String userBId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "ONE_ON_ONE",
                                  "memberIds": ["%s"]
                                }
                                """.formatted(userBId)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText();
    }

    // =========================================================================
    // T01: sendMessage happy path
    // =========================================================================

    @Test
    void sendMessage_happy_returns201AndUpdatesLastMessageAt() throws Exception {
        String tokenA = registerAndGetToken("t01_msg_a@test.com", "t01msg_a");
        String userBId = registerAndGetUserId("t01_msg_b@test.com", "t01msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        // Conversation.lastMessageAt should be null before first message
        Conversation convBefore = conversationRepository.findById(
                java.util.UUID.fromString(convId)).orElseThrow();
        assertNull(convBefore.getLastMessageAt(), "lastMessageAt should be null before first message");

        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "Hello World" }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.content").value("Hello World"))
                .andExpect(jsonPath("$.type").value("TEXT"))
                .andExpect(jsonPath("$.sender.username").value("t01msg_a"))
                .andExpect(jsonPath("$.replyToMessage").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());

        // Verify conversation.lastMessageAt updated
        Conversation convAfter = conversationRepository.findById(
                java.util.UUID.fromString(convId)).orElseThrow();
        assertNotNull(convAfter.getLastMessageAt(), "lastMessageAt should be set after message sent");

        // Verify message persisted in DB
        assertEquals(1, messageRepository.count(), "Should have exactly 1 message in DB");
    }

    // =========================================================================
    // T02: sendMessage non-member returns 404
    // =========================================================================

    @Test
    void sendMessage_nonMember_returns404() throws Exception {
        String tokenA = registerAndGetToken("t02_msg_a@test.com", "t02msg_a");
        String userBId = registerAndGetUserId("t02_msg_b@test.com", "t02msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        // Register a third user — not member of the conversation
        String tokenC = registerAndGetToken("t02_msg_c@test.com", "t02msg_c");

        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenC)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "I should not be able to send this" }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONV_NOT_FOUND"));
    }

    // =========================================================================
    // T03: sendMessage empty content returns 400
    // =========================================================================

    @Test
    void sendMessage_emptyContent_returns400() throws Exception {
        String tokenA = registerAndGetToken("t03_msg_a@test.com", "t03msg_a");
        String userBId = registerAndGetUserId("t03_msg_b@test.com", "t03msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "" }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T04: sendMessage content too long (5001 chars) returns 400
    // =========================================================================

    @Test
    void sendMessage_contentTooLong_returns400() throws Exception {
        String tokenA = registerAndGetToken("t04_msg_a@test.com", "t04msg_a");
        String userBId = registerAndGetUserId("t04_msg_b@test.com", "t04msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        String tooLong = "A".repeat(5001);

        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T05: sendMessage replyTo from a different conversation returns 400
    // =========================================================================

    @Test
    void sendMessage_replyToDifferentConv_returns400() throws Exception {
        String tokenA = registerAndGetToken("t05_msg_a@test.com", "t05msg_a");
        String userBId = registerAndGetUserId("t05_msg_b@test.com", "t05msg_b");
        String userCId = registerAndGetUserId("t05_msg_c@test.com", "t05msg_c");

        String convABId = createOneOnOneConversation(tokenA, userBId);

        // tokenA also creates conversation with C
        String convACId = createOneOnOneConversation(tokenA, userCId);

        // Send a message in conv AC
        MvcResult msgResult = mockMvc.perform(post("/api/conversations/{convId}/messages", convACId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "Message in convAC" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String msgIdFromOtherConv = objectMapper.readTree(
                msgResult.getResponse().getContentAsString()).get("id").asText();

        // Try to reply with message from different conv in convAB — should fail
        mockMvc.perform(post("/api/conversations/{convId}/messages", convABId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "Attempting cross-conv reply",
                                  "replyToMessageId": "%s"
                                }
                                """.formatted(msgIdFromOtherConv)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T06: sendMessage replyTo non-existent returns 400
    // =========================================================================

    @Test
    void sendMessage_replyToNonExistent_returns400() throws Exception {
        String tokenA = registerAndGetToken("t06_msg_a@test.com", "t06msg_a");
        String userBId = registerAndGetUserId("t06_msg_b@test.com", "t06msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        String fakeMessageId = java.util.UUID.randomUUID().toString();

        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "Replying to ghost message",
                                  "replyToMessageId": "%s"
                                }
                                """.formatted(fakeMessageId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T07: sendMessage rate limit exceeded returns 429
    // =========================================================================

    @Test
    void sendMessage_rateLimitExceeded_returns429() throws Exception {
        String tokenA = registerAndGetToken("t07_msg_a@test.com", "t07msg_a");
        String userBId = registerAndGetUserId("t07_msg_b@test.com", "t07msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        // Simulate rate limit exceeded: increment returns > 30
        when(valueOps.increment(startsWith("rate:msg:"))).thenReturn(31L);

        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "Rate limited message" }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    // =========================================================================
    // T08: getMessages happy path — items sorted ASC, hasMore=false
    // =========================================================================

    @Test
    void getMessages_happyPath_returnsSortedAscAndHasMoreFalse() throws Exception {
        String tokenA = registerAndGetToken("t08_msg_a@test.com", "t08msg_a");
        String userBId = registerAndGetUserId("t08_msg_b@test.com", "t08msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        // Send 3 messages
        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "First" }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "Second" }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "Third" }
                                """))
                .andExpect(status().isCreated());

        MvcResult getResult = mockMvc.perform(get("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(false))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn();

        // Verify order is ASC (oldest first)
        var responseTree = objectMapper.readTree(getResult.getResponse().getContentAsString());
        var items = responseTree.get("items");
        assertEquals("First", items.get(0).get("content").asText());
        assertEquals("Second", items.get(1).get("content").asText());
        assertEquals("Third", items.get(2).get("content").asText());
    }

    // =========================================================================
    // T09: getMessages empty conversation
    // =========================================================================

    @Test
    void getMessages_empty_returnsEmptyList() throws Exception {
        String tokenA = registerAndGetToken("t09_msg_a@test.com", "t09msg_a");
        String userBId = registerAndGetUserId("t09_msg_b@test.com", "t09msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        mockMvc.perform(get("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    // =========================================================================
    // T10: getMessages cursor pagination
    // =========================================================================

    @Test
    void getMessages_cursorPagination_returnsOlderMessages() throws Exception {
        String tokenA = registerAndGetToken("t10_msg_a@test.com", "t10msg_a");
        String userBId = registerAndGetUserId("t10_msg_b@test.com", "t10msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        java.util.UUID convUuid = java.util.UUID.fromString(convId);
        java.util.UUID userAUuid = userRepository.findByEmail("t10_msg_a@test.com")
                .orElseThrow().getId();

        // Insert 5 messages directly with explicit timestamps spaced 1 day apart
        // (using days instead of ms to avoid H2 timezone offset issues in comparisons)
        java.time.OffsetDateTime base = java.time.OffsetDateTime.of(
                2020, 1, 1, 10, 0, 0, 0, java.time.ZoneOffset.UTC);

        com.chatapp.conversation.entity.Conversation conv =
                conversationRepository.findById(convUuid).orElseThrow();
        com.chatapp.user.entity.User sender =
                userRepository.findById(userAUuid).orElseThrow();

        for (int i = 1; i <= 5; i++) {
            com.chatapp.message.entity.Message msg =
                    com.chatapp.message.entity.Message.builder()
                            .conversation(conv)
                            .sender(sender)
                            .type(com.chatapp.message.enums.MessageType.TEXT)
                            .content("Message " + i)
                            .build();
            // Set createdAt manually before save (override @PrePersist)
            msg.setCreatedAt(base.plusDays(i));
            messageRepository.save(msg);
        }

        // Page 1 with limit=3 → expects Messages 3,4,5 (newest) and hasMore=true
        MvcResult page1 = mockMvc.perform(get("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn();

        String nextCursor = objectMapper.readTree(
                page1.getResponse().getContentAsString()).get("nextCursor").asText();

        // Page 2 using cursor — expects Messages 1,2 (older)
        MvcResult page2 = mockMvc.perform(get("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .param("limit", "3")
                        .param("cursor", nextCursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andReturn();

        var p2Items = objectMapper.readTree(page2.getResponse().getContentAsString()).get("items");
        assertTrue(p2Items.size() > 0, "Page 2 should have at least 1 item");
        for (var item : p2Items) {
            String content = item.get("content").asText();
            assertTrue(content.equals("Message 1") || content.equals("Message 2"),
                    "Page 2 should only contain older messages, got: " + content);
        }
    }

    // =========================================================================
    // T11: getMessages non-member returns 404
    // =========================================================================

    @Test
    void getMessages_nonMember_returns404() throws Exception {
        String tokenA = registerAndGetToken("t11_msg_a@test.com", "t11msg_a");
        String userBId = registerAndGetUserId("t11_msg_b@test.com", "t11msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        String tokenC = registerAndGetToken("t11_msg_c@test.com", "t11msg_c");

        mockMvc.perform(get("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONV_NOT_FOUND"));
    }

    // =========================================================================
    // T12: getMessages invalid limit returns 400
    // =========================================================================

    @Test
    void getMessages_invalidLimit_returns400() throws Exception {
        String tokenA = registerAndGetToken("t12_msg_a@test.com", "t12msg_a");
        String userBId = registerAndGetUserId("t12_msg_b@test.com", "t12msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        // limit=0
        mockMvc.perform(get("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        // limit=101
        mockMvc.perform(get("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T13: sendMessage with reply — replyToMessage preview in response
    // =========================================================================

    @Test
    void sendMessage_withReply_returns201WithReplyPreview() throws Exception {
        String tokenA = registerAndGetToken("t13_msg_a@test.com", "t13msg_a");
        String userBId = registerAndGetUserId("t13_msg_b@test.com", "t13msg_b");
        String convId = createOneOnOneConversation(tokenA, userBId);

        // Send original message
        MvcResult originalResult = mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "content": "Original message to reply to" }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String originalMsgId = objectMapper.readTree(
                originalResult.getResponse().getContentAsString()).get("id").asText();

        // Send reply
        mockMvc.perform(post("/api/conversations/{convId}/messages", convId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "This is a reply",
                                  "replyToMessageId": "%s"
                                }
                                """.formatted(originalMsgId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("This is a reply"))
                .andExpect(jsonPath("$.replyToMessage").isNotEmpty())
                .andExpect(jsonPath("$.replyToMessage.id").value(originalMsgId))
                .andExpect(jsonPath("$.replyToMessage.senderName").isNotEmpty())
                .andExpect(jsonPath("$.replyToMessage.contentPreview")
                        .value("Original message to reply to"));
    }
}
