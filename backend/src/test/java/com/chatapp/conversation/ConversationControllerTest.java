package com.chatapp.conversation;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests cho Conversation endpoints.
 *
 * Tests:
 *  W3-BE-1:  savingConversation_shouldPersistWithNonNullId          (critical UUID fix)
 *  T01:      createOneOnOne_happyPath_returns201
 *  T02:      createOneOnOne_duplicate_returns409_withConversationId
 *  T03:      createOneOnOne_targetUserNotFound_returns404
 *  T04:      createOneOnOne_withSelf_returns400
 *  T05:      createGroup_happyPath_returns201
 *  T06:      createGroup_missingName_returns400
 *  T07:      createGroup_tooFewMembers_returns400
 *  T08:      listConversations_happyPath_returns200
 *  T09:      listConversations_emptyList_returns200
 *  T10:      getConversation_happyPath_returns200
 *  T11:      getConversation_notMember_returns404
 *  T12:      searchUsers_happyPath_returns200
 *  T13:      searchUsers_queryTooShort_returns400
 *  T14:      createConversation_noAuth_returns401
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
class ConversationControllerTest {

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

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private FirebaseAuth firebaseAuth;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conversationMemberRepository.deleteAll();
        conversationRepository.deleteAll();
        userAuthProviderRepository.deleteAll();
        userRepository.deleteAll();

        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(900L);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(0L);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());
    }

    // =========================================================================
    // W3-BE-1: Critical UUID persistence test
    // =========================================================================

    /**
     * W3-BE-1: Saving Conversation must persist with non-null id.
     * Verifies that @PrePersist UUID generation works correctly (Option B fix).
     * If this fails → @GeneratedValue + insertable=false conflict not fixed.
     */
    @Test
    void savingConversation_shouldPersistWithNonNullId() {
        Conversation conv = new Conversation();
        conv.setType(ConversationType.ONE_ON_ONE);
        Conversation saved = conversationRepository.save(conv);
        assertNotNull(saved.getId(), "Conversation id must not be null after save");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String register(String email, String username) throws Exception {
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

    // =========================================================================
    // T01: Create ONE_ON_ONE happy path
    // =========================================================================

    @Test
    void createOneOnOne_happyPath_returns201() throws Exception {
        String tokenA = register("t01a@test.com", "t01_userA");
        String userBId = registerAndGetUserId("t01b@test.com", "t01_userB");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "ONE_ON_ONE",
                                  "name": null,
                                  "memberIds": ["%s"]
                                }
                                """.formatted(userBId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("ONE_ON_ONE"))
                .andExpect(jsonPath("$.name").doesNotExist())
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.lastMessageAt").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    // =========================================================================
    // T02: Duplicate ONE_ON_ONE returns 409
    // =========================================================================

    @Test
    void createOneOnOne_duplicate_returns409_withConversationId() throws Exception {
        String tokenA = register("t02a@test.com", "t02_userA");
        String userBId = registerAndGetUserId("t02b@test.com", "t02_userB");

        // First create
        MvcResult first = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ONE_ON_ONE", "name": null, "memberIds": ["%s"]}
                                """.formatted(userBId)))
                .andExpect(status().isCreated())
                .andReturn();

        String convId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("id").asText();

        // Second create — must return 409
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ONE_ON_ONE", "name": null, "memberIds": ["%s"]}
                                """.formatted(userBId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONV_ONE_ON_ONE_EXISTS"))
                .andExpect(jsonPath("$.details.conversationId").value(convId));
    }

    // =========================================================================
    // T03: Target user not found
    // =========================================================================

    @Test
    void createOneOnOne_targetUserNotFound_returns404() throws Exception {
        String tokenA = register("t03a@test.com", "t03_userA");
        String fakeUserId = "00000000-0000-0000-0000-000000000099";

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ONE_ON_ONE", "name": null, "memberIds": ["%s"]}
                                """.formatted(fakeUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONV_MEMBER_NOT_FOUND"));
    }

    // =========================================================================
    // T04: Cannot create ONE_ON_ONE with self
    // =========================================================================

    @Test
    void createOneOnOne_withSelf_returns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"t04a@test.com","username":"t04_userA","password":"Pass123!A","fullName":"User T04"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        String tokenA = tree.get("accessToken").asText();
        String userAId = tree.get("user").get("id").asText();

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ONE_ON_ONE", "name": null, "memberIds": ["%s"]}
                                """.formatted(userAId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T05: Create GROUP happy path
    // =========================================================================

    @Test
    void createGroup_happyPath_returns201() throws Exception {
        String tokenA = register("t05a@test.com", "t05_userA");
        String userBId = registerAndGetUserId("t05b@test.com", "t05_userB");
        String userCId = registerAndGetUserId("t05c@test.com", "t05_userC");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "GROUP",
                                  "name": "Test Group",
                                  "memberIds": ["%s", "%s"]
                                }
                                """.formatted(userBId, userCId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.name").value("Test Group"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.createdBy").isNotEmpty());
    }

    // =========================================================================
    // T06: GROUP missing name
    // =========================================================================

    @Test
    void createGroup_missingName_returns400() throws Exception {
        String tokenA = register("t06a@test.com", "t06_userA");
        String userBId = registerAndGetUserId("t06b@test.com", "t06_userB");
        String userCId = registerAndGetUserId("t06c@test.com", "t06_userC");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "GROUP",
                                  "name": null,
                                  "memberIds": ["%s", "%s"]
                                }
                                """.formatted(userBId, userCId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T07: GROUP too few members
    // =========================================================================

    @Test
    void createGroup_tooFewMembers_returns400() throws Exception {
        String tokenA = register("t07a@test.com", "t07_userA");
        String userBId = registerAndGetUserId("t07b@test.com", "t07_userB");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "GROUP",
                                  "name": "Small Group",
                                  "memberIds": ["%s"]
                                }
                                """.formatted(userBId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T07b: GROUP with caller in memberIds returns 400
    // =========================================================================

    @Test
    void createGroup_callerInMemberIds_returns400() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"t07b_a@test.com","username":"t07b_userA","password":"Pass123!A","fullName":"User T07B-A"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        String tokenA = tree.get("accessToken").asText();
        String userAId = tree.get("user").get("id").asText();
        String userBId = registerAndGetUserId("t07b_b@test.com", "t07b_userB");
        String userCId = registerAndGetUserId("t07b_c@test.com", "t07b_userC");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "GROUP",
                                  "name": "Bad Group",
                                  "memberIds": ["%s", "%s", "%s"]
                                }
                                """.formatted(userAId, userBId, userCId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T07c: GROUP with duplicate memberIds deduped — still valid if >= 2 unique
    // =========================================================================

    @Test
    void createGroup_duplicateMemberIds_deduped_returns201() throws Exception {
        String tokenA = register("t07c_a@test.com", "t07c_userA");
        String userBId = registerAndGetUserId("t07c_b@test.com", "t07c_userB");
        String userCId = registerAndGetUserId("t07c_c@test.com", "t07c_userC");

        // Send userB twice — after dedup should have 2 unique members (B + C), valid
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "GROUP",
                                  "name": "Dedup Group",
                                  "memberIds": ["%s", "%s", "%s"]
                                }
                                """.formatted(userBId, userCId, userBId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members.length()").value(3));
    }

    // =========================================================================
    // T07d: GROUP with duplicate memberIds only — fewer than 2 unique returns 400
    // =========================================================================

    @Test
    void createGroup_duplicateMemberIds_tooFewUnique_returns400() throws Exception {
        String tokenA = register("t07d_a@test.com", "t07d_userA");
        String userBId = registerAndGetUserId("t07d_b@test.com", "t07d_userB");

        // Send userB twice — after dedup only 1 unique member, invalid
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "type": "GROUP",
                                  "name": "Too Few Unique",
                                  "memberIds": ["%s", "%s"]
                                }
                                """.formatted(userBId, userBId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T08: List conversations happy path
    // =========================================================================

    @Test
    void listConversations_happyPath_returns200() throws Exception {
        String tokenA = register("t08a@test.com", "t08_userA");
        String userBId = registerAndGetUserId("t08b@test.com", "t08_userB");

        // Create a conversation first
        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ONE_ON_ONE", "name": null, "memberIds": ["%s"]}
                                """.formatted(userBId)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].type").value("ONE_ON_ONE"))
                .andExpect(jsonPath("$.content[0].unreadCount").value(0));
    }

    // =========================================================================
    // T09: List conversations empty
    // =========================================================================

    @Test
    void listConversations_emptyList_returns200() throws Exception {
        String tokenA = register("t09a@test.com", "t09_userA");

        mockMvc.perform(get("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // =========================================================================
    // T10: Get conversation by id
    // =========================================================================

    @Test
    void getConversation_happyPath_returns200() throws Exception {
        String tokenA = register("t10a@test.com", "t10_userA");
        String userBId = registerAndGetUserId("t10b@test.com", "t10_userB");

        MvcResult createResult = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ONE_ON_ONE", "name": null, "memberIds": ["%s"]}
                                """.formatted(userBId)))
                .andExpect(status().isCreated())
                .andReturn();

        String convId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(convId))
                .andExpect(jsonPath("$.type").value("ONE_ON_ONE"))
                .andExpect(jsonPath("$.members").isArray())
                .andExpect(jsonPath("$.members.length()").value(2));
    }

    // =========================================================================
    // T11: Get conversation - not a member returns 404
    // =========================================================================

    @Test
    void getConversation_notMember_returns404() throws Exception {
        String tokenA = register("t11a@test.com", "t11_userA");
        String userBId = registerAndGetUserId("t11b@test.com", "t11_userB");
        String tokenC = register("t11c@test.com", "t11_userC");

        // A creates conversation with B
        MvcResult createResult = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ONE_ON_ONE", "name": null, "memberIds": ["%s"]}
                                """.formatted(userBId)))
                .andExpect(status().isCreated())
                .andReturn();

        String convId = objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText();

        // C tries to access A-B conversation → 404
        mockMvc.perform(get("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + tokenC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONV_NOT_FOUND"));
    }

    // =========================================================================
    // T12: Search users happy path
    // =========================================================================

    @Test
    void searchUsers_happyPath_returns200() throws Exception {
        String tokenA = register("t12a@test.com", "t12_alice");
        register("t12b@test.com", "t12_bob");
        register("t12c@test.com", "t12_charlie");

        mockMvc.perform(get("/api/users/search")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("q", "t12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                // Should see bob and charlie but NOT alice (herself)
                .andExpect(jsonPath("$.length()").value(2));
    }

    // =========================================================================
    // T13: Search users query too short
    // =========================================================================

    @Test
    void searchUsers_queryTooShort_returns400() throws Exception {
        String tokenA = register("t13a@test.com", "t13_userA");

        mockMvc.perform(get("/api/users/search")
                        .header("Authorization", "Bearer " + tokenA)
                        .param("q", "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // T14: Create conversation without auth returns 401
    // =========================================================================

    @Test
    void createConversation_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "ONE_ON_ONE", "name": null, "memberIds": ["00000000-0000-0000-0000-000000000001"]}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"));
    }

    // =========================================================================
    // TD-8: @PathVariable UUID parse fail must return 400, not 500
    // =========================================================================

    /**
     * TD-8: Malformed UUID in path → MethodArgumentTypeMismatchException → 400 VALIDATION_FAILED.
     * Before fix: catch-all Exception handler returned 500 INTERNAL_ERROR.
     */
    @Test
    void getConversation_malformedUUID_returns400() throws Exception {
        String validToken = register("td8a@test.com", "td8_userA");

        mockMvc.perform(get("/api/conversations/not-a-uuid")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    /**
     * TD-8: Well-formed UUID that does not exist → 404 CONV_NOT_FOUND (anti-enumeration).
     * Verifies the 400 fix does not interfere with the normal 404 path.
     */
    @Test
    void getConversation_validUUIDNonExistent_returns404() throws Exception {
        String validToken = register("td8b@test.com", "td8_userB");

        mockMvc.perform(get("/api/conversations/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONV_NOT_FOUND"));
    }
}
