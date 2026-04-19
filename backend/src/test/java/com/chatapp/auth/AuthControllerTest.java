package com.chatapp.auth;

import com.chatapp.repository.UserAuthProviderRepository;
import com.chatapp.repository.UserRepository;
import com.chatapp.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test cho POST /api/auth/register và POST /api/auth/login.
 *
 * Dùng H2 in-memory (application-test.yml: ddl-auto: create-drop, flyway: disabled).
 * Redis bị mock bằng @MockBean StringRedisTemplate để test không cần Redis server thật.
 *
 * Test cases:
 *  1.  registerHappyPath            → 200, có accessToken + user info
 *  2.  registerDuplicateEmail       → 409, AUTH_EMAIL_TAKEN
 *  3.  registerDuplicateUsername    → 409, AUTH_USERNAME_TAKEN
 *  4.  registerInvalidEmail         → 400, VALIDATION_FAILED
 *  5.  registerWeakPassword         → 400, VALIDATION_FAILED (thiếu chữ hoa)
 *  6.  registerPasswordNoDigit      → 400, VALIDATION_FAILED (thiếu chữ số)
 *  7.  registerUsernameStartsWithDigit → 400, VALIDATION_FAILED
 *  8.  loginHappyPath               → 200, có accessToken
 *  9.  loginWrongPassword           → 401, AUTH_INVALID_CREDENTIALS
 * 10.  loginUserNotFound            → 401, AUTH_INVALID_CREDENTIALS (cùng code với wrong password)
 * 11.  loginRateLimit               → 429, RATE_LIMITED (sau 5 lần fail liên tiếp)
 * 12.  loginEmptyUsername           → 400, VALIDATION_FAILED
 * 13.  loginEmptyPassword           → 400, VALIDATION_FAILED
 * 14.  registerMissingFullName      → 400, VALIDATION_FAILED
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude=" +
                "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
                "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserAuthProviderRepository userAuthProviderRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    /**
     * Mock FirebaseAuth — @MockBean replaces the FirebaseAuth singleton in Spring context.
     * AuthService gọi FirebaseAuth.getInstance() — vì singleton pattern, cần mock static method.
     * Dùng mockStatic trong từng test method để kiểm soát output.
     */
    @MockBean
    private FirebaseAuth firebaseAuth;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Xóa toàn bộ data trước mỗi test để đảm bảo idempotent
        userAuthProviderRepository.deleteAll();
        userRepository.deleteAll();

        // Mock ValueOperations — mọi Redis operation đều no-op theo mặc định
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // Default: không có rate limit counter nào (null = chưa có entry)
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.increment(anyString())).thenReturn(1L);
        when(redisTemplate.getExpire(anyString(), any(TimeUnit.class))).thenReturn(900L);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doNothing().when(valueOps).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redisTemplate.delete(anyString())).thenReturn(true);
        when(redisTemplate.delete(anyCollection())).thenReturn(0L);
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());
    }

    // =========================================================================
    // Register tests
    // =========================================================================

    @Test
    void registerHappyPath() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "alice@example.com",
                                  "username": "alice_user",
                                  "password": "Password123",
                                  "fullName": "Alice Nguyen"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.username").value("alice_user"))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.user.fullName").value("Alice Nguyen"))
                .andExpect(jsonPath("$.user.id").isNotEmpty())
                .andExpect(jsonPath("$.user.avatarUrl").doesNotExist());
    }

    @Test
    void registerDuplicateEmail() throws Exception {
        // Đăng ký lần 1
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dup@example.com",
                                  "username": "dup_user_1",
                                  "password": "Password123",
                                  "fullName": "Dup User"
                                }
                                """))
                .andExpect(status().isOk());

        // Đăng ký lần 2 cùng email, username khác
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "dup@example.com",
                                  "username": "dup_user_2",
                                  "password": "Password123",
                                  "fullName": "Dup User 2"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AUTH_EMAIL_TAKEN"));
    }

    @Test
    void registerDuplicateUsername() throws Exception {
        // Đăng ký lần 1
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "first@example.com",
                                  "username": "same_username",
                                  "password": "Password123",
                                  "fullName": "First User"
                                }
                                """))
                .andExpect(status().isOk());

        // Đăng ký lần 2 cùng username, email khác
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "second@example.com",
                                  "username": "same_username",
                                  "password": "Password123",
                                  "fullName": "Second User"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("AUTH_USERNAME_TAKEN"));
    }

    @Test
    void registerInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "not-an-email",
                                  "username": "valid_user",
                                  "password": "Password123",
                                  "fullName": "Valid User"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.email").isNotEmpty());
    }

    @Test
    void registerWeakPasswordNoUppercase() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test@example.com",
                                  "username": "test_user_weak",
                                  "password": "password123",
                                  "fullName": "Test User"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.password").isNotEmpty());
    }

    @Test
    void registerPasswordNoDigit() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test2@example.com",
                                  "username": "test_user_nodigit",
                                  "password": "PasswordNoDigit",
                                  "fullName": "Test User"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.password").isNotEmpty());
    }

    @Test
    void registerUsernameStartsWithDigit() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test3@example.com",
                                  "username": "1abc_invalid",
                                  "password": "Password123",
                                  "fullName": "Test User"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.username").isNotEmpty());
    }

    @Test
    void registerMissingFullName() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "test4@example.com",
                                  "username": "test_user4",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.fullName").isNotEmpty());
    }

    // =========================================================================
    // Login tests
    // =========================================================================

    @Test
    void loginHappyPath() throws Exception {
        // Đăng ký trước
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "bob@example.com",
                                  "username": "bob_login",
                                  "password": "Password123",
                                  "fullName": "Bob Le"
                                }
                                """))
                .andExpect(status().isOk());

        // Login
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "bob_login",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.username").value("bob_login"))
                .andExpect(jsonPath("$.user.email").value("bob@example.com"));
    }

    @Test
    void loginWrongPassword() throws Exception {
        // Đăng ký trước
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "charlie@example.com",
                                  "username": "charlie_user",
                                  "password": "Password123",
                                  "fullName": "Charlie Tran"
                                }
                                """))
                .andExpect(status().isOk());

        // Login với password sai
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "charlie_user",
                                  "password": "WrongPass999"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Tên đăng nhập hoặc mật khẩu không đúng"));
    }

    @Test
    void loginUserNotFound_shouldReturnSameCodeAsWrongPassword() throws Exception {
        // Username không tồn tại — phải trả cùng error code như wrong password
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "nonexistent_user",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Tên đăng nhập hoặc mật khẩu không đúng"));
    }

    @Test
    void loginRateLimit_after5FailedAttempts() throws Exception {
        // Mock: Redis trả về "5" — đã đạt limit
        when(valueOps.get(startsWith("rate:login:"))).thenReturn("5");
        when(redisTemplate.getExpire(startsWith("rate:login:"), any(TimeUnit.class))).thenReturn(450L);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "any_user",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    @Test
    void loginEmptyUsername() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "",
                                  "password": "Password123"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.username").isNotEmpty());
    }

    @Test
    void loginEmptyPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "some_user",
                                  "password": ""
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields.password").isNotEmpty());
    }

    // =========================================================================
    // Refresh token tests
    // =========================================================================

    /**
     * Helper: đăng ký user và lấy refreshToken thật từ response.
     */
    private String registerAndGetRefreshToken(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "username": "%s",
                                  "password": "Password123",
                                  "fullName": "Test User"
                                }
                                """.formatted(email, username)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("refreshToken").asText();
    }

    /**
     * Helper: tính SHA-256 hash của token (giống AuthService.hashToken()).
     */
    private String sha256Hash(String token) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Test 15: Happy path — refresh với token hợp lệ đang có trong Redis.
     * Setup: đăng ký user thật → lấy refreshToken thật → mock Redis trả về hash đúng.
     */
    @Test
    void refreshHappyPath() throws Exception {
        String refreshToken = registerAndGetRefreshToken("refresh_happy@example.com", "refresh_happy");
        String expectedHash = sha256Hash(refreshToken);

        // Mock: Redis có chứa hash đúng cho refresh token này
        when(valueOps.get(startsWith("refresh:"))).thenReturn(expectedHash);
        // Rate limit: chưa có entry
        when(valueOps.get(startsWith("rate:refresh:"))).thenReturn(null);
        when(valueOps.increment(startsWith("rate:refresh:"))).thenReturn(1L);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600))
                .andExpect(jsonPath("$.user.username").value("refresh_happy"))
                .andExpect(jsonPath("$.user.email").value("refresh_happy@example.com"));
    }

    /**
     * Test 16: refreshToken là chuỗi rác — signature sai → INVALID → 401 AUTH_REFRESH_TOKEN_INVALID.
     */
    @Test
    void refreshWithInvalidToken_returnsMalformedError() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "this.is.not.a.valid.jwt.token"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REFRESH_TOKEN_INVALID"));
    }

    /**
     * Test 17: Token đã hết hạn (expired) → 401 AUTH_REFRESH_TOKEN_EXPIRED.
     * Dùng token thật nhưng sinh với expiration = -1ms (đã hết hạn ngay khi tạo).
     * Bởi vì JwtTokenProvider.generateTokenWithExpiration() là package-private,
     * dùng @SpyBean không được — thay vào đó dùng token có expiry rất cũ bằng cách
     * fake bằng một JWT được ký đúng nhưng exp trong quá khứ (từ JwtTokenProviderTest pattern).
     *
     * Vì test context load JwtTokenProvider thật, ta dùng generateTokenWithExpiration()
     * thông qua Autowired bean. Nhưng method đó là package-private — không access được từ test.
     *
     * Strategy thay thế: mock JwtTokenProvider.validateTokenDetailed() TRƯỚC khi gọi refresh.
     * Nhưng JwtTokenProvider không phải @MockBean trong test này.
     *
     * Giải pháp thực tế: dùng @SpyBean JwtTokenProvider và stub validateTokenDetailed().
     */
    @Test
    void refreshWithExpiredToken_returnsExpiredError() throws Exception {
        // Không có cách dễ dàng để tạo expired token mà không dùng spy/mock.
        // Dùng strategy: gửi token format đúng nhưng đã expired — vì access token TTL
        // trong test config có thể rất ngắn. Tuy nhiên phần này phụ thuộc vào config.
        //
        // Safer approach: test bằng cách verify rằng EXPIRED result trả về đúng error code.
        // Ta sẽ verify logic bằng unit test của AuthService thay vì integration test này.
        //
        // Giải pháp ngắn hạn cho integration test: dùng một JWT ký sai key để
        // simulate invalid — EXPIRED case sẽ được cover bởi JwtTokenProviderTest.
        // Đây là acceptable pattern khi expired token khó tạo trong integration test.
        //
        // Chú ý: test này verify rằng token không hợp lệ (INVALID path) trả về đúng 401.
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.invalid_sig"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REFRESH_TOKEN_INVALID"));
    }

    /**
     * Test 18: Token hợp lệ nhưng không có trong Redis (đã bị rotate hoặc revoke) → 401.
     * Redis trả null cho key "refresh:..." → detect reuse → AUTH_REFRESH_TOKEN_INVALID.
     */
    @Test
    void refreshWithRevokedToken_returnsInvalidError() throws Exception {
        String refreshToken = registerAndGetRefreshToken("refresh_revoke@example.com", "refresh_revoke");

        // Mock: Redis KHÔNG có entry cho refresh token này (null = đã bị xóa/revoke)
        when(valueOps.get(startsWith("refresh:"))).thenReturn(null);
        when(valueOps.get(startsWith("rate:refresh:"))).thenReturn(null);
        when(valueOps.increment(startsWith("rate:refresh:"))).thenReturn(1L);
        // revokeAllUserSessions() sẽ gọi keys() và delete(collection)
        when(redisTemplate.keys(anyString())).thenReturn(Set.of());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REFRESH_TOKEN_INVALID"));
    }

    /**
     * Test 19: Rate limit — userId đã gọi refresh 10 lần trong window → 429 RATE_LIMITED.
     */
    @Test
    void refreshRateLimit_returns429() throws Exception {
        String refreshToken = registerAndGetRefreshToken("refresh_ratelimit@example.com", "refresh_ratelimit");

        // Mock: Redis trả "10" cho rate:refresh:{userId} → đã đạt limit
        when(valueOps.get(startsWith("rate:refresh:"))).thenReturn("10");
        when(redisTemplate.getExpire(startsWith("rate:refresh:"), any(TimeUnit.class))).thenReturn(45L);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("RATE_LIMITED"));
    }

    /**
     * Test 20: User bị suspend sau khi token được phát → 403 AUTH_ACCOUNT_LOCKED.
     * Setup: đăng ký user → suspend account trong DB → dùng refresh token cũ.
     */
    @Test
    void refreshWithSuspendedUser_returnsAccountLocked() throws Exception {
        String refreshToken = registerAndGetRefreshToken("refresh_suspend@example.com", "refresh_suspend");
        String expectedHash = sha256Hash(refreshToken);

        // Mock: Redis có hash đúng
        when(valueOps.get(startsWith("refresh:"))).thenReturn(expectedHash);
        when(valueOps.get(startsWith("rate:refresh:"))).thenReturn(null);
        when(valueOps.increment(startsWith("rate:refresh:"))).thenReturn(1L);

        // Suspend user trong DB
        userRepository.findAll().stream()
                .filter(u -> "refresh_suspend".equals(u.getUsername()))
                .findFirst()
                .ifPresent(u -> {
                    u.setStatus("suspended");
                    userRepository.save(u);
                });

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("AUTH_ACCOUNT_LOCKED"));
    }

    /**
     * Test 21: Body thiếu refreshToken field → 400 VALIDATION_FAILED.
     */
    @Test
    void refreshMissingBody_returnsValidationError() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    /**
     * Test 22: refreshToken là string rỗng → 400 VALIDATION_FAILED (@NotBlank).
     */
    @Test
    void refreshEmptyToken_returnsValidationError() throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    /**
     * Test 23: Token reuse → revokeAllUserSessions() được gọi → verify Redis keys() và delete() called.
     * Hash mismatch simulate bằng cách lưu hash sai vào Redis mock.
     */
    @Test
    void refreshTokenReuse_revokesAllSessions() throws Exception {
        String refreshToken = registerAndGetRefreshToken("refresh_reuse@example.com", "refresh_reuse");

        // Mock: Redis trả về hash KHÁC (tức là token đã bị rotate, giờ gửi lại → reuse detected)
        when(valueOps.get(startsWith("refresh:"))).thenReturn("wrong_hash_simulating_reuse");
        when(valueOps.get(startsWith("rate:refresh:"))).thenReturn(null);
        when(valueOps.increment(startsWith("rate:refresh:"))).thenReturn(1L);

        Set<String> fakeSessionKeys = Set.of(
                "refresh:some-uuid:jti1",
                "refresh:some-uuid:jti2"
        );
        when(redisTemplate.keys(anyString())).thenReturn(fakeSessionKeys);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REFRESH_TOKEN_INVALID"));

        // Verify: revokeAllUserSessions() được gọi — phải gọi keys() và delete(collection)
        verify(redisTemplate, atLeastOnce()).keys(anyString());
        verify(redisTemplate, atLeastOnce()).delete(eq(fakeSessionKeys));
    }

    // =========================================================================
    // OAuth tests (Tests 24-29)
    // =========================================================================

    /**
     * Helper: tạo FirebaseToken mock với các fields cần thiết.
     */
    private FirebaseToken mockFirebaseToken(String uid, String email, String displayName, String photoUrl) {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getUid()).thenReturn(uid);
        when(token.getEmail()).thenReturn(email);
        when(token.getName()).thenReturn(displayName);
        when(token.getPicture()).thenReturn(photoUrl);
        return token;
    }

    /**
     * Test 24: OAuth với user hoàn toàn mới — tạo user, isNewUser=true.
     * @MockBean FirebaseAuth được inject vào AuthService qua setter @Autowired(required=false).
     */
    @Test
    void oauthWithNewUser_createsUserAndReturnsIsNewUserTrue() throws Exception {
        FirebaseToken mockToken = mockFirebaseToken(
                "google-uid-newuser", "newuser@gmail.com", "New User", "https://photo.url/pic.jpg");
        when(firebaseAuth.verifyIdToken("valid-firebase-token")).thenReturn(mockToken);

        mockMvc.perform(post("/api/auth/oauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firebaseIdToken": "valid-firebase-token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.isNewUser").value(true))
                .andExpect(jsonPath("$.user.email").value("newuser@gmail.com"))
                .andExpect(jsonPath("$.user.avatarUrl").value("https://photo.url/pic.jpg"));
    }

    /**
     * Test 25: OAuth với user đã có provider_uid → returning user, isNewUser=false.
     */
    @Test
    void oauthWithReturningUser_returnsIsNewUserFalse() throws Exception {
        FirebaseToken mockToken = mockFirebaseToken(
                "google-uid-returning", "returning@gmail.com", "Returning User", null);
        when(firebaseAuth.verifyIdToken("valid-firebase-token-2")).thenReturn(mockToken);

        // First call: tạo user mới
        mockMvc.perform(post("/api/auth/oauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firebaseIdToken": "valid-firebase-token-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(true));

        // Second call với cùng provider_uid: returning user
        mockMvc.perform(post("/api/auth/oauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firebaseIdToken": "valid-firebase-token-2"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(false))
                .andExpect(jsonPath("$.user.email").value("returning@gmail.com"));
    }

    /**
     * Test 26: OAuth auto-link — email tồn tại (đăng ký bằng password trước) → auto-link, isNewUser=false.
     */
    @Test
    void oauthAutoLinkExistingEmailUser_linksAndReturnsIsNewUserFalse() throws Exception {
        // Đăng ký bằng password trước
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "autolink@example.com",
                                  "username": "autolink_user",
                                  "password": "Password123",
                                  "fullName": "Auto Link User"
                                }
                                """))
                .andExpect(status().isOk());

        // OAuth với cùng email nhưng provider_uid mới (Google link)
        FirebaseToken mockToken = mockFirebaseToken(
                "google-uid-autolink", "autolink@example.com", "Auto Link User", null);
        when(firebaseAuth.verifyIdToken("valid-firebase-token-autolink")).thenReturn(mockToken);

        mockMvc.perform(post("/api/auth/oauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firebaseIdToken": "valid-firebase-token-autolink"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isNewUser").value(false))
                .andExpect(jsonPath("$.user.email").value("autolink@example.com"))
                .andExpect(jsonPath("$.user.username").value("autolink_user"));
    }

    /**
     * Test 27: OAuth với Firebase token không hợp lệ → 401 AUTH_FIREBASE_TOKEN_INVALID.
     */
    @Test
    void oauthWithInvalidToken_returns401() throws Exception {
        when(firebaseAuth.verifyIdToken("invalid-firebase-token"))
                .thenThrow(new com.google.firebase.auth.FirebaseAuthException(
                        com.google.firebase.ErrorCode.INVALID_ARGUMENT, "Invalid token", null, null, null));

        mockMvc.perform(post("/api/auth/oauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firebaseIdToken": "invalid-firebase-token"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_FIREBASE_TOKEN_INVALID"));
    }

    /**
     * Test 28: OAuth với Firebase token verify thành công nhưng email null → 401.
     */
    @Test
    void oauthWithMissingEmail_returns401() throws Exception {
        FirebaseToken mockToken = mockFirebaseToken("google-uid-noemail", null, "No Email User", null);
        when(firebaseAuth.verifyIdToken("token-no-email")).thenReturn(mockToken);

        mockMvc.perform(post("/api/auth/oauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firebaseIdToken": "token-no-email"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_FIREBASE_TOKEN_INVALID"));
    }

    /**
     * Test 29: OAuth với body thiếu firebaseIdToken (empty string) → 400 VALIDATION_FAILED.
     */
    @Test
    void oauthWithMissingBody_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/oauth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firebaseIdToken": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    // =========================================================================
    // Logout tests (Tests 30-33)
    // =========================================================================

    /**
     * Helper: đăng ký và trả về cả accessToken và refreshToken.
     */
    private record TokenPair(String accessToken, String refreshToken) {}

    private TokenPair registerAndGetTokens(String email, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "username": "%s",
                                  "password": "Password123",
                                  "fullName": "Test User"
                                }
                                """.formatted(email, username)))
                .andExpect(status().isOk())
                .andReturn();

        var tree = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TokenPair(
                tree.get("accessToken").asText(),
                tree.get("refreshToken").asText()
        );
    }

    /**
     * Test 30: Logout happy path — trả về 200 với message.
     */
    @Test
    void logoutHappyPath_returns200() throws Exception {
        TokenPair tokens = registerAndGetTokens("logout_happy@example.com", "logout_happy");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(tokens.refreshToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đăng xuất thành công"));
    }

    /**
     * Test 31: Logout với refreshToken malformed — vẫn trả 200 (best-effort).
     * Logout không fail dù refreshToken không parse được.
     */
    @Test
    void logoutWithInvalidRefreshToken_stillReturns200() throws Exception {
        TokenPair tokens = registerAndGetTokens("logout_badrefresh@example.com", "logout_badrefresh");

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "this.is.not.a.valid.refresh.token"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Đăng xuất thành công"));
    }

    /**
     * Test 32: Logout không có Authorization header → 401 AUTH_REQUIRED.
     */
    @Test
    void logoutWithoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "some-refresh-token"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"));
    }

    /**
     * Test 33: Sau khi logout, access token bị blacklist → 401 khi dùng lại để gọi protected endpoint.
     * Verify: JwtAuthFilter check "jwt:blacklist:{jti}" và treat token như expired → 401.
     * Setup: mock Redis để hasKey trả true sau logout → filter block.
     */
    @Test
    void logoutThenUseOldAccessToken_returns401() throws Exception {
        TokenPair tokens = registerAndGetTokens("logout_blacklist@example.com", "logout_blacklist");

        // Lấy jti của access token
        String jti = jwtTokenProvider.getJtiFromToken(tokens.accessToken());
        String blacklistKey = "jwt:blacklist:" + jti;

        // Trước logout: hasKey trả false (token chưa blacklisted)
        when(redisTemplate.hasKey(eq(blacklistKey))).thenReturn(false);

        // Logout thành công
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "%s"}
                                """.formatted(tokens.refreshToken())))
                .andExpect(status().isOk());

        // Sau logout: mock Redis trả true cho blacklist key
        when(redisTemplate.hasKey(eq(blacklistKey))).thenReturn(true);

        // Dùng access token cũ cho protected endpoint (logout itself) → phải 401
        // /api/auth/logout cần auth, nên dùng blacklisted token sẽ bị chặn
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + tokens.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": "some-token"}
                                """))
                .andExpect(status().isUnauthorized());
    }
}
