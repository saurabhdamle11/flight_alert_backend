package com.tracker.tracker_backend.security;

import com.tracker.tracker_backend.exception.RateLimitedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fixed-window rate limiter backed by Redis ({@code rl:<bucket>:<key>}).
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final StringRedisTemplate redis;

    public void check(String bucket, String key, int limit, Duration window) {
        String redisKey = "rl:" + bucket + ":" + key;
        Long count = redis.opsForValue().increment(redisKey);
        if (count != null && count == 1) {
            redis.expire(redisKey, window);
        }
        if (count != null && count > limit) {
            throw new RateLimitedException();
        }
    }

    /** Client IP; the app only receives traffic through Caddy, which sets X-Forwarded-For. */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].strip();
        }
        return request.getRemoteAddr();
    }
}
