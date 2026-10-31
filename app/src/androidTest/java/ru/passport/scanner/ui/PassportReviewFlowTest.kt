package ru.passport.scanner.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.passport.scanner.data.PassportRepository
import ru.passport.scanner.data.PassportRepositoryException
import ru.passport.scanner.data.DemoPassportRepository
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValue
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage
import ru.passport.scanner.presentation.ScanViewModel
import ru.passport.scanner.export.PassportExporter

@RunWith(AndroidJUnit4::class)
class PassportReviewFlowTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun editedValuesExportImmediatelyAndUiHasNoPerFieldReviewOrConfirmControls() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.cacheDir, "ui-test.7z")
        val openedArchive = AtomicReference<File?>()
        val vm = ScanViewModel(
            repository = object : PassportRepository {
                override suspend fun recognize(image: ScanImage, requestId: String) = sampleDraft()
            },
            createArchive = { snapshot: PassportSnapshot ->
                assertEquals("ПЕТРОВ", snapshot.values.getValue(PassportField.SURNAME))
                assertEquals("F", snapshot.values.getValue(PassportField.GENDER))
                archive.writeBytes(byteArrayOf(1))
                archive
            },
        )
        vm.pickImage(ScanImage("ui-test", File(context.cacheDir, "unused.jpg"), "image/jpeg"))

        compose.setContent {
            MaterialTheme {
                PassportScannerScreen(vm) { openedArchive.set(it) }
            }
        }
        compose.onNodeWithText("Распознать").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("field_surname").fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag("field_surname").performTextReplacement("ПЕТРОВ")
        compose.onNodeWithText("МУЖ.").assertIsDisplayed()
        compose.onNodeWithText("ЖЕН.").assertIsDisplayed()
        compose.onNodeWithTag("gender_F").assertIsDisplayed().performClick()
        assertEquals("F", vm.state.value.value(PassportField.GENDER))
        compose.onAllNodesWithTag("review_surname").assertCountEquals(0)
        compose.onAllNodesWithTag("send_passport").assertCountEquals(1)
        compose.onNodeWithText("Подтвердить данные").assertDoesNotExist()
        compose.onNodeWithText("Нужно проверить").assertDoesNotExist()
        compose.onNodeWithText("Создать архив и открыть почту").assertExists().performClick()

        compose.waitUntil(timeoutMillis = 5_000) { openedArchive.get() != null }
        assertEquals(archive, openedArchive.get())
    }

    @Test
    fun invalidFieldBlocksExportAndShowsValidationMessage() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        var archiveCalls = 0
        val vm = ScanViewModel(
            repository = object : PassportRepository {
                override suspend fun recognize(image: ScanImage, requestId: String) = sampleDraft()
            },
            createArchive = { archiveCalls++; File(context.cacheDir, "invalid.7z") },
        )
        vm.pickImage(ScanImage("invalid-ui", File(context.cacheDir, "unused.jpg"), "image/jpeg"))
        compose.setContent { MaterialTheme { PassportScannerScreen(vm) { } } }
        compose.onNodeWithText("Распознать").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("field_surname").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("review_list").performScrollToNode(hasTestTag("field_series"))
        compose.onNodeWithTag("field_series").performTextReplacement("12")
        compose.onNodeWithText("Создать архив и открыть почту").performClick()
        compose.onNodeWithText("Серия должна содержать 4 цифры.").assertIsDisplayed()
        assertEquals(0, archiveCalls)
    }

    @Test
    fun retryReusesTheSelectedImageAfterRecognitionFailure() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val image = ScanImage("retry-image", File(context.cacheDir, "retry-unused.jpg"), "image/jpeg")
        val recognizedImageIds = mutableListOf<String>()
        val vm = ScanViewModel(
            repository = object : PassportRepository {
                override suspend fun recognize(image: ScanImage, requestId: String): PassportDraft {
                    recognizedImageIds += image.id
                    if (recognizedImageIds.size == 1) throw PassportRepositoryException("Сервер временно недоступен.")
                    return sampleDraft()
                }
            },
            createArchive = { error("Export is not part of this test") },
        )
        vm.pickImage(image)

        compose.setContent {
            MaterialTheme {
                PassportScannerScreen(vm) { error("Email is not part of this test") }
            }
        }
        compose.onNodeWithText("Распознать").performClick()
        compose.onNodeWithText("Повторить").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("field_surname").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals(listOf("retry-image", "retry-image"), recognizedImageIds)
        assertEquals("retry-image", vm.state.value.image?.id)
    }

    @Test
    fun demoModeShowsLocalDataNoticeAndUsesRealExportFlow() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val openedArchive = AtomicReference<File?>()
        val vm = ScanViewModel(
            repository = DemoPassportRepository(),
            createArchive = PassportExporter(context.cacheDir)::createArchive,
        )
        vm.pickImage(ScanImage("demo-ui-test", File(context.cacheDir, "demo-image-not-read.jpg"), "image/jpeg"))

        compose.setContent {
            MaterialTheme {
                PassportScannerScreen(vm, onOpenEmail = openedArchive::set, isDemo = true)
            }
        }
        compose.onNodeWithTag("demo_mode_banner").assertIsDisplayed()
        compose.onNodeWithText("Фото не отправляется на сервер. Подставляются данные из примера.").assertIsDisplayed()
        compose.onNodeWithText("Получить тестовые данные").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag("field_surname").fetchSemanticsNodes().isNotEmpty()
        }

        assertEquals("ТЕСТОВЫЙ", vm.state.value.value(PassportField.SURNAME))
        assertEquals("0000", vm.state.value.value(PassportField.SERIES))
        assertEquals("000000", vm.state.value.value(PassportField.NUMBER))
        compose.onNodeWithTag("demo_mode_banner").assertIsDisplayed()
        compose.onNodeWithText("Фото не отправляется на сервер. Подставляются данные из примера.").assertIsDisplayed()
        compose.onNodeWithText("Создать архив и открыть почту").performClick()

        compose.waitUntil(timeoutMillis = 10_000) { openedArchive.get() != null }
        val archive = requireNotNull(openedArchive.get())
        try {
            assertTrue(archive.isFile)
            assertTrue(archive.length() > 0L)
        } finally {
            archive.parentFile?.deleteRecursively()
        }
    }

    private fun sampleDraft(): PassportDraft = PassportDraft(
        values = PassportField.entries.associateWith { field ->
            val text = when (field) {
                PassportField.SURNAME -> "ИВАНОВ"
                PassportField.GIVEN_NAME -> "АЛЕКСЕЙ"
                PassportField.PATRONYMIC -> "ПЕТРОВИЧ"
                PassportField.GENDER -> "M"
                PassportField.BIRTH_DATE -> "1990-04-12"
                PassportField.BIRTH_PLACE -> "ГОРОД ПРИМЕР"
                PassportField.SERIES -> "0012"
                PassportField.NUMBER -> "000345"
                PassportField.ISSUE_DATE -> "2010-06-18"
                PassportField.ISSUER -> "ОТДЕЛ ПРИМЕРА"
                PassportField.DEPARTMENT_CODE -> "123-456"
            }
            PassportValue(text, RecognitionStatus.RECOGNIZED)
        },
    )
}
