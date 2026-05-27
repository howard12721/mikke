package jp.xhw.mikke.services.media.model

enum class MediaStatus {
    PENDING_UPLOAD,
    READY,
    DELETED,
    FAILED,
    ;

    fun toDatabaseValue(): String = name

    companion object {
        fun fromDatabaseValue(raw: String): MediaStatus =
            entries.firstOrNull { it.name == raw }
                ?: error("Unknown media status: $raw")
    }
}

enum class MediaVariantKind {
    ORIGINAL,
    THUMBNAIL,
    ICON,
    ;

    fun toDatabaseValue(): String = name

    companion object {
        fun fromDatabaseValue(raw: String): MediaVariantKind =
            entries.firstOrNull { it.name == raw }
                ?: error("Unknown media variant: $raw")
    }
}

enum class MediaVariantStatus {
    PENDING,
    READY,
    FAILED,
    ;

    fun toDatabaseValue(): String = name

    companion object {
        fun fromDatabaseValue(raw: String): MediaVariantStatus =
            entries.firstOrNull { it.name == raw }
                ?: error("Unknown media variant status: $raw")
    }
}

enum class UploadMethod {
    PUT,
    ;

    fun toDatabaseValue(): String = name

    companion object {
        fun fromDatabaseValue(raw: String): UploadMethod =
            entries.firstOrNull { it.name == raw }
                ?: error("Unknown upload method: $raw")
    }
}
