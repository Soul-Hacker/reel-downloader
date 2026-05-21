package com.reeldown.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Streams a remote media URL through the Spring Boot server.
 *
 * Why proxy?
 * - Instagram CDN URLs contain signed tokens; direct browser downloads
 *   often fail due to Referer / CORS restrictions.
 * - Proxying through our server adds the correct Referer header and
 *   handles redirects transparently.
 */
@Service
@Slf4j
public class ProxyDownloadService {

    private final HttpClient httpClient;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/122.0.0.0 Safari/537.36";

    public ProxyDownloadService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Open a streaming connection to the remote URL and return the response.
     * The caller is responsible for closing the InputStream.
     *
     * @param mediaUrl The full CDN URL to proxy
     * @return Java HttpResponse with an InputStream body
     */
    public HttpResponse<InputStream> proxyStream(String mediaUrl) throws Exception {
        log.info("Proxy streaming: {}...", mediaUrl.substring(0, Math.min(80, mediaUrl.length())));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(mediaUrl))
                .header("User-Agent", USER_AGENT)
                .header("Referer",    "https://www.instagram.com/")
                .header("Origin",     "https://www.instagram.com")
                .header("Accept",     "*/*")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        log.debug("Proxy upstream status: {}", response.statusCode());

        if (response.statusCode() >= 400) {
            response.body().close();
            throw new RuntimeException(
                "Upstream returned HTTP " + response.statusCode() +
                " — the media link may have expired. Please re-fetch."
            );
        }

        return response;
    }

    /**
     * Guess a safe filename extension from the URL or content-type header.
     */
    public String guessExtension(String url, String contentType) {
        if (contentType != null) {
            if (contentType.contains("video"))   return ".mp4";
            if (contentType.contains("jpeg"))    return ".jpg";
            if (contentType.contains("png"))     return ".png";
            if (contentType.contains("webp"))    return ".webp";
        }
        if (url.contains(".mp4"))  return ".mp4";
        if (url.contains(".jpg") || url.contains(".jpeg")) return ".jpg";
        if (url.contains(".png"))  return ".png";
        if (url.contains(".webp")) return ".webp";
        return ".bin";
    }
}
