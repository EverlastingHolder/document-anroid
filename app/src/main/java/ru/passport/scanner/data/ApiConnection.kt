package ru.passport.scanner.data

import java.net.URI
import java.util.Base64

/** Immutable credentials captured for one request sequence; deliberately not a data class. */
class ApiConnection(
    baseUrl: String,
    bearerToken: String,
    val connectionId: String,
    allowDebugLocalHttp: Boolean = false,
) {
    val baseUrl: String = validateBaseUrl(baseUrl, allowDebugLocalHttp)
    private val token: String = bearerToken.also(::validateToken)

    internal fun authorizationHeaderValue(): String = "Bearer $token"
    internal fun bearerTokenForPersistence(): String = token

    override fun toString(): String = "ApiConnection([redacted])"

    companion object {
        private val TOKEN_PATTERN = Regex("pbt1_[A-Za-z0-9_-]{43}")
        private val CONNECTION_ID_PATTERN = Regex("[A-Za-z0-9._:-]{1,128}")

        private fun validateToken(value: String) {
            require(TOKEN_PATTERN.matches(value)) { "Invalid API credential." }
            val encoded = value.removePrefix("pbt1_")
            val decoded = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull()
            require(decoded?.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded) {
                "Invalid API credential."
            }
        }

        private fun validateBaseUrl(value: String, allowDebugLocalHttp: Boolean): String {
            val trimmed = value.trim().trimEnd('/')
            val uri = runCatching { URI(trimmed) }.getOrNull()
                ?: throw IllegalArgumentException("Invalid API address.")
            val scheme = uri.scheme?.lowercase()
            val secure = scheme == "https"
            val localHttp = scheme == "http" && allowDebugLocalHttp && isAllowedLocalHost(uri.host)
            require((secure || localHttp) && !uri.isOpaque && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.port in -1..65535 && uri.port != 0 && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            ) { "Invalid API address or transport security policy." }
            return trimmed
        }

        private fun isAllowedLocalHost(host: String?): Boolean {
            if (host == null) return false
            val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
            if (normalized == "localhost" || normalized == "::1") return true
            val octets = normalized.split('.')
            if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || it.any { ch -> !ch.isDigit() } }) {
                return false
            }
            val bytes = octets.map { it.toIntOrNull()?.takeIf { n -> n in 0..255 } ?: return false }
            return bytes[0] == 10 || bytes[0] == 127 ||
                (bytes[0] == 192 && bytes[1] == 168) ||
                (bytes[0] == 172 && bytes[1] in 16..31) ||
                bytes == listOf(10, 0, 2, 2)
        }
    }

    init {
        require(CONNECTION_ID_PATTERN.matches(connectionId)) { "Invalid API connection identifier." }
    }
}
