package com.reeldown.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimiterConfiguration {

    /**
     * Global rate limiter: 10 fetches per 60 seconds.
     * Per-IP tracking is handled separately in IpRateLimiterService
     * to prevent individual users from consuming all capacity.
     */
    @Bean
    public RateLimiter reelFetchRateLimiter(RateLimiterRegistry registry) {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitForPeriod(10)
                .limitRefreshPeriod(Duration.ofSeconds(60))
                .timeoutDuration(Duration.ofSeconds(3))
                .build();
        return registry.rateLimiter("reelFetch", config);
    }
}
