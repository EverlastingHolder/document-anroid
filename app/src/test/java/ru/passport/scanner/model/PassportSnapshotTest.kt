package ru.passport.scanner.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PassportSnapshotTest {
    @Test
    fun defensiveCopyIsIndependentFromLaterEditsToSourceValues() {
        val sourceValues = validValues().toMutableMap()
        val source = PassportSnapshot(sourceValues, revision = 4)

        val snapshot = source.defensiveCopy()
        sourceValues[PassportField.SURNAME] = "ИЗМЕНЕНО"

        assertEquals("ИВАНОВ", snapshot.values.getValue(PassportField.SURNAME))
        assertEquals(4, snapshot.revision)
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
