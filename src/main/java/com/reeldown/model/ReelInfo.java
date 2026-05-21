package com.reeldown.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Holds all extracted media information for a reel / post.
 * Null fields are omitted from JSON responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReelInfo {

    /** Direct URL to the video file (mp4), if available */
    private String videoUrl;

    /** Thumbnail / cover image URL */
    private String imageUrl;

    /** Post caption text */
    private String caption;

    /** Instagram username of the post author */
    private String authorUsername;

    /** Display name of the author */
    private String authorFullName;

    /** Profile picture URL of the author */
    private String authorProfilePic;

    /** Instagram shortcode / post ID (e.g. "ABC123xyz") */
    private String postId;

    /** The original URL that was submitted */
    private String originalUrl;

    /** Whether a downloadable video was found */
    private boolean hasVideo;

    /** Whether a downloadable image / thumbnail was found */
    private boolean hasImage;

    /** Which strategy successfully extracted the data */
    private String extractedVia;
}
