package com.chatapp.auth;

import com.chatapp.user.repository.UserRepository;
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

    @MockBean
    private StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Xóa toàn bộ user trước mỗi test để đảm bảo idempotent
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
}
