package com.chatapp.auth.service;

import com.chatapp.auth.dto.request.LoginRequest;
import com.chatapp.auth.dto.request.RegisterRequest;
import com.chatapp.auth.dto.response.AuthResponse;
import com.chatapp.auth.dto.response.UserDto;
import com.chatapp.exception.AppException;
import com.chatapp.security.JwtTokenProvider;
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
import java.util.Base64;
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
