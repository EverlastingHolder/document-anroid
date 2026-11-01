package ru.passport.scanner.export

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SeekableByteChannel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import ru.passport.scanner.model.PassportSnapshot
import ru.passport.scanner.model.PassportField
import ru.passport.scanner.model.PassportValidator

/** Builds a password-protected 7z entirely in memory from the current valid snapshot. */
class PassportExporter {
    /** Remove only export artifacts from the previous file-based app version. */
    suspend fun cleanupLegacyExportStaging(cacheDir: File) = withContext(Dispatchers.IO) {
        val exportRoot = File(cacheDir, LEGACY_EXPORT_DIRECTORY)
        if (exportRoot.parentFile?.canonicalFile != cacheDir.canonicalFile || exportRoot.isSymbolicLink()) return@withContext
        exportRoot.listFiles().orEmpty()
            .filter { it.name.startsWith(LEGACY_SESSION_PREFIX) && it.isDirectory && !it.isSymbolicLink() && it.canonicalFile.parentFile == exportRoot.canonicalFile }
            .forEach { it.deleteRecursively() }
        exportRoot.delete()
    }

    suspend fun createArchive(snapshot: PassportSnapshot): ByteArray = withContext(Dispatchers.IO) {
        val canonicalSnapshot = snapshot.defensiveCopy()
        require(PassportValidator.validate(canonicalSnapshot.values).isEmpty()) {
            "Passport snapshot contains invalid values"
        }

        val textBytes = PassportTextSerializer.serialize(canonicalSnapshot).toByteArray(Charsets.UTF_8)
        writeEncryptedArchive(textBytes)
    }

    private fun writeEncryptedArchive(textBytes: ByteArray): ByteArray {
        val channel = BoundedMemorySeekableByteChannel(MAX_ARCHIVE_BYTES)
        val password = ARCHIVE_PASSWORD.toCharArray()
        try {
            var archive: ByteArray? = null
            SevenZOutputFile(channel, password).use { output ->
                val entry = SevenZArchiveEntry().apply {
                    name = TEXT_ENTRY_NAME
                    size = textBytes.size.toLong()
                }
                output.putArchiveEntry(entry)
                output.write(textBytes)
                output.closeArchiveEntry()
                output.finish()
                archive = channel.copyBytes()
            }
            return requireNotNull(archive)
        } finally {
            textBytes.fill(0)
            password.fill('\u0000')
            channel.close()
        }
    }

    private fun File.isSymbolicLink(): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(toPath()) }.getOrDefault(true)

    private companion object {
        const val MAX_ARCHIVE_BYTES = 1024 * 1024
        const val LEGACY_EXPORT_DIRECTORY = "exports"
        const val LEGACY_SESSION_PREFIX = "passport-export-"
        const val TEXT_ENTRY_NAME = "document.txt"
        const val ARCHIVE_PASSWORD = "123"
    }
}

/** A bounded seekable buffer for 7z's end-of-archive index; its backing bytes are erased on close. */
internal class BoundedMemorySeekableByteChannel(private val maxBytes: Int) : SeekableByteChannel {
    private var storage = ByteArray(4096)
    private var size = 0
    private var cursor = 0
    private var open = true

    override fun read(dst: ByteBuffer): Int {
        ensureOpen()
        if (cursor >= size) return -1
        val count = minOf(dst.remaining(), size - cursor)
        dst.put(storage, cursor, count)
        cursor += count
        return count
    }

    override fun write(src: ByteBuffer): Int {
        ensureOpen()
        val count = src.remaining()
        val end = cursor.toLong() + count
        if (end > maxBytes) throw IOException("In-memory export exceeds its size limit")
        ensureCapacity(end.toInt())
        src.get(storage, cursor, count)
        cursor += count
        size = maxOf(size, cursor)
        return count
    }

    override fun position(): Long { ensureOpen(); return cursor.toLong() }

    override fun position(newPosition: Long): SeekableByteChannel {
        ensureOpen()
        if (newPosition < 0 || newPosition > maxBytes) throw IllegalArgumentException("Invalid channel position")
        cursor = newPosition.toInt()
        return this
    }

    override fun size(): Long { ensureOpen(); return size.toLong() }

    override fun truncate(newSize: Long): SeekableByteChannel {
        ensureOpen()
        if (newSize < 0 || newSize > maxBytes) throw IllegalArgumentException("Invalid channel size")
        if (newSize < size) {
            storage.fill(0, newSize.toInt(), size)
            size = newSize.toInt()
            cursor = minOf(cursor, size)
        }
        return this
    }

    override fun isOpen(): Boolean = open

    override fun close() {
        if (open) {
            storage.fill(0)
            storage = ByteArray(0)
            size = 0
            cursor = 0
            open = false
        }
    }

    fun copyBytes(): ByteArray { ensureOpen(); return storage.copyOf(size) }

    private fun ensureCapacity(required: Int) {
        if (required <= storage.size) return
        var capacity = storage.size
        while (capacity < required) capacity = minOf(maxBytes, capacity * 2)
        storage = storage.copyOf(capacity)
    }

    private fun ensureOpen() { if (!open) throw ClosedChannelException() }
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
