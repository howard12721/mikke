package jp.xhw.mikke.services.media.worker

/**
 * Subscribes to [media.upload_completed] and generates thumbnail variants.
 *
 * Thumbnail generation is not implemented yet. Until it is, [jp.xhw.mikke.services.media.application.MediaDeliveryUrlBuilder]
 * falls back to the original delivery URL when the thumbnail variant is not ready.
 */
class MediaThumbnailWorker
