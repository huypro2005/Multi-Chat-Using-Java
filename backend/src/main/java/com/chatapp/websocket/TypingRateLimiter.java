package com.chatapp.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-based rate limiter for typing indicator events.
 *
 * Strategy: INCR + EXPIRE on first increment.
 * Window: 2 seconds. Max 1 event per key per window.
 *
 * Fail-open: if Redis is unavailable, allow the event through
 * to avoid degrading the typing UX due to infrastructure issues.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TypingRateLimiter {

    private final StringRedisTemplate redis;

    /**
     * Returns true if the event is allowed (within rate limit), false if it should be dropped.
     *
     * @param key Redis key identifying the rate limit bucket (e.g. "rate:typing:{userId}:{convId}")
     */
    public boolean allow(String key) {
        try {
            Long count = redis.opsForValue().increment(key);
            if (Long.valueOf(1L).equals(count)) {
                // First event in this window — set TTL to open the next window after 2s
                redis.expire(key, Duration.ofSeconds(2));
            }
            return count != null && count <= 1;
        } catch (Exception e) {
            // Fail-open: Redis unavailable — allow event through, log at WARN level
            log.warn("[TYPING_RATE_LIMITER] Redis unavailable for key={}, fail-open", key);
            return true;
        }
    }
}
