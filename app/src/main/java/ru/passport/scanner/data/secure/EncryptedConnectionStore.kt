package ru.passport.scanner.data.secure

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.passport.scanner.data.ApiConnection
import ru.passport.scanner.data.ConnectionStore
import java.io.ByteArrayInputStream
import java.io.DataInputStream

/** Encrypted operator connection saved only under Context.noBackupFilesDir. */
class EncryptedConnectionStore internal constructor(
    private val file: EncryptedRecordFile,
    private val keyProvider: AesKeyProvider,
    private val allowDebugLocalHttp: Boolean = false,
) : ConnectionStore {
    constructor(context: Context, allowDebugLocalHttp: Boolean = false) : this(
        privateRecordFile(context, FILE_NAME),
        AndroidKeyStoreAesKeyProvider(),
        allowDebugLocalHttp,
    )

    private val lock = SecureRecordLocks.forKey(file.lockKey)
    private val cipher = AesGcmRecordCipher(KEY_ALIAS, PURPOSE, keyProvider)

    override suspend fun load(): ApiConnection? = withContext(Dispatchers.IO) {
        lock.withLock { readConnectionLocked() }
    }

    override suspend fun save(connection: ApiConnection) = withContext(Dispatchers.IO) {
        lock.withLock {
            // An existing encrypted credential must still be readable before it can be replaced.
            // This prevents an invalidated key from silently destroying recoverable state.
            validateExistingRecordLocked()
            val token = connection.bearerTokenForPersistence()
            val validatedConnection = try {
                ApiConnection(connection.baseUrl, token, connection.connectionId, allowDebugLocalHttp)
            } catch (_: Exception) {
                throw SecureStorageUnavailableException()
            }
            val plaintext = securePlaintext { output ->
                output.writeInt(CONNECTION_VERSION)
                output.writeUTF(validatedConnection.baseUrl)
                output.writeUTF(token)
                output.writeUTF(validatedConnection.connectionId)
            }
            persistLocked(plaintext)
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        lock.withLock {
            file.delete()
            keyProvider.deleteKey(KEY_ALIAS)
        }
    }

    private fun readConnectionLocked(): ApiConnection? {
        val envelope = file.read(MAX_ENVELOPE_BYTES) ?: return null
        val plaintext = try {
            cipher.decrypt(envelope, MAX_PLAINTEXT_BYTES)
        } finally {
            envelope.fill(0)
        }
        return try {
            DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                if (input.readInt() != CONNECTION_VERSION) throw SecureStorageNeedsResetException()
                val baseUrl = input.readUTF()
                val bearerToken = input.readUTF()
                val connectionId = input.readUTF()
                if (input.read() != -1) throw SecureStorageNeedsResetException()
                try {
                    ApiConnection(baseUrl, bearerToken, connectionId, allowDebugLocalHttp)
                } catch (_: Exception) {
                    throw SecureStorageNeedsResetException()
                }
            }
        } catch (_: SecureStorageNeedsResetException) {
            throw SecureStorageNeedsResetException()
        } catch (_: Exception) {
            throw SecureStorageNeedsResetException()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun validateExistingRecordLocked() {
        val envelope = file.read(MAX_ENVELOPE_BYTES) ?: return
        val plaintext = try {
            cipher.decrypt(envelope, MAX_PLAINTEXT_BYTES)
        } finally {
            envelope.fill(0)
        }
        try {
            DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                if (input.readInt() != CONNECTION_VERSION) throw SecureStorageNeedsResetException()
                repeat(3) { skipUtfField(input) }
                if (input.read() != -1) throw SecureStorageNeedsResetException()
            }
        } catch (_: SecureStorageNeedsResetException) {
            throw SecureStorageNeedsResetException()
        } catch (_: Exception) {
            throw SecureStorageNeedsResetException()
        } finally {
            plaintext.fill(0)
        }
    }

    private fun skipUtfField(input: DataInputStream) {
        val length = input.readUnsignedShort()
        if (length > input.available()) throw SecureStorageNeedsResetException()
        val buffer = ByteArray(minOf(length, 256))
        try {
            var remaining = length
            while (remaining > 0) {
                val chunk = minOf(buffer.size, remaining)
                input.readFully(buffer, 0, chunk)
                remaining -= chunk
            }
        } finally {
            buffer.fill(0)
        }
    }

    private fun persistLocked(plaintext: ByteArray) {
        try {
            val envelope = cipher.encrypt(plaintext)
            try {
                file.write(envelope, MAX_ENVELOPE_BYTES)
            } finally {
                envelope.fill(0)
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private companion object {
        const val FILE_NAME = "api-connection-v1.enc"
        const val KEY_ALIAS = "ru.passport.scanner.api-connection.v1"
        const val PURPOSE = "api-connection"
        const val CONNECTION_VERSION = 1
        const val MAX_PLAINTEXT_BYTES = 16 * 1024
        const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 64
    }
}
