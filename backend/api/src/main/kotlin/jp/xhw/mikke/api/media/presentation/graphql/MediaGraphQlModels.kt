package jp.xhw.mikke.api.media.presentation.graphql

data class CreateMediaUploadInput(
    val contentType: String,
    val contentLengthBytes: Int,
    val originalFileName: String? = null,
)

data class CheckMediaUploadInput(
    val mediaId: String,
    val objectKey: String,
)

data class RequiredHeader(
    val name: String,
    val value: String,
)

data class MediaUploadUrl(
    val mediaId: String,
    val objectKey: String,
    val uploadUrl: String,
    val uploadMethod: String,
    val requiredHeaders: List<RequiredHeader>,
    val expiresAt: String,
)

data class UploadCheck(
    val mediaId: String,
    val objectKey: String,
    val status: String,
    val contentLengthBytes: String,
    val contentType: String,
    val etag: String,
)

data class Media(
    val id: String,
    val originalUrl: String,
    val thumbnailUrl: String,
    val status: String,
    val contentType: String,
    val contentLengthBytes: String,
    val createdAt: String,
    val uploadedAt: String,
)

fun jp.xhw.mikke.api.media.application.MediaUploadUrl.toGraphQl(): MediaUploadUrl =
    MediaUploadUrl(
        mediaId = mediaId,
        objectKey = objectKey,
        uploadUrl = uploadUrl,
        uploadMethod = uploadMethod,
        requiredHeaders = requiredHeaders.map { RequiredHeader(name = it.key, value = it.value) },
        expiresAt = expiresAt,
    )

fun jp.xhw.mikke.api.media.application.UploadCheck.toGraphQl(): UploadCheck =
    UploadCheck(
        mediaId = mediaId,
        objectKey = objectKey,
        status = status,
        contentLengthBytes = contentLengthBytes.toString(),
        contentType = contentType,
        etag = etag,
    )

fun jp.xhw.mikke.api.media.application.Media.toGraphQl(): Media =
    Media(
        id = id,
        originalUrl = originalUrl,
        thumbnailUrl = thumbnailUrl,
        status = status,
        contentType = contentType,
        contentLengthBytes = contentLengthBytes.toString(),
        createdAt = createdAt,
        uploadedAt = uploadedAt,
    )
