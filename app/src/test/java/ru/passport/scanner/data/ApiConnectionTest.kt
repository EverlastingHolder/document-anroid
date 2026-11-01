package ru.passport.scanner.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConnectionTest {
    @Test
    fun asyncPayloadOwnsExactBytesAndNormalizesMediaType() {
        val callerBytes = byteArrayOf(1, 2, 3)
        val payload = AsyncUploadPayload(callerBytes, " IMAGE/JPEG; charset=binary ")
        callerBytes[0] = 99
        val exposedCopy = payload.copyBytes()
        exposedCopy[1] = 99

        assertTrue(payload.mimeType == "image/jpeg")
        assertTrue(payload.copyBytes().contentEquals(byteArrayOf(1, 2, 3)))
        assertFalse(payload.toString().contains("1, 2, 3"))
    }

    @Test
    fun redactsBearerFromStringRepresentation() {
        val token = testToken()
        val connection = ApiConnection("https://api.example.test", token, "installation-1")

        assertFalse(connection.toString().contains(token))
        assertFalse(connection.toString().contains("api.example.test"))
        assertTrue(connection.toString().contains("[redacted]"))
    }

    @Test
    fun permitsHttpsAndOnlyExplicitDebugHttpForLocalLiteralHosts() {
        ApiConnection("https://api.example.test", testToken(), "prod")
        ApiConnection("http://10.0.2.2:8080", testToken(), "emulator", allowDebugLocalHttp = true)

        assertRejected { ApiConnection("http://10.0.2.2:8080", testToken(), "emulator") }
        assertRejected { ApiConnection("http://public.example.test", testToken(), "bad", allowDebugLocalHttp = true) }
        assertRejected { ApiConnection("http://192.168.1.4", testToken(), "bad") }
    }

    @Test
    fun rejectsCredentialsQueryFragmentAndPathInBaseUrl() {
        listOf(
            "https://user@api.example.test",
            "https://api.example.test?token=secret",
            "https://api.example.test/#fragment",
            "https://api.example.test/prefix",
        ).forEach { url -> assertRejected { ApiConnection(url, testToken(), "id") } }
    }

    @Test
    fun validatesOpaqueCredentialFormatAndConnectionIdentifier() {
        assertRejected { ApiConnection("https://api.example.test", "shared-secret", "id") }
        assertRejected { ApiConnection("https://api.example.test", "pbt1_short", "id") }
        assertRejected { ApiConnection("https://api.example.test", testToken(), "contains spaces") }
    }

    private fun assertRejected(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }

    private fun testToken(): String = "pbt1_" + "A".repeat(43)
}
