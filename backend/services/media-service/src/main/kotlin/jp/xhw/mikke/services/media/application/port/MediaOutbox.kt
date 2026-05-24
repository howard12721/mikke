package jp.xhw.mikke.services.media.application.port

import jp.xhw.mikke.services.media.model.MediaId
import jp.xhw.mikke.services.media.model.MediaRecord
import jp.xhw.mikke.services.media.model.MediaVariantRecord
import kotlin.time.Instant

interface MediaOutbox {
    fun appendUploadUrlCreated(
        media: MediaRecord,
        expiresAt: Instant,
    )

    fun appendUploadCompleted(media: MediaRecord)

    fun appendThumbnailReady(variant: MediaVariantRecord)

    fun appendDeleted(
        mediaId: MediaId,
        deletedAt: Instant,
    )
}
