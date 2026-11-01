package ru.passport.scanner.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class ClientInstallation(val clientId: String, val createdAt: Instant) {
    override fun toString(): String = "ClientInstallation([redacted])"
}

enum class ClientRegistrationFailureCategory {
    TRANSPORT,
    TIMEOUT,
    INVALID_REQUEST,
    ACCESS_DENIED,
    RATE_LIMITED,
    UNAVAILABLE,
    INVALID_RESPONSE,
}

class ClientInstallationRegistrationException(
    val category: ClientRegistrationFailureCategory,
    val httpStatus: Int? = null,
) : Exception(safeMessage(category)) {
    override fun toString(): String = "ClientInstallationRegistrationException(category=$category)"

    companion object {
        private fun safeMessage(category: ClientRegistrationFailureCategory): String = when (category) {
            ClientRegistrationFailureCategory.TRANSPORT -> "Не удалось зарегистрировать подключение. Проверьте сеть и повторите попытку."
            ClientRegistrationFailureCategory.TIMEOUT -> "Регистрация подключения заняла слишком много времени. Повторите попытку."
            ClientRegistrationFailureCategory.INVALID_REQUEST -> "Сервер отклонил регистрацию подключения."
            ClientRegistrationFailureCategory.ACCESS_DENIED -> "Регистрация подключения запрещена для этой сети."
            ClientRegistrationFailureCategory.RATE_LIMITED -> "Сервер временно ограничил регистрацию подключений. Повторите позже."
            ClientRegistrationFailureCategory.UNAVAILABLE -> "Регистрация подключения временно недоступна. Повторите попытку."
            ClientRegistrationFailureCategory.INVALID_RESPONSE -> "Сервер вернул некорректный ответ регистрации."
        }
    }
}

class ClientInstallationRegistrar(
    private val client: HttpClient = defaultClient(),
) {
    fun close() = client.close()

    suspend fun register(connection: ApiConnection): ClientInstallation {
        val requestId = UUID.randomUUID().toString()
        val tokenHash = sha256Hex(connection.bearerTokenForPersistence())
        val response = try {
            withContext(Dispatchers.IO) {
                client.post("${connection.baseUrl}/v1/client-installations") {
                    timeout { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
                    header(HttpHeaders.ContentType, "application/json")
                    header("X-Request-ID", requestId)
                    setBody(RegistrationRequest(tokenHash))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            throw ClientInstallationRegistrationException(ClientRegistrationFailureCategory.TIMEOUT)
        } catch (_: SocketTimeoutException) {
            throw ClientInstallationRegistrationException(ClientRegistrationFailureCategory.TIMEOUT)
        } catch (_: Exception) {
            throw ClientInstallationRegistrationException(ClientRegistrationFailureCategory.TRANSPORT)
        }

        if (response.status != HttpStatusCode.Created && response.status != HttpStatusCode.OK) {
            val category = when (response.status.value) {
                400 -> ClientRegistrationFailureCategory.INVALID_REQUEST
                401, 403 -> ClientRegistrationFailureCategory.ACCESS_DENIED
                429 -> ClientRegistrationFailureCategory.RATE_LIMITED
                503 -> ClientRegistrationFailureCategory.UNAVAILABLE
                else -> ClientRegistrationFailureCategory.INVALID_RESPONSE
            }
            throw ClientInstallationRegistrationException(category, response.status.value)
        }
        return try {
            val text = response.bodyAsText()
            val root = REGISTRATION_JSON.parseToJsonElement(text).jsonObject
            if (root.keys != setOf("client_id", "created_at")) throw invalidResponse(response.status.value)
            val dto = REGISTRATION_JSON.decodeFromString<RegistrationResponse>(text)
            if (!CLIENT_ID_PATTERN.matches(dto.clientId)) throw invalidResponse(response.status.value)
            val createdAt = runCatching { Instant.parse(dto.createdAt) }.getOrNull() ?: throw invalidResponse(response.status.value)
            ClientInstallation(dto.clientId, createdAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ClientInstallationRegistrationException) {
            throw failure
        } catch (_: Exception) {
            throw invalidResponse(response.status.value)
        }
    }

    private fun invalidResponse(status: Int) = ClientInstallationRegistrationException(
        ClientRegistrationFailureCategory.INVALID_RESPONSE,
        status,
    )

    private fun sha256Hex(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    @Serializable
    private data class RegistrationRequest(@SerialName("token_sha256") val tokenSHA256: String) {
        override fun toString(): String = "RegistrationRequest([redacted])"
    }

    @Serializable
    private data class RegistrationResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("created_at") val createdAt: String,
    ) {
        override fun toString(): String = "RegistrationResponse([redacted])"
    }

    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 30_000L
        private val CLIENT_ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        private val REGISTRATION_JSON = Json { ignoreUnknownKeys = false }

        private fun defaultClient(): HttpClient = HttpClient(OkHttp) {
            engine { config { retryOnConnectionFailure(false) } }
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                connectTimeoutMillis = 15_000
            }
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(REGISTRATION_JSON)
            }
        }
    }
}
