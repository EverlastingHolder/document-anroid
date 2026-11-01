package ru.passport.scanner.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.RecognitionStatus
import ru.passport.scanner.model.ScanImage
import java.io.File

class KtorPassportRepositoryTest {
    @Test
    fun submitsAsyncMultipartWithCapturedBearerAndStableIdempotencyKey() = runTest {
        val image = imageFile()
        val capturedRequests = mutableListOf<HttpRequestData>()
        val repository = KtorPassportRepository(
            "https://api.example.test",
            mockClient { request ->
                capturedRequests += request
                respond(
                    """{"job_id":"123e4567-e89b-42d3-a456-426614174000","status":"queued","status_url":"/v1/passport/jobs/123e4567-e89b-42d3-a456-426614174000","created_at":"2026-10-31T10:00:00Z"}""",
                    HttpStatusCode.Accepted,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val connection = connection("pbt1_" + "A".repeat(43))

        val accepted = repository.submit(
            AsyncUploadPayload(byteArrayOf(1, 2, 3), "image/jpeg"),
            "123e4567-e89b-42d3-a456-426614174001",
            "stable-retry-key-1",
            connection,
        )

        assertEquals("123e4567-e89b-42d3-a456-426614174000", accepted.jobId)
        assertEquals(JobState.QUEUED, accepted.status)
        assertEquals("/v1/passport/jobs/${accepted.jobId}", accepted.statusUrl)
        val request = capturedRequests.single()
        assertEquals("POST", request.method.value)
        assertEquals(connection.authorizationHeaderValue(), request.headers["Authorization"])
        assertEquals("stable-retry-key-1", request.headers["Idempotency-Key"])
        assertEquals("123e4567-e89b-42d3-a456-426614174001", request.headers["X-Request-ID"])
        val multipart = request.body as OutgoingContent
        assertTrue(multipart.contentType.toString().startsWith("multipart/form-data"))
        val raw = String(multipart.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(raw.contains("name=\"file\"; filename=\"scan\""))
        assertTrue(raw.contains("Content-Type: image/jpeg\r\n"))
        assertTrue(raw.contains("\u0001\u0002\u0003"))
        repository.close()
        image.delete()
    }

    @Test
    fun readsEveryJobStateAndMapsCompletedCanonicalResultUsingJobId() = runTest {
        val jobId = "123e4567-e89b-42d3-a456-426614174000"
        val bodies = listOf(
            """{"job_id":"$jobId","status":"queued","created_at":"2026-10-31T10:00:00Z","updated_at":"2026-10-31T10:00:00Z","started_at":"2026-10-31T10:00:30Z","finished_at":null}""",
            """{"job_id":"$jobId","status":"processing","created_at":"2026-10-31T10:00:00Z","updated_at":"2026-10-31T10:01:00Z","started_at":"2026-10-31T10:01:00Z","finished_at":null}""",
            """{"job_id":"$jobId","status":"completed","created_at":"2026-10-31T10:00:00Z","updated_at":"2026-10-31T10:02:00Z","started_at":"2026-10-31T10:01:00Z","finished_at":"2026-10-31T10:02:00Z","result":${successResponse().replace("123e4567-e89b-12d3-a456-426614174000", jobId)}}""",
            """{"job_id":"$jobId","status":"failed","created_at":"2026-10-31T10:00:00Z","updated_at":"2026-10-31T10:02:00Z","started_at":null,"finished_at":"2026-10-31T10:02:00Z","error":{"code":"processing_timeout","message":"safe timeout"}}""",
        )
        val requests = mutableListOf<HttpRequestData>()
        val connection = connection("pbt1_" + "A".repeat(43))
        val repository = KtorPassportRepository(
            connection.baseUrl,
            mockClient { request ->
                requests += request
                val index = requests.lastIndex
                respond(bodies[index], HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        )

        val queued = repository.readStatus(jobId, "123e4567-e89b-42d3-a456-426614174001", connection)
        val processing = repository.readStatus(jobId, "123e4567-e89b-42d3-a456-426614174002", connection)
        val completed = repository.readStatus(jobId, "123e4567-e89b-42d3-a456-426614174003", connection)
        val failed = repository.readStatus(jobId, "123e4567-e89b-42d3-a456-426614174004", connection)

        assertTrue(queued is JobStatus.Queued)
        assertTrue(processing is JobStatus.Processing)
        assertTrue(completed is JobStatus.Completed)
        assertEquals(jobId, (completed as JobStatus.Completed).jobId)
        assertEquals("0012", completed.result.values.getValue(PassportField.SERIES).value)
        assertFalse(completed.toString().contains("ИВАНОВ"))
        assertFalse(completed.toString().contains(jobId))
        assertTrue(failed is JobStatus.Failed)
        assertEquals("processing_timeout", (failed as JobStatus.Failed).errorCode)
        assertTrue(requests.all { it.headers["Authorization"] == connection.authorizationHeaderValue() })
        assertEquals(listOf("123e4567-e89b-42d3-a456-426614174001", "123e4567-e89b-42d3-a456-426614174002", "123e4567-e89b-42d3-a456-426614174003", "123e4567-e89b-42d3-a456-426614174004"), requests.map { it.headers["X-Request-ID"] })
        assertTrue(requests.all { it.url.toString() == "https://api.example.test/v1/passport/jobs/$jobId" })
        assertTrue(requests.all { it.method.value == "GET" })
        repository.close()
    }

    @Test
    fun receiptUsesEmptyPostWithClientAuthAndNoIdempotencyHeader() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val jobId = "123e4567-e89b-42d3-a456-426614174000"
        val requestId = "123e4567-e89b-42d3-a456-426614174001"
        val connection = connection("pbt1_" + "A".repeat(43))
        val repository = KtorPassportRepository(connection.baseUrl, mockClient { request ->
            captured += request
            respond("", HttpStatusCode.NoContent)
        })

        repository.acknowledgeReceipt(jobId, requestId, connection)

        val request = captured.single()
        assertEquals("POST", request.method.value)
        assertEquals("/v1/passport/jobs/$jobId/receipt", request.url.encodedPath)
        assertEquals(connection.authorizationHeaderValue(), request.headers["Authorization"])
        assertEquals(requestId, request.headers["X-Request-ID"])
        assertEquals(null, request.headers["Idempotency-Key"])
        assertEquals(0, (request.body as OutgoingContent).toByteArray().size)
        repository.close()
    }

    @Test
    fun mapsReceiptConflictAndPurgedStatusWithoutLeakingServerDetails() = runTest {
        val jobId = "123e4567-e89b-42d3-a456-426614174000"
        val requestId = "123e4567-e89b-42d3-a456-426614174001"
        val connection = connection("pbt1_" + "A".repeat(43))
        val responses = ArrayDeque(listOf(
            HttpStatusCode.Conflict to "job_not_terminal",
            HttpStatusCode.Gone to "job_result_gone",
        ))
        val repository = KtorPassportRepository(connection.baseUrl, mockClient {
            val (status, code) = responses.removeFirst()
            respond(
                """{"request_id":"$requestId","code":"$code","message":"private server payload"}""",
                status,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })

        val receiptFailure = runCatching { repository.acknowledgeReceipt(jobId, requestId, connection) }.exceptionOrNull()
        val statusFailure = runCatching { repository.readStatus(jobId, requestId, connection) }.exceptionOrNull()

        assertEquals(AsyncFailureCategory.JOB_NOT_TERMINAL, (receiptFailure as AsyncJobRepositoryException).category)
        assertEquals(AsyncFailureCategory.JOB_RESULT_GONE, (statusFailure as AsyncJobRepositoryException).category)
        assertFalse(receiptFailure.message.orEmpty().contains("private server payload"))
        assertFalse(statusFailure.message.orEmpty().contains("private server payload"))
        repository.close()
    }

    @Test
    fun rejectsForeignStatusUrlAndNeverFollowsItWithBearerCredential() = runTest {
        val image = imageFile()
        var requestCount = 0
        val repository = KtorPassportRepository(
            "https://api.example.test",
            mockClient {
                requestCount += 1
                respond(
                    """{"job_id":"123e4567-e89b-42d3-a456-426614174000","status":"queued","status_url":"https://attacker.example/collect","created_at":"2026-10-31T10:00:00Z"}""",
                    HttpStatusCode.Accepted,
                    headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            },
        )
        val connection = connection("pbt1_" + "A".repeat(43))
        val error = runCatching {
            repository.submit(AsyncUploadPayload(byteArrayOf(1, 2, 3), "image/jpeg"), "123e4567-e89b-42d3-a456-426614174001", "same-key", connection)
        }.exceptionOrNull()

        assertTrue(error is PassportRepositoryException)
        assertEquals("https://attacker.example/collect".contains(error?.message.orEmpty()), false)
        assertEquals(1, requestCount)
        repository.close()
        image.delete()
    }

    @Test
    fun rejectsMalformedStatusSnapshotsAndDoesNotExposeServerPayloads() = runTest {
        val jobId = "123e4567-e89b-42d3-a456-426614174000"
        val bodies = listOf(
            """{"job_id":"$jobId","status":"queued","created_at":"2026-10-31T10:00:00Z","updated_at":"2026-10-31T10:00:00Z","started_at":null,"finished_at":null,"result":{"secret":"passport"}}""",
            """{"job_id":"$jobId","status":"completed","created_at":"2026-10-31T10:00:00Z","updated_at":"2026-10-31T10:02:00Z","started_at":"2026-10-31T10:01:00Z","finished_at":"2026-10-31T10:02:00Z","result":${successResponse().replace("123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-42d3-a456-426614174099")}}""",
            """{"job_id":"$jobId","status":"failed","created_at":"2026-10-31T10:00:00Z","updated_at":"2026-10-31T10:02:00Z","started_at":null,"finished_at":"2026-10-31T10:02:00Z","error":{"code":"unknown_private_code","message":"passport secret"}}""",
        )
        bodies.forEach { body ->
            val connection = connection("pbt1_" + "A".repeat(43))
            val repository = KtorPassportRepository(
                connection.baseUrl,
                mockClient { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) },
            )
            val failure = runCatching {
                repository.readStatus(jobId, "123e4567-e89b-42d3-a456-426614174001", connection)
            }.exceptionOrNull()
            assertTrue(failure is PassportRepositoryException)
            assertFalse(failure?.message.orEmpty().contains("passport"))
            assertFalse(failure?.message.orEmpty().contains("secret"))
            repository.close()
        }
    }

    @Test
    fun sendsSameCallerProvidedKeyOnRepeatedSubmissionAndDoesNotRetryPostInternally() = runTest {
        val image = imageFile()
        var count = 0
        val seenKeys = mutableListOf<String?>()
        val repository = KtorPassportRepository(
            "https://api.example.test",
            mockClient { request ->
                count += 1
                seenKeys += request.headers["Idempotency-Key"]
                respond("unavailable", HttpStatusCode.ServiceUnavailable)
            },
        )
        val connection = connection("pbt1_" + "A".repeat(43))
        repeat(2) {
            runCatching {
                repository.submit(AsyncUploadPayload(byteArrayOf(1, 2, 3), "image/jpeg"), "123e4567-e89b-42d3-a456-426614174001", "stable-key", connection)
            }.onFailure { failure ->
                assertTrue(failure is AsyncJobRepositoryException)
                assertEquals(AsyncFailureCategory.SERVER_UNAVAILABLE, (failure as AsyncJobRepositoryException).category)
                assertEquals(503, failure.httpStatus)
            }
        }
        assertEquals(2, count)
        assertEquals(listOf("stable-key", "stable-key"), seenKeys)
        repository.close()
        image.delete()
    }

    @Test
    fun mapsAsyncConflictSafelyAndRejectsMismatchedErrorCorrelation() = runTest {
        val image = imageFile()
        val requestId = "123e4567-e89b-42d3-a456-426614174001"
        val connection = connection("pbt1_" + "A".repeat(43))
        val responses = ArrayDeque(
            listOf(
                """{"request_id":"$requestId","code":"idempotency_conflict","message":"private server detail"}""",
                """{"request_id":"123e4567-e89b-42d3-a456-426614174002","code":"idempotency_conflict","message":"private server detail"}""",
            ),
        )
        val repository = KtorPassportRepository(
            connection.baseUrl,
            mockClient { respond(responses.removeFirst(), HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) },
        )

        val first = runCatching {
            repository.submit(AsyncUploadPayload(byteArrayOf(1, 2, 3), "image/jpeg"), requestId, "same-key", connection)
        }.exceptionOrNull()
        val second = runCatching {
            repository.submit(AsyncUploadPayload(byteArrayOf(1, 2, 3), "image/jpeg"), requestId, "same-key", connection)
        }.exceptionOrNull()

        assertTrue(first?.message.orEmpty().contains("Не меняйте ключ"))
        assertTrue(first is AsyncJobRepositoryException)
        assertEquals(AsyncFailureCategory.IDEMPOTENCY_CONFLICT, (first as AsyncJobRepositoryException).category)
        assertEquals(409, first.httpStatus)
        assertFalse(first?.message.orEmpty().contains("private server detail"))
        assertEquals(AsyncFailureCategory.IDEMPOTENCY_CONFLICT, (second as AsyncJobRepositoryException).category)
        assertEquals("Ключ отправки уже связан с другими данными. Не меняйте ключ для повтора.", second.message)
        repository.close()
        image.delete()
    }

    @Test
    fun refusesInvalidIdempotencyKeyBeforeNetworkRequest() = runTest {
        val image = imageFile()
        var requestCount = 0
        val repository = KtorPassportRepository(
            "https://api.example.test",
            mockClient { requestCount += 1; error("must not send") },
        )
        val error = runCatching {
            repository.submit(
                AsyncUploadPayload(byteArrayOf(1, 2, 3), "image/jpeg"),
                "123e4567-e89b-42d3-a456-426614174001",
                "bad key with spaces",
                connection("pbt1_" + "A".repeat(43)),
            )
        }.exceptionOrNull()

        assertTrue(error is PassportRepositoryException)
        assertEquals(0, requestCount)
        repository.close()
        image.delete()
    }

    @Test
    fun categorizesAmbiguousAsyncTimeoutAndPropagatesCancellation() = runTest {
        val connection = connection("pbt1_" + "A".repeat(43))
        val timeoutRepository = KtorPassportRepository(
            connection.baseUrl,
            mockClient { throw HttpRequestTimeoutException(HttpRequestBuilder()) },
        )
        val payload = AsyncUploadPayload(byteArrayOf(1, 2, 3), "image/jpeg")
        val timeout = runCatching {
            timeoutRepository.submit(payload, "123e4567-e89b-42d3-a456-426614174001", "stable-key", connection)
        }.exceptionOrNull()
        assertTrue(timeout is AsyncJobRepositoryException)
        assertEquals(AsyncFailureCategory.TIMEOUT, (timeout as AsyncJobRepositoryException).category)
        assertEquals(null, timeout.httpStatus)
        timeoutRepository.close()

        val cancellation = CancellationException("test cancellation")
        val cancelledRepository = KtorPassportRepository(connection.baseUrl, mockClient { throw cancellation })
        val cancelled = runCatching {
            cancelledRepository.readStatus(
                "123e4567-e89b-42d3-a456-426614174000",
                "123e4567-e89b-42d3-a456-426614174002",
                connection,
            )
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        cancelledRepository.close()
    }

    @Test
    fun postsExpectedMultipartAndMapsStrictRecognitionSchema() = runTest {
        val image = imageFile()
        var seenUrl = ""
        var seenRequestId = ""
        var seenBodyContentType = ""
        var multipartBodyBytes = byteArrayOf()
        val client = mockClient { request ->
            seenUrl = request.url.toString()
            seenRequestId = request.headers["X-Request-ID"].orEmpty()
            val multipart = request.body as? OutgoingContent
            seenBodyContentType = multipart?.contentType?.toString().orEmpty()
            multipartBodyBytes = multipart?.toByteArray() ?: byteArrayOf()
            respond(successResponse(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val repository = KtorPassportRepository("https://api.example.test", client)

        val result = repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")

        assertEquals("https://api.example.test/v1/passport/recognize", seenUrl)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", seenRequestId)
        assertTrue(seenBodyContentType.startsWith("multipart/form-data"))
        val multipartBody = String(multipartBodyBytes, Charsets.ISO_8859_1)
        val boundaryMatch = Regex("""boundary=(?:"([^"]+)"|([^;]+))""", RegexOption.IGNORE_CASE)
            .find(seenBodyContentType)
        val boundary = boundaryMatch?.groupValues?.let { it[1].ifEmpty { it[2] } }
        assertNotNull(boundary)
        val delimiter = "--${checkNotNull(boundary)}"
        assertEquals(2, multipartBody.split(delimiter).size - 1)
        assertTrue(multipartBody.startsWith("$delimiter\r\n"))
        assertTrue(multipartBody.endsWith("$delimiter--\r\n"))
        val contentDispositionHeaders = multipartBody.lineSequence()
            .filter { it.startsWith("Content-Disposition:", ignoreCase = true) }
            .toList()
        assertEquals(1, contentDispositionHeaders.size)
        val contentDispositionHeader = contentDispositionHeaders.single()
        assertEquals(1, Regex("""\bform-data\b""", RegexOption.IGNORE_CASE).findAll(contentDispositionHeader).count())
        val contentDispositionParameters = Regex(
            """(?:^|;\s*)(name|filename)=(?:"([^"]*)"|([^;\s]+))""",
            RegexOption.IGNORE_CASE,
        ).findAll(contentDispositionHeader)
            .map { match ->
                match.groupValues[1].lowercase() to match.groupValues[2].ifEmpty { match.groupValues[3] }
            }
            .toList()
        assertEquals(2, contentDispositionParameters.size)
        assertEquals(listOf("file"), contentDispositionParameters.filter { it.first == "name" }.map { it.second })
        assertEquals(listOf("scan"), contentDispositionParameters.filter { it.first == "filename" }.map { it.second })
        assertTrue(multipartBody.contains("Content-Type: image/jpeg\r\n"))
        assertTrue(multipartBodyBytes.toList().windowed(3).contains(byteArrayOf(1, 2, 3).toList()))
        assertEquals(11, result.values.size)
        assertEquals("0012", result.values.getValue(PassportField.SERIES).value)
        assertEquals("000345", result.values.getValue(PassportField.NUMBER).value)
        assertEquals(RecognitionStatus.MISSING, result.values.getValue(PassportField.PATRONYMIC).status)
        assertEquals("manual_review_required", result.values.getValue(PassportField.GENDER).issues.single().code)
        assertTrue(result.warnings.isEmpty())
        repository.close()
        image.delete()
    }

    @Test
    fun mapsBackendErrorCodesToSafeClientTextWithoutUsingServerMessage() = runTest {
        val image = imageFile()
        val client = mockClient {
            respond(
                """{"request_id":"123e4567-e89b-12d3-a456-426614174000","code":"image_unprocessable","message":"raw passport value"}""",
                HttpStatusCode.UnprocessableEntity,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val repository = KtorPassportRepository("https://api.example.test", client)

        val error = runCatching {
            repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
        }.exceptionOrNull()

        assertTrue(error is PassportRepositoryException)
        assertTrue(error?.message.orEmpty().contains("обработать это изображение"))
        assertFalse(error?.message.orEmpty().contains("raw passport value"))
        repository.close()
        image.delete()
    }

    @Test
    fun mapsEveryContractErrorStatusAndCodeWithoutRetryingPost() = runTest {
        val image = imageFile()
        val errors = listOf(
            HttpStatusCode.BadRequest to "invalid_request",
            HttpStatusCode.Forbidden to "access_denied",
            HttpStatusCode(413, "Payload Too Large") to "upload_too_large",
            HttpStatusCode.UnsupportedMediaType to "unsupported_media_type",
            HttpStatusCode.UnprocessableEntity to "image_unprocessable",
            HttpStatusCode.TooManyRequests to "concurrency_limit_reached",
            HttpStatusCode.InternalServerError to "processing_failed",
            HttpStatusCode.ServiceUnavailable to "ocr_unavailable",
            HttpStatusCode.GatewayTimeout to "processing_timeout",
            HttpStatusCode.NotFound to "not_found",
            HttpStatusCode.MethodNotAllowed to "method_not_allowed",
        )
        errors.forEach { (status, code) ->
            var postCount = 0
            val repository = KtorPassportRepository(
                "http://192.168.1.91:8080",
                mockClient {
                    postCount += 1
                    respond(
                        """{"request_id":"123e4567-e89b-12d3-a456-426614174000","code":"$code","message":"raw passport value"}""",
                        status,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                },
            )

            val failure = runCatching {
                repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
            }.exceptionOrNull()

            assertTrue("Expected $status/$code to be a repository error", failure is PassportRepositoryException)
            assertFalse(failure?.message.orEmpty().contains("raw passport value"))
            assertEquals("POST must not be retried for $status", 1, postCount)
            repository.close()
        }
        image.delete()
    }

    @Test
    fun malformedOrMismatchedErrorResponseUsesGenericSafeText() = runTest {
        val image = imageFile()
        val bodies = listOf(
            "not json",
            """{"request_id":"bad","code":"image_unprocessable","message":"private detail"}""",
            """{"request_id":"123e4567-e89b-12d3-a456-426614174000","code":"unknown","message":"private detail"}""",
            """{"request_id":"123e4567-e89b-12d3-a456-426614174000","code":"image_unprocessable","message":""}""",
            """{"request_id":"123e4567-e89b-12d3-a456-426614174000","code":"processing_failed","message":"private detail"}""",
            """{"request_id":"123e4567-e89b-12d3-a456-426614174000","code":"image_unprocessable","message":"private detail","extra":true}""",
        )
        bodies.forEach { body ->
            val repository = KtorPassportRepository(
                "https://api.example.test",
                mockClient {
                    respond(body, HttpStatusCode.UnprocessableEntity, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )
            val failure = runCatching {
                repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
            }.exceptionOrNull()
            assertTrue(failure is PassportRepositoryException)
            assertEquals("Сервер не смог обработать изображение. Повторите запрос.", failure?.message)
            repository.close()
        }
        image.delete()
    }

    @Test
    fun propagatesTransportCancellationInsteadOfMappingItToNetworkFailure() = runTest {
        val image = imageFile()
        val cancellation = CancellationException("test cancellation")
        val repository = KtorPassportRepository(
            "https://api.example.test",
            mockClient { throw cancellation },
        )

        val failure = runCatching {
            repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        repository.close()
        image.delete()
    }

    @Test
    fun rejectsRecognitionResponseThatOmitsRequiredSchemaField() = runTest {
        val image = imageFile()
        val malformed = successResponse().replace(",\"issuer\":${field("ОТДЕЛ")}", "")
        val repository = KtorPassportRepository(
            "https://api.example.test",
            mockClient {
                respond(malformed, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        )

        val failure = runCatching {
            repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
        }.exceptionOrNull()

        assertTrue(failure is PassportRepositoryException)
        assertTrue(failure?.message.orEmpty().contains("не смогло прочитать"))
        repository.close()
        image.delete()
    }

    @Test
    fun refusesMalformedUrlAndMissingBaseUrlBeforeMakingRequest() = runTest {
        val image = imageFile()
        val malformed = KtorPassportRepository("ftp://api.example.test", mockClient { error("must not send") })
        val empty = KtorPassportRepository("", mockClient { error("must not send") })

        val malformedError = runCatching { malformed.recognize(ScanImage("local-id", image, "image/jpeg"), "id") }.exceptionOrNull()
        val emptyError = runCatching { empty.recognize(ScanImage("local-id", image, "image/jpeg"), "id") }.exceptionOrNull()

        assertTrue(malformedError?.message.orEmpty().contains("HTTP- или HTTPS"))
        assertTrue(emptyError?.message.orEmpty().contains("не настроен"))
        malformed.close()
        empty.close()
        image.delete()
    }

    @Test
    fun allowsConfiguredLanHttpAndUsesRequestEndpoint() = runTest {
        val image = imageFile()
        var seenUrl = ""
        val repository = KtorPassportRepository(
            "http://192.168.1.91:8080",
            mockClient { request ->
                seenUrl = request.url.toString()
                respond(successResponse(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
        )

        repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")

        assertEquals("http://192.168.1.91:8080/v1/passport/recognize", seenUrl)
        repository.close()
        image.delete()
    }

    @Test
    fun rejectsHttpUrlWithCredentialsQueryOrFragment() = runTest {
        val image = imageFile()
        val malformedLocalUrls = listOf(
            "http://user@localhost:8080",
            "http://localhost:8080?token=value",
            "http://localhost:8080/#fragment",
        )
        malformedLocalUrls.forEach { url ->
            val repository = KtorPassportRepository(url, mockClient { error("must not send") })
            val error = runCatching {
                repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
            }.exceptionOrNull()
            assertTrue("Expected $url to be rejected", error?.message.orEmpty().contains("HTTP- или HTTPS"))
            repository.close()
        }
        image.delete()
    }

    @Test
    fun mapsRequestTimeoutToRetryMessageAndKeepsCancellationDistinct() = runTest {
        val image = imageFile()
        val timeoutRepository = KtorPassportRepository(
            "https://api.example.test",
            mockClient { throw HttpRequestTimeoutException(HttpRequestBuilder()) },
        )
        val timeoutFailure = runCatching {
            timeoutRepository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
        }.exceptionOrNull()
        assertTrue(timeoutFailure is PassportRepositoryException)
        assertTrue(timeoutFailure?.message.orEmpty().contains("слишком много времени"))
        timeoutRepository.close()

        val cancellation = CancellationException("test cancellation")
        val cancellationRepository = KtorPassportRepository("https://api.example.test", mockClient { throw cancellation })
        val cancellationFailure = runCatching {
            cancellationRepository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
        }.exceptionOrNull()
        assertTrue(cancellationFailure is CancellationException)
        cancellationRepository.close()
        image.delete()
    }

    private fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine { request -> handler(request) }) {
            followRedirects = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false }) }
        }

    private fun connection(token: String) = ApiConnection("https://api.example.test", token, "test-installation")

    private fun imageFile(): File = File.createTempFile("passport-scan-test", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun successResponse(): String = """
        {
          "request_id":"123e4567-e89b-12d3-a456-426614174000",
          "status":"completed","review_required":true,"pipeline_version":"test",
          "fields":{
            "surname":${field("ИВАНОВ")},"given_name":${field("ИВАН")},
            "patronymic":${field(null, "missing")},
            "gender":${field("M", "uncertain", "[{\"code\":\"manual_review_required\",\"message\":\"Проверьте пол.\"}]")},
            "birth_date":${field("1990-04-12")},"birth_place":${field("МОСКВА")},
            "series":${field("0012")},"number":${field("000345")},
            "issue_date":${field("2010-06-18")},"issuer":${field("ОТДЕЛ")},
            "department_code":${field("000-001")}
          },
          "warnings":[]
        }
    """.trimIndent()

    private fun field(value: String?, state: String = "recognized", issues: String = "[]"): String =
        """{"value":${value?.let { Json.encodeToString(kotlinx.serialization.serializer<String>(), it) } ?: "null"},"state":"$state","issues":$issues}"""
}
