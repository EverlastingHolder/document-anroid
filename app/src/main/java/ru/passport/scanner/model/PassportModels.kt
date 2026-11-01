package ru.passport.scanner.model


enum class PassportField(val apiKey: String, val label: String) {
    SURNAME("surname", "Фамилия"),
    GIVEN_NAME("given_name", "Имя"),
    PATRONYMIC("patronymic", "Отчество"),
    GENDER("gender", "Пол"),
    BIRTH_DATE("birth_date", "Дата рождения"),
    BIRTH_PLACE("birth_place", "Место рождения"),
    SERIES("series", "Серия"),
    NUMBER("number", "Номер"),
    ISSUE_DATE("issue_date", "Дата выдачи"),
    ISSUER("issuer", "Кем выдан"),
    DEPARTMENT_CODE("department_code", "Код подразделения");

    companion object {
        fun fromApiKey(key: String): PassportField? = entries.firstOrNull { it.apiKey == key }
    }
}

enum class RecognitionStatus { RECOGNIZED, UNCERTAIN, MISSING }

data class PassportIssue(val code: String, val message: String)

data class PassportValue(
    val value: String,
    val status: RecognitionStatus,
    val issues: List<PassportIssue> = emptyList(),
)

data class PassportDraft(
    val values: Map<PassportField, PassportValue>,
    val warnings: List<PassportIssue> = emptyList(),
) {
    init {
        require(values.keys == PassportField.entries.toSet()) { "Draft must contain all passport fields" }
    }
}

data class PassportSnapshot(
    val values: Map<PassportField, String>,
    val revision: Long,
) {
    init {
        require(values.keys == PassportField.entries.toSet()) { "Passport snapshot must contain all fields" }
    }

    fun defensiveCopy(): PassportSnapshot = copy(values = values.toMap())
}

/** Document bytes and preview data live only in process memory. */
class ScanImage(id: String, bytes: ByteArray, val mimeType: String, val preview: android.graphics.Bitmap? = null) {
    /** Compatibility helper for JVM fixtures; app production flows use byte arrays. */
    constructor(id: String, file: java.io.File, mimeType: String) : this(
        id,
        if (file.isFile) file.readBytes() else byteArrayOf(0),
        mimeType,
    )
    val id: String = id
    private val contents = bytes.copyOf()
    val bytes: ByteArray get() = contents.copyOf()
    val sizeBytes: Int get() = contents.size
    fun eraseInMemory() {
        contents.fill(0)
    }
    override fun toString(): String = "ScanImage(id=[redacted], bytes=[redacted])"
}

data class FieldValidationError(val field: PassportField, val message: String)

/** Canonical client-side validation. It never repairs or guesses entered values. */
object PassportValidator {
    fun validate(values: Map<PassportField, String>): List<FieldValidationError> = buildList {
        PassportField.entries.forEach { field ->
            val value = values[field].orEmpty()
            if (field == PassportField.PATRONYMIC && value.isBlank()) return@forEach
            val error = when {
                value.isBlank() -> "Заполните поле «${field.label.lowercase()}»."
                field == PassportField.GENDER && value !in setOf("M", "F") -> "Выберите «МУЖ.» или «ЖЕН.»"
                field == PassportField.BIRTH_DATE || field == PassportField.ISSUE_DATE -> {
                    val parsed = runCatching { java.time.LocalDate.parse(value) }.getOrNull()
                    if (value.length != 10 || parsed == null || parsed.toString() != value) "Введите дату в формате YYYY-MM-DD." else null
                }
                field == PassportField.SERIES && !value.matches(Regex("[0-9]{4}")) -> "Серия должна содержать 4 цифры."
                field == PassportField.NUMBER && !value.matches(Regex("[0-9]{6}")) -> "Номер должен содержать 6 цифр."
                field == PassportField.DEPARTMENT_CODE && !value.matches(Regex("[0-9]{3}-[0-9]{3}")) -> "Введите код в формате 000-000."
                else -> null
            }
            if (error != null) add(FieldValidationError(field, error))
        }
    }
}
