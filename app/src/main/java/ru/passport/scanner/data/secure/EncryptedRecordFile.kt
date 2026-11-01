package ru.passport.scanner.data.secure

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Private storage boundary so JVM tests can exercise stores without Android framework stubs. */
internal interface EncryptedRecordFile {
    val lockKey: String
    fun read(maxBytes: Int): ByteArray?
    fun write(bytes: ByteArray, maxBytes: Int)
    fun delete()
}

internal class AtomicEncryptedRecordFile(private val base: File) : EncryptedRecordFile {
    private val atomicFile = AtomicFile(base)
    override val lockKey: String = base.absolutePath

    override fun read(maxBytes: Int): ByteArray? {
        try {
            validatePrivateRecordParent(base)
            if (!base.exists() && !File(base.path + ".bak").exists()) return null
            validatePrivateRecordPath(base)
            val backup = File(base.path + ".bak")
            if (backup.exists()) validatePrivateRecordPath(backup)
            return atomicFile.openRead().use { input -> input.readBounded(maxBytes) }
        } catch (_: SecureStorageNeedsResetException) {
            throw SecureStorageNeedsResetException()
        } catch (_: Exception) {
            throw SecureStorageUnavailableException()
        }
    }

    override fun write(bytes: ByteArray, maxBytes: Int) {
        if (bytes.size > maxBytes) throw SecureStorageUnavailableException()
        try {
            validatePrivateRecordParent(base)
            val output = atomicFile.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                atomicFile.finishWrite(output)
            } catch (_: Exception) {
                atomicFile.failWrite(output)
                throw SecureStorageUnavailableException()
            }
            if (!base.setReadable(false, false) || !base.setWritable(false, false) || !base.setExecutable(false, false) ||
                !base.setReadable(true, true) || !base.setWritable(true, true)
            ) {
                throw SecureStorageUnavailableException()
            }
        } catch (_: SecureStorageUnavailableException) {
            throw SecureStorageUnavailableException()
        } catch (_: Exception) {
            throw SecureStorageUnavailableException()
        }
    }

    override fun delete() {
        try {
            validatePrivateRecordParent(base)
            if (base.exists() || File(base.path + ".bak").exists()) {
                val backup = File(base.path + ".bak")
                if (backup.exists()) validatePrivateRecordPath(backup)
            }
            atomicFile.delete()
        } catch (_: Exception) {
            throw SecureStorageUnavailableException()
        }
    }
}

internal fun privateRecordFile(
    context: Context,
    name: String,
    directoryName: String = PRIVATE_DIRECTORY_NAME,
): EncryptedRecordFile {
    require(
        FILE_NAME_PATTERN.matches(name) && name != "." && name != ".." &&
            DIRECTORY_NAME_PATTERN.matches(directoryName) && directoryName != "." && directoryName != "..",
    ) {
        throw SecureStorageUnavailableException()
    }
    val noBackupDirectory = try {
        context.noBackupFilesDir
    } catch (_: Exception) {
        throw SecureStorageUnavailableException()
    }
    try {
        validatePlatformNoBackupDirectory(noBackupDirectory)
    } catch (_: SecureStorageUnavailableException) {
        throw SecureStorageUnavailableException()
    } catch (_: Exception) {
        throw SecureStorageUnavailableException()
    }
    val directory = privateChildDirectory(noBackupDirectory, directoryName)
    val target = File(directory, name)
    validatePrivateRecordParent(target)
    return AtomicEncryptedRecordFile(target)
}

private fun privateChildDirectory(parent: File, name: String): File {
    val directory = File(parent, name)
    try {
        Os.mkdir(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
    } catch (failure: ErrnoException) {
        // An existing path is accepted only after lstat verifies it is our private directory.
        if (failure.errno != OsConstants.EEXIST) throw SecureStorageUnavailableException()
    } catch (_: Exception) {
        throw SecureStorageUnavailableException()
    }
    validatePrivateDirectory(directory)
    return directory
}

private fun validatePlatformNoBackupDirectory(directory: File) {
    val stat = Os.lstat(directory.absolutePath)
    if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() ||
        (stat.st_mode and OsConstants.S_IRWXU) != OsConstants.S_IRWXU
    ) {
        throw SecureStorageUnavailableException()
    }
    // Android owns this platform directory and may expose mode 0771. We never change it.
}

private fun validatePrivateDirectory(directory: File) {
    val stat = Os.lstat(directory.absolutePath)
    if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() ||
        (stat.st_mode and OsConstants.S_IRWXU) != OsConstants.S_IRWXU ||
        (stat.st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO)) != 0
    ) {
        throw SecureStorageUnavailableException()
    }
    val parent = directory.parentFile ?: throw SecureStorageUnavailableException()
    if (directory.canonicalFile.parentFile != parent.canonicalFile) throw SecureStorageUnavailableException()
}

private fun validatePrivateRecordParent(file: File) {
    val parent = file.parentFile ?: throw SecureStorageUnavailableException()
    try {
        val stat = Os.lstat(parent.absolutePath)
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() ||
            (stat.st_mode and (OsConstants.S_IRWXG or OsConstants.S_IRWXO)) != 0 ||
            (stat.st_mode and OsConstants.S_IRWXU) != OsConstants.S_IRWXU
        ) {
            throw SecureStorageUnavailableException()
        }
        val parentDirectory = parent.parentFile ?: throw SecureStorageUnavailableException()
        if (parent.canonicalFile.parentFile != parentDirectory.canonicalFile) throw SecureStorageUnavailableException()
        if (file.exists() || File(file.path + ".bak").exists()) validatePrivateRecordPath(file)
    } catch (_: SecureStorageUnavailableException) {
        throw SecureStorageUnavailableException()
    } catch (_: Exception) {
        throw SecureStorageUnavailableException()
    }
}

private fun validatePrivateRecordPath(file: File) {
    try {
        val stat = Os.lstat(file.absolutePath)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() || (stat.st_mode and 0x3f) != 0) {
            throw SecureStorageNeedsResetException()
        }
    } catch (_: SecureStorageNeedsResetException) {
        throw SecureStorageNeedsResetException()
    } catch (_: Exception) {
        // AtomicFile may be recovering a backup while the base path is absent.
        if (file.exists()) throw SecureStorageNeedsResetException()
    }
}

private const val PRIVATE_DIRECTORY_NAME = "passport-secure-v1"
private val PRIVATE_DIRECTORY_MODE = OsConstants.S_IRWXU
private val FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
private val DIRECTORY_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,80}")

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (output.size() > maxBytes - count) throw SecureStorageNeedsResetException()
        output.write(buffer, 0, count)
    }
    buffer.fill(0)
    return output.toByteArray()
}
