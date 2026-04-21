package com.chatapp.file;

import com.chatapp.file.storage.LocalStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LocalStorageService.
 *
 *  S01: store returns path format "yyyy/MM/uuid.ext" (relative to basePath)
 *  S02: store + retrieve roundtrip — content bytes khớp
 *  S03: path traversal trên retrieve ("../etc/passwd") → IllegalArgumentException
 *  S04: delete sau store → file biến mất khỏi disk
 *  S05: retrieve file không tồn tại → IOException
 *  S06: store với fileId chứa path separator → IllegalArgumentException
 *  S07: store với ext chứa dấu chấm → IllegalArgumentException
 */
class LocalStorageServiceTest {

    @TempDir
    Path tmpDir;

    private LocalStorageService service;

    @BeforeEach
    void setUp() {
        // Init service với base = tempDir. @TempDir tự cleanup sau mỗi test.
        service = new LocalStorageService(tmpDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        // Dọn manually (phòng trường hợp @TempDir cleanup chưa kịp)
        if (Files.exists(tmpDir)) {
            try (var walk = Files.walk(tmpDir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        }
    }

    // S01
    @Test
    void store_returnsPathFormatYearMonthUuidExt() throws IOException {
        String fileId = UUID.randomUUID().toString();
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);

        String storagePath = service.store(
                new ByteArrayInputStream(content), fileId, "jpg");

        // Expected format: "yyyy/MM/{fileId}.jpg" (forward slash on all OS)
        LocalDate today = LocalDate.now();
        String year = String.format("%04d", today.getYear());
        String month = String.format("%02d", today.getMonthValue());
        String expected = year + "/" + month + "/" + fileId + ".jpg";

        assertEquals(expected, storagePath);

        // File phải thực sự tồn tại trên disk
        Path actualFile = service.getBasePath().resolve(storagePath);
        assertTrue(Files.exists(actualFile), "File phải được tạo trên disk");
        assertArrayEquals(content, Files.readAllBytes(actualFile));
    }

    // S02
    @Test
    void retrieve_afterStore_returnsCorrectContent() throws IOException {
        byte[] content = "roundtrip test content".getBytes(StandardCharsets.UTF_8);
        String fileId = UUID.randomUUID().toString();

        String storagePath = service.store(
                new ByteArrayInputStream(content), fileId, "pdf");

        try (InputStream in = service.retrieve(storagePath)) {
            byte[] read = in.readAllBytes();
            assertArrayEquals(content, read);
        }
    }

    // S03
    @Test
    void retrieve_pathTraversal_throwsIllegalArgumentException() {
        // Attempt: "../../etc/passwd"
        assertThrows(IllegalArgumentException.class,
                () -> service.retrieve("../../etc/passwd"));

        // Attempt: "../outside.txt"
        assertThrows(IllegalArgumentException.class,
                () -> service.retrieve("../outside.txt"));
    }

    // S04
    @Test
    void delete_afterStore_fileNoLongerExists() throws IOException {
        byte[] content = "to delete".getBytes(StandardCharsets.UTF_8);
        String fileId = UUID.randomUUID().toString();

        String storagePath = service.store(
                new ByteArrayInputStream(content), fileId, "png");
        Path actualFile = service.getBasePath().resolve(storagePath);
        assertTrue(Files.exists(actualFile));

        service.delete(storagePath);
        assertFalse(Files.exists(actualFile), "File nên bị xoá sau delete()");

        // Delete lần 2 → idempotent, không throw
        assertDoesNotThrow(() -> service.delete(storagePath));
    }

    // S05
    @Test
    void retrieve_nonExistentFile_throwsIOException() {
        // Path hợp lệ (trong basePath) nhưng file chưa tồn tại
        String fakePath = "2099/01/" + UUID.randomUUID() + ".jpg";
        assertThrows(IOException.class, () -> service.retrieve(fakePath));
    }

    // S06
    @Test
    void store_fileIdWithPathSeparator_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.store(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                        "../evil", "jpg"));

        assertThrows(IllegalArgumentException.class, () ->
                service.store(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                        "foo/bar", "jpg"));
    }

    // S07
    @Test
    void store_extWithDot_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                service.store(new ByteArrayInputStream(new byte[]{1, 2, 3}),
                        UUID.randomUUID().toString(), ".jpg"));
    }
}
