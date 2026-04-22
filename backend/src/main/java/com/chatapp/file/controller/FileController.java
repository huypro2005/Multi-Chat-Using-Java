package com.chatapp.file.controller;

import com.chatapp.exception.AppException;
import com.chatapp.file.dto.FileDto;
import com.chatapp.file.entity.FileRecord;
import com.chatapp.file.exception.StorageException;
import com.chatapp.file.service.FileAuthService;
import com.chatapp.file.service.FileService;
import com.chatapp.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Duration;
import java.util.UUID;

/**
 * REST controller cho File endpoints.
 *
 * POST /api/files/upload       → 201 FileDto (W6-D1)
 * GET  /api/files/{id}         → 200 stream file content (W6-D1 stub: uploader only)
 *
 * W6-D2 sẽ thêm:
 *  - GET /api/files/{id}/thumb  → 200 thumbnail JPEG
 *  - Authorization mở rộng cho conv member
 *
 * Auth required: tất cả. JWT được validate bởi JwtAuthFilter.
 * @AuthenticationPrincipal User: Spring Security inject User entity trực tiếp (xem JwtAuthFilter).
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;
    private final FileAuthService fileAuthService;

    /**
     * POST /api/files/upload
     * Upload 1 file (multipart/form-data, field "file"). Trả FileDto.
     *
     * @param file  multipart part — server validate size, MIME (magic bytes), MIME mismatch.
     * @param user  authenticated user (từ JWT).
     * @return 201 Created + FileDto.
     */
    /**
     * POST /api/files/upload?public={true|false}
     *
     * W7-D4-fix (ADR-021): query param {@code public} (default false).
     *  - public=true  → avatar upload → is_public=true, endpoint /api/files/{id}/public.
     *  - public=false → message attachment (default) → is_public=false, endpoint /api/files/{id}.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileDto upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "public", defaultValue = "false") boolean isPublic,
            @AuthenticationPrincipal User user) {
        return fileService.upload(file, user.getId(), isPublic);
    }

    /**
     * GET /api/files/{id}
     * Stream file content — uploader hoặc conv-member (W6-D2 dùng FileAuthService).
     *
     * Response headers (theo API_CONTRACT.md):
     *  - Content-Type: MIME thật của file.
     *  - Content-Disposition: inline; filename="{originalName}".
     *  - Cache-Control: private, max-age=604800 (7 ngày).
     *  - X-Content-Type-Options: nosniff.
     *  - ETag: file.id (immutable).
     */
    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {

        FileRecord record = fileAuthService.findAccessibleById(id, user.getId())
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Không tìm thấy file"));

        InputStream stream;
        try {
            stream = fileService.openStream(record);
        } catch (StorageException e) {
            log.warn("[FileController] Physical file missing for record {} — storage error: {}", id, e.getMessage());
            throw new AppException(HttpStatus.NOT_FOUND, "FILE_PHYSICALLY_DELETED",
                    "File đã bị xóa khỏi hệ thống");
        }
        InputStreamResource resource = new InputStreamResource(stream);

        // Content-Disposition: inline cho browser render (image/PDF). Sanitize tránh header injection.
        String safeFilename = sanitizeForHeader(record.getOriginalName());
        String contentDisposition = "inline; filename=\"" + safeFilename + "\"";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(record.getMime()))
                .contentLength(record.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header("X-Content-Type-Options", "nosniff")
                .eTag("\"" + record.getId() + "\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(resource);
    }

    /**
     * GET /api/files/{id}/thumb
     * Stream thumbnail 200x200 của image (W6-D2).
     *
     * Auth: dùng {@link com.chatapp.file.service.FileAuthService} — uploader OR
     * member của conv chứa message attach file này. Anti-enumeration: mọi case
     * (not-found / not-accessible / expired / không có thumb) → 404 FILE_NOT_FOUND.
     *
     * Response:
     *  - Content-Type: MIME của file (thumbnail giữ cùng format với source: jpg/png/webp/gif).
     *  - Cache-Control: private, max-age=604800 (7 ngày) — thumbnail immutable.
     *  - ETag: "{id}-thumb".
     *  - X-Content-Type-Options: nosniff.
     */
    @GetMapping("/{id}/thumb")
    public ResponseEntity<Resource> downloadThumb(
            @PathVariable UUID id,
            @AuthenticationPrincipal User user) {

        FileRecord record = fileAuthService.findAccessibleById(id, user.getId())
                .filter(r -> r.getThumbnailInternalPath() != null)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND",
                        "Không tìm thấy thumbnail"));

        InputStream stream;
        try {
            stream = fileService.openThumbnailStream(record);
        } catch (StorageException e) {
            log.warn("[FileController] Physical thumbnail missing for record {} — storage error: {}", id, e.getMessage());
            throw new AppException(HttpStatus.NOT_FOUND, "FILE_PHYSICALLY_DELETED",
                    "Thumbnail đã bị xóa khỏi hệ thống");
        }
        InputStreamResource resource = new InputStreamResource(stream);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(record.getMime()))
                .header("X-Content-Type-Options", "nosniff")
                .eTag("\"" + record.getId() + "-thumb\"")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate())
                .body(resource);
    }

    /**
     * GET /api/files/{id}/public
     *
     * Public file download — KHÔNG yêu cầu JWT (ADR-021, W7-D4-fix).
     * Chỉ serve files có is_public=true (avatar user, avatar group, default system files).
     *
     * Anti-enumeration: trả 404 cho CẢ not-found, is_public=false, expired — ngăn
     * attacker dò is_public flag.
     *
     * Response headers:
     *  - Content-Type: MIME thật của file.
     *  - Content-Disposition: inline; filename="{originalName}".
     *  - Cache-Control: public, max-age=86400 (1 ngày — browser HTTP cache thay cho blob URL).
     *  - ETag: file.id.
     *  - X-Content-Type-Options: nosniff.
     */
    @GetMapping("/{id}/public")
    public ResponseEntity<Resource> downloadPublic(@PathVariable UUID id) {
        FileRecord record = fileService.loadForPublicDownload(id);
        if (record == null) {
            throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Tệp không tồn tại");
        }

        InputStream stream;
        try {
            stream = fileService.openStream(record);
        } catch (StorageException e) {
            log.warn("[FileController] Public file physical missing: id={}, err={}",
                    id, e.getMessage());
            throw new AppException(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND",
                    "Tệp không tồn tại");
        }
        InputStreamResource resource = new InputStreamResource(stream);

        String safeFilename = sanitizeForHeader(record.getOriginalName());
        String contentDisposition = "inline; filename=\"" + safeFilename + "\"";

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(record.getMime()))
                .contentLength(record.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header("X-Content-Type-Options", "nosniff")
                .eTag("\"" + record.getId() + "\"")
                // Public cache: browser + CDN cache. Avatar change → URL đổi → cache miss tự nhiên.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .body(resource);
    }

    /**
     * Sanitize filename cho Content-Disposition header — strip CRLF (chống header injection)
     * + non-ASCII (tránh encoding corner-case với RFC 5987). Giữ ký tự printable ASCII.
     */
    private String sanitizeForHeader(String name) {
        if (name == null) return "file";
        return name.replaceAll("[\\r\\n\"]", "")
                .replaceAll("[^\\x20-\\x7E]", "_");
    }
}
