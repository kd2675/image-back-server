package image.back.server.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import image.back.server.exception.UploadRateLimitException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
public class AttachmentUploadRateLimiter {
    private final int limitPerMinute;
    private final Cache<String, UploadWindow> windows = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofMinutes(2))
            .build();

    public AttachmentUploadRateLimiter(
            @Value("${attachment.upload-rate-limit-per-minute:12}") int limitPerMinute
    ) {
        if (limitPerMinute < 1) {
            throw new IllegalArgumentException("attachment upload rate limit must be positive");
        }
        this.limitPerMinute = limitPerMinute;
    }

    public void check(String userKey) {
        UploadWindow window = windows.get(userKey, ignored -> new UploadWindow());
        if (window == null || !window.tryAcquire(limitPerMinute)) {
            throw new UploadRateLimitException("Too many attachment uploads. Please try again shortly.");
        }
    }

    private static final class UploadWindow {
        private Instant startedAt = Instant.now();
        private int count;

        private synchronized boolean tryAcquire(int limit) {
            Instant now = Instant.now();
            if (startedAt.plusSeconds(60).isBefore(now)) {
                startedAt = now;
                count = 0;
            }
            if (count >= limit) {
                return false;
            }
            count++;
            return true;
        }
    }
}
