package ru.passport.scanner.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.time.Instant

class AutomaticConnectionManagerTest {
    @Test
    fun persistsGeneratedCredentialBeforeRegistrationAndRetriesSameIdentity() = runTest {
        val store = MemoryConnectionStore()
        val registrations = mutableListOf<String>()
        var failFirstRegistration = true
        val registrar: suspend (ApiConnection) -> ClientInstallation = { connection ->
            assertNotNull("Credential must be durable before registration starts.", store.value)
            assertEquals(store.value!!.connectionId, connection.connectionId)
            registrations += tokenFingerprint(connection)
            if (failFirstRegistration) {
                failFirstRegistration = false
                throw IllegalStateException("synthetic network failure")
            }
            ClientInstallation("123e4567-e89b-42d3-a456-426614174001", Instant.parse("2026-10-31T00:00:00Z"))
        }
        var randomCounter = 0
        var idCounter = 0
        val manager = AutomaticConnectionManager(
            connectionStore = store,
            registerClient = registrar,
            allowDebugLocalHttp = false,
            randomBytes = { bytes ->
                randomCounter += 1
                bytes.fill(randomCounter.toByte())
            },
            newConnectionId = { "installation-${++idCounter}" },
        )

        assertNull(manager.connect("https://api.example.test"))
        assertTrue(manager.state.value is ConnectionUiState.Unavailable)
        assertNull(manager.currentConnection())
        assertNotNull(store.value)

        val ready = manager.connect("https://api.example.test")
        assertNotNull(ready)
        assertTrue(manager.state.value is ConnectionUiState.Ready)
        assertEquals(1, randomCounter)
        assertEquals(1, idCounter)
        assertEquals(2, registrations.size)
        assertEquals(registrations.first(), registrations.last())
        assertFalse(manager.state.value.toString().contains(ready!!.bearerTokenForPersistence()))
    }

    @Test
    fun processRestartLoadsAndReregistersTheSameSavedCredential() = runTest {
        val store = MemoryConnectionStore()
        val firstRegistrar = SuccessfulRegistrar()
        val first = manager(store, firstRegistrar).connect("https://api.example.test")!!
        val fingerprint = tokenFingerprint(first)

        val restartedRegistrar = SuccessfulRegistrar()
        val restarted = manager(store, restartedRegistrar).connect("https://api.example.test")!!

        assertEquals(first.connectionId, restarted.connectionId)
        assertEquals(fingerprint, tokenFingerprint(restarted))
        assertEquals(1, restartedRegistrar.connections.size)
    }

    @Test
    fun serverMismatchDoesNotSilentlyMintReplacementAndSwitchRequiresFence() = runTest {
        val store = MemoryConnectionStore()
        val registrar = SuccessfulRegistrar()
        val manager = manager(store, registrar)
        val first = manager.connect("https://one.example.test")!!

        assertNull(manager.connect("https://two.example.test"))
        assertTrue(manager.state.value is ConnectionUiState.NeedsServer)
        assertEquals(first.connectionId, store.value!!.connectionId)
        assertEquals(1, registrar.connections.size)

        val order = mutableListOf<String>()
        val second = manager.changeServer("https://two.example.test") {
            order += "fence-and-clear-pending"
            assertEquals(first.connectionId, store.value!!.connectionId)
            assertNull(manager.currentConnection())
            true
        }

        assertNotNull(second)
        assertEquals(listOf("fence-and-clear-pending"), order)
        assertEquals("https://two.example.test", second!!.baseUrl)
        assertTrue(second.connectionId != first.connectionId)
        assertEquals(second.connectionId, store.value!!.connectionId)
        assertEquals(2, registrar.connections.size)
    }

    @Test
    fun failedFenceLeavesSavedConnectionUntouched() = runTest {
        val store = MemoryConnectionStore()
        val registrar = SuccessfulRegistrar()
        val manager = manager(store, registrar)
        val first = manager.connect("https://one.example.test")!!

        runCatching {
            manager.changeServer("https://two.example.test") {
                throw IllegalStateException("pending operation could not be cleared")
            }
        }

        assertEquals(first.connectionId, store.value!!.connectionId)
        assertEquals(first.connectionId, manager.currentConnection()!!.connectionId)
        assertEquals(1, registrar.connections.size)
    }

    @Test
    fun rejectedPendingClearAbortsEndpointReplacement() = runTest {
        val store = MemoryConnectionStore()
        val registrar = SuccessfulRegistrar()
        val manager = manager(store, registrar)
        val first = manager.connect("https://one.example.test")!!

        val changed = manager.changeServer("https://two.example.test") {
            assertNull(manager.currentConnection())
            false
        }

        assertNull(changed)
        assertEquals(first.connectionId, store.value!!.connectionId)
        assertEquals(first.connectionId, manager.currentConnection()!!.connectionId)
        assertEquals(1, registrar.connections.size)
    }

    @Test
    fun stateAndManagerStringNeverIncludeCredential() = runTest {
        val store = MemoryConnectionStore()
        val manager = manager(store, SuccessfulRegistrar())
        val connection = manager.connect("https://api.example.test")!!
        val rawToken = connection.bearerTokenForPersistence()

        assertFalse(manager.toString().contains(rawToken))
        assertFalse(manager.state.value.toString().contains(rawToken))
        assertFalse(manager.state.value.toString().contains("Bearer"))
    }

    @Test
    fun accessDeniedFencesOnlyMatchingConnectionWithoutRotatingSavedCredential() = runTest {
        val store = MemoryConnectionStore()
        val manager = manager(store, SuccessfulRegistrar())
        val connection = manager.connect("https://api.example.test")!!
        val fingerprint = tokenFingerprint(connection)

        manager.markAccessDenied("stale-connection-id")
        assertEquals(connection.connectionId, manager.currentConnection()!!.connectionId)

        manager.markAccessDenied(connection.connectionId)

        assertNull(manager.currentConnection())
        assertEquals(
            ConnectionUnavailableReason.ACCESS_DENIED,
            (manager.state.value as ConnectionUiState.Unavailable).reason,
        )
        assertFalse((manager.state.value as ConnectionUiState.Unavailable).retryable)
        assertEquals(fingerprint, tokenFingerprint(store.value!!))
    }

    private fun manager(store: MemoryConnectionStore, registrar: SuccessfulRegistrar) =
        AutomaticConnectionManager(
            connectionStore = store,
            registerClient = { connection -> registrar.register(connection) },
            allowDebugLocalHttp = false,
            randomBytes = { bytes -> bytes.fill(0x2a) },
            newConnectionId = { "connection-${store.saveCount}" },
        )

    private fun tokenFingerprint(connection: ApiConnection): String = MessageDigest.getInstance("SHA-256")
        .digest(connection.bearerTokenForPersistence().toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private class MemoryConnectionStore : ConnectionStore {
        var value: ApiConnection? = null
        var saveCount = 0

        override suspend fun load(): ApiConnection? = value

        override suspend fun save(connection: ApiConnection) {
            value = connection
            saveCount += 1
        }

        override suspend fun clear() {
            value = null
        }
    }

    private class SuccessfulRegistrar {
        val connections = mutableListOf<ApiConnection>()

        suspend fun register(connection: ApiConnection): ClientInstallation {
            connections += connection
            return ClientInstallation("123e4567-e89b-42d3-a456-426614174001", Instant.parse("2026-10-31T00:00:00Z"))
        }
    }
}
