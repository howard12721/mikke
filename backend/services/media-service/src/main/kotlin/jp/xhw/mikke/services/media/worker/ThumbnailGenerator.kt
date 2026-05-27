package jp.xhw.mikke.services.media.worker

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import kotlin.math.min
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

class ImageIoThumbnailGenerator(
    private val maxSourcePixels: Long = 24_000_000,
) : ThumbnailGenerator {
    init {
        require(maxSourcePixels > 0) { "maxSourcePixels must be positive" }
        ImageIO.scanForPlugins()
    }

    override fun generateWebp(
        sourceBytes: ByteArray,
        maxSizePx: Int,
        targetAspectWidth: Int,
        targetAspectHeight: Int,
    ): GeneratedThumbnail {
        require(targetAspectWidth > 0) { "targetAspectWidth must be positive" }
        require(targetAspectHeight > 0) { "targetAspectHeight must be positive" }
        val source =
            try {
                decodeBounded(sourceBytes)
            } catch (e: UnsupportedImageException) {
                throw e
            } catch (e: Exception) {
                throw UnsupportedImageException("Unable to decode image", e)
            }

        val sourceAspect = source.width.toDouble() / source.height.toDouble()
        val targetAspect = targetAspectWidth.toDouble() / targetAspectHeight.toDouble()
        val cropWidth: Int
        val cropHeight: Int
        if (sourceAspect > targetAspect) {
            cropHeight = source.height
            cropWidth = (cropHeight.toDouble() * targetAspect).roundToInt().coerceAtLeast(1)
        } else {
            cropWidth = source.width
            cropHeight = (cropWidth.toDouble() / targetAspect).roundToInt().coerceAtLeast(1)
        }
        val cropX = ((source.width - cropWidth) / 2).coerceAtLeast(0)
        val cropY = ((source.height - cropHeight) / 2).coerceAtLeast(0)

        val scale = min(1.0, maxSizePx.toDouble() / cropWidth.toDouble())
        val targetWidth = (cropWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (cropHeight * scale).roundToInt().coerceAtLeast(1)

        val thumbnail = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
        val graphics = thumbnail.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, targetWidth, targetHeight)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(
                source,
                0,
                0,
                targetWidth,
                targetHeight,
                cropX,
                cropY,
                cropX + cropWidth,
                cropY + cropHeight,
                null,
            )
        } finally {
            graphics.dispose()
        }

        val output = ByteArrayOutputStream()
        try {
            if (!ImageIO.write(thumbnail, "webp", output)) {
                throw UnsupportedImageException("WebP encoder is not available")
            }
        } catch (e: UnsupportedImageException) {
            throw e
        } catch (e: Exception) {
            throw UnsupportedImageException("Unable to encode thumbnail", e)
        }

        return GeneratedThumbnail(
            bytes = output.toByteArray(),
            width = targetWidth,
            height = targetHeight,
        )
    }

    private fun decodeBounded(sourceBytes: ByteArray): BufferedImage {
        val input =
            ImageIO.createImageInputStream(ByteArrayInputStream(sourceBytes))
                ?: throw UnsupportedImageException("Unable to create image input stream")

        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) {
                throw UnsupportedImageException("Unsupported image format")
            }

            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width.toLong() * height.toLong() > maxSourcePixels) {
                    throw UnsupportedImageException("Image dimensions exceed thumbnail input limit")
                }
                return reader.read(0) ?: throw UnsupportedImageException("Unable to decode image")
            } finally {
                reader.dispose()
                disposeRemainingReaders(readers)
            }
        }
    }

    private fun disposeRemainingReaders(readers: Iterator<ImageReader>) {
        while (readers.hasNext()) {
            readers.next().dispose()
        }
    }
}
