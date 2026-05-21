package com.reeldown.controller;

import com.reeldown.model.ApiResponse;
import com.reeldown.model.FetchRequest;
import com.reeldown.model.ReelInfo;
import com.reeldown.service.IpRateLimiterService;
import com.reeldown.service.ProxyDownloadService;
import com.reeldown.service.ReelScraperService;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * REST API for the reel downloader.
 *
 * POST /api/fetch          — extract media info from an Instagram URL
 * GET  /api/download       — proxy-stream a media file for download
 * GET  /api/status         — rate-limit status
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class ReelController {

    private final ReelScraperService    scraperService;
    private final ProxyDownloadService  proxyService;
    private final IpRateLimiterService  ipRateLimiter;

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/fetch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extract media metadata from a public Instagram reel / post URL.
     * Body: { "url": "https://www.instagram.com/reel/..." }
     */
    @PostMapping("/fetch")
    public ResponseEntity<ApiResponse<ReelInfo>> fetchReel(
            @Valid @RequestBody FetchRequest body,
            HttpServletRequest request) {

        String clientIp = resolveClientIp(request);
        log.info("Fetch request from [{}] for URL: {}", clientIp, body.getUrl());

        // Validate it is actually an Instagram URL
        String url = body.getUrl().trim();
        if (!url.contains("instagram.com")) {
            return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error("Must be a valid Instagram URL (https://www.instagram.com/...)"));
        }

        // Per-IP rate check (fast path — before hitting the global limiter)
        if (!ipRateLimiter.isAllowed(clientIp)) {
            long wait = ipRateLimiter.secondsUntilReset(clientIp);
            log.warn("IP {} rate-limited. Reset in {}s", clientIp, wait);
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(wait))
                    .body(ApiResponse.error(
                        "Too many requests from your IP. Please wait " + wait + " seconds."
                    ));
        }

        try {
            ReelInfo info      = scraperService.fetchReelInfo(body.getUrl());
            int      remaining = scraperService.getRateLimiterMetrics().getAvailablePermissions();

            return ResponseEntity.ok(ApiResponse.ok(info, remaining));

        } catch (RequestNotPermitted e) {
            log.warn("Global rate limit exceeded for IP: {}", clientIp);
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(ApiResponse.error(
                        "Server is busy. Please wait a moment and try again."
                    ));

        } catch (Exception e) {
            log.error("Fetch failed for URL {}: {}", body.getUrl(), e.getMessage());
            return ResponseEntity
                    .status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(ApiResponse.error(e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/download
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Proxy-stream media from the CDN through the server.
     * The browser triggers a file download via Content-Disposition: attachment.
     *
     * @param url      The media CDN URL (video or image)
     * @param filename Desired download filename (sanitized server-side)
     */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam String url,
            @RequestParam(defaultValue = "instagram_media") String filename) {

        // Basic sanity check — only allow Instagram CDN URLs
        if (!isAllowedMediaUrl(url)) {
            log.warn("Rejected proxy request for non-CDN URL: {}", url);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            HttpResponse<InputStream> upstream = proxyService.proxyStream(url);

            String contentType = upstream.headers()
                    .firstValue("content-type")
                    .orElse("application/octet-stream");

            // Sanitize + ensure extension
            String safeFilename = sanitizeFilename(filename, url, contentType);

            StreamingResponseBody responseBody = out -> {
                try (InputStream in = upstream.body()) {
                    byte[] buf = new byte[16_384]; // 16 KB chunks
                    int    read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.flush();
                }
            };

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + safeFilename + "\"")
                    .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(responseBody);

        } catch (Exception e) {
            log.error("Proxy download failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/status
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns current rate-limit status (useful for the UI gauge). */
    @GetMapping("/status")
    public ResponseEntity<?> status(HttpServletRequest request) {
        String clientIp = resolveClientIp(request);
        var    metrics  = scraperService.getRateLimiterMetrics();

        return ResponseEntity.ok(Map.of(
            "global", Map.of(
                "available", metrics.getAvailablePermissions(),
                "limit",     10,
                "refresh",   "60s"
            ),
            "ip", Map.of(
                "remaining",    ipRateLimiter.remainingRequests(clientIp),
                "limit",        5,
                "resetSeconds", ipRateLimiter.secondsUntilReset(clientIp)
            )
        ));
    }


    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/config-status
    // ─────────────────────────────────────────────────────────────────────────

    /** Tells the UI whether the RapidAPI key has been configured. */
    @GetMapping("/config-status")
    public ResponseEntity<?> configStatus() {
        boolean keySet = scraperService.hasApiKey();
        return ResponseEntity.ok(Map.of(
            "apiKeyConfigured", keySet,
            "message", keySet
                ? "Ready — RapidAPI key is configured."
                : "Setup required: add rapidapi.key=YOUR_KEY to application.properties"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Resolve real client IP, respecting reverse-proxy X-Forwarded-For. */
    private String resolveClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * Only proxy Instagram CDN domains to prevent SSRF abuse.
     */
    private boolean isAllowedMediaUrl(String url) {
        return url != null && (
            url.contains("cdninstagram.com") ||
            url.contains("scontent") ||
            url.contains("fbcdn.net") ||
            url.contains("instagram.com")
        );
    }

    /** Strip dangerous characters and ensure a valid file extension. */
    private String sanitizeFilename(String filename, String url, String contentType) {
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (!safe.contains(".")) {
            safe += proxyService.guessExtension(url, contentType);
        }
        return safe;
    }
}
