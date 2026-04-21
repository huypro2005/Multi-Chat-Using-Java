package com.chatapp.file.scheduler;

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
import com.chatapp.file.storage.StorageService;
import com.chatapp.message.entity.Message;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.user.entity.User;
import com.chatapp.user.repository.UserAuthProviderRepository;
import com.chatapp.user.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration tests cho FileCleanupJob (W6-D3).
 *
 * Pattern:
 * - @SpringBootTest để load full context (cần repository beans thật).
 * - @MockBean StorageService để tránh disk I/O thật.
 * - @MockBean StringRedisTemplate vì FileService inject Redis (context load fail nếu thiếu).
 * - Redis auto-configuration excluded (consistent với FileControllerTest).
 * - Cron triggers disabled qua application-test.yml (expired-cron/orphan-cron = "-").
 * - JdbcTemplate để override timestamps sau save (vì @PrePersist set createdAt=now()).
 *
 * Test cases:
 *  CJ01: cleanupExpiredFiles — file hết hạn, không còn attachment → xóa DB
 *  CJ02: cleanupExpiredFiles — file chưa hết hạn → không xóa
 *  CJ03: cleanupExpiredFiles — file hết hạn nhưng vẫn còn attachment → physical delete, DB giữ với expired=true
 *  CJ04: cleanupOrphanFiles — orphan cũ hơn 1h → xóa DB
 *  CJ05: cleanupOrphanFiles — orphan mới (30 phút) → không xóa
 *  CJ06: cleanupOrphanFiles — file đã attach (attachedAt non-null) → không xóa dù createdAt cũ
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@ActiveProfiles("test")
class FileCleanupJobTest {

    @Autowired
    private FileCleanupJob fileCleanupJob;

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

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAuthProviderRepository userAuthProviderRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // MockBean để tránh disk I/O thật
    @MockBean
    private StorageService storageService;

    // MockBean để tránh UnsatisfiedDependency (FileService inject StringRedisTemplate)
    @MockBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @MockBean
    private FirebaseAuth firebaseAuth;

    @MockBean
    private SimpMessagingTemplate simpMessagingTemplate;

    // Test user được tạo 1 lần trong setUp
    private User testUser;

    @BeforeEach
    void setUp() throws Exception {
        // Cleanup order: FK chain message_attachments → messages → conversation_members → conversations → files → users
        messageAttachmentRepository.deleteAll();
        messageRepository.deleteAll();
        conversationMemberRepository.deleteAll();
        conversationRepository.deleteAll();
        fileRecordRepository.deleteAll();
        userAuthProviderRepository.deleteAll();
        userRepository.deleteAll();

        // Mock StorageService: delete() không làm gì (no-op, không throw IOException)
        doNothing().when(storageService).delete(anyString());

        // Tạo user dùng chung cho các test (tránh lặp code)
        testUser = userRepository.save(User.builder()
                .email("cleanup_test@test.com")
                .username("cleanuptest")
                .passwordHash("$2a$12$dummy")
                .fullName("Cleanup Test User")
                .build());
    }

    // =========================================================================
    // CJ01: Expired file, không còn attachment → bị xóa khỏi DB
    // =========================================================================

    @Test
    void cleanupExpiredFiles_expiredNoAttachment_deletedFromDB() throws Exception {
        // Arrange: file với expiresAt 2 ngày trước, expired=false
        FileRecord file = saveFileWithOverrides(
                buildFileRecord(testUser.getId()),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(35), // createdAt
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(2)   // expiresAt
        );
        UUID fileId = file.getId();

        // Act
        fileCleanupJob.cleanupExpiredFiles();

        // Assert: physical delete được gọi
        verify(storageService, atLeastOnce()).delete(file.getStoragePath());
        // Assert: record bị xóa khỏi DB
        assertFalse(fileRecordRepository.existsById(fileId),
                "File record phải bị xóa khỏi DB sau cleanup expired");
    }

    // =========================================================================
    // CJ02: File chưa hết hạn → không bị touch
    // =========================================================================

