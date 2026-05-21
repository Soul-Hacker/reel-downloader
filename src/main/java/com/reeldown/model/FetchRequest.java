package com.reeldown.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Request body for POST /api/fetch
 *
 * Note: we validate the Instagram domain in the controller with a simple
 * .contains() check instead of @Pattern to avoid the name clash with
 * java.util.regex.Pattern which is used in the scraper service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FetchRequest {

    @NotBlank(message = "URL must not be blank")
    private String url;
}
