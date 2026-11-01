package ru.passport.scanner.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.passport.scanner.data.secure.SecureStorageNeedsResetException
import ru.passport.scanner.data.secure.SecureStorageUnavailableException
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Public connection state. It never contains an API credential or server response body. */
sealed interface ConnectionUiState {
    data object NeedsServer : ConnectionUiState

    data class Connecting(val serverUrl: String) : ConnectionUiState

    data class Ready(val serverUrl: String, val connectionId: String) : ConnectionUiState

    data class Unavailable(
        val serverUrl: String,
        val reason: ConnectionUnavailableReason,
        val retryable: Boolean,
    ) : ConnectionUiState

    data object StorageUnavailable : ConnectionUiState
}

enum class ConnectionUnavailableReason {
    SERVER_UNAVAILABLE,
    ACCESS_DENIED,
    INVALID_SERVER,
}

/**
 * Creates a per-install credential once, persists it before registration, and reuses it after
 * process death or an ambiguous registration response. Call [connect] again to retry registration.
 */
class AutomaticConnectionManager internal constructor(
    private val connectionStore: ConnectionStore,
    private val registerClient: suspend (ApiConnection) -> ClientInstallation,
    private val allowDebugLocalHttp: Boolean,
    private val randomBytes: (ByteArray) -> Unit,
    private val newConnectionId: () -> String,
) {
    constructor(
        connectionStore: ConnectionStore,
        registrar: ClientInstallationRegistrar,
        allowDebugLocalHttp: Boolean = false,
    ) : this(
        connectionStore = connectionStore,
        registerClient = { connection -> registrar.register(connection) },
        allowDebugLocalHttp = allowDebugLocalHttp,
        randomBytes = { SecureRandom().nextBytes(it) },
        newConnectionId = { UUID.randomUUID().toString() },
    )

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<ConnectionUiState>(ConnectionUiState.NeedsServer)
    @Volatile
    private var readyConnection: ApiConnection? = null

    val state: StateFlow<ConnectionUiState> = mutableState.asStateFlow()

    /** A credential is available to request code only after durable registration succeeds. */
    fun currentConnection(): ApiConnection? = readyConnection

    /** Fences stale request failures without rotating, deleting, or re-enrolling a credential. */
    suspend fun markAccessDenied(connectionId: String) = mutex.withLock {
        val connection = readyConnection ?: return@withLock
        if (connection.connectionId != connectionId) return@withLock
        readyConnection = null
        mutableState.value = ConnectionUiState.Unavailable(
            serverUrl = connection.baseUrl,
            reason = ConnectionUnavailableReason.ACCESS_DENIED,
            retryable = false,
        )
    }

    suspend fun connect(serverUrl: String): ApiConnection? = mutex.withLock {
        val normalizedUrl = try {
            normalizeServerUrl(serverUrl)
        } catch (_: IllegalArgumentException) {
            readyConnection = null
            mutableState.value = ConnectionUiState.NeedsServer
            return@withLock null
        }

        readyConnection?.let { current ->
            if (current.baseUrl == normalizedUrl && mutableState.value is ConnectionUiState.Ready) {
                return@withLock current
            }
        }

        val existing = try {
            connectionStore.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readyConnection = null
            mutableState.value = ConnectionUiState.StorageUnavailable
            return@withLock null
        }

        if (existing != null && existing.baseUrl != normalizedUrl) {
            readyConnection = null
            mutableState.value = ConnectionUiState.NeedsServer
            return@withLock null
        }

        val connection = existing ?: try {
            newConnection(normalizedUrl).also { connectionStore.save(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecureStorageNeedsResetException) {
            readyConnection = null
            mutableState.value = ConnectionUiState.StorageUnavailable
            return@withLock null
        } catch (_: SecureStorageUnavailableException) {
            readyConnection = null
            mutableState.value = ConnectionUiState.StorageUnavailable
            return@withLock null
        } catch (_: Exception) {
            readyConnection = null
            mutableState.value = ConnectionUiState.StorageUnavailable
            return@withLock null
        }

        registerAndPublish(connection)
    }

    /**
     * Replaces the saved server only after the caller's explicit confirmation flow has fenced
     * outstanding work and cleared the pending operation. If that callback fails, credentials stay
     * untouched.
     */
    suspend fun changeServer(
        serverUrl: String,
        fenceAndClearPendingOperation: suspend () -> Boolean,
    ): ApiConnection? = mutex.withLock {
        val normalizedUrl = try {
            normalizeServerUrl(serverUrl)
        } catch (_: IllegalArgumentException) {
            readyConnection = null
            mutableState.value = ConnectionUiState.NeedsServer
            return@withLock null
        }

        val previousConnection = readyConnection
        val previousState = mutableState.value
        readyConnection = null
        mutableState.value = ConnectionUiState.Connecting(normalizedUrl)
        try {
            if (!fenceAndClearPendingOperation()) {
                readyConnection = previousConnection
                mutableState.value = previousState
                return@withLock null
            }
        } catch (cancelled: CancellationException) {
            readyConnection = previousConnection
            mutableState.value = previousState
            throw cancelled
        } catch (failure: Exception) {
            readyConnection = previousConnection
            mutableState.value = previousState
            throw failure
        }

        try {
            connectionStore.clear()
            val connection = newConnection(normalizedUrl)
            connectionStore.save(connection)
            registerAndPublish(connection)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: SecureStorageNeedsResetException) {
            readyConnection = null
            mutableState.value = ConnectionUiState.StorageUnavailable
            null
        } catch (_: SecureStorageUnavailableException) {
            readyConnection = null
            mutableState.value = ConnectionUiState.StorageUnavailable
            null
        } catch (_: Exception) {
            readyConnection = null
            mutableState.value = ConnectionUiState.StorageUnavailable
            null
        }
    }

    private suspend fun registerAndPublish(connection: ApiConnection): ApiConnection? {
        mutableState.value = ConnectionUiState.Connecting(connection.baseUrl)
        return try {
            registerClient(connection)
            readyConnection = connection
            mutableState.value = ConnectionUiState.Ready(connection.baseUrl, connection.connectionId)
            connection
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ClientInstallationRegistrationException) {
            readyConnection = null
            val denied = error.category == ClientRegistrationFailureCategory.ACCESS_DENIED
            mutableState.value = ConnectionUiState.Unavailable(
                serverUrl = connection.baseUrl,
                reason = if (denied) ConnectionUnavailableReason.ACCESS_DENIED else ConnectionUnavailableReason.SERVER_UNAVAILABLE,
                retryable = !denied,
            )
            null
        } catch (_: Exception) {
            readyConnection = null
            mutableState.value = ConnectionUiState.Unavailable(
                serverUrl = connection.baseUrl,
                reason = ConnectionUnavailableReason.SERVER_UNAVAILABLE,
                retryable = true,
            )
            null
        }
    }

    private fun newConnection(serverUrl: String): ApiConnection {
        val secret = ByteArray(32)
        return try {
            randomBytes(secret)
            val token = "pbt1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret)
            ApiConnection(serverUrl, token, newConnectionId(), allowDebugLocalHttp)
        } finally {
            secret.fill(0)
        }
    }

    private fun normalizeServerUrl(value: String): String {
        require(value.isNotBlank()) { "A server address is required." }
        return ApiConnection(value, "pbt1_" + "A".repeat(43), "validation", allowDebugLocalHttp).baseUrl
    }
}
