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

@RunWith(AndroidJUnit4::class)
class NetworkSecurityConfigurationTest {
    @Test
    fun cleartextIsAllowedOnlyForConfiguredHttpHost() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(BuildConfig.APPLICATION_ID, targetContext.packageName)

        val policy = NetworkSecurityPolicy.getInstance()
        val unconfiguredHost = "not-configured.invalid"
        assertFalse(
            "Cleartext must stay disabled for hosts outside the configured API host.",
            policy.isCleartextTrafficPermitted(unconfiguredHost),
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
