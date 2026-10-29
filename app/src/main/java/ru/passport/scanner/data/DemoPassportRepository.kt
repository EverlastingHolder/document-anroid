package ru.passport.scanner.data

import kotlinx.coroutines.delay
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValue
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage

/** Offline OCR stand-in for product demonstrations. It never opens the image or contacts a server. */
class DemoPassportRepository : PassportRepository {
    @Suppress("UNUSED_PARAMETER")
    override suspend fun recognize(image: ScanImage, requestId: String): PassportDraft {
        delay(RECOGNITION_DELAY_MILLIS)
        return PassportDraft(
            values = DEMO_VALUES.mapValues { (_, value) ->
                PassportValue(value = value, status = RecognitionStatus.RECOGNIZED)
            },
        )
    }

    private companion object {
        const val RECOGNITION_DELAY_MILLIS = 700L

        val DEMO_VALUES = mapOf(
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
        )
    }
}
