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
