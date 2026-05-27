package jp.xhw.mikke.services.media.worker

import com.sksamuel.scrimage.ImmutableImage
import com.sksamuel.scrimage.Position
import com.sksamuel.scrimage.ScaleMethod
import com.sksamuel.scrimage.webp.WebpWriter
import kotlin.math.roundToInt

fun interface ThumbnailGenerator {
    fun generateWebp(
        sourceBytes: ByteArray,
        maxSizePx: Int,
        targetAspectWidth: Int,
        targetAspectHeight: Int,
    ): GeneratedThumbnail
}

data class GeneratedThumbnail(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GeneratedThumbnail

        if (width != other.width) return false
        if (height != other.height) return false
        if (!bytes.contentEquals(other.bytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

class UnsupportedImageException(
    message: String? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ScrimageThumbnailGenerator(
    private val maxSourcePixels: Long = 24_000_000,
) : ThumbnailGenerator {
    init {
        require(maxSourcePixels > 0) { "maxSourcePixels must be positive" }
    }

    override fun generateWebp(
        sourceBytes: ByteArray,
        maxSizePx: Int,
        targetAspectWidth: Int,
        targetAspectHeight: Int,
    ): GeneratedThumbnail {
        require(targetAspectWidth > 0) { "targetAspectWidth must be positive" }
        require(targetAspectHeight > 0) { "targetAspectHeight must be positive" }
        val source = decodeBounded(sourceBytes)
        val sourceAspect = source.width.toDouble() / source.height.toDouble()
        val targetAspect = targetAspectWidth.toDouble() / targetAspectHeight.toDouble()
        val cropWidth =
            if (sourceAspect > targetAspect) {
                (source.height.toDouble() * targetAspect).roundToInt().coerceAtLeast(1)
            } else {
                source.width
            }
        val cropHeight =
            if (sourceAspect > targetAspect) {
                source.height
            } else {
                (source.width.toDouble() / targetAspect).roundToInt().coerceAtLeast(1)
            }
        val scale =
            if (cropWidth <= maxSizePx) {
                1.0
            } else {
                maxSizePx.toDouble() / cropWidth.toDouble()
            }
        val targetWidth = (cropWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (cropHeight * scale).roundToInt().coerceAtLeast(1)
        val thumbnail =
            try {
                source.cover(targetWidth, targetHeight, ScaleMethod.Bicubic, Position.Center)
            } catch (e: Exception) {
                throw UnsupportedImageException("Unable to resize image", e)
            }
        val bytes =
            try {
                thumbnail.forWriter(WebpWriter.DEFAULT).bytes()
            } catch (e: Exception) {
                throw UnsupportedImageException("Unable to encode thumbnail", e)
            }

        return GeneratedThumbnail(
            bytes = bytes,
            width = targetWidth,
            height = targetHeight,
        )
    }

    private fun decodeBounded(sourceBytes: ByteArray): ImmutableImage {
        val source =
            try {
                ImmutableImage.loader().fromBytes(sourceBytes)
            } catch (e: Exception) {
                throw UnsupportedImageException("Unsupported image format", e)
            }
        val pixelCount = source.width.toLong() * source.height.toLong()
        if (pixelCount > maxSourcePixels) {
            throw UnsupportedImageException("Image dimensions exceed thumbnail input limit")
        }
        return source
    }
}
