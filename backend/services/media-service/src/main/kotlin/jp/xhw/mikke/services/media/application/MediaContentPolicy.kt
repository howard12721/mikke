package jp.xhw.mikke.services.media.application

object MediaContentPolicy {
    const val MAX_CONTENT_LENGTH_BYTES: Long = 10L * 1024L * 1024L

    private val allowedContentTypes =
        setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
        )

    fun validateContentType(contentType: String) {
        val normalized = contentType.trim().lowercase()
        if (normalized !in allowedContentTypes) {
            throw InvalidMediaInputException(
                "content_type must be one of: ${allowedContentTypes.joinToString(", ")}",
            )
        }
    }

    fun validateContentLength(contentLengthBytes: Long) {
        if (contentLengthBytes <= 0) {
            throw InvalidMediaInputException("content_length_bytes must be positive")
        }
        if (contentLengthBytes > MAX_CONTENT_LENGTH_BYTES) {
            throw InvalidMediaInputException("content_length_bytes must be at most $MAX_CONTENT_LENGTH_BYTES")
        }
    }
}
