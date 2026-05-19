package jp.xhw.mikke.services.media.application

import jp.xhw.mikke.platform.uuid.formatGrpcUuid
import jp.xhw.mikke.services.media.model.MediaId
import jp.xhw.mikke.services.media.model.MediaVariantKind

object MediaObjectKeys {
    fun forVariant(
        mediaId: MediaId,
        variant: MediaVariantKind,
    ): String = "media/${formatGrpcUuid(mediaId.value)}/${variant.name.lowercase()}"
}
