package ru.passport.scanner.data.secure

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.passport.scanner.data.ApiConnection
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class EncryptedSecureStoresTest {
    @Test
    fun encryptedEnvelopeUsesFreshNoncePurposeBindingAndAuthenticatedCiphertext() {
        val keys = TestKeyProvider()
        val first = AesGcmRecordCipher("test-key", "connection", keys)
        val plaintext = "opaque credential record".toByteArray(Charsets.UTF_8)

        val one = first.encrypt(plaintext)
        val two = first.encrypt(plaintext)

        assertFalse(one.contentEquals(two))
        assertFalse(one.toList().windowed("opaque credential record".length).any {
            it == "opaque credential record".toByteArray(Charsets.UTF_8).toList()
        })
        assertArrayEquals(plaintext, first.decrypt(one, 1024))
        assertTrue(runCatching { AesGcmRecordCipher("test-key", "pending", keys).decrypt(one, 1024) }.exceptionOrNull() is SecureStorageNeedsResetException)

        val damaged = one.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { first.decrypt(damaged, 1024) }.exceptionOrNull() is SecureStorageNeedsResetException)
    }

    @Test
    fun connectionStoreEncryptsPairPreservesIdAndFailsClosedWhenKeyIsMissing() = runTest {
        val file = MemoryRecordFile("connection-test")
        val keys = TestKeyProvider()
        val store = EncryptedConnectionStore(file, keys)
        val token = testToken()
        val connection = ApiConnection("https://api.example.test", token, CONNECTION_ID)

        store.save(connection)

        val encrypted = checkNotNull(file.contents)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(token))
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("api.example.test"))
        val restored = checkNotNull(store.load())
        assertEquals(connection.baseUrl, restored.baseUrl)
        assertEquals(connection.connectionId, restored.connectionId)
        assertEquals("Bearer $token", restored.authorizationHeaderValue())
        assertFalse(restored.toString().contains(token))

        keys.removeKey()
        val locked = runCatching { store.load() }.exceptionOrNull()
        assertTrue(locked is SecureStorageNeedsResetException)
        assertTrue("existing encrypted state must remain present", file.contents != null)
        assertTrue(runCatching {
            store.save(ApiConnection("https://replacement.example.test", token, CONNECTION_ID))
        }.exceptionOrNull() is SecureStorageNeedsResetException)
        assertArrayEquals(encrypted, file.contents)
        store.clear()
        assertNull(store.load())
        store.save(ApiConnection("https://api.example.test", token, CONNECTION_ID))
        assertEquals(CONNECTION_ID, checkNotNull(store.load()).connectionId)
    }

    @Test
    fun debugHttpCredentialCannotBeLoadedUnderReleasePolicy() = runTest {
        val file = MemoryRecordFile("connection-policy-test")
        val keys = TestKeyProvider()
        EncryptedConnectionStore(file, keys, allowDebugLocalHttp = true).save(
            ApiConnection("http://10.0.2.2:8080", testToken(), CONNECTION_ID, allowDebugLocalHttp = true),
        )

        val error = runCatching { EncryptedConnectionStore(file, keys, allowDebugLocalHttp = false).load() }.exceptionOrNull()
        assertTrue(error is SecureStorageNeedsResetException)
        assertTrue(file.contents != null)
    }

    @Test
    fun releaseConnectionStoreRejectsDebugHttpOnSave() = runTest {
        val file = MemoryRecordFile("connection-save-policy-test")
        val store = EncryptedConnectionStore(file, TestKeyProvider(), allowDebugLocalHttp = false)
        val debugConnection = ApiConnection("http://10.0.2.2:8080", testToken(), CONNECTION_ID, allowDebugLocalHttp = true)

        assertTrue(runCatching { store.save(debugConnection) }.exceptionOrNull() is SecureStorageUnavailableException)
        assertNull(file.contents)
    }

    private class MemoryRecordFile(override val lockKey: String) : EncryptedRecordFile {
        var contents: ByteArray? = null

        override fun read(maxBytes: Int): ByteArray? = contents?.also {
            if (it.size > maxBytes) throw SecureStorageNeedsResetException()
        }?.copyOf()

        override fun write(bytes: ByteArray, maxBytes: Int) {
            if (bytes.size > maxBytes) throw SecureStorageUnavailableException()
            contents = bytes.copyOf()
        }

        override fun delete() {
            contents?.fill(0)
            contents = null
        }
    }

    private class TestKeyProvider : AesKeyProvider {
        private var key: SecretKey? = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")

        override fun existingKey(alias: String): SecretKey? = key

        override fun createKey(alias: String): SecretKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES").also { key = it }

        override fun deleteKey(alias: String) {
            key = null
        }

        fun removeKey() {
            key = null
        }
    }

    private fun testToken(): String = "pbt1_" + "A".repeat(43)

    private companion object {
        const val CONNECTION_ID = "7e4f6ad1-8a0f-4cc4-8c33-483477f9ae83"
        const val OPERATION_ID = "9cfe2e2f-135d-42f4-8db5-ddabc5a2f831"
        const val JOB_ID = "b305da58-4913-4805-9094-e80c1dc27d36"
    }
}
