package com.chatapp.file;

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
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests cho File REST endpoints (W6-D1).
 *
 *  F01: upload valid JPEG → 201 + FileDto (id, url, thumbUrl, expiresAt)
 *  F02: upload valid PDF → 201 + FileDto, thumbUrl = null
 *  F03: upload missing file param → 400 FILE_EMPTY
 *  F04: upload empty file bytes → 400 FILE_EMPTY
 *  F05: upload MIME không whitelist (text/plain) → 415 FILE_TYPE_NOT_ALLOWED
 *  F06: upload không có JWT → 401 AUTH_REQUIRED
 *  F07: upload rate limit exceeded (>20/min) → 429 RATE_LIMITED
 *  F08: download file bằng uploader → 200 với đúng Content-Type + Content-Disposition
 *  F09: download file không tồn tại → 404 NOT_FOUND
 *  F10: download file của người khác → 404 NOT_FOUND (anti-enumeration)
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
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAuthProviderRepository userAuthProviderRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private FirebaseAuth firebaseAuth;

    @MockBean
    private SimpMessagingTemplate simpMessagingTemplate;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    // JPEG magic bytes: FF D8 FF E0 + JFIF
    private static final byte[] JPEG_MAGIC = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
    };

    // PDF magic bytes
    private static final byte[] PDF_MAGIC = "%PDF-1.4\n%âãÏÓ\n".getBytes();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        userAuthProviderRepository.deleteAll();
        userRepository.deleteAll();

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

    // =========================================================================
    // F01: upload valid JPEG
    // =========================================================================

    @Test
    void upload_validJpeg_returns201WithFileDto() throws Exception {
        String token = registerAndGetToken("f01_upload@test.com", "f01upload");

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_MAGIC);

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.mime").value("image/jpeg"))
                .andExpect(jsonPath("$.name").value("photo.jpg"))
                .andExpect(jsonPath("$.size").value(JPEG_MAGIC.length))
                .andExpect(jsonPath("$.url").isNotEmpty())
                .andExpect(jsonPath("$.thumbUrl").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();

        // url format: /api/files/{id}
        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        String id = tree.get("id").asText();
        assertEquals("/api/files/" + id, tree.get("url").asText());
        assertEquals("/api/files/" + id + "/thumb", tree.get("thumbUrl").asText());
    }

    // =========================================================================
    // F02: upload valid PDF → thumbUrl null
    // =========================================================================

    @Test
    void upload_validPdf_returns201WithNullThumbUrl() throws Exception {
        String token = registerAndGetToken("f02_pdf@test.com", "f02pdf");

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", PDF_MAGIC);

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mime").value("application/pdf"))
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        // thumbUrl null/absent cho PDF
        assertTrue(tree.get("thumbUrl") == null || tree.get("thumbUrl").isNull(),
                "thumbUrl phải null cho PDF");
    }

    // =========================================================================
    // F03: upload không có field file → 400 FILE_EMPTY
    // =========================================================================

    @Test
    void upload_missingFileParam_returns400FileEmpty() throws Exception {
        String token = registerAndGetToken("f03_missing@test.com", "f03missing");

        // multipart request nhưng KHÔNG có part "file"
        mockMvc.perform(multipart("/api/files/upload")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("FILE_EMPTY"));
    }

    // =========================================================================
    // F04: upload file rỗng (0 bytes) → 400 FILE_EMPTY
    // =========================================================================

    @Test
    void upload_emptyFileBytes_returns400FileEmpty() throws Exception {
        String token = registerAndGetToken("f04_empty@test.com", "f04empty");

        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.jpg", "image/jpeg", new byte[0]);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("FILE_EMPTY"));
    }

    // =========================================================================
    // F05: upload MIME không trong whitelist (text/plain) → 415
    // =========================================================================

    @Test
    void upload_mimeNotInWhitelist_returns415() throws Exception {
        String token = registerAndGetToken("f05_bad_mime@test.com", "f05bmime");

        byte[] textContent = "This is plain text content.".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", textContent);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("FILE_TYPE_NOT_ALLOWED"))
                .andExpect(jsonPath("$.details.allowedMimes").exists());
    }

    // =========================================================================
    // F06: upload không có JWT → 401 AUTH_REQUIRED
    // =========================================================================

    @Test
    void upload_noJwt_returns401() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_MAGIC);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"));
    }

    // =========================================================================
    // F07: upload rate limit exceeded → 429 RATE_LIMITED
    // =========================================================================

    @Test
    void upload_rateLimitExceeded_returns429() throws Exception {
        String token = registerAndGetToken("f07_rl@test.com", "f07rl");

        // Mock Redis INCR trả > 20
        when(valueOps.increment(startsWith("rate:file-upload:"))).thenReturn(21L);

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_MAGIC);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.details.retryAfterSeconds").exists());
    }

    // =========================================================================
    // F08: download file bằng uploader → 200
    // =========================================================================

    @Test
    void download_byUploader_returns200WithContent() throws Exception {
        String token = registerAndGetToken("f08_dl@test.com", "f08dl");

        // Upload first
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_MAGIC);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(
                uploadResult.getResponse().getContentAsString()).get("id").asText();

        // Download
        mockMvc.perform(get("/api/files/{id}", fileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().exists("Content-Disposition"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    // =========================================================================
    // F09: download file không tồn tại → 404
    // =========================================================================

    @Test
    void download_nonExistent_returns404() throws Exception {
        String token = registerAndGetToken("f09_404@test.com", "f09_404");

        String fakeId = UUID.randomUUID().toString();

        mockMvc.perform(get("/api/files/{id}", fakeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // =========================================================================
    // F10: download file của user khác → 404 (anti-enumeration)
    // =========================================================================

    @Test
    void download_byNonUploader_returns404AntiEnumeration() throws Exception {
        String tokenA = registerAndGetToken("f10_a@test.com", "f10a");
        String tokenB = registerAndGetToken("f10_b@test.com", "f10b");

        // A uploads
        MockMultipartFile file = new MockMultipartFile(
                "file", "secret.jpg", "image/jpeg", JPEG_MAGIC);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(
                uploadResult.getResponse().getContentAsString()).get("id").asText();

        // B tries to download A's file → 404 (anti-enumeration: không leak 403)
        mockMvc.perform(get("/api/files/{id}", fileId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }
}
