package ru.passport.scanner.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassportValidatorTest {
    @Test
    fun acceptsCanonicalValuesAndEmptyPatronymic() {
        assertTrue(PassportValidator.validate(validValues()).isEmpty())
    }

    @Test
    fun rejectsDisplayLabelsAsInputAndRequestsRussianChoice() {
        val values = validValues() + (PassportField.GENDER to "МУЖ.")

        val error = PassportValidator.validate(values).single()

        assertEquals(PassportField.GENDER, error.field)
        assertEquals("Выберите «МУЖ.» или «ЖЕН.»", error.message)
    }

    @Test
    fun rejectsImpossibleCalendarDateAndMalformedIdentifiersWithoutRepairingThem() {
        val values = validValues().toMutableMap().apply {
            put(PassportField.BIRTH_DATE, "2023-02-29")
            put(PassportField.SERIES, "12")
            put(PassportField.NUMBER, "00034")
            put(PassportField.DEPARTMENT_CODE, "123456")
        }

        val errors = PassportValidator.validate(values)

        assertEquals(
            setOf(PassportField.BIRTH_DATE, PassportField.SERIES, PassportField.NUMBER, PassportField.DEPARTMENT_CODE),
            errors.map { it.field }.toSet(),
        )
        assertEquals("12", values[PassportField.SERIES])
    }

    private fun validValues(): Map<PassportField, String> = mapOf(
        PassportField.SURNAME to "ИВАНОВ",
        PassportField.GIVEN_NAME to "ИВАН",
        PassportField.PATRONYMIC to "",
        PassportField.GENDER to "M",
        PassportField.BIRTH_DATE to "1990-04-12",
        PassportField.BIRTH_PLACE to "МОСКВА",
        PassportField.SERIES to "0012",
        PassportField.NUMBER to "000345",
        PassportField.ISSUE_DATE to "2010-06-18",
        PassportField.ISSUER to "ОТДЕЛ ПРИМЕРА",
        PassportField.DEPARTMENT_CODE to "123-456",
    )
}
