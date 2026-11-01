package ru.passport.scanner.data.secure

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

internal class ZeroingByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    fun takeAndClear(): ByteArray = toByteArray().also {
        buf.fill(0)
        reset()
    }

    fun clear() {
        buf.fill(0)
        reset()
    }

    override fun toString(): String = "ZeroingByteArrayOutputStream([redacted])"
}

internal inline fun securePlaintext(block: (DataOutputStream) -> Unit): ByteArray {
    val buffer = ZeroingByteArrayOutputStream(512)
    try {
        DataOutputStream(buffer).use(block)
        return buffer.takeAndClear()
    } catch (_: Exception) {
        buffer.clear()
        throw SecureStorageUnavailableException()
    }
}
