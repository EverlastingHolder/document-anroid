package ru.passport.scanner.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.streams.asInput
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.passport.scanner.model.PassportDraft
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportIssue
import ru.passport.scanner.model.PassportValue
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage
import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.time.Instant
import java.time.LocalDate

interface PassportRepository {
    suspend fun recognize(image: ScanImage, requestId: String): PassportDraft
    suspend fun submit(payload: AsyncUploadPayload, requestId: String, idempotencyKey: String, connection: ApiConnection): AcceptedJob =
        throw AsyncJobRepositoryException("Асинхронная отправка заданий недоступна.", AsyncFailureCategory.REQUEST_REJECTED)
    suspend fun readStatus(jobId: String, requestId: String, connection: ApiConnection): JobStatus =
        throw AsyncJobRepositoryException("Асинхронный статус заданий недоступен.", AsyncFailureCategory.JOB_NOT_FOUND)
    suspend fun acknowledgeReceipt(jobId: String, requestId: String, connection: ApiConnection) = Unit
    fun close() = Unit
}

open class PassportRepositoryException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AsyncJobRepositoryException(
    message: String,
    val category: AsyncFailureCategory,
    val httpStatus: Int? = null,
) : PassportRepositoryException(message)

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
        val imageBytes = image.bytes
        if (imageBytes.isEmpty()) {
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
                                    size = imageBytes.size.toLong(),
                                ) { ByteArrayInputStream(imageBytes).asInput() }
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
        } finally {
            imageBytes.fill(0)
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
            response.body<CanonicalRecognitionResponse>()
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
        return mapCanonicalResult(dto, requestId, isAsync = false)
    }

    override suspend fun submit(
        payload: AsyncUploadPayload,
        requestId: String,
        idempotencyKey: String,
        connection: ApiConnection,
    ): AcceptedJob {
        validateRequestId(requestId)
        if (!IDEMPOTENCY_KEY_PATTERN.matches(idempotencyKey)) {
            throw AsyncJobRepositoryException("Не удалось подготовить ключ отправки. Повторите попытку.", AsyncFailureCategory.REQUEST_REJECTED)
        }
        val response = try {
            withContext(Dispatchers.IO) {
                client.post("${connection.baseUrl}/v1/passport/jobs") {
                    timeout { requestTimeoutMillis = ASYNC_REQUEST_TIMEOUT_MILLIS }
                    header("Authorization", connection.authorizationHeaderValue())
                    header("X-Request-ID", requestId)
                    header("Idempotency-Key", idempotencyKey)
                    setBody(multipartImage(payload))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        if (response.status != HttpStatusCode.Accepted) {
            throw asyncHttpFailure(response.status, response.bodyAsTextSafely(), isStatusRead = false, expectedRequestId = requestId)
        }
        try {
            val dto = decodeBody<AcceptedJobResponse>(response.bodyAsTextSafely(), "Сервер вернул некорректный ответ задания.")
            val jobId = validateResponseJobId(dto.jobId)
            val statusUrl = "/v1/passport/jobs/$jobId"
            if (dto.statusUrl != statusUrl) throw malformedAsyncResponse(HttpStatusCode.Accepted.value)
            val status = parseJobState(dto.status)
            val createdAt = parseResponseTimestamp(dto.createdAt, HttpStatusCode.Accepted.value)
            return AcceptedJob(jobId, status, statusUrl, createdAt)
        } catch (failure: AsyncJobRepositoryException) {
            throw failure
        } catch (_: PassportRepositoryException) {
            throw malformedAsyncResponse(HttpStatusCode.Accepted.value)
        }
    }

    override suspend fun readStatus(jobId: String, requestId: String, connection: ApiConnection): JobStatus {
        validateRequestId(requestId)
        val normalizedJobId = normalizeRequestedJobId(jobId)
        val response = try {
            withContext(Dispatchers.IO) {
                client.get("${connection.baseUrl}/v1/passport/jobs/$normalizedJobId") {
                    timeout { requestTimeoutMillis = ASYNC_REQUEST_TIMEOUT_MILLIS }
                    header("Authorization", connection.authorizationHeaderValue())
                    header("X-Request-ID", requestId)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        if (response.status != HttpStatusCode.OK) {
            throw asyncHttpFailure(response.status, response.bodyAsTextSafely(), isStatusRead = true, expectedRequestId = requestId)
        }
        val text = response.bodyAsTextSafely()
        val root = decodeAsyncBody<kotlinx.serialization.json.JsonElement>(text, HttpStatusCode.OK.value)
        val obj = root as? JsonObject ?: throw malformedJobStatus()
        val statusText = obj["status"]?.jsonPrimitive?.contentOrNull ?: throw malformedJobStatus()
        val id = validateResponseJobId(obj["job_id"]?.jsonPrimitive?.contentOrNull ?: throw malformedJobStatus())
        if (id != normalizedJobId) throw malformedJobStatus()
        val expected = when (statusText) {
            "queued", "processing" -> setOf("job_id", "status", "created_at", "updated_at", "started_at", "finished_at")
            "completed" -> setOf("job_id", "status", "created_at", "updated_at", "started_at", "finished_at", "result")
            "failed" -> setOf("job_id", "status", "created_at", "updated_at", "started_at", "finished_at", "error")
            else -> throw malformedJobStatus()
        }
        if (obj.keys != expected) throw malformedJobStatus()
        val envelope = decodeAsyncBody<JobStatusEnvelope>(text, HttpStatusCode.OK.value)
        val createdAt = parseResponseTimestamp(envelope.createdAt, HttpStatusCode.OK.value)
        val updatedAt = parseResponseTimestamp(envelope.updatedAt, HttpStatusCode.OK.value)
        val startedAt = envelope.startedAt?.let { parseResponseTimestamp(it, HttpStatusCode.OK.value) }
        val finishedAt = envelope.finishedAt?.let { parseResponseTimestamp(it, HttpStatusCode.OK.value) }
        return when (statusText) {
            "queued" -> {
                if (finishedAt != null) throw malformedJobStatus()
                JobStatus.Queued(id, createdAt, updatedAt, startedAt, null)
            }
            "processing" -> {
                if (startedAt == null || finishedAt != null) throw malformedJobStatus()
                JobStatus.Processing(id, createdAt, updatedAt, startedAt, null)
            }
            "completed" -> {
                val result = envelope.result ?: throw malformedJobStatus()
                if (startedAt == null || finishedAt == null || envelope.error != null || result.requestId != id) {
                    throw malformedJobStatus()
                }
                JobStatus.Completed(
                    id, createdAt, updatedAt, startedAt, finishedAt,
                    result.pipelineVersion,
                    mapAsyncCanonicalResult(result, id),
                )
            }
            else -> {
                val error = envelope.error ?: throw malformedJobStatus()
                val safeMessage = JOB_ERROR_MESSAGES[error.code] ?: throw malformedJobStatus()
                if (finishedAt == null || envelope.result != null || error.message.isBlank()) throw malformedJobStatus()
                JobStatus.Failed(id, createdAt, updatedAt, startedAt, finishedAt, error.code, safeMessage)
            }
        }
    }

    override suspend fun acknowledgeReceipt(jobId: String, requestId: String, connection: ApiConnection) {
        validateRequestId(requestId)
        val normalizedJobId = normalizeRequestedJobId(jobId)
        val response = try {
            withContext(Dispatchers.IO) {
                client.post("${connection.baseUrl}/v1/passport/jobs/$normalizedJobId/receipt") {
                    timeout { requestTimeoutMillis = ASYNC_REQUEST_TIMEOUT_MILLIS }
                    header("Authorization", connection.authorizationHeaderValue())
                    header("X-Request-ID", requestId)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw networkFailure(error)
        }
        if (response.status == HttpStatusCode.NoContent) return
        throw asyncHttpFailure(response.status, response.bodyAsTextSafely(), isStatusRead = false, expectedRequestId = requestId, isReceipt = true)
    }

    private fun validateRequestId(requestId: String) {
        if (!UUID_PATTERN.matches(requestId)) {
            throw AsyncJobRepositoryException("Не удалось подготовить идентификатор запроса. Повторите попытку.", AsyncFailureCategory.REQUEST_REJECTED)
        }
    }

    private fun multipartImage(payload: AsyncUploadPayload) = MultiPartFormDataContent(
        formData {
            appendInput(
                "file",
                headers = headersOf(
                    HttpHeaders.ContentDisposition,
                    "filename=\"scan\"",
                    HttpHeaders.ContentType,
                    payload.mimeType,
                ),
                size = payload.sizeBytes.toLong(),
            ) { ZeroingByteArrayInputStream(payload.copyBytes()).asInput() }
        },
    )

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsTextSafely(): String = try {
        bodyAsText()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ""
    }

    private inline fun <reified T> decodeBody(text: String, message: String): T = try {
        ASYNC_JSON.decodeFromString<T>(text)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw PassportRepositoryException(message)
    }

    private fun networkFailure(error: Exception): AsyncJobRepositoryException = when (error) {
        is HttpRequestTimeoutException, is SocketTimeoutException -> AsyncJobRepositoryException(
            "Сервер не ответил вовремя. Проверьте состояние задания и повторите запрос.", AsyncFailureCategory.TIMEOUT,
        )
        is ConnectTimeoutException -> AsyncJobRepositoryException(
            "Не удалось подключиться к серверу. Проверьте сеть и доступность API.", AsyncFailureCategory.TRANSPORT,
        )
        else -> AsyncJobRepositoryException(
            "Не удалось связаться с сервером. Проверьте подключение к сети и доступность API.", AsyncFailureCategory.TRANSPORT,
        )
    }

    private fun asyncHttpFailure(
        status: HttpStatusCode,
        body: String,
        isStatusRead: Boolean,
        expectedRequestId: String,
        isReceipt: Boolean = false,
    ): AsyncJobRepositoryException {
        val dto = runCatching { ASYNC_JSON.decodeFromString<AsyncErrorResponse>(body) }.getOrNull()
        val allowed = when {
            isReceipt -> RECEIPT_ERROR_CODES[status.value]
            isStatusRead -> GET_ERROR_CODES[status.value]
            else -> POST_ERROR_CODES[status.value]
        }
        val code = dto?.takeIf {
            it.requestId == expectedRequestId && UUID_PATTERN.matches(it.requestId) && it.code == allowed && it.message.isNotBlank()
        }?.code
        val category = asyncFailureCategory(status, isStatusRead)
        val resolvedCategory = when {
            isReceipt && status.value == 409 && code == "job_not_terminal" -> AsyncFailureCategory.JOB_NOT_TERMINAL
            isStatusRead && status.value == 410 && code == "job_result_gone" -> AsyncFailureCategory.JOB_RESULT_GONE
            isReceipt && status.value == 409 -> AsyncFailureCategory.INVALID_RESPONSE
            isStatusRead && status.value == 410 -> AsyncFailureCategory.INVALID_RESPONSE
            else -> category
        }
        return AsyncJobRepositoryException(code?.let(ASYNC_ERROR_MESSAGES::get) ?: messageFor(resolvedCategory), resolvedCategory, status.value)
    }

    private fun normalizeRequestedJobId(value: String): String {
        if (!UUID_V4_PATTERN.matches(value)) {
            throw AsyncJobRepositoryException("Не удалось подготовить идентификатор задания.", AsyncFailureCategory.REQUEST_REJECTED)
        }
        return value.lowercase()
    }

    private fun validateResponseJobId(value: String): String {
        if (!UUID_V4_PATTERN.matches(value)) throw malformedJobStatus()
        return value.lowercase()
    }

    private fun parseResponseTimestamp(value: String, httpStatus: Int): Instant =
        runCatching { Instant.parse(value) }.getOrElse { throw malformedAsyncResponse(httpStatus) }

    private fun parseJobState(value: String): JobState = when (value) {
        "queued" -> JobState.QUEUED
        "processing" -> JobState.PROCESSING
        "completed" -> JobState.COMPLETED
        "failed" -> JobState.FAILED
        else -> throw malformedAsyncResponse(HttpStatusCode.Accepted.value)
    }

    private fun malformedJobStatus() = malformedAsyncResponse(HttpStatusCode.OK.value)

    private fun malformedAsyncResponse(status: Int) = AsyncJobRepositoryException(
        "Сервер вернул некорректный ответ задания.", AsyncFailureCategory.INVALID_RESPONSE, status,
    )

    private fun asyncFailureCategory(status: HttpStatusCode, isStatusRead: Boolean): AsyncFailureCategory = when (status.value) {
        401 -> AsyncFailureCategory.UNAUTHORIZED
        403 -> AsyncFailureCategory.ACCESS_DENIED
        404 -> if (isStatusRead) AsyncFailureCategory.JOB_NOT_FOUND else AsyncFailureCategory.REQUEST_REJECTED
        409 -> AsyncFailureCategory.IDEMPOTENCY_CONFLICT
        400, 413, 415, 405 -> AsyncFailureCategory.REQUEST_REJECTED
        in 500..599 -> AsyncFailureCategory.SERVER_UNAVAILABLE
        else -> AsyncFailureCategory.INVALID_RESPONSE
    }

    private fun messageFor(category: AsyncFailureCategory): String = when (category) {
        AsyncFailureCategory.UNAUTHORIZED -> ASYNC_ERROR_MESSAGES.getValue("unauthorized")
        AsyncFailureCategory.ACCESS_DENIED -> ASYNC_ERROR_MESSAGES.getValue("access_denied")
        AsyncFailureCategory.IDEMPOTENCY_CONFLICT -> ASYNC_ERROR_MESSAGES.getValue("idempotency_conflict")
        AsyncFailureCategory.JOB_NOT_FOUND -> ASYNC_ERROR_MESSAGES.getValue("not_found")
        AsyncFailureCategory.JOB_NOT_TERMINAL -> ASYNC_ERROR_MESSAGES.getValue("job_not_terminal")
        AsyncFailureCategory.JOB_RESULT_GONE -> ASYNC_ERROR_MESSAGES.getValue("job_result_gone")
        AsyncFailureCategory.REQUEST_REJECTED,
        AsyncFailureCategory.SERVER_UNAVAILABLE,
        AsyncFailureCategory.TRANSPORT,
        AsyncFailureCategory.TIMEOUT,
        AsyncFailureCategory.INVALID_RESPONSE -> GENERIC_ASYNC_ERROR
    }

    private inline fun <reified T> decodeAsyncBody(text: String, status: Int): T = try {
        ASYNC_JSON.decodeFromString<T>(text)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw malformedAsyncResponse(status)
    }

    private fun mapCanonicalResult(dto: CanonicalRecognitionResponse, expectedJobId: String, isAsync: Boolean): PassportDraft {
        if (dto.status != "completed" || !dto.reviewRequired || dto.requestId != expectedJobId || dto.pipelineVersion.isBlank()) {
            throw PassportRepositoryException(
                if (isAsync) "Сервер вернул некорректный статус задания." else "Сервер вернул неподдерживаемый результат распознавания.",
            )
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
            throw PassportRepositoryException(
                if (isAsync) "Сервер вернул некорректный статус задания." else "Сервер вернул неподдерживаемое предупреждение распознавания.",
            )
        }
        if (dto.fields.allValues().any { result -> result.issues.any { it.code !in FIELD_ISSUE_CODES || it.message.isBlank() } }) {
            throw PassportRepositoryException(
                if (isAsync) "Сервер вернул некорректный статус задания." else "Сервер вернул неподдерживаемое описание результата.",
            )
        }
        return PassportDraft(values, dto.warnings.map { PassportIssue(it.code, it.message) })
    }

    private fun mapAsyncCanonicalResult(dto: CanonicalRecognitionResponse, expectedJobId: String): PassportDraft = try {
        mapCanonicalResult(dto, expectedJobId, isAsync = true)
    } catch (failure: AsyncJobRepositoryException) {
        throw failure
    } catch (_: PassportRepositoryException) {
        throw malformedJobStatus()
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
        private val UUID_V4_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")
        private val IDEMPOTENCY_KEY_PATTERN = Regex("[\\x21-\\x7E]{1,128}")
        private const val GENERIC_SERVER_ERROR = "Сервер не смог обработать изображение. Повторите запрос."
        private const val GENERIC_ASYNC_ERROR = "Сервер временно не смог обработать запрос. Повторите попытку позже."
        private const val ASYNC_REQUEST_TIMEOUT_MILLIS = 30_000L
        private val ASYNC_JSON = Json { ignoreUnknownKeys = false }
        private val POST_ERROR_CODES = mapOf(
            400 to "invalid_request", 401 to "unauthorized", 403 to "access_denied", 409 to "idempotency_conflict",
            413 to "upload_too_large", 415 to "unsupported_media_type", 503 to "job_acceptance_unavailable",
        )
        private val GET_ERROR_CODES = mapOf(
            401 to "unauthorized", 403 to "access_denied", 404 to "not_found", 405 to "method_not_allowed",
            410 to "job_result_gone", 503 to "job_status_unavailable",
        )
        private val RECEIPT_ERROR_CODES = mapOf(
            401 to "unauthorized", 403 to "access_denied", 404 to "not_found", 409 to "job_not_terminal",
            503 to "job_receipt_unavailable",
        )
        private val ASYNC_ERROR_MESSAGES = mapOf(
            "invalid_request" to "Не удалось отправить запрос. Проверьте изображение и повторите попытку.",
            "unauthorized" to "Сервер не принял данные доступа. Проверьте настройки подключения.",
            "access_denied" to "Доступ к серверу запрещён для этого подключения.",
            "idempotency_conflict" to "Ключ отправки уже связан с другими данными. Не меняйте ключ для повтора.",
            "upload_too_large" to "Изображение слишком большое. Выберите другой снимок.",
            "unsupported_media_type" to "Поддерживаются изображения JPEG, PNG и WebP.",
            "job_acceptance_unavailable" to "Сервер временно не принял задание. Повторите отправку с тем же ключом.",
            "not_found" to "Задание не найдено или недоступно.",
            "method_not_allowed" to "Запрос не поддерживается сервером.",
            "job_status_unavailable" to "Статус задания временно недоступен. Повторите запрос.",
            "job_not_terminal" to "Задание ещё выполняется. Проверим его состояние позже.",
            "job_result_gone" to "Результат больше недоступен. Начните новое распознавание.",
            "job_receipt_unavailable" to "Результат сохранён на устройстве. Сервер временно недоступен; проверьте подключение к сети.",
        )
        private val JOB_ERROR_MESSAGES = mapOf(
            "image_unprocessable" to "Сервер не смог обработать изображение. Выберите другой снимок или отправьте задание повторно.",
            "processing_failed" to "Сервер не смог обработать изображение. Повторите попытку позже.",
            "ocr_unavailable" to "Сервис распознавания временно недоступен. Попробуйте ещё раз позже.",
            "processing_timeout" to "Распознавание заняло слишком много времени. Отправьте новое задание.",
        )
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

internal fun validateFieldResult(field: PassportField, result: FieldResult) {
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
internal data class CanonicalRecognitionResponse(
    @SerialName("request_id") val requestId: String,
    val status: String,
    @SerialName("review_required") val reviewRequired: Boolean,
    @SerialName("pipeline_version") val pipelineVersion: String,
    val fields: RecognitionFields,
    val warnings: List<RecognitionIssue>,
) {
    override fun toString(): String = "CanonicalRecognitionResponse([redacted])"
}

@Serializable
internal data class RecognitionFields(
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
internal data class FieldResult(
    val value: String? = null,
    val state: String,
    val issues: List<RecognitionIssue>,
)

@Serializable
internal data class RecognitionIssue(val code: String, val message: String)

@Serializable
private data class ErrorResponse(
    @SerialName("request_id") val requestId: String,
    val code: String,
    val message: String,
)

private fun headersOf(vararg pairs: String): io.ktor.http.Headers = io.ktor.http.headers {
    pairs.toList().chunked(2).forEach { (name, value) -> append(name, value) }
}

private class ZeroingByteArrayInputStream(bytes: ByteArray) : java.io.ByteArrayInputStream(bytes) {
    override fun close() {
        buf.fill(0)
        super.close()
    }
}
