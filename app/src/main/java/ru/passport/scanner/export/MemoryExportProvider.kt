package ru.passport.scanner.export

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.io.IOException

/** Read-only ContentProvider for the single in-memory encrypted export. */
class MemoryExportProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? {
        val providerContext = context ?: return null
        return if (MemoryExportStore.metadata(providerContext, uri) != null) ARCHIVE_MIME_TYPE else null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (selection != null || selectionArgs != null || sortOrder != null) {
            throw IllegalArgumentException("Export query parameters are not supported.")
        }
        val columns = projection?.toList() ?: DEFAULT_COLUMNS
        if (columns.any { it !in SUPPORTED_COLUMNS }) {
            throw IllegalArgumentException("Unsupported export metadata column.")
        }
        val providerContext = context ?: return null
        val metadata = MemoryExportStore.metadata(providerContext, uri) ?: return null
        return MatrixCursor(columns.toTypedArray(), 1).apply {
            addRow(columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> metadata.displayName
                    OpenableColumns.SIZE -> metadata.size
                    else -> throw IllegalArgumentException("Unsupported export metadata column.")
                }
            })
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Export is read-only.")
        val providerContext = context ?: throw FileNotFoundException("Export is unavailable.")
        val snapshot = MemoryExportStore.snapshot(providerContext, uri)
            ?: throw FileNotFoundException("Export is unavailable.")
        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (failure: IOException) {
            snapshot.bytes.fill(0)
            throw failure
        }
        val writer = Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    output.write(snapshot.bytes)
                }
            } catch (_: IOException) {
                // A revoked or closed recipient pipe needs no retry or logging.
            } finally {
                snapshot.bytes.fill(0)
            }
        }, "memory-export-pipe").apply { isDaemon = true }
        try {
            writer.start()
        } catch (failure: RuntimeException) {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            snapshot.bytes.fill(0)
            throw FileNotFoundException("Export is unavailable.").also { it.initCause(failure) }
        }
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Export provider is read-only.")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Export provider is read-only.")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Export provider is read-only.")

    private companion object {
        const val ARCHIVE_MIME_TYPE = "application/x-7z-compressed"
        val DEFAULT_COLUMNS = listOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val SUPPORTED_COLUMNS = setOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    }
}
