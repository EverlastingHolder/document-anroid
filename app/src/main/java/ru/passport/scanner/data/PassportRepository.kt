package ru.passport.scanner.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.network.sockets.ConnectTimeoutException
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
import java.net.SocketTimeoutException
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
                                        "filename=\"scan\"",
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
        } catch (e: HttpRequestTimeoutException) {
            throw PassportRepositoryException("Распознавание заняло слишком много времени. Повторите запрос.")
        } catch (e: SocketTimeoutException) {
            throw PassportRepositoryException("Распознавание заняло слишком много времени. Повторите запрос.")
        } catch (e: ConnectTimeoutException) {
            throw PassportRepositoryException("Не удалось подключиться к серверу. Проверьте сеть и доступность API, затем повторите запрос.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Exception details can contain request data or transport internals; never expose/log them.
            throw PassportRepositoryException("Не удалось связаться с сервером. Проверьте подключение к сети и доступность API, затем повторите запрос.")
        }

        if (response.status != HttpStatusCode.OK) {
            val error = try {
                Json.decodeFromString<ErrorResponse>(response.bodyAsText())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val expectedCode = ERROR_CODES_BY_STATUS[response.status.value]
            val validError = error?.takeIf {
                it.requestId.matches(UUID_PATTERN) && it.message.isNotBlank() && it.code == expectedCode
            }
            throw PassportRepositoryException(validError?.let { safeErrorMessage(it.code) } ?: GENERIC_SERVER_ERROR)
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
            throw PassportRepositoryException("Укажите корректный HTTP- или HTTPS-адрес сервера распознавания.")
        }
        val supportedScheme = uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)
        if (!supportedScheme || uri.host.isNullOrBlank() ||
            uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null
        ) {
            throw PassportRepositoryException("Укажите корректный HTTP- или HTTPS-адрес сервера распознавания.")
        }
        return config.trim().trimEnd('/') + "/v1/passport/recognize"
    }

    private fun safeErrorMessage(code: String): String = when (code) {
        "invalid_request" -> "Не удалось отправить запрос. Проверьте изображение и повторите попытку."
        "access_denied" -> "Сервер запретил доступ. Проверьте адрес сервера или подключение к сети."
        "upload_too_large" -> "Изображение слишком большое. Выберите другой снимок и повторите попытку."
        "unsupported_media_type" -> "Поддерживаются изображения JPEG, PNG и WebP. Выберите другой файл."
        "image_unprocessable" -> "Не удалось обработать это изображение. Выберите другой снимок или повторите запрос."
        "concurrency_limit_reached" -> "Сервис занят. Повторите запрос немного позже."
        "processing_failed" -> "Сервер не смог обработать изображение. Повторите запрос позже."
        "ocr_unavailable" -> "Сервис распознавания временно недоступен. Попробуйте ещё раз позже."
        "processing_timeout" -> "Распознавание заняло слишком много времени. Повторите запрос."
        "not_found", "method_not_allowed" -> "Адрес сервера распознавания настроен неверно. Проверьте его и повторите запрос."
        else -> GENERIC_SERVER_ERROR
    }

    companion object {
        private val SUPPORTED_MEDIA_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private const val GENERIC_SERVER_ERROR = "Сервер не смог обработать изображение. Повторите запрос."
        private val ERROR_CODES_BY_STATUS = mapOf(
            400 to "invalid_request",
            403 to "access_denied",
            413 to "upload_too_large",
            415 to "unsupported_media_type",
            422 to "image_unprocessable",
            429 to "concurrency_limit_reached",
            500 to "processing_failed",
            503 to "ocr_unavailable",
            504 to "processing_timeout",
            404 to "not_found",
            405 to "method_not_allowed",
        )
        private val FIELD_ISSUE_CODES = setOf(
            "conflicting_field_candidates",
            "manual_review_required",
            "date_invalid_calendar_or_format",
        )

        fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            engine {
                config {
                    retryOnConnectionFailure(false)
                }
            }
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = 600_000
                socketTimeoutMillis = 600_000
                connectTimeoutMillis = 15_000
            }
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
