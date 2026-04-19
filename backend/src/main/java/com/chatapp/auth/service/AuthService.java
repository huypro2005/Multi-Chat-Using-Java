package com.chatapp.auth.service;

import com.chatapp.auth.dto.request.LoginRequest;
import com.chatapp.auth.dto.request.RefreshRequest;
import com.chatapp.auth.dto.request.RegisterRequest;
import com.chatapp.auth.dto.response.AuthResponse;
import com.chatapp.auth.dto.response.UserDto;
import com.chatapp.exception.AppException;
import com.chatapp.security.JwtTokenProvider;
import com.chatapp.security.JwtTokenProvider.TokenValidationResult;
import com.chatapp.user.entity.User;
import com.chatapp.user.enums.AuthMethod;
import com.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Auth service — xử lý register và login.
 *
 * Security patterns (theo contract):
 * - INVALID_CREDENTIALS dùng cho cả "user not found" lẫn "wrong password" (tránh user enumeration).
 * - Rate limit login: Redis INCR key "rate:login:{ip}", TTL 900s, chặn sau >= 5 lần fail.
 * - Rate limit register: Redis INCR key "rate:register:{ip}", TTL 900s, chặn sau >= 10 req.
 * - Refresh token: lưu SHA-256 hash vào Redis, key "refresh:{userId}:{jti}", TTL 7 ngày.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

    /** TTL cho rate limit windows: 15 phút = 900 giây. */
    private static final long RATE_LIMIT_TTL_SECONDS = 900L;

    /** Max failed login attempts per IP per window. */
    private static final int LOGIN_MAX_FAILURES = 5;

    /** Max register requests per IP per window. */
    private static final int REGISTER_MAX_REQUESTS = 10;

    /** Max refresh calls per userId per window (60 giây). */
    private static final int REFRESH_MAX_CALLS = 10;

    /** TTL cho rate limit refresh window: 60 giây. */
    private static final long REFRESH_RATE_LIMIT_TTL_SECONDS = 60L;

    @Value("${app.jwt.refresh-expiration-ms}")
    private long refreshExpirationMs;

    // -------------------------------------------------------------------------
    // Register
    // -------------------------------------------------------------------------

    /**
     * Đăng ký tài khoản mới.
     * Flow: rate limit check → unique check → create user → generate tokens.
     *
     * @param req      validated request body
     * @param clientIp IP của client (từ X-Forwarded-For hoặc remoteAddr)
     * @return AuthResponse với accessToken, refreshToken, user info
     */
    @Transactional
    public AuthResponse register(RegisterRequest req, String clientIp) {
        // 1. Rate limit: 10 requests/15 phút/IP
        checkRegisterRateLimit(clientIp);

        // 2. Unique check — email trước, username sau (theo contract note)
        if (userRepository.existsByEmail(req.email())) {
            throw new AppException(HttpStatus.CONFLICT, "AUTH_EMAIL_TAKEN", "Email đã được đăng ký bởi tài khoản khác");
        }
        if (userRepository.existsByUsername(req.username())) {
            throw new AppException(HttpStatus.CONFLICT, "AUTH_USERNAME_TAKEN", "Username đã tồn tại trong hệ thống");
        }

        // 3. Tạo và lưu user entity
        User user = User.builder()
                .email(req.email())
                .username(req.username())
                .fullName(req.fullName())
                .passwordHash(passwordEncoder.encode(req.password()))
                .status("active")
                .build();
        user = userRepository.save(user);

        log.debug("User registered: username={}, email={}", user.getUsername(), user.getEmail());

        // 4. Generate tokens và trả về
        return buildAuthResponse(user, AuthMethod.PASSWORD);
    }

    // -------------------------------------------------------------------------
    // Login
    // -------------------------------------------------------------------------

    /**
     * Đăng nhập bằng username + password.
     * Flow: rate limit check → find user → verify password → check status → reset counter → generate tokens.
     *
     * Security: dùng cùng error code + message cho "user not found" và "wrong password"
     * để tránh user enumeration attack.
     *
     * @param req      validated request body
     * @param clientIp IP của client
     * @return AuthResponse với accessToken, refreshToken, user info
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req, String clientIp) {
        // 1. Rate limit check: 5 lần fail/15 phút/IP
        checkLoginRateLimit(clientIp);

        // 2. Find user — KHÔNG tiết lộ user có tồn tại hay không
        User user = userRepository.findByUsername(req.username()).orElse(null);

        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            // Tăng fail counter khi user not found hoặc wrong password
            incrementLoginFailCounter(clientIp);
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS",
                    "Tên đăng nhập hoặc mật khẩu không đúng");
        }

        // 3. Check account status — chỉ sau khi xác minh credentials
        if (!user.isActive()) {
            // Không tính vào fail counter vì credentials đúng
            throw new AppException(HttpStatus.FORBIDDEN, "AUTH_ACCOUNT_LOCKED",
                    "Tài khoản bị khóa bởi admin");
        }

        // 4. Reset fail counter khi login thành công
        redisTemplate.delete("rate:login:" + clientIp);

        log.debug("User logged in: username={}", user.getUsername());

        // 5. Generate tokens (cần @Transactional write để lưu refresh token vào Redis)
        return buildAuthResponse(user, AuthMethod.PASSWORD);
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    /**
     * Refresh token rotation: validate → check Redis → delete old → generate new.
     *
     * Security flow:
     * 1. validateTokenDetailed() — phân biệt EXPIRED vs INVALID vs VALID.
     * 2. Rate limit per-userId (10 calls/60s) — tránh brute-force rotation attack.
     * 3. Hash và compare constant-time (MessageDigest.isEqual) — tránh timing attack.
     * 4. Token reuse detection: nếu hash không khớp → revokeAllUserSessions().
     * 5. DELETE old token trước khi generate new — tránh race condition.
     *
     * Pitfall (V1): DELETE → crash → SAVE new token không xảy ra → user mất session.
     * Acceptable cho V1. Workaround V2: dùng Redis MULTI/EXEC (atomic).
     *
     * @param rawRefreshToken raw refresh token từ client
     * @param clientIp        IP của client (không dùng cho rate limit nhưng giữ để log nếu cần)
     * @return AuthResponse mới với access token và refresh token mới
     */
    @Transactional
    public AuthResponse refresh(String rawRefreshToken, String clientIp) {
        // a. Parse và validate token trước khi làm bất cứ điều gì khác
        TokenValidationResult result = jwtTokenProvider.validateTokenDetailed(rawRefreshToken);

        if (result == TokenValidationResult.INVALID) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID",
                    "Refresh token không hợp lệ");
        }
        if (result == TokenValidationResult.EXPIRED) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_EXPIRED",
                    "Refresh token đã hết hạn, vui lòng đăng nhập lại");
        }

        // b. Extract claims — token đã VALID nên getClaims() sẽ không throw
        UUID userId = jwtTokenProvider.getUserIdFromToken(rawRefreshToken);
        String jti = jwtTokenProvider.getJtiFromToken(rawRefreshToken);

        // c. Rate limit per-userId: 10 calls/60s
        String rateLimitKey = "rate:refresh:" + userId;
        String callCountStr = redisTemplate.opsForValue().get(rateLimitKey);
        if (callCountStr != null) {
            int callCount;
            try {
                callCount = Integer.parseInt(callCountStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid rate limit value in Redis for key {}: {}", rateLimitKey, callCountStr);
                callCount = 0;
            }
            if (callCount >= REFRESH_MAX_CALLS) {
                Long ttl = redisTemplate.getExpire(rateLimitKey, TimeUnit.SECONDS);
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Vượt giới hạn refresh, thử lại sau "
                                + (ttl != null && ttl > 0 ? ttl : REFRESH_RATE_LIMIT_TTL_SECONDS) + " giây");
            }
        }
        // Increment counter — set TTL khi lần đầu
        Long currentCount = redisTemplate.opsForValue().increment(rateLimitKey);
        if (currentCount != null && currentCount == 1L) {
            redisTemplate.expire(rateLimitKey, REFRESH_RATE_LIMIT_TTL_SECONDS, TimeUnit.SECONDS);
        }

        // d. Hash token và compare với giá trị trong Redis (constant-time để tránh timing attack)
        String redisKey = "refresh:" + userId + ":" + jti;
        String storedHash = redisTemplate.opsForValue().get(redisKey);
        String expectedHash = hashToken(rawRefreshToken);

        if (storedHash == null || !constantTimeEquals(storedHash, expectedHash)) {
            // Token reuse detected hoặc token đã bị revoke — security alert
            log.warn("[SECURITY] Refresh token reuse/invalid detected for userId={}, jti={}. Revoking all sessions.", userId, jti);
            revokeAllUserSessions(userId);
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID",
                    "Refresh token đã được sử dụng trước đó hoặc không còn hợp lệ");
        }

        // e. Load user — nếu không tồn tại thì token là giả
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "AUTH_REFRESH_TOKEN_INVALID",
                        "Refresh token không hợp lệ"));

        // f. Check account status SAU khi xác minh token hợp lệ
        if (!user.isActive()) {
            // Revoke token nhưng KHÔNG revoke all sessions — admin có thể muốn giữ log
            redisTemplate.delete(redisKey);
            throw new AppException(HttpStatus.FORBIDDEN, "AUTH_ACCOUNT_LOCKED",
                    "Tài khoản bị khóa bởi admin");
        }

        // g. DELETE old refresh token TRƯỚC khi generate new (ngăn reuse nếu crash sau đây)
        redisTemplate.delete(redisKey);

        // h. Generate new tokens (buildAuthResponse tự lưu refresh token mới vào Redis)
        log.debug("Refresh token rotated for userId={}", userId);
        return buildAuthResponse(user, AuthMethod.PASSWORD);
    }

    /**
     * Revoke tất cả refresh sessions của một user — dùng khi detect token reuse attack.
     * Dùng redisTemplate.keys() thay vì KEYS command trực tiếp.
     *
     * Cảnh báo: keys() dùng O(N) scan — OK cho V1 vì user thường có ít sessions.
     * V2: nên dùng SCAN iterator hoặc maintain user→sessions index.
     */
    private void revokeAllUserSessions(UUID userId) {
        String pattern = "refresh:" + userId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
            log.warn("[SECURITY] Revoked {} session(s) for userId={}", keys.size(), userId);
        } else {
            log.warn("[SECURITY] No active sessions found to revoke for userId={}", userId);
        }
    }

    /**
     * Constant-time string comparison để tránh timing attack khi so sánh token hash.
     * String.equals() có thể short-circuit khi tìm thấy ký tự khác — attacker có thể đo thời gian.
     * MessageDigest.isEqual() luôn so sánh toàn bộ byte array.
     */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Build AuthResponse: generate access + refresh token, lưu hash refresh token vào Redis.
     */
    private AuthResponse buildAuthResponse(User user, AuthMethod authMethod) {
        String accessToken = jwtTokenProvider.generateAccessToken(user, authMethod);
        String refreshToken = jwtTokenProvider.generateRefreshToken(user);

        // Lưu SHA-256 hash của refresh token vào Redis (không lưu raw token)
        String jti = jwtTokenProvider.getJtiFromToken(refreshToken);
        String tokenHash = hashToken(refreshToken);
        String redisKey = "refresh:" + user.getId() + ":" + jti;
        long ttlSeconds = refreshExpirationMs / 1000;
        redisTemplate.opsForValue().set(redisKey, tokenHash, ttlSeconds, TimeUnit.SECONDS);

        return new AuthResponse(
                accessToken,
                refreshToken,
                "Bearer",
                3600,
                UserDto.from(user)
        );
    }

    /**
     * Rate limit check cho register: 10 requests/15 phút/IP.
     * Mọi request đều tính vào quota (không chỉ thất bại).
     */
    private void checkRegisterRateLimit(String clientIp) {
        String key = "rate:register:" + clientIp;
        Long current = redisTemplate.opsForValue().increment(key);
        if (current == 1L) {
            // Lần đầu tiên — set TTL
            redisTemplate.expire(key, RATE_LIMIT_TTL_SECONDS, TimeUnit.SECONDS);
        }
        if (current != null && current > REGISTER_MAX_REQUESTS) {
            Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Quá nhiều yêu cầu, thử lại sau " + (ttl != null && ttl > 0 ? ttl : RATE_LIMIT_TTL_SECONDS) + " giây");
        }
    }

    /**
     * Rate limit check cho login: chặn nếu đã có >= 5 lần fail từ IP này.
     * KHÔNG increment ở đây — chỉ check. Increment gọi riêng khi thất bại.
     */
    private void checkLoginRateLimit(String clientIp) {
        String key = "rate:login:" + clientIp;
        String failCountStr = redisTemplate.opsForValue().get(key);
        if (failCountStr != null) {
            int failCount;
            try {
                failCount = Integer.parseInt(failCountStr);
            } catch (NumberFormatException e) {
                log.warn("Invalid rate limit value in Redis for key {}: {}", key, failCountStr);
                return;
            }
            if (failCount >= LOGIN_MAX_FAILURES) {
                Long ttl = redisTemplate.getExpire(key, TimeUnit.SECONDS);
                throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                        "Quá nhiều lần đăng nhập thất bại, thử lại sau "
                                + (ttl != null && ttl > 0 ? ttl : RATE_LIMIT_TTL_SECONDS) + " giây");
            }
        }
    }

    /**
     * Tăng fail counter cho login rate limit.
     * Gọi khi credentials sai. Counter tự expire sau 15 phút.
     */
    private void incrementLoginFailCounter(String clientIp) {
        String key = "rate:login:" + clientIp;
        Long current = redisTemplate.opsForValue().increment(key);
        if (current == 1L) {
            // Lần fail đầu tiên — set TTL
            redisTemplate.expire(key, RATE_LIMIT_TTL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Hash token bằng SHA-256 để lưu vào Redis thay vì raw token.
     * An toàn hơn: nếu Redis bị compromise, attacker không dùng được hash để forge token.
     */
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 luôn available trong JVM — nếu thiếu thì JVM bị lỗi nghiêm trọng
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
