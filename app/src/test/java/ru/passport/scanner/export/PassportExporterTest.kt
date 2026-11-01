package ru.passport.scanner.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.channels.SeekableByteChannel
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportValidator

class PassportExporterTest {
    @Test
    fun archiveIsEncryptedInMemoryAndContainsOnlyCurrentSnapshotValues() {
        val cacheDir = Files.createTempDirectory("passport-export-test").toFile()
        val snapshot = fixture("User edited")
        val draft = fixture("OCR draft")
        var archive = ByteArray(0)

        try {
            archive = kotlinx.coroutines.runBlocking { PassportExporter().createArchive(snapshot) }
            assertTrue("Archive must be a 7z byte sequence", archive.size > 32)
            assertEquals("Export must not create app files", 0, cacheDir.listFiles().orEmpty().size)

            withArchive(archive, "123") { input ->
                val entry = input.nextEntry
                assertEquals("document.txt", entry.name)
                val bytes = readEntry(input)
                val exportedText = String(bytes, StandardCharsets.UTF_8)
                bytes.fill(0)
                assertEquals(PassportTextSerializer.serialize(snapshot), exportedText)
                assertTrue(exportedText.contains("Серия: 0012\nНомер: 000345\n"))
                assertFalse(exportedText.contains(draft.values.getValue(PassportField.SURNAME)))
                assertEquals(null, input.nextEntry)
            }

            val rejected = runCatching { withArchive(archive, "wrong") { input -> input.nextEntry; readEntry(input) } }
            assertTrue("Archive must reject an incorrect password", rejected.isFailure)
        } finally {
            archive.fill(0)
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun serializerWritesAllElevenRussianLabelsInStableOrderAndLocalizesGender() {
        val snapshot = fixture("value")
        val lines = PassportTextSerializer.serialize(snapshot).trimEnd().lines()

        assertEquals(11, lines.size)
        assertEquals("Пол: МУЖ.", lines[3])
        assertEquals(PassportField.entries.map { field ->
            val value = if (field == PassportField.GENDER) "МУЖ." else snapshot.values.getValue(field)
            "${field.label}: $value"
        }, lines)
    }

    @Test
    fun encryptedTextEntryExportsFemaleLabelWhileSnapshotKeepsCanonicalCode() {
        val base = fixture("value")
        val snapshot = PassportSnapshot(base.values + (PassportField.GENDER to "F"), base.revision)
        val archive = kotlinx.coroutines.runBlocking { PassportExporter().createArchive(snapshot) }
        try {
            withArchive(archive, "123") { input ->
                assertEquals("document.txt", input.nextEntry.name)
                val bytes = readEntry(input)
                try {
                    val text = String(bytes, StandardCharsets.UTF_8)
                    assertTrue(text.contains("Пол: ЖЕН.\n"))
                    assertFalse(text.contains("Пол: F\n"))
                } finally { bytes.fill(0) }
            }
            assertEquals("F", snapshot.values.getValue(PassportField.GENDER))
        } finally { archive.fill(0) }
    }

    @Test
    fun refusesToExportCurrentValuesThatFailValidation() {
        val invalidValues = fixture("ИВАНОВ").values.toMutableMap().apply { put(PassportField.SERIES, "12") }
        val failure = runCatching {
            kotlinx.coroutines.runBlocking { PassportExporter().createArchive(PassportSnapshot(invalidValues, revision = 8)) }
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(PassportValidator.validate(invalidValues).isNotEmpty())
    }

    @Test
    fun removesOnlyKnownLegacyExportStagingDirectories() {
        val cacheDir = Files.createTempDirectory("passport-legacy-export-test").toFile()
        val known = java.io.File(cacheDir, "exports/passport-export-old/document.7z").apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        val unrelated = java.io.File(cacheDir, "exports/user-file.txt").apply { writeText("keep") }
        try {
            kotlinx.coroutines.runBlocking { PassportExporter().cleanupLegacyExportStaging(cacheDir) }
            assertFalse(known.exists())
            assertTrue(unrelated.isFile)
            assertEquals("keep", unrelated.readText())
        } finally { cacheDir.deleteRecursively() }
    }

    private fun fixture(name: String): PassportSnapshot = PassportSnapshot(
        PassportField.entries.associateWith { field -> when (field) {
            PassportField.SURNAME -> name
            PassportField.GIVEN_NAME -> "АЛЕКСЕЙ"
            PassportField.PATRONYMIC -> ""
            PassportField.GENDER -> "M"
            PassportField.BIRTH_DATE -> "1990-04-12"
            PassportField.BIRTH_PLACE -> "ГОРОД ПРИМЕР"
            PassportField.SERIES -> "0012"
            PassportField.NUMBER -> "000345"
            PassportField.ISSUE_DATE -> "2010-06-18"
            PassportField.ISSUER -> "ОТДЕЛ ПРИМЕРА"
            PassportField.DEPARTMENT_CODE -> "123-456"
        } },
        revision = 7,
    )

    private fun withArchive(bytes: ByteArray, password: String, block: (SevenZFile) -> Unit) {
        val channel = TestByteArrayChannel(bytes)
        try { SevenZFile(channel, password.toCharArray()).use(block) } finally { channel.close() }
    }

    private fun readEntry(input: SevenZFile): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
        output.toByteArray()
    }

    private class TestByteArrayChannel(private val source: ByteArray) : SeekableByteChannel {
        private var cursor = 0
        private var open = true
        override fun read(dst: ByteBuffer): Int {
            check(open)
            if (cursor >= source.size) return -1
            val count = minOf(dst.remaining(), source.size - cursor)
            dst.put(source, cursor, count)
            cursor += count
            return count
        }
        override fun write(src: ByteBuffer): Int = error("read-only")
        override fun position(): Long = cursor.toLong()
        override fun position(newPosition: Long): SeekableByteChannel = apply { cursor = newPosition.toInt() }
        override fun size(): Long = source.size.toLong()
        override fun truncate(size: Long): SeekableByteChannel = error("read-only")
        override fun isOpen(): Boolean = open
        override fun close() { open = false }
    }
}
