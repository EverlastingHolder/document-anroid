package ru.passport.scanner.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.passport.scanner.data.AcceptedJob
import ru.passport.scanner.data.ApiConnection
import ru.passport.scanner.data.AsyncUploadPayload
import ru.passport.scanner.data.JobState
import ru.passport.scanner.data.JobStatus
import ru.passport.scanner.data.PassportRepository
import ru.passport.scanner.data.PendingScanOperation
import ru.passport.scanner.data.PendingScanStore
import ru.passport.scanner.data.TerminalJobSnapshot
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportValue
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage
import ru.passport.scanner.presentation.ScanViewModel

@RunWith(AndroidJUnit4::class)
class AsyncJobProgressFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun queuedJobMovesToProcessingAndCanonicalDraftCanBeEditedAndExported() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = File(context.cacheDir, "async-progress-synthetic.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val archive = byteArrayOf(1, 2, 3)
        val openedArchive = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        val repository = ProgressRepository()
        val queuedGate = CompletableDeferred<Unit>()
        val processingGate = CompletableDeferred<Unit>()
        val connection = ApiConnection(
            baseUrl = "https://api.example.test",
            bearerToken = "pbt1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() }),
            connectionId = "instrumented-connection",
        )
        val viewModel = ScanViewModel(
            repository = repository,
            createArchive = { snapshot: PassportSnapshot ->
                assertEquals("TEST-EDITED", snapshot.values.getValue(PassportField.SURNAME))
                archive
            },
            pendingScanStore = InMemoryPendingScanStore(),
            apiConnectionProvider = { connection },
            pollPause = { attempt ->
                when (attempt) {
                    0 -> queuedGate.await()
                    1 -> processingGate.await()
                }
            },
        )
        viewModel.pickImage(ScanImage("synthetic-async-flow", image, "image/jpeg"))

        try {
            compose.setContent {
                MaterialTheme {
                    PassportScannerScreen(viewModel = viewModel, isLegacySync = false, onOpenEmail = openedArchive::set)
                }
            }
            compose.onNodeWithText("Отправить на распознавание").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.state.value.asyncScan.phase == ru.passport.scanner.presentation.AsyncScanState.Phase.QUEUED && repository.statusReads == 1
            }
            compose.onNodeWithText("В очереди").assertIsDisplayed()
            compose.onNodeWithText("Новый снимок").assertIsNotEnabled()
            queuedGate.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.state.value.asyncScan.phase == ru.passport.scanner.presentation.AsyncScanState.Phase.PROCESSING && repository.statusReads == 2
            }
            compose.onNodeWithText("Распознавание").assertIsDisplayed()
            processingGate.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.draft != null }
            compose.onNodeWithText("Данные документа").assertIsDisplayed()
            compose.onNodeWithTag("field_surname").performTextReplacement("TEST-EDITED")
            compose.onNodeWithText("Создать архив и открыть почту").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { openedArchive.get() != null }

            assertEquals(1, repository.submissions)
            assertEquals(3, repository.statusReads)
            assertTrue(archive.contentEquals(openedArchive.get()))
        } finally {
            viewModel.setForeground(false)
            image.delete()
            archive.fill(0)
        }
    }

    @Test
    fun currentProcessAcceptedJobWithoutImageAndCompletedDraftRemainsEditable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = byteArrayOf(4, 3, 2, 1)
        val openedArchive = java.util.concurrent.atomic.AtomicReference<ByteArray?>()
        val repository = ProgressRepository()
        val queuedGate = CompletableDeferred<Unit>()
        val processingGate = CompletableDeferred<Unit>()
        val connection = ApiConnection(
            baseUrl = "https://api.example.test",
            bearerToken = "pbt1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { (it + 1).toByte() }),
            connectionId = "instrumented-connection",
        )
        val pendingStore = InMemoryPendingScanStore(
            PendingScanOperation.Accepted(
                operationId = "123e4567-e89b-42d3-a456-426614174010",
                connectionId = connection.connectionId,
                jobId = JOB_ID,
            ),
        )
        val viewModel = ScanViewModel(
            repository = repository,
            createArchive = { snapshot ->
                assertEquals("TEST-RESTORED", snapshot.values.getValue(PassportField.SURNAME))
                assertEquals("F", snapshot.values.getValue(PassportField.GENDER))
                archive
            },
            pendingScanStore = pendingStore,
            apiConnectionProvider = { connection },
            pollPause = { attempt ->
                when (attempt) {
                    0 -> queuedGate.await()
                    1 -> processingGate.await()
                    else -> Unit
                }
            },
        )

        try {
            compose.setContent {
                MaterialTheme {
                    PassportScannerScreen(viewModel = viewModel, isLegacySync = false, onOpenEmail = openedArchive::set)
                }
            }
            viewModel.setForeground(true)
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.state.value.asyncScan.phase == ru.passport.scanner.presentation.AsyncScanState.Phase.QUEUED && repository.statusReads == 1
            }
            compose.onNodeWithText("В очереди").assertIsDisplayed()
            compose.onNodeWithText("Ожидание результата").assertIsDisplayed()
            compose.onNodeWithText("Снять фото").assertDoesNotExist()

            queuedGate.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000) {
                viewModel.state.value.asyncScan.phase == ru.passport.scanner.presentation.AsyncScanState.Phase.PROCESSING && repository.statusReads == 2
            }
            compose.onNodeWithText("Распознавание").assertIsDisplayed()
            processingGate.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000) { viewModel.state.value.draft != null }

            compose.onNodeWithText("Данные документа").assertIsDisplayed()
            compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("field_surname"))
            compose.onNodeWithTag("field_surname").performTextReplacement("TEST-RESTORED")
            compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("gender_F"))
            compose.onNodeWithTag("gender_F").performClick()
            compose.onNodeWithText("Создать архив и открыть почту").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { openedArchive.get() != null }

            assertEquals(0, repository.submissions)
            assertEquals(3, repository.statusReads)
            assertTrue(archive.contentEquals(openedArchive.get()))
        } finally {
            viewModel.setForeground(false)
            archive.fill(0)
        }
    }

    private class ProgressRepository : PassportRepository {
        @Volatile var submissions = 0
        @Volatile var statusReads = 0

        override suspend fun recognize(image: ScanImage, requestId: String): PassportDraft = error("Async flow expected")

        override suspend fun submit(
            payload: AsyncUploadPayload,
            requestId: String,
            idempotencyKey: String,
            connection: ApiConnection,
        ): AcceptedJob {
            submissions++
            assertEquals("instrumented-connection", connection.connectionId)
            assertTrue(payload.sizeBytes > 0)
            return AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", CREATED)
        }

        override suspend fun readStatus(jobId: String, requestId: String, connection: ApiConnection): JobStatus {
            assertEquals(JOB_ID, jobId)
            statusReads++
            return when (statusReads) {
                1 -> JobStatus.Queued(JOB_ID, CREATED, CREATED, null, null)
                2 -> JobStatus.Processing(JOB_ID, CREATED, STARTED, STARTED, null)
                else -> JobStatus.Completed(JOB_ID, CREATED, FINISHED, STARTED, FINISHED, "synthetic-test", draft())
            }
        }
    }

    private class InMemoryPendingScanStore(initial: PendingScanOperation? = null) : PendingScanStore {
        private var record: PendingScanOperation? = initial

        override suspend fun load(): PendingScanOperation? = record

        override suspend fun beginSubmitting(operation: PendingScanOperation.Submitting) {
            check(record == null)
            record = operation
        }

        override suspend fun markAccepted(operationId: String, jobId: String): Boolean {
            val current = record ?: return false
            if (current.operationId != operationId) return false
            record = PendingScanOperation.Accepted(operationId, current.connectionId, jobId)
            return true
        }

        override suspend fun markReceived(operationId: String, jobId: String, terminal: TerminalJobSnapshot): Boolean {
            val current = record as? PendingScanOperation.Accepted ?: return false
            if (current.operationId != operationId || current.jobId != jobId) return false
            record = PendingScanOperation.Received(operationId, current.connectionId, jobId, terminal, false)
            return true
        }

        override suspend fun markReceiptAcknowledged(operationId: String, jobId: String): Boolean {
            val current = record as? PendingScanOperation.Received ?: return false
            if (current.operationId != operationId || current.jobId != jobId) return false
            record = current.copy(receiptAcknowledged = true)
            return true
        }

        override suspend fun clear(operationId: String): Boolean {
            if (record?.operationId != operationId) return false
            record = null
            return true
        }
    }

    private companion object {
        const val JOB_ID = "123e4567-e89b-42d3-a456-426614174000"
        val CREATED = Instant.parse("2026-10-31T10:00:00Z")
        val STARTED = Instant.parse("2026-10-31T10:01:00Z")
        val FINISHED = Instant.parse("2026-10-31T10:02:00Z")

        fun draft() = PassportDraft(PassportField.entries.associateWith { field ->
            val value = when (field) {
                PassportField.SURNAME -> "TEST"
                PassportField.GIVEN_NAME -> "SAMPLE"
                PassportField.PATRONYMIC -> ""
                PassportField.GENDER -> "M"
                PassportField.BIRTH_DATE -> "1990-04-12"
                PassportField.BIRTH_PLACE -> "TEST CITY"
                PassportField.SERIES -> "0012"
                PassportField.NUMBER -> "000345"
                PassportField.ISSUE_DATE -> "2010-06-18"
                PassportField.ISSUER -> "TEST OFFICE"
                PassportField.DEPARTMENT_CODE -> "000-001"
            }
            PassportValue(value, RecognitionStatus.RECOGNIZED)
        })
    }
}