    @Test
    void cleanupExpiredFiles_notExpiredYet_untouched() throws Exception {
        // Arrange: file với expiresAt 5 ngày sau
        FileRecord file = saveFileWithOverrides(
                buildFileRecord(testUser.getId()),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(25), // createdAt
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(5)    // expiresAt (tương lai)
        );
        UUID fileId = file.getId();

        // Act
        fileCleanupJob.cleanupExpiredFiles();

        // Assert: physical delete KHÔNG được gọi
        verify(storageService, never()).delete(anyString());
        // Assert: record vẫn trong DB
        assertTrue(fileRecordRepository.existsById(fileId),
                "File chưa hết hạn không được xóa");
    }

    // =========================================================================
    // CJ03: Expired nhưng vẫn còn attachment → physical delete, DB giữ với expired=true
    // =========================================================================

    @Test
    void cleanupExpiredFiles_expiredStillAttached_physicalDeletedDbKept() throws Exception {
        // Arrange: tạo conversation + message + attachment để file còn FK
        Conversation conv = conversationRepository.save(Conversation.builder()
                .type(ConversationType.ONE_ON_ONE)
                .createdBy(testUser)
                .build());
        conversationMemberRepository.save(ConversationMember.builder()
                .conversation(conv).user(testUser).role(MemberRole.OWNER).build());
        Message msg = messageRepository.save(Message.builder()
                .conversation(conv).sender(testUser).type(MessageType.IMAGE).content("photo").build());

        FileRecord file = saveFileWithOverrides(
                buildFileRecord(testUser.getId()),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(35),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(2)
        );
        messageAttachmentRepository.save(MessageAttachment.builder()
                .id(new MessageAttachmentId(msg.getId(), file.getId()))
                .displayOrder((short) 0)
                .build());

        UUID fileId = file.getId();

        // Act
        fileCleanupJob.cleanupExpiredFiles();

        // Assert: physical delete được gọi (original path)
        verify(storageService, atLeastOnce()).delete(file.getStoragePath());
        // Assert: record vẫn trong DB (vì vẫn còn attachment)
        assertTrue(fileRecordRepository.existsById(fileId),
                "Record phải giữ lại trong DB khi vẫn có attachment");
        // Assert: expired=true được set
        FileRecord updated = fileRecordRepository.findById(fileId).orElseThrow();
        assertTrue(updated.isExpired(),
                "expired phải được set thành true cho file có attachment hết hạn");
    }

    // =========================================================================
    // CJ04: Orphan cũ hơn 1h → bị xóa khỏi DB
    // =========================================================================

    @Test
    void cleanupOrphanFiles_orphanOlderThan1h_deleted() throws Exception {
        // Arrange: attachedAt=null (orphan), createdAt 2 ngày trước (dùng ngày để tránh H2 sub-hour precision issue)
        FileRecord file = saveFileWithOverrides(
                buildOrphanRecord(testUser.getId()),
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(2),  // createdAt (2 days old — well past 1h threshold)
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(28)   // expiresAt (chưa hết hạn)
        );
        UUID fileId = file.getId();

        // Act
        fileCleanupJob.cleanupOrphanFiles();

        // Assert
        verify(storageService, atLeastOnce()).delete(file.getStoragePath());
        assertFalse(fileRecordRepository.existsById(fileId),
                "Orphan cũ hơn 1h phải bị xóa");
    }

    // =========================================================================
    // CJ05: Orphan mới hơn 1h (vài phút trước) → không bị xóa
    // =========================================================================

    @Test
    void cleanupOrphanFiles_recentOrphan_untouched() throws Exception {
        // Arrange: attachedAt=null (orphan), createdAt vài phút trước (trong grace period 1h)
        // Dùng minusMinutes(5) để rõ ràng nằm trong 1h grace period.
        FileRecord file = saveFileWithOverrides(
                buildOrphanRecord(testUser.getId()),
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5), // createdAt (very recent)
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(30)     // expiresAt
        );
        UUID fileId = file.getId();

        // Act
        fileCleanupJob.cleanupOrphanFiles();

