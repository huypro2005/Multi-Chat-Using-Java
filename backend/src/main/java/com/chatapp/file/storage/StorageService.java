package com.chatapp.file.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Abstraction cho file storage — V1 dùng local disk (LocalStorageService),
 * V2 migrate S3 (S3StorageService) mà KHÔNG đổi code business logic (ADR-019).
 *
 * Tất cả method throws IOException thuần để caller (FileService) catch và
 * wrap thành StorageException với message friendly.
 */
public interface StorageService {

    /**
     * Lưu stream vào storage.
     *
     * @param data    nội dung file (caller đã validate size + MIME).
     * @param fileId  UUID v4 string (không kèm extension) — tên file trên disk.
     * @param ext     extension (không kèm dấu chấm, ví dụ "jpg", "pdf") — map từ MIME whitelist.
     * @return storagePath — identifier nội bộ để retrieve/delete sau này. V1 = relative path,
     *         V2 sẽ là S3 key. Caller lưu vào files.storage_path.
     */
    String store(InputStream data, String fileId, String ext) throws IOException;

    /**
     * Mở stream để đọc file đã lưu. Caller có trách nhiệm close stream sau khi dùng.
     * Dùng trong GET /api/files/{id} để stream xuống client.
     */
    InputStream retrieve(String storagePath) throws IOException;

    /**
     * Xoá file khỏi storage. No-op nếu không tồn tại (idempotent).
     * Dùng trong cleanup job (W6-D3) khi file expire hoặc orphan.
     */
    void delete(String storagePath) throws IOException;

    /**
     * Resolve internal (relative) path thành absolute {@link Path}, kèm canonical
     * prefix check — throw {@link SecurityException} nếu phát hiện path traversal
     * (path cố nằm ngoài basePath).
     *
     * <p>Dùng trong {@code ThumbnailService.generate()} và các tác vụ đọc/ghi
     * trực tiếp filesystem (cần {@code Path} object, không phải {@code InputStream}).
     *
     * <p>V1 local disk: trả absolute path trong basePath. V2 S3: sẽ throw
     * {@code UnsupportedOperationException} hoặc trả {@link Path} tạm cục bộ sau khi
     * download — tuỳ implementation khi migrate.
     *
     * @param internalPath path relative đã lưu trong DB (vd "2026/04/abc.jpg").
     * @return absolute {@link Path} đã được canonicalize + validate.
     * @throws SecurityException nếu internalPath escape basePath.
     */
    Path resolveAbsolute(String internalPath) throws SecurityException;
}
