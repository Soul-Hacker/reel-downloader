package com.reeldown.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP rate limiter: each IP address gets its own quota
 * of MAX_REQUESTS requests within WINDOW_SECONDS.
 *
 * Uses a simple sliding-window counter stored in memory.
 * Old entries are cleaned up automatically every 5 minutes.
 */
@Service
@Slf4j
public class IpRateLimiterService {

    private static final int MAX_REQUESTS    = 5;   // max per IP per window
    private static final int WINDOW_SECONDS  = 60;  // window size in seconds

    private record IpRecord(AtomicInteger count, Instant windowStart) {}

    private final Map<String, IpRecord> ipMap = new ConcurrentHashMap<>();

    /**
     * Returns true if the request is allowed for this IP.
     * Returns false if the IP has exceeded its quota.
     */
    public boolean isAllowed(String ip) {
        Instant now = Instant.now();
        IpRecord record = ipMap.compute(ip, (key, existing) -> {
            if (existing == null ||
                    now.getEpochSecond() - existing.windowStart().getEpochSecond() >= WINDOW_SECONDS) {
                // New window
                return new IpRecord(new AtomicInteger(1), now);
            }
            existing.count().incrementAndGet();
            return existing;
        });

        int count = record.count().get();
        boolean allowed = count <= MAX_REQUESTS;

        if (!allowed) {
            log.warn("IP {} exceeded rate limit: {} requests in window", ip, count);
        }

        return allowed;
    }

    /**
     * Returns seconds remaining in the current window for this IP.
     */
    public long secondsUntilReset(String ip) {
        IpRecord record = ipMap.get(ip);
        if (record == null) return 0L;
        long elapsed = Instant.now().getEpochSecond() - record.windowStart().getEpochSecond();
        return Math.max(0, WINDOW_SECONDS - elapsed);
    }

    /**
     * Returns remaining requests allowed for this IP in the current window.
     */
    public int remainingRequests(String ip) {
        IpRecord record = ipMap.get(ip);
        if (record == null) return MAX_REQUESTS;
        long elapsed = Instant.now().getEpochSecond() - record.windowStart().getEpochSecond();
        if (elapsed >= WINDOW_SECONDS) return MAX_REQUESTS;
        return Math.max(0, MAX_REQUESTS - record.count().get());
    }

    /**
     * Purge stale IP records every 5 minutes to prevent memory leaks.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void cleanup() {
        Instant cutoff = Instant.now().minusSeconds(WINDOW_SECONDS * 2L);
        int before = ipMap.size();
        ipMap.entrySet().removeIf(e -> e.getValue().windowStart().isBefore(cutoff));
        int removed = before - ipMap.size();
        if (removed > 0) {
            log.debug("IP rate limiter cleanup: removed {} stale entries", removed);
        }
    }
}
