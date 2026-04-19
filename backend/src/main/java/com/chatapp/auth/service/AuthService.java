package com.chatapp.auth.service;

import com.chatapp.auth.dto.request.LoginRequest;
import com.chatapp.auth.dto.request.RefreshRequest;
import com.chatapp.auth.dto.request.RegisterRequest;
import com.chatapp.auth.dto.response.AuthResponse;
import com.chatapp.auth.dto.response.OAuthResponse;
import com.chatapp.auth.dto.response.UserDto;
import com.chatapp.exception.AppException;
import com.chatapp.security.JwtTokenProvider;
import com.chatapp.security.JwtTokenProvider.TokenValidationResult;
import com.chatapp.user.entity.User;
import com.chatapp.user.entity.UserAuthProvider;
import com.chatapp.user.enums.AuthMethod;
import com.chatapp.user.repository.UserAuthProviderRepository;
import com.chatapp.user.repository.UserRepository;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.Base64;
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
public class AuthService {

    private final UserRepository userRepository;
    private final UserAuthProviderRepository userAuthProviderRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;

    /**
     * FirebaseAuth bean — nullable vì FirebaseConfig có thể không init SDK
     * (khi FIREBASE_CREDENTIALS_PATH không set hoặc file không tồn tại).
     * @Autowired(required=false) để Spring không fail khi bean FirebaseAuth không có.
     */
    @Nullable
    private FirebaseAuth firebaseAuth;

    public AuthService(
            UserRepository userRepository,
            UserAuthProviderRepository userAuthProviderRepository,
            PasswordEncoder passwordEncoder,
            JwtTokenProvider jwtTokenProvider,
            StringRedisTemplate redisTemplate) {
        this.userRepository = userRepository;
        this.userAuthProviderRepository = userAuthProviderRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.redisTemplate = redisTemplate;
    }

    @Autowired(required = false)
    public void setFirebaseAuth(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
    }

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

        // OAuth-only users có passwordHash = null — treat như wrong password (không leak)
        if (user == null || user.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
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

    // -------------------------------------------------------------------------
    // OAuth (Google via Firebase)
    // -------------------------------------------------------------------------

    /**
     * Đăng nhập / đăng ký bằng Google OAuth thông qua Firebase ID Token.
     *
     * Auto-link logic (theo contract):
     * 1. Tìm theo provider_uid → returning OAuth user → phát token.
     * 2. Tìm theo email → user đã đăng ký password → auto-link provider, phát token.
     * 3. Không tìm thấy → tạo user mới, isNewUser = true.
     *
     * @param firebaseIdToken token JWT phát bởi Firebase Auth phía client
     * @param clientIp        IP của client để rate limit (không implement limit trong method này)
     * @return OAuthResponse kèm isNewUser flag
     */
    @Transactional
    public OAuthResponse oauth(String firebaseIdToken, String clientIp) {
        // 1. Verify Firebase token
        if (firebaseAuth == null) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_FIREBASE_UNAVAILABLE",
                    "Firebase Admin SDK chưa được khởi tạo — OAuth endpoint không khả dụng");
        }

