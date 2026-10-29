package ru.passport.scanner.data

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage
import java.io.File

class DemoPassportRepositoryTest {
    @Test
    fun returnsAllCanonicalSyntheticFieldsWithoutReadingImageOrContactingServer() = runTest {
        val repository = DemoPassportRepository()
        val image = ScanImage("demo", File("missing-demo-image.jpg"), "image/jpeg")

        val draft = async { repository.recognize(image, "not-a-network-request-id") }
        assertFalse(draft.isCompleted)
        advanceUntilIdle()
        val result = draft.await()

        assertEquals(PassportField.entries.toSet(), result.values.keys)
        assertEquals(
            mapOf(
                PassportField.SURNAME to "ТЕСТОВЫЙ",
                PassportField.GIVEN_NAME to "ПРИМЕР",
                PassportField.PATRONYMIC to "ТЕСТОВИЧ",
                PassportField.GENDER to "M",
                PassportField.BIRTH_DATE to "2000-01-01",
                PassportField.BIRTH_PLACE to "ТЕСТОВЫЙ ГОРОД",
                PassportField.SERIES to "0000",
                PassportField.NUMBER to "000000",
                PassportField.ISSUE_DATE to "2020-01-01",
                PassportField.ISSUER to "ТЕСТОВОЕ ПОДРАЗДЕЛЕНИЕ",
                PassportField.DEPARTMENT_CODE to "000-000",
            ),
            result.values.mapValues { (_, value) -> value.value },
        )
        assertTrue(result.values.values.all { it.status == RecognitionStatus.RECOGNIZED && it.issues.isEmpty() })
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun cancelsPromptlyWhileWaitingForSyntheticRecognition() = runTest {
        val repository = DemoPassportRepository()
        val job = async {
            repository.recognize(
                ScanImage("demo", File("missing-demo-image.jpg"), "image/jpeg"),
                "not-a-network-request-id",
            )
        }
        runCurrent()
        assertFalse(job.isCompleted)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }
}
