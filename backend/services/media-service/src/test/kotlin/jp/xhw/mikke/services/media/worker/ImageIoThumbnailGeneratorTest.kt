package jp.xhw.mikke.services.media.worker

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageIoThumbnailGeneratorTest {
    @Test
    fun `generateWebp resizes and center crops to 16 by 9`() {
        val sourceBytes = pngBytes(width = 1200, height = 600)
        val generator = ScrimageThumbnailGenerator()

        val thumbnail =
            generator.generateWebp(
                sourceBytes = sourceBytes,
                maxSizePx = 512,
                targetAspectWidth = 16,
                targetAspectHeight = 9,
            )

        assertEquals(512, thumbnail.width)
        assertEquals(288, thumbnail.height)
        assertTrue(thumbnail.bytes.isNotEmpty())
    }

    @Test
    fun `generateWebp keeps small images without upscaling and crops 16 by 9`() {
        val sourceBytes = pngBytes(width = 320, height = 240)
        val generator = ScrimageThumbnailGenerator()

        val thumbnail =
            generator.generateWebp(
                sourceBytes = sourceBytes,
                maxSizePx = 512,
                targetAspectWidth = 16,
                targetAspectHeight = 9,
            )

        assertEquals(320, thumbnail.width)
        assertEquals(180, thumbnail.height)
    }

    @Test
    fun `generateWebp rejects images above source pixel limit before decoding`() {
        val sourceBytes = pngBytes(width = 20, height = 20)
        val generator = ScrimageThumbnailGenerator(maxSourcePixels = 100)

        val error =
            assertThrows(UnsupportedImageException::class.java) {
                generator.generateWebp(
                    sourceBytes = sourceBytes,
                    maxSizePx = 512,
                    targetAspectWidth = 16,
                    targetAspectHeight = 9,
                )
            }

        assertEquals("Image dimensions exceed thumbnail input limit", error.message)
    }

    @Test
    fun `generateWebp preserves decode failure diagnostic`() {
        val generator = ScrimageThumbnailGenerator()

        val error =
            assertThrows(UnsupportedImageException::class.java) {
                generator.generateWebp(
                    sourceBytes = byteArrayOf(1, 2, 3),
                    maxSizePx = 512,
                    targetAspectWidth = 16,
                    targetAspectHeight = 9,
                )
            }

        assertEquals("Unsupported image format", error.message)
    }

    @Test
    fun `generateWebp crops to square for icon variant`() {
        val sourceBytes = pngBytes(width = 800, height = 600)
        val generator = ScrimageThumbnailGenerator()

        val icon =
            generator.generateWebp(
                sourceBytes = sourceBytes,
                maxSizePx = 256,
                targetAspectWidth = 1,
                targetAspectHeight = 1,
            )

        assertEquals(256, icon.width)
        assertEquals(256, icon.height)
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
