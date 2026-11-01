package ru.passport.scanner.export

import android.provider.OpenableColumns
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryExportProviderInstrumentedTest {
    @Test
    fun encryptedArchiveIsReadOnlyRamOnlyReopenableAndClearedByUri() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val beforeFiles = appFiles(context)
        val original = ByteArray(4_096) { ((it * 31) % 251).toByte() }
        val expected = original.copyOf()
        var currentUri: Uri? = null
        try {
            val uri = MemoryExportStore.publish(context, original)
            currentUri = uri
            original.fill(0)

            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.exports", uri.authority)
            assertEquals(2, uri.pathSegments.size)
            assertEquals("exports", uri.pathSegments[0])
            assertEquals("application/x-7z-compressed", resolver.getType(uri))
            val metadata = resolver.query(uri, null, null, null, null) ?: error("Provider returned no metadata cursor.")
            metadata.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("document.7z", cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                assertEquals(expected.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
            }

            val firstOpen = resolver.openInputStream(uri) ?: error("Provider returned no read stream.")
            val replacementBytes = byteArrayOf(0x37, 0x7a, 0x01, 0x02, 0x03)
            val replacement = MemoryExportStore.publish(context, replacementBytes)
            currentUri = replacement
            replacementBytes.fill(0)
            firstOpen.use { assertArrayEquals(expected, it.readBytes()) }
            assertFalse(MemoryExportStore.clear(context, uri))
            assertTrue(runCatching { resolver.openInputStream(uri) }.isFailure)

            val secondOpen = resolver.openInputStream(replacement) ?: error("Provider returned no second stream.")
            secondOpen.use { assertArrayEquals(byteArrayOf(0x37, 0x7a, 1, 2, 3), it.readBytes()) }
            assertTrue(runCatching { resolver.openFileDescriptor(replacement, "w") }.isFailure)

            val invalid = Uri.parse("content://${context.packageName}.exports/exports/../../files/secret")
            assertTrue(runCatching { resolver.openInputStream(invalid) }.isFailure)
            assertTrue(MemoryExportStore.clear(context, replacement))
            currentUri = null
            assertTrue(runCatching { resolver.openInputStream(replacement) }.isFailure)
            assertEquals(beforeFiles, appFiles(context))
        } finally {
            currentUri?.let { MemoryExportStore.clear(context, it) }
        }
    }

    private fun appFiles(context: android.content.Context): Set<String> =
        listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)
            .flatMap { root ->
                root.walkTopDown().filter(File::isFile).map { file ->
                    "${root.name}/${file.relativeTo(root).invariantSeparatorsPath}"
                }.toList()
            }.toSet()
}
