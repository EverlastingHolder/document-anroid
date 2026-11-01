package ru.passport.scanner.export

import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/** Prepares a draft for an email app; the user reviews and sends it manually. */
object PassportEmail {
    const val RECIPIENT = "test@mail.ru"
    const val ARCHIVE_MIME_TYPE = "application/x-7z-compressed"

    /**
     * Creates an email-only chooser. Apps must support both `mailto:` composition and
     * sharing this 7z attachment; generic share targets are deliberately excluded.
     * The caller removes the staged archive if no compatible email app is installed.
     */
    fun createChooserIntent(context: Context, archiveUri: Uri): Intent =
        createChooserIntent(context, archiveUri) { query ->
            context.packageManager.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
        }

    internal fun createChooserIntent(
        context: Context,
        archiveUri: Uri,
        queryActivities: (Intent) -> List<ResolveInfo>,
    ): Intent {
        require(archiveUri.scheme == "content" && archiveUri.authority == "${context.packageName}.exports") {
            "Archive URI is invalid"
        }
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

        fun targetedSendIntent(component: ComponentName) = Intent(Intent.ACTION_SEND).apply {
            this.component = component
            type = ARCHIVE_MIME_TYPE
            putExtra(Intent.EXTRA_EMAIL, arrayOf(RECIPIENT))
            putExtra(Intent.EXTRA_SUBJECT, "Данные документа")
            putExtra(Intent.EXTRA_TEXT, "Во вложении архив с подтверждёнными данными.")
            putExtra(Intent.EXTRA_STREAM, archiveUri)
            clipData = ClipData.newUri(context.contentResolver, "Архив документа", archiveUri)
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
        chooser.clipData = ClipData.newUri(context.contentResolver, "Архив документа", archiveUri)
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return chooser
    }

    class NoEmailHandlerException : IllegalStateException(
        "No installed email app can attach this archive.",
    )
}
