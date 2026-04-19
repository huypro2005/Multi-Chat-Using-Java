package com.chatapp.security;

import com.chatapp.model.entity.User;
import com.chatapp.model.enums.AuthMethod;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test cho JwtTokenProvider — dùng Spring context để load @Value properties.
 * Exclude Redis autoconfigure vì JWT test không cần Redis.
 * @MockBean StringRedisTemplate để AuthService có thể wire (AuthService inject StringRedisTemplate).
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
class JwtTokenProviderTest {

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
                .username("test_user")
                .email("test@example.com")
                .fullName("Test User")
                .passwordHash("$2a$12$hashed")
                .status("active")
                .build();
    }

    @Test
    void generateAndValidateAccessToken() {
        String token = jwtTokenProvider.generateAccessToken(testUser, AuthMethod.PASSWORD);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();

        UUID extractedUserId = jwtTokenProvider.getUserIdFromToken(token);
        assertThat(extractedUserId).isEqualTo(testUser.getId());

        String jti = jwtTokenProvider.getJtiFromToken(token);
        assertThat(jti).isNotBlank();
    }

    @Test
    void generateAndValidateRefreshToken() {
        String token = jwtTokenProvider.generateRefreshToken(testUser);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.validateToken(token)).isTrue();

        UUID extractedUserId = jwtTokenProvider.getUserIdFromToken(token);
        assertThat(extractedUserId).isEqualTo(testUser.getId());
    }

    @Test
    void accessTokenAndRefreshTokenShouldHaveDifferentJti() {
        String accessToken = jwtTokenProvider.generateAccessToken(testUser, AuthMethod.PASSWORD);
        String refreshToken = jwtTokenProvider.generateRefreshToken(testUser);

        String accessJti = jwtTokenProvider.getJtiFromToken(accessToken);
        String refreshJti = jwtTokenProvider.getJtiFromToken(refreshToken);

        assertThat(accessJti).isNotEqualTo(refreshJti);
    }

    @Test
    void expiredTokenShouldBeInvalid() {
        // Tạo provider với access expiration = -1000ms (đã hết hạn ngay khi tạo)
        JwtTokenProvider expiredProvider = new JwtTokenProvider(
                "changeme-this-is-a-very-long-secret-key-for-dev-only-at-least-256-bits",
                -1000L,   // negative = expire immediately
                604800000L
        );

        String token = expiredProvider.generateAccessToken(testUser, AuthMethod.PASSWORD);

        assertThat(expiredProvider.validateToken(token)).isFalse();
    }

    @Test
    void invalidSignatureShouldBeRejected() {
        String token = jwtTokenProvider.generateAccessToken(testUser, AuthMethod.PASSWORD);

        // Tamper signature — modify last few chars
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        assertThat(jwtTokenProvider.validateToken(tampered)).isFalse();
    }

    @Test
    void randomStringTokenShouldBeInvalid() {
        assertThat(jwtTokenProvider.validateToken("not.a.jwt")).isFalse();
        assertThat(jwtTokenProvider.validateToken("")).isFalse();
    }

    @Test
    void generateAccessTokenWithPasswordMethod() {
        String token = jwtTokenProvider.generateAccessToken(testUser, AuthMethod.PASSWORD);
        Claims claims = jwtTokenProvider.getClaims(token);
        assertThat(claims.get("auth_method")).isEqualTo("password");
    }

    @Test
    void generateAccessTokenWithOauthMethod() {
        String token = jwtTokenProvider.generateAccessToken(testUser, AuthMethod.OAUTH2_GOOGLE);
        Claims claims = jwtTokenProvider.getClaims(token);
        assertThat(claims.get("auth_method")).isEqualTo("oauth2_google");
    }

    @Test
    void getAuthMethodFromTokenPassword() {
        String token = jwtTokenProvider.generateAccessToken(testUser, AuthMethod.PASSWORD);
        assertThat(jwtTokenProvider.getAuthMethodFromToken(token)).isEqualTo(AuthMethod.PASSWORD);
    }

    @Test
    void getAuthMethodFromTokenOauth() {
        String token = jwtTokenProvider.generateAccessToken(testUser, AuthMethod.OAUTH2_GOOGLE);
        assertThat(jwtTokenProvider.getAuthMethodFromToken(token)).isEqualTo(AuthMethod.OAUTH2_GOOGLE);
    }
}
