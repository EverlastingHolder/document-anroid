package ru.passport.scanner.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.passport.scanner.data.PassportRepository
import ru.passport.scanner.data.PassportRepositoryException
import ru.passport.scanner.data.AcceptedJob
import ru.passport.scanner.data.ApiConnection
import ru.passport.scanner.data.AsyncFailureCategory
import ru.passport.scanner.data.AsyncJobRepositoryException
import ru.passport.scanner.data.AsyncUploadPayload
import ru.passport.scanner.data.InMemoryPendingScanStore
import ru.passport.scanner.data.JobState
import ru.passport.scanner.data.JobStatus
import ru.passport.scanner.data.PendingScanOperation
import ru.passport.scanner.data.PendingScanStore
import ru.passport.scanner.data.PendingScanStoreException
import ru.passport.scanner.data.TerminalJobSnapshot
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValue
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage
import java.io.File
import java.time.Instant
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class ScanViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = kotlinx.coroutines.Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = kotlinx.coroutines.Dispatchers.resetMain()

    @Test
    fun ignoresLateResponseAfterNewImageEvenWhenRepositoryIgnoresCancellation() = runTest(dispatcher) {
        val repository = DeferredRepository()
        val vm = ScanViewModel(repository, createArchive = { archiveBytes() })
        vm.pickImage(image("first"))
        vm.recognize()
        runCurrent()
        vm.pickImage(image("second"))
        vm.recognize()
        runCurrent()

        repository.results[0].complete(draft("OLD"))
        runCurrent()
        assertNull(vm.state.value.draft)
        assertEquals("second", vm.state.value.image?.id)

        repository.results[1].complete(draft("NEW"))
        advanceUntilIdle()
        assertEquals("NEW", vm.state.value.value(PassportField.SURNAME))
    }

    @Test
    fun userEditDuringRecognitionKeepsEditAndDropsDraftResponse() = runTest(dispatcher) {
        val repository = DeferredRepository()
        val vm = ScanViewModel(repository, createArchive = { archiveBytes() })
        vm.pickImage(image("same"))
        vm.recognize()
        runCurrent()
        vm.editField(PassportField.SURNAME, "EDITED")

        repository.results.single().complete(draft("OCR"))
        advanceUntilIdle()

        assertNull(vm.state.value.draft)
        assertEquals("EDITED", vm.state.value.value(PassportField.SURNAME))
        assertFalse(vm.state.value.isRecognizing)
    }

    @Test
    fun ignoresRecognitionFailuresAfterUserEdit() = runTest(dispatcher) {
        val failures = listOf(
            PassportRepositoryException("repository failure"),
            Exception("generic failure"),
        )

        failures.forEachIndexed { index, failure ->
            val repository = DeferredRepository()
            val vm = ScanViewModel(repository, createArchive = { archiveBytes() })
            vm.pickImage(image("same-$index"))
            vm.recognize()
            runCurrent()
            vm.editField(PassportField.SURNAME, "EDITED-$index")

            repository.results.single().completeExceptionally(failure)
            advanceUntilIdle()

            assertEquals("EDITED-$index", vm.state.value.value(PassportField.SURNAME))
            assertNull(vm.state.value.draft)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.isRecognizing)
        }
    }

    @Test
    fun displaysCurrentRecognitionFailuresAndResetsLoading() = runTest(dispatcher) {
        val failures = listOf(
            PassportRepositoryException("repository failure") to "repository failure",
            Exception("generic failure") to "Не удалось распознать изображение. Проверьте соединение и повторите запрос.",
        )

        failures.forEachIndexed { index, (failure, expectedError) ->
            val repository = DeferredRepository()
            val vm = ScanViewModel(repository, createArchive = { archiveBytes() })
            vm.pickImage(image("current-$index"))
            vm.recognize()
            runCurrent()
            assertTrue(vm.state.value.isRecognizing)

            repository.results.single().completeExceptionally(failure)
            advanceUntilIdle()

            assertEquals(expectedError, vm.state.value.error)
            assertNull(vm.state.value.draft)
            assertFalse(vm.state.value.isRecognizing)
        }
    }

    @Test
    fun exportsImmediatelyAfterRecognitionAndUsesCurrentEditedValues() = runTest(dispatcher) {
        var exported: PassportSnapshot? = null
        val vm = ScanViewModel(ImmediateRepository(draft("IVANOV")), createArchive = { snapshot ->
            exported = snapshot
            archiveBytes()
        })
        vm.pickImage(image("current"))
        vm.recognize()
        advanceUntilIdle()

        vm.prepareShare()
        advanceUntilIdle()
        assertEquals("IVANOV", exported?.values?.get(PassportField.SURNAME))
        assertNotNull(vm.state.value.shareRequest)

        vm.editField(PassportField.SURNAME, "CORRECTED")
        assertFalse(vm.state.value.isExporting)
        vm.prepareShare()
        advanceUntilIdle()
        assertEquals("CORRECTED", exported?.values?.get(PassportField.SURNAME))
    }

    @Test
    fun staleArchiveResultDoesNotCreateShareEventAfterEditAndShareEventConsumesOnce() = runTest(dispatcher) {
        val archiveGate = CompletableDeferred<ByteArray>()
        var archiveInput: PassportSnapshot? = null
        var archiveCalls = 0
        val vm = ScanViewModel(ImmediateRepository(draft("IVANOV")), createArchive = {
            archiveInput = it
            if (archiveCalls++ == 0) archiveGate.await() else archiveBytes()
        })
        vm.pickImage(image("current"))
        vm.recognize()
        advanceUntilIdle()
        vm.prepareShare()
        runCurrent()
        assertEquals(vm.state.value.currentValues(), archiveInput?.values)

        vm.editField(PassportField.SURNAME, "CORRECTED")
        archiveGate.complete("stale.7z".toByteArray())
        advanceUntilIdle()
        assertNull(vm.state.value.shareRequest)

        vm.prepareShare()
        advanceUntilIdle()
        val share = vm.state.value.shareRequest
        assertNotNull(share)
        assertArrayEquals(archiveBytes(), vm.consumeShareRequest(share!!.id))
        assertNull(vm.state.value.shareRequest)
        assertNull(vm.consumeShareRequest(share.id))
    }

    @Test
    fun invalidValuesPreventArchiveCreationAndExposeFieldErrors() = runTest(dispatcher) {
        var archiveCalls = 0
        val vm = ScanViewModel(ImmediateRepository(draft("IVANOV")), createArchive = {
            archiveCalls++
            archiveBytes()
        })
        vm.pickImage(image("current"))
        vm.recognize()
        advanceUntilIdle()
        vm.editField(PassportField.SERIES, "12")

        vm.prepareShare()
        advanceUntilIdle()

        assertEquals(0, archiveCalls)
        assertNull(vm.state.value.shareRequest)
        assertEquals(PassportField.SERIES, vm.state.value.validationErrors.single().field)
    }

    @Test
    fun ambiguousPostRetriesWithSameKeyAndExactBytesThenPollsAcceptedJob() = runTest(dispatcher) {
        val uploadFile = temporaryImage("same-image-bytes")
        try {
            val repository = AsyncRepository().apply {
                submitFailures += AsyncJobRepositoryException("temporary", AsyncFailureCategory.TIMEOUT)
                accepted = AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                statuses += JobStatus.Completed(
                    JOB_ID,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:02Z"),
                    Instant.parse("2026-01-01T00:00:01Z"),
                    Instant.parse("2026-01-01T00:00:02Z"),
                    "old-pipeline",
                    draft("ASYNC"),
                )
            }
            val store = MemoryPendingScanStore()
            val vm = asyncViewModel(repository, store, { connection })
            vm.pickImage(ScanImage("async", uploadFile, "image/jpeg; charset=binary"))
            vm.recognize()
            advanceUntilIdle()

            assertEquals(2, repository.submissions.size)
            assertEquals(repository.submissions[0].second, repository.submissions[1].second)
            assertEquals("same-image-bytes", repository.submissions[0].first)
            assertEquals(repository.submissions[0].first, repository.submissions[1].first)
            assertEquals(1, repository.statusReads.size)
            assertEquals(AsyncScanState.Phase.COMPLETED, vm.state.value.asyncScan.phase)
            assertEquals("ASYNC", vm.state.value.value(PassportField.SURNAME))
            assertTrue(store.current is PendingScanOperation.Received)
            assertEquals(1, repository.receiptAcks)
        } finally {
            uploadFile.delete()
        }
    }

    @Test
    fun terminalResultIsAppliedToLiveStateBeforeReceiptIsAcknowledged() = runTest(dispatcher) {
        val uploadFile = temporaryImage("receipt-after-live-apply")
        try {
            val store = InMemoryPendingScanStore()
            val repository = AsyncRepository().apply {
                accepted = AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                statuses += JobStatus.Completed(
                    JOB_ID, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:02Z"),
                    Instant.parse("2026-01-01T00:00:01Z"), Instant.parse("2026-01-01T00:00:02Z"), "pipeline-v1", draft("IN RAM"),
                )
            }
            val vm = asyncViewModel(repository, store, { connection })
            repository.onReceipt = {
                assertEquals(AsyncScanState.Phase.COMPLETED, vm.state.value.asyncScan.phase)
                assertEquals("IN RAM", vm.state.value.value(PassportField.SURNAME))
            }
            vm.pickImage(ScanImage("receipt-live", uploadFile, "image/jpeg"))
            vm.recognize()
            advanceUntilIdle()

            assertEquals(1, repository.receiptAcks)
            assertTrue(store.load() is PendingScanOperation.Received)
            assertNull(InMemoryPendingScanStore().load())
        } finally {
            uploadFile.delete()
        }
    }

    @Test
    fun postWaitsForProcessMemoryStateAndCanRetryMemoryStoreFailure() = runTest(dispatcher) {
        val uploadFile = temporaryImage("memory-first")
        try {
            val repository = AsyncRepository().apply {
                accepted = AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                statuses += JobStatus.Completed(
                    JOB_ID,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:02Z"),
                    Instant.parse("2026-01-01T00:00:01Z"),
                    Instant.parse("2026-01-01T00:00:02Z"),
                    "pipeline-v1",
                    draft("DURABLE"),
                )
            }
            val store = MemoryPendingScanStore().apply { failNextBegin = true }
            val vm = asyncViewModel(repository, store, { connection })
            vm.pickImage(ScanImage("memory", uploadFile, "image/jpeg"))
            vm.recognize()
            advanceUntilIdle()

            assertTrue(repository.submissions.isEmpty())
            assertEquals(AsyncScanState.Phase.RECOVERY_REQUIRED, vm.state.value.asyncScan.phase)

            vm.retry()
            advanceUntilIdle()
            assertEquals(1, repository.submissions.size)
            assertEquals(AsyncScanState.Phase.COMPLETED, vm.state.value.asyncScan.phase)
        } finally {
            uploadFile.delete()
        }
    }

    @Test
    fun discardAwaitsIDScopedJournalClearAndReturnsFalseWhenClearFails() = runTest(dispatcher) {
        val store = MemoryPendingScanStore().apply {
            current = PendingScanOperation.Accepted("op-discard", connection.connectionId, JOB_ID)
        }
        val vm = asyncViewModel(AsyncRepository(), store, { connection })

        assertTrue(vm.abandonPendingOperationAndAwait())
        assertNull(store.current)
        assertEquals(AsyncScanState.Phase.IDLE, vm.state.value.asyncScan.phase)

        store.current = PendingScanOperation.Accepted("op-protected", connection.connectionId, JOB_ID)
        store.failClear = true
        assertFalse(vm.abandonPendingOperationAndAwait())
        assertEquals("op-protected", store.current?.operationId)
    }

    @Test
    fun accessDeniedCallbackRunsForJob401ButNotPolicy403() = runTest(dispatcher) {
        val uploadFile = temporaryImage("credential-callback")
        try {
            val deniedProfiles = mutableListOf<String>()
            val unauthorizedPost = AsyncRepository().apply {
                submitFailures += AsyncJobRepositoryException("unauthorized", AsyncFailureCategory.UNAUTHORIZED, 401)
            }
            val postVM = asyncViewModel(unauthorizedPost, MemoryPendingScanStore(), { connection }) { deniedProfiles += it }
            postVM.pickImage(ScanImage("post-401", uploadFile, "image/jpeg"))
            postVM.recognize()
            advanceUntilIdle()
            assertEquals(listOf(connection.connectionId), deniedProfiles)
            assertEquals(AsyncScanState.Phase.CONNECTION_REQUIRED, postVM.state.value.asyncScan.phase)

            deniedProfiles.clear()
            val forbiddenPost = AsyncRepository().apply {
                submitFailures += AsyncJobRepositoryException("forbidden", AsyncFailureCategory.ACCESS_DENIED, 403)
            }
            val forbiddenVM = asyncViewModel(forbiddenPost, MemoryPendingScanStore(), { connection }) { deniedProfiles += it }
            forbiddenVM.pickImage(ScanImage("post-403", uploadFile, "image/jpeg"))
            forbiddenVM.recognize()
            advanceUntilIdle()
            assertTrue(deniedProfiles.isEmpty())

            val getUnauthorized = AsyncRepository().apply {
                accepted = AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                statusFailures += AsyncJobRepositoryException("unauthorized", AsyncFailureCategory.UNAUTHORIZED, 401)
            }
            val getVM = asyncViewModel(getUnauthorized, MemoryPendingScanStore(), { connection }) { deniedProfiles += it }
            getVM.pickImage(ScanImage("get-401", uploadFile, "image/jpeg"))
            getVM.recognize()
            advanceUntilIdle()
            assertEquals(listOf(connection.connectionId), deniedProfiles)
        } finally {
            uploadFile.delete()
        }
    }

    @Test
    fun currentProcessAcceptedJobReadsStatusWithoutResubmitting() = runTest(dispatcher) {
        val store = MemoryPendingScanStore().apply {
            current = PendingScanOperation.Accepted("op-restored", connection.connectionId, JOB_ID)
        }
        val repository = AsyncRepository().apply {
            statuses += JobStatus.Failed(
                JOB_ID,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:02Z"),
                null,
                Instant.parse("2026-01-01T00:00:02Z"),
                "image_unprocessable",
                "Безопасное сообщение",
            )
        }
        val vm = asyncViewModel(repository, store, { connection })

        vm.setForeground(true)
        advanceUntilIdle()

        assertTrue(repository.submissions.isEmpty())
        assertEquals(listOf(JOB_ID), repository.statusReads)
        assertEquals(AsyncScanState.Phase.FAILED, vm.state.value.asyncScan.phase)
        assertEquals("image_unprocessable", vm.state.value.asyncScan.errorCode)
        assertTrue(vm.state.value.asyncScan.message?.contains("Безопасное") == true)
    }

    @Test
    fun currentProcessReceivedResultIsEditableOfflineAndReceiptRetriesWhenConnectionBecomesReady() = runTest(dispatcher) {
        val store = MemoryPendingScanStore().apply {
            current = PendingScanOperation.Received(
                "op-local-result", connection.connectionId, JOB_ID,
                TerminalJobSnapshot.Completed("old-pipeline", draft("LOCAL RESULT")), false,
            )
        }
        val repository = AsyncRepository()
        val vm = asyncViewModel(repository, store, connectionProvider = { null }, savedConnectionId = connection.connectionId)

        vm.setForeground(true)
        advanceUntilIdle()

        assertEquals(AsyncScanState.Phase.COMPLETED, vm.state.value.asyncScan.phase)
        assertEquals("${vm.state.value.asyncScan.phase} / ${vm.state.value.error}", "LOCAL RESULT", vm.state.value.value(PassportField.SURNAME))
        assertTrue(repository.submissions.isEmpty())
        assertTrue(repository.statusReads.isEmpty())
        assertEquals(0, repository.receiptAcks)

        vm.editField(PassportField.SURNAME, "EDITED OFFLINE")
        var ready: ApiConnection? = null
        val laterReadyVm = asyncViewModel(repository, store, { ready }, savedConnectionId = connection.connectionId)
        laterReadyVm.setForeground(true)
        advanceUntilIdle()
        assertEquals("LOCAL RESULT", laterReadyVm.state.value.value(PassportField.SURNAME))
        ready = connection
        laterReadyVm.onConnectionReady()
        advanceUntilIdle()

        assertEquals(1, repository.receiptAcks)
        assertEquals("LOCAL RESULT", laterReadyVm.state.value.value(PassportField.SURNAME))
        assertTrue((store.current as PendingScanOperation.Received).receiptAcknowledged)
        assertTrue(repository.statusReads.isEmpty())
    }

    @Test
    fun acceptedJobResumesAfterDelayedConnectionReadySignal() = runTest(dispatcher) {
        val store = MemoryPendingScanStore().apply {
            current = PendingScanOperation.Accepted("op-delayed-ready", connection.connectionId, JOB_ID)
        }
        val repository = AsyncRepository().apply {
            statuses += JobStatus.Completed(
                JOB_ID, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:02Z"),
                Instant.parse("2026-01-01T00:00:01Z"), Instant.parse("2026-01-01T00:00:02Z"), "old-pipeline", draft("READY"),
            )
        }
        var ready: ApiConnection? = null
        val vm = asyncViewModel(repository, store, { ready }, savedConnectionId = connection.connectionId)

        vm.setForeground(true)
        advanceUntilIdle()
        assertTrue(repository.statusReads.isEmpty())
        ready = connection
        vm.onConnectionReady()
        advanceUntilIdle()

        assertEquals(listOf(JOB_ID), repository.statusReads)
        assertEquals(AsyncScanState.Phase.COMPLETED, vm.state.value.asyncScan.phase)
        assertEquals(1, repository.receiptAcks)
        assertTrue(store.current is PendingScanOperation.Received)
    }

    @Test
    fun purgedAcceptedResultDoesNotAutoResubmitAndAllowsExplicitNewScan() = runTest(dispatcher) {
        val uploadFile = temporaryImage("purged-result")
        try {
            val store = MemoryPendingScanStore()
            val repository = AsyncRepository().apply {
                accepted = AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                statusFailures += AsyncJobRepositoryException("purged", AsyncFailureCategory.JOB_RESULT_GONE, 410)
            }
            val vm = asyncViewModel(repository, store, { connection })
            vm.pickImage(ScanImage("purged", uploadFile, "image/jpeg"))
            vm.recognize()
            advanceUntilIdle()

            assertEquals(AsyncScanState.Phase.FAILED, vm.state.value.asyncScan.phase)
            assertEquals("job_result_gone", vm.state.value.asyncScan.errorCode)
            assertEquals(1, repository.submissions.size)
            assertEquals(0, repository.receiptAcks)
            assertNull(vm.state.value.image)

            val replacement = temporaryImage("new-explicit-scan")
            vm.pickImage(ScanImage("new-explicit", replacement, "image/jpeg"))
            assertEquals("new-explicit", vm.state.value.image?.id)
            replacement.delete()
        } finally {
            uploadFile.delete()
        }
    }

    @Test
    fun receiptConflictRequiresTerminalReadBeforeRetryingAcknowledgement() = runTest(dispatcher) {
        val uploadFile = temporaryImage("receipt-409")
        try {
            val repository = AsyncRepository().apply {
                accepted = AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                val terminal = JobStatus.Completed(
                    JOB_ID, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:02Z"),
                    Instant.parse("2026-01-01T00:00:01Z"), Instant.parse("2026-01-01T00:00:02Z"), "pipeline", draft("DONE"),
                )
                statuses += terminal
                statuses += JobStatus.Queued(JOB_ID, terminal.createdAt, terminal.createdAt, null, null)
                statuses += JobStatus.Processing(JOB_ID, terminal.createdAt, terminal.updatedAt, terminal.startedAt, null)
                statuses += terminal
                receiptFailures += AsyncJobRepositoryException("active", AsyncFailureCategory.JOB_NOT_TERMINAL, 409)
            }
            val store = MemoryPendingScanStore()
            val vm = asyncViewModel(repository, store, { connection })
            vm.pickImage(ScanImage("receipt-409", uploadFile, "image/jpeg"))
            vm.recognize()
            advanceUntilIdle()

            assertEquals(4, repository.statusReads.size)
            assertEquals(2, repository.receiptAttempts)
            assertTrue((store.current as PendingScanOperation.Received).receiptAcknowledged)
        } finally {
            uploadFile.delete()
        }
    }

    @Test
    fun foregroundPauseStopsPollingAndResumeContinuesSameAcceptedJob() = runTest(dispatcher) {
        val uploadFile = temporaryImage("pause-resume")
        try {
            val repository = AsyncRepository().apply {
                accepted = AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                deferredStatus = CompletableDeferred()
            }
            val store = MemoryPendingScanStore()
            val vm = asyncViewModel(repository, store, { connection })
            vm.pickImage(ScanImage("pause-resume", uploadFile, "image/jpeg"))
            vm.recognize()
            runCurrent()
            assertEquals(listOf(JOB_ID), repository.statusReads)

            vm.setForeground(false)
            runCurrent()
            assertFalse(vm.state.value.isRecognizing)
            repository.deferredStatus = null
            repository.statuses += JobStatus.Completed(
                JOB_ID,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:02Z"),
                Instant.parse("2026-01-01T00:00:01Z"),
                Instant.parse("2026-01-01T00:00:02Z"),
                "pipeline-v-old",
                draft("RESUMED"),
            )
            vm.setForeground(true)
            advanceUntilIdle()

            assertEquals(1, repository.submissions.size)
            assertEquals(listOf(JOB_ID, JOB_ID), repository.statusReads)
            assertEquals(AsyncScanState.Phase.COMPLETED, vm.state.value.asyncScan.phase)
            assertEquals("RESUMED", vm.state.value.value(PassportField.SURNAME))
        } finally {
            uploadFile.delete()
        }
    }

    @Test
    fun lateAsyncResultDoesNotReplaceEditMadeWhilePolling() = runTest(dispatcher) {
        val uploadFile = temporaryImage("edit-race")
        try {
            val completion = CompletableDeferred<JobStatus>()
            val repository = AsyncRepository().apply {
                accepted = AcceptedJob(JOB_ID, JobState.PROCESSING, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                deferredStatus = completion
            }
            val vm = asyncViewModel(repository, MemoryPendingScanStore(), { connection })
            vm.pickImage(ScanImage("async-race", uploadFile, "image/jpeg"))
            vm.recognize()
            runCurrent()
            vm.editField(PassportField.SURNAME, "USER EDIT")
            completion.complete(
                JobStatus.Completed(
                    JOB_ID,
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T00:00:02Z"),
                    Instant.parse("2026-01-01T00:00:01Z"),
                    Instant.parse("2026-01-01T00:00:02Z"),
                    "pipeline-v-old",
                    draft("OCR RESULT"),
                ),
            )
            advanceUntilIdle()

            assertEquals("USER EDIT", vm.state.value.value(PassportField.SURNAME))
            assertNull(vm.state.value.draft)
            assertEquals(AsyncScanState.Phase.COMPLETED, vm.state.value.asyncScan.phase)
        } finally {
            uploadFile.delete()
        }
    }

    @Test
    fun switchingConnectionFencesOldJobAndKeepsLocalOperationBlocked() = runTest(dispatcher) {
        val uploadFile = temporaryImage("profile-switch")
        try {
            var currentConnection: ApiConnection? = connection
            val repository = AsyncRepository().apply {
                accepted = AcceptedJob(JOB_ID, JobState.QUEUED, "/v1/passport/jobs/$JOB_ID", Instant.parse("2026-01-01T00:00:00Z"))
                deferredStatus = CompletableDeferred()
            }
            val vm = asyncViewModel(repository, MemoryPendingScanStore(), { currentConnection })
            vm.pickImage(ScanImage("profile-switch", uploadFile, "image/jpeg"))
            vm.recognize()
            runCurrent()
            vm.onApiConnectionChanged()
            assertEquals(AsyncScanState.Phase.CONNECTION_REQUIRED, vm.state.value.asyncScan.phase)
            assertTrue(vm.state.value.asyncScan.blocksNewScan)
            currentConnection = alternateConnection
            advanceUntilIdle()
            assertEquals(listOf(JOB_ID), repository.statusReads)
            assertNull(vm.state.value.draft)
        } finally {
            uploadFile.delete()
        }
    }

    private fun image(id: String) = ScanImage(id, File("$id.jpg"), "image/jpeg")

    private fun draft(surname: String): PassportDraft = PassportDraft(
        PassportField.entries.associateWith { field ->
            PassportValue(
                value = when (field) {
                    PassportField.SURNAME -> surname
                    PassportField.GIVEN_NAME -> "IVAN"
                    PassportField.PATRONYMIC -> ""
                    PassportField.GENDER -> "M"
                    PassportField.BIRTH_DATE -> "1990-04-12"
                    PassportField.BIRTH_PLACE -> "MOSCOW"
                    PassportField.SERIES -> "0012"
                    PassportField.NUMBER -> "000345"
                    PassportField.ISSUE_DATE -> "2010-06-18"
                    PassportField.ISSUER -> "DEPARTMENT"
                    PassportField.DEPARTMENT_CODE -> "123-456"
                },
                status = RecognitionStatus.RECOGNIZED,
            )
        },
    )

    private class DeferredRepository : PassportRepository {
        val results = mutableListOf<CompletableDeferred<PassportDraft>>()
        override suspend fun recognize(image: ScanImage, requestId: String): PassportDraft {
            val result = CompletableDeferred<PassportDraft>().also(results::add)
            return withContext(NonCancellable) { result.await() }
        }
    }

    private class ImmediateRepository(private val result: PassportDraft) : PassportRepository {
        override suspend fun recognize(image: ScanImage, requestId: String): PassportDraft = result
    }

    private fun temporaryImage(contents: String): File = File.createTempFile("passport-async", ".jpg").also {
        it.writeText(contents)
    }

    private fun archiveBytes(): ByteArray = "synthetic archive".toByteArray()

    private fun asyncViewModel(
        repository: PassportRepository,
        store: PendingScanStore,
        connectionProvider: suspend () -> ApiConnection?,
        savedConnectionId: String? = null,
        onAccessDenied: suspend (String) -> Unit = {},
    ) = ScanViewModel(
        repository = repository,
        createArchive = { archiveBytes() },
        pendingScanStore = store,
        apiConnectionProvider = connectionProvider,
        pollPause = {},
        onAccessDenied = onAccessDenied,
        savedConnectionIdProvider = { savedConnectionId ?: connectionProvider()?.connectionId },
    )

    private class MemoryPendingScanStore : PendingScanStore {
        var current: PendingScanOperation? = null
        var failNextBegin = false
        var failClear = false
        override suspend fun load(): PendingScanOperation? = current
        override suspend fun beginSubmitting(operation: PendingScanOperation.Submitting) {
            if (failNextBegin) {
                failNextBegin = false
                throw PendingScanStoreException()
            }
            if (current != null) throw PendingScanStoreException()
            current = operation
        }
        override suspend fun markAccepted(operationId: String, jobId: String): Boolean {
            val submitting = current as? PendingScanOperation.Submitting ?: return false
            if (submitting.operationId != operationId) return false
            current = PendingScanOperation.Accepted(operationId, submitting.connectionId, jobId)
            return true
        }
        override suspend fun markReceived(operationId: String, jobId: String, terminal: TerminalJobSnapshot): Boolean {
            val accepted = current as? PendingScanOperation.Accepted ?: return false
            if (accepted.operationId != operationId || accepted.jobId != jobId) return false
            current = PendingScanOperation.Received(operationId, accepted.connectionId, jobId, terminal, false)
            return true
        }
        override suspend fun markReceiptAcknowledged(operationId: String, jobId: String): Boolean {
            val received = current as? PendingScanOperation.Received ?: return false
            if (received.operationId != operationId || received.jobId != jobId) return false
            current = received.copy(receiptAcknowledged = true)
            return true
        }
        override suspend fun clear(operationId: String): Boolean {
            if (failClear) return false
            if (current?.operationId != operationId) return false
            current = null
            return true
        }
    }

    private class AsyncRepository : PassportRepository {
        val submissions = mutableListOf<Pair<String, String>>()
        val statusReads = mutableListOf<String>()
        val submitFailures = ArrayDeque<Exception>()
        val statusFailures = ArrayDeque<Exception>()
        val statuses = ArrayDeque<JobStatus>()
        var accepted: AcceptedJob? = null
        var deferredStatus: CompletableDeferred<JobStatus>? = null
        var receiptAcks = 0
        var receiptAttempts = 0
        var onReceipt: (() -> Unit)? = null
        val receiptFailures = ArrayDeque<Exception>()

        override suspend fun recognize(image: ScanImage, requestId: String): PassportDraft = error("sync fallback used")

        override suspend fun submit(
            payload: AsyncUploadPayload,
            requestId: String,
            idempotencyKey: String,
            connection: ApiConnection,
        ): AcceptedJob {
            submissions += payload.copyBytes().decodeToString() to idempotencyKey
            if (submitFailures.isNotEmpty()) throw submitFailures.removeFirst()
            return accepted ?: error("accepted response not configured")
        }

        override suspend fun readStatus(jobId: String, requestId: String, connection: ApiConnection): JobStatus {
            statusReads += jobId
            if (statusFailures.isNotEmpty()) throw statusFailures.removeFirst()
            deferredStatus?.let { return it.await() }
            return statuses.removeFirst()
        }

        override suspend fun acknowledgeReceipt(jobId: String, requestId: String, connection: ApiConnection) {
            receiptAttempts++
            onReceipt?.invoke()
            if (receiptFailures.isNotEmpty()) throw receiptFailures.removeFirst()
            receiptAcks++
        }
    }

    companion object {
        private const val JOB_ID = "f7b8edc1-9934-4f10-a33e-efec856c41ae"
        private val connection = ApiConnection(
            "https://passport.example.test",
            "pbt1_${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })}",
            "profile-a",
        )
        private val alternateConnection = ApiConnection(
            "https://passport.example.test",
            "pbt1_${Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 8 })}",
            "profile-b",
        )
    }
}
