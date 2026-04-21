package com.chatapp.file;

import com.chatapp.conversation.entity.Conversation;
import com.chatapp.conversation.entity.ConversationMember;
import com.chatapp.conversation.enums.ConversationType;
import com.chatapp.conversation.enums.MemberRole;
import com.chatapp.conversation.repository.ConversationMemberRepository;
import com.chatapp.conversation.repository.ConversationRepository;
import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.entity.MessageAttachment;
import com.chatapp.file.entity.MessageAttachmentId;
import com.chatapp.file.repository.FileRecordRepository;
import com.chatapp.file.repository.MessageAttachmentRepository;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.user.entity.User;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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

    @Autowired
    private FileRecordRepository fileRecordRepository;

    @Autowired
    private MessageAttachmentRepository messageAttachmentRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private ConversationMemberRepository conversationMemberRepository;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private FirebaseAuth firebaseAuth;

    @MockBean
    private SimpMessagingTemplate simpMessagingTemplate;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    // Valid 10x10 JPEG bytes — generated via ImageIO so Thumbnailator có thể đọc.
    // Dùng cho các test cần trigger thumbnail generation thực sự.
    private static final byte[] JPEG_REAL = realJpegBytes(10, 10);

    // JPEG magic bytes only — dùng cho test nơi chỉ cần Tika detect (fail-open thumbnail OK)
    private static final byte[] JPEG_MAGIC = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
    };

    // PDF magic bytes
    private static final byte[] PDF_MAGIC = "%PDF-1.4\n%âãÏÓ\n".getBytes();

    private static byte[] realJpegBytes(int w, int h) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < h; y++) {
                    img.setRGB(x, y, 0xAA5533);
                }
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Không generate được JPEG test bytes", e);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        messageAttachmentRepository.deleteAll();
        messageRepository.deleteAll();
        conversationMemberRepository.deleteAll();
        conversationRepository.deleteAll();
        fileRecordRepository.deleteAll();
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
                "file", "photo.jpg", "image/jpeg", JPEG_REAL);

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.mime").value("image/jpeg"))
                .andExpect(jsonPath("$.name").value("photo.jpg"))
                .andExpect(jsonPath("$.size").value(JPEG_REAL.length))
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
    // F05: upload MIME không trong whitelist (EXE magic) → 415
    // =========================================================================

    @Test
    void upload_mimeNotInWhitelist_returns415() throws Exception {
        String token = registerAndGetToken("f05_bad_mime@test.com", "f05bmime");

        // EXE magic bytes (MZ) — application/x-msdownload, không trong whitelist
        byte[] exeBytes = new byte[]{0x4D, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile(
                "file", "virus.exe", "application/octet-stream", exeBytes);

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
                "file", "photo.jpg", "image/jpeg", JPEG_REAL);
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

    // =========================================================================
    // W6-D2 — VIỆC 1: Thumbnail generation + GET /thumb
    // =========================================================================

    /**
     * F11 (W6-D2): Upload JPEG → thumbnail_internal_path được set trong DB.
     */
    @Test
    void upload_jpeg_generatesThumbnailPathInDb() throws Exception {
        String token = registerAndGetToken("f11_thumb@test.com", "f11thumb");

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_REAL);
        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText();

        FileRecord record = fileRecordRepository.findById(UUID.fromString(fileId)).orElseThrow();
        assertNotNull(record.getThumbnailInternalPath(),
                "thumbnailInternalPath phải non-null sau upload JPEG thành công");
        assertTrue(record.getThumbnailInternalPath().contains("_thumb"),
                "Thumbnail path phải chứa '_thumb' suffix");
    }

    /**
     * F12 (W6-D2): Upload PDF → thumbnail_internal_path là null (PDF không generate thumbnail V1).
     */
    @Test
    void upload_pdf_thumbnailPathIsNull() throws Exception {
        String token = registerAndGetToken("f12_pdf@test.com", "f12pdf");

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", PDF_MAGIC);
        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText();

        FileRecord record = fileRecordRepository.findById(UUID.fromString(fileId)).orElseThrow();
        assertNull(record.getThumbnailInternalPath(),
                "thumbnailInternalPath phải null cho PDF");
    }

    /**
     * F13 (W6-D2): GET /thumb valid image → 200 + Content-Type image/*.
     */
    @Test
    void downloadThumb_validImage_returns200() throws Exception {
        String token = registerAndGetToken("f13_thumbdl@test.com", "f13thumbdl");

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_REAL);
        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/files/{id}/thumb", fileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("ETag", "\"" + fileId + "-thumb\""));
    }

    /**
     * F14 (W6-D2): GET /thumb cho PDF file → 404 (không có thumbnail).
     */
    @Test
    void downloadThumb_pdfFile_returns404() throws Exception {
        String token = registerAndGetToken("f14_thumbpdf@test.com", "f14thumbpdf");

        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", PDF_MAGIC);
        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/files/{id}/thumb", fileId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    /**
     * F15 (W6-D2): GET /thumb không có JWT → 401 AUTH_REQUIRED.
     */
    @Test
    void downloadThumb_noJwt_returns401() throws Exception {
        String fakeId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/files/{id}/thumb", fakeId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"));
    }

    // =========================================================================
    // W6-D2 — VIỆC 2: Download authorization via FileAuthService
    // =========================================================================

    /**
     * F16 (W6-D2): Conv-member download file được attach vào message trong conv → 200.
     */
    @Test
    void download_convMemberWithAttachment_returns200() throws Exception {
        String tokenA = registerAndGetToken("f16_a@test.com", "f16a");
        String tokenB = registerAndGetToken("f16_b@test.com", "f16b");

        User userA = userRepository.findByEmail("f16_a@test.com").orElseThrow();
        User userB = userRepository.findByEmail("f16_b@test.com").orElseThrow();

        // A uploads
        MockMultipartFile uploadFile = new MockMultipartFile(
                "file", "shared.jpg", "image/jpeg", JPEG_MAGIC);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(uploadFile)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(
                uploadResult.getResponse().getContentAsString()).get("id").asText();

        // Create conv A+B via repository (avoids REST rate limit / additional complexity)
        Conversation conv = conversationRepository.save(Conversation.builder()
                .type(ConversationType.ONE_ON_ONE)
                .createdBy(userA)
                .build());
        conversationMemberRepository.save(ConversationMember.builder()
                .conversation(conv).user(userA).role(MemberRole.OWNER).build());
        conversationMemberRepository.save(ConversationMember.builder()
                .conversation(conv).user(userB).role(MemberRole.MEMBER).build());

        // A sends a message with attachment (construct directly via repositories)
        Message msg = messageRepository.save(Message.builder()
                .conversation(conv).sender(userA).type(MessageType.IMAGE).content("photo")
                .build());
        messageAttachmentRepository.save(MessageAttachment.builder()
                .id(new MessageAttachmentId(msg.getId(), UUID.fromString(fileId)))
                .displayOrder((short) 0)
                .build());

        // B (member) should be able to download
        mockMvc.perform(get("/api/files/{id}", fileId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"));
    }

    /**
     * F17 (W6-D2): Non-member (không phải uploader, không phải member của conv
     * chứa attachment) → 404 NOT_FOUND.
     */
    @Test
    void download_nonMemberNonUploader_returns404() throws Exception {
        String tokenA = registerAndGetToken("f17_a@test.com", "f17a");
        String tokenB = registerAndGetToken("f17_b@test.com", "f17b");
        String tokenC = registerAndGetToken("f17_c@test.com", "f17c");

        User userA = userRepository.findByEmail("f17_a@test.com").orElseThrow();
        User userB = userRepository.findByEmail("f17_b@test.com").orElseThrow();

        // A uploads + attaches to conv(A,B)
        MockMultipartFile uploadFile = new MockMultipartFile(
                "file", "private.jpg", "image/jpeg", JPEG_MAGIC);
        MvcResult uploadResult = mockMvc.perform(multipart("/api/files/upload")
                        .file(uploadFile)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isCreated())
                .andReturn();
        String fileId = objectMapper.readTree(
                uploadResult.getResponse().getContentAsString()).get("id").asText();

        Conversation conv = conversationRepository.save(Conversation.builder()
                .type(ConversationType.ONE_ON_ONE).createdBy(userA).build());
        conversationMemberRepository.save(ConversationMember.builder()
                .conversation(conv).user(userA).role(MemberRole.OWNER).build());
        conversationMemberRepository.save(ConversationMember.builder()
                .conversation(conv).user(userB).role(MemberRole.MEMBER).build());

        Message msg = messageRepository.save(Message.builder()
                .conversation(conv).sender(userA).type(MessageType.IMAGE).content("photo").build());
        messageAttachmentRepository.save(MessageAttachment.builder()
                .id(new MessageAttachmentId(msg.getId(), UUID.fromString(fileId)))
                .displayOrder((short) 0)
                .build());

        // C không phải uploader, không phải member của conv chứa attachment → 404
        mockMvc.perform(get("/api/files/{id}", fileId)
                        .header("Authorization", "Bearer " + tokenC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    /**
     * F18 (W6-D2): File đã expire (expires_at < now) → 404 NOT_FOUND.
     */
    @Test
    void download_expiredFile_returns404() throws Exception {
        String token = registerAndGetToken("f18_exp@test.com", "f18exp");
        User user = userRepository.findByEmail("f18_exp@test.com").orElseThrow();

        // Create a FileRecord that's already expired (set expires_at in the past)
        FileRecord expired = fileRecordRepository.save(FileRecord.builder()
                .uploaderId(user.getId())
                .originalName("old.jpg")
                .mime("image/jpeg")
                .sizeBytes(100L)
                .storagePath("2020/01/fake.jpg")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(60))
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(30))
                .expired(false)
                .build());

        mockMvc.perform(get("/api/files/{id}", expired.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    // =========================================================================
    // W6-D4-extend — New file types + iconType tests
    // =========================================================================

    // Magic bytes
    // ZIP/DOCX/XLSX/PPTX magic: PK\x03\x04
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
    // TXT: plain text bytes
    private static final byte[] TXT_BYTES = "Hello World text content".getBytes();

    /**
     * F19 (W6-D4-extend): Upload DOCX (ZIP magic + .docx extension) → 201 + iconType=WORD.
     * Tika detect ZIP_MAGIC → application/zip → override via extension → docx MIME.
     */
    @Test
    void upload_docxFile_returns201WithIconTypeWord() throws Exception {
        String token = registerAndGetToken("f19_docx@test.com", "f19docx");

        MockMultipartFile file = new MockMultipartFile(
                "file", "document.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ZIP_MAGIC);

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iconType").value("WORD"))
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        // MIME stored phải là docx MIME (sau override)
        String mime = tree.get("mime").asText();
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", mime);
    }

    /**
     * F20 (W6-D4-extend): Upload TXT → 201 + iconType=TEXT.
     */
    @Test
    void upload_txtFile_returns201WithIconTypeText() throws Exception {
        String token = registerAndGetToken("f20_txt@test.com", "f20txt");

        MockMultipartFile file = new MockMultipartFile(
                "file", "readme.txt", "text/plain", TXT_BYTES);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iconType").value("TEXT"))
                .andExpect(jsonPath("$.mime").value("text/plain"));
    }

    /**
     * F21 (W6-D4-extend): Upload ZIP → 201 + iconType=ARCHIVE.
     */
    @Test
    void upload_zipFile_returns201WithIconTypeArchive() throws Exception {
        String token = registerAndGetToken("f21_zip@test.com", "f21zip");

        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.zip", "application/zip", ZIP_MAGIC);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iconType").value("ARCHIVE"))
                .andExpect(jsonPath("$.mime").value("application/zip"));
    }

    /**
     * F22 (W6-D4-extend): Upload file .exe → 400 FILE_TYPE_NOT_ALLOWED.
     * Bytes giả EXE magic: MZ = 0x4D 0x5A.
     */
    @Test
    void upload_exeFile_returns400FileTypeNotAllowed() throws Exception {
        String token = registerAndGetToken("f22_exe@test.com", "f22exe");

        byte[] exeMagic = {0x4D, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        MockMultipartFile file = new MockMultipartFile(
                "file", "virus.exe", "application/octet-stream", exeMagic);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("FILE_TYPE_NOT_ALLOWED"));
    }

    /**
     * F23 (W6-D4-extend): Upload JPEG → iconType=IMAGE.
     */
    @Test
    void upload_jpeg_iconTypeIsImage() throws Exception {
        String token = registerAndGetToken("f23_img@test.com", "f23img");

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_REAL);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iconType").value("IMAGE"));
    }

    /**
     * F24 (W6-D4-extend): Upload PDF → iconType=PDF.
     */
    @Test
    void upload_pdf_iconTypeIsPdf() throws Exception {
        String token = registerAndGetToken("f24_pdfico@test.com", "f24pdfico");

        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", PDF_MAGIC);

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.iconType").value("PDF"));
    }
}