        FirebaseToken firebaseToken;
        try {
            firebaseToken = firebaseAuth.verifyIdToken(firebaseIdToken);
        } catch (FirebaseAuthException e) {
            String code = e.getAuthErrorCode() != null ? e.getAuthErrorCode().name() : "";
            if (code.contains("EXPIRED") || code.contains("TOKEN_EXPIRED")) {
                throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_FIREBASE_TOKEN_INVALID",
                        "Firebase token đã hết hạn");
            }
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_FIREBASE_TOKEN_INVALID",
                    "Firebase token không hợp lệ");
        }

        // 2. Extract claims
        String providerUid = firebaseToken.getUid();
        String email = firebaseToken.getEmail();
        String displayName = firebaseToken.getName();
        String photoUrl = firebaseToken.getPicture();

        if (email == null || email.isBlank()) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "AUTH_FIREBASE_TOKEN_INVALID",
                    "Không thể xác minh email từ Firebase token");
        }

        // 3. Check by provider_uid (returning OAuth user)
        Optional<UserAuthProvider> existingProvider =
                userAuthProviderRepository.findByProviderAndProviderUid("google", providerUid);
        if (existingProvider.isPresent()) {
            User user = existingProvider.get().getUser();
            if (!user.isActive()) {
                throw new AppException(HttpStatus.FORBIDDEN, "AUTH_ACCOUNT_LOCKED",
                        "Tài khoản bị khóa bởi admin");
            }
            AuthResponse authResp = buildAuthResponse(user, AuthMethod.OAUTH2_GOOGLE);
            return toOAuthResponse(authResp, false);
        }

        // 4. Check by email (auto-link existing password user)
        Optional<User> existingUser = userRepository.findByEmail(email);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (!user.isActive()) {
                throw new AppException(HttpStatus.FORBIDDEN, "AUTH_ACCOUNT_LOCKED",
                        "Tài khoản bị khóa bởi admin");
            }
            // Auto-link Google provider
            UserAuthProvider newProvider = UserAuthProvider.builder()
                    .user(user)
                    .provider("google")
                    .providerUid(providerUid)
                    .build();
            userAuthProviderRepository.save(newProvider);
            log.info("[OAuth] Auto-linked Google provider to existing userId={}", user.getId());
            AuthResponse authResp = buildAuthResponse(user, AuthMethod.OAUTH2_GOOGLE);
            return toOAuthResponse(authResp, false);
        }

        // 5. Create new user
        String username = generateUniqueUsername(email, displayName);
        User newUser = User.builder()
                .email(email)
                .username(username)
                .fullName(displayName != null && !displayName.isBlank()
                        ? displayName : email.split("@")[0])
                .avatarUrl(photoUrl)
                .status("active")
                .build();
        newUser = userRepository.save(newUser);

        UserAuthProvider provider = UserAuthProvider.builder()
                .user(newUser)
                .provider("google")
                .providerUid(providerUid)
                .build();
        userAuthProviderRepository.save(provider);

        log.info("[OAuth] Created new user userId={}, username={}", newUser.getId(), newUser.getUsername());
        AuthResponse authResp = buildAuthResponse(newUser, AuthMethod.OAUTH2_GOOGLE);
        return toOAuthResponse(authResp, true);
    }

    /**
     * Convert AuthResponse → OAuthResponse với isNewUser flag.
     */
    private OAuthResponse toOAuthResponse(AuthResponse base, boolean isNewUser) {
        return new OAuthResponse(
                base.accessToken(),
                base.refreshToken(),
                base.tokenType(),
                base.expiresIn(),
                isNewUser,
                base.user()
        );
    }

    /**
     * Tạo username unique từ email prefix.
     * - Sanitize: chỉ giữ [a-z0-9_], chuyển về lowercase.
     * - Đảm bảo không bắt đầu bằng số (prefix "_").
     * - Thử tối đa 5 lần với suffix ngẫu nhiên 4 số, sau đó fallback UUID.
     */
    private String generateUniqueUsername(String email, String displayName) {
        String base = email.split("@")[0]
                .replaceAll("[^a-zA-Z0-9_]", "_")
                .toLowerCase();

        // Đảm bảo đủ dài tối thiểu
        if (base.length() < 3) {
            base = base + "_usr";
        }
        // Cắt tối đa 40 ký tự (để chừa chỗ cho suffix)
        base = base.substring(0, Math.min(base.length(), 40));
        // Đảm bảo không bắt đầu bằng số
        if (!base.isEmpty() && Character.isDigit(base.charAt(0))) {
            base = "_" + base;
        }

        // Thử không suffix trước
        if (!userRepository.existsByUsername(base)) {
            return base;
        }
        // Thử 4 lần với suffix ngẫu nhiên
        Random random = new Random();
        for (int i = 0; i < 4; i++) {
            String candidate = base + "_" + (1000 + random.nextInt(9000));
            if (!userRepository.existsByUsername(candidate)) {
                return candidate;
            }
        }
        // Ultimate fallback: UUID suffix 8 ký tự (cực kỳ hiếm xảy ra)
        return base + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // -------------------------------------------------------------------------
    // Logout
    // -------------------------------------------------------------------------

    /**
     * Đăng xuất phiên hiện tại — best-effort delete refresh token + blacklist access token.
     *
     * Không ném exception nếu refresh token không hợp lệ/không tồn tại trong Redis
     * — logout luôn trả 200 miễn là access token valid (đã verify ở filter trước đó).
     *
     * Side effects:
     * 1. DELETE "refresh:{userId}:{refreshJti}" khỏi Redis.
     * 2. SET "jwt:blacklist:{accessJti}" "" EX {remaining_ttl} — chặn tái sử dụng access token.
     *
     * @param rawRefreshToken raw refresh token từ client body
     * @param userId          userId từ security context (đã verify JWT)
     * @param accessTokenJti  jti của access token đang dùng
     * @param accessTokenRemainingMs thời gian còn lại của access token tính bằng ms
     */
    public void logout(String rawRefreshToken, UUID userId, String accessTokenJti, long accessTokenRemainingMs) {
        // 1. Best-effort: delete refresh token từ Redis
        try {
            TokenValidationResult result = jwtTokenProvider.validateTokenDetailed(rawRefreshToken);
            if (result != TokenValidationResult.INVALID) {
                String jti = jwtTokenProvider.getJtiFromToken(rawRefreshToken);
                String key = "refresh:" + userId + ":" + jti;
                redisTemplate.delete(key);
                log.debug("[Logout] Deleted refresh token key={} for userId={}", key, userId);
            } else {
                log.warn("[Logout] Refresh token invalid/malformed for userId={} — skipping Redis delete", userId);
            }
        } catch (Exception e) {
            // Best-effort: không block logout nếu refresh token operation fail
            log.warn("[Logout] Could not delete refresh token for userId={}: {}", userId, e.getMessage());
        }

        // 2. Blacklist access token (nếu còn thời gian sống)
        if (accessTokenRemainingMs > 0) {
            long ttlSeconds = accessTokenRemainingMs / 1000;
            if (ttlSeconds > 0) {
                redisTemplate.opsForValue().set(
                        "jwt:blacklist:" + accessTokenJti,
                        "",
                        ttlSeconds,
                        TimeUnit.SECONDS
                );
                log.debug("[Logout] Blacklisted access token jti={} TTL={}s", accessTokenJti, ttlSeconds);
            }
        }
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
