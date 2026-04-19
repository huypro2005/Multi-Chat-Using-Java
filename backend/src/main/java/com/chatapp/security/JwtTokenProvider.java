package com.chatapp.security;

import com.chatapp.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT utility component — generate, validate, extract claims.
 *
 * Dùng jjwt 0.12.x API:
 *   - Parser: Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
 *   - Sign:   .signWith(key)  (không còn signWith(key, algorithm) style cũ)
 *
 * Secret key được encode sang Base64 nếu chưa phải Base64, rồi dùng
 * Keys.hmacShaKeyFor() để tạo HMAC-SHA256 key.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    /**
     * Kết quả validate token chi tiết.
     * VALID    — token hợp lệ, có thể dùng.
     * EXPIRED  — token hết hạn, FE có thể dùng refresh token để lấy token mới.
     * INVALID  — token sai signature / malformed, FE phải login lại.
     */
    public enum TokenValidationResult {
        VALID, EXPIRED, INVALID
    }

    private final SecretKey secretKey;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expiration-ms}") long accessExpirationMs,
            @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {

        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;

        // Lấy raw bytes từ secret string (UTF-8), không cần Base64 decode.
        // Keys.hmacShaKeyFor yêu cầu >= 32 bytes (256-bit) cho HMAC-SHA256.
        // Secret trong application.yml phải đủ dài (>= 32 ký tự).
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Tạo access token (1 giờ theo config).
     * Claims: sub=userId, username, auth_method, jti (UUID random).
     * auth_method hardcode "password" — Tuần 2 sẽ truyền dynamic khi có OAuth.
     */
    public String generateAccessToken(User user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("auth_method", "password")
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + accessExpirationMs))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Tạo refresh token (7 ngày theo config).
     * Claims: sub=userId, jti (UUID random). Không có username/auth_method — chỉ dùng để refresh.
     */
    public String generateRefreshToken(User user) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.getId().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + refreshExpirationMs))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Validate token với kết quả chi tiết — phân biệt expired vs invalid.
     * EXPIRED: token đúng signature nhưng hết hạn → FE dùng refresh token.
     * INVALID: sai signature / malformed / null → FE phải login lại.
     * VALID:   token hợp lệ hoàn toàn.
     */
    public TokenValidationResult validateTokenDetailed(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return TokenValidationResult.VALID;
        } catch (ExpiredJwtException e) {
            log.debug("JWT token expired: {}", e.getMessage());
            return TokenValidationResult.EXPIRED;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return TokenValidationResult.INVALID;
        }
    }

    /**
     * Validate token: check signature + expiration.
     * Giữ method này để không break các test hiện tại.
     * Internally delegate sang validateTokenDetailed.
     */
    public boolean validateToken(String token) {
        return validateTokenDetailed(token) == TokenValidationResult.VALID;
    }

    /**
     * Generate access token với expiration tùy chỉnh — chỉ dùng trong unit/integration test.
     * Package-private để không expose ra ngoài package security.
     */
    String generateTokenWithExpiration(User user, long expirationMs) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                .claim("auth_method", "password")
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMs))
                .signWith(secretKey)
                .compact();
    }

    /**
     * Extract tất cả claims từ token (đã validate trước khi gọi method này).
     */
    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public UUID getUserIdFromToken(String token) {
        return UUID.fromString(getClaims(token).getSubject());
    }

    public String getJtiFromToken(String token) {
        return getClaims(token).getId();
    }
}
