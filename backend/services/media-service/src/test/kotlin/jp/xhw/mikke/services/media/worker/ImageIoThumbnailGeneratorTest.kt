package jp.xhw.mikke.services.media.worker

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageIoThumbnailGeneratorTest {
    @Test
    fun `generateWebp resizes and center crops to 16 by 9`() {
        assumeTrue(ImageIO.getImageWritersByMIMEType("image/webp").hasNext(), "WebP writer unavailable")
        val sourceBytes = pngBytes(width = 1200, height = 600)
        val generator = ImageIoThumbnailGenerator()

        val thumbnail = generator.generateWebp(sourceBytes = sourceBytes, maxSizePx = 512)

        assertEquals(512, thumbnail.width)
        assertEquals(288, thumbnail.height)
        assertTrue(thumbnail.bytes.isNotEmpty())
    }

    @Test
    fun `generateWebp keeps small images without upscaling and crops 16 by 9`() {
        assumeTrue(ImageIO.getImageWritersByMIMEType("image/webp").hasNext(), "WebP writer unavailable")
        val sourceBytes = pngBytes(width = 320, height = 240)
        val generator = ImageIoThumbnailGenerator()

        val thumbnail = generator.generateWebp(sourceBytes = sourceBytes, maxSizePx = 512)

        assertEquals(320, thumbnail.width)
        assertEquals(180, thumbnail.height)
    }

    @Test
    fun `generateWebp rejects images above source pixel limit before decoding`() {
        val sourceBytes = pngBytes(width = 20, height = 20)
        val generator = ImageIoThumbnailGenerator(maxSourcePixels = 100)

        val error =
            assertThrows(UnsupportedImageException::class.java) {
                generator.generateWebp(sourceBytes = sourceBytes, maxSizePx = 512)
            }

        assertEquals("Image dimensions exceed thumbnail input limit", error.message)
    }

    @Test
    fun `generateWebp preserves decode failure diagnostic`() {
        val generator = ImageIoThumbnailGenerator()

        val error =
            assertThrows(UnsupportedImageException::class.java) {
                generator.generateWebp(sourceBytes = byteArrayOf(1, 2, 3), maxSizePx = 512)
            }

        assertEquals("Unsupported image format", error.message)
    }

    private fun pngBytes(
        width: Int,
        height: Int,
    ): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = Color.BLUE
            graphics.fillRect(0, 0, width, height)
        } finally {
            graphics.dispose()
        }

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
    }
}
