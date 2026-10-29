package ru.passport.scanner.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanImageFilesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun normalExifJpegKeepsItsOriginalUploadBytes() {
        val file = temporaryFile("normal.jpg")
        try {
            writeSyntheticJpeg(file)
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }
            val originalBytes = file.readBytes()

            val image = ScanImageFiles.cameraImage(file)

            assertEquals("image/jpeg", image.mimeType)
            assertArrayEquals(originalBytes, image.file.readBytes())
        } finally {
            file.delete()
        }
    }

    @Test
    fun rotatedExifJpegHasUprightPixelsInTheUploadFileAndPreview() {
        val file = temporaryFile("rotated.jpg")
        try {
            writeSyntheticJpeg(file)
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }
            val originalBytes = file.readBytes()

            val image = ScanImageFiles.cameraImage(file)
            val options = BitmapFactoryBounds.read(image.file)
            val preview = requireNotNull(ScanImageFiles.decodePreview(image.file))
            try {
                assertEquals("image/jpeg", image.mimeType)
                assertFalse(originalBytes.contentEquals(image.file.readBytes()))
                assertEquals(60, options.first)
                assertEquals(100, options.second)
                assertEquals(options.first, preview.width)
                assertEquals(options.second, preview.height)
                assertFalse(
                    ExifInterface(image.file).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL,
                    ) == ExifInterface.ORIENTATION_ROTATE_90,
                )
                assertColorNear(Color.RED, preview.getPixel(preview.width / 2, preview.height / 4))
                assertColorNear(Color.BLUE, preview.getPixel(preview.width / 2, preview.height * 3 / 4))
            } finally {
                preview.recycle()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun pickerRejectsUndecodableImageAndRemovesItsPrivateCopy() {
        val source = temporaryFile("broken.jpg")
        val scansDirectory = File(context.cacheDir, "scans")
        val existingFiles = scansDirectory.listFiles().orEmpty().toSet()
        try {
            source.writeText("not an image", Charsets.UTF_8)

            val result = runCatching { ScanImageFiles.copyFromPicker(context, Uri.fromFile(source)) }

            assertTrue("Corrupt picker input must be rejected", result.isFailure)
            assertEquals(existingFiles, scansDirectory.listFiles().orEmpty().toSet())
        } finally {
            source.delete()
        }
    }

    private fun temporaryFile(name: String): File =
        File(context.cacheDir, "image-pipeline-${UUID.randomUUID()}-$name")

    private fun writeSyntheticJpeg(file: File) {
        val bitmap = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.RED)
                drawRect(50f, 0f, 100f, 60f, android.graphics.Paint().apply { color = Color.BLUE })
            }
            FileOutputStream(file).use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        val red = Color.red(actual)
        val blue = Color.blue(actual)
        if (expected == Color.RED) {
            assertTrue("Expected red-dominant pixel, got $actual", red > blue * 1.4)
        } else {
            assertTrue("Expected blue-dominant pixel, got $actual", blue > red * 1.4)
        }
    }

    private object BitmapFactoryBounds {
        fun read(file: File): Pair<Int, Int> {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            return options.outWidth to options.outHeight
        }
    }
}
