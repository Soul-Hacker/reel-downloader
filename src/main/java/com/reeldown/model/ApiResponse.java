package com.reeldown.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Standard envelope for all API responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private String error;
    private T data;

    /** Remaining rate-limit tokens for this window */
    private Integer rateLimitRemaining;

    public static <T> ApiResponse<T> ok(T data, int remaining) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .rateLimitRemaining(remaining)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .error(message)
                .build();
    }
}
