package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.services.media.model.MediaId
import jp.xhw.mikke.services.media.model.MediaRecord
import jp.xhw.mikke.services.media.model.MediaVariantKind
import jp.xhw.mikke.services.media.model.MediaVariantRecord

interface MediaRepository {
    fun insert(media: MediaRecord)

    fun findById(id: MediaId): MediaRecord?

    fun findByIds(ids: List<MediaId>): List<MediaRecord>

    fun findVariantByDeliveryKey(deliveryKey: String): MediaVariantRecord?

    fun update(media: MediaRecord)

    fun findVariant(
        mediaId: MediaId,
        variant: MediaVariantKind,
    ): MediaVariantRecord?
}
