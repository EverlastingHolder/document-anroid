package ru.passport.scanner.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.UUID

/** One process-local encrypted export; it never writes archive bytes to a file. */
object MemoryExportStore {
    private val lock = Any()
    private var current: Entry? = null

    /**
     * Retains a defensive copy of the caller's encrypted archive until replacement,
     * explicit clear, or process death. The caller remains responsible for clearing
     * its input buffer after this method returns.
     */
    fun publish(context: Context, encryptedArchiveBytes: ByteArray, displayName: String = DEFAULT_DISPLAY_NAME): Uri {
        require(encryptedArchiveBytes.isNotEmpty()) { "Export archive is empty." }
        require(DISPLAY_NAME_PATTERN.matches(displayName)) { "Invalid export display name." }
        val appContext = context.applicationContext
        val entry = Entry(UUID.randomUUID().toString(), displayName, encryptedArchiveBytes.copyOf())
        val previous = synchronized(lock) {
            current.also { current = entry }
        }
        previous?.bytes?.fill(0)
        previous?.let { revoke(appContext, uriFor(appContext, it.id)) }
        return uriFor(appContext, entry.id)
    }

    /** Clears and revokes only the matching currently published URI. */
    fun clear(context: Context, uri: Uri): Boolean {
        val appContext = context.applicationContext
        val id = parseIdOrThrow(appContext, uri)
        val removed = synchronized(lock) {
            current?.takeIf { it.id == id }?.also { current = null }
        } ?: return false
        removed.bytes.fill(0)
        revoke(appContext, uriFor(appContext, removed.id))
        return true
    }

    internal fun snapshot(context: Context, uri: Uri): Snapshot? {
        val appContext = context.applicationContext
        val id = parseIdOrThrow(appContext, uri)
        return synchronized(lock) {
            current?.takeIf { it.id == id }?.let { Snapshot(it.displayName, it.bytes.copyOf()) }
        }
    }

    internal fun metadata(context: Context, uri: Uri): Metadata? {
        val appContext = context.applicationContext
        val id = parseIdOrThrow(appContext, uri)
        return synchronized(lock) {
            current?.takeIf { it.id == id }?.let { Metadata(it.displayName, it.bytes.size.toLong()) }
        }
    }

    private fun parseIdOrThrow(context: Context, uri: Uri): String {
        val authority = "${context.packageName}.exports"
        val segments = uri.pathSegments
        if (uri.scheme != "content" || uri.encodedAuthority != authority ||
            uri.encodedQuery != null || uri.encodedFragment != null ||
            segments.size != 2 || segments[0] != PATH_PREFIX ||
            !UUID_PATTERN.matches(segments[1]) || uri.encodedPath != "/$PATH_PREFIX/${segments[1]}"
        ) {
            throw IllegalArgumentException("Invalid export URI.")
        }
        return segments[1]
    }

    private fun uriFor(context: Context, id: String): Uri = Uri.Builder()
        .scheme("content")
        .authority("${context.packageName}.exports")
        .appendPath(PATH_PREFIX)
        .appendPath(id)
        .build()

    private fun revoke(context: Context, uri: Uri) {
        runCatching { context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    internal data class Snapshot(val displayName: String, val bytes: ByteArray)
    internal data class Metadata(val displayName: String, val size: Long)

    private data class Entry(val id: String, val displayName: String, val bytes: ByteArray)

    private const val PATH_PREFIX = "exports"
    private const val DEFAULT_DISPLAY_NAME = "document.7z"
    private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val DISPLAY_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._ -]{0,58}\\.7z")
}
