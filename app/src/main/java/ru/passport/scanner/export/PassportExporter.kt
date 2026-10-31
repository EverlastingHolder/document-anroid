package ru.passport.scanner.export

import java.io.File
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValidator

/** Builds a password-protected 7z containing only the current valid passport snapshot. */
class PassportExporter(private val cacheDir: File) {
    /** Call at app startup to remove expired export sessions from the app cache. */
    suspend fun cleanupExpiredExports() = withContext(Dispatchers.IO) {
        File(cacheDir, EXPORT_DIRECTORY).takeIf { it.isDirectory }?.let { purgeExpiredExports(it) }
    }

    suspend fun createArchive(snapshot: PassportSnapshot): File = withContext(Dispatchers.IO) {
        val canonicalSnapshot = snapshot.defensiveCopy()
        require(PassportValidator.validate(canonicalSnapshot.values).isEmpty()) {
            "Passport snapshot contains invalid values"
        }

        val exportRoot = File(cacheDir, EXPORT_DIRECTORY)
        check(exportRoot.mkdirs() || exportRoot.isDirectory) { "Cannot create export cache" }
        purgeExpiredExports(exportRoot)

        val sessionDir = File(exportRoot, "$SESSION_PREFIX${UUID.randomUUID()}")
        check(sessionDir.mkdir()) { "Cannot create export session" }
        val temporaryArchive = File(sessionDir, "$ARCHIVE_NAME.tmp")
        val archive = File(sessionDir, ARCHIVE_NAME)
        try {
            val text = PassportTextSerializer.serialize(canonicalSnapshot)
            writeEncryptedArchive(temporaryArchive, text.toByteArray(Charsets.UTF_8))
            try {
                Files.move(temporaryArchive.toPath(), archive.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryArchive.toPath(), archive.toPath())
            }
            archive.setLastModified(System.currentTimeMillis())
            archive
        } catch (failure: Throwable) {
            sessionDir.deleteRecursively()
            throw failure
        } finally {
            temporaryArchive.delete()
        }
    }

    private fun writeEncryptedArchive(destination: File, textBytes: ByteArray) {
        SevenZOutputFile(destination, ARCHIVE_PASSWORD.toCharArray()).use { output ->
            val entry = SevenZArchiveEntry().apply {
                name = TEXT_ENTRY_NAME
                size = textBytes.size.toLong()
            }
            output.putArchiveEntry(entry)
            output.write(textBytes)
            output.closeArchiveEntry()
            output.finish()
        }
    }

    private fun purgeExpiredExports(exportRoot: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_MILLIS
        exportRoot.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith(SESSION_PREFIX) && it.lastModified() < cutoff }
            .forEach { it.deleteRecursively() }
    }

    private companion object {
        const val EXPORT_DIRECTORY = "exports"
        const val SESSION_PREFIX = "passport-export-"
        const val ARCHIVE_NAME = "document.7z"
        const val TEXT_ENTRY_NAME = "document.txt"
        const val ARCHIVE_PASSWORD = "123"
        // Keep email attachments in the app cache for 24 hours before best-effort cleanup.
        const val RETENTION_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** Stable, Russian-labeled UTF-8 representation of the current canonical snapshot. */
internal object PassportTextSerializer {
    fun serialize(snapshot: PassportSnapshot): String = buildString {
        PassportField.entries.forEach { field ->
            append(field.label)
            append(": ")
            append(
                when (field) {
                    PassportField.GENDER -> when (snapshot.values.getValue(field)) {
                        "M" -> "МУЖ."
                        "F" -> "ЖЕН."
                        else -> snapshot.values.getValue(field)
                    }
                    else -> snapshot.values.getValue(field)
                },
            )
            append('\n')
        }
    }
}
