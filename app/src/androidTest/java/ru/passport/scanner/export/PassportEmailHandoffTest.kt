package ru.passport.scanner.export

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportField

@RunWith(AndroidJUnit4::class)
class PassportEmailHandoffTest {
    @Test
    fun chooserAttachmentIsReadableFromSeparateFakeMailAppUid() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testContext = instrumentation.context
        val archiveBytes = PassportExporter().createArchive(currentSnapshot())
        val archiveUri = MemoryExportStore.publish(targetContext, archiveBytes)
        val expectedHash = sha256(archiveBytes)
        val resultAction = "${targetContext.packageName}.${FakeEmailActivity.RESULT_ACTION}.${UUID.randomUUID()}"
        val latch = CountDownLatch(1)
        var result: Intent? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                result = intent
                latch.countDown()
            }
        }

        ContextCompat.registerReceiver(
            targetContext,
            receiver,
            IntentFilter(resultAction),
            ContextCompat.RECEIVER_EXPORTED,
        )
        try {
            val mailtoHandlers = targetContext.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )
            val archiveHandlers = targetContext.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_SEND).setType(PassportEmail.ARCHIVE_MIME_TYPE),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )
            assertTrue(
                "The test APK fake mail activity must be visible for mailto.",
                mailtoHandlers.any { it.activityInfo?.packageName == testContext.packageName },
            )
            assertTrue(
                "The test APK fake mail activity must accept the 7z MIME type.",
                archiveHandlers.any { it.activityInfo?.packageName == testContext.packageName },
            )

            val chooser = PassportEmail.createChooserIntent(targetContext, archiveUri)
            val candidates = listOfNotNull(
                chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT),
            ) + chooser.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)
                .orEmpty()
                .filterIsInstance<Intent>()
            val fakeMailTarget = candidates.firstOrNull {
                it.component?.packageName == testContext.packageName &&
                    it.component?.className == FakeEmailActivity::class.java.name
            }
            assertNotNull("Email-only chooser must include its test email handler.", fakeMailTarget)

            fakeMailTarget!!
                .putExtra(FakeEmailActivity.EXTRA_RESULT_ACTION, resultAction)
                .putExtra(FakeEmailActivity.EXTRA_RESULT_PACKAGE, targetContext.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            targetContext.startActivity(fakeMailTarget)

            assertTrue("Fake email app did not report back in time.", latch.await(10, TimeUnit.SECONDS))
            val handoffResult = result
            assertNotNull(handoffResult)
            assertEquals("", handoffResult!!.getStringExtra(FakeEmailActivity.EXTRA_ERROR).orEmpty())
            assertEquals(
                listOf(PassportEmail.RECIPIENT),
                handoffResult.getStringArrayExtra(FakeEmailActivity.EXTRA_RECIPIENTS)?.toList(),
            )
            assertEquals("test@mail.ru", handoffResult.getStringExtra(FakeEmailActivity.EXTRA_RECIPIENT))
            assertTrue(handoffResult.getBooleanExtra(FakeEmailActivity.EXTRA_HAS_7Z_SIGNATURE, false))
            assertEquals(expectedHash, handoffResult.getStringExtra(FakeEmailActivity.EXTRA_SHA256))
            val fakeActivityUid = handoffResult.getIntExtra(FakeEmailActivity.EXTRA_UID, -1)
            assertEquals(testContext.applicationInfo.uid, fakeActivityUid)
            assertNotEquals("Fake email activity must run outside the target app UID.", Process.myUid(), fakeActivityUid)
        } finally {
            targetContext.unregisterReceiver(receiver)
            MemoryExportStore.clear(targetContext, archiveUri)
            archiveBytes.fill(0)
        }
    }

    private fun currentSnapshot() = PassportSnapshot(
        PassportField.entries.associateWith { field ->
            when (field) {
                PassportField.SURNAME -> "HANDOFF_TEST"
                PassportField.GIVEN_NAME -> "SYNTHETIC"
                PassportField.PATRONYMIC -> ""
                PassportField.GENDER -> "F"
                PassportField.BIRTH_DATE -> "1990-04-12"
                PassportField.BIRTH_PLACE -> "SYNTHETIC TEST CITY"
                PassportField.SERIES -> "0012"
                PassportField.NUMBER -> "000345"
                PassportField.ISSUE_DATE -> "2010-06-18"
                PassportField.ISSUER -> "SYNTHETIC TEST OFFICE"
                PassportField.DEPARTMENT_CODE -> "123-456"
            }
        },
        revision = 5,
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
