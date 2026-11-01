package ru.passport.scanner

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.passport.scanner.data.ApiConnection

@RunWith(AndroidJUnit4::class)
class NetworkSecurityConfigurationTest {
    @Test
    fun debugCleartextPolicyWorksWithAppLevelPrivateHostValidation() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, targetContext.packageName)

        val policy = NetworkSecurityPolicy.getInstance()
        val otherPrivateHost = "192.168.1.77"
        assertTrue("Debug permits configured local HTTP addresses.", policy.isCleartextTrafficPermitted(otherPrivateHost))
        assertTrue("Loopback HTTP is available to local fixture tests.", policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(
            "The app must reject public HTTP hosts even though debug Network Security allows cleartext sockets.",
            runCatching {
                ApiConnection(
                    "http://not-configured.invalid",
                    "pbt1_" + "A".repeat(43),
                    "invalid-public-host",
                    allowDebugLocalHttp = true,
                )
            }.isFailure,
        )

        val configuredUrl = BuildConfig.OCR_BASE_URL
        if (configuredUrl.isBlank()) return

        val configuredUri = URI(configuredUrl)
        when (configuredUri.scheme.lowercase()) {
            "http" -> assertTrue(
                "Cleartext must be permitted for the configured HTTP API host.",
                policy.isCleartextTrafficPermitted(requireNotNull(configuredUri.host)),
            )
            "https" -> assertFalse(
                "HTTPS configuration must not enable cleartext for its host.",
                policy.isCleartextTrafficPermitted(requireNotNull(configuredUri.host)),
            )
            else -> throw AssertionError("Configured API URL must use HTTP or HTTPS.")
        }
    }
}
