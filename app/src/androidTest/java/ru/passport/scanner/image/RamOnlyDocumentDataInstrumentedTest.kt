package ru.passport.scanner.image

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.passport.scanner.data.secure.LegacyDocumentCleanup
import ru.passport.scanner.export.PassportExporter
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import android.graphics.Bitmap

@RunWith(AndroidJUnit4::class)
class RamOnlyDocumentDataInstrumentedTest {
    @Test
    fun imageConversionUsesMemoryAndStartupRemovesOnlyKnownLegacyDocumentPaths() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ScanImageFiles.cleanupLegacyScanFiles(context.cacheDir)
        PassportExporter().cleanupLegacyExportStaging(context.cacheDir)
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val encoded = ByteArrayOutputStream()
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, encoded))
        } finally {
            bitmap.recycle()
        }
        val source = encoded.toByteArray()
        val original = source.copyOf()
        val image = ScanImageFiles.createImage("ram-${UUID.randomUUID()}", source, "image/jpeg")
        assertTrue(source.all { it == 0.toByte() })
        assertArrayEquals(original, image.bytes)
        assertTrue(image.preview != null)
        assertFalse(File(context.cacheDir, "scans").exists())

        val legacyScans = File(context.cacheDir, "scans")
        assertTrue(legacyScans.mkdirs())
        File(legacyScans, "legacy.upload").writeBytes(byteArrayOf(1, 2, 3))
        val exports = File(context.cacheDir, "exports")
        val oldAppExport = File(exports, "passport-export-legacy")
        assertTrue(oldAppExport.mkdirs())
        File(oldAppExport, "document.7z").writeBytes(byteArrayOf(4))
        val otherCacheChild = File(exports, "unmanaged-${UUID.randomUUID()}")
        assertTrue(otherCacheChild.mkdirs())
        File(otherCacheChild, "keep.tmp").writeBytes(byteArrayOf(5))

        ScanImageFiles.cleanupLegacyScanFiles(context.cacheDir)
        PassportExporter().cleanupLegacyExportStaging(context.cacheDir)

        assertFalse(legacyScans.exists())
        assertFalse(oldAppExport.exists())
        assertTrue(File(otherCacheChild, "keep.tmp").isFile)
        otherCacheChild.deleteRecursively()
        Unit
    }

    @Test
    fun oldPendingJournalCleanupDoesNotTouchConnectionRecord() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val privateDir = File(context.noBackupFilesDir, "passport-secure-v1")
        assertTrue(privateDir.mkdirs() || privateDir.isDirectory)
        val oldRecord = File(privateDir, "pending-scan-v1.enc")
        val oldBackup = File(privateDir, "pending-scan-v1.enc.bak")
        val connectionRecord = File(privateDir, "api-connection-v1.enc")
        oldRecord.writeBytes(byteArrayOf(1))
        oldBackup.writeBytes(byteArrayOf(2))
        connectionRecord.writeBytes(byteArrayOf(3))

        LegacyDocumentCleanup.clearPendingJobRecord(context)

        assertFalse(oldRecord.exists())
        assertFalse(oldBackup.exists())
        assertTrue(connectionRecord.readBytes().contentEquals(byteArrayOf(3)))
        connectionRecord.delete()
    }
}
