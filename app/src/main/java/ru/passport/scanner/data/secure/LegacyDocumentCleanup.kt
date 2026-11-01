package ru.passport.scanner.data.secure

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.security.KeyStore

/** Deletes only the former pending-job record and its AtomicFile siblings/key. */
object LegacyDocumentCleanup {
    fun clearPendingJobRecord(context: Context) {
        val noBackup = context.noBackupFilesDir
        val directory = File(noBackup, "passport-secure-v1")
        try {
            val stat = Os.lstat(directory.absolutePath)
            if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid() ||
                directory.canonicalFile.parentFile != noBackup.canonicalFile
            ) return
            listOf("pending-scan-v1.enc", "pending-scan-v1.enc.bak", "pending-scan-v1.enc.new").forEach { name ->
                val target = File(directory, name)
                if (target.canonicalFile.parentFile != directory.canonicalFile || !target.exists()) return@forEach
                val targetStat = Os.lstat(target.absolutePath)
                if (OsConstants.S_ISREG(targetStat.st_mode) && targetStat.st_uid == Process.myUid()) target.delete()
            }
        } catch (_: Exception) {
            // Cleanup is best-effort and never expands beyond these exact paths.
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(LEGACY_PENDING_KEY_ALIAS)) keyStore.deleteEntry(LEGACY_PENDING_KEY_ALIAS)
        } catch (_: Exception) {
            // Never touch the separate API connection alias.
        }
    }

    private const val LEGACY_PENDING_KEY_ALIAS = "ru.passport.scanner.pending-scan.v1"
}
