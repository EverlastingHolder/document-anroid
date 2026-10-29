package ru.passport.scanner.export

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportField

@RunWith(AndroidJUnit4::class)
class PassportEmailTest {
    @Test
    fun chooserIncludesOnlyEmailAndArchiveCapableTargetsWithReadableAttachment() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = PassportExporter(context.cacheDir).createArchive(currentSnapshot())
        val chooser = PassportEmail.createChooserIntent(context, archive) { query ->
            when (query.action) {
                Intent.ACTION_SENDTO -> listOf(
                    resolveInfo("example.mail", "example.mail.ComposeActivity"),
                    resolveInfo("example.onlymail", "example.onlymail.ComposeActivity"),
                )
                Intent.ACTION_SEND -> {
                    assertEquals(PassportEmail.ARCHIVE_MIME_TYPE, query.type)
                    listOf(
                        resolveInfo("example.mail", "example.mail.ShareActivity"),
                        resolveInfo("example.mail", "example.mail.AlternateShareActivity"),
                        resolveInfo("example.generic", "example.generic.ShareActivity"),
                    )
                }
                else -> error("Unexpected query action: ${query.action}")
            }
        }
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        val uri = target?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        val additional = chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)
            ?.filterIsInstance<Intent>()
            .orEmpty()
        val choices = listOfNotNull(target) + additional

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_SEND, target?.action)
        assertEquals(2, choices.size)
        assertEquals(
            setOf("example.mail/example.mail.ShareActivity", "example.mail/example.mail.AlternateShareActivity"),
            choices.map { intent ->
                "${intent.component?.packageName}/${intent.component?.className}"
            }.toSet(),
        )
        choices.forEach { choice ->
            assertEquals(Intent.ACTION_SEND, choice.action)
            assertEquals(PassportEmail.ARCHIVE_MIME_TYPE, choice.type)
            assertEquals(
                listOf(PassportEmail.RECIPIENT),
                choice.getStringArrayExtra(Intent.EXTRA_EMAIL)?.toList(),
            )
            assertEquals(uri, choice.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertTrue(choice.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertNotNull(choice.clipData)
            assertEquals(uri, choice.clipData?.getItemAt(0)?.uri)
        }
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, chooser.clipData?.getItemAt(0)?.uri)
        assertFalse(choices.any { it.component?.packageName == "example.generic" })
        assertNotNull(uri)

        context.contentResolver.openInputStream(uri!!).use { stream ->
            assertNotNull("FileProvider URI must grant readable access", stream)
            val sharedArchive = java.io.File(context.cacheDir, "provider-test.7z")
            try {
                sharedArchive.outputStream().use { output -> stream!!.copyTo(output) }
                SevenZFile(sharedArchive, "123".toCharArray()).use { input ->
                    assertEquals("passport.txt", input.nextEntry.name)
                    assertTrue(String(readEntry(input), StandardCharsets.UTF_8).contains("Фамилия: ТЕСТОВ"))
                }
            } finally {
                sharedArchive.delete()
                archive.parentFile?.deleteRecursively()
            }
        }
    }

    @Test
    fun noEmailAndAttachmentIntersectionThrowsRecoverableException() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = java.io.File(context.cacheDir, "email-no-handler-test.7z").apply { writeBytes(byteArrayOf(1)) }
        try {
            val failure = runCatching {
                PassportEmail.createChooserIntent(context, archive) { emptyList() }
            }.exceptionOrNull()
            assertTrue(failure is PassportEmail.NoEmailHandlerException)
            assertTrue("Archive must remain available for retry", archive.isFile)
        } finally {
            archive.delete()
        }
    }

    private fun currentSnapshot() = PassportSnapshot(
        PassportField.entries.associateWith { field ->
            when (field) {
                PassportField.SURNAME -> "ТЕСТОВ"
                PassportField.GIVEN_NAME -> "ТЕСТ"
                PassportField.PATRONYMIC -> ""
                PassportField.GENDER -> "F"
                PassportField.BIRTH_DATE -> "1990-04-12"
                PassportField.BIRTH_PLACE -> "ГОРОД ПРИМЕР"
                PassportField.SERIES -> "0012"
                PassportField.NUMBER -> "000345"
                PassportField.ISSUE_DATE -> "2010-06-18"
                PassportField.ISSUER -> "ОТДЕЛ ПРИМЕРА"
                PassportField.DEPARTMENT_CODE -> "123-456"
            }
        },
        revision = 1,
    )

    private fun resolveInfo(packageName: String, activityName: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = activityName
        }
    }

    private fun readEntry(input: SevenZFile): ByteArray = ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