        // Assert: không bị touch
        verify(storageService, never()).delete(anyString());
        assertTrue(fileRecordRepository.existsById(fileId),
                "Orphan mới hơn 1h không được xóa (grace period)");
    }

    // =========================================================================
    // CJ06: File đã attach (attachedAt non-null), createdAt rất cũ → không bị xóa bởi orphan job
    // =========================================================================

    @Test
    void cleanupOrphanFiles_attachedFile_untouched() throws Exception {
        // Arrange: attachedAt non-null = đã attach, createdAt 2 ngày trước
        FileRecord file = fileRecordRepository.save(FileRecord.builder()
                .id(UUID.randomUUID())
                .uploaderId(testUser.getId())
                .originalName("attached.jpg")
                .mime("image/jpeg")
                .sizeBytes(1024L)
                .storagePath("2024/01/" + UUID.randomUUID() + ".jpg")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2))
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(28))
                .expired(false)
                .attachedAt(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2)) // đã attach
                .build());
        // Override createdAt với JdbcTemplate (attachedAt đã set trong builder, @PrePersist không override)
        jdbcTemplate.update(
                "UPDATE files SET created_at = ? WHERE id = CAST(? AS UUID)",
                java.sql.Timestamp.from(OffsetDateTime.now(ZoneOffset.UTC).minusDays(2).toInstant()),
                file.getId().toString()
        );
        UUID fileId = file.getId();

        // Act
        fileCleanupJob.cleanupOrphanFiles();

        // Assert: KHÔNG bị xóa vì attachedAt non-null
        verify(storageService, never()).delete(anyString());
        assertTrue(fileRecordRepository.existsById(fileId),
                "File đã attach không được xóa bởi orphan job");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    /**
     * Build FileRecord builder với attachedAt=null (orphan-eligible).
     * createdAt và expiresAt sẽ được override bởi saveFileWithOverrides.
     */
    private FileRecord buildOrphanRecord(UUID uploaderId) {
        return FileRecord.builder()
                .id(UUID.randomUUID())
                .uploaderId(uploaderId)
                .originalName("test.jpg")
                .mime("image/jpeg")
                .sizeBytes(1024L)
                .storagePath("2024/01/" + UUID.randomUUID() + ".jpg")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)) // overridden
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30)) // overridden
                .expired(false)
                .attachedAt(null)
                .build();
    }

    /**
     * Build FileRecord builder với attachedAt=null.
     * createdAt và expiresAt sẽ được override bởi saveFileWithOverrides.
     */
    private FileRecord buildFileRecord(UUID uploaderId) {
        return FileRecord.builder()
                .id(UUID.randomUUID())
                .uploaderId(uploaderId)
                .originalName("file.jpg")
                .mime("image/jpeg")
                .sizeBytes(512L)
                .storagePath("2024/01/" + UUID.randomUUID() + ".jpg")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC)) // overridden
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(30)) // overridden
                .expired(false)
                .attachedAt(null)
                .build();
    }

    /**
     * Save FileRecord và override createdAt + expiresAt bằng JdbcTemplate.
     * Cần thiết vì @PrePersist set createdAt=now() và service set expiresAt.
     * JdbcTemplate UPDATE sau save đảm bảo giá trị test chính xác.
     */
    private FileRecord saveFileWithOverrides(FileRecord record,
                                              OffsetDateTime createdAt,
                                              OffsetDateTime expiresAt) {
        FileRecord saved = fileRecordRepository.save(record);
        // H2 UUID in WHERE clause: cast to VARCHAR for H2 compatibility (per knowledge base pattern).
        // Timestamp: use java.sql.Timestamp for TIMESTAMPTZ column in H2 PostgreSQL MODE.
        jdbcTemplate.update(
                "UPDATE files SET created_at = ?, expires_at = ? WHERE id = CAST(? AS UUID)",
                java.sql.Timestamp.from(createdAt.toInstant()),
                java.sql.Timestamp.from(expiresAt.toInstant()),
                saved.getId().toString()
        );
        // Clear JPA 1st-level cache and reload from DB to get updated timestamps
        fileRecordRepository.flush();
        return fileRecordRepository.findById(saved.getId()).orElseThrow();
    }
}
