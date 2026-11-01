package ru.passport.scanner.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ClientInstallationRegistrarTest {
    @Test
    fun sendsOnlyHashAndReturnsValidatedInstallation() = runTest {
        lateinit var captured: HttpRequestData
        val token = testToken()
        val registrar = ClientInstallationRegistrar(mockClient { request ->
            captured = request
            respond(
                """{"client_id":"123e4567-e89b-42d3-a456-426614174000","created_at":"2026-10-31T10:00:00Z"}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val connection = ApiConnection("https://api.example.test", token, "install-1")

        val result = registrar.register(connection)

        assertEquals("/v1/client-installations", captured.url.encodedPath)
        assertNull(captured.headers[HttpHeaders.Authorization])
		val outgoing = captured.body as OutgoingContent
		assertEquals("application/json", outgoing.contentType.toString())
		val wire = outgoing.toByteArray().decodeToString()
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue(wire.contains(expectedHash))
        assertFalse(wire.contains(token))
        assertEquals("123e4567-e89b-42d3-a456-426614174000", result.clientId)
        assertEquals("2026-10-31T10:00:00Z", result.createdAt.toString())
        assertFalse(result.toString().contains(expectedHash))
        registrar.close()
    }

    @Test
    fun acceptsExactReplayAndRejectsExtraResponseFields() = runTest {
        val replayRegistrar = ClientInstallationRegistrar(mockClient {
            respond(
                """{"client_id":"123e4567-e89b-42d3-a456-426614174000","created_at":"2026-10-31T10:00:00Z"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        assertEquals("123e4567-e89b-42d3-a456-426614174000", replayRegistrar.register(connection()).clientId)
        replayRegistrar.close()

        val malformed = ClientInstallationRegistrar(mockClient {
            respond(
                """{"client_id":"123e4567-e89b-42d3-a456-426614174000","created_at":"2026-10-31T10:00:00Z","token_sha256":"secret"}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val error = runCatching { malformed.register(connection()) }.exceptionOrNull()
        assertTrue(error is ClientInstallationRegistrationException)
        assertEquals(ClientRegistrationFailureCategory.INVALID_RESPONSE, (error as ClientInstallationRegistrationException).category)
        assertFalse(error.toString().contains("secret"))
        malformed.close()
    }

    @Test
    fun mapsDenialAndRateLimitWithoutReadingServerDetails() = runTest {
        for ((status, category) in listOf(
            HttpStatusCode.Unauthorized to ClientRegistrationFailureCategory.ACCESS_DENIED,
            HttpStatusCode.Forbidden to ClientRegistrationFailureCategory.ACCESS_DENIED,
            HttpStatusCode.TooManyRequests to ClientRegistrationFailureCategory.RATE_LIMITED,
            HttpStatusCode.ServiceUnavailable to ClientRegistrationFailureCategory.UNAVAILABLE,
        )) {
            val registrar = ClientInstallationRegistrar(mockClient {
                respond("secret body", status, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
            })
            val error = runCatching { registrar.register(connection()) }.exceptionOrNull()
            assertTrue(error is ClientInstallationRegistrationException)
            assertEquals(category, (error as ClientInstallationRegistrationException).category)
            assertFalse(error.toString().contains("secret"))
            assertFalse(error.message.orEmpty().contains("secret"))
            registrar.close()
        }
    }

    @Test
    fun propagatesCancellation() = runTest {
        val registrar = ClientInstallationRegistrar(mockClient {
            kotlinx.coroutines.awaitCancellation()
        })
        val cancellation = runCatching { withTimeout(0) { registrar.register(connection()) } }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        registrar.close()
    }

    private fun connection() = ApiConnection("https://api.example.test", testToken(), "install-test")

    private fun testToken() = "pbt1_" + "A".repeat(43)

    private fun mockClient(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine { request -> handler(request) }) {
            followRedirects = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = false }) }
        }
}
