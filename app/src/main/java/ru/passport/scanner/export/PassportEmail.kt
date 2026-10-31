package ru.passport.scanner.export

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/** Prepares a draft for an email app; the user reviews and sends it manually. */
object PassportEmail {
    const val RECIPIENT = "test@mail.ru"
    const val ARCHIVE_MIME_TYPE = "application/x-7z-compressed"

    /**
     * Creates an email-only chooser. Apps must support both `mailto:` composition and
     * sharing this 7z attachment; generic share targets are deliberately excluded.
     * The archive remains in cache if no compatible email app is installed.
     */
    fun createChooserIntent(context: Context, archive: File): Intent =
        createChooserIntent(context, archive) { query ->
            context.packageManager.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
        }

    internal fun createChooserIntent(
        context: Context,
        archive: File,
        queryActivities: (Intent) -> List<ResolveInfo>,
    ): Intent {
        require(archive.isFile) { "Archive does not exist" }
        val mailPackages = queryActivities(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
        ).mapNotNull { it.activityInfo?.packageName }.toSet()
        val compatibleSendTargets = queryActivities(
            Intent(Intent.ACTION_SEND).setType(ARCHIVE_MIME_TYPE),
        ).filter { it.activityInfo?.packageName in mailPackages }
            .distinctBy { it.activityInfo?.let { info -> "${info.packageName}/${info.name}" } }
            .mapNotNull { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@mapNotNull null
                ComponentName(activity.packageName, activity.name)
            }

        if (compatibleSendTargets.isEmpty()) throw NoEmailHandlerException()

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            archive,
        )
        fun targetedSendIntent(component: ComponentName) = Intent(Intent.ACTION_SEND).apply {
            this.component = component
            type = ARCHIVE_MIME_TYPE
            putExtra(Intent.EXTRA_EMAIL, arrayOf(RECIPIENT))
            putExtra(Intent.EXTRA_SUBJECT, "Данные документа")
            putExtra(Intent.EXTRA_TEXT, "Во вложении архив с подтверждёнными данными.")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "Архив документа", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(
            targetedSendIntent(compatibleSendTargets.first()),
            "Отправить архив по почте",
        ).putExtra(
            Intent.EXTRA_INITIAL_INTENTS,
            compatibleSendTargets.drop(1).map(::targetedSendIntent).toTypedArray(),
        )
        // Keep the read grant on the chooser as well as its explicit target intents.
        chooser.clipData = ClipData.newUri(context.contentResolver, "Архив документа", uri)
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return chooser
    }

    class NoEmailHandlerException : IllegalStateException(
        "No installed email app can attach this archive. The archive is still saved; install an email app and retry.",
    )
}
