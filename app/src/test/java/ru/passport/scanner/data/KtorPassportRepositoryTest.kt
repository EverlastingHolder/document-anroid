package ru.passport.scanner.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
        val client = mockClient { request ->
            seenUrl = request.url.toString()
            seenRequestId = request.headers["X-Request-ID"].orEmpty()
            seenBodyContentType = (request.body as? OutgoingContent)?.contentType?.toString().orEmpty()
            respond(successResponse(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val repository = KtorPassportRepository("https://api.example.test", client)

        val result = repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")

        assertEquals("https://api.example.test/v1/passport/recognize", seenUrl)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", seenRequestId)
        assertTrue(seenBodyContentType.startsWith("multipart/form-data"))
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
    fun refusesCleartextAndMissingBaseUrlBeforeMakingRequest() = runTest {
        val image = imageFile()
        val defaultHttp = KtorPassportRepository("http://127.0.0.1", mockClient { error("must not send") })
        val nonLocalHttp = KtorPassportRepository(
            "http://api.example.test",
            mockClient { error("must not send") },
            allowLocalHttp = true,
        )
        val empty = KtorPassportRepository("", mockClient { error("must not send") })

        val defaultHttpError = runCatching { defaultHttp.recognize(ScanImage("local-id", image, "image/jpeg"), "id") }.exceptionOrNull()
        val nonLocalError = runCatching { nonLocalHttp.recognize(ScanImage("local-id", image, "image/jpeg"), "id") }.exceptionOrNull()
        val emptyError = runCatching { empty.recognize(ScanImage("local-id", image, "image/jpeg"), "id") }.exceptionOrNull()

        assertTrue(defaultHttpError?.message.orEmpty().contains("HTTPS"))
        assertTrue(nonLocalError?.message.orEmpty().contains("HTTPS"))
        assertTrue(emptyError?.message.orEmpty().contains("не настроен"))
        defaultHttp.close()
        nonLocalHttp.close()
        empty.close()
        image.delete()
    }

    @Test
    fun allowsLocalHttpOnlyWhenExplicitlyEnabledAndUsesRequestEndpoint() = runTest {
        val image = imageFile()
        var seenUrl = ""
        val repository = KtorPassportRepository(
            "http://10.0.2.2:8080",
            mockClient { request ->
                seenUrl = request.url.toString()
                respond(successResponse(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            },
            allowLocalHttp = true,
        )

        repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")

        assertEquals("http://10.0.2.2:8080/v1/passport/recognize", seenUrl)
        repository.close()
        image.delete()
    }

    @Test
    fun rejectsLocalHttpUrlWithCredentialsQueryOrFragmentEvenWhenEnabled() = runTest {
        val image = imageFile()
        val malformedLocalUrls = listOf(
            "http://user@localhost:8080",
            "http://localhost:8080?token=value",
            "http://localhost:8080/#fragment",
        )
        malformedLocalUrls.forEach { url ->
            val repository = KtorPassportRepository(url, mockClient { error("must not send") }, allowLocalHttp = true)
            val error = runCatching {
                repository.recognize(ScanImage("local-id", image, "image/jpeg"), "123e4567-e89b-12d3-a456-426614174000")
            }.exceptionOrNull()
            assertTrue("Expected $url to be rejected", error?.message.orEmpty().contains("HTTPS"))
            repository.close()
        }
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
            "department_code":${field("123-456")}
          },
          "warnings":[]
        }
    """.trimIndent()

    private fun field(value: String?, state: String = "recognized", issues: String = "[]"): String =
        """{"value":${value?.let { Json.encodeToString(kotlinx.serialization.serializer<String>(), it) } ?: "null"},"state":"$state","issues":$issues}"""
}
