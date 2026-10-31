package ru.passport.scanner.export

import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValidator

class PassportExporterTest {
    @Test
    fun archiveHasOneTextEntryEncryptedWithPassword123AndUsesCurrentSnapshotValues() {
        val cacheDir = Files.createTempDirectory("passport-export-test").toFile()
        val draft = fixture("OCR draft")
        val snapshot = fixture("User edited")

        try {
            val archive = kotlinx.coroutines.runBlocking {
                PassportExporter(cacheDir).createArchive(snapshot)
            }

            SevenZFile(archive, "123".toCharArray()).use { input ->
                val entry = input.nextEntry
                assertEquals("document.txt", entry.name)
                val bytes = readEntry(input)
                val exportedText = String(bytes, StandardCharsets.UTF_8)
                assertEquals(PassportTextSerializer.serialize(snapshot), exportedText)
                assertTrue(exportedText.contains("Серия: 0012\nНомер: 000345\n"))
                assertFalse(exportedText.contains(draft.values.getValue(PassportField.SURNAME)))
                assertEquals(null, input.nextEntry)
            }

            val rejected = runCatching {
                SevenZFile(archive, "wrong".toCharArray()).use { input ->
                    input.nextEntry
                    readEntry(input)
                }
            }
            assertTrue("Archive must reject an incorrect password", rejected.isFailure)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun serializerWritesAllElevenRussianLabelsInStableOrderAndLocalizesGender() {
        val snapshot = fixture("value")
        val lines = PassportTextSerializer.serialize(snapshot).trimEnd().lines()

        assertEquals(11, lines.size)
        assertEquals("Пол: МУЖ.", lines[3])
        assertEquals(
            PassportField.entries.map { field ->
                val value = if (field == PassportField.GENDER) "МУЖ." else snapshot.values.getValue(field)
                "${field.label}: $value"
            },
            lines,
        )
    }

    @Test
    fun encryptedTextEntryExportsFemaleLabelWhileSnapshotKeepsCanonicalCode() {
        val cacheDir = Files.createTempDirectory("passport-female-export-test").toFile()
        val snapshot = fixture("value").let { base ->
            PassportSnapshot(base.values + (PassportField.GENDER to "F"), base.revision)
        }

        try {
            val archive = kotlinx.coroutines.runBlocking { PassportExporter(cacheDir).createArchive(snapshot) }
            SevenZFile(archive, "123".toCharArray()).use { input ->
                assertEquals("document.txt", input.nextEntry.name)
                val text = String(readEntry(input), StandardCharsets.UTF_8)
                assertTrue(text.contains("Пол: ЖЕН.\n"))
                assertFalse(text.contains("Пол: F\n"))
            }
            assertEquals("F", snapshot.values.getValue(PassportField.GENDER))
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun refusesToExportCurrentValuesThatFailValidation() {
        val cacheDir = Files.createTempDirectory("passport-invalid-export-test").toFile()
        val invalidValues = fixture("ИВАНОВ").values.toMutableMap().apply {
            put(PassportField.SERIES, "12")
        }

        try {
            val failure = runCatching {
                kotlinx.coroutines.runBlocking {
                    PassportExporter(cacheDir).createArchive(PassportSnapshot(invalidValues, revision = 8))
                }
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(PassportValidator.validate(invalidValues).isNotEmpty())
            assertEquals(emptyList<File>(), cacheDir.listFiles()?.toList())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun writesSyntheticArchiveForIndependent7ZipVerification() {
        val cacheDir = Files.createTempDirectory("passport-independent-7z-proof").toFile()
        val snapshot = syntheticProofFixture()
        val projectDir = File(System.getProperty("user.dir")).let { workingDir ->
            if (File(workingDir, "app").isDirectory) workingDir else workingDir.parentFile
        }
        val proofDirectory = File(projectDir, "build/verification")
        val proofArchive = File(proofDirectory, "archive-proof.7z")
        val expectedTextFile = File(proofDirectory, "archive-proof.expected.txt")

        try {
            val archive = kotlinx.coroutines.runBlocking {
                PassportExporter(cacheDir).createArchive(snapshot)
            }
            check(proofDirectory.mkdirs() || proofDirectory.isDirectory)
            Files.copy(archive.toPath(), proofArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            expectedTextFile.writeText(EXPECTED_PROOF_TEXT, StandardCharsets.UTF_8)

            SevenZFile(proofArchive, "123".toCharArray()).use { input ->
                assertEquals("document.txt", input.nextEntry.name)
                assertEquals(EXPECTED_PROOF_TEXT, String(readEntry(input), StandardCharsets.UTF_8))
                assertEquals(null, input.nextEntry)
            }
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun fixture(name: String): PassportSnapshot {
        val values = PassportField.entries.associateWith { field ->
            when (field) {
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
            }
        }
        return PassportSnapshot(values, revision = 7)
    }

    private fun syntheticProofFixture(): PassportSnapshot = PassportSnapshot(
        mapOf(
            PassportField.SURNAME to "СИНТЕТИЧЕСКИЙ",
            PassportField.GIVEN_NAME to "ПРИМЕР",
            PassportField.PATRONYMIC to "",
            PassportField.GENDER to "F",
            PassportField.BIRTH_DATE to "1999-12-31",
            PassportField.BIRTH_PLACE to "ГОРОД ОБРАЗЕЦ",
            PassportField.SERIES to "0042",
            PassportField.NUMBER to "000007",
            PassportField.ISSUE_DATE to "2020-01-02",
            PassportField.ISSUER to "ТЕСТОВОЕ УЧРЕЖДЕНИЕ",
            PassportField.DEPARTMENT_CODE to "123-456",
        ),
        revision = 42,
    )

    private fun readEntry(input: SevenZFile): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private companion object {
        const val EXPECTED_PROOF_TEXT =
            "Фамилия: СИНТЕТИЧЕСКИЙ\n" +
                "Имя: ПРИМЕР\n" +
                "Отчество: \n" +
            "Пол: ЖЕН.\n" +
                "Дата рождения: 1999-12-31\n" +
                "Место рождения: ГОРОД ОБРАЗЕЦ\n" +
                "Серия: 0042\n" +
                "Номер: 000007\n" +
                "Дата выдачи: 2020-01-02\n" +
                "Кем выдан: ТЕСТОВОЕ УЧРЕЖДЕНИЕ\n" +
                "Код подразделения: 123-456\n"
    }
}
