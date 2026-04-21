package com.chatapp.file.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

/**
 * Local disk implementation của StorageService — V1 storage layer (ADR-019).
 *
 * Path layout: `{base}/{yyyy}/{mm}/{fileId}.{ext}`.
 *  - {base} config qua `storage.local.base-path` (default `./uploads`).
 *  - {yyyy}/{mm} = năm/tháng của thời điểm upload — giúp filesystem không có
 *    thư mục chứa > 10k files (ext4 hashed dir vẫn OK, nhưng ls/backup dễ hơn).
 *  - {fileId} là UUID v4 do service sinh — KHÔNG dùng originalName (path traversal).
 *  - {ext} lấy từ MIME whitelist map (không từ client filename).
 *
 * Security: mọi path resolve phải đi qua `assertWithinBase()` — canonicalize + prefix check.
 * Đây là defense chính chống path traversal (W6-1 trong WARNINGS.md).
 */
@Slf4j
@Service
public class LocalStorageService implements StorageService {

    private final Path basePath;

    public LocalStorageService(
            @Value("${storage.local.base-path:./uploads}") String basePathRaw) {
        try {
            Path base = Paths.get(basePathRaw).toAbsolutePath().normalize();
            Files.createDirectories(base);
            // Store canonical (resolves symlinks) so prefix check is robust.
            this.basePath = base.toRealPath();
            log.info("LocalStorageService initialized — basePath={}", this.basePath);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Không khởi tạo được base path cho storage: " + basePathRaw, e);
        }
    }

    @Override
    public String store(InputStream data, String fileId, String ext) throws IOException {
        if (fileId == null || fileId.isBlank()) {
            throw new IllegalArgumentException("fileId không được rỗng");
        }
        if (ext == null || ext.isBlank()) {
            throw new IllegalArgumentException("ext không được rỗng");
        }
        // fileId phải là UUID-ish — không được chứa path separator, không "." hay ".."
        if (fileId.contains("/") || fileId.contains("\\") || fileId.contains("..")) {
            throw new IllegalArgumentException("fileId không hợp lệ: " + fileId);
        }
        if (ext.contains("/") || ext.contains("\\") || ext.contains(".")) {
            throw new IllegalArgumentException("ext không hợp lệ: " + ext);
        }

        LocalDate today = LocalDate.now();
        String year = String.format("%04d", today.getYear());
        String month = String.format("%02d", today.getMonthValue());

        // Resolve target: {base}/{yyyy}/{mm}/{fileId}.{ext}
        Path monthDir = basePath.resolve(year).resolve(month);
        Files.createDirectories(monthDir);

        Path target = monthDir.resolve(fileId + "." + ext).normalize();

        // Defense-in-depth: canonical prefix check sau khi resolve.
        assertWithinBase(target);

        Files.copy(data, target, StandardCopyOption.REPLACE_EXISTING);

        // Trả về path relative so với basePath để lưu DB — tránh đưa absolute path vào DB
        // (dễ break khi đổi base path giữa môi trường dev/staging/prod).
        Path relative = basePath.relativize(target);
        // Normalize separators thành '/' để Windows/Linux consistent trong DB.
        return relative.toString().replace('\\', '/');
    }

    @Override
    public InputStream retrieve(String storagePath) throws IOException {
        Path resolved = resolveAndValidate(storagePath);
        if (!Files.exists(resolved)) {
            throw new IOException("File không tồn tại: " + storagePath);
        }
        return Files.newInputStream(resolved);
    }

    @Override
    public void delete(String storagePath) throws IOException {
        Path resolved = resolveAndValidate(storagePath);
        Files.deleteIfExists(resolved);
    }

    /**
     * W6-D2: resolve internal path → absolute Path với canonical prefix check.
     *
     * Throw {@link SecurityException} (khác với {@link IllegalArgumentException} của
     * {@link #resolveAndValidate}) để caller phân biệt "path traversal attempt" với
     * "args invalid" — ThumbnailService rethrow SecurityException như attack signal.
     *
     * <p>Không check file tồn tại — caller decides (ThumbnailService thao tác file
     * sẽ tạo mới, download endpoint cần file tồn tại).
     */
    @Override
    public Path resolveAbsolute(String internalPath) throws SecurityException {
        if (internalPath == null || internalPath.isBlank()) {
            throw new SecurityException("internalPath rỗng");
        }
        Path candidate = basePath.resolve(internalPath).normalize().toAbsolutePath();
        if (!candidate.startsWith(basePath)) {
            throw new SecurityException(
                    "Path traversal detected — target nằm ngoài basePath: " + candidate);
        }
        return candidate;
    }

    /**
     * Resolve storagePath (relative) thành absolute path và verify nằm trong basePath.
     * Throw IllegalArgumentException nếu phát hiện path traversal attempt.
     *
     * Exposed package-private cho unit test.
     */
    Path resolveAndValidate(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath rỗng");
        }
        // Normalize để handle cả Windows '\' lẫn Unix '/'
        Path candidate = basePath.resolve(storagePath).normalize();
        assertWithinBase(candidate);
        return candidate;
    }

    /**
     * Canonical prefix check — chống path traversal (../../etc/passwd).
     *
     * Dùng `toAbsolutePath().normalize()` + `startsWith(basePath)`. Không dùng
     * toRealPath() trên target vì file có thể chưa tồn tại (trường hợp store()).
     */
    private void assertWithinBase(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(basePath)) {
            throw new IllegalArgumentException(
                    "Path traversal detected — target nằm ngoài basePath: " + normalized);
        }
    }

    /** Accessor cho test + diagnostics (actuator). */
    public Path getBasePath() {
        return basePath;
    }
}
