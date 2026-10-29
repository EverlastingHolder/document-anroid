package ru.passport.scanner.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.streams.asInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportIssue
import ru.passport.scanner.model.PassportValue
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage
import java.net.URI
import java.time.LocalDate

interface PassportRepository {
    suspend fun recognize(image: ScanImage, requestId: String): PassportDraft
    fun close() = Unit
}

class PassportRepositoryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** HTTP adapter for the backend's POST /v1/passport/recognize OpenAPI contract. */
class KtorPassportRepository(
    private val baseUrl: String,
    private val client: HttpClient = defaultClient(),
    private val allowLocalHttp: Boolean = false,
) : PassportRepository {
    override fun close() = client.close()

    override suspend fun recognize(image: ScanImage, requestId: String): PassportDraft {
        val endpoint = validatedEndpoint(baseUrl)
        if (!UUID_PATTERN.matches(requestId)) {
            throw PassportRepositoryException("Не удалось подготовить идентификатор запроса. Повторите попытку.")
        }
        if (!image.file.isFile || image.file.length() == 0L) {
            throw PassportRepositoryException("Не удалось прочитать изображение. Выберите файл ещё раз.")
        }
        if (image.mimeType !in SUPPORTED_MEDIA_TYPES) {
            throw PassportRepositoryException("Поддерживаются изображения JPEG, PNG и WebP.")
        }

        val response = try {
            withContext(Dispatchers.IO) {
                client.post(endpoint) {
                    header("X-Request-ID", requestId)
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                appendInput(
                                    "file",
                                    headers = headersOf(
                                        HttpHeaders.ContentDisposition,
                                        "form-data; name=\"file\"; filename=\"scan\"",
                                        HttpHeaders.ContentType,
                                        image.mimeType,
                                    ),
                                    size = image.file.length(),
                                ) { image.file.inputStream().asInput() }
                            },
                        ),
                    )
                }
            }
        } catch (e: PassportRepositoryException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Exception details can contain request data or transport internals; never expose/log them.
            throw PassportRepositoryException("Не удалось связаться с сервером. Проверьте интернет и повторите запрос.")
        }

        if (response.status != HttpStatusCode.OK) {
            val error = try {
                Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            throw PassportRepositoryException(error?.code?.let(::safeErrorMessage) ?: "Сервер не смог обработать изображение. Повторите запрос.")
        }
        val dto = try {
            response.body<RecognitionResponse>()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw PassportRepositoryException("Сервер вернул ответ, который приложение не смогло прочитать.")
        }
        if (dto.status != "completed" || !dto.reviewRequired) {
            throw PassportRepositoryException("Сервер вернул неподдерживаемый результат распознавания.")
        }
        if (dto.requestId != requestId || dto.pipelineVersion.isBlank()) {
            throw PassportRepositoryException("Сервер вернул неподдерживаемый результат распознавания.")
        }
        val values = PassportField.entries.associateWith { field ->
            val result = dto.fields.forField(field)
            validateFieldResult(field, result)
            PassportValue(
                value = result.value.orEmpty(),
                status = RecognitionStatus.valueOf(result.state.uppercase()),
                issues = result.issues.map { PassportIssue(it.code, it.message) },
            )
        }
        if (dto.warnings.any { it.code != "no_fields_recognized" || it.message.isBlank() } ||
            dto.fields.allValues().all { it.value == null } != dto.warnings.any { it.code == "no_fields_recognized" }
        ) {
            throw PassportRepositoryException("Сервер вернул неподдерживаемое предупреждение распознавания.")
        }
        if (dto.fields.allValues().any { result -> result.issues.any { it.code !in FIELD_ISSUE_CODES || it.message.isBlank() } }) {
            throw PassportRepositoryException("Сервер вернул неподдерживаемое описание результата.")
        }
        return PassportDraft(values = values, warnings = dto.warnings.map { PassportIssue(it.code, it.message) })
    }

    private fun validatedEndpoint(config: String): String {
        if (config.isBlank()) throw PassportRepositoryException("Адрес сервера распознавания не настроен.")
        val uri = runCatching { URI(config.trim()) }.getOrNull()
        if (uri == null) {
            throw PassportRepositoryException("Для распознавания требуется настроить HTTPS-адрес сервера.")
        }
        val isHttps = uri.scheme.equals("https", ignoreCase = true)
        val isAllowedLocalHttp = allowLocalHttp && uri.scheme.equals("http", ignoreCase = true) &&
            uri.host?.lowercase() in LOCAL_HTTP_HOSTS
        if ((!isHttps && !isAllowedLocalHttp) || uri.host.isNullOrBlank() ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null
        ) {
            throw PassportRepositoryException("Для распознавания требуется настроить HTTPS-адрес сервера.")
        }
        return config.trim().trimEnd('/') + "/v1/passport/recognize"
    }

    private fun safeErrorMessage(code: String): String = when (code) {
        "invalid_request", "unsupported_media_type" -> "Не удалось отправить изображение. Выберите JPEG, PNG или WebP и повторите запрос."
        "upload_too_large" -> "Изображение слишком большое. Выберите другой снимок."
        "image_unprocessable" -> "Не удалось обработать это изображение. Выберите другой снимок или повторите запрос."
        "concurrency_limit_reached", "ocr_unavailable", "processing_timeout" -> "Сервис распознавания временно недоступен. Попробуйте ещё раз позже."
        else -> "Сервер не смог обработать изображение. Повторите запрос."
    }

    companion object {
        private val SUPPORTED_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val LOCAL_HTTP_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2")
        private val FIELD_ISSUE_CODES = setOf(
            "conflicting_field_candidates",
            "manual_review_required",
            "date_invalid_calendar_or_format",
        )

        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = false })
            }
        }
    }
}

