package com.chatapp.config;

import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Test configuration — override Redis để không cần Redis server thật khi chạy unit test.
 *
 * Dùng embedded Redis hoặc mock. Ở đây tạo LettuceConnectionFactory trỏ vào localhost:6379.
 * Nếu Redis không chạy, test vẫn pass vì JwtTokenProviderTest và SecurityConfigTest
 * không thực sự dùng Redis operation nào.
 *
 * Note: Nếu sau này cần test Redis operation, dùng EmbeddedRedis (ví dụ it.ozimov:embedded-redis).
 */
@TestConfiguration
public class TestConfig {
    // Hiện tại không cần override — Redis connection factory được tạo tự động
    // và test không gọi Redis operation nào trong phase này.
}
