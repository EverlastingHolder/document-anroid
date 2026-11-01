package ru.passport.scanner.export

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
        val archive = "synthetic encrypted archive bytes".toByteArray()
        val uri = MemoryExportStore.publish(context, archive)
        val chooser = PassportEmail.createChooserIntent(context, uri) { query ->
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
        val targetUri = target?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
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
            assertEquals(targetUri, choice.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertTrue(choice.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertNotNull(choice.clipData)
            assertEquals(targetUri, choice.clipData?.getItemAt(0)?.uri)
        }
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(targetUri, chooser.clipData?.getItemAt(0)?.uri)
        assertFalse(choices.any { it.component?.packageName == "example.generic" })
        assertNotNull(targetUri)

        context.contentResolver.openInputStream(targetUri!!).use { stream ->
            assertNotNull("Memory provider URI must grant readable access", stream)
            assertEquals(String(archive), String(stream!!.readBytes()))
        }
        MemoryExportStore.clear(context, targetUri)
        archive.fill(0)
    }

    @Test
    fun noEmailAndAttachmentIntersectionThrowsRecoverableException() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = byteArrayOf(1)
        val uri = MemoryExportStore.publish(context, archive)
        try {
            val failure = runCatching {
                PassportEmail.createChooserIntent(context, uri) { emptyList() }
            }.exceptionOrNull()
            assertTrue(failure is PassportEmail.NoEmailHandlerException)
        } finally {
            MemoryExportStore.clear(context, uri)
            archive.fill(0)
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

}
