package com.chatapp.file;

import com.chatapp.file.exception.FileEmptyException;
import com.chatapp.file.exception.FileTooLargeException;
import com.chatapp.file.exception.FileTypeNotAllowedException;
import com.chatapp.file.exception.MimeMismatchException;
import com.chatapp.file.service.FileValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FileValidationService.
 *
 * Test strategy: dùng actual magic bytes để Tika detect đúng — NOT mock Tika
 * (vì chính Tika là phần quan trọng cần verify).
 *
 *  V01: valid JPEG → pass, trả "image/jpeg"
 *  V02: valid PDF → pass, trả "application/pdf"
 *  V03: valid PNG → pass, trả "image/png"
 *  V04: file > 20MB → FileTooLargeException
 *  V05: empty file → FileEmptyException
 *  V06: null file → FileEmptyException
 *  V07: MIME không trong whitelist (text/plain) → FileTypeNotAllowedException
 *  V08: declared khác detected (declared=image/png nhưng bytes là JPEG) → MimeMismatchException
 *  V09: extensionFromMime cho tất cả MIME whitelist → map đúng
 *  V10: declared alias image/jpg vs detected image/jpeg → accepted (no exception)
 */
class FileValidationServiceTest {

    private FileValidationService service;

    // JPEG magic bytes: FF D8 FF E0 + JFIF header
    private static final byte[] JPEG_MAGIC = new byte[]{
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01,
            0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00
    };

    // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A + IHDR chunk
    private static final byte[] PNG_MAGIC = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00
    };

    // PDF magic bytes: %PDF-1.4
    private static final byte[] PDF_MAGIC = "%PDF-1.4\n%âãÏÓ\n".getBytes();

    @BeforeEach
    void setUp() {
        service = new FileValidationService();
    }

    // V01
    @Test
    void validate_validJpeg_returnsImageJpeg() {
        MultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", JPEG_MAGIC);
        String mime = service.validate(file);
        assertEquals("image/jpeg", mime);
    }

    // V02
    @Test
    void validate_validPdf_returnsApplicationPdf() {
        MultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf", PDF_MAGIC);
        String mime = service.validate(file);
        assertEquals("application/pdf", mime);
    }

    // V03
    @Test
    void validate_validPng_returnsImagePng() {
        MultipartFile file = new MockMultipartFile(
                "file", "icon.png", "image/png", PNG_MAGIC);
        String mime = service.validate(file);
        assertEquals("image/png", mime);
    }

    // V04
    @Test
    void validate_tooLarge_throwsFileTooLargeException() {
        // Tạo file > 20MB — fake InputStream để không OOM
        long size = 20L * 1024 * 1024 + 1;
        MultipartFile file = new SizedMockMultipartFile("file", "big.jpg", "image/jpeg",
                JPEG_MAGIC, size);

        FileTooLargeException ex = assertThrows(FileTooLargeException.class,
                () -> service.validate(file));
        assertEquals(20L * 1024 * 1024, ex.getMaxBytes());
        assertEquals(size, ex.getActualBytes());
    }

    // V05
    @Test
    void validate_emptyFile_throwsFileEmptyException() {
        MultipartFile file = new MockMultipartFile(
                "file", "empty.jpg", "image/jpeg", new byte[0]);
        assertThrows(FileEmptyException.class, () -> service.validate(file));
    }

    // V06
    @Test
    void validate_nullFile_throwsFileEmptyException() {
        assertThrows(FileEmptyException.class, () -> service.validate(null));
    }

    // V07
    @Test
    void validate_mimeNotInWhitelist_throwsFileTypeNotAllowedException() {
        // EXE magic bytes (MZ) — không trong whitelist
        byte[] exeBytes = new byte[]{0x4D, 0x5A, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        MultipartFile file = new MockMultipartFile(
                "file", "virus.exe", "application/octet-stream", exeBytes);

        FileTypeNotAllowedException ex = assertThrows(FileTypeNotAllowedException.class,
                () -> service.validate(file));
        assertEquals(FileValidationService.ALLOWED_MIMES, ex.getAllowedMimes());
        assertNotEquals("image/jpeg", ex.getActualMime()); // bất cứ thứ gì khác
    }

    // V08
    @Test
    void validate_declaredMimeMismatch_throwsMimeMismatchException() {
        // Bytes là JPEG thực, nhưng client khai báo image/png → spoofing attempt
        MultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", JPEG_MAGIC);

        MimeMismatchException ex = assertThrows(MimeMismatchException.class,
                () -> service.validate(file));
        assertEquals("image/png", ex.getDeclaredMime());
        assertEquals("image/jpeg", ex.getDetectedMime());
    }

    // V09
    @Test
    void extensionFromMime_allWhitelistMimes_returnsCorrectExt() {
        // Group A: Images
        assertEquals("jpg",  service.extensionFromMime("image/jpeg"));
        assertEquals("png",  service.extensionFromMime("image/png"));
        assertEquals("webp", service.extensionFromMime("image/webp"));
        assertEquals("gif",  service.extensionFromMime("image/gif"));
        // Group B: Documents & archives
        assertEquals("pdf",  service.extensionFromMime("application/pdf"));
        assertEquals("docx", service.extensionFromMime(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
        assertEquals("xlsx", service.extensionFromMime(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertEquals("pptx", service.extensionFromMime(
                "application/vnd.openxmlformats-officedocument.presentationml.presentation"));
        assertEquals("doc",  service.extensionFromMime("application/msword"));
        assertEquals("xls",  service.extensionFromMime("application/vnd.ms-excel"));
        assertEquals("ppt",  service.extensionFromMime("application/vnd.ms-powerpoint"));
        assertEquals("txt",  service.extensionFromMime("text/plain"));
        assertEquals("zip",  service.extensionFromMime("application/zip"));
        assertEquals("7z",   service.extensionFromMime("application/x-7z-compressed"));
    }

    // V10
    @Test
    void validate_declaredAliasImageJpg_acceptsAsJpeg() {
        // Một số browser (Firefox cũ) gửi image/jpg thay vì image/jpeg.
        MultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpg", JPEG_MAGIC);
        String mime = service.validate(file);
        assertEquals("image/jpeg", mime);
    }

    // V11 (W6-D4-extend): text/plain bytes → trả "text/plain" (charset stripped nếu có)
    @Test
    void validate_textPlain_returnsTextPlain() {
        byte[] textContent = "Hello World text content".getBytes();
        MultipartFile file = new MockMultipartFile(
                "file", "readme.txt", "text/plain", textContent);
        String mime = service.validate(file);
        // charset suffix phải đã stripped
        assertEquals("text/plain", mime);
    }

    // V12 (W6-D4-extend): ZIP magic + extension .docx → override thành docx MIME
    @Test
    void validate_zipMagicWithDocxExtension_returnsDocxMime() {
        byte[] zipMagic = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
        String docxMime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        MultipartFile file = new MockMultipartFile(
                "file", "document.docx", docxMime, zipMagic);
        String result = service.validate(file);
        assertEquals(docxMime, result);
    }

    // V13 (W6-D4-extend): ZIP magic + extension .xlsx → override thành xlsx MIME
    @Test
    void validate_zipMagicWithXlsxExtension_returnsXlsxMime() {
        byte[] zipMagic = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
        String xlsxMime = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        MultipartFile file = new MockMultipartFile(
                "file", "sheet.xlsx", xlsxMime, zipMagic);
        String result = service.validate(file);
        assertEquals(xlsxMime, result);
    }

    // V14 (W6-D4-extend): ZIP magic + extension .zip (thật sự là zip) → giữ application/zip
    @Test
    void validate_zipMagicWithZipExtension_returnsApplicationZip() {
        byte[] zipMagic = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
        MultipartFile file = new MockMultipartFile(
                "file", "archive.zip", "application/zip", zipMagic);
        String result = service.validate(file);
        assertEquals("application/zip", result);
    }

    // Helper: MockMultipartFile với explicit size, phục vụ test size limit mà không cần alloc 20MB byte[]
    static class SizedMockMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] head;
        private final long size;

        SizedMockMultipartFile(String name, String filename, String contentType, byte[] head, long size) {
            this.name = name;
            this.originalFilename = filename;
            this.contentType = contentType;
            this.head = head;
            this.size = size;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return size == 0; }
        @Override public long getSize() { return size; }
        @Override public byte[] getBytes() { return head; }
        @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(head); }
        @Override public void transferTo(java.io.File dest) throws IOException {
            throw new UnsupportedOperationException();
        }
    }
}
