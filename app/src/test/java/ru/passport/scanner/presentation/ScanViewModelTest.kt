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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.passport.scanner.data.PassportRepository
import ru.passport.scanner.data.PassportRepositoryException
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValue
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage
import java.io.File

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
        val vm = ScanViewModel(repository) { File("archive.7z") }
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
        val vm = ScanViewModel(repository) { File("archive.7z") }
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
            val vm = ScanViewModel(repository) { File("archive.7z") }
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
            val vm = ScanViewModel(repository) { File("archive.7z") }
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
        val vm = ScanViewModel(ImmediateRepository(draft("IVANOV"))) { snapshot ->
            exported = snapshot
            File("archive.7z")
        }
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
        val archiveGate = CompletableDeferred<File>()
        var archiveInput: PassportSnapshot? = null
        var archiveCalls = 0
        val vm = ScanViewModel(ImmediateRepository(draft("IVANOV"))) {
            archiveInput = it
            if (archiveCalls++ == 0) archiveGate.await() else File("archive.7z")
        }
        vm.pickImage(image("current"))
        vm.recognize()
        advanceUntilIdle()
        vm.prepareShare()
        runCurrent()
        assertEquals(vm.state.value.currentValues(), archiveInput?.values)

        vm.editField(PassportField.SURNAME, "CORRECTED")
        archiveGate.complete(File("stale.7z"))
        advanceUntilIdle()
        assertNull(vm.state.value.shareRequest)

        vm.prepareShare()
        advanceUntilIdle()
        val share = vm.state.value.shareRequest
        assertNotNull(share)
        assertEquals(File("archive.7z"), vm.consumeShareRequest(share!!.id))
        assertNull(vm.state.value.shareRequest)
        assertNull(vm.consumeShareRequest(share.id))
    }

    @Test
    fun invalidValuesPreventArchiveCreationAndExposeFieldErrors() = runTest(dispatcher) {
        var archiveCalls = 0
        val vm = ScanViewModel(ImmediateRepository(draft("IVANOV"))) {
            archiveCalls++
            File("archive.7z")
        }
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
}
