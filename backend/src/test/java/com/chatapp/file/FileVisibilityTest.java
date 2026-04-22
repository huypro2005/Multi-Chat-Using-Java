package com.chatapp.file;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.file.constant.FileConstants;
import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.file.repository.MessageAttachmentRepository;
import com.chatapp.file.scheduler.FileCleanupJob;
import com.chatapp.file.storage.StorageService;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.user.entity.User;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests cho W7-D4-fix — ADR-021 Hybrid File Visibility + Default Avatars + ADMIN permission.
 *
 * V01-V06: FileDto isPublic/publicUrl shape + upload `?public` param.
 * V07-V09: GET /api/files/{id}/public endpoint behavior.
 * D01-D03: Default avatars (user register + createGroup).
 * D04: FileCleanupJob skips default avatars.
 * P01-P02: ADMIN permission regression (canAddMembers).
 *
 * Tổng 12 tests.
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
class FileVisibilityTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserAuthProviderRepository userAuthProviderRepository;
    @Autowired private FileRecordRepository fileRecordRepository;
    @Autowired private MessageAttachmentRepository messageAttachmentRepository;
    @Autowired private MessageRepository messageRepository;
    @Autowired private ConversationRepository conversationRepository;
    @Autowired private ConversationMemberRepository conversationMemberRepository;
    @Autowired private FileCleanupJob fileCleanupJob;

    @MockBean private StringRedisTemplate redisTemplate;
    @MockBean private FirebaseAuth firebaseAuth;
    @MockBean private SimpMessagingTemplate simpMessagingTemplate;
    @MockBean private StorageService storageService;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    private static final byte[] JPEG_REAL = realJpegBytes();

    private static byte[] realJpegBytes() {
        try {
            BufferedImage img = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 10; x++) {
                for (int y = 0; y < 10; y++) {
                    img.setRGB(x, y, 0xAA5533);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // Clean DB
        messageAttachmentRepository.deleteAll();
        messageRepository.deleteAll();
        conversationMemberRepository.deleteAll();
        conversationRepository.deleteAll();
        fileRecordRepository.deleteAll();
        userAuthProviderRepository.deleteAll();
        userRepository.deleteAll();

        // Seed default avatar records (H2 test doesn't run V11 Flyway).
        // Needed for P01 default group avatar FK + download_public tests.
        seedDefaultAvatarIfMissing(FileConstants.DEFAULT_USER_AVATAR_ID,
                FileConstants.DEFAULT_USER_AVATAR_PATH);
        seedDefaultAvatarIfMissing(FileConstants.DEFAULT_GROUP_AVATAR_ID,
                FileConstants.DEFAULT_GROUP_AVATAR_PATH);

        // Redis mock (fail-open for rate limit)
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(60L);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(0L);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());

        // StorageService mock
        when(storageService.store(any(InputStream.class), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String fileId = inv.getArgument(1);
                    String ext = inv.getArgument(2);
                    return "2026/04/" + fileId + "." + ext;
                });
        when(storageService.retrieve(anyString()))
                .thenAnswer(inv -> new ByteArrayInputStream(JPEG_REAL));
    }

    private void seedDefaultAvatarIfMissing(UUID id, String path) {
        if (fileRecordRepository.findById(id).isEmpty()) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            FileRecord def = FileRecord.builder()
                    .id(id)
                    .uploaderId(null)
                    .originalName("default.jpg")
                    .mime("image/jpeg")
                    .sizeBytes(0L)
                    .storagePath(path)
                    .createdAt(now)
                    .expiresAt(OffsetDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC))
                    .expired(false)
                    .attachedAt(now)
                    .isPublic(true)
                    .build();
            fileRecordRepository.save(def);
        }
    }

    private String registerAndGetToken(String email, String username) throws Exception {
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

    private UUID userIdFromToken(String responseBody) throws Exception {
        return UUID.fromString(objectMapper.readTree(responseBody).get("user").get("id").asText());
    }

    // ==========================================================================
    // V01: upload ?public=true → isPublic=true, publicUrl set, thumbUrl null
    // ==========================================================================

    @Test
    void V01_uploadWithPublicTrue_returnsPublicUrlAndIsPublicTrue() throws Exception {
        String token = registerAndGetToken("v01@test.com", "v01u");

        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", JPEG_REAL);

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("public", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPublic").value(true))
                .andExpect(jsonPath("$.publicUrl").isNotEmpty())
                .andReturn();

        JsonNode tree = objectMapper.readTree(result.getResponse().getContentAsString());
        String id = tree.get("id").asText();
        assertEquals("/api/files/" + id + "/public", tree.get("url").asText());
        assertEquals("/api/files/" + id + "/public", tree.get("publicUrl").asText());

        // thumbUrl nên null cho public file (không expose thumbnail endpoint V1).
        assertTrue(tree.get("thumbUrl") == null || tree.get("thumbUrl").isNull(),
                "Public file should not expose thumbUrl");

        // DB verify is_public=true
        FileRecord rec = fileRecordRepository.findById(UUID.fromString(id)).orElseThrow();
        assertTrue(rec.isPublic());
    }

    // ==========================================================================
    // V02: upload default (no `public` param) → isPublic=false, publicUrl=null
    // ==========================================================================

    @Test
    void V02_uploadDefault_returnsPrivateAndPublicUrlNull() throws Exception {
        String token = registerAndGetToken("v02@test.com", "v02u");

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_REAL);

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPublic").value(false))
                .andReturn();

        JsonNode tree = objectMapper.readTree(result.getResponse().getContentAsString());
        String id = tree.get("id").asText();
        assertEquals("/api/files/" + id, tree.get("url").asText());
        assertTrue(tree.get("publicUrl") == null || tree.get("publicUrl").isNull(),
                "publicUrl phải null cho private file");

        FileRecord rec = fileRecordRepository.findById(UUID.fromString(id)).orElseThrow();
        assertFalse(rec.isPublic());
    }

    // ==========================================================================
    // V03: GET /api/files/{id}/public on public file → 200 OK, no auth required
    // ==========================================================================

    @Test
    void V03_downloadPublic_publicFile_returns200NoAuth() throws Exception {
        // Upload public file first
        String token = registerAndGetToken("v03@test.com", "v03u");
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", JPEG_REAL);
        MvcResult upload = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("public", "true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        // Download /public — NO auth header
        mockMvc.perform(get("/api/files/{id}/public", fileId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().exists("Content-Disposition"));
    }

    // ==========================================================================
    // V04: GET /api/files/{id}/public on private file → 404 (anti-enum)
    // ==========================================================================

    @Test
    void V04_downloadPublic_privateFile_returns404AntiEnum() throws Exception {
        String token = registerAndGetToken("v04@test.com", "v04u");
        MockMultipartFile file = new MockMultipartFile(
                "file", "secret.jpg", "image/jpeg", JPEG_REAL);
        // Upload private (default)
        MvcResult upload = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText();

        // GET /public trả 404 cho private file (anti-enum)
        mockMvc.perform(get("/api/files/{id}/public", fileId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ==========================================================================
    // V05: GET /api/files/{id}/public on non-existent → 404
    // ==========================================================================

    @Test
    void V05_downloadPublic_nonExistent_returns404() throws Exception {
        String fakeId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/files/{id}/public", fakeId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // ==========================================================================
    // V06: Public endpoint no auth required — verify SecurityConfig whitelist
    // ==========================================================================

    @Test
    void V06_publicEndpoint_noJwtRequired_notWhenPrivateEndpoint() throws Exception {
        // Default user avatar (seed) — is_public=true, no auth should work.
        mockMvc.perform(get("/api/files/{id}/public", FileConstants.DEFAULT_USER_AVATAR_ID))
                .andExpect(status().isOk());

        // Private endpoint requires JWT — no token → 401
        mockMvc.perform(get("/api/files/{id}", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized());
    }

    // ==========================================================================
    // D01: User register → avatarUrl = DEFAULT_USER_AVATAR_URL
    // ==========================================================================

    @Test
    void D01_userRegister_setsDefaultUserAvatarUrl() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"d01@test.com","username":"d01u","password":"Pass123!A","fullName":"Test D01"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.avatarUrl").value(FileConstants.DEFAULT_USER_AVATAR_URL))
                .andReturn();

        UUID uid = userIdFromToken(result.getResponse().getContentAsString());
        User u = userRepository.findById(uid).orElseThrow();
        assertEquals(FileConstants.DEFAULT_USER_AVATAR_URL, u.getAvatarUrl());
    }

    // ==========================================================================
    // D02: createGroup without avatarFileId → avatarFileId = DEFAULT_GROUP_AVATAR_ID
    // ==========================================================================

    @Test
    void D02_createGroupWithoutAvatar_usesDefault() throws Exception {
        String tokenA = registerAndGetToken("d02a@test.com", "d02a");
        registerAndGetToken("d02b@test.com", "d02b");
        registerAndGetToken("d02c@test.com", "d02c");

        UUID bId = userRepository.findByUsername("d02b").orElseThrow().getId();
        UUID cId = userRepository.findByUsername("d02c").orElseThrow().getId();

        MvcResult result = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"D02 Group","memberIds":["%s","%s"]}
                                """.formatted(bId, cId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.avatarUrl").value(FileConstants.DEFAULT_GROUP_AVATAR_URL))
                .andReturn();

        UUID convId = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText());
        Conversation conv = conversationRepository.findById(convId).orElseThrow();
        assertEquals(FileConstants.DEFAULT_GROUP_AVATAR_ID, conv.getAvatarFileId());
    }

    // ==========================================================================
    // D03: createGroup with custom avatarFileId → uses custom (not default)
    // ==========================================================================

    @Test
    void D03_createGroupWithCustomAvatar_doesNotUseDefault() throws Exception {
        String tokenA = registerAndGetToken("d03a@test.com", "d03a");
        registerAndGetToken("d03b@test.com", "d03b");
        registerAndGetToken("d03c@test.com", "d03c");

        UUID aId = userRepository.findByUsername("d03a").orElseThrow().getId();
        UUID bId = userRepository.findByUsername("d03b").orElseThrow().getId();
        UUID cId = userRepository.findByUsername("d03c").orElseThrow().getId();

        // Upload custom avatar (public=true, by userA)
        MockMultipartFile file = new MockMultipartFile(
                "file", "custom.jpg", "image/jpeg", JPEG_REAL);
        MvcResult upload = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("public", "true")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andReturn();
        UUID customAvatarId = UUID.fromString(
                objectMapper.readTree(upload.getResponse().getContentAsString()).get("id").asText());

        // Create group with custom avatar
        MvcResult groupResult = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"D03 Group","memberIds":["%s","%s"],"avatarFileId":"%s"}
                                """.formatted(bId, cId, customAvatarId)))
                .andExpect(status().isCreated())
                .andReturn();

        UUID convId = UUID.fromString(
                objectMapper.readTree(groupResult.getResponse().getContentAsString()).get("id").asText());
        Conversation conv = conversationRepository.findById(convId).orElseThrow();
        assertEquals(customAvatarId, conv.getAvatarFileId(),
                "Custom avatar should be used, not default");
        assertNotEquals(FileConstants.DEFAULT_GROUP_AVATAR_ID, conv.getAvatarFileId());
    }

    // ==========================================================================
    // D04: FileCleanupJob skips default avatar files
    // ==========================================================================

    @Test
    void D04_cleanupJob_skipsDefaultAvatars() throws Exception {
        // Force default avatar to look "expired" (simulate corrupted data or mistake).
        // Even so, cleanup should skip because of hardcoded ID guard.
        FileRecord defaultUser = fileRecordRepository.findById(FileConstants.DEFAULT_USER_AVATAR_ID).orElseThrow();
        defaultUser.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        defaultUser.setExpired(false); // not yet cleaned
        fileRecordRepository.save(defaultUser);

        FileRecord defaultGroup = fileRecordRepository.findById(FileConstants.DEFAULT_GROUP_AVATAR_ID).orElseThrow();
        defaultGroup.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(1));
        fileRecordRepository.save(defaultGroup);

        // Run expired cleanup
        fileCleanupJob.cleanupExpiredFiles();

        // Defaults MUST still exist (skip guard in job)
        assertTrue(fileRecordRepository.findById(FileConstants.DEFAULT_USER_AVATAR_ID).isPresent(),
                "DEFAULT_USER_AVATAR must not be deleted by cleanup");
        assertTrue(fileRecordRepository.findById(FileConstants.DEFAULT_GROUP_AVATAR_ID).isPresent(),
                "DEFAULT_GROUP_AVATAR must not be deleted by cleanup");

        // Verify StorageService.delete NEVER called with default path
        verify(storageService, never()).delete(FileConstants.DEFAULT_USER_AVATAR_PATH);
        verify(storageService, never()).delete(FileConstants.DEFAULT_GROUP_AVATAR_PATH);
    }

    // ==========================================================================
    // P01: ADMIN can add members (regression for canAddMembers()=true for ADMIN)
    // ==========================================================================

    @Test
    void P01_adminCanAddMembers_regression() throws Exception {
        String tokenA = registerAndGetToken("p01a@test.com", "p01a");
        registerAndGetToken("p01b@test.com", "p01b");
        registerAndGetToken("p01c@test.com", "p01c");
        registerAndGetToken("p01d@test.com", "p01d");

        UUID bId = userRepository.findByUsername("p01b").orElseThrow().getId();
        UUID cId = userRepository.findByUsername("p01c").orElseThrow().getId();
        UUID dId = userRepository.findByUsername("p01d").orElseThrow().getId();

        // A creates group with B, C
        MvcResult createResult = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"P01 Group","memberIds":["%s","%s"]}
                                """.formatted(bId, cId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID convId = UUID.fromString(
                objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

        // Promote B to ADMIN (direct DB mutation — bypass endpoint since this tests B's permission, not A's)
        ConversationMember bMember = conversationMemberRepository
                .findByConversation_IdAndUser_Id(convId, bId).orElseThrow();
        bMember.setRole(MemberRole.ADMIN);
        conversationMemberRepository.saveAndFlush(bMember);

        // Login as B and verify via JWT extraction
        MvcResult loginB = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"p01b","password":"Pass123!A"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String tokenB = objectMapper.readTree(loginB.getResponse().getContentAsString())
                .get("accessToken").asText();

        // B (ADMIN) adds D → should succeed (regression: canAddMembers=true for ADMIN)
        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s"]}
                                """.formatted(dId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.added.length()").value(1))
                .andExpect(jsonPath("$.added[0].role").value("MEMBER"));
    }

    // ==========================================================================
    // P02: MEMBER cannot add members (regression)
    // ==========================================================================

    @Test
    void P02_memberCannotAddMembers_regression() throws Exception {
        String tokenA = registerAndGetToken("p02a@test.com", "p02a");
        registerAndGetToken("p02b@test.com", "p02b");
        registerAndGetToken("p02c@test.com", "p02c");
        registerAndGetToken("p02d@test.com", "p02d");

        UUID bId = userRepository.findByUsername("p02b").orElseThrow().getId();
        UUID cId = userRepository.findByUsername("p02c").orElseThrow().getId();
        UUID dId = userRepository.findByUsername("p02d").orElseThrow().getId();

        MvcResult createResult = mockMvc.perform(post("/api/conversations")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"GROUP","name":"P02 Group","memberIds":["%s","%s"]}
                                """.formatted(bId, cId)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID convId = UUID.fromString(
                objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText());

        // B stays MEMBER (default)
        MvcResult loginB = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"p02b","password":"Pass123!A"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String tokenB = objectMapper.readTree(loginB.getResponse().getContentAsString())
                .get("accessToken").asText();

        // B (MEMBER) tries to add D → 403 INSUFFICIENT_PERMISSION
        mockMvc.perform(post("/api/conversations/" + convId + "/members")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userIds":["%s"]}
                                """.formatted(dId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_PERMISSION"));
    }
}