private fun validateFieldResult(field: PassportField, result: FieldResult) {
    val state = runCatching { RecognitionStatus.valueOf(result.state.uppercase()) }.getOrNull()
        ?: throw PassportRepositoryException("Сервер вернул неподдерживаемое состояние поля.")
    val validState = when (state) {
        RecognitionStatus.RECOGNIZED -> result.value != null && result.value.isNotEmpty() && result.issues.isEmpty()
        RecognitionStatus.UNCERTAIN -> result.issues.isNotEmpty()
        RecognitionStatus.MISSING -> result.value == null
    }
    if (!validState) throw PassportRepositoryException("Сервер вернул неподдерживаемое состояние поля.")
    val value = result.value ?: return
    val validValue = when (field) {
        PassportField.GENDER -> value == "M" || value == "F"
        PassportField.BIRTH_DATE, PassportField.ISSUE_DATE ->
            value.length == 10 && runCatching { LocalDate.parse(value).toString() == value }.getOrDefault(false)
        PassportField.SERIES -> value.matches(Regex("[0-9]{4}"))
        PassportField.NUMBER -> value.matches(Regex("[0-9]{6}"))
        PassportField.DEPARTMENT_CODE -> value.matches(Regex("[0-9]{3}-[0-9]{3}"))
        else -> value.isNotBlank()
    }
    if (!validValue) throw PassportRepositoryException("Сервер вернул некорректный формат поля.")
    if (result.issues.any { it.code !in setOf("conflicting_field_candidates", "manual_review_required", "date_invalid_calendar_or_format") || it.message.isBlank() }) {
        throw PassportRepositoryException("Сервер вернул неподдерживаемое описание результата.")
    }
}

@Serializable
private data class RecognitionResponse(
    @SerialName("request_id") val requestId: String,
    val status: String,
    @SerialName("review_required") val reviewRequired: Boolean,
    @SerialName("pipeline_version") val pipelineVersion: String,
    val fields: RecognitionFields,
    val warnings: List<RecognitionIssue>,
)

@Serializable
private data class RecognitionFields(
    val surname: FieldResult,
    @SerialName("given_name") val givenName: FieldResult,
    val patronymic: FieldResult,
    val gender: FieldResult,
    @SerialName("birth_date") val birthDate: FieldResult,
    @SerialName("birth_place") val birthPlace: FieldResult,
    val series: FieldResult,
    val number: FieldResult,
    @SerialName("issue_date") val issueDate: FieldResult,
    val issuer: FieldResult,
    @SerialName("department_code") val departmentCode: FieldResult,
) {
    fun allValues(): List<FieldResult> = listOf(
        surname, givenName, patronymic, gender, birthDate, birthPlace,
        series, number, issueDate, issuer, departmentCode,
    )

    fun forField(field: PassportField): FieldResult = when (field) {
        PassportField.SURNAME -> surname
        PassportField.GIVEN_NAME -> givenName
        PassportField.PATRONYMIC -> patronymic
        PassportField.GENDER -> gender
        PassportField.BIRTH_DATE -> birthDate
        PassportField.BIRTH_PLACE -> birthPlace
        PassportField.SERIES -> series
        PassportField.NUMBER -> number
        PassportField.ISSUE_DATE -> issueDate
        PassportField.ISSUER -> issuer
        PassportField.DEPARTMENT_CODE -> departmentCode
    }
}

@Serializable
private data class FieldResult(
    val value: String? = null,
    val state: String,
    val issues: List<RecognitionIssue>,
)

@Serializable
private data class RecognitionIssue(val code: String, val message: String)

@Serializable
private data class ErrorResponse(
    @SerialName("request_id") val requestId: String,
    val code: String,
    val message: String,
)

private fun headersOf(vararg pairs: String): io.ktor.http.Headers = io.ktor.http.headers {
    pairs.toList().chunked(2).forEach { (name, value) -> append(name, value) }
}
