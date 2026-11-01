package ru.passport.scanner.data.secure

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

internal interface AesKeyProvider {
    fun existingKey(alias: String): SecretKey?
    fun createKey(alias: String): SecretKey
    fun deleteKey(alias: String)
}

internal class AndroidKeyStoreAesKeyProvider : AesKeyProvider {
    override fun existingKey(alias: String): SecretKey? {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (!keyStore.containsAlias(alias)) return null
            return keyStore.getKey(alias, null) as? SecretKey ?: throw SecureStorageNeedsResetException()
        } catch (_: SecureStorageNeedsResetException) {
            throw SecureStorageNeedsResetException()
        } catch (_: Exception) {
            throw SecureStorageNeedsResetException()
        }
    }

    override fun createKey(alias: String): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(alias)) {
                return keyStore.getKey(alias, null) as? SecretKey ?: throw SecureStorageNeedsResetException()
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        } catch (_: SecureStorageNeedsResetException) {
            throw SecureStorageNeedsResetException()
        } catch (_: Exception) {
            throw SecureStorageUnavailableException()
        }
    }

    override fun deleteKey(alias: String) {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        } catch (_: Exception) {
            throw SecureStorageUnavailableException()
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    }
}

/** Purpose-bound AES-GCM envelope; the file contains only version, nonce, length and ciphertext. */
internal class AesGcmRecordCipher(
    private val alias: String,
    purpose: String,
    private val keyProvider: AesKeyProvider,
) {
    private val associatedData = "ru.passport.scanner/secure-storage/$purpose/v1".toByteArray(Charsets.UTF_8)

    fun encrypt(plaintext: ByteArray): ByteArray {
        try {
            val key = keyProvider.existingKey(alias) ?: keyProvider.createKey(alias)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(associatedData)
            val nonce = cipher.iv
            if (nonce == null || nonce.size != NONCE_BYTES) throw SecureStorageUnavailableException()
            val ciphertext = cipher.doFinal(plaintext)
            return ByteArrayOutputStream(HEADER_BYTES + ciphertext.size).use { buffer ->
                DataOutputStream(buffer).use { output ->
                    output.write(MAGIC)
                    output.writeByte(FORMAT_VERSION)
                    output.writeByte(nonce.size)
                    output.write(nonce)
                    output.writeInt(ciphertext.size)
                    output.write(ciphertext)
                    output.flush()
                    buffer.toByteArray()
                }
            }
        } catch (_: SecureStorageUnavailableException) {
            throw SecureStorageUnavailableException()
        } catch (_: SecureStorageNeedsResetException) {
            throw SecureStorageNeedsResetException()
        } catch (_: Exception) {
            throw SecureStorageUnavailableException()
        }
    }

    fun decrypt(envelope: ByteArray, maxPlaintextBytes: Int): ByteArray {
        try {
            if (envelope.size < MIN_ENVELOPE_BYTES || envelope.size > maxPlaintextBytes + HEADER_BYTES + TAG_BYTES) {
                throw SecureStorageNeedsResetException()
            }
            DataInputStream(ByteArrayInputStream(envelope)).use { input ->
                val magic = ByteArray(MAGIC.size)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC) || input.readUnsignedByte() != FORMAT_VERSION) {
                    throw SecureStorageNeedsResetException()
                }
                val nonceLength = input.readUnsignedByte()
                if (nonceLength != NONCE_BYTES) throw SecureStorageNeedsResetException()
                val nonce = ByteArray(nonceLength)
                input.readFully(nonce)
                val ciphertextLength = input.readInt()
                val remaining = envelope.size - HEADER_BYTES
                if (ciphertextLength != remaining || ciphertextLength < TAG_BYTES || ciphertextLength > maxPlaintextBytes + TAG_BYTES) {
                    throw SecureStorageNeedsResetException()
                }
                val ciphertext = ByteArray(ciphertextLength)
                input.readFully(ciphertext)
                if (input.read() != -1) throw SecureStorageNeedsResetException()
                val key = keyProvider.existingKey(alias) ?: throw SecureStorageNeedsResetException()
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(associatedData)
                return try {
                    cipher.doFinal(ciphertext).also { plaintext ->
                        if (plaintext.size > maxPlaintextBytes) {
                            plaintext.fill(0)
                            throw SecureStorageNeedsResetException()
                        }
                    }
                } finally {
                    nonce.fill(0)
                    ciphertext.fill(0)
                }
            }
        } catch (_: SecureStorageNeedsResetException) {
            throw SecureStorageNeedsResetException()
        } catch (_: Exception) {
            throw SecureStorageNeedsResetException()
        }
    }

    private companion object {
        val MAGIC = byteArrayOf(0x50, 0x53, 0x53, 0x45, 0x43) // PSSEC
        const val FORMAT_VERSION = 1
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        const val HEADER_BYTES = 5 + 1 + 1 + NONCE_BYTES + 4
        const val MIN_ENVELOPE_BYTES = HEADER_BYTES + TAG_BYTES
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
