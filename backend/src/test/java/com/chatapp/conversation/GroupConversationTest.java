package com.chatapp.conversation;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.user.repository.UserAuthProviderRepository;
import com.chatapp.user.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for W7-D1: Group Chat Schema V9 + CRUD endpoints.
 *
 * Schema tests (3):
 *  S01: V9 schema → conversations has owner_id / avatar_file_id / deleted_at columns
 *  S02: V9 schema → conversation_members has role and joined_at columns (V3 already created)
 *  S03: GROUP created via service has owner_id + name non-null (CHECK invariant enforced at Java layer)
 *
 * createGroup tests (9):
 *  G01: happy path — creator + 2 others → 201, creator=OWNER, others=MEMBER, name set
 *  G02: exactly 3 members total (min valid)
 *  G03: only 1 other member → GROUP_MEMBERS_MIN
 *  G04: 50 other members (total 51) → GROUP_MEMBERS_MAX
 *  G05: duplicate memberIds deduped still valid if ≥ 2 unique
 *  G06: invalid memberId (not exist) → GROUP_MEMBER_NOT_FOUND
 *  G07: name empty string → GROUP_NAME_REQUIRED
 *  G08: name > 100 chars → VALIDATION_FAILED
 *  G09: avatar file not owned by creator → GROUP_AVATAR_NOT_OWNED
 *
 * getConversation tests (2):
 *  H01: GET group → response includes owner + members sorted (OWNER first) + role/joinedAt
 *  H02: GET group user not member → 404 (anti-enum)
 *
 * updateGroupInfo tests (4):
 *  U01: OWNER rename → 200, broadcast CONVERSATION_UPDATED
 *  U02: ADMIN rename → 200 success (ADMIN has permission)
 *  U03: MEMBER rename → INSUFFICIENT_PERMISSION
 *  U04: Rename ONE_ON_ONE → NOT_GROUP
 *
 * deleteGroup tests (3):
 *  D01: OWNER delete → 204, soft-deleted (deleted_at), broadcast GROUP_DELETED, members cleared
 *  D02: ADMIN delete → INSUFFICIENT_PERMISSION
 *  D03: Delete ONE_ON_ONE → NOT_GROUP
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
class GroupConversationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserAuthProviderRepository userAuthProviderRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationMemberRepository conversationMemberRepository;
    @Autowired private FileRecordRepository fileRecordRepository;
    @Autowired private DataSource dataSource;

    @MockBean private StringRedisTemplate redisTemplate;
    @MockBean private FirebaseAuth firebaseAuth;
    @MockBean private SimpMessagingTemplate messagingTemplate;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        conversationMemberRepository.deleteAll();
        conversationRepository.deleteAll();
        fileRecordRepository.deleteAll();
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

    // ==========================================================================
    // Helpers
    // ==========================================================================

    private String register(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","password":"Pass123!A","fullName":"Test %s"}
                                """.formatted(email, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    private UUID registerAndGetUserId(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","password":"Pass123!A","fullName":"Test %s"}
                                """.formatted(email, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("user").get("id").asText());
    }

    private record RegisterResult(String token, UUID userId) {}

    private RegisterResult registerFull(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","username":"%s","password":"Pass123!A","fullName":"Test %s"}
                                """.formatted(email, username, username)))
                .andExpect(status().isOk())
                .andReturn();
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        return new RegisterResult(
                tree.get("accessToken").asText(),
                UUID.fromString(tree.get("user").get("id").asText())
        );
    }

    /** Create a group with caller=creator + N other members. Return group UUID. */
    private UUID createGroupWithMembers(String creatorToken, String name, List<UUID> memberIds) throws Exception {
        String idsJson = memberIds.stream()
                .map(id -> "\"" + id + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + creatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"%s","memberIds":[%s]}
                                """.formatted(name, idsJson)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("id").asText());
    }

    /** Insert a FileRecord directly (for avatar test). */
    private FileRecord createFileRecord(UUID uploaderId, String mime, boolean expired) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        FileRecord file = FileRecord.builder()
                .uploaderId(uploaderId)
                .originalName("avatar.jpg")
                .mime(mime)
                .sizeBytes(1024L)
                .storagePath("2026/04/" + UUID.randomUUID() + ".jpg")
                .expiresAt(now.plusDays(30))
                .expired(expired)
                .build();
        // Need to set createdAt via @PrePersist path; use repo.save
        return fileRecordRepository.save(file);
    }

    // ==========================================================================
    // S01-S03: Schema verification
    // ==========================================================================

    @Test
    void S01_schema_conversations_hasW7Columns() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            // H2 uppercases unquoted identifiers — check case-insensitive
            boolean hasOwnerId = columnExists(meta, "CONVERSATIONS", "OWNER_ID")
                    || columnExists(meta, "conversations", "owner_id");
            boolean hasAvatarFileId = columnExists(meta, "CONVERSATIONS", "AVATAR_FILE_ID")
                    || columnExists(meta, "conversations", "avatar_file_id");
            boolean hasDeletedAt = columnExists(meta, "CONVERSATIONS", "DELETED_AT")
                    || columnExists(meta, "conversations", "deleted_at");
            assertTrue(hasOwnerId, "conversations.owner_id must exist");
            assertTrue(hasAvatarFileId, "conversations.avatar_file_id must exist");
            assertTrue(hasDeletedAt, "conversations.deleted_at must exist");
        }
    }

    @Test
    void S02_schema_conversationMembers_hasRoleAndJoinedAt() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            boolean hasRole = columnExists(meta, "CONVERSATION_MEMBERS", "ROLE")
                    || columnExists(meta, "conversation_members", "role");
            boolean hasJoinedAt = columnExists(meta, "CONVERSATION_MEMBERS", "JOINED_AT")
                    || columnExists(meta, "conversation_members", "joined_at");
            assertTrue(hasRole, "conversation_members.role must exist");
            assertTrue(hasJoinedAt, "conversation_members.joined_at must exist");
        }
    }

    private boolean columnExists(DatabaseMetaData meta, String table, String column) throws Exception {
        try (ResultSet rs = meta.getColumns(null, null, table, column)) {
            return rs.next();
        }
    }

    @Test
    void S03_createGroup_persistsOwnerIdAndName() throws Exception {
        RegisterResult a = registerFull("s03a@test.com", "s03a");
        UUID bId = registerAndGetUserId("s03b@test.com", "s03b");
        UUID cId = registerAndGetUserId("s03c@test.com", "s03c");
        UUID convId = createGroupWithMembers(a.token(), "S03 Group", List.of(bId, cId));

        Conversation conv = conversationRepository.findById(convId).orElseThrow();
        assertNotNull(conv.getName(), "GROUP must have name");
        assertEquals("S03 Group", conv.getName());
        assertNotNull(conv.getOwnerId(), "GROUP must have owner_id");
        assertEquals(a.userId(), conv.getOwnerId());
        assertEquals(ConversationType.GROUP, conv.getType());
        assertNull(conv.getDeletedAt(), "Fresh group not deleted");
    }

    // ==========================================================================
    // G01-G09: createGroup tests
    // ==========================================================================

    @Test
    void G01_createGroup_happyPath_creatorOwnerOthersMember() throws Exception {
        RegisterResult a = registerFull("g01a@test.com", "g01a");
        UUID bId = registerAndGetUserId("g01b@test.com", "g01b");
        UUID cId = registerAndGetUserId("g01c@test.com", "g01c");

        MvcResult res = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"G01 Group","memberIds":["%s","%s"]}
                                """.formatted(bId, cId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.name").value("G01 Group"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.owner").isNotEmpty())
                .andExpect(jsonPath("$.owner.userId").value(a.userId().toString()))
                .andReturn();

        // Verify first member is OWNER (creator), sort role ASC ordinal = OWNER first
        JsonNode members = objectMapper.readTree(res.getResponse().getContentAsString()).get("members");
        assertEquals("OWNER", members.get(0).get("role").asText(),
                "First member in sorted list must be OWNER");
        assertEquals(a.userId().toString(), members.get(0).get("userId").asText());
        // The other two must be MEMBER
        assertEquals("MEMBER", members.get(1).get("role").asText());
        assertEquals("MEMBER", members.get(2).get("role").asText());
    }

    @Test
    void G02_createGroup_exactly3MembersTotal_succeeds() throws Exception {
        String tokenA = register("g02a@test.com", "g02a");
        UUID bId = registerAndGetUserId("g02b@test.com", "g02b");
        UUID cId = registerAndGetUserId("g02c@test.com", "g02c");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"Min Group","memberIds":["%s","%s"]}
                                """.formatted(bId, cId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members.length()").value(3));
    }

    @Test
    void G03_createGroup_only1OtherMember_returnsGroupMembersMin() throws Exception {
        String tokenA = register("g03a@test.com", "g03a");
        UUID bId = registerAndGetUserId("g03b@test.com", "g03b");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"Too Small","memberIds":["%s"]}
                                """.formatted(bId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("GROUP_MEMBERS_MIN"));
    }

    @Test
    void G04_createGroup_tooManyMembers_returnsGroupMembersMax() throws Exception {
        String tokenA = register("g04a@test.com", "g04a");

        // Build 50 member IDs (total 51 including caller > 50 max)
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            UUID id = registerAndGetUserId("g04_" + i + "@test.com", "g04_" + i);
            if (i > 0) ids.append(",");
            ids.append("\"").append(id).append("\"");
        }

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"Too Big","memberIds":[%s]}
                                """.formatted(ids.toString())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("GROUP_MEMBERS_MAX"));
    }

    @Test
    void G05_createGroup_duplicateMemberIdsDeduped_succeedsIfEnoughUnique() throws Exception {
        String tokenA = register("g05a@test.com", "g05a");
        UUID bId = registerAndGetUserId("g05b@test.com", "g05b");
        UUID cId = registerAndGetUserId("g05c@test.com", "g05c");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"Dedup","memberIds":["%s","%s","%s"]}
                                """.formatted(bId, cId, bId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.members.length()").value(3));
    }

    @Test
    void G06_createGroup_invalidMemberId_returnsGroupMemberNotFound() throws Exception {
        String tokenA = register("g06a@test.com", "g06a");
        UUID bId = registerAndGetUserId("g06b@test.com", "g06b");
        UUID fakeId = UUID.fromString("00000000-0000-0000-0000-000000000099");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"Missing","memberIds":["%s","%s"]}
                                """.formatted(bId, fakeId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("GROUP_MEMBER_NOT_FOUND"))
                .andExpect(jsonPath("$.details.missingIds[0]").value(fakeId.toString()));
    }

    @Test
    void G07_createGroup_emptyName_returnsGroupNameRequired() throws Exception {
        String tokenA = register("g07a@test.com", "g07a");
        UUID bId = registerAndGetUserId("g07b@test.com", "g07b");
        UUID cId = registerAndGetUserId("g07c@test.com", "g07c");

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"   ","memberIds":["%s","%s"]}
                                """.formatted(bId, cId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("GROUP_NAME_REQUIRED"));
    }

    @Test
    void G08_createGroup_nameTooLong_returnsValidationFailed() throws Exception {
        String tokenA = register("g08a@test.com", "g08a");
        UUID bId = registerAndGetUserId("g08b@test.com", "g08b");
        UUID cId = registerAndGetUserId("g08c@test.com", "g08c");

        String longName = "a".repeat(101);

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"%s","memberIds":["%s","%s"]}
                                """.formatted(longName, bId, cId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void G09_createGroup_avatarNotOwnedByCreator_returnsGroupAvatarNotOwned() throws Exception {
        RegisterResult a = registerFull("g09a@test.com", "g09a");
        RegisterResult b = registerFull("g09b@test.com", "g09b");
        UUID cId = registerAndGetUserId("g09c@test.com", "g09c");

        // B uploads a file; A tries to use it as group avatar
        FileRecord file = createFileRecord(b.userId(), "image/jpeg", false);

        mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"NotOwned","memberIds":["%s","%s"],"avatarFileId":"%s"}
                                """.formatted(b.userId(), cId, file.getId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("GROUP_AVATAR_NOT_OWNED"));
    }

    // ==========================================================================
    // H01-H02: GET conversation
    // ==========================================================================

    @Test
    void H01_getConversation_groupIncludesOwnerAndSortedMembers() throws Exception {
        RegisterResult a = registerFull("h01a@test.com", "h01a");
        UUID bId = registerAndGetUserId("h01b@test.com", "h01b");
        UUID cId = registerAndGetUserId("h01c@test.com", "h01c");
        UUID convId = createGroupWithMembers(a.token(), "H01 Group", List.of(bId, cId));

        mockMvc.perform(get("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("GROUP"))
                .andExpect(jsonPath("$.name").value("H01 Group"))
                .andExpect(jsonPath("$.owner").isNotEmpty())
                .andExpect(jsonPath("$.owner.userId").value(a.userId().toString()))
                .andExpect(jsonPath("$.owner.fullName").isNotEmpty())
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[0].role").value("OWNER"))
                .andExpect(jsonPath("$.members[0].joinedAt").isNotEmpty())
                .andExpect(jsonPath("$.members[1].role").value("MEMBER"))
                .andExpect(jsonPath("$.members[2].role").value("MEMBER"));
    }

    @Test
    void H02_getConversation_groupNotMember_returns404() throws Exception {
        RegisterResult a = registerFull("h02a@test.com", "h02a");
        UUID bId = registerAndGetUserId("h02b@test.com", "h02b");
        UUID cId = registerAndGetUserId("h02c@test.com", "h02c");
        UUID convId = createGroupWithMembers(a.token(), "H02 Group", List.of(bId, cId));

        String outsiderToken = register("h02x@test.com", "h02x");
        mockMvc.perform(get("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONV_NOT_FOUND"));
    }

    // ==========================================================================
    // U01-U04: updateGroupInfo tests
    // ==========================================================================

    @Test
    void U01_updateGroupInfo_ownerRename_succeedsAndBroadcasts() throws Exception {
        RegisterResult a = registerFull("u01a@test.com", "u01a");
        UUID bId = registerAndGetUserId("u01b@test.com", "u01b");
        UUID cId = registerAndGetUserId("u01c@test.com", "u01c");
        UUID convId = createGroupWithMembers(a.token(), "Old Name", List.of(bId, cId));

        mockMvc.perform(patch("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"New Name"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("New Name"));

        // Verify DB state
        Conversation updated = conversationRepository.findById(convId).orElseThrow();
        assertEquals("New Name", updated.getName());

        // Verify broadcast via messagingTemplate mock
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/conv." + convId),
                any(Object.class)
        );
    }

    @Test
    void U02_updateGroupInfo_adminRename_succeeds() throws Exception {
        RegisterResult a = registerFull("u02a@test.com", "u02a");
        RegisterResult b = registerFull("u02b@test.com", "u02b");
        UUID cId = registerAndGetUserId("u02c@test.com", "u02c");
        UUID convId = createGroupWithMembers(a.token(), "U02 Group", List.of(b.userId(), cId));

        // Promote B to ADMIN via direct repository update
        ConversationMember bMember = conversationMemberRepository
                .findByConversation_IdAndUser_Id(convId, b.userId())
                .orElseThrow();
        bMember.setRole(MemberRole.ADMIN);
        conversationMemberRepository.saveAndFlush(bMember);

        mockMvc.perform(patch("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + b.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Admin Renamed"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Admin Renamed"));
    }

    @Test
    void U03_updateGroupInfo_memberRename_returnsInsufficientPermission() throws Exception {
        RegisterResult a = registerFull("u03a@test.com", "u03a");
        RegisterResult b = registerFull("u03b@test.com", "u03b");
        UUID cId = registerAndGetUserId("u03c@test.com", "u03c");
        UUID convId = createGroupWithMembers(a.token(), "U03 Group", List.of(b.userId(), cId));

        // B is default MEMBER
        mockMvc.perform(patch("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + b.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Not Allowed"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSION"));
    }

    @Test
    void U04_updateGroupInfo_renameOneOnOne_returnsNotGroup() throws Exception {
        RegisterResult a = registerFull("u04a@test.com", "u04a");
        UUID bId = registerAndGetUserId("u04b@test.com", "u04b");

        MvcResult createResult = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"ONE_ON_ONE","memberIds":["%s"]}
                                """.formatted(bId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID convId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText());

        mockMvc.perform(patch("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renaming Direct"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOT_GROUP"));
    }

    // ==========================================================================
    // D01-D03: deleteGroup tests
    // ==========================================================================

    @Test
    void D01_deleteGroup_ownerDelete_softDeletesAndBroadcasts() throws Exception {
        RegisterResult a = registerFull("d01a@test.com", "d01a");
        UUID bId = registerAndGetUserId("d01b@test.com", "d01b");
        UUID cId = registerAndGetUserId("d01c@test.com", "d01c");
        UUID convId = createGroupWithMembers(a.token(), "D01 Group", List.of(bId, cId));

        mockMvc.perform(delete("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isNoContent());

        // Verify soft-delete
        Conversation deleted = conversationRepository.findById(convId).orElseThrow();
        assertNotNull(deleted.getDeletedAt(), "deleted_at must be set");
        assertNull(deleted.getAvatarFileId(), "avatar must be detached");

        // Verify members cleared
        assertEquals(0, conversationMemberRepository.countByConversation_Id(convId),
                "All members hard-deleted");

        // Verify broadcast GROUP_DELETED via messagingTemplate mock
        verify(messagingTemplate, atLeastOnce()).convertAndSend(
                eq("/topic/conv." + convId),
                any(Object.class)
        );

        // Verify 404 when trying to re-GET deleted group
        mockMvc.perform(get("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CONV_NOT_FOUND"));
    }

    @Test
    void D02_deleteGroup_adminDelete_returnsInsufficientPermission() throws Exception {
        RegisterResult a = registerFull("d02a@test.com", "d02a");
        RegisterResult b = registerFull("d02b@test.com", "d02b");
        UUID cId = registerAndGetUserId("d02c@test.com", "d02c");
        UUID convId = createGroupWithMembers(a.token(), "D02 Group", List.of(b.userId(), cId));

        // Promote B to ADMIN
        ConversationMember bMember = conversationMemberRepository
                .findByConversation_IdAndUser_Id(convId, b.userId())
                .orElseThrow();
        bMember.setRole(MemberRole.ADMIN);
        conversationMemberRepository.saveAndFlush(bMember);

        mockMvc.perform(delete("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSION"));

        // Group must still exist
        Conversation conv = conversationRepository.findById(convId).orElseThrow();
        assertNull(conv.getDeletedAt(), "Group must NOT be deleted by ADMIN");
    }

    @Test
    void D03_deleteGroup_oneOnOne_returnsNotGroup() throws Exception {
        RegisterResult a = registerFull("d03a@test.com", "d03a");
        UUID bId = registerAndGetUserId("d03b@test.com", "d03b");

        MvcResult createResult = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"ONE_ON_ONE","memberIds":["%s"]}
                                """.formatted(bId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID convId = UUID.fromString(objectMapper.readTree(createResult.getResponse().getContentAsString())
                .get("id").asText());

        mockMvc.perform(delete("/api/conversations/" + convId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("NOT_GROUP"));
    }
}
