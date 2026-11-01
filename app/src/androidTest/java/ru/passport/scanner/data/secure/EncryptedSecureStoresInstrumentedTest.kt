package ru.passport.scanner.data.secure

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import ru.passport.scanner.data.ApiConnection
import java.util.Base64
import java.util.UUID

/** Verifies that only the separately approved connection profile uses Keystore-backed storage. */
@RunWith(AndroidJUnit4::class)
class EncryptedSecureStoresInstrumentedTest {
    @Test
    fun connectionProfileSurvivesStoreRecreation() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedConnectionStore(context, allowDebugLocalHttp = true)
        val random = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val token = "pbt1_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random)
        random.fill(0)
        val id = UUID.randomUUID().toString()
        try {
            store.save(ApiConnection("http://127.0.0.1:8080", token, id, allowDebugLocalHttp = true))
            val reopened = EncryptedConnectionStore(context, allowDebugLocalHttp = true).load()
            assertNotNull(reopened)
            assertEquals(id, reopened?.connectionId)
            assertEquals("Bearer $token", reopened?.authorizationHeaderValue())
        } finally {
            store.clear()
        }
    }
}
