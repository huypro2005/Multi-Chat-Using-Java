package com.chatapp.security;

import com.chatapp.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test cho SecurityConfig.
 *
 * Verify:
 * 1. /api/health là public (không cần token)
 * 2. Protected endpoint trả 401 JSON (không phải HTML Whitelabel Error)
 * 3. 401 response có đúng field "error": "AUTH_REQUIRED"
 * 4. Token expired trả "error": "AUTH_TOKEN_EXPIRED"
 * 5. Token invalid (wrong sig / malformed) trả "error": "AUTH_REQUIRED"
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
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    // AuthService inject StringRedisTemplate — phải mock để context start được khi Redis bị exclude
    @MockBean
    @SuppressWarnings("unused")
    private StringRedisTemplate stringRedisTemplate;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(UUID.randomUUID())
                .username("security_test_user")
                .email("sectest@example.com")
                .fullName("Security Test User")
                .passwordHash("$2a$12$irrelevant")
                .status("active")
                .build();
    }

    @Test
    void healthEndpointShouldBePublic() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void protectedEndpointWithoutTokenShouldReturn401JsonNotHtml() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void protectedEndpointWithInvalidTokenShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer invalid.token.here"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"));
    }

    @Test
    void authEndpointsShouldBePublic() throws Exception {
        // /api/auth/** permit all — POST không có body sẽ trả 4xx nhưng không phải 401
        // Verify bằng cách check response KHÔNG phải 401
        // Thực tế không có handler nên sẽ 404 hoặc 405 — đều OK, miễn không phải 401
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    // Không được là 401 (Unauthorized) — security cho phép
                    assert status != 401 : "Auth endpoints should not return 401, got: " + status;
                });
    }

    @Test
    void expiredTokenShouldReturnAuthTokenExpired() throws Exception {
        // Generate token đã hết hạn ngay tức thì (expirationMs = -1000)
        // Dùng package-private helper generateTokenWithExpiration — cùng package nên truy cập được
        String expiredToken = jwtTokenProvider.generateTokenWithExpiration(testUser, -1000L);

        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("AUTH_TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Access token đã hết hạn, vui lòng refresh"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void invalidTokenShouldReturnAuthRequired() throws Exception {
        // Token rác — sai signature / malformed
        mockMvc.perform(get("/api/users/me")
                        .header("Authorization", "Bearer this.is.garbage"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value("AUTH_REQUIRED"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
