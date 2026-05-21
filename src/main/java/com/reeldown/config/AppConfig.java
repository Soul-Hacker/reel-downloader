package com.reeldown.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    /**
     * RestTemplate for any Spring-managed HTTP calls.
     * Uses SimpleClientHttpRequestFactory — compatible with Spring Boot 3.2+
     * (RestTemplateBuilder.connectTimeout() was removed in 3.2).
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(12_000); // 12 seconds
        factory.setReadTimeout(20_000);    // 20 seconds
        return new RestTemplate(factory);
    }

    /**
     * Shared Java 11+ HttpClient for scraping (follows redirects, persistent connections).
     */
    @Bean
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(12))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /**
     * Jackson mapper — lenient, ignores unknown fields.
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Global CORS — allow frontend calls from same origin & localhost dev.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST")
                .allowedHeaders("*");
    }
}
