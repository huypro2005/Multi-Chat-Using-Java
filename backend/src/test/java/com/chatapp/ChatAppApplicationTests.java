package com.chatapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
@ActiveProfiles("test")
class ChatAppApplicationTests {

    // AuthService inject StringRedisTemplate — phải mock để context start được khi Redis bị exclude
    @MockBean
    @SuppressWarnings("unused")
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void contextLoads() {
        // Verify Spring context starts without error
    }
}
