package com.reeldown.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reeldown.model.ReelInfo;
import io.github.resilience4j.ratelimiter.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetches Instagram reel/post media using:
 *
 *   GET https://instagram-scraper-stable-api.p.rapidapi.com/get_media_data.php
 *       ?reel_post_code_or_url={encoded_url}
 *       &type=reel   (or post)
 *
 * Accepts any Instagram URL format:
 *   https://www.instagram.com/reel/DWhHfSpCSfq/
 *   https://www.instagram.com/reels/DWhHfSpCSfq/
 *   https://www.instagram.com/p/DLUWkieNc0u/
 */
@Service
@Slf4j
public class ReelScraperService {

    private final RateLimiter  reelFetchRateLimiter;
    private final HttpClient   httpClient;
    private final ObjectMapper objectMapper;

    @Value("${rapidapi.key}")
    private String rapidApiKey;

    @Value("${rapidapi.host}")
    private String rapidApiHost;

    @Value("${rapidapi.base-url}")
    private String rapidApiBaseUrl;

    @Value("${scraper.read-timeout-ms:20000}")
    private int readTimeoutMs;

    private static final Pattern SHORTCODE_PATTERN =
            Pattern.compile("/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)");

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";

    public ReelScraperService(RateLimiter reelFetchRateLimiter,
                               HttpClient httpClient,
                               ObjectMapper objectMapper) {
        this.reelFetchRateLimiter = reelFetchRateLimiter;
        this.httpClient           = httpClient;
        this.objectMapper         = objectMapper;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public ReelInfo fetchReelInfo(String rawUrl) {
        Supplier<ReelInfo> supplier = () -> doFetch(rawUrl);
        return RateLimiter.decorateSupplier(reelFetchRateLimiter, supplier).get();
    }

    public RateLimiter.Metrics getRateLimiterMetrics() {
        return reelFetchRateLimiter.getMetrics();
    }

    public boolean hasApiKey() {
        return rapidApiKey != null && !rapidApiKey.isBlank()
                && !rapidApiKey.equals("YOUR_RAPIDAPI_KEY_HERE");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core fetch
    // ─────────────────────────────────────────────────────────────────────────

    private ReelInfo doFetch(String rawUrl) {
        String url  = normalizeUrl(rawUrl);
        String type = detectType(url);
        log.info("Fetching [type={}] for URL: {}", type, url);

        try {
            ReelInfo info = fetchFromStableScraper(url, type);
            if (hasMedia(info)) {
                log.info("[StableScraper] success");
                return info;
            }
            log.warn("[StableScraper] returned no media. Full info: {}", info);
        } catch (Exception e) {
            log.error("[StableScraper] exception: {}", e.getMessage(), e);
        }

        String altType = type.equals("reel") ? "post" : "reel";
        try {
            ReelInfo info = fetchFromStableScraper(url, altType);
            if (hasMedia(info)) {
                log.info("[StableScraper-retry type={}] success", altType);
                return info;
            }
            log.warn("[StableScraper-retry] returned no media. Full info: {}", info);
        } catch (Exception e) {
            log.error("[StableScraper-retry] exception: {}", e.getMessage(), e);
        }

        throw new RuntimeException(
                "Could not extract media from this post. " +
                        "Make sure the post is public and the URL is correct."
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API Call:
    // GET /get_media_data.php?reel_post_code_or_url={url}&type={reel|post}
    // ─────────────────────────────────────────────────────────────────────────

    private ReelInfo fetchFromStableScraper(String postUrl, String type) throws Exception {
        String encoded = URLEncoder.encode(postUrl, StandardCharsets.UTF_8);
        // API expects just the shortcode (e.g. "DXoP6DEjHOl"), not the full URL
        String shortcode = extractShortcode(postUrl);
        if (shortcode == null) {
            throw new RuntimeException("Could not extract shortcode from URL: " + postUrl);
        }
        String apiUrl = rapidApiBaseUrl
                + "/get_media_data.php"
                + "?reel_post_code_or_url=" + shortcode
                + "&type=" + type;

        log.debug("Calling: {}", apiUrl);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type",    "application/json")
                .header("x-rapidapi-host", rapidApiHost)
                .header("x-rapidapi-key",  rapidApiKey)
                .header("User-Agent",       USER_AGENT)
                .timeout(Duration.ofMillis(readTimeoutMs))
                .GET()
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        log.info("[StableScraper] HTTP {} | FULL BODY: {}",
                response.statusCode(),
                response.body());

        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new RuntimeException("RapidAPI key is invalid or not subscribed.");
        }
        if (response.statusCode() == 429) {
            throw new RuntimeException("RapidAPI rate limit exceeded. Please wait a moment.");
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("API returned HTTP " + response.statusCode());
        }

        return parseResponse(objectMapper.readTree(response.body()), postUrl);
    }

    private ReelInfo parseResponse(JsonNode root, String originalUrl) {
        if (root == null || root.isNull()) return null;

        // Check for API error response e.g. {"error": "media_code should not be a url"}
        if (root.has("error")) {
            throw new RuntimeException("API error: " + root.path("error").asText());
        }

        // ── Actual flat response shape from API:
        // {
        //   "id": "...",
        //   "shortcode": "DXoP6DEjHOl",
        //   "is_video": true,
        //   "video_url": "https://scontent.../video.mp4",
        //   "thumbnail_src": "https://scontent.../thumb.jpg",
        //   "caption": "...",
        //   "owner": { "username": "...", "full_name": "..." }
        // }

        // ── Video URL (flat key at root) ───────────────────────────────────
        String videoUrl = null;
        if (root.path("is_video").asBoolean(false)) {
            videoUrl = text(root, "video_url", "video_src", "playback_url");
        }

        // ── Thumbnail (flat key at root) ───────────────────────────────────
        String imageUrl = text(root, "thumbnail_src", "thumbnail_url",
                "display_url",   "image_url");

        // ── Also handle old nested shape as fallback ───────────────────────
        if (videoUrl == null) {
            JsonNode videoVersions = root.path("video_versions");
            if (videoVersions.isArray() && !videoVersions.isEmpty()) {
                videoUrl = videoVersions.get(0).path("url").asText(null);
            }
        }
        if (imageUrl == null) {
            JsonNode candidates = root.path("image_versions2").path("candidates");
            if (candidates.isArray() && !candidates.isEmpty()) {
                imageUrl = candidates.get(0).path("url").asText(null);
            }
        }

        // ── Caption ────────────────────────────────────────────────────────
        String caption = null;
        JsonNode captionNode = root.path("caption");
        if (captionNode.isTextual()) {
            caption = captionNode.asText(null);
        } else if (captionNode.isObject()) {
            caption = captionNode.path("text").asText(null);
        }

        // ── Author: check both "owner" and "user" keys ─────────────────────
        String username  = null;
        String fullName  = null;
        String profilePic = null;
        JsonNode author = root.path("owner").isMissingNode()
                ? root.path("user")
                : root.path("owner");
        if (!author.isMissingNode()) {
            username   = author.path("username").asText(null);
            fullName   = author.path("full_name").asText(null);
            profilePic = author.path("profile_pic_url").asText(null);
        }

        log.debug("Parsed — videoUrl={} imageUrl={} username={}", videoUrl, imageUrl, username);

        if (videoUrl == null && imageUrl == null) return null;

        return ReelInfo.builder()
                .videoUrl(videoUrl)
                .imageUrl(imageUrl)
                .caption(caption)
                .authorUsername(username)
                .authorFullName(fullName)
                .authorProfilePic(profilePic)
                .originalUrl(originalUrl)
                .postId(extractShortcode(originalUrl))
                .hasVideo(videoUrl != null)
                .hasImage(imageUrl != null)
                .extractedVia("instagram-scraper-stable-api")
                .build();
    }


//    private ReelInfo parseResponse(JsonNode root, String originalUrl) {
//        if (root == null || root.isNull()) return null;
//
//        // The API returns the post object directly (no "data" wrapper)
//        // Real response shape:
//        // {
//        //   "video_versions": [ { "url": "...", "width": 720, "height": 1280 } ],
//        //   "image_versions2": { "candidates": [ { "url": "..." } ] },
//        //   "user": { "username": "...", "full_name": "..." },
//        //   "like_count": 123,
//        //   "comment_count": 5
//        // }
//
//        // ── Video URL: first entry in video_versions array ─────────────────
//        String videoUrl = null;
//        JsonNode videoVersions = root.path("video_versions");
//        if (videoVersions.isArray() && !videoVersions.isEmpty()) {
//            // Pick the first one (highest quality)
//            videoUrl = videoVersions.get(0).path("url").asText(null);
//        }
//
//        // ── Thumbnail: first candidate in image_versions2.candidates ───────
//        String imageUrl = null;
//        JsonNode candidates = root.path("image_versions2").path("candidates");
//        if (candidates.isArray() && !candidates.isEmpty()) {
//            imageUrl = candidates.get(0).path("url").asText(null);
//        }
//
//        // ── Caption ────────────────────────────────────────────────────────
//        // caption can be a string or an object with a "text" field
//        String caption = null;
//        JsonNode captionNode = root.path("caption");
//        if (captionNode.isTextual()) {
//            caption = captionNode.asText(null);
//        } else if (captionNode.isObject()) {
//            caption = captionNode.path("text").asText(null);
//        }
//
//        // ── Author: lives under "user" object ──────────────────────────────
//        String username  = null;
//        String fullName  = null;
//        String profilePic = null;
//        JsonNode user = root.path("user");
//        if (!user.isMissingNode()) {
//            username   = user.path("username").asText(null);
//            fullName   = user.path("full_name").asText(null);
//            profilePic = user.path("profile_pic_url").asText(null);
//        }
//
//        if (videoUrl == null && imageUrl == null) return null;
//
//        return ReelInfo.builder()
//                .videoUrl(videoUrl)
//                .imageUrl(imageUrl)
//                .caption(caption)
//                .authorUsername(username)
//                .authorFullName(fullName)
//                .authorProfilePic(profilePic)
//                .originalUrl(originalUrl)
//                .postId(extractShortcode(originalUrl))
//                .hasVideo(videoUrl != null)
//                .hasImage(imageUrl != null)
//                .extractedVia("instagram-scraper-stable-api")
//                .build();
//    }
    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Return the first non-blank text value from the given field names. */
    private String text(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode n = node.path(f);
            if (n.isTextual()) {
                String v = n.asText().trim();
                if (!v.isBlank() && !v.equals("null")) return v;
            }
        }
        return null;
    }

    /**
     * Detect whether the URL is a reel or a regular post.
     * Defaults to "reel" so the API tries video extraction first.
     */
    private String detectType(String url) {
        if (url.contains("/p/")) return "post";
        return "reel";
    }

    /**
     * Clean the URL:
     * - Strip query params and trailing slash
     * - Normalise /reels/ → /reel/  and share links
     * - Force HTTPS
     */
    private String normalizeUrl(String url) {
        url = url.trim();
        if (url.contains("?")) url = url.substring(0, url.indexOf('?'));
        if (url.endsWith("/"))  url = url.substring(0, url.length() - 1);
        url = url.replace("/share/reel/",  "/reel/");
        url = url.replace("/share/reels/", "/reels/");
        url = url.replace("/share/p/",     "/p/");
        if (url.startsWith("http://")) url = "https://" + url.substring(7);
        return url;
    }

    private String extractShortcode(String url) {
        Matcher m = SHORTCODE_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private boolean hasMedia(ReelInfo info) {
        return info != null && (info.getVideoUrl() != null || info.getImageUrl() != null);
    }
}
